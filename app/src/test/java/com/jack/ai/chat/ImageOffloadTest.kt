package com.jack.ai.chat

import com.jack.ai.api.ApiException
import com.jack.ai.api.StreamEvent
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** 思路来自 deepseek-ai/deepseek-harness packages/compaction/compaction-image-offload/，MIT。 */
class ImageOffloadTest {
    private fun messages() = mutableListOf(buildJsonObject {
        put("role", "user"); put("content", buildJsonArray {
            (1..6).forEach { i -> add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "shot$i") }) }) }
        })
    })
    @Test fun keepsNewestThreeAndMarksOldImages() {
        val msgs = messages(); assertTrue(ImageOffload.reduce(msgs))
        val out = msgs.toString()
        assertFalse(out.contains("shot3")); assertTrue(out.contains("shot4")); assertTrue(out.contains("shot6")); assertTrue(out.contains("[这里原来有一张截图，已省略]"))
    }
    @Test fun retriesImageRejectionOnlyOnce() = runBlocking {
        var calls = 0
        val agent = Agent({ _, _ -> flow<StreamEvent> { calls++; throw ApiException("too many images", 400) } })
        try { agent.run(messages(), null, { 1 }, { _, _ -> "" }); fail("仍被拒绝时应报错") }
        catch (e: ApiException) { assertEquals(2, calls) }
        assertFalse(ImageOffload.rejected(ApiException("invalid image format", 400)))
    }
    @Test fun sizeBudgetDropsOlderLargeImages() {
        val msgs = messages(); ImageOffload.reduce(msgs, maxChars = 1)
        assertEquals(1, (msgs[0]["content"] as JsonArray).count { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" })
        assertTrue(msgs.toString().contains("shot6"))
    }
    @Test fun generic413OnlyCountsWhenImagesDominate() {
        val e = ApiException("Request Entity Too Large", 413)
        val textHeavy = listOf(buildJsonObject { put("role", "tool"); put("content", "字".repeat(50_000)) }) + messages()
        // 大头是工具结果：删图也没用，不当成图片问题
        assertFalse(ImageOffload.rejected(e, textHeavy))
        val imageHeavy = listOf(buildJsonObject { put("role", "user"); put("content", buildJsonArray {
            add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64," + "A".repeat(50_000)) }) })
        }) })
        assertTrue(ImageOffload.rejected(e, imageHeavy))
        // 明说是图片的照旧算
        assertTrue(ImageOffload.rejected(ApiException("image too large", 413), textHeavy))
    }
}
