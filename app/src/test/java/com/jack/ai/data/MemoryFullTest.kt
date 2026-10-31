package com.jack.ai.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFullTest {
    private val big = List(10) { "第${it}条：" + "记".repeat(40) }

    @Test fun refusesGrowthPastBudget() {
        val budget = MemoryOps.tokensOf(big)
        val note = MemoryOps.fullNote(big, big + "新的一条", budget, "全局", canDeep = true)
        assertNotNull(note)
        assertTrue(note!!.contains("to_deep") && note.contains("第0条"))
    }

    @Test fun allowsShrinkAndUnderBudget() {
        val budget = MemoryOps.tokensOf(big) - 50 // 本来就超了
        assertNull(MemoryOps.fullNote(big, big.drop(1), budget, "本对话"))
        assertNull(MemoryOps.fullNote(big.take(2), big.take(3), 10_000, "本对话"))
        // 文件记忆没有深处，不提 to_deep
        assertTrue(!MemoryOps.fullNote(big, big + "x", budget, "本对话")!!.contains("to_deep"))
    }
}
