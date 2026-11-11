package com.example.smartledger.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.smartledger.R
import com.example.smartledger.util.AppLog

/**
 * 读屏被系统关掉、又没拿到自愈授权时的提醒。
 *
 * 用户点通知直接跳到无障碍设置页，比「打开 App → 设置 → 找入口」少三步。
 * 同一条提醒 10 分钟内不重复弹，避免每次亮屏都打扰。
 */
object ServiceAlerts {

    private const val CHANNEL_ID = "screen_reader_alert"
    private const val NOTIFICATION_ID = 1002
    private const val MIN_GAP_MS = 10 * 60 * 1000L

    @Volatile
    private var lastNotifyAt = 0L

    fun notifyAccessibilityLost(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < MIN_GAP_MS) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "读屏识别提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "读屏识别被系统关闭时提醒恢复"
                }
            )
        }

        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("读屏识别已被关闭")
            .setContentText("自动记账已停止，点这里重新开启读屏识别")
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onSuccess {
                lastNotifyAt = now
                AppLog.log("服务", "已发送读屏恢复提醒")
            }
    }
}
