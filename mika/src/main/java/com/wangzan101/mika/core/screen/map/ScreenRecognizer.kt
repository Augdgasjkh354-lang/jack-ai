/*
 * Source: https://github.com/wangzan101/Mika, commit 148fb782 (Apache-2.0).
 * Jack AI modification: score = harmonic mean of a weighted recall (the node's anchors found on screen, weighted by
 * rarity across screens and by how often the anchor showed up on that screen) and a precision over the screen's texts
 * the graph already knows; when the runner-up scores within [MARGIN] of the best, the screen is ambiguous and
 * nothing is returned. Upstream averaged rarity over matched anchors only, which kept screens that share a tab bar
 * ("首页/消息/我的") below the threshold even on a perfect match.
 */
package com.wangzan101.mika.core.screen.map

/** 认屏结果：认成哪个屏 [id]、置信度 [confidence]、命中了哪些骨架锚点 [matched]。 */
data class Recognition(val id: String, val confidence: Double, val matched: Set<String>)

/**
 * 认屏：纯函数、零模型调用。给当前屏的骨架文字集合 [texts]（去数字、去列表内容、去输入框），在 [nodes] 里找最像的一屏。
 * 低于 [confidenceMin] 返回 null：认错比认不出更糟（design D2），不猜。
 */
object ScreenRecognizer {
    /** 第一像和第二像至少要差这么多才认。 */
    const val MARGIN = 0.1

    fun identify(texts: Set<String>, nodes: List<ScreenNode>, confidenceMin: Double = 0.6): Recognition? {
        if (nodes.isEmpty() || texts.isEmpty()) return null
        // 每个骨架文字在多少个屏里出现过（文档频率）：「返回」「首页」哪都有，几乎不是证据
        val df = nodes.flatMap { it.anchors.keys }.groupingBy { it }.eachCount()
        fun idf(t: String) = 1.0 / maxOf(1, df[t] ?: 1)
        // 这一屏上图里认得的字（没见过的多半是新内容，不算反证）
        val known = texts.filter { (df[it] ?: 0) > 0 }
        val knownWeight = known.sumOf(::idf)
        var best: Recognition? = null
        var second = 0.0
        for (node in nodes.sortedBy { it.id }) {
            if (node.anchors.isEmpty()) continue
            val matched = node.anchors.keys.filterTo(sortedSetOf()) { it in texts }
            if (matched.isEmpty()) continue
            val visits = maxOf(1, node.visits)
            // 偶尔才出现的（横幅、角标）权重低：这一屏出现过几次 / 来过几次
            fun w(t: String) = idf(t) * minOf(1.0, (node.anchors[t] ?: 0).toDouble() / visits)
            val recall = matched.sumOf(::w) / node.anchors.keys.sumOf(::w).coerceAtLeast(1e-9)
            val precision = if (knownWeight <= 0) 0.0 else matched.sumOf(::idf) / knownWeight
            val score = if (recall + precision <= 0) 0.0 else 2 * recall * precision / (recall + precision)
            val b = best
            if (b == null || score > b.confidence || (score == b.confidence && matched.size > b.matched.size)) {
                if (b != null) second = maxOf(second, b.confidence)
                best = Recognition(node.id, score, matched)
            } else second = maxOf(second, score)
        }
        // 两页差不多像（只剩底栏那几个到处都有的字）：分不清就当不知道
        return best?.takeIf { it.confidence >= confidenceMin && it.confidence - second >= MARGIN }
    }
}
