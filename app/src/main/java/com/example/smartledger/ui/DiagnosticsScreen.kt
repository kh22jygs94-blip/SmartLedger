package com.example.smartledger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.view.Choreographer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.example.smartledger.diag.CrashEventReader
import com.example.smartledger.diag.DiagnosticsMonitor
import com.example.smartledger.diag.TraceImporter
import com.example.smartledger.service.KeepAliveService
import com.example.smartledger.util.SeriesResampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DiagTab(val label: String) {
    LIVE("实时"),
    EVENTS("事件"),
    IMPORT("导入")
}

@Composable
fun DiagnosticsScreen(tier: WindowTier) {
    var tab by remember { mutableStateOf(DiagTab.LIVE) }
    val context = LocalContext.current
    val enabled by DiagnosticsMonitor.enabled.collectAsStateWithLifecycle()

    ContentFrame(tier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = tier.pageBottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "诊断",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B2321)
                        )
                        Text(
                            text = if (enabled) "系统指标 · 崩溃事件 · trace 分析（全部本地处理）" else "已关闭，前台和后台都不再采样",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFB4593F)
                        )
                    }
                    Button(
                        onClick = { DiagnosticsMonitor.setEnabled(context, !enabled) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (enabled) Color(0xFFB4593F) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (enabled) "关闭" else "开启", fontSize = 13.sp)
                    }
                }
            }

            item {
                DiagTabRow(current = tab, onSelect = { tab = it })
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (tab) {
                        DiagTab.LIVE -> LivePane()
                        DiagTab.EVENTS -> EventsPane()
                        DiagTab.IMPORT -> ImportPane()
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagTabRow(current: DiagTab, onSelect: (DiagTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEDF2F0), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DiagTab.entries.forEach { item ->
            val selected = item == current
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = if (selected) Color.White else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(item) }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = item.label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) Color(0xFF1B2321) else Color(0xFF6B7C77)
                    )
                }
            }
        }
    }
}

/* ------------------------------- 实时 ------------------------------- */

@Composable
private fun LivePane() {
    val context = LocalContext.current
    val samples by DiagnosticsMonitor.samples.collectAsStateWithLifecycle()
    val running by DiagnosticsMonitor.running.collectAsStateWithLifecycle()
    val enabled by DiagnosticsMonitor.enabled.collectAsStateWithLifecycle()
    var background by remember { mutableStateOf(DiagnosticsMonitor.isBackgroundRefreshEnabled(context)) }

    val latest = samples.lastOrNull()
    val cpuValues = samples.map { it.cpuPercent }
    val memValues = samples.map { it.memUsedMb.toFloat() }
    val fpsValues = samples.map { it.fps }

    // 只有诊断开启且界面可见时才统计帧率：Choreographer 只在有绘制时回调
    FrameRateReporter(active = enabled && running)

    DiagCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("采样状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        !enabled -> "已关闭，前台和后台检测都停了"
                        !running -> "已暂停，进入前台后自动开始"
                        background -> "运行中（前台 + 后台刷新）"
                        else -> "运行中（仅前台刷新）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled && running) MaterialTheme.colorScheme.primary else Color(0xFFB4593F)
                )
            }
            Switch(
                checked = background,
                enabled = enabled,
                onCheckedChange = {
                    DiagnosticsMonitor.setBackgroundRefresh(context, it)
                    background = it
                    // 后台采样要依附常驻前台服务，否则进程随时会被回收
                    if (it) {
                        runCatching {
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, KeepAliveService::class.java)
                            )
                        }
                    }
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (enabled) {
                "前台每秒采样；打开开关后后台也采样，耗电略增。"
            } else {
                "点右上角「开启」恢复采样，后台刷新开关随总开关一起失效。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    DiagCard {
        MetricHeader(
            title = "本应用 CPU",
            value = latest?.let { String.format(Locale.CHINA, "%.1f%%", it.cpuPercent) } ?: "--",
            hint = "本进程"
        )
        Spacer(Modifier.height(8.dp))
        MetricChart(
            values = cpuValues,
            maxValue = 100f,
            color = Color(0xFF2E7D6F),
            modifier = Modifier.fillMaxWidth().height(96.dp)
        )
    }

    DiagCard {
        MetricHeader(
            title = "内存占用",
            value = latest?.let { "${it.memUsedMb} MB" } ?: "--",
            hint = latest?.let { "总量 ${it.memTotalMb} MB · 本应用 ${it.appPssMb} MB" } ?: ""
        )
        Spacer(Modifier.height(8.dp))
        MetricChart(
            values = memValues,
            maxValue = (memValues.maxOrNull() ?: 1f) * 1.15f,
            color = Color(0xFF4A63B0),
            modifier = Modifier.fillMaxWidth().height(96.dp)
        )
    }

    DiagCard {
        MetricHeader(
            title = "界面帧率",
            value = latest?.let { String.format(Locale.CHINA, "%.0f fps", it.fps) } ?: "--",
            hint = "界面静止时无值"
        )
        Spacer(Modifier.height(8.dp))
        MetricChart(
            values = fpsValues,
            maxValue = 120f,
            color = Color(0xFFB4593F),
            modifier = Modifier.fillMaxWidth().height(80.dp)
        )
    }
}

/** 每秒统计一次回调次数，近似界面帧率；诊断关闭后不再挂回调 */
@Composable
private fun FrameRateReporter(active: Boolean) {
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        val choreographer = Choreographer.getInstance()
        var frames = 0
        var lastAt = System.currentTimeMillis()
        var alive = true
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frames++
                val now = System.currentTimeMillis()
                val elapsed = now - lastAt
                if (elapsed >= 1000) {
                    DiagnosticsMonitor.submitFps(frames * 1000f / elapsed)
                    frames = 0
                    lastAt = now
                }
                if (alive) choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose { alive = false }
    }
}

@Composable
private fun MetricHeader(title: String, value: String, hint: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}

/** 面积折线：没有第三方图表库，Canvas 手绘，和统计页保持同一套视觉 */
@Composable
private fun MetricChart(values: List<Float>, maxValue: Float, color: Color, modifier: Modifier = Modifier) {
    val baseline = Color(0xFFE3EAE7)
    // Path 复用：每帧重新分配对象会制造大量垃圾，交给 remember 持有
    val linePath = remember { Path() }
    val areaPath = remember { Path() }

    Canvas(modifier = modifier) {
        if (size.height <= 0f || size.width <= 0f) return@Canvas
        drawLine(
            color = baseline,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f
        )
        if (values.size < 2) return@Canvas

        // 点数远多于像素时先降采样，保拐点、不掉峰值，绘制成本随之下降
        val threshold = (size.width / 3f).toInt().coerceAtLeast(3)
        val points = if (values.size > threshold * 2) SeriesResampler.lttb(values, threshold) else values

        val upper = maxValue.coerceAtLeast(1f)
        val stepX = size.width / (points.size - 1)
        linePath.reset()
        points.forEachIndexed { index, value ->
            val x = index * stepX
            val ratio = (value / upper).coerceIn(0f, 1f)
            val y = size.height - ratio * size.height
            if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }

        areaPath.reset()
        areaPath.addPath(linePath)
        areaPath.lineTo(size.width, size.height)
        areaPath.lineTo(0f, size.height)
        areaPath.close()

        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)))
        )
        drawPath(path = linePath, color = color, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
        val lastY = size.height - (points.last() / upper).coerceIn(0f, 1f) * size.height
        drawCircle(color = color, radius = 3.5f, center = Offset(size.width, lastY))
    }
}

/* ------------------------------- 事件 ------------------------------- */

@Composable
private fun EventsPane() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(CrashEventReader.canRead(context)) }
    var events by remember { mutableStateOf<List<CrashEventReader.Event>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<CrashEventReader.Event?>(null) }

    LaunchedEffect(reload) {
        granted = CrashEventReader.canRead(context)
        loading = true
        error = ""
        events = runCatching { CrashEventReader.read() }
            .onFailure { error = it.message ?: "读取失败" }
            .getOrDefault(emptyList())
        loading = false
    }

    if (!granted) {
        PermissionCard(
            title = "需要一次性授权才能读系统崩溃记录",
            description = "tombstone、ANR 的原始文件在 /data 下，普通应用读不到；" +
                "系统会把它们同步到 dropbox，授权 DUMP 后即可列出。",
            commands = CrashEventReader.grantCommands(context.packageName)
        )
    }

    DiagCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("崩溃 / ANR / Tombstone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = when {
                        loading -> "正在读取 dropbox…"
                        error.isNotBlank() -> error
                        events.isEmpty() -> "没有新的异常记录"
                        else -> "最近 ${events.size} 条，点开看正文"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error.isNotBlank()) Color(0xFFB4593F) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { reload++ }) { Text("刷新", fontSize = 12.sp) }
        }
    }

    events.forEach { event ->
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { detail = event }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = categoryColor(event.tag).copy(alpha = 0.12f)
                ) {
                    Text(
                        text = event.label,
                        fontSize = 11.sp,
                        color = categoryColor(event.tag),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(event.tag, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "${event.time} · ${event.bytes} 字节",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", fontSize = 18.sp, color = Color(0xFF9AA8A4))
            }
        }
    }

    detail?.let { event ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("${event.label} · ${event.time}") },
            text = {
                Text(
                    text = event.body.ifBlank { "（无正文）" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 40
                )
            },
            confirmButton = {
                TextButton(onClick = { detail = null }) { Text("关闭") }
            }
        )
    }
}

private fun categoryColor(tag: String): Color = when {
    tag.contains("anr", true) || tag.contains("watchdog", true) -> Color(0xFFB4593F)
    tag.contains("tombstone", true) || tag.contains("native", true) -> Color(0xFF7A5BB4)
    tag.contains("crash", true) -> Color(0xFFB4593F)
    else -> Color(0xFF4A63B0)
}

/* ------------------------------- 导入 ------------------------------- */

@Composable
private fun ImportPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<TraceImporter.Result?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            result = TraceImporter.parse(context, uri)
            busy = false
        }
    }

    DiagCard {
        Text("导入 trace 文件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "支持 ftrace 纯文本；perfetto、perf.data 请先在电脑端转成文本。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { picker.launch(arrayOf("text/*", "application/octet-stream")) },
            enabled = !busy
        ) {
            Text(if (busy) "解析中…" else "选择文件", fontSize = 13.sp)
        }
    }

    when (val state = result) {
        is TraceImporter.Result.Failure -> {
            DiagCard {
                Text("解析失败", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB4593F)
                )
            }
        }

        is TraceImporter.Result.Success -> {
            val summary = state.summary
            DiagCard {
                MetricHeader(
                    title = summary.fileName,
                    value = "${summary.totalEvents} 事件",
                    hint = String.format(Locale.CHINA, "跨度 %.1f 秒%s", summary.durationSec, if (summary.truncated) " · 已截断" else "")
                )
                Spacer(Modifier.height(8.dp))
                MetricChart(
                    values = summary.buckets.map { it.toFloat() },
                    maxValue = (summary.buckets.maxOrNull() ?: 1).toFloat(),
                    color = Color(0xFF2E7D6F),
                    modifier = Modifier.fillMaxWidth().height(88.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "横轴为时间（按秒分桶），纵轴为事件数",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DiagCard {
                SectionTitle("高频事件", trailing = "Top ${summary.topEvents.size}")
                Spacer(Modifier.height(8.dp))
                summary.topEvents.forEach { (name, count) ->
                    RankBar(label = name, value = count, max = summary.topEvents.first().second, color = Color(0xFF4A63B0))
                }
            }

            DiagCard {
                SectionTitle("最活跃进程", trailing = "Top ${summary.topProcesses.size}")
                Spacer(Modifier.height(8.dp))
                summary.topProcesses.forEach { (name, count) ->
                    RankBar(label = name, value = count, max = summary.topProcesses.first().second, color = Color(0xFFB4593F))
                }
            }
        }

        null -> Unit
    }
}

@Composable
private fun RankBar(label: String, value: Int, max: Int, color: Color) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text("$value", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Color(0xFFEDF2F0), CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (value.toFloat() / max.coerceAtLeast(1)).coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(color, CircleShape)
            )
        }
    }
}

/* ------------------------------- 公共 ------------------------------- */

@Composable
private fun DiagCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun PermissionCard(title: String, description: String, commands: List<String>) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFFF4EF),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color(0xFFB4593F))
            Spacer(Modifier.height(6.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            commands.forEach { command ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = command,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("adb", command))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("复制", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
