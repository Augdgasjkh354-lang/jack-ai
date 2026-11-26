package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuoteReplyTest {
    @Test fun noQuoteKeepsText() {
        assertEquals("你好", withQuote(null, "你好"))
        assertEquals("你好", withQuote("  ", "你好"))
    }

    @Test fun quotePrefixesEachLine() {
        assertEquals("> 第一行\n>\n> 第三行\n\n这是什么意思", withQuote("第一行\n\n第三行", " 这是什么意思 "))
    }

    @Test fun quoteOnly() {
        assertEquals("> 一句话", withQuote("一句话", ""))
    }

    @Test fun longQuoteIsCut() {
        val out = withQuote("字".repeat(QUOTE_MAX + 50), "问")
        assertEquals(QUOTE_MAX + "> ".length + 1 + "\n\n问".length, out.length)
    }

    @Test fun roundTrip() {
        val (q, body) = splitQuote(withQuote("第一行\n\n第三行", "这是什么意思"))
        assertEquals("第一行\n\n第三行", q)
        assertEquals("这是什么意思", body)
    }

    @Test fun splitWithoutQuote() {
        val (q, body) = splitQuote("普通消息\n> 中间的不算")
        assertNull(q)
        assertEquals("普通消息\n> 中间的不算", body)
    }

    @Test fun splitQuoteOnly() {
        assertEquals("一句话" to "", splitQuote("> 一句话"))
    }
}
