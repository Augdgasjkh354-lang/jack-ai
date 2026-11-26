package com.jack.ai.ui

import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class FriendlyDayTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 26, 23, 30, 0) }.timeInMillis

    @Test fun nearDaysInWords() {
        assertEquals("今天", friendlyDay("2026-11-26", now))
        assertEquals("明天", friendlyDay("2026-11-27", now))
        assertEquals("后天", friendlyDay("2026-11-28", now))
    }

    @Test fun laterDaysShowDateAndWeekday() {
        assertEquals("12月5日 周六", friendlyDay("2026-12-05", now))
        assertEquals("2027年1月4日 周一", friendlyDay("2027-01-04", now))
        assertEquals("乱写", friendlyDay("乱写", now))
    }

    @Test fun tableCopiesAsTabSeparated() {
        val t = Table(listOf("名字", "**价格**"), listOf(TextAlign.Start, TextAlign.End), listOf(listOf("苹果", "`3` 元"), listOf("梨<br>雪梨", "5")))
        assertEquals("名字\t价格\n苹果\t3 元\n梨 雪梨\t5", tableTsv(t))
    }
}
