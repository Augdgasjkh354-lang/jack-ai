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

    @Test fun keepsTheSameDaysMessagesAndArchivesThemOnTheNextDay() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val messages = listOf(buildJsonObject { put("role", "assistant"); put("content", "昨天的想法") })
        val state = FreeConversation.State(messages = messages, next = "接着做", conversationDay = "2026-10-27")
        FreeConversation.save(app, state)
        assertEquals(state, FreeConversation.forDay(app, "2026-10-27"))
        val next = FreeConversation.forDay(app, "2026-10-28")
        assertTrue(next.messages.isEmpty())
        assertTrue(next.next.isEmpty())
        assertEquals("2026-10-28", next.conversationDay)
        assertEquals(next, FreeConversation.load(app))
        val dir = java.io.File(Workspace.dir(app, Reader.CONV_ID), ".src")
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("free_day_2026-10-27_") && it.readText().contains("昨天的想法") })
    }

    @Test fun nextDayGetsTheSavedContinuationInANewConversation() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        FreeTime.invalidate()
        val messages = listOf(
            buildJsonObject { put("role", "system"); put("content", "自由时间") },
            buildJsonObject { put("role", "user"); put("content", "开始") },
            buildJsonObject { put("role", "assistant"); put("content", "读到第三章，想看看河流") },
        )
        FreeConversation.save(app, FreeConversation.State(messages = messages, conversationDay = "2026-10-27"))
        val previous = FreeState(lately = "喜欢看云")
        val summary = Reader.summarizeForNextTime(messages, "") { "读到第三章，还想看看河流。" }!!
        FreeTime.save(app, previous.copy(freeSummary = summary, freeSummaryAt = 123))
        val today = FreeConversation.forDay(app, "2026-10-28")
        FreeTime.invalidate()
        val nextState = FreeTime.load(app)
        assertTrue(today.messages.isEmpty())
        assertTrue(Reader.continuationNote(nextState).contains("读到第三章"))
        assertEquals("喜欢看云", nextState.lately)
        Unit
    }

    @Test fun dreamReceivesTheEntireAutobiography() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val full = "过往".repeat(2500) + "全文最后一段"
        val bio = Autobiography(text = full)
        java.io.File(app.filesDir, "autobiography.json").writeText(kotlinx.serialization.json.Json.encodeToString(Autobiography.serializer(), bio))
        assertTrue(SelfStory.forDream(app).contains("全文最后一段"))
        assertTrue(SelfStory.forDream(app).contains(full))
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
