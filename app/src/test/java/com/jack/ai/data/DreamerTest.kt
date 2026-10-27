package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamerTest {
    private fun msg(at: Long, text: String, role: String = "user") = ChatMessage(role = role, content = text, createdAt = at)

    @Test
    fun transcriptFitsEverythingWhenSmall() {
        val a = Conversation(title = "甲", messages = listOf(msg(10, "旧的"), msg(110, "一"), msg(130, "三", "assistant")))
        val b = Conversation(title = "乙", messages = listOf(msg(120, "二")))
        val (text, cutoff) = Dreamer.transcript(listOf(a, b), since = 100, until = 1000)
        assertNull(cutoff)
        assertFalse(text.contains("旧的"))
        // 对话按第一条新消息的先后排
        assertTrue(text.indexOf("甲") < text.indexOf("乙"))
        assertTrue(text.contains("Kavi: 三"))
    }

    @Test
    fun tooMuchKeepsTheEarliestAndResumesAfterIt() {
        val long = "字".repeat(2000)
        val c = Conversation(title = "长", messages = (1..10).map { msg(100L + it, "$it$long") })
        val (first, cutoff) = Dreamer.transcript(listOf(c), since = 100, until = 1000, limit = 5_000)
        assertEquals(102L, cutoff)
        assertTrue(first.contains("1字") && first.contains("2字") && !first.contains("3字"))
        // 下一轮从断的地方接着：一条不漏、不重复
        val (second, _) = Dreamer.transcript(listOf(c), since = cutoff!!, until = 1000, limit = 5_000)
        assertTrue(second.contains("3字") && !second.contains("2字"))
        // 一条就超过上限也至少放一条，不会卡住
        val (one, cut) = Dreamer.transcript(listOf(c), since = 100, until = 1000, limit = 10)
        assertTrue(one.contains("1字"))
        assertEquals(101L, cut)
    }

    @Test
    fun mergesTenOldJournalsButKeepsTheNewest() {
        val nine = (1..10).map { DreamEntry(createdAt = it * 1000L, journal = "第 $it 天") }
        // 最新一篇不算，只有 9 篇：不合
        assertNull(Dreamer.mergeBatch(nine))
        val list = (nine + DreamEntry(createdAt = 11_000L, journal = "第 11 天")).sortedByDescending { it.createdAt }
        val batch = Dreamer.mergeBatch(list)!!
        assertEquals((1..10).map { "第 $it 天" }, batch.map { it.journal })
        val after = Dreamer.replaceWithMerged(list, batch, "十天合成", "记了几条")
        assertEquals(2, after.size)
        // 下次聊天带的仍是最新一篇
        assertEquals("第 11 天", after.first { it.journal.isNotBlank() }.journal)
        val m = after[1]
        assertEquals(10, m.merged); assertEquals(1000L, m.from); assertEquals(10_000L, m.createdAt)
        // 合成过的不再算进下一批
        assertNull(Dreamer.mergeBatch(after))
    }
}
