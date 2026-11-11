package com.example.smartledger.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.smartledger.SmartLedgerApp
import com.example.smartledger.data.Direction
import com.example.smartledger.data.IngestResult
import com.example.smartledger.data.TxSource
import com.example.smartledger.parser.ParsedPayment
import com.example.smartledger.parser.PaymentParser
import com.example.smartledger.util.AppLog
import com.example.smartledger.util.MultiPatternMatcher
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 读屏识别原型（验证阶段）。
 *
 * 两条路：
 * 1. 无障碍节点树——系统设置、支付宝这类标准/半标准界面能直接读到文本；
 * 2. 截图 + 本地 OCR——微信是自绘界面，节点树为空，只能截当前屏再离线识别。
 *
 * OCR 用 ML Kit bundled 中文模型，模型打包在 APK 里，不联网、不上传。
 * 方法仍只做「读」，不做点击和手势；结果打到 logcat，原文只留内存不落盘。
 */
class ScreenReaderService : AccessibilityService() {

    private var lastReadAt = 0L
    private var lastOcrAt = 0L
    private var ocrInFlight = false

    /** 截图接口同一时刻只能有一个调用方 */
    private var screenshotBusy = false

    private var foregroundPkg = ""

    /** 上一次调用截图接口的时间，系统要求两次之间留足够间隔 */
    private var lastScreenshotAt = 0L

    /** 界面变化后等待停稳的截图任务 */
    private var captureJob: Job? = null

    /** 最近一次界面变化的时间，截图任务用它判断「安静了多久」 */
    private var lastPageEventAt = 0L

    /** OCR 连续失败次数：连续失败到阈值就进入冷却，避免坏页面持续吃 CPU */
    private var ocrFailures = 0
    private var ocrCooldownUntil = 0L

    /** 页面判定用的多模式串匹配器，替代逐个 contains 的全文扫描 */
    private val resultMatcher = MultiPatternMatcher(RESULT_WORDS)

    /** 上一帧的缩略图指纹。相同就说明画面没变，不必再跑 OCR */
    private var lastFrameHash = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同一屏内容被反复读到时的兜底去重，仓库层还有一层秒级时间比对 */
    private var lastIngestSignature = ""
    private var lastIngestAt = 0L

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "读屏服务已连接，白名单=${TARGET_PACKAGES.joinToString()}")
        // 记录连接时间，设置页的保活自检用它显示「上次连接」
        ServiceHealth.markConnected(this)
        AppLog.log("服务", "读屏服务已连接，开始监听微信 / 支付宝 / 京东 / 云闪付")
        // 不再定时截屏：界面切换事件到了才截一次，静置的页面完全不耗电
        startKeepAlive()
    }

    /**
     * 常驻通知把进程挂成前台服务：联想一类的系统在「清理后台/清理最近任务」时，
     * 普通后台进程会被直接杀掉，读屏被杀后无障碍事件静默停止派发，
     * 用户看到的就是「支付宝不灵敏、怎么都不记账」。前台服务能被优先保留。
     * 通知本体与开机自启服务共用 [ServiceKeepAlive]，只显示一条。
     */
    private fun startKeepAlive() = ServiceKeepAlive.start(this)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        Log.d(TAG, "evt pkg=$pkg type=${event.eventType}")

        // 记录前台应用，截图任务用它判断这一屏是不是还停在白名单里
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg != foregroundPkg) lastFrameHash = 0
            foregroundPkg = pkg
        } else if (pkg in TARGET_PACKAGES) {
            foregroundPkg = pkg
        }

        // 验证阶段额外放开系统设置，用来对照「普通应用能不能读到节点」
        if (pkg !in TARGET_PACKAGES && pkg != PROBE_PACKAGE) return

        // 界面切换/内容变化时截一次屏：微信是自绘界面，节点树读不到，只能靠截图识别
        val switched = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (switched && pkg in TARGET_PACKAGES) scheduleCapture(pkg)

        // 账单页会连续派发大量内容变更事件，限流避免反复遍历节点树
        val now = System.currentTimeMillis()
        if (now - lastReadAt < THROTTLE_MS) return
        lastReadAt = now

        val root = rootInActiveWindow
        val texts = ArrayList<String>(64)
        if (root != null) collectText(root, texts, 0)

        if (texts.size >= 2) {
            val joined = texts.joinToString(" ")
            val parsed = PaymentParser.parse(joined, CHANNEL[pkg].orEmpty())
            if (parsed != null) {
                ScreenSnapshotStore.update(pkg, joined, parsed)
                Log.d(TAG, "节点树命中 pkg=$pkg nodes=${texts.size} parse=${parsed.desc()}")
                Log.d(TAG, "text=$joined")
                // 节点树能读到内容的多是列表页，必须见到单号/时间字段才按下单页处理
                ingest(pkg, joined, parsed, detailOnly = true)
                return
            }
            Log.d(TAG, "节点树没解析出金额 pkg=$pkg nodes=${texts.size}，等界面切换后截图识别")
        } else {
            Log.d(TAG, "节点树为空/过少 pkg=$pkg texts=${texts.size}，等界面切换后截图识别")
        }
    }

    /**
     * 界面切换（微信里翻页、弹层、出结果页）后截一次屏。
     *
     * 防抖：每来一个新事件就把「安静计时」重置，界面真正停稳后才截屏，
     * 一串连续转场只会产出一次 OCR。但不能无限等——支付宝首页的轮播和时钟
     * 会持续派发事件，永远等不到安静，所以另设一个最长等待上限，到点照常截。
     */
    private fun scheduleCapture(pkg: String) {
        lastPageEventAt = System.currentTimeMillis()
        if (captureJob?.isActive == true) return

        Log.d(TAG, "排入截图 pkg=$pkg")
        captureJob = scope.launch {
            while (true) {
                val startedAt = System.currentTimeMillis()
                // 等界面安静 MAX_SETTLE_MS，或被最长等待上限打断
                while (true) {
                    val now = System.currentTimeMillis()
                    val idle = now - lastPageEventAt
                    if (idle >= CAPTURE_SETTLE_MS || now - startedAt >= MAX_SETTLE_MS) break
                    delay(CAPTURE_SETTLE_MS - idle)
                }
                captureOnce(pkg)

                // 截图期间界面又变了就再补一张，否则收工
                if (System.currentTimeMillis() - lastPageEventAt < CAPTURE_SETTLE_MS) continue
                break
            }
        }
    }

    private fun captureOnce(pkg: String, attempt: Int = 0) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // 用户已经离开这个应用，或屏幕关了，不必再截
        if (pkg != foregroundPkg || !isScreenOn()) {
            Log.d(TAG, "跳过截图 pkg=$pkg fg=$foregroundPkg screenOn=${isScreenOn()}")
            AppLog.log("截图", "跳过 $pkg：已离开该应用或屏幕已关闭")
            return
        }

        // 系统对 takeScreenshot 有最小间隔限制，间隔不够会直接返回 errorCode=3；
        // 上一张还在 OCR 时也先等一下，别把这一屏丢掉
        val now = System.currentTimeMillis()
        val wait = SCREENSHOT_GAP_MS - (now - lastScreenshotAt)
        if (screenshotBusy || ocrInFlight || wait > 0) {
            Log.d(TAG, "延后截图 pkg=$pkg busy=$screenshotBusy ocr=$ocrInFlight wait=$wait attempt=$attempt")
            retryCapture(pkg, attempt, maxOf(wait, RETRY_DELAY_MS))
            return
        }

        lastScreenshotAt = now
        screenshotBusy = true

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                screenshotBusy = false
                val hardwareBuffer = screenshot.hardwareBuffer
                val bitmap = runCatching {
                    Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
                hardwareBuffer.close()
                if (bitmap == null) return
                AppLog.log("截图", "已截取 $pkg 画面 ${bitmap.width}×${bitmap.height}")

                val hash = frameHashOf(bitmap)
                if (hash == lastFrameHash) {
                    bitmap.recycle()
                    Log.d(TAG, "界面变了但画面相同，跳过 OCR pkg=$pkg")
                    AppLog.log("截图", "$pkg 画面与上一帧相同，跳过识别")
                    return
                }

                // 被 OCR 节流挡下时不要记录这帧指纹，交给重试补上
                val nowMs = System.currentTimeMillis()
                if (ocrInFlight || nowMs - lastOcrAt < OCR_THROTTLE_MS) {
                    bitmap.recycle()
                    retryCapture(pkg, attempt, RETRY_DELAY_MS)
                    return
                }
                // 熔断：OCR 连续失败后冷却一段时间，坏页面不再无限重试
                if (System.currentTimeMillis() < ocrCooldownUntil) {
                    bitmap.recycle()
                    Log.d(TAG, "OCR 冷却中，跳过 pkg=$pkg")
                    return
                }

                lastFrameHash = hash
                lastOcrAt = nowMs
                ocrInFlight = true
                Log.d(TAG, "画面变化，开始识别 pkg=$pkg")
                // 降采样到 OCR 够用的宽度，原图立刻回收，峰值内存少一大截
                recognize(pkg, downscaleForOcr(bitmap))
            }

            override fun onFailure(errorCode: Int) {
                screenshotBusy = false
                Log.d(TAG, "截图失败 pkg=$pkg errorCode=$errorCode")
                AppLog.log("截图", "截图失败 $pkg errorCode=$errorCode")
                // errorCode=3 是两次截图间隔太短，稍后再试一次
                retryCapture(pkg, attempt, RETRY_DELAY_MS)
            }
        })
    }

    /** 数量有限的重试：界面停着不动时最多补两次，避免任何情况下变成轮询 */
    private fun retryCapture(pkg: String, attempt: Int, delayMs: Long) {
        if (attempt >= MAX_CAPTURE_RETRY) {
            Log.d(TAG, "放弃补截 pkg=$pkg attempt=$attempt")
            return
        }
        scope.launch {
            delay(delayMs)
            captureOnce(pkg, attempt + 1)
        }
    }

    /** 16x16 缩略图的像素指纹，够判断「这一屏有没有变」，比逐像素比对省得多 */
    private fun frameHashOf(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, FRAME_SIZE, FRAME_SIZE, true)
        val pixels = IntArray(FRAME_SIZE * FRAME_SIZE)
        small.getPixels(pixels, 0, FRAME_SIZE, 0, 0, FRAME_SIZE, FRAME_SIZE)
        small.recycle()
        var hash = 17
        for (pixel in pixels) hash = hash * 31 + (pixel and 0x00FFFFFF)
        return hash
    }

    /**
     * OCR 只关心字够不够清楚，原图分辨率对识别率几乎没有增益。
     * 缩到固定宽度后把原图回收，避免一张 1600×2560 的 ARGB 位图（约 16MB）驻留。
     */
    private fun downscaleForOcr(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= OCR_MAX_WIDTH) return bitmap
        val ratio = OCR_MAX_WIDTH.toFloat() / bitmap.width
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, OCR_MAX_WIDTH, height, true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    private fun isScreenOn(): Boolean {
        val power = getSystemService(PowerManager::class.java) ?: return true
        return power.isInteractive
    }

    private fun recognize(pkg: String, bitmap: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                // 行是按阅读顺序返回的，拼起来正好保留「京东商城平台商户 / -15.70」这种相邻关系
                val joined = text.textBlocks
                    .flatMap { it.lines }
                    .mapNotNull { it.text.trim().takeIf(String::isNotEmpty) }
                    .joinToString(" ")
                val parsed = PaymentParser.parse(joined, CHANNEL[pkg].orEmpty())
                val lineCount = text.textBlocks.sumOf { it.lines.size }
                Log.d(TAG, "OCR pkg=$pkg lines=$lineCount parse=${parsed?.desc()}")
                Log.d(TAG, "ocr text=$joined")
                AppLog.log(
                    "识别",
                    "OCR $pkg 共 $lineCount 行，" + (
                        parsed?.let { "识别到 ${yuanOf(it.amountCents)} 元 · ${directionLabel(it.direction)} · ${it.merchant}" }
                            ?: "未识别出账单"
                        )
                )
                if (parsed != null) {
                    ScreenSnapshotStore.update(pkg, joined, parsed)
                    ingest(pkg, joined, parsed)
                }
                ocrFailures = 0
                ocrInFlight = false
                bitmap.recycle()
            }
            .addOnFailureListener { error ->
                ocrInFlight = false
                bitmap.recycle()
                ocrFailures++
                if (ocrFailures >= OCR_FAILURE_THRESHOLD) {
                    // 连续失败说明这类画面处理不了，冷却一段时间再试，避免反复烧 CPU
                    ocrCooldownUntil = System.currentTimeMillis() + OCR_COOLDOWN_MS
                    ocrFailures = 0
                    AppLog.log("识别", "OCR 连续失败，暂停识别 1 分钟")
                }
                Log.d(TAG, "OCR 失败 pkg=$pkg error=${error.message}")
                AppLog.log("识别", "OCR 失败 $pkg：${error.message}")
            }
    }

    /**
     * 自动入账。
     *
     * 只认「账单详情页 / 支付结果页」：
     * 1. 详情页一定能读到秒级交易时间或单号；
     * 2. 支付宝、微信的支付结果页没有时间，但有「支付成功 / 付款成功 / 你已收款」。
     *
     * 不能再用关键词列表判断页面形态：支付宝账单列表页头部就是「支出¥313.39」，
     * 列表行里还有「自动扣款成功」「退款」「订单」，关键词全都能命中，
     * 结果把月度合计当成一笔 313.39 的退款记了进去。
     */
    private fun ingest(pkg: String, text: String, parsed: ParsedPayment, detailOnly: Boolean = false) {
        val confirmed = parsed.orderNo.isNotBlank() ||
            parsed.occurredAt != null ||
            (!detailOnly && resultMatcher.containsAny(text))
        if (!confirmed) {
            Log.d(TAG, "页面不是账单详情/结果页，仅识别不入账 pkg=$pkg")
            AppLog.log("入账", "识别到 ${yuanOf(parsed.amountCents)} 元，但页面不是账单详情/结果页，未入账")
            return
        }

        // 同一屏会被反复读到（限流 + 截图），先用内容签名挡一层，避免日志和写库刷屏。
        // 签名必须带上秒级时间和单号：金额、商户完全相同的两笔订单，只有这两项能区分。
        // 不带方向：同一页两次识别可能把「+10.03」和「10.03元」判成收入、转账两种。
        val signature = "${pkg}_${parsed.amountCents}_${parsed.merchant}_${parsed.occurredAt}_${parsed.orderNo}"
        val now = System.currentTimeMillis()
        if (signature == lastIngestSignature && now - lastIngestAt < INGEST_DEDUPE_MS) return
        lastIngestSignature = signature
        lastIngestAt = now

        val repository = (applicationContext as? SmartLedgerApp)?.container?.repository
        if (repository == null) {
            Log.w(TAG, "应用容器未初始化，跳过入账")
            return
        }
        scope.launch {
            val result = runCatching {
                // 能走到这里的页面已经由 CONFIRM_WORDS 判定为「结果页」，直接按已确认入账
                repository.ingest(text, parsed.channel, now, TxSource.SCREEN_OCR, forceConfirm = true)
            }.getOrElse {
                Log.w(TAG, "入账失败 pkg=$pkg", it)
                return@launch
            }
            Log.i(TAG, "自动入账 result=$result pkg=$pkg ${parsed.desc()}")
            AppLog.log(
                "入账",
                "${resultLabel(result)}：${yuanOf(parsed.amountCents)} 元 · ${directionLabel(parsed.direction)} · ${parsed.merchant}"
            )
        }
    }

    private fun yuanOf(cents: Long): String = String.format(Locale.CHINA, "%.2f", cents / 100.0)

    private fun directionLabel(direction: Direction): String = when (direction) {
        Direction.EXPENSE -> "支出"
        Direction.INCOME -> "收入"
        Direction.TRANSFER -> "转账"
        Direction.REFUND -> "退款"
    }

    private fun resultLabel(result: IngestResult): String = when (result) {
        IngestResult.CONFIRMED -> "已入账"
        IngestResult.PENDING -> "进入待确认"
        IngestResult.DUPLICATE -> "重复账单，已跳过"
        IngestResult.STALE -> "不是当天账单，已跳过"
        IngestResult.REJECTED -> "不像账单，已跳过"
    }

    private fun com.example.smartledger.parser.ParsedPayment.desc(): String =
        "${amountCents}分/$direction/$merchant/$channel/conf=${"%.2f".format(confidence)}"

    /** 递归收集 text / contentDescription；深度设上限，防止异常界面把栈撑爆 */
    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (index in 0 until node.childCount) {
            collectText(node.getChild(index), out, depth + 1)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AppLog.log("服务", "读屏服务已断开")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenReader"

        /** 账单页会连续派发内容变更事件，节点树遍历按这个间隔限流 */
        private const val THROTTLE_MS = 800L

        /** 一次界面切换只会截一张图，靠画面指纹去重，这里不需要长节流 */
        private const val OCR_THROTTLE_MS = 1000L
        private const val MAX_DEPTH = 40
        private const val INGEST_DEDUPE_MS = 60_000L

        /** 界面切换后等它停稳再截，避开转场动画 */
        private const val CAPTURE_SETTLE_MS = 700L

        /** 防抖的最长等待：页面一直有事件（轮播、时钟）也不能永远不截 */
        private const val MAX_SETTLE_MS = 1500L

        /** OCR 输入的最大宽度，再大对识别率无增益，只会多吃内存 */
        private const val OCR_MAX_WIDTH = 1080

        /** OCR 连续失败多少次进入冷却，冷却多久 */
        private const val OCR_FAILURE_THRESHOLD = 3
        private const val OCR_COOLDOWN_MS = 60_000L

        /** 截图被系统限流或上一个 OCR 还没结束时，隔多久补一次 */
        private const val RETRY_DELAY_MS = 500L
        private const val MAX_CAPTURE_RETRY = 2
        private const val FRAME_SIZE = 16

        /** 系统限制的最小截图间隔，留一点余量 */
        private const val SCREENSHOT_GAP_MS = 700L

        /**
         * 只有「支付结果页」才会出现、且账单列表里没有的强文案。
         * 「扣款成功」不能放进来：支付宝账单列表里有「自动扣款成功」，会把列表页放行；
         * 「交易成功 / 退款成功」列表每一行都有，同样不能作为依据。
         */
        private val RESULT_WORDS = listOf("支付成功", "付款成功", "你已收款")

        /** 对照组：系统设置是标准原生界面，能读到就说明服务本身没问题 */
        private const val PROBE_PACKAGE = "com.android.settings"

        /** 只读这几个包，避免把聊天记录、通讯录等无关内容带进来 */
        private val TARGET_PACKAGES = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.jingdong.app.mall",
            "com.unionpay"
        )

        private val CHANNEL = mapOf(
            "com.tencent.mm" to "微信",
            "com.eg.android.AlipayGphone" to "支付宝",
            "com.jingdong.app.mall" to "京东",
            "com.unionpay" to "云闪付"
        )
    }
}
