package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Word、Excel、PPT 转网页：拿 App 自己生成的文件转一遍，标题、列表、表格、每张表、每页都要在，特殊字符要转义。 */
class OfficeHtmlTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun docxKeepsHeadingsListsAndTables() {
        val f = tmp.newFile("t.docx")
        f.outputStream().use { OfficeWriter.writeDocx("# 季度总结\n开头，**加粗**，还有 <特殊> & 字符。\n\n- 第一点\n- 第二点\n\n| 项目 | 金额 |\n|---|---|\n| 房租 | 3000 |\n", it) }
        val html = OfficeHtml.body(f)
        assertTrue(html, Regex("<h1[^>]*>季度总结</h1>").containsMatchIn(html))
        assertTrue(html, html.contains("<b>加粗</b>"))
        assertTrue(html, html.contains("&lt;特殊&gt; &amp; 字符"))
        assertTrue(html, html.contains("第一点") && html.contains("第二点"))
        assertTrue(html, html.contains("<table>") && html.contains("房租") && html.contains("3000"))
        assertFalse(html.contains("<特殊>"))
    }

    @Test
    fun xlsxShowsEverySheetAsATable() {
        val f = tmp.newFile("t.xlsx")
        f.outputStream().use { OfficeWriter.writeXlsx(listOf(
            OfficeWriter.Sheet("开销", listOf(listOf("项目", "金额"), listOf("房租", "3000"))),
            OfficeWriter.Sheet("收入", listOf(listOf("工资", "8000"))),
        ), it) }
        val html = OfficeHtml.body(f)
        assertTrue(html, html.contains("<div class=sheet>开销</div>") && html.contains("<div class=sheet>收入</div>"))
        assertTrue(html, html.contains("<td>房租</td>") && html.contains("<td>3000</td>") && html.contains("<td>8000</td>"))
    }

    @Test
    fun pptxShowsEverySlideInOrder() {
        val f = tmp.newFile("t.pptx")
        f.outputStream().use { OfficeWriter.writePptx(listOf(
            OfficeWriter.Slide("title", "读书分享", listOf("汇报人 Jack")),
            OfficeWriter.Slide("content", "增长 32%", listOf("要点一", "要点 <三> & 四")),
        ), "", it) }
        val html = OfficeHtml.body(f)
        assertTrue(html, html.indexOf("第 1 页") < html.indexOf("第 2 页"))
        assertTrue(html, html.contains("读书分享") && html.contains("增长 32%"))
        assertTrue(html, html.contains("要点 &lt;三&gt; &amp; 四"))
    }

    @Test
    fun columnsAndPaths() {
        assertEquals(0, OfficeHtml.column("A1"))
        assertEquals(27, OfficeHtml.column("AB12"))
        assertEquals("AB", OfficeHtml.columnName(27))
        assertEquals("word/media/image1.png", OfficeHtml.resolve("word", "media/image1.png"))
        assertEquals("ppt/media/a.png", OfficeHtml.resolve("ppt/slides", "../media/a.png"))
        assertEquals("xl/worksheets/sheet1.xml", OfficeHtml.resolve("xl", "/xl/worksheets/sheet1.xml"))
    }
}
