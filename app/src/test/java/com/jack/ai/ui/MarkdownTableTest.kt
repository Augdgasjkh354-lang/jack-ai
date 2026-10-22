package com.jack.ai.ui

import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回答里的表格单独画：认得出表格、和文字拆得开、列宽估得合理。 */
class MarkdownTableTest {
    @Test
    fun parsesHeaderAlignAndRows() {
        val t = parseTable("| 模型 | 输入价 | 说明 |\n|:---|---:|:---:|\n| Flash | 1 元 | 快 |\n| Pro | 4 元 |")!!
        assertEquals(listOf("模型", "输入价", "说明"), t.header)
        assertEquals(listOf(TextAlign.Start, TextAlign.End, TextAlign.Center), t.align)
        // 少一格的行补空，不会错位
        assertEquals(listOf(listOf("Flash", "1 元", "快"), listOf("Pro", "4 元", "")), t.rows)
    }

    @Test
    fun withoutOuterPipesAndEscapedPipe() {
        val t = parseTable("a | b\n--- | ---\nx \\| y | z")!!
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf("x | y", "z"), t.rows.single())
    }

    @Test
    fun notATable() {
        assertNull(parseTable("就是一段话 | 里面有竖线"))
        // 流式输出时只写出了表头，还不是表格，先当文字
        assertNull(parseTable("| a | b |"))
    }

    @Test
    fun tableGluedToTextIsSplitOut() {
        val parts = splitTables("对比如下：\n| a | b |\n|---|---|\n| 1 | 2 |\n以上。")
        assertEquals(listOf("对比如下：", "| a | b |\n|---|---|\n| 1 | 2 |", "以上。"), parts)
        assertEquals(listOf("普通段落"), splitTables("普通段落"))
    }

    @Test
    fun columnWidthFollowsContentWithinBounds() {
        assertEquals(52f, columnWidth(listOf("a")))
        assertEquals(220f, columnWidth(listOf("很长很长很长很长很长很长很长很长很长很长很长很长很长很长")))
        assertTrue(columnWidth(listOf("中文四字")) > columnWidth(listOf("abcd")))
    }

    @Test
    fun columnsFitTheScreenBeforeScrolling() {
        // 窄表铺满
        assertEquals(300f, fitColumns(listOf(100f, 50f), 300f).sum(), 0.01f)
        // 宽表先压宽列，刚好放下
        val fit = fitColumns(listOf(80f, 64f, 64f, 150f, 150f), 440f)
        assertEquals(440f, fit.sum(), 0.01f)
        assertEquals(64f, fit[1], 0.01f)
        // 压到底也放不下：保持最窄，横向滑动
        assertEquals(listOf(84f, 84f, 84f, 84f), fitColumns(listOf(200f, 200f, 200f, 200f), 300f))
    }
}
