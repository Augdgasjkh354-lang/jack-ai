/*
 * Source: https://github.com/wangzan101/Mika, commit 148fb782 (Apache-2.0).
 * Jack AI modification: wrongOwner reads Kavi's [ScreenMove] list instead of Mika's RunEvent stream.
 */
package com.wangzan101.mika.core.screen.map

/**
 * merge gate 自检（design D7）：两道必须为 0 的关卡，作为自检/测试用，不是运行指标。
 * 拦「合错」这一向（两个不同的页被当成一个、或屏归错了 App）；「拆」的方向不设门槛（D9）。
 */
object ScreenMapMergeGate {
    /** 两个标了不同语义名、却被认成同一屏的屏 → 合错的嫌疑。返回 (被认错的屏 id, 被认成了谁)。[labeled] 缺名时不判。 */
    fun wrongMerge(nodes: List<ScreenNode>, labeled: Map<String, String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (a in nodes) {
            val labelA = labeled[a.id] ?: continue
            val rec = ScreenRecognizer.identify(a.anchors.keys.toSet(), nodes) ?: continue
            if (rec.id == a.id) continue
            val labelB = labeled[rec.id] ?: continue
            if (labelA != labelB) out += a.id to rec.id
        }
        return out
    }

    /** 同一屏签名出现在两个不同的包名下（且两个包名都已有图）：屏归错了 App。返回这些签名。 */
    fun wrongOwner(moves: List<ScreenMove>, graphs: Map<String, ScreenGraph>): List<String> {
        val owners = mutableMapOf<String, MutableSet<String>>()
        for (m in moves) {
            for ((pkg, texts) in listOf(m.beforeApp to m.beforeTexts, m.afterApp to m.afterTexts)) {
                if (pkg == null || pkg !in graphs || texts.isEmpty()) continue
                owners.getOrPut(ScreenId.signature(texts)) { mutableSetOf() }.add(pkg)
            }
        }
        return owners.filterValues { it.size > 1 }.keys.sorted()
    }
}
