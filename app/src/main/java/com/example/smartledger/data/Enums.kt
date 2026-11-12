package com.example.smartledger.data

/**
 * 资金方向。
 *
 * 刻意不用「正负号」表示，因为退款、账户互转、信用卡还款
 * 如果混进支出统计，月度报表会彻底失真。
 */
enum class Direction {
    /** 支出：计入消费统计 */
    EXPENSE,
    /** 收入：工资、红包、他人转账进来 */
    INCOME,
    /** 转账：账户之间搬运，不计入收支 */
    TRANSFER,
    /** 退款：退回的钱，计入收入侧，不计入支出 */
    REFUND
}

/** 数据来源，用于追溯一条记录是怎么来的 */
enum class TxSource {
    MANUAL,        // 手动记账
    NOTIFICATION,  // 通知监听
    SHARE,         // 从其他 App 分享文本过来
    SCREEN_OCR,    // 读屏/截图 OCR 识别
    SMS,           // 短信解析（第二代）
    IMPORT         // 账单文件导入（第二代）
}

/** 记录状态：待确认的记录不进入正式账目 */
enum class TxStatus {
    CONFIRMED,  // 已确认
    PENDING,    // 待用户确认
    IGNORED     // 已忽略
}
