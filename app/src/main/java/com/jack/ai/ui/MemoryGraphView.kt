package com.jack.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.memory.MemoryGraph
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 记忆关系图：点是记忆里标过的人、地方、项目……（越大说明涉及它的记忆越多），线是两者出现在同一条记忆里。
 * 点一个只看关于它的记忆，再点取消。布局按力导向算一次（同样的记忆每次排得一样）。
 */
@Composable
fun MemoryGraphView(all: List<MemoryItem>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val graph = remember(all) { layout(all) }
    if (graph.nodes.size < 2) return
    val measurer = rememberTextMeasurer()
    val c = MaterialTheme.colorScheme
    val sel = selected?.let(MemoryGraph::norm)
    Canvas(
        modifier.fillMaxWidth().height(280.dp).pointerInput(graph) {
            detectTapGestures { p ->
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val hit = graph.nodes.minByOrNull { n -> val d = Offset(n.x * w, n.y * h) - p; d.getDistanceSquared() }
                val near = hit?.let { n -> (Offset(n.x * w, n.y * h) - p).getDistance() < 28.dp.toPx() } == true
                onSelect(if (!near || hit!!.key == sel) null else hit.label)
            }
        },
    ) {
        val w = size.width; val h = size.height
        val linked = sel?.let { s -> graph.edges.filter { it.a == s || it.b == s }.flatMap { listOf(it.a, it.b) }.toSet() }
        val maxW = graph.edges.maxOfOrNull { it.weight } ?: 1
        graph.edges.forEach { e ->
            val a = graph.byKey[e.a]!!; val b = graph.byKey[e.b]!!
            val on = sel == null || e.a == sel || e.b == sel
            drawLine(c.primary.copy(alpha = if (on) 0.15f + 0.35f * e.weight / maxW else 0.05f),
                Offset(a.x * w, a.y * h), Offset(b.x * w, b.y * h), strokeWidth = (1 + 2f * e.weight / maxW).dp.toPx())
        }
        val maxN = graph.nodes.maxOf { it.count }
        graph.nodes.forEach { n ->
            val on = sel == null || n.key == sel || linked?.contains(n.key) == true
            val r = (5 + 9f * n.count / maxN).dp.toPx()
            val center = Offset(n.x * w, n.y * h)
            drawCircle(if (n.key == sel) c.primary else c.primary.copy(alpha = if (on) 0.55f else 0.15f), r, center)
            val text = measurer.measure(n.label, TextStyle(fontSize = 11.sp, color = c.onSurface.copy(alpha = if (on) 0.9f else 0.3f)))
            val tx = (center.x - text.size.width / 2f).coerceIn(0f, max(0f, w - text.size.width))
            val ty = min(center.y + r + 2.dp.toPx(), h - text.size.height)
            drawText(text, topLeft = Offset(tx, ty))
        }
    }
}

private class Node(val key: String, val label: String, val count: Int, var x: Float, var y: Float)
private class Edge(val a: String, val b: String, val weight: Int)
private class Graph(val nodes: List<Node>, val edges: List<Edge>) { val byKey = nodes.associateBy { it.key } }

/** 取涉及最多的 24 个，按力导向排（相连的互相拉近，所有点互相推开），坐标落在 0..1 里，留出边距。 */
private fun layout(all: List<MemoryItem>): Graph {
    val ents = MemoryGraph.entities(all).entries.sortedByDescending { it.value.size }.take(24)
    if (ents.size < 2) return Graph(emptyList(), emptyList())
    val keys = ents.map { it.key }.toSet()
    val nodes = ents.mapIndexed { i, (k, v) ->
        // 起点排一圈（确定的，不用随机数）
        val t = 2 * Math.PI * i / ents.size
        Node(k, v.first().about.firstOrNull { MemoryGraph.norm(it) == k } ?: k, v.size,
            (0.5 + 0.35 * kotlin.math.cos(t)).toFloat(), (0.5 + 0.35 * kotlin.math.sin(t)).toFloat())
    }
    val pairs = HashMap<Pair<String, String>, Int>()
    all.forEach { m ->
        val ks = m.about.map(MemoryGraph::norm).filter { it in keys }.distinct().sorted()
        for (i in ks.indices) for (j in i + 1 until ks.size) pairs.merge(ks[i] to ks[j], 1, Int::plus)
    }
    val edges = pairs.map { (p, n) -> Edge(p.first, p.second, n) }
    val byKey = nodes.associateBy { it.key }
    val k = sqrt(1f / nodes.size) * 0.9f
    var temp = 0.1f
    repeat(160) {
        val dx = FloatArray(nodes.size); val dy = FloatArray(nodes.size)
        for (i in nodes.indices) for (j in nodes.indices) if (i != j) {
            val ox = nodes[i].x - nodes[j].x; val oy = nodes[i].y - nodes[j].y
            val d = max(0.01f, sqrt(ox * ox + oy * oy))
            val f = k * k / d
            dx[i] += ox / d * f; dy[i] += oy / d * f
        }
        val idx = nodes.withIndex().associate { it.value.key to it.index }
        edges.forEach { e ->
            val a = byKey[e.a]!!; val b = byKey[e.b]!!
            val ox = a.x - b.x; val oy = a.y - b.y
            val d = max(0.01f, sqrt(ox * ox + oy * oy))
            val f = d * d / k * (1 + 0.3f * e.weight)
            val ia = idx[e.a]!!; val ib = idx[e.b]!!
            dx[ia] -= ox / d * f; dy[ia] -= oy / d * f
            dx[ib] += ox / d * f; dy[ib] += oy / d * f
        }
        nodes.forEachIndexed { i, n ->
            // 往中间拉：不相连的几团不会各自飘到角落，点和点之间也留得开
            dx[i] += (0.5f - n.x) * 2f; dy[i] += (0.5f - n.y) * 2f
            val d = max(0.0001f, sqrt(dx[i] * dx[i] + dy[i] * dy[i]))
            n.x += dx[i] / d * min(d, temp); n.y += dy[i] / d * min(d, temp)
        }
        temp *= 0.97f
    }
    // 拉伸到 0.08..0.92，标签不出界
    val minX = nodes.minOf { it.x }; val maxX = nodes.maxOf { it.x }
    val minY = nodes.minOf { it.y }; val maxY = nodes.maxOf { it.y }
    nodes.forEach { n ->
        n.x = 0.08f + 0.84f * (n.x - minX) / max(0.001f, maxX - minX)
        n.y = 0.06f + 0.82f * (n.y - minY) / max(0.001f, maxY - minY)
    }
    return Graph(nodes, edges)
}
