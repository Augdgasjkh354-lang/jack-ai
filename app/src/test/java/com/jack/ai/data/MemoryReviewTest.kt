package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryReviewTest {
    private fun msg(role: String, at: Long, text: String = "话") = ChatMessage(role = role, content = text, createdAt = at)

    @Test fun dueEveryFewUserTurnsSinceLastReview() {
        val msgs = (1..6).flatMap { listOf(msg("user", it * 10L), msg("assistant", it * 10L + 1)) }
        assertTrue(MemoryReview.due(Conversation(messages = msgs)))
        // 回看到第 4 轮：后面只剩两轮，还不到
        assertFalse(MemoryReview.due(Conversation(messages = msgs, memReviewedAt = 41)))
    }

    @Test fun parsesAndCapsOps() {
        val raw = """{"ops":[{"op":"add","text":"a"},{"op":"update","old":"b","text":"c"},{"op":"delete","old":"d"},{"op":"add","text":"e"},{"op":"bogus"}]}"""
        assertEquals(listOf("add", "update", "delete"), MemoryReview.parse(raw).map { it.op })
        assertTrue(MemoryReview.parse("不是 JSON").isEmpty())
    }

    @Test fun appliesSkippingUnknownDuplicatesAndOverflow() {
        val ops = listOf(
            MemoryReview.Op("add", "Jack 喜欢短回答"),
            MemoryReview.Op("add", "已有的"),
            MemoryReview.Op("update", text = "x", old = "认不出的"),
            MemoryReview.Op("update", text = "Jack 住在上海", old = "住在"),
        )
        val (list, done) = MemoryReview.apply(listOf("已有的", "Jack 住在北京"), ops, 10_000)
        assertEquals(listOf("已有的", "Jack 住在上海", "Jack 喜欢短回答"), list)
        assertEquals(2, done.size)
        // 满了：新增不做
        val (same, none) = MemoryReview.apply(listOf("已有的"), listOf(MemoryReview.Op("add", "新的一条很长很长")), MemoryOps.tokensOf(listOf("已有的")))
        assertEquals(listOf("已有的"), same)
        assertTrue(none.isEmpty())
    }
}
