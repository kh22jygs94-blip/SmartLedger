package com.example.smartledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartledger.util.Format
import java.time.LocalDate

@Composable
fun StatsScreen(state: LedgerUiState, tier: WindowTier) {
    val maxCents = state.categoryTotals.maxOfOrNull { it.cents } ?: 0L
    val today = LocalDate.now().dayOfMonth
    val dailyAverage = if (today > 0) state.monthExpense / today else 0L

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
                        text = "统计",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B2321)
                    )
                    Text(
                        text = state.monthLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { MonthOverviewCard(state) }

            if (state.monthItems.isEmpty()) {
                item { HintCard("本月暂无账目") }
            } else {
                item {
                    SectionTitle(
                        text = "每日收支",
                        trailing = "日均支出 ¥${Format.yuan(dailyAverage)}"
                    )
                }
                item { CashflowChart(state.dailyFlows) }

                item {
                    SectionTitle(
                        text = "累计结余",
                        trailing = signedYuan(state.balance)
                    )
                }
                item { BalanceTrendChart(state.dailyFlows) }
            }

            item { SectionTitle(text = "分类支出") }

            if (state.categoryTotals.isEmpty()) {
                item { HintCard("本月暂无支出") }
            } else {
                items(state.categoryTotals, key = { it.categoryId }) { total ->
                    CategoryBar(total, maxCents)
                }
            }
        }
    }
}

/** 市场惯例：收入用绿、支出用红，和主题色保持一致 */
private fun signedYuan(cents: Long): String {
    val sign = if (cents >= 0) "+" else "-"
    return "$sign¥${Format.yuan(kotlin.math.abs(cents))}"
}

/**
 * 每日资金流柱状图：零轴居中，收入向上、支出向下。
 * 这是银行/券商 App 收支统计最常用的画法，正负方向一眼能看出当天是净流入还是净流出。
 */
@Composable
private fun CashflowChart(flows: List<DailyFlow>) {
    if (flows.isEmpty()) return
    val incomeColor = MaterialTheme.colorScheme.primary
    val expenseColor = MaterialTheme.colorScheme.secondary
    val gridColor = Color(0xFFE6EDEA)
    val axisColor = Color(0xFFC9D6D2)
    val labelColor = Color(0xFF8A9995)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)
    val maxValue = maxOf(
        flows.maxOfOrNull { it.expense } ?: 0L,
        flows.maxOfOrNull { it.income } ?: 0L
    ).coerceAtLeast(1L)

    Surface(shape = RoundedCornerShape(22.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(expenseColor, "支出")
                Spacer(Modifier.width(14.dp))
                LegendDot(incomeColor, "收入")
            }
            Spacer(Modifier.height(10.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(168.dp)) {
                val labelArea = 16.dp.toPx()
                val plotHeight = size.height - labelArea
                val half = plotHeight / 2f
                val zeroY = half
                val slot = size.width / flows.size
                val barWidth = (slot * 0.62f).coerceAtLeast(1.5.dp.toPx())
                // 留一点余量，最高的一根柱子不贴到上下边界
                val barMax = half * 0.94f

                // 上下两根参考线 + 零轴
                drawLine(gridColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                drawLine(axisColor, Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())
                drawLine(gridColor, Offset(0f, plotHeight), Offset(size.width, plotHeight), 1.dp.toPx())

                flows.forEachIndexed { index, flow ->
                    val centerX = slot * index + slot / 2f
                    if (flow.income > 0L) {
                        val h = flow.income.toFloat() / maxValue * barMax
                        drawRoundRect(
                            color = incomeColor,
                            topLeft = Offset(centerX - barWidth / 2f, zeroY - h),
                            size = Size(barWidth, h),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                    if (flow.expense > 0L) {
                        val h = flow.expense.toFloat() / maxValue * barMax
                        drawRoundRect(
                            color = expenseColor,
                            topLeft = Offset(centerX - barWidth / 2f, zeroY),
                            size = Size(barWidth, h),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }

                drawDayAxis(flows.size, slot, plotHeight, textMeasurer, labelStyle)
            }
        }
    }
}

/** 横轴刻度：1、5、10…和月末，间距太密时只保留 1/10/20/月末 */
private fun DrawScope.drawDayAxis(
    days: Int,
    slot: Float,
    plotHeight: Float,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle
) {
    val step = when {
        days <= 15 -> 5
        else -> 10
    }
    val marks = buildList {
        add(1)
        var day = step
        while (day < days) {
            add(day)
            day += step
        }
        if (days !in this) add(days)
    }
    marks.forEach { day ->
        val text = if (day == days) "${day}日" else "$day"
        val layout = textMeasurer.measure(text, labelStyle)
        val centerX = slot * (day - 1) + slot / 2f
        val x = (centerX - layout.size.width / 2f)
            .coerceIn(0f, (slot * days - layout.size.width).coerceAtLeast(0f))
        drawText(textLayoutResult = layout, topLeft = Offset(x, plotHeight + 3.dp.toPx()))
    }
}

/**
 * 本月累计结余走势：从 1 号的 0 开始，按日累加净流入，画成带渐变填充的面积折线。
 * 只画到今天，未来的日子没有数据不参与曲线。
 */
@Composable
private fun BalanceTrendChart(flows: List<DailyFlow>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = Color(0xFFE6EDEA)
    val axisColor = Color(0xFFC9D6D2)
    val today = LocalDate.now().dayOfMonth
    val cumulative = remember(flows, today) {
        var running = 0L
        flows.take(today).map { flow ->
            running += flow.income - flow.expense
            running
        }
    }
    if (cumulative.isEmpty()) return

    // 路径对象复用，避免每次重组/动画帧都新建两个 Path
    val areaPath = remember { Path() }
    val linePath = remember { Path() }

    val low = cumulative.min()
    val high = cumulative.max()
    val endValue = cumulative.last()

    Surface(shape = RoundedCornerShape(22.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "¥${Format.yuan(endValue)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (endValue < 0) MaterialTheme.colorScheme.secondary else Color(0xFF1B2321)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "区间 ${Format.yuan(low)} ~ ${Format.yuan(high)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(136.dp)) {
                val padTop = 8.dp.toPx()
                val padBottom = 8.dp.toPx()
                val plotHeight = size.height - padTop - padBottom
                // 数据范围上下各留余量：折线不和网格线、卡片边缘重合；
                // 全月净值都没变化时让曲线落在中线，而不是贴着底部
                val rawSpan = (high - low).toFloat()
                val margin = if (rawSpan == 0f) {
                    maxOf(1f, kotlin.math.abs(low.toFloat()) * 0.2f)
                } else {
                    rawSpan * 0.12f
                }
                val minY = low.toFloat() - margin
                val span = rawSpan + margin * 2f

                fun yOf(value: Long): Float =
                    padTop + (1f - (value - minY) / span) * plotHeight

                val stepX = if (cumulative.size <= 1) 0f else size.width / (cumulative.size - 1)

                // 三条水平参考线，中间那条是 0 轴（区间跨过 0 时）
                for (i in 0..2) {
                    val y = padTop + plotHeight * i / 2f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                if (low < 0L && high > 0L) {
                    drawLine(axisColor, Offset(0f, yOf(0L)), Offset(size.width, yOf(0L)), 1.dp.toPx())
                }

                val points = cumulative.mapIndexed { index, value ->
                    Offset(if (cumulative.size <= 1) size.width / 2f else stepX * index, yOf(value))
                }

                // 面积：折线下方渐隐填充，金融走势图的标准做法
                areaPath.reset()
                areaPath.moveTo(points.first().x, padTop + plotHeight)
                areaPath.lineTo(points.first().x, points.first().y)
                points.drop(1).forEach { areaPath.lineTo(it.x, it.y) }
                areaPath.lineTo(points.last().x, padTop + plotHeight)
                areaPath.close()
                drawPath(
                    path = areaPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0.02f)),
                        startY = padTop,
                        endY = padTop + plotHeight
                    )
                )

                linePath.reset()
                linePath.moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { linePath.lineTo(it.x, it.y) }
                drawPath(linePath, color = lineColor, style = Stroke(width = 2.dp.toPx()))

                // 末端点：标出「现在」的位置
                val last = points.last()
                drawCircle(Color.White, radius = 4.5.dp.toPx(), center = last)
                drawCircle(lineColor, radius = 3.dp.toPx(), center = last)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MonthOverviewCard(state: LedgerUiState) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("本月支出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "¥ ${Format.yuan(state.monthExpense)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OverviewItem("收入", state.monthIncome, Modifier.weight(1f))
                OverviewItem("结余", state.balance, Modifier.weight(1f))
                OverviewItem("记账笔数", null, Modifier.weight(1f), count = state.monthItems.size)
            }
        }
    }
}

@Composable
private fun OverviewItem(label: String, cents: Long?, modifier: Modifier = Modifier, count: Int = 0) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (cents == null) "$count 笔" else "¥${Format.yuan(cents)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (cents != null && cents < 0) Color(0xFFB4593F) else Color(0xFF1B2321)
        )
    }
}

@Composable
private fun CategoryBar(total: CategoryTotal, maxCents: Long) {
    val fraction = if (maxCents <= 0L) 0f else (total.cents.toFloat() / maxCents).coerceIn(0.02f, 1f)

    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${total.emoji} ${total.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "¥${Format.yuan(total.cents)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE9EFEC))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
