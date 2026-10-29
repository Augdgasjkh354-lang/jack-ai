package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kavi 的钱：每天按日历存进积蓄（不设上限）；整个积蓄都能花，花光后收尾的钱不从积蓄扣；近况写得出来。 */
class FreeTimeTest {
    private val day = 86_400_000L
    private val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse("2026-10-27 02:30")!!.time

    @Test
    fun dailyBudgetGoesIntoSavingsEveryCalendarDay() {
        // 第一次：今天这份存进去
        val first = FreeTime.accrue(FreeState(savings = 0.2), 0.5, now)
        assertEquals(0.7, first.savings, 1e-9)
        assertEquals("2026-10-27", first.accruedThrough)
        // 同一天再算一次不重复存
        assertEquals(first, FreeTime.accrue(first, 0.5, now))
        // 隔了三天（手机关着也算）：存三份，没有上限
        assertEquals(10.0 + 1.5, FreeTime.accrue(FreeState(savings = 10.0, accruedThrough = "2026-10-24"), 0.5, now).savings, 1e-9)
    }

    @Test
    fun wholeSavingsCanBeSpentAndWrapUpMoneyIsNotDeducted() {
        val b = FreeBudget(0.0, extra = 0.1, savings = 1.0) { "系统：用完了 $it" }.also { it.use(1.0) }
        assertEquals(1.0, b.soft, 1e-9)
        assertEquals(1.1, b.limit, 1e-9)
        assertEquals(0.6, b.settle(0.4), 1e-9)   // 花了 0.4，剩 0.6
        assertEquals(0.0, b.settle(1.05), 1e-9)  // 花进了收尾钱：积蓄归零，不倒欠
    }

    @Test
    fun latelyIsReadFromTheReport() {
        val r = Reader.parseReport("Did: write\nTitle: 一封信\nTopics:\nLately: 最近在学写短诗，也在慢慢做一个记天气的小工具。\n今晚写了封信。")
        assertEquals("write", r.kind)
        assertEquals("最近在学写短诗，也在慢慢做一个记天气的小工具。", r.lately)
        assertEquals("今晚写了封信。", r.body)
        assertTrue(Reader.parseReport("随便").lately.isEmpty())
    }
    @Test
    fun continuationStatesWhenTheSummaryWasSaved() {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.LocalDateTime.of(2026, 10, 27, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val at = java.time.LocalDateTime.of(2026, 10, 25, 9, 30).atZone(zone).toInstant().toEpochMilli()
        val note = Reader.continuationNote(FreeState(freeSummary = "还想读完那本书", freeSummaryAt = at), now)
        assertTrue(note.contains("2026-10-25 09:30"))
        assertTrue(note.contains("2 天前"))
        assertTrue(note.contains("不一定是每件事发生的时间"))
        assertTrue(Reader.continuationNote(FreeState(freeSummary = "旧线索"), now).contains("整理时间未记录"))
        assertTrue(note.contains("（时间不明）还想读完那本书"))
        assertEquals("（2026-10-25）旧线索", Reader.markUnknownClueTimes("（2026-10-25）旧线索"))
    }

    @Test
    fun freeTimeSummaryCarriesForwardWithoutChangingLately() = kotlinx.coroutines.runBlocking {
        val messages = listOf(
            kotlinx.serialization.json.buildJsonObject { put("role", kotlinx.serialization.json.JsonPrimitive("system")) },
            kotlinx.serialization.json.buildJsonObject { put("role", kotlinx.serialization.json.JsonPrimitive("user")) },
            kotlinx.serialization.json.buildJsonObject { put("role", kotlinx.serialization.json.JsonPrimitive("assistant")); put("content", kotlinx.serialization.json.JsonPrimitive("文章读到第三章，想再看看河流")) },
        )
        val old = FreeState(lately = "近来喜欢看云", freeSummary = "旧线索")
        var seen = ""
        val summary = Reader.summarizeForNextTime(messages, old.freeSummary, "2026-10-27", 1L) { request ->
            seen = request.toString()
            "文章读到第三章；还想看看河流。"
        }
        val saved = old.copy(freeSummary = summary!!, freeSummaryAt = 1)
        assertTrue(seen.contains("文章读到第三章"))
        assertTrue(seen.contains("旧线索"))
        assertTrue(seen.contains("2026-10-27"))
        assertTrue(seen.contains("时间不明"))
        assertTrue(Reader.continuationNote(saved).contains("还想看看河流"))
        assertEquals("近来喜欢看云", saved.lately)
        assertEquals("", Reader.summarizeForNextTime(messages, "") { "无" })
        Unit
    }

}
