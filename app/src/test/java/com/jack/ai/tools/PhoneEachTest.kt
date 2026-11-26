package com.jack.ai.tools

import com.jack.ai.a11y.Element
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneEachTest {
    private fun el(n: Int, label: String, role: String = "按钮") =
        Element(n, role, label, intArrayOf(0, n * 100, 500, n * 100 + 80), listOf(n), clickable = true, editable = false,
            scrollable = false, password = false, checked = null, enabled = true)

    @Test fun cursorDoesEachOnceScrollsThenEnds() {
        val c = PhoneEach.Cursor(PhoneEach.Matcher(null, "广告"))
        val page1 = listOf(el(1, "首页", "文字"), el(2, "看广告 A"), el(3, "看广告 B"))
        var s = c.advance(page1, justScrolled = false, reveal = true)
        assertEquals("看广告 A", (s as PhoneEach.Cursor.Step.Do).e.label); c.finish(s.e)
        // 回到列表，编号变了也认得出做过的
        s = c.advance(listOf(el(5, "看广告 A"), el(6, "看广告 B")), false, true)
        assertEquals("看广告 B", (s as PhoneEach.Cursor.Step.Do).e.label); c.finish(s.e)
        assertEquals(PhoneEach.Cursor.Step.Scroll, c.advance(page1, false, true))
        // 翻过一页冒出新的
        s = c.advance(listOf(el(1, "看广告 B"), el(2, "看广告 C")), true, true)
        assertEquals("看广告 C", (s as PhoneEach.Cursor.Step.Do).e.label); c.finish(s.e)
        assertEquals(PhoneEach.Cursor.Step.Scroll, c.advance(listOf(el(1, "看广告 C")), false, true))
        // 再翻没有新的：到底
        assertEquals(PhoneEach.Cursor.Step.End, c.advance(listOf(el(1, "看广告 C")), true, true))
    }

    @Test fun revealNoneStopsAfterVisible() {
        val c = PhoneEach.Cursor(PhoneEach.Matcher("按钮", null))
        val s = c.advance(listOf(el(1, "一")), false, reveal = false) as PhoneEach.Cursor.Step.Do
        c.finish(s.e)
        assertEquals(PhoneEach.Cursor.Step.End, c.advance(listOf(el(1, "一"), el(2, "标题", "文字")), false, reveal = false))
    }

    @Test fun itemPlaceholderBecomesNumber() {
        val body = listOf(
            buildJsonObject { put("action", "tap"); put("text", "@item"); put("expect", "详情") },
            buildJsonObject { put("action", "tap"); put("text", "领取") },
            buildJsonObject { put("action", "back") },
        )
        val r = PhoneEach.resolve(body, el(7, "看广告 A"))
        assertEquals(JsonPrimitive(7), r[0]["n"]); assertFalse("text" in r[0]); assertEquals(JsonPrimitive("详情"), r[0]["expect"])
        assertEquals(body[1], r[1]); assertEquals(body[2], r[2])
    }

    @Test fun repeatDetectorNudgesOnceOnOpenDoBack() {
        val d = PhoneEach.RepeatDetector()
        val motif = listOf("tap", "tap", "back")
        val fired = (1..3).flatMap { motif }.map { d.feed(it, true) }
        assertEquals(listOf(false, false, false, false, false, false, false, false, true), fired)
        assertFalse((1..3).flatMap { motif }.any { d.feed(it, true) })
    }

    @Test fun repeatDetectorIgnoresFailuresAndAfterEach() {
        val d = PhoneEach.RepeatDetector()
        listOf("tap", "back", "tap", "back", "tap").forEach { d.feed(it, true) }
        assertFalse(d.feed("back", false))
        val e = PhoneEach.RepeatDetector()
        e.feed("each", true)
        assertFalse((1..4).flatMap { listOf("tap", "back") }.any { e.feed(it, true) })
        // 只是来回翻页、没有返回的不算
        val f = PhoneEach.RepeatDetector()
        assertFalse((1..6).flatMap { listOf("scroll", "read") }.any { f.feed(it, true) })
        assertTrue(true)
    }
}
