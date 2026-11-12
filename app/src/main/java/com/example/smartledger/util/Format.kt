package com.example.smartledger.util

import com.example.smartledger.data.Direction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val Direction.label: String
    get() = when (this) {
        Direction.EXPENSE -> "支出"
        Direction.INCOME -> "收入"
        Direction.TRANSFER -> "转账"
        Direction.REFUND -> "退款"
    }

/** 所有金额在库里都是「分」，展示时才转成两位小数 */
object Format {

    private val zone: ZoneId get() = ZoneId.systemDefault()
    /** 账目时间一律精确到秒：同金额的多笔订单只能靠秒级时间来区分 */
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA)
    private val dateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

    fun yuan(cents: Long): String =
        String.format(Locale.CHINA, "%,.2f", cents / 100.0)

    /** 不带千分位，用于回填到输入框（带逗号的文本解析不出来） */
    fun yuanForEdit(cents: Long): String =
        String.format(Locale.US, "%.2f", cents / 100.0)

    /** 列表里显示时分秒 */
    fun time(millis: Long): String = millis.toLocal().format(timeFormatter)

    fun dateTime(millis: Long): String {
        val dt = millis.toLocal()
        return "${dt.format(dateFormatter)} ${dt.format(timeFormatter)}"
    }

    fun todayLabel(): String = LocalDate.now(zone).format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))

    fun parseYuanToCents(text: String): Long? {
        val value = text.trim()
        if (value.isEmpty()) return null
        val decimal = runCatching { BigDecimal(value) }.getOrNull() ?: return null
        if (decimal <= BigDecimal.ZERO) return null
        return decimal.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** 以自然日边界取区间 [当天 00:00, 次日 00:00) */
    fun dayRange(date: LocalDate): Pair<Long, Long> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    fun monthRange(date: LocalDate): Pair<Long, Long> {
        val first = date.withDayOfMonth(1)
        val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = first.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    private fun Long.toLocal(): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone)
}
