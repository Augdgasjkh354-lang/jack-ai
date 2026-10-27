package com.jack.ai.data

import org.junit.Assert.*
import org.junit.Test

class FreePoolTest {
    @Test fun quotaCodeIsParsedFromResponse() {
        assertTrue(isFreeTierOnly("""{"error":{"code":"AllocationQuota.FreeTierOnly","message":"spent"}}"""))
        assertTrue(isFreeTierOnly("""{"code":"AllocationQuota.FreeTierOnly"}"""))
        assertFalse(isFreeTierOnly("""{"error":{"message":"AllocationQuota.FreeTierOnly"}}"""))
        assertFalse(isFreeTierOnly("not json"))
    }
    @Test fun probeResultsRequireValidResponse() {
        assertEquals(1, probeResult(true, """{"choices":[{"message":{"content":"x"}}]}"""))
        assertEquals(2, probeResult(false, """{"error":{"code":"AllocationQuota.FreeTierOnly"}}"""))
        assertEquals(0, probeResult(false, """{"error":{"code":"other"}}"""))
        assertEquals(0, probeResult(true, "{}"))
    }
    @Test fun filtersNonChatModels() {
        assertTrue(isChatModel("qwen-plus"))
        listOf("text-embedding-v3", "qwen-tts", "qwen-asr", "wan-image", "qwen-vl").forEach { assertFalse(isChatModel(it)) }
    }
    @Test fun rotatesAndReturnsEmptyForFallback() {
        val p = Provider(id = "p", name = "通义", baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1", apiKey = "key")
        val settings = Settings(providers = listOf(p))
        val pool = FreePoolState(models = listOf(FreeModel("p", "a"), FreeModel("p", "b"), FreeModel("p", "c", 1)))
        assertEquals(listOf("a", "b"), freeCandidates(pool, settings, 0).map { it.model })
        assertEquals(listOf("b", "a"), freeCandidates(pool, settings, 1).map { it.model })
        val empty = freeCandidates(pool.copy(models = pool.models.map { it.copy(exhaustedAt = 1) }), settings, 0)
        assertTrue(empty.isEmpty())
        val fallback = Endpoint("https://example.com", "key", "backup", false, false, false)
        assertEquals(fallback, chooseFreeOrFallback(empty, fallback))
        assertNull(chooseFreeOrFallback(empty, null))
        assertEquals("a", chooseFreeOrFallback(freeCandidates(pool, settings, 0), fallback)?.model)
    }
}
