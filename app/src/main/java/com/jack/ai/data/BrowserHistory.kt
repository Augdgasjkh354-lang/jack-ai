package com.jack.ai.data

import kotlinx.serialization.Serializable

/** 浏览记录的一条：同一个网址只留一条，记最后一次看的时间和看了几次。 */
@Serializable
data class HistoryEntry(val url: String, val title: String = "", val at: Long, val visits: Int = 1)

/**
 * 长一点的浏览记录（「最近访问」只留 30 条给起始页用）：Kavi 能按关键词、时间翻出以前看过的网页，
 * 比如「上周看的那个保温杯页面」。隐身页不记。
 */
object BrowserHistory {
    const val MAX = 3000
    const val KEEP_DAYS = 180
    private const val DAY = 86_400_000L

    /** 记一次：同一个网址合并（次数加一、时间更新、有新标题就换）。 */
    fun record(list: List<HistoryEntry>, url: String, title: String, now: Long): List<HistoryEntry> {
        val old = list.firstOrNull { it.url == url }
        val e = HistoryEntry(url, title.ifBlank { old?.title.orEmpty() }, now, (old?.visits ?: 0) + 1)
        return (listOf(e) + list.filter { it.url != url && now - it.at < KEEP_DAYS * DAY }).take(MAX)
    }

    /** 网页标题晚到了：补上。 */
    fun retitle(list: List<HistoryEntry>, url: String, title: String): List<HistoryEntry> =
        if (title.isBlank() || list.none { it.url == url && it.title != title }) list
        else list.map { if (it.url == url) it.copy(title = title) else it }

    /**
     * 按关键词找（空格分开的几个词，标题里有的分高、网址里有的分低），days 只看最近几天。
     * 词都对上的排前面，分一样的新的在前。没给关键词就是最近看的。
     */
    fun search(list: List<HistoryEntry>, query: String, now: Long, days: Int? = null, limit: Int = 15): List<HistoryEntry> {
        val recent = if (days != null && days > 0) list.filter { now - it.at <= days * DAY } else list
        val words = query.lowercase().split(Regex("[\\s,，、]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return recent.sortedByDescending { it.at }.take(limit)
        return recent.mapNotNull { e ->
            val t = e.title.lowercase(); val u = e.url.lowercase()
            val hits = words.count { it in t || it in u }
            if (hits == 0) null else e to (hits * 10 + words.count { it in t } * 2)
        }.sortedWith(compareByDescending<Pair<HistoryEntry, Int>> { it.second }.thenByDescending { it.first.at }).map { it.first }.take(limit)
    }

    /** 给模型看的清单。 */
    fun describe(found: List<HistoryEntry>, now: Long): String = found.mapIndexed { i, e ->
        val ago = (now - e.at).let { d ->
            when {
                d < 3600_000 -> "刚才"
                d < DAY -> "${d / 3600_000} 小时前"
                else -> "${d / DAY} 天前"
            }
        }
        "${i + 1}. ${e.title.ifBlank { "（无标题）" }} ${e.url}（$ago" + (if (e.visits > 1) "，看过 ${e.visits} 次" else "") + "）"
    }.joinToString("\n")
}

/** 记下来的一个价格（盯价格、比价时每查一次记一条），用来看走势。 */
@Serializable
data class PricePoint(val item: String, val price: Double, val at: Long, val url: String = "")

object PriceLog {
    const val MAX = 5000

    fun add(list: List<PricePoint>, p: PricePoint): List<PricePoint> = (list + p).takeLast(MAX)

    /** 记过价格的东西：名字 → 记了几次，新的在前。 */
    fun items(list: List<PricePoint>): List<Pair<String, Int>> =
        list.groupBy { it.item }.map { (k, v) -> Triple(k, v.size, v.maxOf { it.at }) }.sortedByDescending { it.third }.map { it.first to it.second }

    /** 某样东西的价格记录（先找一字不差的，再找包含的），按时间排。 */
    fun series(list: List<PricePoint>, item: String): List<PricePoint> {
        val n = item.trim()
        val exact = list.filter { it.item == n }
        val hit = exact.ifEmpty {
            val name = list.map { it.item }.distinct().firstOrNull { it.contains(n, true) || n.contains(it, true) } ?: return emptyList()
            list.filter { it.item == name }
        }
        return hit.sortedBy { it.at }
    }

    private fun yuan(v: Double) = "¥" + if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(v)
    private fun date(at: Long) = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))

    /** 走势的几句话：最新、比上次、比第一次、最低最高。 */
    fun summary(s: List<PricePoint>): String {
        if (s.isEmpty()) return "没有记录。"
        val last = s.last()
        val lo = s.minBy { it.price }; val hi = s.maxBy { it.price }
        fun change(from: Double, to: Double) = when {
            to < from -> "降了 ${yuan(from - to)}（${"%.1f".format((from - to) / from * 100)}%）"
            to > from -> "涨了 ${yuan(to - from)}（${"%.1f".format((to - from) / from * 100)}%）"
            else -> "没变"
        }
        return buildString {
            append("「${last.item}」记了 ${s.size} 次（${date(s.first().at)} 到 ${date(last.at)}）。")
            append("最新 ${yuan(last.price)}（${date(last.at)}）")
            if (s.size > 1) {
                append("，比上次${change(s[s.size - 2].price, last.price)}，比第一次${change(s.first().price, last.price)}")
                append("。最低 ${yuan(lo.price)}（${date(lo.at)}），最高 ${yuan(hi.price)}（${date(hi.at)}）")
                if (last.price <= lo.price && s.size >= 3) append("，现在是记录里最低的")
            }
            append("。")
        }
    }
}
