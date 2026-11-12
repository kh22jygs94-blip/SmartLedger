package com.example.smartledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartledger.util.AppLog
import com.example.smartledger.util.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class LogSection(val label: String) {
    RECORDS("操作记录"),
    FILES("应用文件")
}

private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

/**
 * 日志页：分成「操作记录」和「应用文件」两块。
 *
 * 操作记录是应用使用无障碍、通知、截图这些权限时留下的痕迹（精确到秒）；
 * 应用文件是内部存储的只读目录浏览，两个页面共用同一套内部存储事实，方便互相对照。
 */
@Composable
fun LogScreen(tier: WindowTier) {
    var section by remember { mutableStateOf(LogSection.RECORDS) }

    ContentFrame(tier) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = "日志",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1B2321)
                )
                Text(
                    text = "记录自动记账的关键动作",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            SectionTabs(current = section, onChange = { section = it })
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    LogSection.RECORDS -> RecordsPane(tier)
                    LogSection.FILES -> FilesPane(tier)
                }
            }
        }
    }
}

@Composable
private fun SectionTabs(current: LogSection, onChange: (LogSection) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LogSection.entries.forEach { section ->
            val selected = section == current
            Surface(
                onClick = { onChange(section) },
                shape = RoundedCornerShape(10.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White
            ) {
                Text(
                    text = section.label,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.White else Color(0xFF53615D),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun RecordsPane(tier: WindowTier) {
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        loading = true
        entries = withContext(Dispatchers.IO) { AppLog.readRecent(500) }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "最近 ${entries.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { reload++ }) { Text("刷新", fontSize = 13.sp) }
            TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                Text("删除全部", fontSize = 13.sp)
            }
        }
        if (loading) {
            HintCard("正在读取日志…")
        } else if (entries.isEmpty()) {
            HintCard("还没有日志")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = tier.pageBottomPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { "${it.fileName}:${it.lineIndex}" }) { entry ->
                    LogRow(entry) {
                        scope.launch {
                            withContext(Dispatchers.IO) { AppLog.delete(entry) }
                            reload++
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("删除全部日志？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { AppLog.clear() }
                        reload++
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun LogRow(entry: LogEntry, onDelete: () -> Unit) {
    val color = categoryColor(entry.category)
    Surface(shape = RoundedCornerShape(14.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = entry.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(entry.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("删除", fontSize = 11.sp, color = Color(0xFF8A9995))
                }
            }
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF2B3532),
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

@Composable
private fun FilesPane(tier: WindowTier) {
    val context = LocalContext.current
    val root = remember { File(context.applicationInfo.dataDir) }
    var current by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var previewText by remember { mutableStateOf("") }

    LaunchedEffect(current) {
        loading = true
        entries = withContext(Dispatchers.IO) { listFiles(current) }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { current = current.parentFile ?: current },
                enabled = current != root
            ) {
                Text("上一级", fontSize = 13.sp)
            }
            Text(
                text = relativePath(root, current),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = "点文件可预览（只读）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (loading) {
            HintCard("正在读取目录…")
        } else if (entries.isEmpty()) {
            HintCard("当前目录为空。")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = tier.pageBottomPadding),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(entries, key = { it.file.absolutePath }) { node ->
                    FileRow(node) {
                        if (node.file.isDirectory) {
                            current = node.file
                        } else {
                            previewFile = node.file
                            previewText = readPreview(node.file)
                        }
                    }
                }
            }
        }
    }

    previewFile?.let { file ->
        AlertDialog(
            onDismissRequest = { previewFile = null },
            title = { Text(file.name, style = MaterialTheme.typography.titleSmall) },
            text = {
                Box(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = previewText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF2B3532)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { previewFile = null }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun FileRow(node: FileNode, onClick: () -> Unit) {
    val file = node.file
    val isDir = file.isDirectory
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (isDir) "📁" else "📄", fontSize = 16.sp)
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = if (isDir) "${file.name}/" else file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isDir) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isDir) {
                        "${node.childCount} 项 · ${formatTime(file.lastModified())}"
                    } else {
                        "${formatSize(file.length())} · ${formatTime(file.lastModified())}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun categoryColor(category: String): Color = when (category) {
    "截图" -> Color(0xFF2E7D6F)
    "识别" -> Color(0xFF4A63B0)
    "入账" -> Color(0xFFB4593F)
    "通知" -> Color(0xFF3F7BB4)
    "启动" -> Color(0xFF7A5BB4)
    else -> Color(0xFF6B7C77)
}

private fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormatter)

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024f)
    else -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024f * 1024f))
}

private fun relativePath(root: File, current: File): String {
    val relative = current.relativeToOrNull(root)?.path.orEmpty()
    return if (relative.isBlank()) "/" else "/$relative"
}

/** 目录项数和大小在 IO 线程一次算好，避免渲染时再碰磁盘 */
private data class FileNode(val file: File, val childCount: Int)

/** 目录在前、名称升序；只读，不做任何写操作 */
private fun listFiles(dir: File): List<FileNode> =
    (dir.listFiles() ?: emptyArray())
        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        .map { file ->
            FileNode(file, if (file.isDirectory) file.listFiles()?.size ?: 0 else 0)
        }

/** 只做文本预览：二进制文件只报大小，避免把数据库读成乱码 */
private fun readPreview(file: File): String {
    if (file.length() > 256L * 1024L) {
        return "文件较大（${formatSize(file.length())}），不在界面中预览。"
    }
    val text = runCatching { file.readText() }.getOrNull()
        ?: return "无法以文本方式读取（${formatSize(file.length())}）。"
    if (text.isEmpty()) return "空文件"
    val printable = text.count { it == '\n' || it == '\t' || it == '\r' || it >= ' ' }
    if (printable.toFloat() / text.length < 0.9f) {
        return "二进制文件（${formatSize(file.length())}），不在界面中预览。"
    }
    return text.take(8000)
}
