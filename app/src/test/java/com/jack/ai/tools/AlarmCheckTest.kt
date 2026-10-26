package com.jack.ai.tools

import com.jack.ai.data.Verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AlarmCheckTest {
    /** 2026-10-26 是周一。 */
    private fun at(day: Int, h: Int, m: Int) = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, day, h, m, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test
    fun nextRingTime() {
        val now = at(26, 10, 0)
        assertEquals(at(26, 18, 30), nextAlarmAt(now, 18, 30, emptyList()))
        // 已经过了：明天
        assertEquals(at(27, 7, 0), nextAlarmAt(now, 7, 0, emptyList()))
        // 只在周三、周五：下一个是周三
        assertEquals(at(28, 7, 0), nextAlarmAt(now, 7, 0, listOf(3, 5)))
        // 周一今天已过：下周一
        assertEquals(at(26, 0, 0) + 7 * 86_400_000L + 9 * 3_600_000L, nextAlarmAt(now, 9, 0, listOf(1)))
    }

    @Test
    fun readsBack() {
        val want = at(27, 7, 0)
        assertFalse(Verify.failed(alarmCheck(want, want)))
        assertTrue(alarmCheck(want, want).contains("设上了"))
        // 有更早的闹钟：没法确认，但不算失败
        val earlier = alarmCheck(want, at(26, 22, 0))
        assertFalse(Verify.failed(earlier)); assertTrue(earlier.contains("没法直接确认"))
        assertTrue(Verify.failed(alarmCheck(want, null)))
        assertTrue(Verify.failed(alarmCheck(want, at(28, 7, 0))))
    }
}
