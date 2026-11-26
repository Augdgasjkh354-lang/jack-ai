/*
 * Source: https://github.com/wangzan101/Mika, commit 148fb782 (Apache-2.0).
 * Jack AI modification: input is a plain [ScreenMove] list recorded by Kavi's phone tool instead of Mika's RunEvent
 * stream; screens with no skeleton text are skipped (nothing to recognize them by); a screen that the recognizer
 * matches with high confidence ([SAME_SCREEN]) is counted as the same node instead of splitting on every optional
 * banner. Replay order is still fixed, so the same moves always build the same graph.
 */
package com.wangzan101.mika.core.screen.map

/**
 * 一次动作：动作前在哪个 App 的哪一屏（骨架文字）、做了什么（[action]，点的是 [target]）、动作后到了哪。
 * [effective] 动作有没有让界面变（没换屏时区分「同屏变了状态」和「没反应」）。
 */
data class ScreenMove(
    val beforeApp: String?,
    val beforeTexts: List<String>,
    val action: String,
    val target: String?,
    val afterApp: String?,
    val afterTexts: List<String>,
    val effective: Boolean,
)

/**
 * 从一串动作确定性地长出屏图（design D4）：每个动作前、后各认一次屏（认得出就是老屏、计数加一；认不出建新屏），
 * 动作记成一条转移边。宁可分开不可合错：只有认屏置信度到 [SAME_SCREEN] 才算同一屏。
 */
class ScreenMapBuilder {
    fun apply(moves: List<ScreenMove>, base: Map<String, ScreenGraph> = emptyMap()): Map<String, ScreenGraph> {
        val graphs = linkedMapOf<String, MutableMap<String, ScreenNode>>()
        for (m in moves) {
            val beforeApp = m.beforeApp ?: continue
            val afterApp = m.afterApp ?: continue
            val beforeNode = visitOrCreate(graphs, base, beforeApp, m.beforeTexts) ?: continue
            // 跳去别的 App：那边的屏也记下（是那个 App 的图），这条边不指向本图的屏
            val afterNode = visitOrCreate(graphs, base, afterApp, m.afterTexts)
            val effect = when {
                beforeApp != afterApp -> TransitionEffect.LEFT_APP
                afterNode == null -> TransitionEffect.UNKNOWN
                afterNode.id != beforeNode.id -> TransitionEffect.NAVIGATED
                m.effective -> TransitionEffect.STATE_CHANGED
                else -> TransitionEffect.NONE
            }
            val to = if (effect == TransitionEffect.LEFT_APP || effect == TransitionEffect.UNKNOWN) null else afterNode?.id
            val edge = Transition(action = m.action, target = m.target?.takeIf { it.isNotEmpty() }, effect = effect, to = to, count = 1)
            val nodes = graphs.getValue(beforeApp)
            val cur = nodes.getValue(beforeNode.id)
            val key = edge.key()
            val prev = cur.transitions[key]
            nodes[beforeNode.id] = cur.copy(transitions = cur.transitions + (key to (prev?.let { it.copy(count = it.count + 1) } ?: edge)))
        }
        return graphs.mapValues { (app, nodes) -> ScreenGraph(app, nodes.toMap()) }
    }

    /** [base] 是已存的图：先在它和这次已经长出的屏里认，认得出就并进那个屏的 id（这次的片段里只记增量）。 */
    private fun visitOrCreate(
        graphs: MutableMap<String, MutableMap<String, ScreenNode>>, base: Map<String, ScreenGraph>, app: String, texts: List<String>,
    ): ScreenNode? {
        if (texts.isEmpty()) return null
        val nodes = graphs.getOrPut(app) { linkedMapOf() }
        val known = (base[app] ?: ScreenGraph(app, emptyMap())).mergedWith(ScreenGraph(app, nodes.toMap())).nodes()
        val id = ScreenRecognizer.identify(texts.toSet(), known, SAME_SCREEN)?.id ?: ScreenId.of(app, ScreenId.signature(texts))
        val existing = nodes[id]
        val updated = if (existing != null) existing.copy(anchors = increment(existing.anchors, texts), visits = existing.visits + 1, status = "confirmed")
        else ScreenNode(id = id, app = app, anchors = texts.associateWith { 1 }, visits = 1,
            status = if (base[app]?.nodes?.containsKey(id) == true) "confirmed" else "provisional")
        nodes[id] = updated
        return updated
    }

    private fun increment(anchors: Map<String, Int>, texts: List<String>): Map<String, Int> {
        val m = anchors.toMutableMap()
        for (t in texts.distinct()) m[t] = (m[t] ?: 0) + 1
        return m
    }

    companion object {
        /** 认成同一屏要的置信度：比认屏时高，拿不准就分开（合错比拆多危险）。 */
        const val SAME_SCREEN = 0.8
    }
}
