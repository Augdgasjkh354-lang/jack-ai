package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kavi 的钱：没花完的攒下（有上限、没过自由时间的日子不攒）；从积蓄拿了没花完的退回；近况写得出来。 */
class FreeTimeTest {
    private val day = 86_400_000L
    private val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse("2026-10-27 02:30")!!.time

    @Test
    fun unspentBudgetIsSaved() {
        // 第一次：只记下日子
        val first = FreeTime.accrue(FreeState(), emptyList(), 0.5, now)
        assertEquals(0.0, first.savings, 1e-9)
        assertEquals("2026-10-26", first.accruedThrough)
        // 之后：前天花了 0.2（攒 0.3），昨天没过自由时间（不攒），今天的不算
        val log = listOf(ReadEntry(at = now - 2 * day, spent = 0.2), ReadEntry(at = now, spent = 0.4))
        val s = FreeTime.accrue(FreeState(accruedThrough = "2026-10-24"), log, 0.5, now)
        assertEquals(0.3, s.savings, 1e-9)
        assertEquals("2026-10-26", s.accruedThrough)
        // 同一天再算一次不重复攒
        assertEquals(s, FreeTime.accrue(s, log, 0.5, now))
        // 最多攒 7 天
        assertEquals(3.5, FreeTime.accrue(FreeState(savings = 3.4, accruedThrough = "2026-10-24"), log, 0.5, now).savings, 1e-9)
    }

    @Test
    fun existingGiftsAreNeverReducedWhenSavingsAreCapped() {
        val log = listOf(ReadEntry(at = now - 2 * day, spent = 0.2))
        val state = FreeState(savings = 5.0, accruedThrough = "2026-10-24")
        assertEquals(5.0, FreeTime.accrue(state, log, 0.5, now).savings, 1e-9)
        assertEquals(3.5, FreeTime.accrue(state.copy(savings = 3.4), log, 0.5, now).savings, 1e-9)
    }

    @Test
    fun takingFromSavingsRaisesTheLimitAndReturnsTheRest() {
        val b = FreeBudget(today = 0.5, extra = 0.1, savings = 1.0) { "系统：用完了 $it" }
        assertEquals(0.6, b.limit, 1e-9)
        b.noted = true
        assertEquals(0.4, b.use(0.4), 1e-9)
        assertFalse(b.noted)  // 拿了钱，用完时再提醒一次
        assertEquals(0.9, b.soft, 1e-9)
        assertEquals(0.6, b.savings, 1e-9)
        assertEquals(0.6, b.use(5.0), 1e-9)  // 拿不了那么多就拿全部
        // 一共花了 0.7：今天的 0.5 + 积蓄里的 0.2，没花的 0.8 退回
        assertEquals(0.8, b.settle(0.7), 1e-9)
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
