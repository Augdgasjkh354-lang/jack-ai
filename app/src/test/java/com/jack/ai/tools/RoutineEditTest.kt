package com.jack.ai.tools

import com.jack.ai.data.Routine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原地改提醒 / 任务 / 监控：编号不变，只改给了的字段。 */
class RoutineEditTest {
    private fun args(s: String) = Json.parseToJsonElement(s).jsonObject
    private val now = System.currentTimeMillis()
    private val nextYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1

    @Test
    fun onlyTheTimeChanges() {
        val old = Routine(kind = "reminder", title = "交周报", hour = 9, minute = 0, repeat = "weekly", date = "$nextYear-01-05")
        val r = editedRoutine(old, args("""{"kind":"reminder","when":"$nextYear-01-06 10:30"}"""), now)
        assertEquals(old.id, r.id)
        assertEquals("交周报", r.title)
        assertEquals("weekly", r.repeat)
        assertEquals("$nextYear-01-06", r.date)
        assertEquals(10, r.hour); assertEquals(30, r.minute)
    }

    @Test
    fun taskKeepsPromptUnlessGivenAndTurnsBackOn() {
        val old = Routine(kind = "task", title = "AI 新闻", prompt = "整理 5 条", hour = 8, minute = 0, repeat = "daily",
            date = "$nextYear-01-01", enabled = false, offPeak = true)
        val r = editedRoutine(old, args("""{"kind":"task","text":"AI 早报","off_peak":false}"""), now)
        assertEquals("AI 早报", r.title); assertEquals("整理 5 条", r.prompt)
        assertEquals(false, r.offPeak); assertTrue(r.enabled)
    }

    @Test
    fun watchCanBeExtended() {
        val old = Routine(kind = "watch", title = "盯票", prompt = "查 G1", hour = 0, minute = 0, everyMin = 30, until = now + 3_600_000)
        val r = editedRoutine(old, args("""{"kind":"watch","every_minutes":60,"until":"$nextYear-01-02"}"""), now)
        assertEquals(60, r.everyMin)
        assertTrue(r.until > old.until)
        assertEquals("查 G1", r.prompt)
    }

    @Test
    fun badInputsAreExplained() {
        val old = Routine(kind = "reminder", title = "x", hour = 9, minute = 0, date = "$nextYear-01-05")
        val e1 = runCatching { editedRoutine(old, args("""{"repeat":"monthly"}"""), now) }.exceptionOrNull()!!
        assertTrue(e1.message!!.contains("repeat"))
        val e2 = runCatching { editedRoutine(old, args("""{"when":"2001-01-01 09:00"}"""), now) }.exceptionOrNull()!!
        assertTrue(e2.message!!.contains("过去"))
    }
}
