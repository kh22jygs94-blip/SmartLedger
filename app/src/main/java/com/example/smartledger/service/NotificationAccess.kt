package com.example.smartledger.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** 判断用户是否已经授予「通知使用权」。系统没有提供 API，只能读安全设置字符串 */
object NotificationAccess {

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val component = ComponentName(context, PaymentNotificationListener::class.java)
        return enabled.split(':').any {
            it.equals(component.flattenToString(), ignoreCase = true)
        }
    }
}
