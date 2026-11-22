package com.jack.ai.data.media

import java.text.Normalizer

/**
 * 配完回听核对（思路来自 debpalash/VoiceStudio 的配音质检）：每句配音再转成文字，和文案比，
 * 数字读得不一样的、字差得多的（漏读、念错、英文读歪）挑出来重配。
 * 比之前两边都归一：去标点空格、全角转半角、小写，中文数字换成阿拉伯数字（「两千零二十四」「二零二四」都是 2024，「百分之三十」是 30），
 * 这样「2024 年」念成「二零二四年」不算错。转文字本身也会听错，结果只是「可能有问题」，交给 Kavi 判断。
 */
object SpeechCheck {
    /** 相似度低于这个就标出来。 */
    const val MIN_SIMILAR = 0.8

    data class Issue(val index: Int, val text: String, val heard: String, val similar: Double, val numbers: Boolean)

    fun check(lines: List<String>, heard: List<String?>): List<Issue> = lines.indices.mapNotNull { i ->
        val h = heard.getOrNull(i) ?: return@mapNotNull null
        val a = norm(lines[i]); val b = norm(h)
        if (a.isEmpty()) return@mapNotNull null
        val sim = similar(a, b)
        val numbers = digits(a) != digits(b)
        if (sim >= MIN_SIMILAR && !numbers) null else Issue(i, lines[i], h, sim, numbers)
    }

    fun format(issues: List<Issue>, total: Int): String {
        if (issues.isEmpty()) return "回听核对：$total 句都和文案对得上。"
        return buildString {
            append("回听核对：$total 句里 ${issues.size} 句听着和文案不一样（转文字也会听错，同音字、英文缩写多半没事，自己判断）：\n")
            issues.forEach {
                append("${it.index + 1}. 文案「${it.text}」→ 听到「${it.heard.ifBlank { "（没听到声音）" }}」")
                append(if (it.numbers) "，数字不一样" else "，相似 ${(it.similar * 100).toInt()}%")
                append('\n')
            }
            append("真念错的：把那句改写一下再配（数字、英文写成想让它怎么念，比如「2024 年」写成「二零二四年」、「AI」写成「A I」），只改这几句，其他句子不会重配、不重复花钱。")
        }
    }

    /** 归一：只留字母和数字（汉字也算字母），中文数字换成阿拉伯数字。 */
    fun norm(s: String): String {
        val t = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().replace("百分之", "")
        val num = NUM.replace(t) { m -> cn(m.value) ?: m.value }
        return num.filter { it.isLetterOrDigit() }
    }

    private fun digits(s: String) = Regex("""\d+""").findAll(s).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

    private val NUM = Regex("""[零〇一二两三四五六七八九十百千万亿]+(?:点[零〇一二两三四五六七八九]+)?""")

    /** 中文数字转阿拉伯数字：带十百千万的按数值算，不带的逐位读（「二零二四」）；「点」后面逐位。 */
    fun cn(s: String): String? {
        val (int, frac) = if ('点' in s) s.substringBefore('点') to s.substringAfter('点') else s to ""
        val head = if (int.any { it in "十百千万亿" }) value(int)?.toString() else int.map { d(it) ?: return null }.joinToString("")
        if (head == null || head.isEmpty() && frac.isEmpty()) return null
        if (frac.isEmpty()) return head
        return head.ifEmpty { "0" } + "." + frac.map { d(it) ?: return null }.joinToString("")
    }

    private fun d(c: Char): Int? = when (c) { '〇' -> 0; '两' -> 2; else -> "零一二三四五六七八九".indexOf(c).takeIf { it >= 0 } }

    private fun value(s: String): Long? {
        var total = 0L; var section = 0L; var num = 0L
        for (c in s) {
            val n = d(c)
            if (n != null) { num = n.toLong(); continue }
            when (c) {
                '十' -> { section += (if (num == 0L) 1 else num) * 10; num = 0 }
                '百' -> { section += num * 100; num = 0 }
                '千' -> { section += num * 1000; num = 0 }
                '万' -> { total += (section + num) * 10_000; section = 0; num = 0 }
                '亿' -> { total = (total + section + num) * 100_000_000; section = 0; num = 0 }
                else -> return null
            }
        }
        return total + section + num
    }

    /** 1 − 编辑距离 / 较长的长度。 */
    fun similar(a: String, b: String): Double {
        if (a == b) return 1.0
        val n = maxOf(a.length, b.length)
        if (n == 0) return 1.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / n
    }
}
