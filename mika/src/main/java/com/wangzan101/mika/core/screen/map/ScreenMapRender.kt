/*
 * Source: https://github.com/wangzan101/Mika, commit 148fb782 (Apache-2.0).
 * Jack AI modification: a screen's label is its three most frequent anchors instead of every anchor; exits are
 * ordered by how often they were walked and capped; texts are worded for Kavi's phone tool.
 */
package com.wangzan101.mika.core.screen.map

/**
 * 把屏图渲染成给模型看的紧凑地图文字（design D8）。屏没有语义名（D3），名字就用骨架锚点自描述；
 * 图超长只截屏清单，不挤掉屏幕清单。
 */
object ScreenMapRender {
    /** 你现在在哪个屏；认不出返回空串。 */
    fun renderPosition(graph: ScreenGraph, currentId: String?): String {
        val node = currentId?.let { graph.nodes[it] } ?: return ""
        return "现在在「${label(node)}」这一页"
    }

    /** 进入某 App 时给模型的屏图：有图才给；认得出当前屏就带「你在哪 + 从这怎么走」，认不出只给页面清单。 */
    fun renderMapFor(graph: ScreenGraph?, skeletonTexts: Set<String>?): String? {
        if (graph == null || graph.nodes.isEmpty()) return null
        if (skeletonTexts == null) return null
        val currentId = ScreenRecognizer.identify(skeletonTexts, graph.nodes())?.id
        return renderMap(graph, currentId).ifEmpty { null }
    }

    /** 页面清单 + 从当前页出发的路。超 [maxScreens] 截清单、超 [maxChars] 截整段。图空返回空串。 */
    fun renderMap(graph: ScreenGraph, currentId: String?, maxScreens: Int = 12, maxChars: Int = 1200): String {
        if (graph.nodes.isEmpty()) return ""
        val lines = mutableListOf<String>()
        val all = graph.nodes.values.sortedWith(compareByDescending<ScreenNode> { it.visits }.thenBy { it.id })
        lines.add("走过的页面（${all.size}）：" + all.take(maxScreens).joinToString(" / ") { "「${label(it)}」" } +
            if (all.size > maxScreens) " ……" else "")
        val cur = currentId?.let { graph.nodes[it] }
        if (cur != null) {
            lines.add("现在在「${label(cur)}」这一页")
            val outs = cur.transitions.values.filter { it.to != null && it.to != currentId && it.effect == TransitionEffect.NAVIGATED }
                .sortedWith(compareByDescending<Transition> { it.count }.thenBy { it.key() }).take(8)
            if (outs.isNotEmpty()) {
                lines.add("从这一页走过：")
                outs.forEach { t ->
                    val toLabel = t.to?.let { graph.nodes[it]?.let(::label) } ?: t.to.orEmpty()
                    val what = t.target?.let { "点「$it」" } ?: t.action
                    lines.add("  $what → 「$toLabel」")
                }
            }
        }
        val out = lines.joinToString("\n")
        return if (out.length <= maxChars) out else out.take(maxChars)
    }

    /** 屏的名字：出现得最多的三个骨架文字。 */
    fun label(node: ScreenNode): String =
        node.anchors.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(3).joinToString("·") { it.key }.ifEmpty { node.id.take(8) }
}
