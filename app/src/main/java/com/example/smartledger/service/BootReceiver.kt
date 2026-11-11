package com.example.smartledger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.smartledger.util.AppLog

/**
 * 开机与覆盖安装后的自启动入口。
 *
 * 这两个广播是 Android 12+ 仍然允许从后台启动前台服务的豁免场景，
 * 也是唯一合法的「应用自己把自己拉起来」的时机。
 * 解锁屏幕不在此列（后台启动 Activity 被系统禁止），但读屏服务由系统绑定，
 * 解锁后会自动恢复派发事件，不需要额外拉起界面。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KeepAliveService::class.java)
            )
            Log.i(TAG, "开机/覆盖安装后拉起常驻服务 action=$action")
            AppLog.log(
                "启动",
                if (action == Intent.ACTION_BOOT_COMPLETED) "开机完成，拉起常驻服务" else "应用更新完成，拉起常驻服务"
            )
        }.onFailure {
            Log.w(TAG, "拉起常驻服务失败：${it.message}")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
