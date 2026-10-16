package com.jack.ai.ui

import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private fun conv(title: String, vararg texts: String, at: Long = 0) =
        Conversation(title = title, updatedAt = at, messages = texts.map { ChatMessage(role = "user", content = it) })

    @Test
    fun titleHitsComeFirstAndSnippetShowsMatch() {
        val a = conv("周末计划", "去爬山", at = 1)
        val b = conv("闲聊", "我想把周末计划改一下，下雨就不去了", at = 2)
        val c = conv("别的", "没关系")
        val r = searchConversations(listOf(c, b, a), "周末计划")
        assertEquals(listOf(a.id, b.id), r.map { it.first.id })
        assertTrue(r[1].second.contains("周末计划"))
    }

    @Test
    fun caseInsensitiveAndSkipsEmpty() {
        val r = searchConversations(listOf(conv("x", "Hello World"), Conversation(title = "hello")), "hello")
        assertEquals(1, r.size)
    }
}
