package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class AttachmentsEncodingTest {
    @Test
    fun decodesGbkText() {
        val text = "客户名单,金额\n张三,100"
        assertEquals(text, Attachments.decodeText(text.toByteArray(Charset.forName("GBK"))))
    }

    @Test
    fun decodesGb18030Text() {
        val text = "𠀀附件"
        assertEquals(text, Attachments.decodeText(text.toByteArray(Charset.forName("GB18030"))))
    }

    @Test
    fun decodesUtf8WithBom() {
        val text = "附件内容"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, Attachments.decodeText(bytes))
    }
}
