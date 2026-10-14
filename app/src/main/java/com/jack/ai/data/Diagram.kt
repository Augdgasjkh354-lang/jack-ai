package com.jack.ai.data

import kotlin.math.max

/**
 * 树状图（思维导图）和流程图的排版。只算位置，不画图，方便单独测试。
 * 画图在 DiagramWriter 里。
 */
object Diagram {
    const val FONT = 26f
    const val LINE_H = 36f
    private const val PAD_X = 22f
    private const val PAD_Y = 14f

    fun interface Measure { fun width(s: String): Float }

    data class Box(
        val id: String, val lines: List<String>,
        val x: Float, val y: Float, val w: Float, val h: Float,
        /** root / box / round / diamond */
        val shape: String, val group: Int = 0, val depth: Int = 0,
    ) {
        val cx get() = x + w / 2
        val cy get() = y + h / 2
    }

    data class Link(
        val points: List<Pair<Float, Float>>,
        val label: String = "",
        val curved: Boolean = false,
        val group: Int = 0,
        val arrow: Boolean = true,
    )

    data class Layout(val width: Float, val height: Float, val boxes: List<Box>, val links: List<Link>)

    class TreeNode(val label: String, val children: MutableList<TreeNode> = mutableListOf())

    data class FNode(val id: String, val label: String, val shape: String = "box")
    data class FEdge(val from: String, val to: String, val label: String = "")

    /** 按宽度折行：中文逐字折，英文尽量在空格处折。 */
    fun wrap(text: String, m: Measure, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        for (para in text.split('\n')) {
            var line = StringBuilder()
            for (ch in para) {
                line.append(ch)
                if (line.length > 1 && m.width(line.toString()) > maxW) {
                    val sp = line.lastIndexOf(" ")
                    if (sp > 0) {
                        out += line.substring(0, sp).trimEnd()
                        line = StringBuilder(line.substring(sp + 1))
                    } else {
                        out += line.substring(0, line.length - 1)
                        line = StringBuilder(ch.toString())
                    }
                }
            }
            val rest = line.toString().trim()
            if (rest.isNotEmpty()) out += rest
        }
        return out.ifEmpty { listOf("") }
    }

    // ---------- 树状图 ----------

    private val BULLET = Regex("""^([-*+•·]|\d+[.)、])\s+""")
    private val HEADING = Regex("""^(#{1,6})\s+""")

    /**
     * 大纲 → 树。支持缩进列表（- / * / 1.）和 Markdown 标题（# ## ###）。
     * 只有一个顶层节点时它就是中心主题，否则用 title 当中心主题。
     */
    fun parseOutline(text: String, title: String): TreeNode {
        val virtual = TreeNode(title.ifBlank { "主题" })
        val stack = ArrayDeque<Pair<Int, TreeNode>>()
        for (raw in text.lines()) {
            if (raw.isBlank()) continue
            val expanded = raw.replace("\t", "    ")
            var indent = expanded.length - expanded.trimStart().length
            var s = expanded.trim()
            HEADING.find(s)?.let { h ->
                indent = (h.groupValues[1].length - 7) * 1000
                s = s.substring(h.value.length)
            }
            BULLET.find(s)?.let { s = s.substring(it.value.length) }
            s = s.replace("**", "").trim()
            if (s.isEmpty()) continue
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
            val node = TreeNode(s)
            (stack.lastOrNull()?.second ?: virtual).children += node
            stack.addLast(indent to node)
        }
        return if (virtual.children.size == 1) virtual.children[0] else virtual
    }

    fun tree(root: TreeNode, m: Measure): Layout {
        class P(val label: String, val depth: Int, val group: Int, val lines: List<String>, val w: Float, val h: Float) {
            var x = 0f
            var y = 0f
            val kids = mutableListOf<P>()
        }
        val colW = mutableListOf<Float>()
        fun build(n: TreeNode, depth: Int, group: Int): P {
            val lines = wrap(n.label, m, if (depth == 0) 340f else 300f)
            val w = max(90f, lines.maxOf { m.width(it) } + PAD_X * 2 + if (depth == 0) 16f else 0f)
            val h = lines.size * LINE_H + PAD_Y * 2 + if (depth == 0) 12f else 0f
            val p = P(n.label, depth, group, lines, w, h)
            while (colW.size <= depth) colW += 0f
            colW[depth] = max(colW[depth], w)
            n.children.forEachIndexed { i, c -> p.kids += build(c, depth + 1, if (depth == 0) i else group) }
            return p
        }
        val top = build(root, 0, 0)
        val gapX = 80f
        val gapY = 18f
        val colX = FloatArray(colW.size)
        for (d in 1 until colW.size) colX[d] = colX[d - 1] + colW[d - 1] + gapX

        fun shift(p: P, dy: Float) { p.y += dy; p.kids.forEach { shift(it, dy) } }
        var cursor = 0f
        fun place(p: P) {
            p.x = colX[p.depth]
            val startCursor = cursor
            if (p.kids.isEmpty()) {
                p.y = cursor
                cursor += p.h + gapY
                return
            }
            p.kids.forEach { place(it) }
            val first = p.kids.first()
            val last = p.kids.last()
            p.y = (first.y + first.h / 2 + last.y + last.h / 2) / 2 - p.h / 2
            if (p.y < startCursor) {
                // 父节点比子节点们高，整体往下挪
                val dy = startCursor - p.y
                shift(p, dy)
                cursor += dy
            }
            cursor = max(cursor, p.y + p.h + gapY)
        }
        place(top)

        val boxes = mutableListOf<Box>()
        val links = mutableListOf<Link>()
        var n = 0
        fun collect(p: P) {
            boxes += Box("n${n++}", p.lines, p.x, p.y, p.w, p.h, if (p.depth == 0) "root" else "box", p.group, p.depth)
            p.kids.forEach { k ->
                links += Link(listOf(p.x + p.w to p.y + p.h / 2, k.x to k.y + k.h / 2), curved = true, group = k.group, arrow = false)
                collect(k)
            }
        }
        collect(top)
        val width = boxes.maxOf { it.x + it.w }
        return Layout(width, max(cursor - gapY, top.h), boxes, links)
    }

    // ---------- 流程图 ----------

    fun flow(nodes: List<FNode>, edges: List<FEdge>, horizontal: Boolean, m: Measure): Layout {
        val all = LinkedHashMap<String, FNode>()
        nodes.forEach { all.putIfAbsent(it.id, it) }
        edges.forEach { e -> listOf(e.from, e.to).forEach { all.putIfAbsent(it, FNode(it, it)) } }
        require(all.isNotEmpty()) { "流程图没有节点" }
        val ids = all.keys.toList()
        val outEdges = ids.associateWith { mutableListOf<Int>() }
        edges.forEachIndexed { i, e -> outEdges.getValue(e.from) += i }

        // 找回头的边（循环），排层次时先忽略它们
        val state = HashMap<String, Int>()
        val back = HashSet<Int>()
        fun dfs(u: String) {
            state[u] = 1
            for (ei in outEdges.getValue(u)) {
                val v = edges[ei].to
                when (state[v] ?: 0) {
                    1 -> back += ei
                    0 -> dfs(v)
                }
            }
            state[u] = 2
        }
        ids.forEach { if ((state[it] ?: 0) == 0) dfs(it) }

        // 最长路径分层
        val rank = HashMap<String, Int>().apply { ids.forEach { put(it, 0) } }
        val indeg = HashMap<String, Int>().apply { ids.forEach { put(it, 0) } }
        edges.forEachIndexed { i, e -> if (i !in back) indeg[e.to] = indeg.getValue(e.to) + 1 }
        val queue = ArrayDeque(ids.filter { indeg.getValue(it) == 0 })
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (ei in outEdges.getValue(u)) {
                if (ei in back) continue
                val v = edges[ei].to
                rank[v] = max(rank.getValue(v), rank.getValue(u) + 1)
                indeg[v] = indeg.getValue(v) - 1
                if (indeg.getValue(v) == 0) queue.addLast(v)
            }
        }
        val maxRank = rank.values.max()
        val layers = (0..maxRank).map { r -> ids.filter { rank[it] == r }.toMutableList() }

        // 按上一层的平均位置排序，减少连线交叉
        val preds = ids.associateWith { mutableListOf<String>() }
        edges.forEachIndexed { i, e -> if (i !in back) preds.getValue(e.to) += e.from }
        repeat(3) {
            for (r in 1..maxRank) {
                val pos = HashMap<String, Float>()
                layers.forEach { l -> l.forEachIndexed { i, id -> pos[id] = i - (l.size - 1) / 2f } }
                val keyed = layers[r].mapIndexed { i, id ->
                    val ps = preds.getValue(id).mapNotNull { pos[it] }
                    id to (if (ps.isEmpty()) pos.getValue(id) else ps.average().toFloat()) + i * 0.001f
                }
                layers[r].clear()
                layers[r].addAll(keyed.sortedBy { it.second }.map { it.first })
            }
        }

        // 每个节点的大小
        data class Size(val lines: List<String>, val w: Float, val h: Float)
        val size = ids.associateWith { id ->
            val n = all.getValue(id)
            val diamond = n.shape == "diamond"
            val lines = wrap(n.label, m, if (diamond) 200f else 280f)
            val tw = lines.maxOf { m.width(it) }
            val th = lines.size * LINE_H
            if (diamond) Size(lines, max(160f, tw * 2 + 30f), max(100f, th * 2 + 20f))
            else Size(lines, max(120f, tw + PAD_X * 2), th + PAD_Y * 2 + 4f)
        }

        val gapMain = 76f   // 层与层之间
        val gapCross = 48f  // 同一层节点之间
        val boxes = HashMap<String, Box>()
        // 主方向：竖排时是 y，横排时是 x
        val layerThick = layers.map { l -> l.maxOf { if (horizontal) size.getValue(it).w else size.getValue(it).h } }
        val layerLen = layers.map { l -> l.sumOf { (if (horizontal) size.getValue(it).h else size.getValue(it).w).toDouble() }.toFloat() + gapCross * (l.size - 1) }
        val maxLen = layerLen.max()
        var main = 0f
        layers.forEachIndexed { r, l ->
            var cross = (maxLen - layerLen[r]) / 2
            l.forEach { id ->
                val s = size.getValue(id)
                val n = all.getValue(id)
                boxes[id] = if (horizontal) {
                    Box(id, s.lines, main + (layerThick[r] - s.w) / 2, cross, s.w, s.h, n.shape)
                } else {
                    Box(id, s.lines, cross, main + (layerThick[r] - s.h) / 2, s.w, s.h, n.shape)
                }
                cross += (if (horizontal) s.h else s.w) + gapCross
            }
            main += layerThick[r] + gapMain
        }
        val layerStart = FloatArray(layers.size)
        run { var a = 0f; layers.indices.forEach { layerStart[it] = a; a += layerThick[it] + gapMain } }

        var width = boxes.values.maxOf { it.x + it.w }
        var height = boxes.values.maxOf { it.y + it.h }
        val links = mutableListOf<Link>()
        var backCount = 0
        edges.forEachIndexed { i, e ->
            val a = boxes.getValue(e.from)
            val b = boxes.getValue(e.to)
            if (i !in back && rank.getValue(e.to) > rank.getValue(e.from)) {
                val r = rank.getValue(e.from)
                val mid = layerStart[r] + layerThick[r] + gapMain / 2
                val pts = if (!horizontal) {
                    val s = a.cx to a.y + a.h
                    val t = b.cx to b.y
                    if (kotlin.math.abs(s.first - t.first) < 1f) listOf(s, t)
                    else listOf(s, s.first to mid, t.first to mid, t)
                } else {
                    val s = a.x + a.w to a.cy
                    val t = b.x to b.cy
                    if (kotlin.math.abs(s.second - t.second) < 1f) listOf(s, t)
                    else listOf(s, mid to s.second, mid to t.second, t)
                }
                links += Link(pts, e.label)
            } else {
                // 回头的边从侧面绕回去
                val off = 36f + backCount++ * 22f
                val pts = if (!horizontal) {
                    val x = max(a.x + a.w, b.x + b.w).let { max(it, width) } + off
                    listOf(a.x + a.w to a.cy, x to a.cy, x to b.cy, b.x + b.w to b.cy)
                } else {
                    val y = max(a.y + a.h, b.y + b.h).let { max(it, height) } + off
                    listOf(a.cx to a.y + a.h, a.cx to y, b.cx to y, b.cx to b.y + b.h)
                }
                links += Link(pts, e.label)
            }
        }
        links.flatMap { it.points }.forEach { (x, y) -> width = max(width, x); height = max(height, y) }
        val pad = if (backCount > 0) 8f else 0f
        return Layout(width + pad, height + pad, ids.map { boxes.getValue(it) }, links)
    }
}
