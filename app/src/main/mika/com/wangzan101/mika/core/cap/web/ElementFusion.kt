// Copyright 2026 wangzan101. Licensed under Apache-2.0.
// Ported from Mika 0091d2d (ElementFusion.kt).
// Jack AI modification: use Kavi's numbered browser elements and CSS-pixel bounds.
package com.wangzan101.mika.core.cap.web

import kotlin.math.max
import kotlin.math.min

/** 融合页面脚本与 CDP 无障碍树，保留页面原有编号，新增节点由调用方分配编号。 */
object ElementFusion {
    data class Box(val l: Double, val t: Double, val r: Double, val b: Double) {
        val x get() = (l + r) / 2
        val y get() = (t + b) / 2
        val width get() = r - l
        val height get() = b - t
        fun intersect(other: Box): Box? {
            val a = Box(max(l, other.l), max(t, other.t), min(r, other.r), min(b, other.b))
            return a.takeIf { it.width >= 2 && it.height >= 2 }
        }
        fun contains(x: Double, y: Double) = x in l..r && y in t..b
        fun overlap(other: Box): Double {
            val i = intersect(other) ?: return 0.0
            val union = width * height + other.width * other.height - i.width * i.height
            return if (union > 0) i.width * i.height / union else 0.0
        }
    }

    data class PageNode(val n: Int, val kind: String, val label: String, val box: Box)
    data class AxNode(
        val id: String, val parent: String?, val frameId: String, val order: Int,
        val backendId: Int?, val role: String, val name: String,
        val ignored: Boolean = false, val states: Set<String> = emptySet(),
    )
    data class Frame(val id: String, val parent: String?)
    data class Candidate(val node: AxNode, val ownerId: Int?, val clip: Box?)
    data class Extra(
        val backendId: Int, val frameId: String, val order: Int,
        val kind: String, val label: String, val box: Box, val clip: Box?,
        val ownerId: Int? = null, val hitId: Int? = null, val hits: Set<Int> = emptySet(),
        val type: String = "", val tag: String = "",
    )
    data class Merged(
        val oldN: Int?, val backendId: Int?, val kind: String, val label: String,
        val box: Box, val ownerId: Int? = null, val type: String = "", val tag: String = "",
    )
    data class Result(val elements: List<Merged>, val added: Int, val matched: Int)

    private val roles = mapOf(
        "button" to "按钮", "ToggleButton" to "按钮", "DisclosureTriangle" to "按钮",
        "link" to "链接", "checkbox" to "勾选框", "radio" to "单选",
        "textbox" to "输入框", "searchbox" to "搜索框", "combobox" to "下拉框",
        "PopUpButton" to "下拉框", "switch" to "开关", "tab" to "标签页",
        "menuitem" to "菜单项", "menuitemcheckbox" to "勾选框",
        "menuitemradio" to "单选", "option" to "选项", "treeitem" to "可点",
        "slider" to "滑条", "spinbutton" to "输入框",
    )
    private val textKinds = setOf("输入框", "搜索框", "日期框", "日期时间框", "时间框", "月份框", "周框", "密码框")
    fun kindFor(role: String): String? = roles[role]

    /** 缺失的交互节点：跳过已有编号、禁用节点、以及已经包含在一个可点外层里的子节点。 */
    fun candidates(page: List<PageNode>, ids: List<Int?>, ax: List<AxNode>,
                   frames: List<Frame>, hidden: Map<Int, Box>, owners: Map<Int, String>): List<Candidate> {
        val existing = ids.filterNotNull().toSet()
        val pageById = page.indices.mapNotNull { i -> ids.getOrNull(i)?.let { it to page[i] } }.toMap()
        val nodes = ax.groupBy { it.frameId }.mapValues { (_, list) -> list.associateBy { it.id } }
        val hiddenByFrame = hidden.mapNotNull { (backend, box) ->
            owners[backend]?.let { it to (backend to box) }
        }.toMap()
        val parentFrames = frames.associate { it.id to it.parent }
        fun owner(frame: String): Pair<Int, Box>? {
            var id: String? = frame
            repeat(32) {
                if (id == null) return null
                hiddenByFrame[id]?.let { return it }
                id = parentFrames[id]
            }
            return null
        }
        return ax.asSequence().mapNotNull { n ->
            val backend = n.backendId ?: return@mapNotNull null
            if (n.ignored || "disabled" in n.states || kindFor(n.role) == null || backend in existing) return@mapNotNull null
            var p = n.parent?.let { nodes[n.frameId]?.get(it) }
            var nested = false
            repeat(64) {
                if (p == null) return@repeat
                val prev = p!!
                if (prev.backendId?.let(pageById::get)?.kind in setOf(
                        "按钮", "链接", "可点", "输入框", "搜索框", "勾选框", "单选", "下拉框", "菜单项")) nested = true
                p = prev.parent?.let { nodes[n.frameId]?.get(it) }
            }
            if (nested) return@mapNotNull null
            val frame = owner(n.frameId)
            Candidate(n, frame?.first, frame?.second)
        }.sortedWith(compareBy<Candidate> { if (it.ownerId != null) 0 else 1 }
            .thenBy { it.node.order }).take(60).toList()
    }

    /** 命中测试落在节点自身或子孙上，都算它露在最上面。 */
    fun subtreeIds(ax: List<AxNode>, node: AxNode): Set<Int> {
        val children = ax.filter { it.frameId == node.frameId }.groupBy { it.parent }
        val out = HashSet<Int>()
        val seen = HashSet<String>()
        val stack = ArrayDeque(listOf(node))
        while (stack.isNotEmpty() && seen.size < 500) {
            val next = stack.removeLast()
            if (!seen.add(next.id)) continue
            next.backendId?.let(out::add)
            children[next.id]?.let(stack::addAll)
        }
        return out
    }

    /** 合并时先去遮挡和重复，再把缺失节点按页面位置插回，不更改旧编号。 */
    fun fuse(page: List<PageNode>, ids: List<Int?>, ax: List<AxNode>,
             extras: List<Extra>, viewport: Box): Result {
        val axById = ax.filter { !it.ignored && it.backendId != null }.associateBy { it.backendId }
        val base = page.mapIndexed { i, p ->
            val id = ids.getOrNull(i)
            val n = id?.let(axById::get)
            val kind = if (p.kind in textKinds || p.kind == "文件选择") p.kind
                else n?.role?.let(::kindFor) ?: p.kind
            // 输入框的值与敏感信息一律以页面脚本为准；无障碍名称只补非输入控件。
            val label = if (p.kind in textKinds || p.kind == "文件选择" || n?.name.isNullOrBlank()) p.label
                else n!!.name.replace(Regex("\\s+"), " ").trim().take(80)
            Merged(p.n, id, kind, label, p.box)
        }
        val added = ArrayList<Extra>()
        val nodes = ax.groupBy { it.frameId }.mapValues { (_, l) -> l.associateBy { it.id } }
        for (e in extras) {
            if (base.any { it.backendId == e.backendId } || "disabled" in
                    (axById[e.backendId]?.states ?: emptySet())) continue
            val rect = e.box.intersect(viewport)?.let { e.clip?.let(it::intersect) ?: it } ?: continue
            val visible = e.hitId in e.hits || (e.ownerId != null && e.hitId == e.ownerId)
            if (!visible) continue // 无法证明可点、或被挡住的，都不展示
            if (base.any {
                    it.box.contains(rect.x, rect.y) && (it.label.equals(e.label, true) ||
                        (it.kind == e.kind && it.box.overlap(rect) > 0.7))
                }) continue
            val nested = added.any { prev ->
                if (prev.kind != e.kind || !prev.label.equals(e.label, true)) return@any false
                var p = axById[e.backendId]?.parent
                val byFrame = nodes[e.frameId].orEmpty()
                var ancestor = false
                repeat(64) {
                    if (p == null) return@repeat
                    if (byFrame[p]?.backendId == prev.backendId) ancestor = true
                    p = byFrame[p]?.parent
                }
                ancestor || prev.box.overlap(rect) > 0.75
            }
            if (!nested) added += e.copy(box = rect)
        }
        val all = base.toMutableList()
        for (e in added) {
            val item = Merged(null, e.backendId, e.kind, e.label.trim().take(80), e.box,
                e.ownerId, e.type, e.tag)
            val before = all.indexOfFirst { it.box.y > e.box.y + 1 }
            if (before < 0) all += item else all.add(before, item)
        }
        return Result(all, added.size, base.count { it.backendId in axById })
    }
}
