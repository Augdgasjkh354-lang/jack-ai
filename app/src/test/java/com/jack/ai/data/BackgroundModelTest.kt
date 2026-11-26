package com.jack.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 后台模型、子 agent 模型：用户选了就用选的，不再悄悄换成 DeepSeek Flash。 */
class BackgroundModelTest {
    private val gpt = Provider(id = "g", name = "ChatGPT", baseUrl = "http://x", apiKey = "k", models = listOf(ProviderModel("luna"), ProviderModel("sol")))
    private val base = Settings(apiKey = "ds", model = customModelKey("g", "sol"), providers = listOf(gpt))

    @Test fun autoKeepsTheOldBehaviour() {
        assertEquals(MODEL_FLASH, base.backgroundEndpoint()!!.model)
        assertEquals("sol", base.copy(apiKey = "").backgroundEndpoint()!!.model)
        assertNull(base.subagentEndpoint())
    }

    @Test fun autoPrefersGpt6Luna() {
        val luna = gpt.copy(models = gpt.models + ProviderModel("gpt-6-luna"))
        val s = base.copy(providers = listOf(luna), fallbackModels = listOf(MODEL_FLASH))
        val ep = s.subagentEndpoint()!!
        assertEquals("gpt-6-luna", ep.model)
        // 主模型的备用（DeepSeek）不带给子 agent
        assertEquals(emptyList<Endpoint>(), ep.fallbacks)
    }

    @Test fun chosenModelsAreUsed() {
        val s = base.copy(backgroundModel = customModelKey("g", "luna"), subagentModel = customModelKey("g", "sol"))
        assertEquals("luna", s.backgroundEndpoint()!!.model)
        assertEquals(customModelKey("g", "luna"), s.backgroundModelKey())
        assertEquals("sol", s.subagentEndpoint()!!.model)
    }

    @Test fun aGoneChoiceFallsBackToTheMainModelNotDeepSeek() {
        val s = base.copy(backgroundModel = customModelKey("gone", "x"), subagentModel = customModelKey("gone", "x"))
        assertEquals("sol", s.backgroundEndpoint()!!.model)
        assertEquals("sol", s.subagentEndpoint()!!.model)
    }

    @Test fun visionStandInPrefersTheBackgroundModel() {
        val blind = Provider(id = "b", name = "B", baseUrl = "http://y", apiKey = "k", models = listOf(ProviderModel("txt", vision = false)))
        val eyes = gpt.copy(models = listOf(ProviderModel("luna", vision = true), ProviderModel("sol", vision = true)))
        val s = Settings(apiKey = "", model = customModelKey("b", "txt"), providers = listOf(blind, eyes), backgroundModel = customModelKey("g", "sol"))
        assertEquals(customModelKey("g", "sol"), s.visionModelKey())
        assertEquals(customModelKey("g", "luna"), s.copy(backgroundModel = "").visionModelKey())
        assertNull(s.copy(providers = listOf(blind)).visionModelKey())
    }
}
