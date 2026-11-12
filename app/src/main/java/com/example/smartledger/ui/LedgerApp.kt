package com.example.smartledger.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class MainTab(val label: String, val emoji: String) {
    HOME("首页", "🏠"),
    STATS("统计", "📊"),
    LOGS("日志", "📋"),
    DIAG("诊断", "🩺"),
    SETTINGS("设置", "⚙️")
}

@Composable
fun LedgerApp(viewModel: LedgerViewModel, tier: WindowTier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(MainTab.HOME) }
    var adding by remember { mutableStateOf(false) }
    // 点「＋」的瞬间把剪贴板读出来：此时页面一定有焦点，账单页复制的内容才读得到
    var clipboardText by remember { mutableStateOf("") }
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // 手机用底部导航；平板留给内容，导航挪到左侧导轨
            if (tier == WindowTier.Compact) {
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    MainTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { TabIcon(item, 17.sp, state.pendingCount) },
                            label = {
                                Text(
                                    text = item.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (tab == item) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                unselectedIconColor = Color(0xFF8A9A95),
                                unselectedTextColor = Color(0xFF8A9A95)
                            )
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (tab == MainTab.HOME || tab == MainTab.STATS) {
                FloatingActionButton(
                    onClick = {
                        clipboardText = readClipboardText(context)
                        adding = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("＋", fontSize = 24.sp)
                }
            }
        }
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (tier != WindowTier.Compact) {
                NavigationRail(
                    containerColor = Color.White,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    MainTab.entries.forEach { item ->
                        NavigationRailItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { TabIcon(item, 20.sp, state.pendingCount) },
                            label = {
                                Text(
                                    text = item.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (tab == item) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                unselectedIconColor = Color(0xFF8A9A95),
                                unselectedTextColor = Color(0xFF8A9A95)
                            )
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    MainTab.HOME -> HomeScreen(
                        state = state,
                        tier = tier,
                        onDelete = { viewModel.delete(it.id) },
                        onConfirm = viewModel::confirm,
                        onIgnore = viewModel::ignore,
                        onSelectDay = viewModel::selectDay
                    )
                    MainTab.STATS -> StatsScreen(state, tier)
                    MainTab.LOGS -> LogScreen(tier)
                    MainTab.DIAG -> DiagnosticsScreen(tier)
                    MainTab.SETTINGS -> SettingsScreen(
                        state = state,
                        tier = tier,
                        onPreview = viewModel::previewParse,
                        onReset = viewModel::resetAll
                    )
                }
            }
        }
    }

    if (adding) {
        AddTransactionSheet(
            categories = state.categories,
            tier = tier,
            clipboardText = clipboardText,
            onDismiss = { adding = false },
            onSave = { amountText, direction, categoryId, merchant, note ->
                viewModel.addTransaction(amountText, direction, categoryId, merchant, note)
                adding = false
            }
        )
    }
}

/** 剪贴板只对前台且持有焦点的应用开放，所以必须在页面获得焦点时调用 */
private fun readClipboardText(context: Context): String = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}.getOrDefault("")

/** 导航图标；待确认数量做成首页图标右上角的红点角标 */
@Composable
private fun TabIcon(tab: MainTab, iconSize: TextUnit, pendingCount: Int) {
    if (tab == MainTab.HOME && pendingCount > 0) {
        BadgedBox(
            badge = {
                Badge(containerColor = Color(0xFFE5484D), contentColor = Color.White) {
                    Text(
                        text = if (pendingCount > 99) "99+" else "$pendingCount",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        ) {
            Text(tab.emoji, fontSize = iconSize)
        }
    } else {
        Text(tab.emoji, fontSize = iconSize)
    }
}
