package com.jack.ai.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCheckTest {
    @Test fun chineseNumbers() {
        assertEquals("2024", SpeechCheck.cn("二零二四"))
        assertEquals("2024", SpeechCheck.cn("两千零二十四"))
        assertEquals("15", SpeechCheck.cn("十五"))
        assertEquals("105", SpeechCheck.cn("一百零五"))
        assertEquals("35000", SpeechCheck.cn("三万五千"))
        assertEquals("120000000", SpeechCheck.cn("一亿两千万"))
        assertEquals("3.5", SpeechCheck.cn("三点五"))
    }

    @Test fun sameWhenReadDifferently() {
        val r = SpeechCheck.check(
            listOf("2024 年销量涨了 30%！", "AI 帮你写文案"),
            listOf("二零二四年销量涨了百分之三十", "ai帮你写文案。"))
        assertTrue(r.toString(), r.isEmpty())
    }

    @Test fun flagsWrongNumberAndMissing() {
        val r = SpeechCheck.check(
            listOf("价格是 199 元", "今天我们来聊聊怎么做小红书封面", "空"),
            listOf("价格是一百九十元", "今天我们", null))
        assertEquals(2, r.size)
        assertTrue(r[0].numbers)
        assertEquals(1, r[1].index)
        assertTrue(SpeechCheck.format(r, 3).contains("数字不一样"))
    }

    @Test fun cleanReport() {
        assertTrue(SpeechCheck.format(emptyList(), 5).contains("都和文案对得上"))
    }
}
