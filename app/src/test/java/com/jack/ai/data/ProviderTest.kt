package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTest {
    private val kimi = Provider(
        id = "p1", name = "Kimi", baseUrl = "https://api.moonshot.cn/v1", apiKey = "sk-k",
        models = listOf(ProviderModel("kimi-k2", vision = false, reasoning = true), ProviderModel("openai/gpt-4o:free", vision = true)),
    )

    @Test
    fun deepseekEndpoint() {
        val s = Settings(apiKey = "sk-d", model = MODEL_PRO)
        val ep = s.endpoint()!!
        assertTrue(ep.deepseek)
        assertFalse(ep.vision)
        assertEquals("sk-d", ep.apiKey)
    }

    @Test
    fun customEndpointKeepsColonsInModelId() {
        val s = Settings(providers = listOf(kimi), model = customModelKey("p1", "openai/gpt-4o:free"))
        val ep = s.endpoint()!!
        assertFalse(ep.deepseek)
        assertEquals("openai/gpt-4o:free", ep.model)
        assertTrue(ep.vision)
        assertEquals("https://api.moonshot.cn/v1", ep.baseUrl)
        assertEquals("gpt-4o:free", modelLabel(s.model))
    }

    @Test
    fun missingProviderGivesNull() {
        assertNull(Settings(model = customModelKey("gone", "x")).endpoint())
    }

    @Test
    fun backgroundPrefersDeepSeekFlash() {
        val withKey = Settings(apiKey = "sk-d", providers = listOf(kimi), model = customModelKey("p1", "kimi-k2"))
        assertEquals(MODEL_FLASH, withKey.backgroundEndpoint()!!.model)
        val noKey = withKey.copy(apiKey = "")
        assertEquals("kimi-k2", noKey.backgroundEndpoint()!!.model)
        assertNull(Settings().backgroundEndpoint())
    }

    @Test
    fun choicesHideDeepSeekWithoutKeyWhenOtherServicesExist() {
        assertEquals(2, Settings().modelChoices().size)
        val only = Settings(providers = listOf(kimi)).modelChoices()
        assertEquals(listOf("Kimi", "Kimi"), only.map { it.group })
        assertEquals(4, Settings(apiKey = "sk", providers = listOf(kimi)).modelChoices().size)
    }

    @Test
    fun guesses() {
        assertTrue(guessVision("qwen-vl-max"))
        assertTrue(guessVision("gpt-4o-mini"))
        assertFalse(guessVision("moonshot-v1-8k"))
        assertTrue(guessReasoning("o3-mini"))
        assertTrue(guessReasoning("deepseek-r1"))
        assertTrue(guessReasoning("qwq-32b"))
        assertFalse(guessReasoning("gpt-4o"))
        assertFalse(guessReasoning("glm-4-flash"))
    }
}
