package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTest {
    private val now = 1_800_000_000_000L
    private val w = Routine(kind = "watch", title = "盯票", prompt = "p", hour = 0, minute = 0, everyMin = 30, until = now + 3_600_000L)

    @Test
    fun nextCheckIsOneIntervalAfterTheLast() {
        assertEquals(now, w.nextAt(now))
        assertEquals(now + 10 * 60_000L, w.copy(lastRunAt = now - 20 * 60_000L).nextAt(now))
    }

    @Test
    fun stopsWhenExpiredOrOff() {
        assertNull(w.nextAt(now + 3_600_000L))
        assertNull(w.copy(enabled = false).nextAt(now))
    }

    @Test
    fun describesIntervalAndDeadline() {
        assertTrue(w.describe(), w.describe().startsWith("每 30 分钟查一次，盯到 "))
        assertTrue(w.copy(everyMin = 120).describe(), w.copy(everyMin = 120).describe().startsWith("每 2 小时查一次"))
    }
}
