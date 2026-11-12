package com.example.smartledger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 屏幕宽度档位，断点与 Material 3 的窗口尺寸等级一致。
 * Compact：手机竖屏；Medium：折叠展开 / 平板竖屏；Expanded：平板横屏、桌面。
 */
enum class WindowTier {
    Compact, Medium, Expanded;

    /** 大屏上不让内容无限拉伸，按可读宽度收口 */
    val contentMaxWidth: Dp
        get() = when (this) {
            Compact -> Dp.Infinity
            Medium -> 560.dp
            Expanded -> 720.dp
        }

    /** 底部留白：手机要给悬浮按钮让位，平板有侧边导轨时不需要那么多 */
    val pageBottomPadding: Dp
        get() = if (this == Compact) 104.dp else 88.dp
}

/**
 * 读取当前窗口宽度并换算成档位。
 * 用 Configuration 的宽度而不是屏幕物理尺寸，分屏、折叠、旋转时都会跟着变。
 */
@Composable
fun rememberWindowTier(): WindowTier {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp >= 840 -> WindowTier.Expanded
        widthDp >= 600 -> WindowTier.Medium
        else -> WindowTier.Compact
    }
}

/**
 * 页面内容外框：手机铺满，平板/横屏时限制宽度并居中，
 * 避免卡片被拉成整屏宽的一条。
 */
@Composable
fun ContentFrame(
    tier: WindowTier,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = tier.contentMaxWidth).fillMaxSize()) {
            content()
        }
    }
}
