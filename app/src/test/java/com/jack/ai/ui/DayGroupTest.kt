package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class DayGroupTest {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private fun t(s: String) = fmt.parse(s)!!.time

    @Test
    fun groupsByCalendarDay() {
        val now = t("2026-10-21 09:00")
        assertEquals("今天", dayGroup(t("2026-10-21 00:05"), now))
        // 昨晚 23 点离现在不到 24 小时，也算「昨天」
        assertEquals("昨天", dayGroup(t("2026-10-20 23:00"), now))
        assertEquals("7 天内", dayGroup(t("2026-10-15 12:00"), now))
        assertEquals("30 天内", dayGroup(t("2026-10-01 12:00"), now))
        assertEquals("2026年8月", dayGroup(t("2026-08-03 12:00"), now))
    }
}
