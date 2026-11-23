package com.jack.ai.data.memory

import com.jack.ai.data.MemoryItem
import java.util.Calendar
import java.util.TimeZone

/**
 * 记忆之间的关系和时间（借鉴 Operit 的图谱记忆，自己重写）。
 *
 * 关系：每条记忆标「涉及谁、什么」（[MemoryItem.about]：人、地方、项目、东西），标了同一个的记忆就连在一起。
 * 回想时两种连法：话里直接提到了某个（「小王」），把关于它的记忆都带出来；按意思找到几条后，再顺着它们涉及的带出相连的。
 * 不另存一张图：关系就是这些标签，改记忆时跟着改，不会有指向已删记忆的断线。
 *
 * 时间：每条记忆可以记「这件事是什么时候的」（[MemoryItem.at]：2026-11-20 / 2026-11 / 2026），不是记下的时间。
 * 话里说到「上周」「去年国庆」「3 月」，就按时间范围找；没标时间的按记下的时间算。
 */
object MemoryGraph {
    private val CN: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 统一写法：去空格、小写（「小王」「 小王 」「XiaoWang」「xiaowang」算同一个）。 */
    fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), "")

    /** 所有标过的：名字 → 涉及它的记忆。 */
    fun entities(all: List<MemoryItem>): Map<String, List<MemoryItem>> {
        val map = LinkedHashMap<String, MutableList<MemoryItem>>()
        all.forEach { m -> m.about.map(::norm).filter { it.isNotEmpty() }.distinct().forEach { map.getOrPut(it) { mutableListOf() } += m } }
        return map
    }

    /** 话里直接提到了哪些（至少两个字，免得「我」「他」这种到处都是）。长的先认，认过的短名字被包含就不算（「王总」里的「王」）。 */
    fun mentioned(query: String, names: Collection<String>): List<String> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val hit = names.filter { it.length >= 2 && it in q }.sortedByDescending { it.length }
        return hit.filterIndexed { i, n -> hit.take(i).none { n in it } }
    }

    /** 和这几条相连的（涉及同一个），不含它们自己；共有的越多越靠前。 */
    fun neighbors(seeds: List<MemoryItem>, all: List<MemoryItem>, top: Int = 5): List<MemoryItem> {
        val keys = seeds.flatMap { s -> s.about.map(::norm) }.filter { it.isNotEmpty() }.toSet()
        if (keys.isEmpty()) return emptyList()
        val ids = seeds.map { it.id }.toSet()
        return all.asSequence().filter { it.id !in ids }
            .map { m -> m to m.about.map(::norm).count { it in keys } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryItem, Int>> { it.second }.thenByDescending { it.first.lastUsedAt })
            .take(top).map { it.first }.toList()
    }

    // ---------------- 时间 ----------------

    /** 一段时间 [from, to)（毫秒），label 给人看。 */
    data class Span(val from: Long, val to: Long, val label: String) {
        fun overlaps(o: Span) = from < o.to && o.from < to
    }

    private fun cal(ms: Long) = Calendar.getInstance(CN).apply { timeInMillis = ms }
    private fun dayStart(c: Calendar) = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    private fun ymd(y: Int, m: Int, d: Int) = Calendar.getInstance(CN).apply { clear(); set(y, m - 1, d) }
    private fun days(c: Calendar, n: Int, label: String): Span {
        val s = dayStart(c)
        val e = (s.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, n) }
        return Span(s.timeInMillis, e.timeInMillis, label)
    }
    private fun month(y: Int, m: Int, label: String): Span {
        val s = ymd(y, m, 1)
        return Span(s.timeInMillis, (s.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis, label)
    }
    private fun year(y: Int, label: String) = Span(ymd(y, 1, 1).timeInMillis, ymd(y + 1, 1, 1).timeInMillis, label)
    private fun weekStart(c: Calendar): Calendar {
        val s = dayStart(c)
        // 中国习惯周一是一周的开始
        val dow = (s.get(Calendar.DAY_OF_WEEK) + 5) % 7
        s.add(Calendar.DAY_OF_MONTH, -dow)
        return s
    }

    private val NUM = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)
    /** 「3」「三」「十二」「二十」 → 数字。 */
    private fun num(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        if (s.isEmpty() || s.any { it !in NUM }) return null
        if (s.length == 1) return NUM[s[0]]
        val t = s.indexOf('十')
        if (t < 0) return null
        val tens = if (t == 0) 1 else NUM[s[0]] ?: return null
        val ones = if (t == s.lastIndex) 0 else NUM[s[t + 1]] ?: return null
        return tens * 10 + ones
    }
    private const val N = "([0-9]{1,4}|[一二两三四五六七八九十]{1,3})"
    private val WEEKDAY = "一二三四五六日天"

    /**
     * 话里说到的时间范围；没有就是 null。认常见的说法：今天 / 昨天 / 前天、这周 / 上周 / 上周三、这个月 / 上个月、今年 / 去年 / 前年、
     * N 天（周、个月、年）前、最近 N 天、2026 年、3 月、3 月 5 号、2026-03-05、去年 3 月、元旦、国庆、春节前后。
     */
    fun timeOf(query: String, now: Long = System.currentTimeMillis()): Span? {
        val q = query.replace(" ", "")
        val c = cal(now)
        val y = c.get(Calendar.YEAR)
        Regex("(20[0-9]{2})[-/.]([01]?[0-9])[-/.]([0-3]?[0-9])").find(q)?.let { m ->
            val (yy, mm, dd) = m.destructured
            return days(ymd(yy.toInt(), mm.toInt(), dd.toInt()), 1, m.value)
        }
        // 年：2025 年、去年、前年、今年（后面可能还跟着月、日）
        var baseYear: Int? = null
        var yearLabel = ""
        Regex("(20[0-9]{2})年").find(q)?.let { baseYear = it.groupValues[1].toInt(); yearLabel = it.value }
        if (baseYear == null) when {
            "前年" in q -> { baseYear = y - 2; yearLabel = "前年" }
            "去年" in q -> { baseYear = y - 1; yearLabel = "去年" }
            "今年" in q -> { baseYear = y; yearLabel = "今年" }
        }
        Regex("${N}月(?:份)?(?:${N}(?:日|号))?").find(q)?.let { m ->
            val mm = num(m.groupValues[1]) ?: return@let
            if (mm !in 1..12) return@let
            // 没说哪年：过去的月份和接下来两个月算今年（快到的生日、安排），再往后的算去年（2 月说「11 月」是去年 11 月）
            val ahead = (mm - (c.get(Calendar.MONTH) + 1) + 12) % 12
            val yy = baseYear ?: if (mm > c.get(Calendar.MONTH) + 1 && ahead > 2) y - 1 else y
            val dd = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::num)
            return if (dd != null && dd in 1..31) days(ymd(yy, mm, dd), 1, yearLabel + m.value) else month(yy, mm, yearLabel + m.value)
        }
        when {
            "国庆" in q -> return Span(ymd(baseYear ?: y, 10, 1).timeInMillis, ymd(baseYear ?: y, 10, 8).timeInMillis, yearLabel + "国庆")
            "元旦" in q -> return days(ymd(baseYear ?: y, 1, 1), 3, yearLabel + "元旦")
            // 春节日期每年不同：按 1 月中到 2 月底宽着找
            "春节" in q || "过年" in q -> return Span(ymd(baseYear ?: y, 1, 15).timeInMillis, ymd(baseYear ?: y, 3, 1).timeInMillis, yearLabel + "春节前后")
        }
        baseYear?.let { return year(it, yearLabel) }
        Regex("最近${N}(天|周|个?星期|个月)").find(q)?.let { m ->
            val n = num(m.groupValues[1]) ?: return@let
            val d = when (m.groupValues[2]) { "天" -> n; "个月" -> n * 30; else -> n * 7 }
            val end = (dayStart(c).clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
            return Span(end.timeInMillis - d * 86_400_000L, end.timeInMillis, m.value)
        }
        Regex("${N}(天|周|个?星期|个月|年)(?:以)?前").find(q)?.let { m ->
            val n = num(m.groupValues[1]) ?: return@let
            val at = c.clone() as Calendar
            return when (m.groupValues[2]) {
                "天" -> { at.add(Calendar.DAY_OF_MONTH, -n); days(at, 1, m.value) }
                "个月" -> { at.add(Calendar.MONTH, -n); month(at.get(Calendar.YEAR), at.get(Calendar.MONTH) + 1, m.value) }
                "年" -> year(y - n, m.value)
                else -> { at.add(Calendar.WEEK_OF_YEAR, -n); val s = weekStart(at); days(s, 7, m.value) }
            }
        }
        Regex("(上上|上|这|本|下)(?:个)?(?:周|星期|礼拜)([$WEEKDAY])?").find(q)?.let { m ->
            val shift = when (m.groupValues[1]) { "上上" -> -2; "上" -> -1; "下" -> 1; else -> 0 }
            val s = weekStart(c).apply { add(Calendar.WEEK_OF_YEAR, shift) }
            val d = m.groupValues[2]
            if (d.isEmpty()) return days(s, 7, m.value)
            s.add(Calendar.DAY_OF_MONTH, WEEKDAY.indexOf(d[0]).coerceAtMost(6))
            return days(s, 1, m.value)
        }
        Regex("(上上|上|这|本)个?月").find(q)?.let { m ->
            val at = (c.clone() as Calendar).apply { add(Calendar.MONTH, when (m.groupValues[1]) { "上上" -> -2; "上" -> -1; else -> 0 }) }
            return month(at.get(Calendar.YEAR), at.get(Calendar.MONTH) + 1, m.value)
        }
        listOf("大前天" to -3, "前天" to -2, "昨天" to -1, "昨晚" to -1, "今天" to 0, "今早" to 0, "今晚" to 0).firstOrNull { it.first in q }?.let { (w, d) ->
            return days((c.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, d) }, 1, w)
        }
        return null
    }

    /** 记忆上标的时间（2026-11-20 / 2026-11 / 2026）→ 时间段；格式不对返回 null。 */
    fun spanOf(at: String): Span? {
        val p = at.trim().split('-').map { it.toIntOrNull() ?: return null }
        return when (p.size) {
            1 -> if (p[0] in 1900..2200) year(p[0], at) else null
            2 -> if (p[1] in 1..12) month(p[0], p[1], at) else null
            3 -> if (p[1] in 1..12 && p[2] in 1..31) days(ymd(p[0], p[1], p[2]), 1, at) else null
            else -> null
        }
    }

    /** 把模型给的时间整理成 2026-11-20 / 2026-11 / 2026，认不出就是空的。 */
    fun cleanAt(raw: String): String {
        val m = Regex("(20[0-9]{2})(?:[-/.年]([01]?[0-9]))?(?:[-/.月]([0-3]?[0-9]))?").find(raw.trim()) ?: return ""
        val (yy, mm, dd) = m.destructured
        val mi = mm.toIntOrNull()
        val di = dd.toIntOrNull()
        return when {
            mi == null || mi !in 1..12 -> yy
            di == null || di !in 1..31 -> "%s-%02d".format(yy, mi)
            else -> "%s-%02d-%02d".format(yy, mi, di)
        }
    }

    /** 这条记忆在不在这段时间：标了时间按标的算，没标按记下的时间。 */
    fun inSpan(m: MemoryItem, span: Span): Boolean =
        spanOf(m.at)?.overlaps(span) ?: (m.createdAt >= span.from && m.createdAt < span.to)

    /** 整理模型给的「涉及」：去空、去重、每个不超过 20 字，最多 6 个。 */
    fun cleanAbout(raw: List<String>): List<String> =
        raw.map { it.trim().trim('「', '」', '"', '\'') }.filter { it.isNotEmpty() && it.length <= 20 }.distinctBy(::norm).take(6)

    // ---------------- 找记忆（search_my_data 的 memory） ----------------

    /** 一条找到的记忆和为什么找到它。 */
    data class Found(val item: MemoryItem, val deep: Boolean, val why: String)

    /**
     * 按关键词、提到的人和事、时间一起找；再带上和找到的相连的。
     * 只说了时间（「上周」）就列出那段时间的全部；hot 常记，deep 深处。
     */
    fun search(query: String, hot: List<MemoryItem>, deep: List<MemoryItem>, now: Long = System.currentTimeMillis(), top: Int = 12): List<Found> {
        val all = hot.map { it to false } + deep.map { it to true }
        if (all.isEmpty()) return emptyList()
        val span = timeOf(query, now)
        val named = mentioned(query, entities(hot + deep).keys).toSet()
        // 去掉时间说法、人名以后还剩的词才当关键词
        var rest = query
        span?.let { rest = rest.replace(it.label, " ") }
        named.forEach { n -> rest = Regex(Regex.escape(n), RegexOption.IGNORE_CASE).replace(rest, " ") }
        val words = MemoryOps.keyTokens(rest)
        val out = LinkedHashMap<String, Found>()
        fun put(m: MemoryItem, d: Boolean, why: String) { out.putIfAbsent(m.id, Found(m, d, why)) }
        val inTime = { m: MemoryItem -> span == null || inSpan(m, span) }
        all.filter { (m, _) -> m.about.any { norm(it) in named } && inTime(m) }
            .forEach { (m, d) -> put(m, d, "about " + m.about.filter { norm(it) in named }.joinToString("、")) }
        if (words.isNotEmpty()) {
            val index = com.jack.ai.data.LibraryIndex(all.map { (m, _) -> m.id to (m.text + " " + m.about.joinToString(" ")) })
            val byId = all.associateBy { it.first.id }
            index.search(rest, top * 2).map { it.doc }.distinct().mapNotNull { byId[it] }
                .filter { (m, _) -> inTime(m) && MemoryOps.keyTokens(m.text + m.about.joinToString("")).any { it in words } }
                .forEach { (m, d) -> put(m, d, "words") }
        }
        if (span != null && named.isEmpty() && words.isEmpty()) {
            all.filter { (m, _) -> inSpan(m, span) }.sortedBy { spanOf(it.first.at)?.from ?: it.first.createdAt }
                .forEach { (m, d) -> put(m, d, span.label) }
        }
        val found = out.values.take(top).toList()
        val deepIds = deep.map { it.id }.toSet()
        val linked = neighbors(found.map { it.item }, hot + deep, top = 4).map { Found(it, it.id in deepIds, "linked") }
        return (found + linked).distinctBy { it.item.id }
    }
}
