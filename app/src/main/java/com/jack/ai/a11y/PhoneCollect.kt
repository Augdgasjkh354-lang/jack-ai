package com.jack.ai.a11y

/**
 * 逐屏读长列表、聊天记录：把相邻两屏按重叠的几行拼起来，重叠的只留一份。
 * 思路来自 Mika 的 phone_collect，Apache-2.0。
 */
object PhoneCollect {
    /** 拼好的行；[gaps] 是前后两屏一行都对不上的次数（中间可能漏了，用「……」标出来）。 */
    data class Lines(val lines: List<String>, val gaps: Int = 0)

    const val GAP = "……"

    /**
     * 往下读（[prepend] = false）：新的一屏接在后面，去掉和已有末尾重叠的开头；
     * 往上读（聊天记录往前翻，[prepend] = true）：新的一屏放在前面，去掉和已有开头重叠的结尾。
     * 重叠按最长的那段算：新的一屏整个都已经有了就什么也不加。
     */
    fun merge(acc: Lines, next: List<String>, prepend: Boolean): Lines {
        if (next.isEmpty()) return acc
        if (acc.lines.isEmpty()) return Lines(next, acc.gaps)
        val max = minOf(acc.lines.size, next.size)
        val k = (max downTo 1).firstOrNull { k ->
            if (prepend) next.takeLast(k) == acc.lines.take(k) else acc.lines.takeLast(k) == next.take(k)
        } ?: 0
        // 新的一屏整个都在已有的里面（比如翻动的距离很小）：不加
        if (k == 0 && containsRun(acc.lines, next)) return acc
        val gap = if (k == 0) listOf(GAP) else emptyList()
        return if (prepend) Lines(next.dropLast(k) + gap + acc.lines, acc.gaps + gap.size)
        else Lines(acc.lines + gap + next.drop(k), acc.gaps + gap.size)
    }

    private fun containsRun(all: List<String>, run: List<String>): Boolean =
        run.size <= all.size && (0..all.size - run.size).any { i -> all.subList(i, i + run.size) == run }
}
