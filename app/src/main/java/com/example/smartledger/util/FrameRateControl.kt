package com.example.smartledger.util

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * 设置页的锁帧率：60Hz 省电、120Hz 更顺滑。
 * 只作用于本应用窗口，不改系统设置；写入本机后重启仍然生效。
 */
object FrameRateControl {

    private const val PREFS = "display"
    private const val KEY_RATE = "refresh_rate_hz"
    const val DEFAULT_HZ = 60

    private val _rate = MutableStateFlow(DEFAULT_HZ)
    val rate: StateFlow<Int> = _rate

    fun init(context: Context) {
        _rate.value = read(context)
    }

    fun read(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_RATE, DEFAULT_HZ)

    fun set(context: Context, hz: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RATE, hz)
            .apply()
        _rate.value = hz
    }

    /**
     * 系统没有直接的「窗口锁帧率」接口，这里同时声明期望刷新率，
     * 并把窗口绑到最接近的显示模式上；改完属性后系统会让这块窗口按该模式出图。
     */
    fun apply(activity: Activity, hz: Int) {
        val window = activity.window
        runCatching {
            val attributes = window.attributes
            attributes.preferredRefreshRate = hz.toFloat()
            @Suppress("DEPRECATION")
            val display = window.windowManager.defaultDisplay
            val mode = display.supportedModes.firstOrNull { abs(it.refreshRate - hz) < 2f }
            if (mode != null) attributes.preferredDisplayModeId = mode.modeId
            window.attributes = attributes
        }
    }
}
