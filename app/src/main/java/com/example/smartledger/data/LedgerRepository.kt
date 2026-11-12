package com.example.smartledger.data

import com.example.smartledger.parser.CategoryClassifier
import com.example.smartledger.parser.MerchantNormalizer
import com.example.smartledger.parser.PaymentParser
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId

/** 通知/短信入库的结果，方便调用方做提示 */
enum class IngestResult {
    CONFIRMED,  // 置信度高，直接入账
    PENDING,    // 置信度不够，进待确认
    DUPLICATE,  // 命中去重，已存在
    STALE,      // 账单没写完整时间，或不是当天的账单
    REJECTED    // 不是一笔交易
}

/**
 * 业务仓库：把所有「怎么记账」的规则收在这里，界面和采集服务都不直接碰数据库。
 *
 * 一条自动采集的数据完整链路：
 * 通知文本 -> PaymentParser 解析 -> 归一化商户 -> 查重 -> 自动分类
 *          -> 按置信度决定 CONFIRMED / PENDING -> 落库
 */
class LedgerRepository(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao
) {

    fun observeConfirmed(start: Long, end: Long): Flow<List<TransactionEntity>> =
        transactionDao.observeConfirmed(start, end)

    fun observePending(): Flow<List<TransactionEntity>> = transactionDao.observePending()

    /** 首页月历用的区间金额明细 */
    fun observeRangeAmounts(start: Long, end: Long): Flow<List<AmountPoint>> =
        transactionDao.observeRangeAmounts(start, end)

    /** 分类支出汇总交给 SQL 聚合，界面层不再遍历整月数据 */
    fun observeExpenseByCategory(start: Long, end: Long): Flow<List<CategorySum>> =
        transactionDao.observeExpenseByCategory(start, end)

    fun observePendingCount(): Flow<Int> = transactionDao.observePendingCount()

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    /** 首次启动写入默认分类 */
    suspend fun ensureSeeded() {
        if (categoryDao.count() > 0) return
        categoryDao.insertAll(DEFAULT_CATEGORIES)
    }

    /**
     * 重置账本：删除全部账目（含待确认）。
     * 分类定义、系统权限、常驻服务都不动，只清用户数据。
     */
    suspend fun resetAll() {
        transactionDao.deleteAll()
    }

    /** 手动记账：用户自己填的，直接认定已确认 */
    suspend fun addManual(
        amountCents: Long,
        direction: Direction,
        categoryId: Long,
        merchant: String,
        note: String,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        val normalized = MerchantNormalizer.normalize(merchant)
        transactionDao.insert(
            TransactionEntity(
                amountCents = amountCents,
                direction = direction,
                categoryId = categoryId,
                merchant = merchant.ifBlank { "未填写商户" },
                merchantNormalized = normalized,
                channel = "手动",
                note = note,
                occurredAt = occurredAt,
                source = TxSource.MANUAL,
                rawText = "",
                confidence = 1f,
                status = TxStatus.CONFIRMED,
                fingerprint = fingerprintOf(amountCents, direction, normalized, occurredAt)
            )
        )
    }

    /**
     * 自动采集入口。通知监听服务只负责把文本交过来，其余判断都在这里完成。
     *
     * forceConfirm 用于「页面本身已经写明结果」的场景（账单详情页、转账结果页）：
     * 这类页面没有「收款方」等字段，算出来的置信度天然偏低，但结果可信。
     */
    suspend fun ingest(
        rawText: String,
        channel: String,
        occurredAt: Long,
        source: TxSource,
        forceConfirm: Boolean = false
    ): IngestResult {
        val parsed = PaymentParser.parse(rawText, channel) ?: return IngestResult.REJECTED
        val normalized = MerchantNormalizer.normalize(parsed.merchant)

        // 只看时间：账单页写了「年-月-日-时-分-秒」就要求是当天的账单；
        // 没写时间的（支付成功弹窗、转账卡片）按账单产生时的系统时间记账。
        val pageAt = parsed.occurredAt
        if (source == TxSource.SCREEN_OCR && pageAt != null && !isSameDay(pageAt, occurredAt)) {
            return IngestResult.STALE
        }

        val effectiveAt = pageAt ?: occurredAt

        if (isDuplicate(parsed, normalized, effectiveAt)) return IngestResult.DUPLICATE

        val confidence = if (forceConfirm) maxOf(parsed.confidence, AUTO_CONFIRM_THRESHOLD) else parsed.confidence
        val status = if (confidence >= AUTO_CONFIRM_THRESHOLD) TxStatus.CONFIRMED else TxStatus.PENDING
        val categoryId = resolveCategoryId(parsed.direction, rawText, parsed.merchant)

        transactionDao.insert(
            TransactionEntity(
                amountCents = parsed.amountCents,
                direction = parsed.direction,
                categoryId = categoryId,
                merchant = parsed.merchant,
                merchantNormalized = normalized,
                channel = parsed.channel.ifBlank { channel },
                note = "",
                occurredAt = effectiveAt,
                source = source,
                rawText = rawText,
                confidence = confidence,
                status = status,
                fingerprint = fingerprintOf(parsed.amountCents, parsed.direction, normalized, effectiveAt),
                orderNo = parsed.orderNo
            )
        )
        return if (status == TxStatus.CONFIRMED) IngestResult.CONFIRMED else IngestResult.PENDING
    }

    /**
     * 判重顺序：
     * 1. 订单号相同即同一笔，不受时间限制；
     * 2. 页面写了交易时间：精确到秒比对，同一秒 + 同一金额才算同一笔，
     *    秒数不同就是两笔真实订单，金额、商户完全一样也放行；另外把「通知先到、截图后到」的同一笔配掉；
     * 3. 两边都没有时间：只能按「金额 + 方向 + 商户 + 3 秒内」判断，
     *    窗口内的重复投递算同一笔，超过就是另一笔相同金额的订单。
     */
    private suspend fun isDuplicate(
        parsed: com.example.smartledger.parser.ParsedPayment,
        normalizedMerchant: String,
        occurredAt: Long
    ): Boolean {
        val orderNo = parsed.orderNo
        if (orderNo.isNotBlank() && transactionDao.findByOrderNo(orderNo) != null) return true

        val pageAt = parsed.occurredAt
        if (pageAt != null) {
            if (transactionDao.findSameMoment(parsed.amountCents, pageAt) != null) {
                return true
            }
            // 通知先记了一笔（没有页面时间），截图后到时按金额 + 商户配对成同一笔
            return transactionDao.findNotificationTwin(
                amount = parsed.amountCents,
                direction = parsed.direction.name,
                merchant = normalizedMerchant,
                from = pageAt - NOTIFICATION_PAIR_WINDOW_MS,
                to = pageAt + NOTIFICATION_PAIR_WINDOW_MS
            ) != null
        }

        if (orderNo.isNotBlank()) {
            val nearby = transactionDao.findDuplicate(
                amount = parsed.amountCents,
                direction = parsed.direction.name,
                merchant = normalizedMerchant,
                from = occurredAt - NOTIFICATION_PAIR_WINDOW_MS,
                to = occurredAt + NOTIFICATION_PAIR_WINDOW_MS
            )
            // 对方有不同订单号 → 是另一笔相同金额的订单，放行
            return nearby != null && nearby.orderNo.isBlank()
        }

        return transactionDao.findDuplicate(
            amount = parsed.amountCents,
            direction = parsed.direction.name,
            merchant = normalizedMerchant,
            from = occurredAt - DEDUPE_WINDOW_MS,
            to = occurredAt + DEDUPE_WINDOW_MS
        ) != null
    }

    /** 按手机的本地时区比日历日：年月日都对上才算当天 */
    private fun isSameDay(a: Long, b: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
    }

    suspend fun confirm(id: Long, amountCents: Long, categoryId: Long) =
        transactionDao.confirmPending(id, amountCents, categoryId)

    suspend fun ignore(id: Long) = transactionDao.updateStatus(id, TxStatus.IGNORED.name)

    suspend fun delete(id: Long) = transactionDao.delete(id)

    /**
     * 支出优先按商户名分类，商户判不出来时才看整页文本。
     * 原因：账单详情页底部有「点外卖领国补」这类推广文案，用全文会把京东商城误判成餐饮。
     */
    private suspend fun resolveCategoryId(direction: Direction, rawText: String, merchant: String): Long = when (direction) {
        Direction.EXPENSE -> {
            val byMerchant = CategoryClassifier.suggest(merchant)
            val suggested = if (byMerchant != "其他") byMerchant else CategoryClassifier.suggest(rawText)
            categoryDao.findByName(suggested, Direction.EXPENSE.name)?.id
                ?: categoryDao.findByName("其他", Direction.EXPENSE.name)?.id
                ?: 0L
        }

        Direction.INCOME, Direction.REFUND -> {
            val name = when {
                direction == Direction.REFUND -> "退款"
                rawText.contains("工资") -> "工资"
                rawText.contains("红包") -> "红包"
                else -> "其他"
            }
            categoryDao.findByName(name, Direction.INCOME.name)?.id ?: 0L
        }

        Direction.TRANSFER -> 0L
    }

    private fun fingerprintOf(
        amountCents: Long,
        direction: Direction,
        merchant: String,
        occurredAt: Long
    ): String = "${amountCents}_${direction.name}_${merchant}_${occurredAt / FINGERPRINT_BUCKET_MS}"

    companion object {
        /**
         * 没有页面时间时的兜底窗口：通知用的 postTime、结果页用的系统时间本身都是秒级，
         * 同一笔被重复投递通常落在同一两秒内；超过 3 秒就按另一笔相同金额的订单处理。
         */
        private const val DEDUPE_WINDOW_MS = 3 * 1000L

        /**
         * 通知与截图配对窗口：通知的 postTime 就是付款时刻，和账单页上的秒级时间基本一致，
         * 差几秒还算同一笔；放宽到分钟级会把「另一笔同金额订单」也吞掉。
         */
        private const val NOTIFICATION_PAIR_WINDOW_MS = 10 * 1000L

        /** 指纹也按秒分桶，两笔同金额订单的指纹不会撞在一起 */
        private const val FINGERPRINT_BUCKET_MS = 1000L
        private const val AUTO_CONFIRM_THRESHOLD = 0.85f

        private val DEFAULT_CATEGORIES = listOf(
            CategoryEntity(name = "餐饮", emoji = "🍜", direction = Direction.EXPENSE, sortOrder = 0),
            CategoryEntity(name = "交通", emoji = "🚇", direction = Direction.EXPENSE, sortOrder = 1),
            CategoryEntity(name = "购物", emoji = "🛍️", direction = Direction.EXPENSE, sortOrder = 2),
            CategoryEntity(name = "日用", emoji = "🧴", direction = Direction.EXPENSE, sortOrder = 3),
            CategoryEntity(name = "娱乐", emoji = "🎮", direction = Direction.EXPENSE, sortOrder = 4),
            CategoryEntity(name = "医疗", emoji = "💊", direction = Direction.EXPENSE, sortOrder = 5),
            CategoryEntity(name = "住房", emoji = "🏠", direction = Direction.EXPENSE, sortOrder = 6),
            CategoryEntity(name = "通讯", emoji = "📱", direction = Direction.EXPENSE, sortOrder = 7),
            CategoryEntity(name = "学习", emoji = "📚", direction = Direction.EXPENSE, sortOrder = 8),
            CategoryEntity(name = "其他", emoji = "📦", direction = Direction.EXPENSE, sortOrder = 9),
            CategoryEntity(name = "工资", emoji = "💰", direction = Direction.INCOME, sortOrder = 0),
            CategoryEntity(name = "红包", emoji = "🧧", direction = Direction.INCOME, sortOrder = 1),
            CategoryEntity(name = "退款", emoji = "↩️", direction = Direction.INCOME, sortOrder = 2),
            CategoryEntity(name = "其他", emoji = "💵", direction = Direction.INCOME, sortOrder = 3)
        )
    }
}
