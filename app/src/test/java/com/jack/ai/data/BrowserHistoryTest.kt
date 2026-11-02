package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHistoryTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test
    fun mergesVisitsAndFindsByKeywordAndDays() {
        var h = BrowserHistory.record(emptyList(), "https://detail.1688.com/offer/1.html", "象印保温杯 SM-SA48 批发", now - 6 * day)
        h = BrowserHistory.record(h, "https://www.ruanyifeng.com/blog/weekly.html", "科技爱好者周刊", now - 2 * day)
        h = BrowserHistory.record(h, "https://detail.1688.com/offer/1.html", "", now - day)
        assertEquals(2, h.size)
        val cup = h.first()
        assertEquals(2, cup.visits)
        assertEquals("象印保温杯 SM-SA48 批发", cup.title)
        assertEquals(cup.url, BrowserHistory.search(h, "保温杯", now).single().url)
        assertEquals(cup.url, BrowserHistory.search(h, "1688 保温杯", now).first().url)
        assertTrue(BrowserHistory.search(h, "周刊", now, days = 1).isEmpty())
        assertEquals(2, BrowserHistory.search(h, "", now).size)
        assertTrue(BrowserHistory.describe(h, now).contains("看过 2 次"))
    }

    @Test
    fun priceSummaryShowsTrend() {
        var log = emptyList<PricePoint>()
        listOf(129.0, 119.0, 139.0, 99.0).forEachIndexed { i, p -> log = PriceLog.add(log, PricePoint("象印保温杯", p, now + i * day)) }
        val s = PriceLog.series(log, "保温杯")
        assertEquals(4, s.size)
        val sum = PriceLog.summary(s)
        assertTrue(sum, sum.contains("最新 ¥99"))
        assertTrue(sum, sum.contains("比上次降了 ¥40"))
        assertTrue(sum, sum.contains("现在是记录里最低的"))
        assertEquals(listOf("象印保温杯" to 4), PriceLog.items(log))
        assertTrue(PriceLog.series(log, "电饭煲").isEmpty())
    }

    @Test
    fun historyPageGroupsByDayAndFiltersAndRemoves() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val now = java.time.ZonedDateTime.of(2026, 11, 2, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        val h = 3_600_000L
        val list = listOf(
            HistoryEntry("https://a.com", "保温杯", now - h),
            HistoryEntry("https://b.com", "新闻", now - 20 * h),
            HistoryEntry("https://c.com", "旧的", now - 400L * 24 * h),
            HistoryEntry("https://d.com", "三月", java.time.ZonedDateTime.of(2026, 3, 5, 9, 0, 0, 0, zone).toInstant().toEpochMilli()),
        )
        val g = BrowserHistory.byDay(list, "", now, zone)
        org.junit.Assert.assertEquals(listOf("今天", "昨天", "3月5日", "2025年9月28日"), g.map { it.first })
        org.junit.Assert.assertEquals(listOf("https://a.com"), BrowserHistory.byDay(list, "保温 a.com", now, zone).flatMap { it.second }.map { it.url })
        org.junit.Assert.assertEquals(3, BrowserHistory.remove(list, "https://b.com").size)
    }
}
