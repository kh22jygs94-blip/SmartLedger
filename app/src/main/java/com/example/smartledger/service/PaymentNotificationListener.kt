package com.example.smartledger.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.smartledger.SmartLedgerApp
import com.example.smartledger.data.IngestResult
import com.example.smartledger.util.AppLog
import com.example.smartledger.data.TxSource
import com.example.smartledger.parser.ChannelResolver
import com.example.smartledger.parser.PaymentParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 自动采集的核心：监听系统通知，把支付类通知交给仓库解析入账。
 *
 * 这里刻意保持「轻」——只做三件事：过滤包名、拼文本、抛给仓库。
 * 所有解析和判断都在 parser / repository 里，方便单测和第二代换成模型抽取。
 */
class PaymentNotificationListener : NotificationListenerService() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        // 兜底：任何漏网的异常都要留下日志，否则线上完全无法定位
        Log.e(TAG, "记账协程异常退出", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        // 常驻通知（音乐播放、下载进度）不可能是消费凭证
        if (sbn.isOngoing) return

        val channel = ChannelResolver.resolve(sbn.packageName) ?: return
        val content = buildContent(notification)
        if (!PaymentParser.looksLikePayment(content)) return

        val repository = (applicationContext as? SmartLedgerApp)?.container?.repository ?: run {
            Log.w(TAG, "应用容器未初始化，跳过本条通知")
            return
        }
        val occurredAt = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
        scope.launch {
            try {
                val result = repository.ingest(content, channel, occurredAt, TxSource.NOTIFICATION)
                AppLog.log("通知", "收到 $channel 账单通知，${resultLabel(result)}")
                if (result == IngestResult.PENDING) {
                    Log.i(TAG, "低置信度记录已进入待确认：$channel")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 单条失败不影响后续通知，但必须记下来
                Log.w(TAG, "解析或写入失败：$channel", t)
            }
        }
    }

    /** 通知标题 + 正文，正文优先取展开样式，信息更全 */
    private fun buildContent(notification: Notification): String {
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val body = bigText.ifBlank { text }
        return listOf(title, body).filter { it.isNotBlank() }.joinToString(" ")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "通知监听已连接")
        AppLog.log("服务", "通知监听已连接")
    }

    /** 被系统回收后主动重新绑定，否则用户感知就是自动记账悄悄失效 */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "通知监听被断开，尝试重新绑定")
        AppLog.log("服务", "通知监听被断开，正在重新绑定")
        try {
            requestRebind(ComponentName(this, PaymentNotificationListener::class.java))
        } catch (t: Throwable) {
            Log.e(TAG, "重新绑定通知监听失败", t)
        }
    }

    private fun resultLabel(result: IngestResult): String = when (result) {
        IngestResult.CONFIRMED -> "已自动入账"
        IngestResult.PENDING -> "进入待确认"
        IngestResult.DUPLICATE -> "重复账单，已跳过"
        IngestResult.STALE -> "不是当天账单，已跳过"
        IngestResult.REJECTED -> "不像账单，已跳过"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PaymentListener"
    }
}
