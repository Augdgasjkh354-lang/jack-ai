package com.jack.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftParseTest {
    private fun args(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun parsesAndResolvesImages() {
        val (d, warns) = parseDraft(args("""{"platform":"xhs","title":"三步做好封面","body":"正文","tags":["#封面","设计","设计"],"images":["a.png","b.png"]}""")) { "/ws/$it" }.getOrThrow()
        assertEquals(listOf("封面", "设计"), d.tags)
        assertEquals(listOf("/ws/a.png", "/ws/b.png"), d.images)
        assertTrue(warns.toString(), warns.isEmpty())
        assertEquals("三步做好封面\n\n正文\n\n#封面 #设计", draftText(d))
    }

    @Test fun warnsOnXhsLimits() {
        val (_, warns) = parseDraft(args("""{"platform":"xhs","title":"这是一个特别特别特别特别特别长的小红书标题啊","body":"x","images":["missing.png"]}""")) { null }.getOrThrow()
        assertTrue(warns.any { "20 个字" in it })
        assertTrue(warns.any { "missing.png" in it })
        assertTrue(warns.any { "至少要一张图" in it })
    }

    @Test fun needsBodyAndDefaultsPlatform() {
        assertTrue(parseDraft(args("""{"platform":"xhs"}""")) { it }.isFailure)
        assertEquals("other", parseDraft(args("""{"platform":"tiktok","body":"hi"}""")) { it }.getOrThrow().first.platform)
    }
}
