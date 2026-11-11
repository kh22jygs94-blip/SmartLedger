package com.example.smartledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 统计页用的分类聚合结果，由 SQL 直接算好，避免在主线程遍历整月流水 */
data class CategorySum(
    val categoryId: Long,
    val total: Long
)

/** 月历用的轻量行：只要时间、金额、方向 */
data class AmountPoint(
    val occurredAt: Long,
    val amountCents: Long,
    val direction: Direction
)

@Dao
interface TransactionDao {

    /**
     * 首页与统计共用：按时间区间取「已确认」的账目。
     * 加上限只是兜底：正常一个月的流水远小于 1000 条，避免极端数据量把整月流水全量拉进内存。
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE status = 'CONFIRMED' AND occurredAt >= :start AND occurredAt < :end
        ORDER BY occurredAt DESC
        LIMIT 1000
        """
    )
    fun observeConfirmed(start: Long, end: Long): Flow<List<TransactionEntity>>

    /** 待确认列表 */
    @Query("SELECT * FROM transactions WHERE status = 'PENDING' ORDER BY occurredAt DESC LIMIT 500")
    fun observePending(): Flow<List<TransactionEntity>>

    /** 首页月历：区间内已确认流水的金额与方向，界面自己按天汇总 */
    @Query(
        """
        SELECT occurredAt, amountCents, direction FROM transactions
        WHERE status = 'CONFIRMED' AND occurredAt >= :start AND occurredAt < :end
        """
    )
    fun observeRangeAmounts(start: Long, end: Long): Flow<List<AmountPoint>>

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    /** 按分类汇总本月支出，排序也交给数据库 */
    @Query(
        """
        SELECT categoryId AS categoryId, COALESCE(SUM(amountCents), 0) AS total
        FROM transactions
        WHERE status = 'CONFIRMED' AND direction = 'EXPENSE'
          AND occurredAt >= :start AND occurredAt < :end
        GROUP BY categoryId
        ORDER BY total DESC
        """
    )
    fun observeExpenseByCategory(start: Long, end: Long): Flow<List<CategorySum>>

    /**
     * 去重查询：金额、方向、归一化商户都相同，且时间落在容差窗口内，
     * 就认为是同一笔消费（例如微信通知 + 银行卡短信双重上报）。
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amountCents = :amount
          AND direction = :direction
          AND merchantNormalized = :merchant
          AND occurredAt BETWEEN :from AND :to
        LIMIT 1
        """
    )
    suspend fun findDuplicate(
        amount: Long,
        direction: String,
        merchant: String,
        from: Long,
        to: Long
    ): TransactionEntity?

    /** 按订单号精确判重，不受时间窗限制：同一订单可能被通知、截图先后各上报一次 */
    @Query("SELECT * FROM transactions WHERE orderNo = :orderNo AND orderNo != '' LIMIT 1")
    suspend fun findByOrderNo(orderNo: String): TransactionEntity?

    /**
     * 截图账单带着页面上的「年-月-日-时-分-秒」，同一秒内、同一金额才是同一笔；
     * 秒数不同就是两笔真实订单，哪怕金额一样也要分别记。
     * 按整秒取区间，避免毫秒尾数不同导致同一秒的两条记录漏判。
     * 不带方向：同一页面的两次识别可能一次读到「+10.03」、一次读到「10.03元」，
     * 方向会被判成收入/转账两种，带上方向就挡不住重复。
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amountCents = :amount
          AND occurredAt >= (:occurredAt / 1000) * 1000
          AND occurredAt < ((:occurredAt / 1000) + 1) * 1000
        LIMIT 1
        """
    )
    suspend fun findSameMoment(amount: Long, occurredAt: Long): TransactionEntity?

    /** 通知先记了一笔（没有页面时间）时，用金额 + 商户 + 时间窗把通知和截图配成同一笔 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amountCents = :amount AND direction = :direction
          AND merchantNormalized = :merchant
          AND source = 'NOTIFICATION' AND orderNo = ''
          AND occurredAt BETWEEN :from AND :to
        LIMIT 1
        """
    )
    suspend fun findNotificationTwin(
        amount: Long,
        direction: String,
        merchant: String,
        from: Long,
        to: Long
    ): TransactionEntity?

    @Insert
    suspend fun insert(entity: TransactionEntity): Long

    @Update
    suspend fun update(entity: TransactionEntity)

    @Query("UPDATE transactions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /** 确认待确认记录：允许用户在确认时修正金额和分类 */
    @Query(
        """
        UPDATE transactions
        SET amountCents = :amountCents, categoryId = :categoryId,
            status = 'CONFIRMED', confidence = 1.0
        WHERE id = :id
        """
    )
    suspend fun confirmPending(id: Long, amountCents: Long, categoryId: Long)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    /** 重置：清空全部账目，包括已确认和待确认 */
    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Transaction
    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM transactions WHERE status = 'CONFIRMED' AND direction = :direction AND occurredAt >= :start AND occurredAt < :end")
    suspend fun sumByDirection(direction: String, start: Long, end: Long): Long
}
