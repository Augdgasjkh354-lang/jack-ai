package com.jack.ai.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkSummaryTest {
    @Test fun cleansHeading() {
        assertEquals("核对两家的报价", ThinkSummary.clean("「核对两家的报价。」\n多余的", emptyList()))
        assertEquals("Checking the path", ThinkSummary.clean("1. \"Checking the path.\"", emptyList()))
        assertNull(ThinkSummary.clean("核对报价", listOf("核对报价")))
        assertNull(ThinkSummary.clean("  \n ", emptyList()))
    }

    @Test fun dueWhenEnoughOrAnswerStarted() {
        assertTrue(ThinkSummary.due(ThinkSummary.CHUNK, answering = false))
        assertFalse(ThinkSummary.due(100, answering = false))
        assertTrue(ThinkSummary.due(100, answering = true))
        assertFalse(ThinkSummary.due(20, answering = true))
    }
}
