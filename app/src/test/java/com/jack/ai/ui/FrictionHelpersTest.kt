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
        // 第一条前面有时间、后面有压缩分界线，第二条在列表里是第 3 项（同一时间发的，中间不再标时间）
        assertEquals(3, messageItemIndex(c, ms[1].id))
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

class AppFontsTest {
    @Test
    fun groupsWeightFilesIntoOneFamily() {
        assertEquals("MiSans", AppFonts.familyOf("MiSans-Medium.ttf"))
        assertEquals("MiSans", AppFonts.familyOf("MiSans-Bold.ttf"))
        assertEquals("MiSans", AppFonts.familyOf("MiSansVF.ttf"))
        assertEquals("HarmonyOS-Sans-SC", AppFonts.familyOf("HarmonyOS_Sans_SC_Regular.ttf"))
        assertEquals("OPPOSans", AppFonts.familyOf("OPPOSans-SemiBold.otf"))
        assertEquals(500, AppFonts.weightOf("MiSans-Medium.ttf"))
        assertEquals(600, AppFonts.weightOf("OPPOSans-SemiBold.otf"))
        assertEquals(700, AppFonts.weightOf("HarmonyOS_Sans_SC_Bold.ttf"))
        assertNull(AppFonts.weightOf("MiSansVF.ttf"))
    }
}

class TimeStampTest {
    @Test
    fun breaksAfterTenMinutesAndFormatsInChinese() {
        val a = ChatMessage(role = "user", content = "a", createdAt = 1_000_000L)
        val b = a.copy(id = "b", createdAt = a.createdAt + 9 * 60_000L)
        val c = a.copy(id = "c", createdAt = a.createdAt + 20 * 60_000L)
        assertTrue(timeBreak(null, a))
        assertTrue(!timeBreak(a, b))
        assertTrue(timeBreak(b, c))
        val cal = java.util.Calendar.getInstance().apply { set(2026, 10, 25, 19, 29, 0) }
        val now = cal.timeInMillis
        assertEquals("19:29", stampWhen(now, now))
        assertEquals("昨天 19:29", stampWhen(now - 86_400_000L, now))
        cal.set(2026, 2, 3, 8, 5, 0)
        assertEquals("3月3日 08:05", stampWhen(cal.timeInMillis, now))
    }
}

class FrictionRoundThreeTest {
    @Test
    fun plainCopyDropsMarkdownMarks() {
        val md = """
            ## 标题
            这是**重点**和`代码`，还有[链接](https://a.com)。

            - 第一条
            * [x] 做完的

            | 名字 | 价格 |
            |---|---:|
            | 苹果 | 3 |

            ```kotlin
            val a = 1 * 2
            ```
        """.trimIndent()
        val plain = markdownToPlain(md)
        assertEquals("""
            标题
            这是重点和代码，还有链接（https://a.com）。

            • 第一条
            • 做完的

            名字  价格
            苹果  3

            val a = 1 * 2
        """.trimIndent(), plain)
        // 乘号、单个星号不当斜体吃掉
        assertEquals("3 * 4 = 12", markdownToPlain("3 * 4 = 12"))
        assertEquals("要斜体", markdownToPlain("要*斜体*"))
    }

    @Test
    fun memorySearchMatchesEveryWordInTextOrTags() {
        val m = com.jack.ai.data.MemoryItem(text = "用户喜欢喝 Latte", about = listOf("咖啡店"))
        assertTrue(memoryMatches(m, ""))
        assertTrue(memoryMatches(m, "latte"))
        assertTrue(memoryMatches(m, "喜欢  咖啡"))
        assertTrue(!memoryMatches(m, "喜欢 茶"))
    }

    @Test
    fun keyErrorsPointToTheRightSettingsPage() {
        assertEquals("KEY", errorSettingsPage("API Key 不对或没有权限，请到设置里检查（401）", "deepseek-flash"))
        assertEquals("KEY", errorSettingsPage("账户余额不足，请先充值（402）", ""))
        assertEquals("PROVIDERS", errorSettingsPage("API Key 不对或没有权限，请到设置里检查（401）", "c:p1:gpt-5"))
        assertEquals("PROVIDERS", errorSettingsPage("ChatGPT 登录失效了，到 设置 → 模型服务 里重新登录。", "c:p2:gpt-6.2-sol"))
        assertNull(errorSettingsPage("服务器繁忙，稍后再试（503）", "deepseek-flash"))
    }
}

class DanmakuLanesTest {
    @org.junit.Test fun everySuggestionLandsInExactlyOneLane() {
        val pool = com.jack.ai.data.BizStarts.DRIFT
        val lanes = danmakuLanes(pool, 42)
        org.junit.Assert.assertEquals(4, lanes.size)
        org.junit.Assert.assertEquals(pool.map { it.key }.sorted(), lanes.flatten().map { it.key }.sorted())
        // 种子一样排法一样，换个种子换一种
        org.junit.Assert.assertEquals(lanes, danmakuLanes(pool, 42))
        org.junit.Assert.assertNotEquals(lanes, danmakuLanes(pool, 7))
    }

    @org.junit.Test fun fadesOnlyNearTheEdges() {
        org.junit.Assert.assertEquals(1f, edgeAlpha(500f, 1000f, 50f), 0f)
        org.junit.Assert.assertEquals(0.5f, edgeAlpha(25f, 1000f, 50f), 0.001f)
        org.junit.Assert.assertEquals(0.5f, edgeAlpha(975f, 1000f, 50f), 0.001f)
        org.junit.Assert.assertEquals(0f, edgeAlpha(-10f, 1000f, 50f), 0f)
        org.junit.Assert.assertEquals(0f, edgeAlpha(1100f, 1000f, 50f), 0f)
    }

    @org.junit.Test fun keysAreUnique() {
        val keys = com.jack.ai.data.BizStarts.DRIFT.map { it.key }
        org.junit.Assert.assertEquals(keys.size, keys.toSet().size)
    }
}
