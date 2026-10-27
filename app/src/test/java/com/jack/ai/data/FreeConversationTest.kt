package com.jack.ai.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FreeConversationTest {
    @Test fun conversationAndPendingStepSurviveRestart() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val messages = listOf(
            buildJsonObject { put("role", "system"); put("content", "设定") },
            buildJsonObject { put("role", "assistant"); put("content", "还想继续读") },
        )
        val state = FreeConversation.State(messages, 812_345, 810_000, true, "继续读", 0.12, "2026-10-27", 0.05)
        FreeConversation.save(app, state)
        assertEquals(state, FreeConversation.load(app))
    }

    @Test fun thresholdsUseActualUsageAndCutOnlyAtMessageBoundaries() {
        assertTrue(FreeConversation.current(790_000, 790_000, 800_001) >= FreeConversation.WARN)
        assertTrue(FreeConversation.current(949_000, 940_000, 950_001) >= FreeConversation.COMPACT)
        assertEquals(800_000, FreeConversation.current(960_000, 960_000, 800_000))
        val messages = listOf(
            buildJsonObject { put("role", "system"); put("content", "设定") },
            buildJsonObject { put("role", "user"); put("content", "旧内容") },
            buildJsonObject { put("role", "assistant"); put("content", "旧回答") },
            buildJsonObject { put("role", "user"); put("content", "最近") },
            buildJsonObject { put("role", "assistant"); put("content", "a".repeat(520_000)) },
        )
        val cut = FreeConversation.cutIndex(messages)!!
        assertTrue(cut > 1)
        assertTrue((messages[cut]["role"] as JsonPrimitive).content in setOf("user", "assistant"))
    }
    @Test fun compactionKeepsRecentMessagesAndArchivesOriginals() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val original = buildJsonObject { put("role", "user"); put("content", "很久以前的想法") }
        val recent = buildJsonObject { put("role", "assistant"); put("content", "a".repeat(520_000)) }
        val messages = mutableListOf(
            buildJsonObject { put("role", "system"); put("content", "设定") }, original,
            buildJsonObject { put("role", "assistant"); put("content", "旧回答") },
            buildJsonObject { put("role", "user"); put("content", "最近的问题") }, recent,
        )
        FreeConversation.compact(app, messages) { "整理过的想法" }
        assertTrue(messages.any { it == recent })
        assertTrue(messages.any { it["content"]?.toString()?.contains("整理过的想法") == true })
        val dir = java.io.File(Workspace.dir(app, Reader.CONV_ID), ".src")
        val archives = dir.listFiles().orEmpty().filter { it.name.startsWith("free_archive_") }
        assertTrue(archives.any { it.readText().contains("很久以前的想法") })
        Unit
    }

}
