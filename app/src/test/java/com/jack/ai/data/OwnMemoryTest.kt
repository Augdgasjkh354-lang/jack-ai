package com.jack.ai.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OwnMemoryTest {
    @Test fun deepRecallAndRecent() {
        val hot = OwnMemoryItem(text = "整理工作台", source = "free_session_x.json", at = 1)
        val river = OwnMemoryItem(text = "研究河流", source = "free_session_x.json", at = 2, deep = true)
        val other = OwnMemoryItem(text = "阅读诗歌", source = "free_session_x.json", at = 3, deep = true)
        assertEquals(listOf(hot.id, river.id), OwnMemory.select(listOf(hot, river, other), "河流").map { it.id })
        assertEquals(setOf(hot.id, river.id, other.id), OwnMemory.select(listOf(hot, river, other), "最近做了什么").map { it.id }.toSet())
    }

    @Test fun pendingSurvivesFailureAndStoresSource() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val messages = listOf("system", "user", "assistant").map { role -> buildJsonObject { put("role", role); put("content", if (role == "assistant") "我研究河流" else "开场") } }
        val source = FreeConversation.snapshot(app, FreeConversation.State(messages = messages, conversationDay = "2026-10-26"))
        assertTrue(source in FreeConversation.pending(app))
        try { OwnMemory.extract(app, source, 0) { "错误" }; fail() } catch (_: IllegalArgumentException) { }
        assertTrue(source in FreeConversation.pending(app))
        val batch = OwnMemory.extract(app, source, 0) { """{"memories":[{"kind":"发现","text":"研究了河流"}]}""" }
        OwnMemory.add(app, batch.items)
        FreeConversation.advancePending(app, source, batch.nextOffset)
        assertFalse(source in FreeConversation.pending(app))
        assertEquals(source, OwnMemory.load(app).single().source)
        assertEquals("2026-10-26", FreeTime.day(OwnMemory.load(app).single().at))
        assertTrue(FreeConversation.source(app, source)!!.exists())
        assertTrue(OwnMemory.move(app, OwnMemory.load(app).single().id, true))
        assertTrue(OwnMemory.load(app).single().deep)
    }

    @Test fun dreamOriginalsSurvivePreservation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val original = DreamEntry(journal = "原篇", dream = "旧梦", note = "醒来")
        DreamArchive.preserve(app, listOf(original))
        DreamArchive.preserve(app, listOf(DreamEntry(journal = "另一篇")))
        assertEquals(original, DreamArchive.load(app).first { it.id == original.id })
    }
    @Test fun backupRestoresOwnMemoryAndOriginals() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val item = OwnMemoryItem(text = "独自研究河流", source = "free_session_test.json", at = 123)
        OwnMemory.add(app, listOf(item))
        val dream = DreamEntry(journal = "原梦境")
        DreamArchive.preserve(app, listOf(dream))
        val source = FreeConversation.snapshot(app, FreeConversation.State(messages = listOf(buildJsonObject { put("role", "assistant"); put("content", "自由时间原文") })))
        Timeline.add(app, Episode(place = "free", text = "原经历"))
        val bytes = java.io.ByteArrayOutputStream().also { Backup.export(app, it) }.toByteArray()
        java.io.File(app.filesDir, "own_memories.json").delete()
        java.io.File(app.filesDir, "dream_originals.json").delete()
        java.io.File(app.filesDir, "timeline.jsonl").delete()
        FreeConversation.source(app, source)!!.delete()
        Backup.import(app, bytes.inputStream())
        assertEquals(item, OwnMemory.load(app).single())
        assertEquals(dream, DreamArchive.load(app).single())
        assertTrue(FreeConversation.source(app, source)!!.readText().contains("自由时间原文"))
        assertTrue(Timeline.load(app).any { it.text == "原经历" })
    }


    /** 做梦的修改单里 [o:] 的那些交给自己的经历：放到深处、合并；关于用户的记忆不受影响。 */
    @Test fun dreamOpsOnOwnExperiences() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val a = OwnMemoryItem(text = "读了一篇讲河流改道的文章", source = "s.json", at = 1)
        val b = OwnMemoryItem(text = "又读了河流改道的另一篇", source = "s.json", at = 2, uses = 2)
        val c = OwnMemoryItem(text = "写了半首诗", source = "s.json", at = 3)
        OwnMemory.add(app, listOf(a, b, c))
        val ops = MemoryOps.parse(kotlinx.serialization.json.Json.parseToJsonElement("""[
            {"op":"merge","ids":["o:${a.id.take(6)}","o:${b.id.take(6)}"],"text":"读了两篇讲河流改道的文章"},
            {"op":"demote","id":"o:${c.id.take(6)}"}
        ]""") as kotlinx.serialization.json.JsonArray)
        assertTrue(ops.all { it.own })
        assertFalse(MemoryOps.tooMany(5, ops))
        assertEquals(0, MemoryOps.apply(emptyList(), ops).skipped)
        val r = OwnMemory.applyOps(app, ops)
        assertEquals(1, r.merged); assertEquals(1, r.demoted)
        val left = OwnMemory.load(app)
        assertEquals(listOf("读了两篇讲河流改道的文章", "写了半首诗"), left.map { it.text })
        assertEquals(2, left[0].uses)
        assertTrue(left[1].deep)
    }

    /** 没配向量服务时按关键词想起来，浮上来的算用到一次。 */
    @Test fun deepRecallWithoutVectors() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        MemoryOps.saveDeep(app, listOf(MemoryItem(text = "Jack 对花生过敏，吃了会起疹子")))
        val note = DeepRecall.note(app, Settings(userName = "Jack"), "晚上想做个花生酱拌面，会不会过敏")
        assertTrue(note, note.contains("花生"))
        assertEquals(1, MemoryOps.deep(app).single().uses)
        assertEquals("", DeepRecall.note(app, Settings(userName = "Jack"), "今天天气不错"))
    }
}
