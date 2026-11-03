package com.jack.ai.data

import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class ProviderReasoningAuditTest {
    @Ignore("audit: 复现")
    @Test
    fun qwenHybridThinkingIsDetected() {
        assertTrue(guessReasoning("qwen3.5-plus"))
    }

    @Ignore("audit: 复现")
    @Test
    fun kimiHybridThinkingIsDetected() {
        assertTrue(guessReasoning("kimi-k2.5"))
    }
}
