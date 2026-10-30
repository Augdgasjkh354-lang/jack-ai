package com.jack.ai.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 照片里的表格：文字识别给的是一块块字和它们的位置，这里按位置排回行和列。
 * 1. 一行字里隔得远的拆开（表格的两格之间空得多），隔得近的连起来；
 * 2. 竖着离得近的算同一行；
 * 3. 格子最多的那几行定出每一列在哪，别的格子归到重叠最多（或最近）的那列。
 */
object TableGrid {
    /** 一块字：文字、左上右下（像素）、识别把握（0..1，不知道写 1）。 */
    data class Box(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float = 1f) {
        val h get() = bottom - top
        val cy get() = (top + bottom) / 2.0
        val cx get() = (left + right) / 2.0
    }

    class Result(val rows: List<List<String>>, val unsure: List<String>)

    /**
     * lines：识别出来的每一行，里面是这一行的字块（ML Kit 的 TextLine → TextElement），按从左到右。
     */
    fun build(lines: List<List<Box>>): Result {
        val frags = mutableListOf<Box>()
        for (line in lines) {
            val els = line.filter { it.text.isNotBlank() }.sortedBy { it.left }
            if (els.isEmpty()) continue
            val h = els.map { it.h }.sorted()[els.size / 2].coerceAtLeast(1)
            var cur = els[0]
            for (e in els.drop(1)) {
                // 字块之间空得比一个字还宽，多半是两格
                if (e.left - cur.right > h * 1.1) { frags += cur; cur = e }
                else cur = Box(join(cur.text, e.text), cur.left, min(cur.top, e.top), max(cur.right, e.right), max(cur.bottom, e.bottom), min(cur.confidence, e.confidence))
            }
            frags += cur
        }
        if (frags.isEmpty()) return Result(emptyList(), emptyList())
        val medianH = frags.map { it.h }.sorted()[frags.size / 2].coerceAtLeast(1)

        // 行：按竖直中心排，离当前行中心超过半个字高就另起一行
        val rows = mutableListOf<MutableList<Box>>()
        for (f in frags.sortedBy { it.cy }) {
            val row = rows.lastOrNull()
            if (row != null && abs(f.cy - row.map { it.cy }.average()) <= medianH * 0.6) row += f else rows += mutableListOf(f)
        }
        rows.forEach { r -> r.sortBy { it.left } }

        // 列：格子最多的那些行，每一格的左右范围合起来当作列的位置
        val maxCount = rows.maxOf { it.size }
        val ref = rows.filter { it.size == maxCount }
        val cols = (0 until maxCount).map { i -> ref.minOf { it[i].left } to ref.maxOf { it[i].right } }.toMutableList()
        fun overlap(b: Box, c: Pair<Int, Int>) = min(b.right, c.second) - max(b.left, c.first)
        val grid = rows.map { r ->
            val cells = Array(cols.size) { mutableListOf<String>() }
            r.forEach { b ->
                val best = cols.indices.maxByOrNull { overlap(b, cols[it]) }!!
                val col = if (overlap(b, cols[best]) > 0) best else cols.indices.minByOrNull { abs(b.cx - (cols[it].first + cols[it].second) / 2.0) }!!
                cells[col] += b.text
            }
            cells.map { it.joinToString(" ") }
        }.filter { r -> r.any { it.isNotBlank() } }
        // 整列空的去掉
        val keep = cols.indices.filter { c -> grid.any { it[c].isNotBlank() } }
        val unsure = frags.filter { it.confidence < 0.6f }.map { it.text }.distinct().take(20)
        return Result(grid.map { r -> keep.map { r[it].trim() } }, unsure)
    }

    /** 中文挨着中文不加空格，英文数字之间加。 */
    private fun join(a: String, b: String): String {
        val last = a.lastOrNull() ?: return b
        val first = b.firstOrNull() ?: return a
        return if (last.code >= 0x2E80 || first.code >= 0x2E80) a + b else "$a $b"
    }
}
