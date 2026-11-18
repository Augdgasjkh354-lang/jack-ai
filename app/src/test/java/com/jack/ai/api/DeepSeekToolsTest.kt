package com.jack.ai.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekToolsTest {
    @Test fun ttsCharsCountsChineseAsTwo() {
        assertEquals(2 * 2 + 3, DeepSeekTools.ttsChars("你好abc"))
    }

    @Test fun ttsChunksStayUnderLimitAndKeepAllText() {
        val text = "今天天气很好。".repeat(50) + "abc def ".repeat(40)
        val chunks = DeepSeekTools.ttsChunks(text, max = 100)
        assertTrue(chunks.all { DeepSeekTools.ttsChars(it) <= 100 })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        // 能在句号处断就在句号处断
        assertTrue(chunks.first().endsWith("。"))
    }

    @Test fun shortTextIsOneChunk() {
        assertEquals(listOf("你好"), DeepSeekTools.ttsChunks("  你好 "))
    }

    @Test fun cleanOcrDropsBoxesAndTags() {
        val raw = "<｜ref｜>标题<｜/ref｜><｜box｜>[[52, 40, 948, 96]]<｜/box｜>\n正文第一段\n\n\n\n第二段"
        assertEquals("标题\n正文第一段\n\n第二段", DeepSeekTools.cleanOcr(raw))
    }

    @Test fun deepseekKeyGivesTranscriberEngineWithShortChunks() {
        val e = Transcriber.engine(emptyList(), "sk-x")!!
        assertTrue(e.deepseek)
        assertTrue(e.chunkSec <= 120)
        assertEquals(null, Transcriber.engine(emptyList(), ""))
    }
}
