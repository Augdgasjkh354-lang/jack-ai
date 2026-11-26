package com.jack.ai.ui

import com.jack.ai.UiState
import com.jack.ai.data.Attachment
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.GeneratedFile
import com.jack.ai.data.Routine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTest {
    private val stages = Guide.stages(browser = true, phone = true)
    private fun lesson(id: String) = stages.flatMap { it.lessons }.first { it.id == id }
    private fun done(id: String, s: UiState, marked: Set<String> = emptySet(), tour: Boolean = false) = Guide.isDone(lesson(id), s, marked, tour)
    private fun chat(vararg m: ChatMessage) = UiState(conversations = listOf(Conversation(messages = m.toList())))

    @Test fun lessonsTickThemselvesFromWhatHappened() {
        val empty = UiState()
        assertFalse(done("first-ask", empty))
        assertTrue(done("first-ask", chat(ChatMessage(role = "user", content = "你好"))))
        assertTrue(done("image", chat(ChatMessage(role = "user", attachments = listOf(Attachment(kind = "image", name = "a.jpg"))))))
        assertFalse(done("file", chat(ChatMessage(role = "user", attachments = listOf(Attachment(kind = "image", name = "a.jpg"))))))
        assertTrue(done("doc", chat(ChatMessage(role = "assistant", files = listOf(GeneratedFile("入职.pptx", "/x", "application/vnd"))))))
        assertTrue(done("app", chat(ChatMessage(role = "assistant", files = listOf(GeneratedFile("房贷.html", "/x", "text/html"))))))
        assertTrue(done("watch", UiState(routines = listOf(Routine(kind = "watch", title = "票", hour = 0, minute = 0)))))
        assertFalse(done("task", UiState(routines = listOf(Routine(kind = "reminder", title = "喝水", hour = 8, minute = 0)))))
        assertTrue(done("phone", chat(ChatMessage(role = "assistant", trail = listOf("""{"role":"assistant","tool_calls":[{"function":{"name":"phone","arguments":"{}"}}]}""")))))
        // 看不出来的：自己点「我会了」；聊天引导看过就算
        assertFalse(done("voice", empty)); assertTrue(done("voice", empty, marked = setOf("voice")))
        assertTrue(done("chat-tour", empty, tour = true))
    }

    @Test fun progressPointsAtTheFirstUnfinishedLesson() {
        val all = stages.flatMap { it.lessons }.map { it.id }.toSet()
        val p0 = Guide.progress(stages) { Guide.isDone(it, UiState(), emptySet(), false) }
        assertEquals(0, p0.doneStages); assertEquals("chat-tour", p0.next?.id); assertEquals(0, p0.nextStage)
        val firstStage = stages[0].lessons.map { it.id }.toSet()
        val p1 = Guide.progress(stages) { Guide.isDone(it, UiState(), firstStage, false) }
        assertEquals(1, p1.doneStages); assertEquals(1, p1.nextStage); assertEquals("image", p1.next?.id)
        val pAll = Guide.progress(stages) { Guide.isDone(it, UiState(), all, false) }
        assertNull(pAll.next); assertEquals(stages.size, pAll.doneStages)
    }

    @Test fun storeEditionDropsTheHandsOnStage() {
        assertFalse(Guide.stages(browser = false, phone = false).any { it.id == "hands" })
        assertEquals(listOf("browser"), Guide.stages(browser = true, phone = false).first { it.id == "hands" }.lessons.map { it.id })
        assertTrue(stages.all { st -> st.lessons.isNotEmpty() })
    }
}
