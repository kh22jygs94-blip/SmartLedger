package com.example.smartledger.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** 一次采样：全部来自 /proc 与系统 API，不需要 root */
data class DiagSample(
    val time: Long,
    val cpuPercent: Float,
    val memUsedMb: Int,
    val memTotalMb: Int,
    val appPssMb: Int,
    val fps: Float
)

/**
 * 系统指标的持续采样器。
 *
 * 默认只在应用处于前台时采样，避免后台偷偷耗电；
 * 用户在「诊断」页打开「后台也刷新」后，才随常驻前台服务一起继续采样。
 * 采样源全部是普通应用可读的：/proc/stat、ActivityManager.MemoryInfo、Debug.getMemoryInfo。
 */
object DiagnosticsMonitor {

    private const val PREFS = "diagnostics"
    private const val KEY_BACKGROUND = "background_refresh"
    private const val KEY_ENABLED = "enabled"
    private const val MAX_POINTS = 90

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private val _samples = MutableStateFlow<List<DiagSample>>(emptyList())
    val samples: StateFlow<List<DiagSample>> = _samples

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** 总开关：关闭后前台、后台都不再采样，直到用户手动重新开启 */
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private var appContext: Context? = null
    private var lastCpuTicks = -1L
    private var lastCpuAt = 0L
    private var lastPssMb = 0
    private var tick = 0

    @Volatile
    private var foreground = false

    @Volatile
    private var fps = 0f

    fun init(context: Context) {
        appContext = context.applicationContext
        _enabled.value = isEnabled(context)
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    /** 诊断总开关：关掉时立刻停止采样并清空累计的 CPU 基线 */
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
        _enabled.value = value
        sync()
    }

    fun isBackgroundRefreshEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKGROUND, false)

    fun setBackgroundRefresh(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND, enabled)
            .apply()
        sync()
    }

    /** 由 Activity 生命周期驱动；界面不在前台就不再假装「实时」 */
    fun setForeground(value: Boolean) {
        foreground = value
        sync()
    }

    /** 界面每秒算一次帧率，交给采样器一起存档 */
    fun submitFps(value: Float) {
        fps = value
    }

    private fun sync() {
        val context = appContext ?: return
        val shouldRun = _enabled.value && (foreground || isBackgroundRefreshEnabled(context))
        if (shouldRun) {
            start(context)
        } else {
            stop()
            // 停采样后重置基线，重新开启时第一帧不会算出跨了几分钟的假占用率
            lastCpuTicks = -1L
        }
    }

    private fun start(context: Context) {
        if (loop?.isActive == true) return
        _running.value = true
        loop = scope.launch {
            while (isActive) {
                runCatching { sample(context) }
                delay(1000)
            }
        }
    }

    private fun stop() {
        loop?.cancel()
        loop = null
        _running.value = false
    }

    private fun sample(context: Context) {
        tick++
        val cpu = readAppCpuPercent()
        val memory = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        memory?.getMemoryInfo(info)

        // PSS 读取稍贵，每 3 秒一次就够
        if (tick % 3 == 1) {
            lastPssMb = runCatching {
                val stats = Debug.MemoryInfo()
                Debug.getMemoryInfo(stats)
                stats.totalPss / 1024
            }.getOrDefault(lastPssMb)
        }

        val usedMb = ((info.totalMem - info.availMem) / 1024 / 1024).toInt()
        val totalMb = (info.totalMem / 1024 / 1024).toInt()
        val snapshot = DiagSample(
            time = System.currentTimeMillis(),
            cpuPercent = cpu,
            memUsedMb = usedMb,
            memTotalMb = totalMb,
            appPssMb = lastPssMb,
            fps = fps
        )
        _samples.value = (_samples.value + snapshot).takeLast(MAX_POINTS)
    }

    /** /proc/self/stat 是累计 CPU 节拍，两次差值才是这段时间的占用率 */
    private fun readAppCpuPercent(): Float {
        val stat = runCatching { File("/proc/self/stat").readText() }.getOrNull() ?: return 0f
        // comm 字段可能带空格和括号，从最后一个 ')' 之后再切分才是稳定的
        val close = stat.lastIndexOf(')')
        if (close < 0) return 0f
        val fields = stat.substring(close + 2).trim().split(Regex("\\s+"))
        // 从 state 起算，utime 是第 14 个字段，对应下标 11；stime 为下标 12
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return 0f
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return 0f
        val ticks = utime + stime
        val now = System.currentTimeMillis()
        val previousTicks = lastCpuTicks
        val previousAt = lastCpuAt
        lastCpuTicks = ticks
        lastCpuAt = now
        if (previousTicks < 0 || now <= previousAt) return 0f
        val cpuMs = (ticks - previousTicks) * 1000.0 / CPU_TICKS_PER_SECOND
        return ((cpuMs / (now - previousAt)) * 100).toFloat().coerceIn(0f, 100f)
    }

    private const val CPU_TICKS_PER_SECOND = 100.0
}
