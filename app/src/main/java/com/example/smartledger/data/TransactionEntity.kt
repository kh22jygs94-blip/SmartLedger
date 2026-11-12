package com.example.smartledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条账目。
 *
 * 三个字段是这套设计的核心：
 * - direction：区分支出/收入/转账/退款，避免统计失真
 * - merchantNormalized：归一化后的商户名，统计和分类都靠它
 * - confidence + status：置信度低的记录不直接入账，交给用户确认
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index("occurredAt"),
        Index("status"),
        Index("fingerprint"),
        Index("orderNo"),
        // 首页/统计的主查询是「状态 + 时间区间」，联合索引直接命中，不必回表过滤
        Index(value = ["status", "occurredAt"]),
        // 去重查询按「金额 + 时间窗口」定位，单独给金额建联合索引避免全表扫
        Index(value = ["amountCents", "occurredAt"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 金额，单位「分」。用整数避免浮点误差 */
    val amountCents: Long,

    val direction: Direction,

    val categoryId: Long,

    /** 原始商户名，保留原样用于展示 */
    val merchant: String,

    /** 归一化商户名，用于去重和统计 */
    val merchantNormalized: String,

    /** 支付渠道：微信 / 支付宝 / 招商银行 …… */
    val channel: String,

    val note: String,

    /** 交易发生时间（毫秒时间戳） */
    val occurredAt: Long,

    val source: TxSource,

    /** 原始通知/短信文本，用于事后回溯与规则修正 */
    val rawText: String,

    /** 解析置信度 0~1 */
    val confidence: Float,

    val status: TxStatus,

    /** 去重指纹：金额 + 方向 + 商户 + 时间桶 */
    val fingerprint: String,

    /**
     * 交易单号/商户单号/订单号。有它时可以精确判断「两笔同金额同商户」是不是同一单，
     * 通知文本一般没有，截图识别的账单详情页有。
     */
    val orderNo: String = "",

    val createdAt: Long = System.currentTimeMillis()
)
