package com.example.smartledger.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * 开机自启 / 覆盖安装后先把进程挂成前台服务。
 *
 * 读屏服务本身不能被代码主动启动，只能由系统绑定；
 * 这里提前把进程和前台通知立起来，系统随后绑定读屏服务时进程已经存在，
 * 不会出现「开机后无人拉起进程、读屏静默失效」的情况。
 *
 * 同时负责登记亮屏广播：进程活着的时候，每次亮屏都顺手确认读屏还在不在。
 */
class KeepAliveService : Service() {

    private var screenStateReceiver: ScreenStateReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 启动后必须尽快转前台，否则系统会抛 ANR 级别的异常
        ServiceKeepAlive.start(this)
        registerScreenStateReceiver()
        return START_STICKY
    }

    override fun onDestroy() {
        screenStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenStateReceiver = null
        super.onDestroy()
    }

    /** 系统广播只允许动态注册；重复收到 onStartCommand 时不重复登记 */
    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = ScreenStateReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenStateReceiver = receiver
        }
    }
}
