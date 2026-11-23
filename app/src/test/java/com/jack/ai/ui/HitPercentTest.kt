package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HitPercentTest {
    @Test fun twoDecimalsNoTruncation() {
        assertEquals("99.90%", hitPercent(999, 1000))
        assertEquals("71.43%", hitPercent(5, 7))
        assertEquals("0.00%", hitPercent(0, 36500))
        assertEquals("0.00%", hitPercent(5, 0))
        assertEquals("100.00%", hitPercent(52000, 52000))
    }
}
