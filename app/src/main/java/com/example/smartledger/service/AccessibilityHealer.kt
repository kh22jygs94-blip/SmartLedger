package com.example.smartledger.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.example.smartledger.util.AppLog

/**
 * 读屏服务被系统关掉后的恢复能力。
 *
 * 注意一个系统底线：Android 10 起禁止应用从后台启动界面，
 * 所以「亮屏时自动弹出 Agent 记账」做不到，也没必要做——
 * 真正需要恢复的是读屏服务本身，用户看不到界面也能继续记账。
 *
 * 这里用程序化的「关掉再打开」替代用户手动操作，
 * 前提是用户通过 adb 一次性授予 WRITE_SECURE_SETTINGS，并在设置页打开开关。
 */
object AccessibilityHealer {

    private const val PREFS = "keep_alive"
    private const val KEY_AUTO_RESTORE = "auto_restore_accessibility"

    /** 改写系统设置的能力只能由 adb 授予，普通安装拿不到 */
    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isAutoRestoreEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_RESTORE, false)

    fun setAutoRestoreEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_RESTORE, enabled)
            .apply()
    }

    /** 自愈真正生效的条件：开关打开 + adb 授权都在 */
    fun isActive(context: Context): Boolean =
        isAutoRestoreEnabled(context) && canWriteSecureSettings(context)

    /**
     * 自检入口：读屏没在跑就尝试恢复一次。
     * 返回 true 表示确实执行了改写动作。
     */
    fun restoreIfNeeded(context: Context): Boolean {
        if (!isActive(context)) return false
        if (ServiceHealth.isRunning(context)) return false
        return restore(context)
    }

    /**
     * 把本应用重新写回系统的无障碍列表。
     *
     * 系统对同一个值写两次不会重新绑定，必须先移除再写回，
     * 等价于用户手动「关掉开关再打开」。两次写入之间留一点时间给系统解绑。
     */
    fun restore(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        if (restoring) return false
        restoring = true
        return try {
            val resolver = context.contentResolver
            val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val target = ComponentName(context, ScreenReaderService::class.java).flattenToString()
            val services = Settings.Secure.getString(resolver, key)
                .orEmpty()
                .split(':')
                .filter { it.isNotBlank() }
            val without = services.filterNot { it.equals(target, ignoreCase = true) }

            val ok = runCatching {
                Settings.Secure.putString(resolver, key, without.joinToString(":"))
                Thread.sleep(200)
                Settings.Secure.putString(resolver, key, (without + target).joinToString(":"))
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                true
            }.getOrElse { false }

            AppLog.log("服务", if (ok) "亮屏自检：已重新开启读屏服务" else "亮屏自检：重新开启读屏失败")
            ok
        } finally {
            restoring = false
        }
    }

    @Volatile
    private var restoring = false
}
