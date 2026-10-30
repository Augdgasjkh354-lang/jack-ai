package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** 新版 Word：封面、目录、标题编号、页眉页脚、表格；每个部件 XML 合法，旧的读文字、改文字照样能用。 */
class WordDocTest {
    private val md = """# 2026 年度产品规划
这份规划回答三个问题：我们在哪、要去哪、怎么去。

## 市场回顾
今年行业整体增速放缓，但**细分赛道**仍有机会。

### 行业数据
| 指标 | 2025 | 2026 |
|---|---|---|
| 市场规模（亿元） | 1,200 | 1,350 |
| 增速 | 12% | 9% |
| 头部份额 | 35% | 41% |

### 竞争格局
- 头部三家占据四成份额
- 新进入者集中在 AI 方向
  - 多数还在融资早期

## 核心目标
1. 营收增长 30%
2. 用户突破 2000 万

> 注意：目标以年底审计数据为准。

## 实施路径
分三个阶段推进，每季度复盘一次。

## 资源需求
需要新增 12 人，预算 800 万元。

```
budget = 800
```
"""

    private fun parts(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return out
    }

    private fun write(o: WordDoc.Options, text: String = md): Map<String, String> {
        val bos = ByteArrayOutputStream()
        OfficeWriter.writeDocx(text, bos, o)
        return parts(bos.toByteArray())
    }

    @Test
    fun fullReportHasCoverTocNumberingHeaderFooterAndTables() {
        val o = WordDoc.Options(theme = "teal", cover = WordDoc.Cover("2026 年度产品规划", "产品部 · 内部讨论稿", "Jack", "产品部", "2026 年 10 月"),
            toc = true, headingNumbers = "chinese", indent = true, fontStyle = "classic")
        val bos = ByteArrayOutputStream()
        OfficeWriter.writeDocx(md, bos, o)
        File(System.getProperty("user.dir"), "build/office-samples").apply { mkdirs() }.resolve("report.docx").writeBytes(bos.toByteArray())
        File(System.getProperty("user.dir"), "build/office-samples/simple.docx").outputStream().use { OfficeWriter.writeDocx(md, it) }
        val p = parts(bos.toByteArray())
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        listOf("word/document.xml", "word/styles.xml", "word/settings.xml", "word/header1.xml", "word/footer1.xml", "docProps/core.xml", "[Content_Types].xml")
            .forEach { f.newDocumentBuilder().parse(p.getValue(it).byteInputStream()) }
        val doc = p.getValue("word/document.xml")
        // 封面：副标题、作者；文档标题不在正文里重复
        assertTrue(doc.contains("产品部 · 内部讨论稿"))
        assertEquals(1, Regex(">2026 年度产品规划<").findAll(doc).count())
        // 目录：域代码、每个标题一条、点了能跳
        assertTrue(doc.contains("TOC \\o \"1-3\" \\h \\z \\u"))
        assertTrue(doc.contains("<w:hyperlink w:anchor=\"_Toc1\""))
        assertTrue(p.getValue("word/settings.xml").contains("updateFields"))
        // 中文编号
        assertTrue(doc.contains(">一、市场回顾<"))
        assertTrue(doc.contains(">（一）行业数据<"))
        assertTrue(doc.contains(">四、资源需求<"))
        // 两节：封面目录一节没有页眉页脚，正文一节有，页码从 1 起
        assertEquals(2, Regex("<w:sectPr>").findAll(doc).count())
        assertTrue(doc.contains("<w:headerReference w:type=\"default\" r:id=\"rId10\"/><w:footerReference w:type=\"default\" r:id=\"rId11\"/>"))
        assertTrue(doc.contains("<w:pgNumType w:start=\"1\"/>"))
        assertTrue(p.getValue("word/footer1.xml").contains("SECTIONPAGES"))
        // 表格：表头主色白字、表头重复、数字靠右
        assertTrue(doc.contains("w:fill=\"0F766E\""))
        assertTrue(doc.contains("<w:tblHeader/>"))
        assertTrue(doc.contains("<w:jc w:val=\"right\"/></w:pPr><w:r><w:rPr></w:rPr><w:t xml:space=\"preserve\">1,200"))
        // 正式文件字体、首行缩进
        assertTrue(p.getValue("word/styles.xml").contains("w:eastAsia=\"宋体\""))
        assertTrue(doc.contains("<w:pStyle w:val=\"BodyIndent\"/>"))
        // 旧功能：读出文字、按文字替换
        val text = TextEdit.docxText(doc)
        assertTrue(text.contains("细分赛道"))
        val (changed, n) = TextEdit.replaceInDocx(bos.toByteArray(), "800 万元", "900 万元", false)
        assertEquals(1, n)
        assertTrue(parts(changed).getValue("word/document.xml").contains("900 万元"))
    }

    @Test
    fun shortDocsStayPlain() {
        val p = write(WordDoc.Options(header = ""), "# 请假条\n因家中有事，申请请假一天。\n")
        val doc = p.getValue("word/document.xml")
        assertFalse(doc.contains("TOC \\o"))
        assertFalse(doc.contains("headerReference"))
        assertTrue(doc.contains("footerReference"))
        assertTrue(doc.contains("<w:pStyle w:val=\"Title\"/>"))
        assertEquals(1, Regex("<w:sectPr>").findAll(doc).count())
        assertFalse(p.getValue("word/settings.xml").contains("updateFields"))
    }

    @Test
    fun tocTurnsOnByItselfForLongDocsAndDecimalNumbering() {
        val doc = write(WordDoc.Options(headingNumbers = "decimal")).getValue("word/document.xml")
        assertTrue(doc.contains("TOC \\o"))  // 四个章节，自动加目录；没要封面就不加封面，标题在目录页顶上
        assertFalse(doc.contains("CoverTitle"))
        assertTrue(doc.indexOf("<w:pStyle w:val=\"Title\"/>") < doc.indexOf("TOC \\o"))
        assertTrue(doc.contains(">1 市场回顾<"))
        assertTrue(doc.contains(">1.2 竞争格局<"))
        assertTrue(doc.contains(">4 资源需求<"))
        assertEquals("十二", WordDoc.chineseNumber(12))
        assertEquals("二十", WordDoc.chineseNumber(20))
    }
}
