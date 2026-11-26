package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTaskNameTest {
    @Test fun takesFirstClause() {
        assertEquals("搜索过去 24 小时的 AI 新闻", autoTaskName("搜索过去 24 小时的 AI 新闻，整理成 5 条简报"))
    }

    @Test fun firstLineOnly() {
        assertEquals("看天气", autoTaskName("  看天气\n然后发给我"))
    }

    @Test fun capsLength() {
        assertEquals("一二三四五六七八九十一二三四五六七八九十…", autoTaskName("一二三四五六七八九十一二三四五六七八九十一二"))
    }

    @Test fun blankStaysBlank() {
        assertEquals("", autoTaskName("   "))
    }
}
