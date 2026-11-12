package com.example.smartledger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.smartledger.util.AppLog
import java.util.concurrent.Executors

/**
 * 亮屏自检：锁屏期间读屏服务被清理器或系统关掉时，
 * 用户点亮屏幕这一步就会触发一次检查，不必先打开 App。
 *
 * ACTION_SCREEN_ON / ACTION_USER_PRESENT 不支持在清单里静态注册，
 * 所以由常驻的 KeepAliveService 在运行时登记本接收器。
 */
class ScreenStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_USER_PRESENT) return

        // 系统允许接收器完成后结束进程，这里用 goAsync 把写设置的耗时操作挪到后台线程
        val pending = goAsync()
        val appContext = context.applicationContext
        executor.execute {
            try {
                check(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "screen-state-check").apply { isDaemon = true }
        }

        /** App 启动时也查一次：常驻服务被清掉的情况下，亮屏广播不会有人接 */
        fun checkInBackground(context: Context) = executor.execute { check(context.applicationContext) }

        private fun check(context: Context) {
            if (ServiceHealth.isRunning(context)) return

            // 有授权就替用户把读屏重新打开
            if (AccessibilityHealer.restoreIfNeeded(context)) return

            if (!ServiceHealth.isEnabled(context)) {
                ServiceAlerts.notifyAccessibilityLost(context)
                AppLog.log("服务", "亮屏自检：读屏授权已失效，自动记账已停止")
            }
        }
    }
}
