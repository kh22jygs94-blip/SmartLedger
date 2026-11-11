package com.example.smartledger.service

import com.example.smartledger.parser.ParsedPayment

/**
 * 读屏快照：无障碍服务抓到的最新一屏文本。
 *
 * 只放内存，不落盘——读屏的原文属于最敏感的数据，验证阶段不允许持久化。
 */
object ScreenSnapshotStore {

    @Volatile
    var packageName: String = ""
        private set

    @Volatile
    var rawText: String = ""
        private set

    @Volatile
    var parsed: ParsedPayment? = null
        private set

    @Volatile
    var capturedAt: Long = 0L
        private set

    fun update(pkg: String, raw: String, payment: ParsedPayment?) {
        packageName = pkg
        rawText = raw
        parsed = payment
        capturedAt = System.currentTimeMillis()
    }

    /** 快照是否还新鲜，超过 2 分钟就认为用户已经离开那个页面 */
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        capturedAt > 0L && now - capturedAt < 120_000L
}
