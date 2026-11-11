package com.example.smartledger.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 一条操作记录：毫秒时间戳、类别、内容；fileName + lineIndex + raw 用于定位并删除单条 */
data class LogEntry(
    val time: Long,
    val category: String,
    val message: String,
    val fileName: String = "",
    val lineIndex: Int = -1,
    val raw: String = ""
)

/**
 * 应用操作日志：把「用了什么权限、做了什么」按天写进内部存储。
 *
 * 设计成文件而不是数据库：
 * 1. 截图、OCR 都是高频事件，追加写文件比每次写库轻；
 * 2. 日志本身也是「应用文件」，文件浏览页能直接看到，前后对得上。
 *
 * 写入走单线程队列，调用方（截图回调在主线程）不会被磁盘 IO 拖慢。
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val DIR_NAME = "logs"
    private const val FILE_PREFIX = "app-"
    private const val FILE_SUFFIX = ".log"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val KEEP_DAYS = 7

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "app-log").apply { isDaemon = true }
    }

    @Volatile
    private var logDir: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, DIR_NAME)
        logDir = dir
        installCrashHandler()
        executor.execute {
            runCatching {
                dir.mkdirs()
                pruneOldFiles(dir)
            }.onFailure { Log.w(TAG, "初始化日志目录失败：${it.message}") }
        }
    }

    /**
     * 全局未捕获异常兜底：崩溃时先把异常写进本地日志，再交回系统默认处理器。
     *
     * 崩溃发生在任意线程，不能走 [log] 的异步队列（进程可能立刻就没了），
     * 这里同步写文件；异常信息压成单行，保证日志页按行解析不会错位。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeSync(
                    "崩溃",
                    "线程 ${thread.name} 未捕获异常：${throwable.javaClass.name}: ${throwable.message}"
                )
                val stack = Log.getStackTraceString(throwable).replace("\n", " ").take(4000)
                writeSync("崩溃", stack)
            }
            previous?.uncaughtException(thread, throwable)
                ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** 同步写一行，只在崩溃时用；用独立的格式化器避开与日志线程的竞争 */
    private fun writeSync(category: String, message: String) {
        val dir = logDir ?: return
        val now = System.currentTimeMillis()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date(now))
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(now))
        runCatching {
            dir.mkdirs()
            File(dir, FILE_PREFIX + day + FILE_SUFFIX).appendText("$time|$category|$message\n")
        }.onFailure { Log.w(TAG, "写崩溃日志失败：${it.message}") }
    }

    /** 记一条日志。类别用于界面着色，例如「截图」「识别」「入账」「服务」 */
    fun log(category: String, message: String) {
        val dir = logDir ?: return
        val now = System.currentTimeMillis()
        val line = "${timeFormat.format(Date(now))}|$category|$message"
        executor.execute {
            runCatching {
                val file = File(dir, FILE_PREFIX + dateFormat.format(Date(now)) + FILE_SUFFIX)
                if (file.exists() && file.length() > MAX_FILE_BYTES) return@runCatching
                file.appendText(line + "\n")
            }.onFailure { Log.w(TAG, "写日志失败：${it.message}") }
        }
    }

    /** 按时间倒序读取最近的日志，最多 maxEntries 条 */
    fun readRecent(maxEntries: Int): List<LogEntry> {
        val dir = logDir ?: return emptyList()
        val files = dir.listFiles { file ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
        }?.sortedByDescending { it.name } ?: return emptyList()

        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
        val result = ArrayList<LogEntry>(maxEntries)
        for (file in files) {
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            for (index in lines.indices.reversed()) {
                val line = lines[index]
                val parts = line.split("|", limit = 3)
                if (parts.size < 3) continue
                val time = runCatching { format.parse(parts[0])?.time }.getOrNull() ?: continue
                result.add(LogEntry(time, parts[1], parts[2], file.name, index, line))
                if (result.size >= maxEntries) return result
            }
        }
        return result
    }

    /** 删除一条日志：按文件 + 行号定位，行内容对不上时退回内容匹配，避免误删 */
    fun delete(entry: LogEntry) {
        val dir = logDir ?: return
        // 排队到写日志线程再改文件，避免和正在追加的日志相互覆盖
        executor.submit {
            runCatching {
                val file = File(dir, entry.fileName)
                if (!file.isFile) return@runCatching
                val lines = file.readLines().toMutableList()
                val index = if (entry.lineIndex in lines.indices && lines[entry.lineIndex] == entry.raw) {
                    entry.lineIndex
                } else {
                    lines.indexOf(entry.raw)
                }
                if (index < 0) return@runCatching
                lines.removeAt(index)
                file.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
            }.onFailure { Log.w(TAG, "删除日志失败：${it.message}") }
        }.get()
    }

    /** 删除全部日志文件，重置账本时一起清理 */
    fun clear() {
        val dir = logDir ?: return
        executor.submit {
            runCatching { dir.listFiles()?.forEach { it.delete() } }
                .onFailure { Log.w(TAG, "清空日志失败：${it.message}") }
        }.get()
    }

    private fun pruneOldFiles(dir: File) {
        val files = dir.listFiles() ?: return
        val deadline = System.currentTimeMillis() - KEEP_DAYS * 24L * 60L * 60L * 1000L
        files.forEach { file ->
            if (file.isFile && file.lastModified() < deadline) file.delete()
        }
    }
}
