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

    @Test fun autoKeepsOneLevelInLongConversationsSoTheCacheSurvives() {
        val a = ThinkLevel.AUTO
        // 对话还短：照这条消息选
        assertEquals(ThinkLevel.HIGH to false, a.resolveIn("帮我分析一下", false, false, ThinkLevel.LOW, 3_000))
        assertEquals(ThinkLevel.LOW to false, a.resolveIn("好的", false, false, ThinkLevel.HIGH, 3_000))
        // 长了：用过高档就一直高档，不降回去
        assertEquals(ThinkLevel.HIGH to false, a.resolveIn("好的", false, false, ThinkLevel.HIGH, 30_000))
        // 中等长度：低档升一次高档
        assertEquals(ThinkLevel.HIGH to false, a.resolveIn("帮我分析一下", false, false, ThinkLevel.LOW, 30_000))
        // 很长：不换档，末尾提醒多想
        assertEquals(ThinkLevel.LOW to true, a.resolveIn("帮我分析一下", false, false, ThinkLevel.LOW, 60_000))
        assertEquals(ThinkLevel.LOW to false, a.resolveIn("好的", false, false, ThinkLevel.LOW, 60_000))
        // 第一次（没记录）照常选；手动选的档不受影响
        assertEquals(ThinkLevel.HIGH to false, a.resolveIn("帮我分析一下", false, false, null, 60_000))
        assertEquals(ThinkLevel.LOW to false, ThinkLevel.LOW.resolveIn("帮我分析一下", false, false, ThinkLevel.HIGH, 60_000))
    }
}
