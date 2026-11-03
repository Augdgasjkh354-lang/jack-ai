package com.jack.ai.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** 几个对话同时改记忆（回看、Kavi 存、用户手改）：都走「锁里读改写」，谁的都不丢。 */
@RunWith(RobolectricTestRunner::class)
class MemoryConcurrencyTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun together(n: Int, body: (Int) -> Unit) {
        val start = CountDownLatch(1)
        List(n) { i -> thread { start.await(); body(i) } }.also { start.countDown() }.forEach { it.join() }
    }

    @Test
    fun globalMemoryUpdatesFromManyConversationsAllSurvive() {
        app.filesDir.listFiles().orEmpty().filter { it.name.startsWith("memories.json") }.forEach { it.delete() }
        val store = Store(app)
        store.saveMemories(listOf(MemoryItem(text = "原有记忆")))
        together(8) { i -> repeat(5) { k -> store.updateMemories { it + MemoryItem(text = "对话 $i 第 $k 条") } } }
        val texts = store.loadMemories().map { it.text }
        assertEquals(41, texts.size)
        assertTrue("原有记忆" in texts && "对话 7 第 4 条" in texts)
        // 不改就返回现在的，不写盘
        assertEquals(texts, store.updateMemories { null }.map { it.text })
    }

    @Test
    fun sharedWorkMemoryUpdatesAllSurvive() {
        val file = File(Files.createTempDirectory("work-memory").toFile(), ChatMemory.WORK_FILE)
        ChatMemory.write(file, listOf("原有做事经验"))
        together(6) { i -> repeat(5) { k -> ChatMemory.add(file, "对话 $i 学到的第 $k 条") } }
        val items = ChatMemory.items(ChatMemory.read(file))
        assertEquals(31, items.size)
        assertNull(ChatMemory.update(file) { null })
        assertTrue(!ChatMemory.add(file, "原有做事经验"))
    }
}
