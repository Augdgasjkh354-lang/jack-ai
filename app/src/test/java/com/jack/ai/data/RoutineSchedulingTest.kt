package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RoutineSchedulingTest {
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance().apply { set(y, m - 1, d, h, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    // 2026-11-26 是周四
    private val daily = Routine(kind = "task", title = "早报", hour = 8, minute = 0, repeat = "daily", date = "2026-11-01")

    @Test fun repeatingTaskMissedTodayIsCaughtUpOnce() {
        val now = at(2026, 11, 26, 10, 0)
        val ranYesterday = daily.copy(lastRunAt = at(2026, 11, 25, 8, 0))
        assertEquals(at(2026, 11, 26, 8, 0), ranYesterday.prevAt(now))
        assertTrue(ranYesterday.missedRepeat(now))
        // 今天这次已经跑过
        assertFalse(ranYesterday.copy(lastRunAt = at(2026, 11, 26, 8, 0)).missedRepeat(now))
        // 刚建的、从没跑过：不把今天已经过去的那次补出来
        assertFalse(daily.missedRepeat(now))
        // 过了 12 小时就不补了，等下一次
        assertFalse(ranYesterday.missedRepeat(at(2026, 11, 26, 21, 0)))
        // 关着的不补
        assertFalse(ranYesterday.copy(enabled = false).missedRepeat(now))
    }

    @Test fun weekdaysAndRemindersCatchUpWindow() {
        val sat = at(2026, 11, 28, 10, 0)
        val wd = daily.copy(repeat = "weekdays", lastRunAt = at(2026, 11, 26, 8, 0))
        // 周六往回找到的是周五 8 点，周五没跑过，但已经超过 12 小时
        assertEquals(at(2026, 11, 27, 8, 0), wd.prevAt(sat))
        assertFalse(wd.missedRepeat(sat))
        assertTrue(wd.missedRepeat(at(2026, 11, 27, 9, 0)))
        val remind = daily.copy(kind = "reminder", lastRunAt = at(2026, 11, 25, 8, 0))
        assertTrue(remind.missedRepeat(at(2026, 11, 26, 9, 30)))
        assertFalse(remind.missedRepeat(at(2026, 11, 26, 11, 0)))
        // 一次性的和监控不走这条
        assertFalse(daily.copy(repeat = "none", lastRunAt = 1).missedRepeat(at(2026, 11, 26, 9, 0)))
    }

    @Test fun failStreakSkipsRetriesAndStopsAtSuccessOrPause() {
        fun run(status: String, retry: Boolean = false) = RunRecord(routineId = "r", title = "t", kind = "task", status = status, retry = retry, finishedAt = 1)
        assertEquals(3, Routines.failStreak(listOf(run("failed"), run("failed", retry = true), run("failed"), run("failed"), run("ok"), run("failed"))))
        assertEquals(1, Routines.failStreak(listOf(run("failed"), run("paused"), run("failed"), run("failed"))))
        assertEquals(0, Routines.failStreak(listOf(run("ok"), run("failed"))))
    }

    private val watch = Routine(kind = "watch", title = "降价", prompt = "看价格", hour = 0, minute = 0, everyMin = 30, until = Long.MAX_VALUE, keep = true)

    @Test fun keepWatchingNotifiesOnBecomingMetOnly() {
        var r = watch
        var d = r.afterCheck(false, "¥320", 1_000)
        assertFalse(d.fire); assertEquals("not_met", d.status); assertEquals("¥320", d.next.lastValue); r = d.next
        d = r.afterCheck(true, "¥280", 2_000)
        assertTrue(d.fire); assertEquals(1, d.next.fired); assertEquals(2_000, d.next.lastFiredAt); r = d.next
        // 还满足着：不再提醒
        d = r.afterCheck(true, "¥279", 3_000)
        assertFalse(d.fire); assertEquals("unchanged", d.status); r = d.next
        // 回到不满足再变满足：又提醒
        r = r.afterCheck(false, "¥310", 4_000).next
        d = r.afterCheck(true, "¥270", 5_000)
        assertTrue(d.fire); assertEquals(2, d.next.fired)
        // 没给值时留着上次的
        assertEquals("¥270", d.next.afterCheck(false, "", 6_000).next.lastValue)
    }

    @Test fun eachTimeMetWithCooldown() {
        var r = watch.copy(notifyEach = true, cooldownMin = 60)
        var d = r.afterCheck(true, "3 封新邮件", 0L + 1)
        assertTrue(d.fire); r = d.next
        d = r.afterCheck(true, "4 封", 30 * 60_000L)
        assertFalse(d.fire); assertEquals("cooldown", d.status); r = d.next
        d = r.afterCheck(true, "5 封", 61 * 60_000L)
        assertTrue(d.fire)
    }

    @Test fun keepValueLineIsParsedAndHidden() {
        val o = Routines.judgeWatch("【满足】\n现在 ¥279，比上次低\n链接 https://x\n${Routines.KEEP_MARK} ¥279", okCalls = 1, failedCalls = 0)
        assertEquals("ok", o.status)
        assertEquals("¥279", o.value)
        assertFalse(Routines.KEEP_MARK in o.text)
        assertTrue(o.text.startsWith("现在 ¥279"))
    }
}
