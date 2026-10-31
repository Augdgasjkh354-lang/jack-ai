package com.jack.ai.chat

import com.jack.ai.data.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MemorySnapshotTest {
    private var taken = 0
    private fun fresh(text: String) = { taken++; MemorySnapshots.Snap(listOf(MemoryItem(text = text)), "", "", "", "", "", "", null, 0) }

    @Test fun staysFrozenWithinAConversation() {
        val s = MemorySnapshots()
        s.get("c", null, 1000, fresh("旧"))
        val again = s.get("c", null, 2000, fresh("新"))
        assertEquals("旧", again.memories.single().text)
        assertEquals(1, taken)
    }

    @Test fun refreshesAfterCompressionIdleOrUserEdit() {
        val s = MemorySnapshots()
        s.get("c", null, 0, fresh("1"))
        assertEquals("2", s.get("c", "m5", 10, fresh("2")).memories.single().text)
        // 一直在聊（每次都在过期前）就不重拍
        assertEquals("2", s.get("c", "m5", MemorySnapshots.STALE_MS - 1, fresh("x")).memories.single().text)
        assertEquals("3", s.get("c", "m5", 3 * MemorySnapshots.STALE_MS, fresh("3")).memories.single().text)
        s.clear()
        assertEquals("4", s.get("c", "m5", 3 * MemorySnapshots.STALE_MS + 1, fresh("4")).memories.single().text)
    }

    @Test fun conversationsAreSeparate() {
        val s = MemorySnapshots()
        s.get("a", null, 0, fresh("a"))
        assertEquals("b", s.get("b", null, 1, fresh("b")).memories.single().text)
    }
}
