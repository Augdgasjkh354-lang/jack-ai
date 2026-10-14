package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class TextEditTest {
    @Test
    fun replaceOnlyTheMatchedPart() {
        val code = "fun a() {\n    return 1\n}\nfun b() {\n    return 2\n}\n"
        val (out, n) = TextEdit.replace(code, "return 2", "return 3")
        assertEquals(1, n)
        assertEquals(code.replace("return 2", "return 3"), out)
    }

    @Test
    fun ambiguousAndMissingAreRejected() {
        try { TextEdit.replace("aa bb aa", "aa", "cc"); fail() } catch (e: TextEdit.EditException) { assertTrue(e.message!!.contains("2 次")) }
        try { TextEdit.replace("hello", "world", "x"); fail() } catch (e: TextEdit.EditException) { }
        assertEquals("cc bb cc" to 2, TextEdit.replace("aa bb aa", "aa", "cc", all = true))
    }

    @Test
    fun docxEditAcrossRunsKeepsOtherText() {
        val xml = """<w:document><w:body>""" +
            """<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>今年营收</w:t></w:r><w:r><w:t xml:space="preserve">增长了 1</w:t></w:r><w:r><w:t>5%，表现不错。</w:t></w:r></w:p>""" +
            """<w:p><w:r><w:t>第二段 &amp; 不动</w:t></w:r></w:p>""" +
            """</w:body></w:document>"""
        val (out, n) = TextEdit.replaceInDocumentXml(xml, "增长了 15%", "增长了 18%", all = false)
        assertEquals(1, n)
        assertEquals("今年营收增长了 18%，表现不错。\n第二段 & 不动", TextEdit.docxText(out))
        assertTrue(out.contains("<w:rPr><w:b/></w:rPr><w:t>今年营收</w:t>"))
        assertTrue(out.contains("<w:t>第二段 &amp; 不动</w:t>"))
        // 单个字也能改
        val (out2, _) = TextEdit.replaceInDocumentXml(out, "不错", "很好", all = false)
        assertEquals("今年营收增长了 18%，表现很好。\n第二段 & 不动", TextEdit.docxText(out2))
    }

    @Test
    fun docxRoundTripWithGeneratedFile() {
        val buf = ByteArrayOutputStream()
        OfficeWriter.writeDocx("# 标题\n\n第一段文字，**加粗**的部分。\n\n第二段。", buf)
        val (edited, n) = TextEdit.replaceInDocx(buf.toByteArray(), "第二段", "改过的第二段")
        assertEquals(1, n)
        var xml = ""
        ZipInputStream(edited.inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == "word/document.xml") xml = z.readBytes().toString(Charsets.UTF_8) }
        }
        val text = TextEdit.docxText(xml)
        assertTrue(text, text.contains("改过的第二段。"))
        assertTrue(text, text.contains("第一段文字，加粗的部分。"))
    }
}
