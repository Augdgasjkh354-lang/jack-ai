package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ProactiveTest {
    private fun at(day: Int, h: Int) = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, day, h, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test
    fun quietHoursAndDailyCap() {
        assertTrue(Proactive.quiet(23)); assertTrue(Proactive.quiet(2)); assertTrue(Proactive.quiet(7))
        assertFalse(Proactive.quiet(8)); assertFalse(Proactive.quiet(18))
        val now = at(27, 18)
        assertNull(Proactive.gate(emptyList(), now, 18))
        assertTrue(Proactive.gate(emptyList(), now, 23).orEmpty().contains("深夜"))
        val two = listOf(Nudge(at(27, 9), sent = true, text = "a"), Nudge(at(27, 12), sent = true, text = "b"))
        assertTrue(Proactive.gate(two, now, 18).orEmpty().contains("2 次"))
        // 昨天的、没发出去的不算
        assertNull(Proactive.gate(listOf(Nudge(at(26, 9), sent = true), Nudge(at(27, 9), sent = false), Nudge(at(27, 10), sent = true)), now, 18))
    }

    @Test
    fun mutesAndRepeats() {
        val now = at(27, 18)
        val log = listOf(
            Nudge(at(26, 9), sent = true, text = "今天该回访王总了，他上周说这周定", kind = "客户回访"),
            Nudge(at(25, 9), sent = true, text = "明早 9 点有会", kind = "日程准备", feedback = "mute"),
        )
        assertEquals(setOf("日程准备"), Proactive.muted(log))
        assertTrue(Proactive.veto(log, "日程准备", "后天有个会", now).orEmpty().contains("别为"))
        assertTrue(Proactive.veto(log, "客户回访", "今天该回访王总了，他上周说这周定", now).orEmpty().contains("说过"))
        assertNull(Proactive.veto(log, "客户回访", "李姐的报价今天到期，要不要跟进一下", now))
        // 三天以前说过的可以再说
        assertNull(Proactive.veto(listOf(Nudge(at(20, 9), sent = true, text = "x", kind = "k")), "k", "x", now))
    }

    @Test
    fun recapOfRecentChats() {
        val now = at(27, 18)
        fun conv(id: String, title: String, t: Long, user: String, reply: String, project: String? = null, global: Boolean = true) =
            Conversation(id = id, title = title, updatedAt = t, projectId = project, useGlobalMemory = global,
                messages = listOf(ChatMessage(role = "user", content = user), ChatMessage(role = "assistant", content = reply)))
        val all = listOf(
            conv("a", "报价", at(27, 10), "给王总报价\n200 件", "**好的**，单价 18 元"),
            conv("b", "旧事", at(10, 10), "很久以前", "嗯"),
            conv("c", "私事", at(27, 9), "秘密", "嗯", project = "p"),
            conv("d", "不带记忆", at(27, 8), "x", "y", global = false),
        )
        val projects = listOf(Project(id = "p", name = "隐私", isolated = true))
        val r = Recap.build(Conversation(id = "new"), all, projects, now)
        assertTrue(r, r.contains("「报价」") && r.contains("给王总报价 200 件") && r.contains("好的，单价 18 元"))
        assertFalse(r.contains("旧事")); assertFalse(r.contains("私事")); assertFalse(r.contains("不带记忆"))
        // 这个对话自己关了全局记忆：什么都不带
        assertEquals("", Recap.build(Conversation(id = "new", useGlobalMemory = false), all, projects, now))
    }
}
