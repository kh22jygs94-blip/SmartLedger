package com.example.smartledger

import android.content.Context
import com.example.smartledger.data.AppDatabase
import com.example.smartledger.data.LedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手写依赖容器。第一代不引入 Hilt，减少构建风险；
 * 依赖关系简单到只有「库 -> 仓库」两层，没必要上框架。
 */
class AppContainer(context: Context) {

    private val database = AppDatabase.get(context)

    val repository = LedgerRepository(
        transactionDao = database.transactionDao(),
        categoryDao = database.categoryDao()
    )

    /** 应用级协程作用域，用于初始化数据等不受界面生命周期影响的任务 */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
