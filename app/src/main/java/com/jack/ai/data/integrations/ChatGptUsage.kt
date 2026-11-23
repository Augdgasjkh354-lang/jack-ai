package com.jack.ai.data.integrations

import android.content.Context
import android.content.SharedPreferences

/**
 * Kavi 用 ChatGPT 套餐花了多少（统计页显示）。OpenAI 没给查剩余额度的接口（Sign in with ChatGPT 的文档里只说链到
 * chatgpt.com/settings/usage），所以只能记 Kavi 自己这边的：最近 5 小时、7 天的请求次数和 token，上次撞到上限的时间。
 * 服务要是在响应头里带了额度信息（名字里有 ratelimit、usage、limit 的），也存一份最新的给统计页看。
 */
object ChatGptUsage {
    private const val FIVE_HOURS = 5 * 3600_000L
    private const val WEEK = 7 * 24 * 3600_000L
    private const val MONTH = 30 * 24 * 3600_000L

    /** 一次请求：时间、输入、其中命中缓存、输出、按 API 价值多少美元（不认识的模型、旧版本记的是 null）。 */
    data class Hit(val at: Long, val input: Int, val cached: Int, val output: Int, val usd: Double? = null)

    data class Window(val label: String, val requests: Int, val input: Long, val cached: Long, val output: Long,
                      /** 按校准估算的已用百分比（没校准过是 null）。 */
                      val percent: Double? = null,
                      /** 按 OpenAI API 官方价算值多少美元（见 [GptApiPrice]）。 */
                      val usd: Double = 0.0,
                      /** 其中没算上价的请求（不认识的模型、旧版本记的）。 */
                      val unpriced: Int = 0) {
        val tokens: Long get() = input + output
        /** 折算成「额度单位」：没命中缓存的输入算 1，命中的算 0.1，输出算 8（大致按 GPT 的价格比例）。 */
        val units: Double get() = (input - cached).coerceAtLeast(0) + cached * 0.1 + output * 8.0
    }

    /**
     * 两个窗口各自的「100% 是多少额度单位」：用户照 ChatGPT 用量页上的百分比校准一次（[calibrate]），之后按它估算。
     * 下标 0 是 5 小时，1 是 7 天。
     */
    private val caps = DoubleArray(2)
    @Volatile var calibratedAt: Long = 0
        private set

    @Volatile private var prefs: SharedPreferences? = null
    private val hits = ArrayDeque<Hit>()
    @Volatile var limitHitAt: Long = 0
        private set
    /** 最近一次响应头里看到的额度信息（名字 → 值），没有就是空的。 */
    @Volatile var headers: Map<String, String> = emptyMap()
        private set

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("chatgpt_usage", Context.MODE_PRIVATE)
        prefs = p
        limitHitAt = p.getLong("limitHitAt", 0)
        synchronized(hits) {
            p.getString("hits", "").orEmpty().lineSequence().mapNotNull { line ->
                val f = line.split(',')
                val n = f.take(4).mapNotNull { it.toLongOrNull() }
                if (n.size == 4) Hit(n[0], n[1].toInt(), n[2].toInt(), n[3].toInt(), f.getOrNull(4)?.toDoubleOrNull()) else null
            }.forEach { hits.addLast(it) }
        }
        caps[0] = p.getFloat("cap5h", 0f).toDouble(); caps[1] = p.getFloat("cap7d", 0f).toDouble()
        calibratedAt = p.getLong("calibratedAt", 0)
        headers = p.getString("headers", "").orEmpty().lineSequence().filter { ':' in it }
            .associate { it.substringBefore(':') to it.substringAfter(':') }
    }

    /** 记一次请求的用量（回答结束时 Responses 接口给的）。 */
    fun record(input: Int, cached: Int, output: Int, model: String = "", now: Long = System.currentTimeMillis()) {
        synchronized(hits) {
            hits.addLast(Hit(now, input, cached, output, GptApiPrice.usd(model, input, cached, output)))
            // 留 30 天：看一个月值多少钱
            while (hits.isNotEmpty() && now - hits.first().at > MONTH) hits.removeFirst()
            prefs?.edit()?.putString("hits", hits.joinToString("\n") { "${it.at},${it.input},${it.cached},${it.output}" + (it.usd?.let { u -> ",$u" } ?: "") })?.apply()
        }
    }

    /** 服务说套餐额度（或给 Kavi 设的上限）用完了。 */
    fun limitHit(now: Long = System.currentTimeMillis()) {
        limitHitAt = now
        prefs?.edit()?.putLong("limitHitAt", now)?.apply()
    }

    /** 从响应头里挑出跟额度有关的（文档没写会不会有，有就记下）。 */
    fun seeHeaders(all: Map<String, String>) {
        val found = all.filterKeys { k -> val n = k.lowercase(); "ratelimit" in n || "usage" in n || "limit" in n || n.startsWith("x-codex") }
        if (found.isEmpty() || found == headers) return
        headers = found
        prefs?.edit()?.putString("headers", found.entries.joinToString("\n") { "${it.key}:${it.value}" })?.apply()
    }

    fun windows(now: Long = System.currentTimeMillis()): List<Window> = synchronized(hits) {
        listOf("最近 5 小时" to FIVE_HOURS, "最近 7 天" to WEEK).mapIndexed { i, (label, len) ->
            val w = window(label, len, now)
            if (caps[i] > 0) w.copy(percent = w.units * 100.0 / caps[i]) else w
        }
    }

    /** 最近 30 天：只看按 API 价值多少钱（套餐按月付）。 */
    fun month(now: Long = System.currentTimeMillis()): Window = synchronized(hits) { window("最近 30 天", MONTH, now) }

    /** 最早一条记录的时间（记了几天，30 天没满时说一声）。 */
    fun since(): Long? = synchronized(hits) { hits.firstOrNull()?.at }

    private fun window(label: String, len: Long, now: Long): Window {
        val h = hits.filter { now - it.at <= len }
        return Window(label, h.size, h.sumOf { it.input.toLong() }, h.sumOf { it.cached.toLong() }, h.sumOf { it.output.toLong() },
            usd = h.sumOf { it.usd ?: 0.0 }, unpriced = h.count { it.usd == null })
    }

    /**
     * 照 ChatGPT 用量页校准：percents 是两个窗口现在「已用百分之几」（null 表示这个不改）。
     * 已用越多校准越准；填 0 或用量还是 0 时没法算，跳过。
     */
    fun calibrate(percents: List<Double?>, now: Long = System.currentTimeMillis()) {
        val ws = windows(now)
        percents.forEachIndexed { i, pct ->
            if (i > 1 || pct == null || pct <= 0.0) return@forEachIndexed
            val u = ws[i].units
            if (u > 0) caps[i] = u * 100.0 / pct
        }
        calibratedAt = now
        prefs?.edit()?.putFloat("cap5h", caps[0].toFloat())?.putFloat("cap7d", caps[1].toFloat())?.putLong("calibratedAt", now)?.apply()
    }

    /** 测试用。 */
    internal fun reset() { synchronized(hits) { hits.clear() }; limitHitAt = 0; headers = emptyMap(); caps.fill(0.0); calibratedAt = 0 }
}
