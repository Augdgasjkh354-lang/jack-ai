package com.jack.ai.data

/**
 * 改写别人的文章、视频后核对（思路来自 threerocks/article2media 的改写校验，MIT；代码自己写）：
 * - 稿子里的数字原文里有没有：图上、标题里画出来的数字都得有出处，找不到的多半是模型编的或算错了；
 * - 有没有把作者的经历写成「我」：转述别人的文章写成「我的店一年翻三倍」，读者会以为是发帖人自己。
 * 只找、不改，由模型逐条处理（改掉、标「示意」或「编者换算」）。
 */
object RewriteCheck {
    data class Result(val missing: List<Pair<String, String>>, val firstPerson: List<String>, val checked: Int)

    /** 数字：1,200、3.5、73%、2027、48 万、$10k 这类。10 以内的整数多是序号、页码，不查。 */
    private val NUMBER = Regex("""(\d+(?:[,，]\d{3})*(?:\.\d+)?)\s*(万|亿|千|[kK](?![a-zA-Z])|M(?![a-zA-Z]))?""")
    private val SCALE = mapOf("万" to 10_000L, "亿" to 100_000_000L, "千" to 1_000L, "k" to 1_000L, "K" to 1_000L, "M" to 1_000_000L)

    /** (写出来的样子, 数值)：「48 万」和「480,000」数值一样，算同一个数。 */
    internal fun numbers(text: String): List<Pair<String, java.math.BigDecimal>> = NUMBER.findAll(text).mapNotNull { m ->
        val raw = m.groupValues[1].replace(",", "").replace("，", "")
        val v = raw.toBigDecimalOrNull() ?: return@mapNotNull null
        val unit = m.groupValues[2]
        val value = (SCALE[unit]?.let { v.multiply(java.math.BigDecimal(it)) } ?: v).stripTrailingZeros()
        if (unit.isEmpty() && '.' !in raw && v <= java.math.BigDecimal.TEN) return@mapNotNull null
        m.value.trim() to value
    }.toList()

    /** 去掉引号里的原话（引的原话可以有「我」）。 */
    private fun outsideQuotes(s: String) = s.replace(Regex("""「[^」]*」|“[^”]*”|"[^"]*"|『[^』]*』"""), "")

    fun check(draft: String, source: String, self: Boolean = false): Result {
        // 原文里每个数既按写出来的单位算，也按不带单位算（原文「480,000」、稿子「48 万」；或原文「3 万」、稿子「3」）
        val have = numbers(source).flatMap { (shown, v) ->
            listOf(v, shown.filter { it.isDigit() || it == '.' }.toBigDecimalOrNull()?.stripTrailingZeros())
        }.filterNotNull().map { it.toPlainString() }.toSet()
        val lines = draft.lines()
        val missing = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        for (line in lines) for ((shown, v) in numbers(line)) {
            val key = v.toPlainString()
            if (key !in have && seen.add(key)) missing[shown] = line.trim().take(60)
        }
        val firstPerson = if (self) emptyList() else lines.map { it.trim() }.filter { it.isNotEmpty() }
            .filter { outsideQuotes(it).contains('我') }.map { it.take(60) }.distinct().take(20)
        return Result(missing.map { it.key to it.value }, firstPerson, numbers(draft).size)
    }

    fun format(r: Result): String = buildString {
        if (r.missing.isEmpty() && r.firstPerson.isEmpty()) {
            append("没发现问题：稿子里 ${r.checked} 个数字原文里都有，也没有把作者写成「我」。"); return@buildString
        }
        if (r.missing.isNotEmpty()) {
            append("原文里找不到的数字（${r.missing.size} 个），逐条处理：查原文改对；是你换算的标「编者换算」；示意用的标「示意」；都不是就删掉：\n")
            r.missing.forEach { (n, line) -> append("- $n　「$line」\n") }
        }
        if (r.firstPerson.isNotEmpty()) {
            append(if (r.missing.isNotEmpty()) "\n" else "")
            append("写成了「我」的地方（引号外）：转述别人的文章要写成「他 / 她 / 作者 / 名字」，不然读者以为是发帖人自己的事；文章本来就是用户自己写的，用 self=true 再查：\n")
            r.firstPerson.forEach { append("- 「$it」\n") }
        }
    }
}
