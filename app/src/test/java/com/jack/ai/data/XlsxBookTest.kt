package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** 新版 Excel：数字格式认得对、合计行、条件格式、图表指着格子，XML 都合法。 */
class XlsxBookTest {
    private val rows = listOf(
        listOf("月份", "收入（元）", "支出（元）", "结余率", "日期", "编号", "状态"),
        listOf("1月", "12,000", "8,500", "29%", "2026-01-31", "007", "正常"),
        listOf("2月", "11,500", "9,800", "14.8%", "2026-02-28", "008", "超支"),
        listOf("3月", "13,200", "7,900", "40%", "2026-03-31", "009", "正常"),
        listOf("4月", "12,800", "12,900", "-1%", "2026-04-30", "010", "超支"),
        listOf("5月", "¥14,000.50", "=B6*0.6", "40%", "2026-05-31", "011", "正常"),
    )

    private fun parts(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        return out
    }

    @Test
    fun cellsAreParsedIntoRealNumbers() {
        assertEquals(1234.0, XlsxBook.parse("1,234").number)
        assertEquals("int", XlsxBook.parse("1,234").format)
        assertEquals(0.12, XlsxBook.parse("12%").number!!, 1e-9)
        assertEquals("pct1", XlsxBook.parse("14.8%").format)
        assertEquals("money", XlsxBook.parse("¥12.50").format)
        assertEquals(46309.0, XlsxBook.parse("2026-10-14").number)  // Excel 的日期序号
        assertEquals("007", XlsxBook.parse("007").text)              // 前导 0 留着
        assertEquals("B2*C2", XlsxBook.parse("=B2*C2").formula)
        assertNull(XlsxBook.parse("正常").number)
    }

    @Test
    fun styledSheetWithTotalsHighlightsAndCharts() {
        val sheet = OfficeWriter.Sheet("家庭账本", rows, totalRow = true,
            highlights = listOf(XlsxBook.Highlight("状态", "contains", "超支", "red"), XlsxBook.Highlight("收入（元）", "top", "2", "green"),
                XlsxBook.Highlight("支出（元）", "databar"), XlsxBook.Highlight("不存在的列", ">", "1")),
            charts = listOf(XlsxBook.SheetChart("column", "收支对比", "月份", listOf("收入（元）", "支出（元）"), "元"),
                XlsxBook.SheetChart("line", "结余率", "月份", listOf("结余率"))))
        val bos = ByteArrayOutputStream()
        val warnings = OfficeWriter.writeXlsx(listOf(sheet, OfficeWriter.Sheet("说明", listOf(listOf("备注"), listOf("这是第二张表")))), bos, "teal")
        File(System.getProperty("user.dir"), "build/office-samples").apply { mkdirs() }.resolve("styled.xlsx").writeBytes(bos.toByteArray())
        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings[0].contains("不存在的列"))
        val p = parts(bos.toByteArray())
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        p.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (_, v) -> f.newDocumentBuilder().parse(v.byteInputStream()) }
        val s1 = p.getValue("xl/worksheets/sheet1.xml")
        // 合计行：第 7 行，数字列求和，结余率（百分比）、日期不求和
        assertTrue(s1.contains("<f>SUM(B2:B6)</f>") && s1.contains("<f>SUM(C2:C6)</f>"))
        assertTrue(!s1.contains("SUM(D2:D6)") && !s1.contains("SUM(E2:E6)"))
        assertTrue(s1.contains("<autoFilter ref=\"A1:G6\"/>"))
        assertTrue(s1.contains("type=\"containsText\"") && s1.contains("type=\"top10\"") && s1.contains("type=\"dataBar\""))
        assertTrue(s1.contains("<drawing r:id=\"rId1\"/>"))
        // 图表指着格子，不内嵌数据表
        val c1 = p.getValue("xl/charts/chart1.xml")
        assertTrue(c1.contains("<c:f>'家庭账本'!\$B\$2:\$B\$6</c:f>"))
        assertTrue(c1.contains("<c:f>'家庭账本'!\$A\$2:\$A\$6</c:f>"))
        assertTrue(!c1.contains("externalData"))
        assertTrue(c1.contains(">收支对比<"))
        assertTrue(p.getValue("xl/workbook.xml").contains("fullCalcOnLoad"))
        assertTrue(p.getValue("[Content_Types].xml").contains("/xl/drawings/drawing1.xml"))
        assertTrue(p.getValue("xl/styles.xml").contains("FF0F766E"))
    }
}
