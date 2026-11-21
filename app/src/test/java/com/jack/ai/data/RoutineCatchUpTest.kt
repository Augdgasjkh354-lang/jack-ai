package com.jack.ai.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 一次性提醒在关机期间到点：24 小时以内补跑一次，跑过的、太久的、重复的不补。 */
class RoutineCatchUpTest {
    private fun oneShot(minutesAgo: Int, lastRunAt: Long = 0, enabled: Boolean = true, repeat: String = "none"): Pair<Routine, Long> {
        val due = Calendar.getInstance().apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MINUTE, -minutesAgo) }
        val r = Routine(title = "交电费", hour = due.get(Calendar.HOUR_OF_DAY), minute = due.get(Calendar.MINUTE), repeat = repeat,
            date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(due.time), enabled = enabled, lastRunAt = lastRunAt)
        return r to due.timeInMillis
    }

    @Test
    fun missedOneShotIsCaughtUpOnce() {
        val (r, due) = oneShot(60)
        assertTrue(r.missed())
        // 已经跑过（到点那一刻或之后）就不再补
        assertFalse(r.copy(lastRunAt = due + 1000).missed())
        // 跑完一次性的会被关掉
        assertFalse(r.copy(enabled = false).missed())
    }

    @Test
    fun tooOldOrRepeatingIsNotCaughtUp() {
        assertFalse(oneShot(26 * 60).first.missed())
        assertFalse(oneShot(60, repeat = "daily").first.missed())
        // 还没到点的不算错过
        assertFalse(oneShot(-30).first.missed())
    }
}
