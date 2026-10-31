package com.jack.ai.data

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** 价格：元 / 百万 token。hit 是缓存命中的输入，miss 是没命中的输入，out 是输出。 */
@Serializable
data class Price(val hit: Double, val miss: Double, val out: Double)

/**
 * 计价。DeepSeek 分高峰和空闲两档：北京时间工作日 9:00–12:00、14:00–18:00 是高峰，其余时间（含周末、法定节假日）5 折。
 * 每次请求在发生的那一刻算钱，所以统计里的费用是准的，不用事后估。
 */
object Pricing {
    /** DeepSeek 高峰价（元 / 百万 token），空闲时段减半。来源：api-docs.deepseek.com/zh-cn/quick_start/pricing */
    private val DEEPSEEK_PEAK = mapOf(
        MODEL_FLASH to Price(hit = 0.04, miss = 2.0, out = 8.0),
        MODEL_PRO to Price(hit = 0.30, miss = 9.0, out = 27.0),
    )

    /** OpenRouter 等按美元标价的服务，换算成人民币用的汇率。 */
    const val USD_TO_CNY = 7.1

    private val BEIJING: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val DAY get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = BEIJING }

    /** 法定节假日（放假的日子）。只列到已公布的年份；更晚的年份只按周末判断。 */
    private val HOLIDAYS: Set<String> = buildSet {
        fun range(from: String, days: Int) {
            val c = Calendar.getInstance(BEIJING).apply { time = DAY.parse(from)!! }
            repeat(days) { add(DAY.format(c.time)); c.add(Calendar.DAY_OF_YEAR, 1) }
        }
        // 国务院办公厅关于 2026 年部分节假日安排的通知
        range("2026-01-01", 3)
        range("2026-02-15", 9)
        range("2026-04-04", 3)
        range("2026-05-01", 5)
        range("2026-06-19", 3)
        range("2026-09-25", 3)
        range("2026-10-01", 7)
    }

    /** 这个时刻是不是 DeepSeek 的高峰时段。 */
    fun isPeak(ms: Long): Boolean {
        val c = Calendar.getInstance(BEIJING).apply { timeInMillis = ms }
        val dow = c.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
        if (DAY.format(c.time) in HOLIDAYS) return false
        val minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return minutes in 9 * 60 until 12 * 60 || minutes in 14 * 60 until 18 * 60
    }

    /** 从 ms 起，下一个空闲时段开始的时刻；本身就是空闲时段时返回 ms。 */
    fun nextOffPeak(ms: Long): Long {
        var t = ms
        // 高峰时段最长 3 小时一段，按分钟往后找，最多找一天
        repeat(24 * 60) {
            if (!isPeak(t)) return t
            t += 60_000L
        }
        return t
    }

    /** 这个模型在这个时刻的价格；不知道价格时返回 null。 */
    fun priceAt(ep: Endpoint, ms: Long): Price? {
        if (!ep.deepseek) return ep.price
        // 不认识的 DeepSeek 模型不按 Flash 价估：估错比不估更糟（用量统计会系统性偏低），不知道就返回 null 让调用方记成未计价
        val p = DEEPSEEK_PEAK[ep.model] ?: return null
        return if (isPeak(ms)) p else Price(p.hit / 2, p.miss / 2, p.out / 2)
    }

    /** 旧版本记的用量没有当场算钱，按 DeepSeek 高峰价估一个上限。 */
    fun legacyPrice(model: String): Price? = DEEPSEEK_PEAK[model]

    fun cost(p: Price, prompt: Int, hit: Int, completion: Int): Double =
        (hit * p.hit + (prompt - hit).coerceAtLeast(0) * p.miss + completion * p.out) / 1_000_000.0
}
