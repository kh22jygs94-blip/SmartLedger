package com.example.smartledger.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统级崩溃 / ANR / tombstone 事件读取器。
 *
 * 直接读 /data/tombstones、/data/anr 需要 root，普通应用拿不到；
 * 但系统会把这些事件同步到 dropbox，`dumpsys dropbox --print` 里就有正文。
 * 该命令需要 android.permission.DUMP，只能在 debug 包上通过 adb 授予。
 */
object CrashEventReader {

    const val PERMISSION = "android.permission.DUMP"

    /** DropBoxManagerService 的 dump 还会校验用量访问权限，两个缺一不可 */
    private const val USAGE_PERMISSION = "android.permission.PACKAGE_USAGE_STATS"

    data class Event(
        val time: String,
        val tag: String,
        val label: String,
        val bytes: Int,
        val body: String
    )

    private val HEADER = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) (\S+) \((\w+), (\d+) bytes\)$""")

    /** 只保留和「出了什么问题」有关的事件，storage_trim 之类的正常记录不展示 */
    private val LABELS = mapOf(
        "data_app_crash" to "应用崩溃",
        "data_app_anr" to "应用无响应",
        "data_app_native_crash" to "Native 崩溃",
        "data_app_wtf" to "WTF 异常",
        "system_app_crash" to "系统应用崩溃",
        "system_app_anr" to "系统应用无响应",
        "system_server_crash" to "系统服务崩溃",
        "system_server_anr" to "系统服务无响应",
        "system_server_watchdog" to "看门狗重启",
        "SYSTEM_TOMBSTONE" to "Tombstone",
        "SYSTEM_RESTART" to "系统重启",
        "SYSTEM_LAST_KMSG" to "内核消息",
        "SYSTEM_BOOT" to "系统启动"
    )

    fun canRead(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(USAGE_PERMISSION) == PackageManager.PERMISSION_GRANTED

    suspend fun read(limit: Int = 40): List<Event> = withContext(Dispatchers.IO) {
        val process = runCatching {
            ProcessBuilder("/system/bin/dumpsys", "dropbox", "--print")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return@withContext emptyList()

        val events = ArrayList<Event>()
        var current: Event? = null
        val body = StringBuilder()

        fun flush() {
            current?.let { head ->
                events += head.copy(body = body.toString().trim().take(MAX_BODY_CHARS))
            }
            body.setLength(0)
        }

        runCatching {
            process.inputStream.bufferedReader().use { reader ->
                var lines = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    // --print 会把所有条目的正文都吐出来，限制总量避免极端情况下吃满内存
                    if (++lines > MAX_LINES) break
                    val match = HEADER.find(line)
                    if (match != null) {
                        flush()
                        val (time, tag, _, size) = match.destructured
                        current = if (LABELS.containsKey(tag)) {
                            Event(time, tag, LABELS.getValue(tag), size.toIntOrNull() ?: 0, "")
                        } else {
                            null
                        }
                        if (events.size > limit * 2) events.removeAt(0)
                    } else if (current != null && body.length < MAX_BODY_CHARS) {
                        body.appendLine(line)
                    }
                }
            }
        }
        flush()
        runCatching { process.waitFor() }
        events.takeLast(limit).reversed()
    }

    private const val MAX_BODY_CHARS = 4000
    private const val MAX_LINES = 300_000

    /** 便于在界面里直接复制；DUMP 与用量访问两条必须都执行 */
    fun grantCommands(packageName: String): List<String> = listOf(
        "adb shell pm grant $packageName $PERMISSION",
        "adb shell pm grant $packageName $USAGE_PERMISSION",
        "adb shell appops set $packageName GET_USAGE_STATS allow"
    )

    /** 读取系统日志缓冲区用的另一条授权，按需使用 */
    fun logcatGrantCommand(packageName: String): String =
        "adb shell pm grant $packageName ${Manifest.permission.READ_LOGS}"
}
