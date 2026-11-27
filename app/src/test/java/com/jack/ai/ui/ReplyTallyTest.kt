package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyTallyTest {
    @Test fun fullLine() = assertEquals("V4 Flash · 23 秒 · 3.4 万 tokens", replyTally("V4 Flash", 23_400, 34_210))

    @Test fun oldMessageShowsOnlyModel() = assertEquals("V4 Flash", replyTally("V4 Flash", 0, 0))

    @Test fun shortAndLong() {
        assertEquals("8,321 tokens", replyTally(null, 500, 8_321))
        assertEquals("2 分 5 秒 · 12 万 tokens", replyTally(null, 125_000, 120_000))
        assertEquals("1 小时 3 分", replyTally(null, 3_780_000, 0))
        assertEquals("", replyTally(null, 0, 0))
    }
}
