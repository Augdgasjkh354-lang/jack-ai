package com.jack.ai.api

import com.jack.ai.data.Endpoint
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 百炼 Qwen3、Kimi 的思考开关按各自的参数发，认不出的不发。 */
class ThinkDialectTest {
    private fun ep(url: String, model: String, deepseek: Boolean = false) = Endpoint(url, "k", model, deepseek, vision = false, reasoning = false)
    private val bailian = "https://dashscope.aliyuncs.com/compatible-mode/v1"

    @Test
    fun recognisesHybridModels() {
        assertEquals(ThinkDialect.Kind.ENABLE_THINKING, ThinkDialect.of(ep(bailian, "qwen3.5-plus")))
        assertEquals(ThinkDialect.Kind.ENABLE_THINKING, ThinkDialect.of(ep(bailian, "qwen3-32b")))
        assertEquals(ThinkDialect.Kind.ENABLE_THINKING, ThinkDialect.of(ep(bailian, "qwen-plus-latest")))
        assertEquals(ThinkDialect.Kind.ENABLE_THINKING, ThinkDialect.of(ep(bailian, "kimi-k2.5")))
        assertEquals(ThinkDialect.Kind.KIMI, ThinkDialect.of(ep("https://api.moonshot.cn/v1", "kimi-k2.6")))
    }

    @Test
    fun leavesOthersAlone() {
        // 只思考的、不思考的、编程的：没有开关，发了会报错
        assertEquals(ThinkDialect.Kind.NONE, ThinkDialect.of(ep(bailian, "qwen3-235b-a22b-thinking-2507")))
        assertEquals(ThinkDialect.Kind.NONE, ThinkDialect.of(ep(bailian, "qwen3-coder-plus")))
        assertEquals(ThinkDialect.Kind.NONE, ThinkDialect.of(ep(bailian, "qwen2.5-72b-instruct")))
        assertEquals(ThinkDialect.Kind.NONE, ThinkDialect.of(ep("https://api.moonshot.cn/v1", "moonshot-v1-8k")))
        assertEquals(ThinkDialect.Kind.NONE, ThinkDialect.of(ep("https://openrouter.ai/api/v1", "qwen/qwen3-32b")))
        assertEquals(ThinkDialect.Kind.NONE, ThinkDialect.of(ep("https://api.deepseek.com", "deepseek-chat", deepseek = true)))
    }

    @Test
    fun writesTheRightField() {
        assertEquals("""{"enable_thinking":false}""", buildJsonObject { ThinkDialect.put(this, ThinkDialect.Kind.ENABLE_THINKING, false) }.toString())
        assertEquals("""{"thinking":{"type":"enabled"}}""", buildJsonObject { ThinkDialect.put(this, ThinkDialect.Kind.KIMI, true) }.toString())
    }
}
