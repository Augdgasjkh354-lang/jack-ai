package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class WorkflowWhenTextTest {
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance().apply { set(y, m - 1, d, h, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private val now = at(2026, 11, 26, 23, 30)

    @Test fun nearDaysInWords() {
        assertEquals("昨天 08:05", whenText(at(2026, 11, 25, 8, 5), now))
        assertEquals("今天 00:10", whenText(at(2026, 11, 26, 0, 10), now))
        assertEquals("明天 08:00", whenText(at(2026, 11, 27, 8, 0), now))
        assertEquals("后天 21:00", whenText(at(2026, 11, 28, 21, 0), now))
    }

    @Test fun otherDaysShowDate() {
        assertEquals("12月5日 09:00", whenText(at(2026, 12, 5, 9, 0), now))
        assertEquals("2025年3月1日 09:00", whenText(at(2025, 3, 1, 9, 0), now))
    }
}
