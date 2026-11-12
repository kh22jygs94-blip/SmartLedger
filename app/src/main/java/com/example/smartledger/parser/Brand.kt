package com.example.smartledger.parser

/**
 * 品牌标识：商户名里能认出平台/连锁店时，用一个带品牌色的圆角标记代替分类 emoji。
 * 这里只存颜色和文字，不引入任何第三方 logo 资源——避免商标风险，也省安装包体积。
 */
data class BrandMark(
    val name: String,
    val glyph: String,
    val argb: Long
)

object BrandResolver {

    private val brands: List<Pair<BrandMark, List<String>>> = listOf(
        BrandMark("京东", "京", 0xFFE1251B) to listOf("京东", "京东商城", "京东支付", "JD"),
        BrandMark("淘宝", "淘", 0xFFFF5000) to listOf("淘宝", "天猫", "阿里"),
        BrandMark("拼多多", "拼", 0xFFE02E24) to listOf("拼多多", "多多买菜"),
        BrandMark("美团", "美", 0xFFE68600) to listOf("美团", "大众点评", "外卖"),
        BrandMark("饿了么", "饿", 0xFF0085FF) to listOf("饿了么"),
        BrandMark("支付宝", "支", 0xFF1677FF) to listOf("支付宝", "花呗", "余额宝"),
        BrandMark("微信", "微", 0xFF07C160) to listOf("微信", "零钱", "红包"),
        BrandMark("云闪付", "云", 0xFFD6001C) to listOf("云闪付", "银联"),
        BrandMark("瑞幸", "瑞", 0xFF003D8F) to listOf("瑞幸", "luckin"),
        BrandMark("星巴克", "星", 0xFF00704A) to listOf("星巴克", "starbucks"),
        BrandMark("麦当劳", "麦", 0xFFDA291C) to listOf("麦当劳", "金拱门"),
        BrandMark("肯德基", "肯", 0xFFE4002B) to listOf("肯德基", "KFC"),
        BrandMark("滴滴", "滴", 0xFFE86A00) to listOf("滴滴", "花小猪"),
        BrandMark("铁路 12306", "铁", 0xFF1B4F8C) to listOf("12306", "铁路", "火车票"),
        BrandMark("中国移动", "移", 0xFF0086D1) to listOf("中国移动", "话费"),
        BrandMark("招商银行", "招", 0xFFC8102E) to listOf("招商银行", "招行"),
        BrandMark("工商银行", "工", 0xFFC7000B) to listOf("工商银行", "工行"),
        BrandMark("建设银行", "建", 0xFF003DA5) to listOf("建设银行", "建行"),
        BrandMark("农业银行", "农", 0xFF009B48) to listOf("农业银行", "农行")
    )

    /**
     * 先看商户名（更精确），认不出再退回渠道名。
     * 这样「瑞幸咖啡」配「微信」渠道时会显示瑞幸，而不是显示微信。
     */
    fun resolve(merchant: String, channel: String): BrandMark? =
        match(merchant) ?: match(channel)

    private fun match(text: String): BrandMark? {
        if (text.isBlank()) return null
        return brands.firstOrNull { (_, keywords) ->
            keywords.any { text.contains(it, ignoreCase = true) }
        }?.first
    }
}
