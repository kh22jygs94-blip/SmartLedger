package com.example.smartledger.parser

import com.example.smartledger.data.Direction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 一条通知文本解析出来的结果。
 * confidence 是 0~1 的置信度，仓库层用它决定「直接入账」还是「进待确认」。
 */
data class ParsedPayment(
    val amountCents: Long,
    val direction: Direction,
    val merchant: String,
    val channel: String,
    val confidence: Float,
    /** 交易单号/商户单号/订单号，用于区分「多笔同金额同商户」的订单；通知文本通常没有 */
    val orderNo: String = "",
    /**
     * 账单页上写的交易时间（毫秒）。截图识别时用来判断这一页是刚发生的交易还是历史账单；
     * 页面上没有时间或解析不出来时为 null。
     */
    val occurredAt: Long? = null
)

/**
 * 支付通知解析器：纯规则实现，不依赖网络。
 *
 * 设计顺序是「先保准、再保全」：
 * 1. 金额必须能提取出来，否则直接放弃（避免把营销通知记成消费）
 * 2. 方向靠关键词判断，退款优先于收入，收入优先于转账，最后才落到支出
 * 3. 商户名从「收款方」「商户」等固定格式里抽，抽不到就退化成渠道名
 */
object PaymentParser {

    /**
     * 金额规则。group 是金额分组，signGroup 是正负号分组（-1 表示没有号），
     * explicit 表示带货币符号——带符号的金额更可信，置信度会加分。
     */
    private class AmountRule(
        val regex: Regex,
        val group: Int,
        val signGroup: Int = -1,
        val explicit: Boolean = false,
        /** 页面里可能同时出现多笔金额（优惠金额），取绝对值最大的那笔 */
        val pickLargest: Boolean = false
    )

    /**
     * 金额特征：带货币符号最可信，其次账单详情页里带正负号的实付金额，
     * 再次「金额：xx」「xx元」，最后是漏掉货币符号的独立小数。
     */
    private val amountRules = listOf(
        AmountRule(
            Regex("""([+-])?\s*(?:¥|￥|RMB|人民币)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""),
            group = 2,
            signGroup = 1,
            explicit = true
        ),
        // 微信/支付宝账单详情页复制出来的金额是独占一行的「-15.70」，没有货币符号。
        // 这里强制两位小数，避免把订单号、交易单号这类纯数字串误当成金额。
        // 带正负号的金额最接近「真实收付」，必须排在「订单金额」之前：
        // 支付宝详情页同时有「订单金额 19.90」「双11大促立减 -0.02」「实付 -19.88」。
        AmountRule(
            Regex("""(?:^|\s)([+\-−－])([0-9][0-9,]*\.[0-9]{2})(?=\s|$)"""),
            group = 2,
            signGroup = 1,
            pickLargest = true
        ),
        // 「订单金额 / 优惠金额」是原价、减免，不是这笔的实付，不能当金额用
        AmountRule(
            Regex("""(?<!订单)(?<!訂單)(?<!优惠)(?<!立减)(?:金额|金額)[:：]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""),
            group = 1
        ),
        AmountRule(Regex("""([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:元|块钱)"""), group = 1),
        // 截图 OCR 有时会把「¥0.01」的货币符号漏掉，只剩一个独立的小数。
        // 放在最后兜底，并要求前后都不是数字/小数点/百分号，避免命中订单号和「七日年化0.85%」。
        AmountRule(Regex("""(?<![\d.%])([0-9][0-9,]*\.[0-9]{2})(?![\d.%])"""), group = 1)
    )

    /** 优惠行的固定文案：这些词后面的金额是减免金额，不是实付金额 */
    private val discountMarkers = listOf("优惠", "立减", "立減", "折扣", "满减", "满減", "减免", "抵扣")

    private val refundWords = listOf("退款", "已退回", "原路退回", "退回")
    private val incomeWords = listOf("收款成功", "已收款", "到账", "入账", "收到一笔", "收入")
    private val transferWords = listOf("转账", "转入", "转出")
    private val expenseWords = listOf(
        "支付成功", "付款成功", "已支付", "支付", "付款", "消费", "扣款", "已扣款", "支出", "交易成功"
    )

    private val merchantPatterns = listOf(
        Regex("""收款方[:：]\s*([^\s，,。;；]+)"""),
        Regex("""商户(?:名称)?[:：]\s*([^\s，,。;；]+)"""),
        Regex("""付款给[:：]?\s*([^\s，,。;；]+)"""),
        Regex("""向\s*([^\s，,。;；]{2,20})\s*(?:付款|转账|支付)"""),
        Regex("""在\s*([^\s，,。;；]{2,20})\s*(?:消费|支付|购物)"""),
        Regex("""【([^】]{2,20})】"""),
        Regex("""(?:来自|商家)[:：]\s*([^\s，,。;；]+)"""),
        // 支付宝账单详情页把标签和值分两列排，OCR 读出来是「收款方全称 … 订单号 … 值」，
        // 「收款方全称 订单号」这种相邻关系会误取到「订单号」，只能按值本身的形态认：
        // 收款方全称是公司主体，余额提现的收款方是银行。
        Regex("""([\u4e00-\u9fa5]{2,20}(?:有限责任公司|股份有限公司|有限公司))"""),
        Regex("""([\u4e00-\u9fa5]{2,12}银行)"""),
        // 微信账单详情页的商户名没有「收款方：」前缀，但都以「商户」结尾，例如
        // 「京东商城平台商户」。必须整词匹配：OCR 出的「目在此商户的交易」里也含「商户」，
        // 不限定词边界就会把整句前半段当成商户名。
        Regex("""(?<!\S)([\u4e00-\u9fa5A-Za-z0-9·]{2,20}(?:平台商户|商户))(?!\S)""")
    )

    /** 快速判断这条文本「像不像一笔交易」，用于通知监听的第一道过滤 */
    fun looksLikePayment(text: String): Boolean {
        if (text.isBlank()) return false
        val hasKeyword = (refundWords + incomeWords + transferWords + expenseWords).any { text.contains(it) }
        return hasKeyword && extractAmount(text) != null
    }

    fun parse(rawText: String, channel: String = ""): ParsedPayment? {
        val text = rawText.replace('\n', ' ').trim()
        if (text.isEmpty()) return null

        val amount = extractAmount(text) ?: return null
        val resolvedChannel = inferChannel(text, channel)

        val direction = extractDirection(text, amount.sign)
        val merchantFound = extractMerchant(text)
        val merchant = merchantFound.ifBlank { resolvedChannel.ifBlank { "未知商户" } }

        return ParsedPayment(
            amountCents = amount.cents,
            direction = direction,
            merchant = merchant,
            channel = resolvedChannel,
            // 账单里的正负号是很强的信号，和货币符号同等看待
            confidence = score(amount.explicitCurrency || amount.sign != 0, merchantFound, text, resolvedChannel),
            orderNo = extractOrderNo(text),
            occurredAt = extractOccurredAt(text)
        )
    }

    /**
     * 页面上的交易时间，必须精确到秒：
     * 「2026年11月7日 22:56:58」（微信账单/转账详情）、
     * 「2026-11-08 18:31:05」（支付宝账单详情）。
     * 只到分（「2026年11月8日 18:31」）或没有年份（「11月8日 17:56」）的都不算，
     * 交给上层按「无时间」处理，用系统时间记账。
     * OCR 有时会把冒号识成全角，统一兼容；数字之间允许有空格。
     */
    private val fullDatePattern =
        Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日\s*(\d{1,2})[:：](\d{2})[:：](\d{2})""")
    private val numericDatePattern =
        Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2})[:：](\d{2})[:：](\d{2})""")

    private fun extractOccurredAt(text: String): Long? {
        fullDatePattern.find(text)?.let { m ->
            buildTime(
                year = m.groupValues[1].toIntOrNull(),
                month = m.groupValues[2].toIntOrNull(),
                day = m.groupValues[3].toIntOrNull(),
                hour = m.groupValues[4].toIntOrNull(),
                minute = m.groupValues[5].toIntOrNull(),
                second = m.groupValues[6].toIntOrNull()
            )?.let { return it }
        }
        numericDatePattern.find(text)?.let { m ->
            buildTime(
                year = m.groupValues[1].toIntOrNull(),
                month = m.groupValues[2].toIntOrNull(),
                day = m.groupValues[3].toIntOrNull(),
                hour = m.groupValues[4].toIntOrNull(),
                minute = m.groupValues[5].toIntOrNull(),
                second = m.groupValues[6].toIntOrNull()
            )?.let { return it }
        }
        return null
    }

    private fun buildTime(
        year: Int?,
        month: Int?,
        day: Int?,
        hour: Int?,
        minute: Int?,
        second: Int?
    ): Long? {
        if (year == null || month == null || day == null || hour == null || minute == null || second == null) return null
        val dateTime = runCatching {
            LocalDateTime.of(year, month, day, hour, minute, second)
        }.getOrNull() ?: return null
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * 订单号：微信账单详情页有「交易单号」和「商户单号」，电商订单还有「订单号/订单编号」。
     * 这是区分「多笔同金额同商户」的唯一可靠依据，通知文本里一般没有。
     */
    private val orderNoPatterns = listOf(
        Regex("""交易单号[:：]?\s*([0-9]{10,})"""),
        Regex("""商户单号[:：]?\s*([0-9]{10,})"""),
        Regex("""订单(?:编号|号)[:：]?\s*([0-9]{10,})""")
    )

    private fun extractOrderNo(text: String): String {
        orderNoPatterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.get(1)?.let { return it }
        }
        return ""
    }

    private data class AmountHit(val cents: Long, val sign: Int, val explicitCurrency: Boolean)

    /** 返回 金额（分）、正负号（-1/0/1）与 是否带货币符号 */
    private fun extractAmount(text: String): AmountHit? {
        amountRules.forEach { rule ->
            val matches =
                if (rule.pickLargest) rule.regex.findAll(text).toList()
                else listOfNotNull(rule.regex.find(text))
            val hits = matches
                // 优惠/立减后面的金额一律不算实付，任何规则都要先剔掉
                .filterNot { isDiscountLine(text, it.range.first) }
                .mapNotNull { match ->
                    val raw = match.groupValues[rule.group].replace(",", "")
                    val cents = runCatching {
                        BigDecimal(raw).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
                    }.getOrNull() ?: return@mapNotNull null
                    if (cents <= 0L) return@mapNotNull null
                    val sign = if (rule.signGroup >= 0) {
                        when (match.groupValues[rule.signGroup]) {
                            "-", "−", "－" -> -1
                            "+", "＋" -> 1
                            else -> 0
                        }
                    } else {
                        0
                    }
                    AmountHit(cents, sign, rule.explicit)
                }
            if (hits.isEmpty()) return@forEach
            // 优惠行和实付金额混在一起时，取绝对值最大的那笔（实付通常远大于优惠）
            return if (rule.pickLargest) hits.maxByOrNull { it.cents } else hits.first()
        }
        return null
    }

    /** 金额前面十几个字里带「优惠/立减」等字样，说明这是减免金额，不是这笔账的实付 */
    private fun isDiscountLine(text: String, amountIndex: Int): Boolean {
        val prefix = text.substring(maxOf(0, amountIndex - 12), amountIndex)
        return discountMarkers.any { prefix.contains(it) }
    }

    /** 账单里的正负号比关键词更准：-15.70 一定是支出，+15.70 一定是收入 */
    private fun extractDirection(text: String, sign: Int): Direction = when {
        refundWords.any { text.contains(it) } -> Direction.REFUND
        sign < 0 -> Direction.EXPENSE
        sign > 0 -> Direction.INCOME
        incomeWords.any { text.contains(it) } -> Direction.INCOME
        transferWords.any { text.contains(it) } -> Direction.TRANSFER
        else -> Direction.EXPENSE
    }

    private fun extractMerchant(text: String): String {
        for (pattern in merchantPatterns) {
            val value = pattern.find(text)?.groupValues?.get(1)?.trim().orEmpty()
            // 「京东商城平台商户」这类写法去掉尾巴，展示成「京东商城」
            val merchant = value.removeSuffix("平台商户").removeSuffix("商户").trim()
                .ifBlank { value }
            if (merchant.isNotBlank() && merchant.any { it.isLetter() }) return merchant
        }
        return ""
    }

    /** 分享/剪贴板的文本里通常带着支付渠道的痕迹，能认出来就用它，认不出才退回调用方给的来源 */
    private fun inferChannel(text: String, fallback: String): String = when {
        text.contains("财付通") || text.contains("微信") -> "微信"
        text.contains("支付宝") -> "支付宝"
        text.contains("京东") -> "京东"
        else -> fallback
    }

    /**
     * 置信度由四部分累加：
     * 金额 0.4 是基础分，货币符号 +0.2、命中交易动词 +0.2、抽到商户 +0.15、来源渠道已知 +0.05。
     * 满分 0.98，0.85 以上自动入账，以下进入待确认。
     */
    private fun score(explicitCurrency: Boolean, merchantFound: String, text: String, channel: String): Float {
        var score = 0.4f
        if (explicitCurrency) score += 0.2f
        if ((refundWords + incomeWords + transferWords + expenseWords).any { text.contains(it) }) score += 0.2f
        if (merchantFound.isNotBlank()) score += 0.15f
        if (channel.isNotBlank()) score += 0.05f
        return score.coerceAtMost(0.98f)
    }
}

/** 商户名归一化：去掉空白与标点，方便去重时做严格比较 */
object MerchantNormalizer {
    private val noisePattern = Regex("""[\s,，。.;；:：、()（）【】\[\]]""")

    fun normalize(raw: String): String {
        val cleaned = raw.replace(noisePattern, "")
        return cleaned.ifBlank { "未知" }.take(24)
    }
}

/** 通知包名 -> 渠道名。黑名单式硬编码，避免把无关 App 的通知也拿来解析 */
object ChannelResolver {
    private val map = mapOf(
        "com.tencent.mm" to "微信",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.unionpay" to "云闪付",
        "cmb.pb" to "招商银行",
        "com.icbc" to "工商银行",
        "com.chinamworld.main" to "建设银行",
        "com.android.bankabc" to "农业银行",
        "com.bankcomm.Bankcomm" to "交通银行",
        "com.spdb.mbank" to "浦发银行",
        "com.ecitic.bank.mobile" to "中信银行",
        "com.sankuai.meituan" to "美团",
        "com.dianping.v1" to "大众点评",
        "com.taobao.taobao" to "淘宝",
        "com.jingdong.app.mall" to "京东",
        "com.xunmeng.pinduoduo" to "拼多多",
        "com.taobao.idlefish" to "闲鱼"
    )

    fun resolve(packageName: String?): String? = packageName?.let { map[it] }
}
