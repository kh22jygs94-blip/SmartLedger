package com.example.smartledger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 记账分类。默认分类在首次启动时写入，用户可增删 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 用 emoji 代替图标资源，避免引入图片依赖 */
    val emoji: String,
    /** 只使用 EXPENSE / INCOME 两种 */
    val direction: Direction,
    val sortOrder: Int,
    val isDefault: Boolean = true
)
