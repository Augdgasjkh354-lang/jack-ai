package com.jack.ai.ui

import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.nextAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrictionHelpersTest {
    @Test
    fun nameFromUrlPicksTheServiceName() {
        assertEquals("Deepseek", nameFromUrl("https://api.deepseek.com/v1"))
        assertEquals("Openrouter", nameFromUrl("https://openrouter.ai/api/v1"))
        assertEquals("Notion", nameFromUrl("https://mcp.notion.com/mcp"))
        assertEquals("Moonshot", nameFromUrl("https://api.moonshot.com.cn/v1"))
        assertEquals("", nameFromUrl("http://192.168.1.2:8080/v1"))
        assertEquals("", nameFromUrl("不是网址"))
    }

    @Test
    fun newReminderDefaultsToTheNextHourAndCanBeSaved() {
        val cal = java.util.Calendar.getInstance().apply { set(2026, 10, 24, 14, 35, 0) }
        val r = newRoutineDraft(cal.timeInMillis)
        assertEquals(15, r.hour)
        assertEquals("2026-11-24", r.date)
        assertNotNull(r.nextAt(cal.timeInMillis))
        // 深夜新建：明天 0 点
        cal.set(2026, 10, 24, 23, 10, 0)
        val late = newRoutineDraft(cal.timeInMillis)
        assertEquals(0, late.hour)
        assertEquals("2026-11-25", late.date)
        assertNotNull(late.nextAt(cal.timeInMillis))
    }

    @Test
    fun searchJumpFindsTheHitAndCountsTheSummaryDivider() {
        val ms = listOf("你好", "周末去爬山吧", "好的", "下雨了").map { ChatMessage(role = "user", content = it) }
        val c = Conversation(title = "闲聊", messages = ms, summaryUntilId = ms[0].id)
        assertEquals(ms[1].id, searchHitId(c, "爬山"))
        assertNull(searchHitId(Conversation(title = "爬山", messages = ms.take(1)), "爬山"))
        // 第一条后面有压缩分界线，第二条在列表里是第 2 项
        assertEquals(2, messageItemIndex(c, ms[1].id))
        assertEquals(-1, messageItemIndex(c, "nope"))
    }
}

class PolishHelpersTest {
    private val wj = '⁠'

    @Test
    fun tidyBreaksGluesTheTailAndDates() {
        val t = tidyBreaks("有值得长期记的当场记下")
        assertEquals("有值得长期记的当场记下", t.filter { it != wj })
        assertTrue(t.endsWith("记${wj}记${wj}下") || t.endsWith("场${wj}记${wj}下"))
        val d = tidyBreaks("每 30 分钟查一次，盯到 11月28日 07:56")
        assertTrue(d.contains("1${wj}1${wj}月${wj}2${wj}8${wj}日${wj} ${wj}0${wj}7"))
        assertEquals("", tidyBreaks(""))
        assertEquals("ab", tidyBreaks("ab").filter { it != wj })
    }

    @Test
    fun shortWhenIsChineseAndShort() {
        val c = java.util.Calendar.getInstance().apply { set(2026, 10, 25, 14, 5, 0) }
        val now = c.timeInMillis
        assertEquals("14:05", shortWhen(now, now))
        assertEquals("昨天", shortWhen(now - 86_400_000L, now))
        c.set(2026, 2, 3, 9, 0, 0)
        assertEquals("3月3日", shortWhen(c.timeInMillis, now))
        c.set(2024, 0, 9, 9, 0, 0)
        assertEquals("2024/1/9", shortWhen(c.timeInMillis, now))
    }
}
