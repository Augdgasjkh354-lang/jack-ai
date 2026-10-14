package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagramTest {
    // 中文一个字 26 像素，其他 14 像素
    private val m = Diagram.Measure { s -> s.sumOf { (if (it.code > 0x2E80) 26 else 14).toDouble() }.toFloat() }

    private fun overlap(a: Diagram.Box, b: Diagram.Box) =
        a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    @Test
    fun outlineParsing() {
        val root = Diagram.parseOutline(
            """
            - 学习计划
              - 数学
                - 微积分
                - 线性代数
              - 英语
                1. 单词
                2. 听力
            """.trimIndent(), "",
        )
        assertEquals("学习计划", root.label)
        assertEquals(listOf("数学", "英语"), root.children.map { it.label })
        assertEquals(listOf("单词", "听力"), root.children[1].children.map { it.label })

        val headings = Diagram.parseOutline("# 中心\n## 甲\n- 甲一\n## 乙", "")
        assertEquals("中心", headings.label)
        assertEquals(listOf("甲", "乙"), headings.children.map { it.label })
        assertEquals(listOf("甲一"), headings.children[0].children.map { it.label })

        val multi = Diagram.parseOutline("- A\n- B", "标题")
        assertEquals("标题", multi.label)
        assertEquals(2, multi.children.size)
    }

    @Test
    fun treeHasNoOverlaps() {
        val root = Diagram.parseOutline(
            (1..6).joinToString("\n") { i -> "- 分支$i\n" + (1..i).joinToString("\n") { j -> "  - 这是一个比较长的子节点名字 $i-$j" } },
            "中心主题",
        )
        val l = Diagram.tree(root, m)
        assertEquals(1 + 6 + 21, l.boxes.size)
        assertEquals(l.boxes.size - 1, l.links.size)
        for (i in l.boxes.indices) for (j in i + 1 until l.boxes.size) {
            assertTrue("${l.boxes[i]} vs ${l.boxes[j]}", !overlap(l.boxes[i], l.boxes[j]))
        }
        assertTrue(l.boxes.all { it.x >= 0 && it.y >= 0 && it.x + it.w <= l.width + 0.1f && it.y + it.h <= l.height + 0.1f })
    }

    @Test
    fun flowchartRanksAndLoops() {
        val nodes = listOf(
            Diagram.FNode("s", "开始", "round"),
            Diagram.FNode("a", "输入密码"),
            Diagram.FNode("c", "密码正确？", "diamond"),
            Diagram.FNode("ok", "进入首页"),
            Diagram.FNode("e", "结束", "round"),
        )
        val edges = listOf(
            Diagram.FEdge("s", "a"), Diagram.FEdge("a", "c"),
            Diagram.FEdge("c", "ok", "是"), Diagram.FEdge("c", "a", "否"), Diagram.FEdge("ok", "e"),
        )
        for (horizontal in listOf(false, true)) {
            val l = Diagram.flow(nodes, edges, horizontal, m)
            val by = l.boxes.associateBy { it.id }
            val order = listOf("s", "a", "c", "ok", "e").map { if (horizontal) by.getValue(it).x else by.getValue(it).y }
            assertEquals(order.sorted(), order)
            assertEquals(5, l.links.size)
            for (i in l.boxes.indices) for (j in i + 1 until l.boxes.size) assertTrue(!overlap(l.boxes[i], l.boxes[j]))
            l.links.flatMap { it.points }.forEach { (x, y) -> assertTrue(x <= l.width + 0.1f && y <= l.height + 0.1f) }
        }
    }

    @Test
    fun wrapLongText() {
        val lines = Diagram.wrap("这是一段非常非常长的中文文字需要折行显示", m, 130f)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { m.width(it) <= 130f })
        assertEquals("这是一段非常非常长的中文文字需要折行显示", lines.joinToString(""))
    }
}
