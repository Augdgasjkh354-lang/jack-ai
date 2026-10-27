package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 浏览器实测成功率：Kavi 每做一个网页动作（点、输入、选、按键、打开）记一笔结果，按网站分。
 * 只记网站名和结果，不记内容。看的是改了以后成功率有没有真的变好。
 */
object BrowserStats {
    /** 结果：ok 成功、relocated 编号失效但重新找到并做成了、miss 找不到元素、no_change 点了没反应、request_failed 页面动了但后台请求失败、error 出错。 */
    @Serializable
    data class Event(val at: Long, val site: String, val action: String, val outcome: String)

    data class Rate(val site: String, val total: Int, val ok: Int, val worst: String?) {
        val percent: Int get() = if (total == 0) 0 else ok * 100 / total
    }

    val SUCCESS = setOf("ok", "relocated")
    val LABELS = mapOf("ok" to "成功", "relocated" to "重新定位后成功", "miss" to "找不到元素", "no_change" to "点了没反应",
        "request_failed" to "后台请求失败", "error" to "出错")

    private const val MAX = 3000
    private const val WINDOW_MS = 30L * 24 * 3600 * 1000
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "browser_stats.json")

    fun load(ctx: Context): List<Event> = runCatching {
        json.decodeFromString(ListSerializer(Event.serializer()), file(ctx).readText())
    }.getOrDefault(emptyList())

    fun record(ctx: Context, site: String, action: String, outcome: String, now: Long = System.currentTimeMillis()) {
        if (site.isBlank()) return
        synchronized(lock) {
            val list = (load(ctx) + Event(now, site, action, outcome)).takeLast(MAX)
            runCatching { file(ctx).writeText(json.encodeToString(ListSerializer(Event.serializer()), list)) }
        }
    }

    /** 最近 30 天：总的和各网站的成功率（网站按次数多的在前）。 */
    fun rates(events: List<Event>, now: Long = System.currentTimeMillis()): Pair<Rate, List<Rate>> {
        val recent = events.filter { now - it.at < WINDOW_MS }
        fun rate(site: String, ev: List<Event>) = Rate(site, ev.size, ev.count { it.outcome in SUCCESS },
            ev.filter { it.outcome !in SUCCESS }.groupingBy { it.outcome }.eachCount().maxByOrNull { it.value }?.key)
        val all = rate("全部", recent)
        val bySite = recent.groupBy { it.site }.map { (s, ev) -> rate(s, ev) }.sortedByDescending { it.total }
        return all to bySite
    }

    /** 给自查和统计页看的几行。没有记录返回空。 */
    fun summary(events: List<Event>, sites: Int = 8): String {
        val (all, bySite) = rates(events)
        if (all.total == 0) return ""
        return "近 30 天 ${all.total} 步，成功 ${all.percent}%\n" +
            bySite.take(sites).joinToString("\n") { r ->
                "- ${r.site}：${r.total} 步，成功 ${r.percent}%" + (r.worst?.let { "，最多的问题是${LABELS[it] ?: it}" } ?: "")
            }
    }
}
