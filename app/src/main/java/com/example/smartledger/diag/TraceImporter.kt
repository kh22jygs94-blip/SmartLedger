package com.example.smartledger.diag

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 外部 trace 文件解析。
 *
 * 非 root 设备上没法自己开 ftrace、跑 simpleperf、挂 eBPF，
 * 但可以把别人导出的文本 trace 拉进来做统计——
 * 支持 ftrace 的 `trace` 文本格式（每行带 "进程-号 [cpu] 标志 时间戳: 事件: 参数"）。
 * 二进制格式（perfetto / perf.data）解析成本高，这里明确拒绝并给出提示。
 */
object TraceImporter {

    data class Summary(
        val fileName: String,
        val totalEvents: Int,
        val durationSec: Float,
        val buckets: List<Int>,
        val topEvents: List<Pair<String, Int>>,
        val topProcesses: List<Pair<String, Int>>,
        val truncated: Boolean
    )

    sealed interface Result {
        data class Success(val summary: Summary) : Result
        data class Failure(val reason: String) : Result
    }

    /** ftrace 文本：comm-pid [cpu] 标志 时间戳: 事件: 参数 */
    private val LINE = Regex("""^(.*?)-(\d+)\s+\[\d+\]\s+\S+\s+(\d+\.\d+):\s+([\w:]+):""")

    private const val MAX_LINES = 400_000
    private const val MAX_BUCKETS = 300

    suspend fun parse(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "trace"
        val stream = runCatching { context.contentResolver.openInputStream(uri) }
            .getOrNull() ?: return@withContext Result.Failure("无法打开文件")

        val events = LinkedHashMap<String, Int>()
        val processes = LinkedHashMap<String, Int>()
        var total = 0
        var first = 0.0
        var last = 0.0
        var lines = 0
        var truncated = false

        runCatching {
            stream.bufferedReader().useLines { sequence ->
                for (line in sequence) {
                    if (lines >= MAX_LINES) {
                        truncated = true
                        break
                    }
                    lines++
                    val match = LINE.find(line) ?: continue
                    val (comm, _, timestamp, event) = match.destructured
                    val ts = timestamp.toDoubleOrNull() ?: continue
                    total++
                    if (first == 0.0) first = ts
                    last = ts
                    events[event] = (events[event] ?: 0) + 1
                    val process = comm.trim().ifBlank { "unknown" }
                    processes[process] = (processes[process] ?: 0) + 1
                }
            }
        }.onFailure { return@withContext Result.Failure("读取失败：${it.message}") }

        if (total == 0) {
            return@withContext Result.Failure(
                "没有识别到 ftrace 文本事件。二进制 trace（perfetto / perf.data）需要电脑端转换后再导入。"
            )
        }

        val duration = (last - first).toFloat().coerceAtLeast(1f)
        val bucketCount = (duration.toInt() + 1).coerceIn(1, MAX_BUCKETS)
        val buckets = IntArray(bucketCount)
        runCatching {
            stream.close()
        }

        // 第二遍才能按秒分桶，避免把整份 trace 行都留在内存里
        runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { sequence ->
            for (line in sequence) {
                val match = LINE.find(line) ?: continue
                val ts = match.groupValues[3].toDoubleOrNull() ?: continue
                val index = ((ts - first) / (duration / bucketCount)).toInt().coerceIn(0, bucketCount - 1)
                buckets[index]++
            }
        } }

        Result.Success(
            Summary(
                fileName = name,
                totalEvents = total,
                durationSec = duration,
                buckets = buckets.toList(),
                topEvents = events.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value },
                topProcesses = processes.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value },
                truncated = truncated
            )
        )
    }
}
