package com.example.smartledger.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * 自动记账的保活自检：判断读屏服务「授权还在不在」和「进程里到底跑没跑」。
 *
 * 权限状态全绿不代表功能在跑——厂商清理器把进程杀掉后，无障碍授权仍留在系统里，
 * 用户从设置页看不出来。设置页用这里的结果直接给出可操作的状态。
 */
object ServiceHealth {

    private const val PREFS = "keep_alive"
    private const val KEY_LAST_CONNECT = "last_connected_at"

    /** 一会儿看授权、一会儿看运行状态，合成一次读取供 UI 用 */
    data class Status(
        /** 系统无障碍列表里是否还启用着本应用的服务 */
        val enabled: Boolean,
        /** 服务此刻是否真的在进程里运行 */
        val running: Boolean,
        /** 上一次服务连接成功的时间，0 表示本次安装后还没连过 */
        val lastConnectedAt: Long
    )

    fun check(context: Context): Status = Status(
        enabled = isEnabled(context),
        running = isRunning(context),
        lastConnectedAt = lastConnectedAt(context)
    )

    /** 读屏服务连接成功时调用，用来给设置页显示「上次连接」 */
    fun markConnected(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CONNECT, System.currentTimeMillis())
            .apply()
    }

    private fun lastConnectedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CONNECT, 0L)

    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val target = ComponentName(context, ScreenReaderService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(serviceInfo.packageName, serviceInfo.name) == target
            }
    }

    /**
     * Android 8 起 getRunningServices 只能查到调用方自己的服务，正好够用。
     * 系统把服务记为 crashed 后它不会出现在列表里，这里能识别出来。
     */
    @Suppress("DEPRECATION")
    fun isRunning(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val name = ScreenReaderService::class.java.name
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == name }
    }
}
