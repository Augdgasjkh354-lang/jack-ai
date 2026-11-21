package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import com.jack.ai.data.design.DesignSeries

class DesignSeriesTest {
    @Test
    fun findsWholeSeriesInOrder() {
        val dir = Files.createTempDirectory("series").toFile()
        listOf("租房-02.html", "租房-01.html", "租房-03.html", "租房-01.png", "租房封面.html", "别的-01.html").forEach { java.io.File(dir, it).writeText("x") }
        assertEquals(listOf("租房-01.html", "租房-02.html", "租房-03.html"), DesignSeries.pagesOf(java.io.File(dir, "租房-02.html")).map { it.name })
        assertEquals(listOf("租房封面.html"), DesignSeries.pagesOf(java.io.File(dir, "租房封面.html")).map { it.name })
        assertEquals("租房", DesignSeries.baseOf(java.io.File(dir, "租房-03.html")))
        dir.deleteRecursively()
    }

    @Test
    fun pageNumbersAndThemeAreRewritten() {
        val html = """<html><body><div class="canvas" style="--bg: #fff; translate: 1px 2px"><span class="page" data-slot="page" data-max="5">02/06</span><span data-slot="page">第二页</span></div></body></html>"""
        val renumbered = DesignSeries.withPageNumber(html, 3, 5)
        assertTrue(renumbered, ">04/05<" in renumbered && "第二页" in renumbered)
        val themed = DesignSeries.withCanvasVars(html, mapOf("bg" to "#111111", "font-title" to "\"Smiley Sans\",sans-serif", "bad;key" to "x"))
        // 同名变量换掉，别的样式留着；字体名的双引号换成单引号，不会把 style 属性截断
        assertTrue(themed, """style="translate: 1px 2px; --bg: #111111; --font-title: 'Smiley Sans',sans-serif"""" in themed)
        val bare = DesignSeries.withCanvasVars("""<div class="x canvas">a</div>""", mapOf("primary" to "#E23B2E"))
        assertEquals("""<div class="x canvas" style="--primary: #E23B2E">a</div>""", bare)
    }
}
