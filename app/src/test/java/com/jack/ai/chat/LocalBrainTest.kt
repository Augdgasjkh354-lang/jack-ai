package com.jack.ai.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBrainTest {
    private fun v(vararg x: Float) = x

    @Test
    fun closerToHardExamplesMeansHard() {
        val hard = listOf(v(1f, 0f), v(0.9f, 0.1f), v(0.8f, 0.2f))
        val easy = listOf(v(0f, 1f), v(0.1f, 0.9f), v(0.2f, 0.8f))
        assertTrue(LocalBrain.decide(v(0.95f, 0.05f), hard, easy))
        assertFalse(LocalBrain.decide(v(0.05f, 0.95f), hard, easy))
    }

    @Test
    fun examplesAreBalanced() {
        // 两组例句一样多，免得「最像的三句」偏向例句多的一边
        assertEquals(LocalBrain.HARD.size, LocalBrain.EASY.size)
    }
}
