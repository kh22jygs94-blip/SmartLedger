package com.example.smartledger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smartledger.data.AmountPoint
import com.example.smartledger.data.CategoryEntity
import com.example.smartledger.data.CategorySum
import com.example.smartledger.data.Direction
import com.example.smartledger.data.LedgerRepository
import com.example.smartledger.data.TransactionEntity
import com.example.smartledger.parser.CategoryClassifier
import com.example.smartledger.parser.PaymentParser
import com.example.smartledger.util.AppLog
import com.example.smartledger.util.Format
import com.example.smartledger.util.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 统计页的一行：分类 + 本月该分类的支出合计 */
data class CategoryTotal(
    val categoryId: Long,
    val name: String,
    val emoji: String,
    val cents: Long
)

/** 统计页的资金流时间序列：某一天的支出与收入合计，没有账目的日子也是 0 */
data class DailyFlow(
    val day: Int,
    val expense: Long,
    val income: Long
)

/** 某一天的收支合计，月历格子与选中日汇总共用 */
data class DayTotals(val income: Long, val expense: Long)

data class LedgerUiState(
    val loading: Boolean = true,
    val monthLabel: String = "",
    val todayLabel: String = "",
    val todayExpense: Long = 0,
    val todayIncome: Long = 0,
    val monthExpense: Long = 0,
    val monthIncome: Long = 0,
    val todayItems: List<TransactionEntity> = emptyList(),
    val monthItems: List<TransactionEntity> = emptyList(),
    /** 本月 1 号到月末，逐日的支出/收入，用来画资金流柱状图和结余走势 */
    val dailyFlows: List<DailyFlow> = emptyList(),
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val pendingItems: List<TransactionEntity> = emptyList(),
    val pendingCount: Int = 0,
    /** 首页月历里被点中的自然日（epochDay） */
    val selectedEpochDay: Long = 0,
    val selectedDayLabel: String = "",
    val selectedDayExpense: Long = 0,
    val selectedDayIncome: Long = 0,
    val selectedDayItems: List<TransactionEntity> = emptyList(),
    /** 月历覆盖区间内逐日收支，key 是 epochDay */
    val dayTotals: Map<Long, DayTotals> = emptyMap(),
    val todayEpochDay: Long = 0,
    val categories: List<CategoryEntity> = emptyList()
) {
    val balance: Long get() = monthIncome - monthExpense
}

class LedgerViewModel(private val repository: LedgerRepository) : ViewModel() {

    /** 跨天/跨月时让数据流重新订阅，重新计算区间 */
    private val refreshTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val monthRange = refreshTick.map { Format.monthRange(LocalDate.now()) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val confirmedFlow = monthRange.flatMapLatest { (start, end) ->
        repository.observeConfirmed(start, end)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val expenseByCategoryFlow = monthRange.flatMapLatest { (start, end) ->
        repository.observeExpenseByCategory(start, end)
    }

    /** 首页月历里被点中的那天，默认今天 */
    private val selectedDay = MutableStateFlow(LocalDate.now().toEpochDay())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val selectedDayItemsFlow = selectedDay.flatMapLatest { epochDay ->
        val (start, end) = Format.dayRange(LocalDate.ofEpochDay(epochDay))
        repository.observeConfirmed(start, end)
    }

    /** 月历覆盖今年 + 明年，共 24 个月；逐日金额用于格子里的 + / - 和月份汇总 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val calendarAmountsFlow = refreshTick.map {
        val zone = ZoneId.systemDefault()
        val year = LocalDate.now(zone).year
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year + 2, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        start to end
    }.flatMapLatest { (start, end) ->
        repository.observeRangeAmounts(start, end)
    }

    private data class HomeSnapshot(
        val dayItems: List<TransactionEntity>,
        val amounts: List<AmountPoint>,
        val selectedDay: Long
    )

    private val homeFlow = combine(selectedDayItemsFlow, calendarAmountsFlow, selectedDay) { items, amounts, day ->
        HomeSnapshot(items, amounts, day)
    }

    val state: StateFlow<LedgerUiState> = combine(
        confirmedFlow,
        expenseByCategoryFlow,
        repository.observePending(),
        repository.observeCategories(),
        homeFlow
    ) { items, expenseByCategory, pending, categories, home ->
        buildState(items, expenseByCategory, pending, categories, home)
    }
        // 过滤、求和在后台线程执行，避免每次记账都占用主线程
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LedgerUiState()
        )

    private fun buildState(
        items: List<TransactionEntity>,
        expenseByCategory: List<CategorySum>,
        pending: List<TransactionEntity>,
        categories: List<CategoryEntity>,
        home: HomeSnapshot
    ): LedgerUiState {
        val today = LocalDate.now()
        val (todayStart, todayEnd) = Format.dayRange(today)
        val todayItems = items.filter { it.occurredAt in todayStart until todayEnd }

        val expenseItems = items.filter { it.direction == Direction.EXPENSE }
        val incomeItems = items.filter { it.direction == Direction.INCOME || it.direction == Direction.REFUND }

        // 按自然日把本月的收支拆成时间序列，图表直接用这个数组，不再遍历原始流水
        val monthDays = today.lengthOfMonth()
        val dailyExpense = LongArray(monthDays + 1)
        val dailyIncome = LongArray(monthDays + 1)
        val zone = ZoneId.systemDefault()
        items.forEach { item ->
            val day = Instant.ofEpochMilli(item.occurredAt).atZone(zone).dayOfMonth
            if (day !in 1..monthDays) return@forEach
            when (item.direction) {
                Direction.EXPENSE -> dailyExpense[day] += item.amountCents
                Direction.INCOME, Direction.REFUND -> dailyIncome[day] += item.amountCents
                Direction.TRANSFER -> Unit
            }
        }
        val dailyFlows = (1..monthDays).map { day ->
            DailyFlow(day = day, expense = dailyExpense[day], income = dailyIncome[day])
        }

        val categoryMap = categories.associateBy { it.id }
        val totals = expenseByCategory.map { sum ->
            val category = categoryMap[sum.categoryId]
            CategoryTotal(
                categoryId = sum.categoryId,
                name = category?.name ?: "未分类",
                emoji = category?.emoji ?: "📦",
                cents = sum.total
            )
        }

        // 月历区间内按自然日汇总：格子里的 +/- 与月份合计都读这一份
        val dayTotals = HashMap<Long, DayTotals>()
        home.amounts.forEach { point ->
            val epochDay = Instant.ofEpochMilli(point.occurredAt).atZone(zone).toLocalDate().toEpochDay()
            val current = dayTotals[epochDay] ?: DayTotals(0, 0)
            dayTotals[epochDay] = when (point.direction) {
                Direction.EXPENSE -> current.copy(expense = current.expense + point.amountCents)
                Direction.INCOME, Direction.REFUND -> current.copy(income = current.income + point.amountCents)
                Direction.TRANSFER -> current
            }
        }

        val selectedDate = LocalDate.ofEpochDay(home.selectedDay)
        val selectedTotals = dayTotals[home.selectedDay] ?: DayTotals(0, 0)
        val selectedLabel = if (home.selectedDay == today.toEpochDay()) {
            "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 · 今天"
        } else {
            selectedDate.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
        }

        return LedgerUiState(
            loading = false,
            monthLabel = "${today.year}年${today.monthValue}月",
            todayLabel = Format.todayLabel(),
            todayExpense = todayItems.filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents },
            todayIncome = todayItems.filter { it.direction == Direction.INCOME || it.direction == Direction.REFUND }
                .sumOf { it.amountCents },
            monthExpense = expenseItems.sumOf { it.amountCents },
            monthIncome = incomeItems.sumOf { it.amountCents },
            todayItems = todayItems.sortedByDescending { it.occurredAt },
            monthItems = items,
            dailyFlows = dailyFlows,
            categoryTotals = totals,
            pendingItems = pending,
            pendingCount = pending.size,
            selectedEpochDay = home.selectedDay,
            selectedDayLabel = selectedLabel,
            selectedDayExpense = selectedTotals.expense,
            selectedDayIncome = selectedTotals.income,
            selectedDayItems = home.dayItems.sortedByDescending { it.occurredAt },
            dayTotals = dayTotals,
            todayEpochDay = today.toEpochDay(),
            categories = categories
        )
    }

    /** 月历点选某一天：只切换「当天账单」区域，不改变统计页的月份口径 */
    fun selectDay(epochDay: Long) {
        selectedDay.value = epochDay
    }

    fun refresh() {
        refreshTick.value += 1
    }

    fun addTransaction(
        amountText: String,
        direction: Direction,
        categoryId: Long,
        merchant: String,
        note: String
    ) {
        val cents = Format.parseYuanToCents(amountText) ?: return
        viewModelScope.launch {
            repository.addManual(
                amountCents = cents,
                direction = direction,
                categoryId = categoryId,
                merchant = merchant.trim(),
                note = note.trim()
            )
        }
    }

    fun confirm(id: Long, amountCents: Long, categoryId: Long) {
        viewModelScope.launch { repository.confirm(id, amountCents, categoryId) }
    }

    fun ignore(id: Long) {
        viewModelScope.launch { repository.ignore(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    /**
     * 重置全部数据：清空账目（含待确认）和操作日志。
     * 分类定义和系统权限不动，重置后仍是一个可用的空账本。
     */
    fun resetAll() {
        viewModelScope.launch {
            repository.resetAll()
            withContext(Dispatchers.IO) { AppLog.clear() }
            refresh()
        }
    }

    /** 设置页的「解析自测」：只解析不入库，方便验证规则是否命中 */
    fun previewParse(text: String): String {
        val parsed = PaymentParser.parse(text, "测试通道") ?: return "没有识别到金额，这条文本不会入账。"
        val category = CategoryClassifier.suggest(text + " " + parsed.merchant)
        val percent = (parsed.confidence * 100).toInt()
        return "金额 ¥${Format.yuan(parsed.amountCents)} · ${parsed.direction.label} · " +
            "商户 ${parsed.merchant} · 建议分类 $category · 置信度 $percent%" +
            if (parsed.confidence >= 0.85f) "（会自动入账）" else "（会进入待确认）"
    }

    companion object {
        fun factory(repository: LedgerRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LedgerViewModel(repository) as T
            }
    }
}
