package com.jack.ai.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QuotaTest {
    @Test
    fun windowsStartOnFirstUseAndReset() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = Settings(quotaFiveHours = 1_000, quotaWeek = 5_000)
        val t0 = 1_760_000_000_000
        assertNull(Quota.blocked(ctx, s, t0))
        Quota.add(ctx, 600, t0)
        Quota.add(ctx, 500, t0 + 3600_000)
        val w = Quota.blocked(ctx, s, t0 + 3600_000)
        assertNotNull(w)
        assertEquals(t0 + Quota.FIVE_HOURS, w!!.resetAt)
        // 5 小时后恢复；周额度接着累计
        assertNull(Quota.blocked(ctx, s, t0 + Quota.FIVE_HOURS))
        Quota.add(ctx, 900, t0 + Quota.FIVE_HOURS + 1)
        val (five, week) = Quota.windows(ctx, s, t0 + Quota.FIVE_HOURS + 2)
        assertEquals(900L, five.used)
        assertEquals(2_000L, week.used)
        assertEquals(t0, week.start)
        // 限额 0 不拦
        assertNull(Quota.blocked(ctx, Settings(quotaFiveHours = 0, quotaWeek = 0), t0 + 3600_000))
    }
}
