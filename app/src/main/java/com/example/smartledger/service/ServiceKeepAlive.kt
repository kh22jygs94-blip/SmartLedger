package com.example.smartledger.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.smartledger.MainActivity
import com.example.smartledger.R

/**
 * 常驻前台通知的唯一来源。
 *
 * 读屏服务和开机自启服务共用同一个渠道与通知 ID：
 * 用户始终只看到一条「自动记账已开启」，不会因为两个服务各挂一个通知而重复。
 */
object ServiceKeepAlive {

    const val CHANNEL_ID = "screen_reader_alive"
    const val NOTIFICATION_ID = 1001

    private const val TAG = "ServiceKeepAlive"

    fun start(service: Service) = runCatching {
        val manager = service.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "自动记账", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "读屏识别在后台运行"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("自动记账已开启")
            .setContentText("微信 / 支付宝的账单会自动记入本地账本")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }.onFailure { Log.w(TAG, "前台常驻通知启动失败：${it.message}") }
}
