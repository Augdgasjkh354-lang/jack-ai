package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkLevelTest {
    @Test
    fun autoPicksByDifficulty() {
        val auto = ThinkLevel.AUTO
        assertEquals(ThinkLevel.LOW, auto.resolve("今天吃什么好", work = false, hasFiles = false))
        assertEquals(ThinkLevel.HIGH, auto.resolve("帮我算一下房贷每月还多少", work = false, hasFiles = false))
        assertEquals(ThinkLevel.HIGH, auto.resolve("这段代码为什么报错", work = false, hasFiles = false))
        assertEquals(ThinkLevel.HIGH, auto.resolve("你好", work = true, hasFiles = false))
        assertEquals(ThinkLevel.HIGH, auto.resolve("看看这个", work = false, hasFiles = true))
        // 手动选的档位不动
        assertEquals(ThinkLevel.OFF, ThinkLevel.OFF.resolve("写段代码", work = true, hasFiles = true))
    }

    @Test
    fun selfCheckTemplateIsAlwaysThere() {
        assert(DEFAULT_WORK_TEMPLATES.any { it.id == SELF_CHECK.id })
    }
}
