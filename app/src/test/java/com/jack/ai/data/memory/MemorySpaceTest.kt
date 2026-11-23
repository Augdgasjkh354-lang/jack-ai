package com.jack.ai.data.memory

import com.jack.ai.data.Conversation
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.MemorySpace
import com.jack.ai.data.Settings
import com.jack.ai.data.inSpace
import com.jack.ai.data.spaceName
import com.jack.ai.data.spaceOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** 记忆空间：每个对话只用、只记自己空间的；删掉的空间按「生活」算。 */
class MemorySpaceTest {
    private val work = MemorySpace("work", "工作")
    private val s = Settings(memorySpaces = listOf(work))
    private val life = MemoryItem(id = "life1", text = "Jack 养了一只猫")
    private val job = MemoryItem(id = "job1", text = "Jack 周报每周五交", space = "work")
    private val orphan = MemoryItem(id = "old1", text = "删掉的空间里的", space = "gone")

    @Test fun spacesFilterAndFallBack() {
        assertEquals(listOf("life1", "old1"), listOf(life, job, orphan).inSpace("", s).map { it.id })
        assertEquals(listOf("job1"), listOf(life, job, orphan).inSpace("work", s).map { it.id })
        assertEquals("work", s.spaceOf(Conversation(memorySpace = "work")))
        assertEquals("", s.spaceOf(Conversation(memorySpace = "gone")))
        assertEquals("生活", s.spaceName("gone"))
    }

    @Test fun reviewOnlyTouchesItsSpace() {
        val ops = listOf(
            MemoryReview.Op("add", "Jack 下周出差去上海", about = listOf("上海")),
            // 别的空间里的那条对不上，不会被删
            MemoryReview.Op("delete", old = "养了一只猫"),
        )
        val (next, done) = MemoryReview.applyItems(listOf(life, job), ops, 1500, "test", space = "work", settings = s)
        assertEquals(1, done.size)
        assertEquals(setOf("life1", "job1"), next.filter { it.id in setOf("life1", "job1") }.map { it.id }.toSet())
        val added = next.single { it.text.contains("上海") }
        assertEquals("work", added.space)
        assertEquals(listOf("上海"), added.about)
    }
}
