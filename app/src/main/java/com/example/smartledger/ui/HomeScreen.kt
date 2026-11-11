package com.example.smartledger.ui

import android.graphics.Paint
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartledger.data.TransactionEntity
import com.example.smartledger.util.Format
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    state: LedgerUiState,
    tier: WindowTier,
    onDelete: (TransactionEntity) -> Unit,
    onConfirm: (id: Long, amountCents: Long, categoryId: Long) -> Unit,
    onIgnore: (id: Long) -> Unit,
    onSelectDay: (Long) -> Unit
) {
    val categoryMap = remember(state.categories) { state.categories.associateBy { it.id } }
    // Map 在 Compose 里是不稳定类型，包一层 @Immutable 才能让翻页时复用已合成的页面
    val dayTotals = remember(state.dayTotals) { DayTotalsIndex(state.dayTotals) }
    // 点击选日的回调固定下来，日历页在翻页和无关状态变化时都能跳过重组
    val latestSelectDay = rememberUpdatedState(onSelectDay)
    val selectDay: (Long) -> Unit = remember { { day -> latestSelectDay.value(day) } }
    var deleteTarget by remember { mutableStateOf<TransactionEntity?>(null) }

    ContentFrame(tier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = tier.pageBottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Agent 记账",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B2321)
                        )
                        Text(
                            text = state.todayLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                MonthCalendarCard(
                    dayTotals = dayTotals,
                    todayEpochDay = state.todayEpochDay,
                    selectedEpochDay = state.selectedEpochDay,
                    onSelectDay = selectDay
                )
            }

            item { SelectedDaySummaryCard(state) }

            item {
                SectionTitle(
                    text = "当天账单",
                    trailing = if (state.selectedDayItems.isEmpty()) null else "${state.selectedDayItems.size} 笔"
                )
            }

            if (state.loading) {
                item { LoadingHint() }
            } else if (state.selectedDayItems.isEmpty()) {
                item { EmptyDayCard() }
            } else {
                items(state.selectedDayItems, key = { it.id }) { item ->
                    TransactionRow(
                        item = item,
                        categories = categoryMap,
                        onLongClick = { deleteTarget = item }
                    )
                }
            }

            item {
                SectionTitle(
                    text = "待确认",
                    trailing = if (state.pendingCount == 0) null else "${state.pendingCount} 条"
                )
            }

            if (state.pendingItems.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("没有待确认的记录", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "启用通知使用权后自动采集到这里",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(state.pendingItems, key = { "pending-${it.id}" }) { item ->
                    PendingCard(
                        item = item,
                        categories = state.categories,
                        onConfirm = { amountCents, categoryId -> onConfirm(item.id, amountCents, categoryId) },
                        onIgnore = { onIgnore(item.id) }
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这笔记录？") },
            text = {
                Text("${target.merchant} · ¥${Format.yuan(target.amountCents)}，删除后无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

/** 日历下方：只展示当前点选那天的收支 */
@Composable
private fun SelectedDaySummaryCard(state: LedgerUiState) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = state.selectedDayLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1B2321)
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DayStat("收入", state.selectedDayIncome, Color(0xFF2E7D6F), Modifier.weight(1f))
                Box(
                    Modifier
                        .size(width = 1.dp, height = 26.dp)
                        .background(Color(0xFFE3EAE7))
                )
                Spacer(Modifier.width(14.dp))
                DayStat("支出", state.selectedDayExpense, Color(0xFFD1495B), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayStat(label: String, cents: Long, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "¥${Format.yuan(cents)}",
            color = color,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EmptyDayCard() {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("🧾", fontSize = 22.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "这一天没有账单",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "点日历上的其他日期可以切换查看",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val WeekLabels = listOf("日", "一", "二", "三", "四", "五", "六")

/** 月历覆盖今年 + 明年，共 24 个月（到 2027 年 12 月） */
private const val CalendarMonths = 24

/** 逐日收支的只读包装：Map 本身不稳定，包一层后 pager 页面在翻页时可以直接跳过重组 */
@Immutable
private class DayTotalsIndex(private val values: Map<Long, DayTotals>) {
    operator fun get(epochDay: Long): DayTotals? = values[epochDay]
}

/** 一个月固定 6×7 个格子，日期与金额文本一次算好，绘制时不再拼接字符串 */
@Immutable
private data class GridDay(
    val dayText: String,
    val epochDay: Long,
    val inMonth: Boolean,
    val incomeText: String,
    val expenseText: String
)

/** 日历格子的尺寸与间距，绘制和点击命中共用同一份 */
private val CellHeight = 46.dp
private val CellGap = 4.dp
private val CellCorner = 9.dp

/** 日历格子用的原生画笔：只创建一次，绘制时只改颜色，不测量、不分配对象 */
private class CalendarPaints(density: Density) {
    val day = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = with(density) { 11.sp.toPx() }
    }
    val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = with(density) { 8.sp.toPx() }
    }
    val dayLine = with(density) { 13.sp.toPx() }
    val smallLine = with(density) { 10.sp.toPx() }

    /** 一行文字在给定行高里垂直居中时，基线相对行顶的偏移 */
    fun baselineOffset(paint: TextPaint, lineHeight: Float): Float {
        val metrics = paint.fontMetrics
        return (lineHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
    }
}

/**
 * 月历卡片：左右滑动或点箭头翻月，每页一个月；
 * 每个格子里是当天「+收入 / -支出」，底部左右是当前页月份的收入与支出合计。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthCalendarCard(
    dayTotals: DayTotalsIndex,
    todayEpochDay: Long,
    selectedEpochDay: Long,
    onSelectDay: (Long) -> Unit
) {
    val today = remember { LocalDate.now() }
    val baseYear = today.year
    val pagerState = rememberPagerState(
        initialPage = today.monthValue - 1,
        pageCount = { CalendarMonths }
    )

    // 卡片本身不读任何翻页状态：滑动时这里不重组，只有标题和合计各自的小块跟着变
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            MonthHeader(pagerState = pagerState, baseYear = baseYear)

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                WeekLabels.forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                // 提前合成相邻月份，滑动时不会在首帧现搭 42 个格子
                beyondBoundsPageCount = 1
            ) { index ->
                MonthGrid(
                    year = baseYear + index / 12,
                    month = index % 12 + 1,
                    dayTotals = dayTotals,
                    todayEpochDay = todayEpochDay,
                    selectedEpochDay = selectedEpochDay,
                    onSelectDay = onSelectDay
                )
            }

            Spacer(Modifier.height(10.dp))
            MonthTotalsRow(dayTotals = dayTotals, pagerState = pagerState, baseYear = baseYear)
        }
    }
}

/** 只有标题和左右箭头跟着翻页重组，代价很小 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthHeader(pagerState: PagerState, baseYear: Int) {
    val scope = rememberCoroutineScope()
    val page = pagerState.currentPage
    val year = baseYear + page / 12
    val month = page % 12 + 1

    Row(verticalAlignment = Alignment.CenterVertically) {
        MonthArrow("‹", enabled = page > 0) {
            scope.launch { pagerState.animateScrollToPage(page - 1) }
        }
        Text(
            text = "$year 年 $month 月",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1B2321)
        )
        MonthArrow("›", enabled = page < CalendarMonths - 1) {
            scope.launch { pagerState.animateScrollToPage(page + 1) }
        }
    }
}

/** 底部合计按「停稳的页」算，拖动过程中不跟着跳 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthTotalsRow(dayTotals: DayTotalsIndex, pagerState: PagerState, baseYear: Int) {
    val page = pagerState.settledPage
    val year = baseYear + page / 12
    val month = page % 12 + 1
    val monthTotals = remember(dayTotals, year, month) { totalsOfMonth(dayTotals, year, month) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("本月收入", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "¥${Format.yuan(monthTotals.income)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2E7D6F)
            )
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("本月支出", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "¥${Format.yuan(monthTotals.expense)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFD1495B)
            )
        }
    }
}

/** 把逐日汇总按年月累加，得到某一页月份的收入与支出 */
private fun totalsOfMonth(dayTotals: DayTotalsIndex, year: Int, month: Int): DayTotals {
    val first = LocalDate.of(year, month, 1)
    var income = 0L
    var expense = 0L
    for (index in 0 until first.lengthOfMonth()) {
        val totals = dayTotals[first.plusDays(index.toLong()).toEpochDay()] ?: continue
        income += totals.income
        expense += totals.expense
    }
    return DayTotals(income, expense)
}

/**
 * 一页月历整体画在一块 Canvas 上：42 个格子只有 1 个布局节点，
 * 翻页时不会因为要合成 42 个格子而卡顿。
 */
@Composable
private fun MonthGrid(
    year: Int,
    month: Int,
    dayTotals: DayTotalsIndex,
    todayEpochDay: Long,
    selectedEpochDay: Long,
    onSelectDay: (Long) -> Unit
) {
    val cells = remember(year, month, dayTotals) { buildGridDays(year, month, dayTotals) }
    val density = LocalDensity.current
    val paints = remember(density) { CalendarPaints(density) }
    val primary = MaterialTheme.colorScheme.primary
    val spacing = with(density) { CellGap.toPx() }
    val cellHeight = with(density) { CellHeight.toPx() }
    val corner = with(density) { CellCorner.toPx() }
    // 点击回调随组合变化，但手势检测没必要跟着重启：用最新引用即可
    val currentSelectDay = rememberUpdatedState(onSelectDay)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(CellHeight * 6 + CellGap * 5)
            .pointerInput(cells) {
                detectTapGestures { position ->
                    val cellWidth = (size.width - spacing * 6) / 7f
                    val column = (position.x / (cellWidth + spacing)).toInt().coerceIn(0, 6)
                    val row = (position.y / (cellHeight + spacing)).toInt().coerceIn(0, 5)
                    val cell = cells[row * 7 + column]
                    if (cell.inMonth) currentSelectDay.value(cell.epochDay)
                }
            }
    ) {
        val cellWidth = (size.width - spacing * 6) / 7f
        val dayBaseline = paints.baselineOffset(paints.day, paints.dayLine)
        val smallBaseline = paints.baselineOffset(paints.small, paints.smallLine)
        val selectedBg = primary.copy(alpha = 0.24f)
        val onTodayColor = Color.White.copy(alpha = 0.9f)

        // 整页只进一次原生画布：42 个格子的文字直接 drawText，不额外分配字符串或 lambda
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (index in 0 until 42) {
                val cell = cells[index]
                val left = (index % 7) * (cellWidth + spacing)
                val top = (index / 7) * (cellHeight + spacing)
                val isToday = cell.inMonth && cell.epochDay == todayEpochDay
                val isSelected = cell.inMonth && cell.epochDay == selectedEpochDay

                drawRoundRect(
                    color = when {
                        isToday -> primary
                        isSelected -> selectedBg
                        !cell.inMonth -> Color(0xFFFAFCFB)
                        else -> Color(0xFFF4F6F5)
                    },
                    topLeft = Offset(left, top),
                    size = Size(cellWidth, cellHeight),
                    cornerRadius = CornerRadius(corner)
                )

                val dayColor = when {
                    isToday -> Color.White
                    !cell.inMonth -> Color(0xFFC5CFCB)
                    isSelected -> primary
                    else -> Color(0xFF3A4743)
                }
                val incomeColor = if (isToday) onTodayColor else Color(0xFF2E7D6F)
                val expenseColor = if (isToday) onTodayColor else Color(0xFFD1495B)

                val lineCount = 1 +
                    (if (cell.incomeText.isEmpty()) 0 else 1) +
                    (if (cell.expenseText.isEmpty()) 0 else 1)
                val blockHeight = paints.dayLine + (lineCount - 1) * paints.smallLine
                var lineTop = top + (cellHeight - blockHeight) / 2f
                val centerX = left + cellWidth / 2f

                paints.day.color = dayColor.toArgb()
                native.drawText(cell.dayText, centerX, lineTop + dayBaseline, paints.day)
                lineTop += paints.dayLine
                if (cell.incomeText.isNotEmpty()) {
                    paints.small.color = incomeColor.toArgb()
                    native.drawText(cell.incomeText, centerX, lineTop + smallBaseline, paints.small)
                    lineTop += paints.smallLine
                }
                if (cell.expenseText.isNotEmpty()) {
                    paints.small.color = expenseColor.toArgb()
                    native.drawText(cell.expenseText, centerX, lineTop + smallBaseline, paints.small)
                }
            }
        }
    }
}

/** 一次算好 6×7 个格子的日期、所属月份和金额文本 */
private fun buildGridDays(year: Int, month: Int, dayTotals: DayTotalsIndex): List<GridDay> {
    val first = LocalDate.of(year, month, 1)
    // 周日开头的列偏移：DayOfWeek 的周日值是 7，取余后回到 0
    val leading = first.dayOfWeek.value % 7
    val daysInMonth = first.lengthOfMonth()
    return List(42) { index ->
        val offset = index - leading
        val date = first.plusDays(offset.toLong())
        val inMonth = offset in 0 until daysInMonth
        val epochDay = date.toEpochDay()
        val totals = if (inMonth) dayTotals[epochDay] else null
        val income = totals?.income ?: 0L
        val expense = totals?.expense ?: 0L
        GridDay(
            dayText = date.dayOfMonth.toString(),
            epochDay = epochDay,
            inMonth = inMonth,
            incomeText = if (income > 0) "+${compactYuan(income)}" else "",
            expenseText = if (expense > 0) "-${compactYuan(expense)}" else ""
        )
    }
}

/** 格子太窄，金额超过一万就折成「万」，其余保留两位小数 */
private fun compactYuan(cents: Long): String = when {
    cents >= 10_000_000L -> String.format(Locale.CHINA, "%.0f万", cents / 1_000_000.0)
    cents % 100L == 0L -> "${cents / 100}"
    else -> String.format(Locale.CHINA, "%.2f", cents / 100.0)
}

@Composable
private fun MonthArrow(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            fontSize = 20.sp,
            color = if (enabled) Color(0xFF3A4743) else Color(0xFFC8D2CE)
        )
    }
}

@Composable
private fun LoadingHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("正在读取账本…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
