package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextPreviewTest {
    @Test
    fun knowsWhichFilesAreText() {
        assertTrue(TextPreview.isText("笔记.MD"))
        assertTrue(TextPreview.isText("data.json") && TextPreview.isCode("data.json"))
        assertTrue(TextPreview.isText("a.txt") && !TextPreview.isCode("a.txt"))
        assertTrue(TextPreview.isMarkdown("README.markdown"))
        assertFalse(TextPreview.isText("报告.docx"))
        assertFalse(TextPreview.isText("没有扩展名"))
    }

    @Test
    fun decodesUtf8WithBomAndFallsBackToGbk() {
        assertEquals("你好", TextPreview.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好".toByteArray()))
        // Windows 记事本存的中文常是 GBK
        assertEquals("客户名单", TextPreview.decode("客户名单".toByteArray(charset("GBK"))))
    }

    @Test
    fun truncatedUtf8DropsTheHalfCharacter() {
        val bytes = "价格表".toByteArray()
        val cut = bytes.copyOfRange(0, bytes.size - 1)
        assertEquals("价格", TextPreview.decode(cut, truncated = true))
    }

    @Test
    fun readsOnlyTheStartOfHugeFiles() {
        val f = File.createTempFile("big", ".txt")
        try {
            f.writeText("a".repeat(TextPreview.MAX_BYTES + 10))
            val (text, cut) = TextPreview.read(f)
            assertTrue(cut)
            assertEquals(TextPreview.MAX_BYTES, text.length)
            f.writeText("小文件")
            assertEquals("小文件" to false, TextPreview.read(f))
        } finally { f.delete() }
    }

    @Test
    fun jsonIsIndentedOnlyWhenValid() {
        assertEquals("{\n  \"a\": 1\n}", TextPreview.prettyJson("""{"a":1}"""))
        assertEquals("not json", TextPreview.prettyJson("not json"))
    }

    @Test
    fun chunksSplitOnLinesAndKeepEverything() {
        val text = (1..3000).joinToString("\n") { "第 $it 行" }
        val parts = TextPreview.chunks(text, 4000)
        assertTrue(parts.size > 1)
        assertEquals(text, parts.joinToString("\n"))
        assertTrue(parts.all { it.length <= 4000 })
        assertEquals(listOf("短"), TextPreview.chunks("短"))
    }
}
