package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class QuickTimesTest {
    private fun at(h: Int, m: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 26, h, m, 30); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun cal(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }

    @Test fun morningOffersTonight() {
        val list = quickTimes(at(10, 15)).toMap()
        assertEquals(listOf("10 分钟后", "1 小时后", "今晚 8 点", "明早 9 点"), quickTimes(at(10, 15)).map { it.first })
        val ten = cal(list.getValue("10 分钟后"))
        assertEquals(10, ten.get(Calendar.HOUR_OF_DAY)); assertEquals(25, ten.get(Calendar.MINUTE)); assertEquals(0, ten.get(Calendar.SECOND))
        val tonight = cal(list.getValue("今晚 8 点"))
        assertEquals(26, tonight.get(Calendar.DAY_OF_MONTH)); assertEquals(20, tonight.get(Calendar.HOUR_OF_DAY))
        val tomorrow = cal(list.getValue("明早 9 点"))
        assertEquals(27, tomorrow.get(Calendar.DAY_OF_MONTH)); assertEquals(9, tomorrow.get(Calendar.HOUR_OF_DAY)); assertEquals(0, tomorrow.get(Calendar.MINUTE))
    }

    @Test fun eveningDropsTonightAndAllAreFuture() {
        val now = at(19, 40)
        val list = quickTimes(now)
        assertFalse(list.any { it.first == "今晚 8 点" })
        assertTrue(list.all { it.second > now })
    }
}
