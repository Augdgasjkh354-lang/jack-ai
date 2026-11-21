package com.jack.ai.ui

import com.jack.ai.data.design.DesignSeries
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesignResizeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun slotsAreCollected() {
        val html = """<div class="canvas"><h1 data-slot="title">a</h1><p data-slot='body'>b</p><span data-slot="title">c</span></div>"""
        assertEquals(setOf("title", "body"), DesignResize.slotsOf(html))
    }

    @Test fun singleGetsLabelAndAvoidsClash() {
        val dir = tmp.root
        val src = java.io.File(dir, "海报.html").apply { writeText("x") }
        assertEquals(listOf("海报-方图"), DesignResize.stems(listOf(src), "方图"))
        java.io.File(dir, "海报-方图.png").writeText("x")
        assertEquals(listOf("海报-方图(2)"), DesignResize.stems(listOf(src), "方图"))
    }

    @Test fun groupStaysAGroup() {
        val dir = tmp.root
        val srcs = (0..2).map { java.io.File(dir, DesignSeries.pageName("笔记", it) + ".html").apply { writeText("x") } }
        val stems = DesignResize.stems(srcs, "竖版海报")
        assertEquals(listOf("笔记-竖版海报-01", "笔记-竖版海报-02", "笔记-竖版海报-03"), stems)
        // 新的一组也认得出是一组（编辑器里能翻页）
        assertEquals("笔记-竖版海报", DesignSeries.baseOf(java.io.File(dir, stems[1] + ".html")))
        java.io.File(dir, "笔记-竖版海报-01.png").writeText("x")
        assertEquals("笔记-竖版海报2-01", DesignResize.stems(srcs, "竖版海报").first())
    }
}
