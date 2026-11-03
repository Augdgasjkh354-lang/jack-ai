package com.jack.ai.data

import org.junit.Assert.assertNull
import org.junit.Ignore
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class RoutineRecoveryAuditTest {
    @Ignore("audit: 复现")
    @Test
    fun oneShotTaskMissedWhilePhoneOffHasNoCatchUpTime() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 3, 11, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val date = String.format(
            Locale.US,
            "%04d-%02d-%02d",
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH),
        )
        val task = Routine(
            kind = "task",
            title = "一次性任务",
            prompt = "执行一次",
            hour = 10,
            minute = 0,
            repeat = "none",
            date = date,
        )

        assertNull(task.nextAt(now.timeInMillis))
    }
}
