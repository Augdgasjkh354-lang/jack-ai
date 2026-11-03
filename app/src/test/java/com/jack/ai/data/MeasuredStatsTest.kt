package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 操作其他 App 的成功率、Office 一次做对率的算法。 */
class MeasuredStatsTest {
    private val now = 1_000_000_000_000L
    private val old = now - 40L * 24 * 3600 * 1000  // 30 天前的不算

    @Test
    fun phoneRateByApp() {
        val ev = listOf("ok", "ok", "no_change", "ok").map { BrowserStats.Event(now - 1000, "微信", "tap", it) } +
            BrowserStats.Event(now - 1000, "淘宝", "type", "miss") +
            BrowserStats.Event(old, "抖音", "tap", "failed")
        val (all, byApp) = BrowserStats.rates(ev, now, PhoneStats.SUCCESS)
        assertEquals(5, all.total)
        assertEquals(60, all.percent)
        assertEquals(listOf("微信", "淘宝"), byApp.map { it.site })
        assertEquals("no_change", byApp[0].worst)
        val text = PhoneStats.summary(ev, now)
        assertTrue(text, "微信：4 步，成功 75%，最多的问题是做了但屏幕没变" in text)
        assertTrue(PhoneStats.summary(emptyList(), now).isEmpty())
    }

    @Test
    fun officeFirstPass() {
        fun e(job: String, kind: String, step: String, w: Int, at: Long = now - 1000) = OfficeStats.Event(at, job, kind, step, w)
        val ev = listOf(
            e("a", "PPT", "create", 0),                                   // 一次过
            e("b", "PPT", "create", 2), e("b", "PPT", "rework", 0, now - 500), // 自己改好
            e("c", "PPT", "create", 0), e("c", "PPT", "rework", 0, now - 500), // 看了截图返工：不算一次过
            e("d", "Word", "create", 3),                                  // 带着提醒交付
            e("", "Word", "change", 0),                                   // 之后又让改
            e("z", "Excel", "create", 0, old),                            // 太旧
        )
        val (all, byKind) = OfficeStats.lines(ev, now)
        assertEquals(4, all.made)
        assertEquals(1, all.firstPass)
        assertEquals(2, all.fixed)
        assertEquals(1, all.leftWarnings)
        assertEquals(1, all.changes)
        assertEquals(listOf("PPT", "Word"), byKind.map { it.kind })
        assertEquals(33, byKind[0].percent)
        val text = OfficeStats.summary(ev, now)
        assertTrue(text, text.startsWith("近 30 天生成 4 份，一次过 1（25%）"))
        assertTrue(text, "- Word：1 份，一次过 0（0%），自己返工后改好 0，交付时还带排版提醒 1；之后又让改 1 次" in text)
        assertTrue(OfficeStats.summary(emptyList(), now).isEmpty())
    }
}
