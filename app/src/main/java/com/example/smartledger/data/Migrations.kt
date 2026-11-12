package com.example.smartledger.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移注册表。
 *
 * 约定：任何实体结构变更都必须把 AppDatabase.version 加一，并在这里补一条 Migration。
 * 不启用 fallbackToDestructiveMigration——账本数据不能因为升级被清空。
 * 生成的表结构快照保存在 app/schemas 下，写迁移时以此为准。
 */
object Migrations {

    /**
     * v2：新增 orderNo（交易单号/商户单号）。
     * 老数据补空串，去重逻辑对空串会退回到「金额+方向+商户+时间窗」判断。
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN orderNo TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_orderNo ON transactions(orderNo)")
        }
    }

    /**
     * v3：补两条联合索引，只加索引不改表结构与数据。
     * - status + occurredAt：首页/统计按状态取时间区间
     * - amountCents + occurredAt：去重查询按金额定位时间窗口
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_transactions_status_occurredAt " +
                    "ON transactions(status, occurredAt)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_transactions_amountCents_occurredAt " +
                    "ON transactions(amountCents, occurredAt)"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
