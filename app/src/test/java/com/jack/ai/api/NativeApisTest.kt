package com.jack.ai.api

import com.jack.ai.data.Endpoint
import com.jack.ai.data.ThinkLevel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class NativeApisTest {
    private fun o(s: String) = Json.parseToJsonElement(s).jsonObject

    private val history = listOf(
        o("""{"role":"system","content":"你是 Kavi"}"""),
        o("""{"role":"user","content":[{"type":"text","text":"看图"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,QUJD"}}]}"""),
        o("""{"role":"assistant","content":"我查一下","reasoning_content":"想想","tool_calls":[{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"q\":\"天气\"}"}}]}"""),
        o("""{"role":"tool","tool_call_id":"call_1","content":"晴"}"""),
        o("""{"role":"system","content":"记得简短"}"""),
        o("""{"role":"user","content":"然后呢"}"""),
    )

    @Test fun formatIsRecognisedFromTheAddress() {
        assertEquals(ApiFormat.ANTHROPIC, ApiFormat.of("https://api.anthropic.com/v1"))
        assertEquals(ApiFormat.ANTHROPIC, ApiFormat.of("https://api.deepseek.com/anthropic"))
        assertEquals(ApiFormat.ANTHROPIC, ApiFormat.of("https://open.bigmodel.cn/api/anthropic/"))
        assertEquals(ApiFormat.GEMINI, ApiFormat.of("https://generativelanguage.googleapis.com/v1beta"))
        assertEquals(ApiFormat.OPENAI, ApiFormat.of("https://api.moonshot.cn/v1"))
        assertEquals(ApiFormat.OPENAI, ApiFormat.of(Endpoint("https://api.anthropic.com", "k", "m", deepseek = true, vision = false, reasoning = false)))
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicApi.url("https://api.anthropic.com/v1", "messages"))
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", AnthropicApi.url("https://api.deepseek.com/anthropic", "messages"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta", GeminiApi.base("https://generativelanguage.googleapis.com"))
    }

    @Test fun anthropicConversionAlternatesRolesAndMovesToolResultsIntoUserTurns() {
        val (system, turns) = AnthropicApi.convert(history)
        assertEquals("你是 Kavi", system)
        assertEquals(listOf("user", "assistant", "user"), turns.map { it["role"]!!.jsonPrimitive.content })
        val first = turns[0]["content"]!!.jsonArray
        assertEquals("image", first[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", first[1].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        val assistant = turns[1]["content"]!!.jsonArray
        assertEquals(listOf("text", "tool_use"), assistant.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("天气", assistant[1].jsonObject["input"]!!.jsonObject["q"]!!.jsonPrimitive.content)
        // 思考内容不回传
        assertFalse(turns.toString().contains("想想"))
        val last = turns[2]["content"]!!.jsonArray
        assertEquals("tool_result", last[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", last[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        assertTrue(last[1].toString().contains("【系统提示】记得简短"))
        assertTrue(last[2].toString().contains("然后呢"))
    }

    @Test fun anthropicRequestUsesAdaptiveThinkingAndNoTemperatureOnTheCloud() {
        val ep = Endpoint("https://api.anthropic.com/v1", "k", "claude-opus-5-5", deepseek = false, vision = true, reasoning = true)
        val tools = Json.parseToJsonElement("""[{"type":"function","function":{"name":"web_search","description":"搜","parameters":{"type":"object","properties":{"q":{"type":"string"}}}}}]""").jsonArray
        val on = AnthropicApi.request(ep, history, ThinkLevel.HIGH, tools, stream = true)
        val body = o(on.body)
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse("temperature" in body)
        assertEquals("web_search", body["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("2026-10-21", on.headers["anthropic-version"])
        assertFalse("Authorization" in on.headers)
        val off = o(AnthropicApi.request(ep, history, ThinkLevel.OFF, null, stream = true).body)
        assertFalse("thinking" in off)
        assertEquals("low", off["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        // 别家的兼容地址：老版本号、不带官方专有字段
        val compat = AnthropicApi.request(ep.copy(baseUrl = "https://api.deepseek.com/anthropic"), history, ThinkLevel.OFF, tools, stream = true)
        assertEquals("2023-06-01", compat.headers["anthropic-version"])
        assertFalse("fallbacks" in o(compat.body))
        assertFalse(compat.body.contains("eager_input_streaming"))
    }

    @Test fun foreignToolIdsAreNormalisedConsistently() {
        val odd = "gm1-abcd~c2lnbmF0dXJl+/="
        val msgs = listOf(
            o("""{"role":"user","content":"hi"}"""),
            o("""{"role":"assistant","content":"","tool_calls":[{"id":"$odd","type":"function","function":{"name":"x","arguments":""}}]}"""),
            o("""{"role":"tool","tool_call_id":"$odd","content":"ok"}"""),
        )
        val (_, turns) = AnthropicApi.convert(msgs)
        val useId = turns[1]["content"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        val resultId = turns[2]["content"]!!.jsonArray[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content
        assertEquals(useId, resultId)
        assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(useId))
        // 结尾是助手的补一句用户的话（新模型不接受预填）
        assertEquals("user", AnthropicApi.convert(msgs.take(2)).second.last()["role"]!!.jsonPrimitive.content)
    }

    @Test fun anthropicStreamBecomesTextThinkingToolCallsAndUsage() {
        val p = AnthropicApi.Parser()
        val lines = listOf(
            """data: {"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":90,"output_tokens":1}}}""",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"嗯"}}""",
            """data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"好"}}""",
            """data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"web_search","input":{}}}""",
            """data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"q\":"}}""",
            """data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"x\"}"}}""",
            """data: {"type":"content_block_stop","index":2}""",
            """data: {"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"toolu_2","name":"now","input":{}}}""",
            """data: {"type":"content_block_stop","index":3}""",
            """data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}""",
            """data: {"type":"message_stop"}""",
        )
        val events = lines.flatMap { p.feed(it) }
        assertTrue(p.finished)
        assertEquals(StreamEvent.Text("嗯", ""), events[0])
        assertEquals(StreamEvent.Text("", "好"), events[1])
        val calls = events.filterIsInstance<StreamEvent.ToolCall>()
        assertEquals("{\"q\":\"x\"}", calls.filter { it.index == 2 }.joinToString("") { it.arguments })
        assertEquals("{}", calls.filter { it.index == 3 }.joinToString("") { it.arguments })
        assertEquals(StreamEvent.Usage(100, 20, 90), events.filterIsInstance<StreamEvent.Usage>().single())
        assertEquals(StreamEvent.Finish("tool_calls"), events.last())
    }

    @Test fun anthropicErrorEventsKeepARetryableCode() {
        val e = assertThrows(ApiException::class.java) {
            AnthropicApi.Parser().feed("""data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        }
        assertEquals(503, e.code)
        assertTrue(Failover.worthSwitching(e))
    }

    @Test fun geminiConversionCarriesSignaturesAndFunctionNames() {
        val withSig = history.map {
            if (it["role"]!!.jsonPrimitive.content == "assistant") o(it.toString().replace("call_1", "gm1-x~U0lH"))
            else if (it["role"]!!.jsonPrimitive.content == "tool") o(it.toString().replace("call_1", "gm1-x~U0lH")) else it
        }
        val (system, contents) = GeminiApi.convert(withSig)
        assertEquals("你是 Kavi", system)
        assertEquals(listOf("user", "model", "user"), contents.map { it["role"]!!.jsonPrimitive.content })
        val call = contents[1]["parts"]!!.jsonArray[1].jsonObject
        assertEquals("web_search", call["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("U0lH", call["thoughtSignature"]!!.jsonPrimitive.content)
        val resp = contents[2]["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("web_search", resp["name"]!!.jsonPrimitive.content)
        assertEquals("QUJD", contents[0]["parts"]!!.jsonArray[1].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        val req = GeminiApi.request(Endpoint("https://generativelanguage.googleapis.com/v1beta", "k", "gemini-3-pro", deepseek = false, vision = true, reasoning = true),
            history, ThinkLevel.HIGH, 1f, null, stream = true)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3-pro:streamGenerateContent?alt=sse", req.url)
        assertEquals("high", o(req.body)["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
    }

    @Test fun geminiStreamKeepsTheSignatureInTheToolCallId() {
        val p = GeminiApi.Parser()
        val a = p.feed("""data: {"candidates":[{"content":{"role":"model","parts":[{"text":"想","thought":true},{"text":"好的"}]}}]}""")
        val b = p.feed("""data: {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"now","args":{"tz":"cn"}},"thoughtSignature":"U0lH"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":5,"thoughtsTokenCount":3,"cachedContentTokenCount":20}}""")
        assertEquals(listOf(StreamEvent.Text("想", ""), StreamEvent.Text("", "好的")), a)
        val call = b.filterIsInstance<StreamEvent.ToolCall>().single()
        assertTrue(call.id.endsWith("~U0lH"))
        assertEquals("{\"tz\":\"cn\"}", call.arguments)
        assertEquals(StreamEvent.Usage(50, 8, 20), b.filterIsInstance<StreamEvent.Usage>().single())
        assertEquals(StreamEvent.Finish("tool_calls"), b.last())
        assertTrue(p.finished)
        assertThrows(ApiException::class.java) { GeminiApi.Parser().feed("""data: {"promptFeedback":{"blockReason":"SAFETY"}}""") }
    }

    @Test fun modelListsOfBothServicesAreRead() {
        assertEquals(listOf("claude-haiku-5-5", "claude-opus-5-5"), AnthropicApi.parseModels("""{"data":[{"id":"claude-opus-5-5"},{"id":"claude-haiku-5-5"}]}"""))
        assertEquals(listOf("gemini-3-pro"), GeminiApi.parseModels("""{"models":[{"name":"models/gemini-3-pro","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding-1","supportedGenerationMethods":["embedContent"]}]}"""))
    }

    /** 走一遍真实的流式请求：本机假服务，地址以 /anthropic 结尾按 Anthropic 格式发。 */
    @Test(timeout = 10_000) fun clientStreamsFromAnAnthropicCompatibleServer() {
        val sse = listOf(
            """event: message_start""", """data: {"type":"message_start","message":{"usage":{"input_tokens":7,"output_tokens":1}}}""", "",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}""", "",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""", "",
            """data: {"type":"message_stop"}""", "",
        ).joinToString("\n")
        val server = ServerSocket(0)
        var path = ""
        var sentBody = ""
        thread(isDaemon = true) {
            server.accept().use { s ->
                // 按字节读：请求体里有中文，Content-Length 数的是字节
                val input = java.io.DataInputStream(s.getInputStream())
                fun line(): String {
                    val b = java.io.ByteArrayOutputStream()
                    while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }
                    return b.toString(Charsets.UTF_8.name())
                }
                path = line()
                var len = 0
                while (true) { val l = line(); if (l.isEmpty()) break; if (l.startsWith("Content-Length:", true)) len = l.substringAfter(':').trim().toInt() }
                val buf = ByteArray(len)
                input.readFully(buf)
                sentBody = String(buf, Charsets.UTF_8)
                val bytes = sse.toByteArray()
                s.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
            }
        }
        try {
            val ep = Endpoint("http://127.0.0.1:${server.localPort}/anthropic", "k", "glm-5", deepseek = false, vision = false, reasoning = false)
            val events = runBlocking { DeepSeekClient().stream(ep, history, ThinkLevel.OFF, 1f).toList() }
            assertTrue(path.startsWith("POST /anthropic/v1/messages"))
            assertTrue(Json.parseToJsonElement(sentBody).jsonObject["messages"] is JsonArray)
            assertEquals(StreamEvent.Text("", "你好"), events[0])
            assertEquals(StreamEvent.Usage(7, 2, 0), events[1])
            assertEquals(StreamEvent.Finish("stop"), events[2])
        } finally { server.close() }
    }
}
