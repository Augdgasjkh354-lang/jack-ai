package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkLevelTest {
    @Test
    fun oldAutoSettingBecomesHigh() {
        assertEquals(ThinkLevel.HIGH, ThinkLevel.parse("AUTO"))
        assertEquals(ThinkLevel.LOW, ThinkLevel.parse("LOW"))
        assertEquals(ThinkLevel.OFF, ThinkLevel.parse("OFF"))
        assertNull(ThinkLevel.parse("NOPE"))
        assertNull(ThinkLevel.parse(null))
    }

    @Test
    fun selfCheckTemplateIsAlwaysThere() {
        assert(DEFAULT_WORK_TEMPLATES.any { it.id == SELF_CHECK.id })
    }
}
