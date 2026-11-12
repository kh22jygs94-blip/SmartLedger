package com.example.smartledger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.example.smartledger.service.AccessibilityHealer
import com.example.smartledger.service.NotificationAccess
import com.example.smartledger.service.ScreenReaderService
import com.example.smartledger.service.ServiceHealth
import com.example.smartledger.util.Format
import com.example.smartledger.util.FrameRateControl

@Composable
fun SettingsScreen(
    state: LedgerUiState,
    tier: WindowTier,
    onPreview: (String) -> String,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    val enabled = rememberNotificationAccess()
    val batteryIgnored = rememberBatteryOptimizationIgnored()
    val keepAlive = rememberKeepAliveStatus()
    var sampleText by remember { mutableStateOf("微信支付凭证 ¥32.50 收款方：瑞幸咖啡") }
    var previewResult by remember { mutableStateOf("") }
    var showResetConfirm by remember { mutableStateOf(false) }
    var refreshHz by remember { mutableStateOf(FrameRateControl.read(context)) }

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
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B2321)
                    )
                    Text(
                        text = "自动记账与数据说明",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                KeepAliveCard(
                    status = keepAlive,
                    onOpenAccessibility = { openAccessibilitySettings(context) }
                )
            }

            item {
                AutoRestoreCard()
            }

            item {
                SettingCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("通知使用权", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (enabled) "已开启，支付通知会自动记进账本" else "未开启，目前只能手动记账",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (enabled) MaterialTheme.colorScheme.primary else Color(0xFFB4593F)
                            )
                        }
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (enabled) "去管理" else "去开启", fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "在系统列表里允许「Agent 记账 · 自动记录」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("后台运行", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (batteryIgnored) {
                                    "已忽略电池优化，开机或覆盖安装后会自动恢复自动记账"
                                } else {
                                    "未加入电池优化白名单，休眠时可能被系统清理"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (batteryIgnored) MaterialTheme.colorScheme.primary else Color(0xFFB4593F)
                            )
                        }
                        Button(
                            onClick = { requestIgnoreBatteryOptimization(context) },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (batteryIgnored) "去查看" else "去设置", fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "已内置开机自启；联想等机型建议再到系统「自启动管理」里允许本应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingCard {
                    Text("锁帧率", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (refreshHz >= 120) "120Hz：滑动更顺滑，耗电略高" else "60Hz：更省电，动画更保守",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(60, 120).forEach { value ->
                            val selected = refreshHz == value
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFEDF2F0),
                                modifier = Modifier.clickable {
                                    FrameRateControl.set(context, value)
                                    refreshHz = value
                                }
                            ) {
                                Text(
                                    text = "${value}Hz",
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) Color.White else Color(0xFF3A4743),
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingCard {
                    Text("解析自测", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "粘贴一条通知文本，验证能否识别（只解析，不入账）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = sampleText,
                        onValueChange = { sampleText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { previewResult = onPreview(sampleText) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("试解析", fontSize = 13.sp)
                    }
                    if (previewResult.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFF4F7F6)) {
                            Text(
                                text = previewResult,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            item {
                SettingCard {
                    Text("用法", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    listOf(
                        "手动记账：点右下角 + 号",
                        "自动记账：保持读屏和通知使用权开启",
                        "剪贴板记账：在账单页长按复制，再点 + 号填入"
                    ).forEach {
                        Text(
                            text = "· $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }

            item {
                SettingCard {
                    Row {
                        Text(
                            text = "账本数据",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${state.monthItems.size} 笔 / ${state.monthLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "数据只存本机，不上传服务器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "版本 1.0 · 第一代（本地规则解析）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingCard {
                    Text(
                        text = "重置所有数据",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFB4593F)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "清空全部账目和日志，无法恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { showResetConfirm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB4593F))
                    ) {
                        Text("重置所有数据", color = Color.White)
                    }
                }
            }

            item {
                Surface(shape = RoundedCornerShape(14.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "本月累计支出 ¥${Format.yuan(state.monthExpense)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 二次确认：重置不可恢复，必须点过两次才真正执行
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("确认重置所有数据？") },
            text = {
                Text("删除全部账目和日志，无法恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onReset()
                    }
                ) {
                    Text("确认重置", color = Color(0xFFB4593F), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), content = { content() })
    }
}

/** 从系统设置返回时需要重新读取权限状态，所以在 ON_RESUME 时刷新 */
@Composable
private fun rememberNotificationAccess(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(NotificationAccess.isEnabled(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = NotificationAccess.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}

/** 自动记账是否真的在跑：授权被关掉、或者被系统清理停了，这里都能看出来 */
@Composable
private fun KeepAliveCard(status: ServiceHealth.Status, onOpenAccessibility: () -> Unit) {
    val healthy = status.enabled && status.running
    val stateText = when {
        !status.enabled -> "未开启，账单不会被自动识别"
        !status.running -> "已授权但服务未运行，可能被系统清理"
        status.lastConnectedAt > 0L -> "运行中，上次连接 ${Format.time(status.lastConnectedAt)}"
        else -> "运行中"
    }

    SettingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("自动记账状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (healthy) MaterialTheme.colorScheme.primary else Color(0xFFB4593F)
                )
            }
            Button(
                onClick = onOpenAccessibility,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (healthy) "去管理" else "去开启", fontSize = 13.sp)
            }
        }
        if (!healthy) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "到系统无障碍设置里重新开启即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 亮屏自动恢复：系统不允许应用从后台弹到前台，所以这里保证的是「读屏服务还在」，
 * 而不是「自动打开 App 界面」。
 */
@Composable
private fun AutoRestoreCard() {
    val context = LocalContext.current
    val granted = rememberRestorePermission()
    var enabled by remember { mutableStateOf(AccessibilityHealer.isAutoRestoreEnabled(context)) }
    val command = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    val active = granted && enabled

    SettingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("亮屏自动恢复", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        !granted -> "未授权，暂时不能自动恢复读屏"
                        enabled -> "已开启，亮屏时自动检查并恢复读屏"
                        else -> "已授权，自动恢复开关是关着的"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) MaterialTheme.colorScheme.primary else Color(0xFFB4593F)
                )
            }
            Switch(
                checked = active,
                enabled = granted,
                onCheckedChange = {
                    AccessibilityHealer.setAutoRestoreEnabled(context, it)
                    enabled = it
                }
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "亮屏时检查读屏服务，被清理就重新开启。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!granted) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "开启前需一次性授权（USB 连接电脑执行）：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("adb", command))
                        Toast.makeText(context, "已复制授权命令", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("复制", fontSize = 12.sp)
                }
            }
        }
    }
}

/** 权限只能在应用外授予，回到设置页时重新读一次 */
@Composable
private fun rememberRestorePermission(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(AccessibilityHealer.canWriteSecureSettings(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = AccessibilityHealer.canWriteSecureSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

/** 每次回到设置页都重新检测，用户从系统设置回来能立刻看到最新状态 */
@Composable
private fun rememberKeepAliveStatus(): ServiceHealth.Status {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(ServiceHealth.check(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = ServiceHealth.check(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return status
}

/** 优先跳到本应用的无障碍详情页，机型不支持时退回无障碍列表 */
private fun openAccessibilitySettings(context: Context) {
    // 直接用 action 字符串：部分 SDK 版本没有导出这个常量
    val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
        putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, ScreenReaderService::class.java))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(detail) }.isSuccess) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 电池优化白名单状态：从系统设置返回时需要重新读取 */
@Composable
private fun rememberBatteryOptimizationIgnored(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var ignored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignored = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return ignored
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

/** 优先弹系统的「是否允许忽略电池优化」对话框，机型不支持时退回优化列表页 */
private fun requestIgnoreBatteryOptimization(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching { context.startActivity(direct) }.isSuccess
    if (opened) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
