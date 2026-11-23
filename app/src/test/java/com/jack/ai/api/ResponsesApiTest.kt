package com.jack.ai.api

import com.jack.ai.data.CHATGPT_API_KEY
import com.jack.ai.data.CHATGPT_PROVIDER_URL
import com.jack.ai.data.Provider
import com.jack.ai.data.ProviderModel
import com.jack.ai.data.Settings
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.customModelKey
import com.jack.ai.data.endpoint
import com.jack.ai.data.compressLimit
import com.jack.ai.data.integrations.ChatGptAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用 ChatGPT 账号登录后走的 Responses 接口：请求怎么拼、流怎么读、错误怎么说。 */
class ResponsesApiTest {
    private fun o(s: String) = Json.parseToJsonElement(s).jsonObject

    private val history = listOf(
        o("""{"role":"system","content":"你是 Kavi"}"""),
        o("""{"role":"user","content":[{"type":"text","text":"看图"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,QUJD"}}]}"""),
        o("""{"role":"assistant","content":"我查一下","tool_calls":[{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"q\":\"天气\"}"}}]}"""),
        o("""{"role":"tool","tool_call_id":"call_1","content":"晴"}"""),
        o("""{"role":"system","content":"记得简短"}"""),
        o("""{"role":"user","content":"然后呢"}"""),
    )

    private val settings = Settings(providers = listOf(Provider(id = "g", name = "ChatGPT", baseUrl = CHATGPT_PROVIDER_URL, apiKey = CHATGPT_API_KEY,
        models = listOf(ProviderModel("gpt-6.1-sol", vision = true, reasoning = true)))))
    private val ep = settings.endpoint(customModelKey("g", "gpt-6.1-sol"))!!

    @Test fun chatGptProviderMapsToResponsesEndpoint() {
        assertTrue(ep.chatgpt)
        assertEquals(ApiFormat.RESPONSES, ApiFormat.of(ep))
        assertEquals(ChatGptAuth.BASE_URL, ep.baseUrl)
        assertEquals("gpt-6.1-sol", ep.model)
        // 套餐额度不按 token 收钱
        assertEquals(0.0, ep.price!!.out, 0.0)
        // 按地址找服务的地方（转文字的 OpenAI Whisper）不会误把它当 OpenAI Key
        assertNull(Transcriber.engine(settings.providers))
    }

    @Test fun requestFollowsTheDocumentedRules() {
        val tools = Json.parseToJsonElement("""[{"type":"function","function":{"name":"web_search","description":"搜","parameters":{"type":"object"}}}]""").jsonArray
        val req = ResponsesApi.request(ep, "tok", history, ThinkLevel.HIGH, tools)
        assertEquals("https://api.openai.com/v1/responses", req.url)
        assertEquals("Bearer tok", req.headers["Authorization"])
        val body = o(req.body)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("你是 Kavi", body["instructions"]!!.jsonPrimitive.content)
        // 文档里不认的参数一个都不发
        listOf("temperature", "max_output_tokens", "top_p", "metadata", "user", "previous_response_id").forEach { assertFalse(it, it in body) }
        assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("web_search", tool["name"]!!.jsonPrimitive.content)
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)

        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        // 不能有 system 角色的消息
        assertTrue(input.none { it["role"]?.jsonPrimitive?.content == "system" })
        val user = input[0]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("input_text", user[0]["type"]!!.jsonPrimitive.content)
        assertEquals("input_image", user[1]["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,QUJD", user[1]["image_url"]!!.jsonPrimitive.content)
        assertEquals("output_text", input[1]["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("function_call", input[2]["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[2]["call_id"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[3]["type"]!!.jsonPrimitive.content)
        assertEquals("晴", input[3]["output"]!!.jsonPrimitive.content)
        assertEquals("【系统提示】记得简短", input[4]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun streamIsReadIntoEvents() {
        val p = ResponsesApi.Parser()
        val lines = listOf(
            """event: response.created""",
            """data: {"type":"response.created","response":{"id":"r"}}""",
            """data: {"type":"response.reasoning_summary_part.added","output_index":0}""",
            """data: {"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"想"}""",
            """data: {"type":"response.output_text.delta","output_index":1,"delta":"你好"}""",
            """data: {"type":"response.output_item.added","output_index":2,"item":{"type":"function_call","call_id":"c9","name":"web_search","arguments":""}}""",
            """data: {"type":"response.function_call_arguments.delta","output_index":2,"delta":"{\"q\":"}""",
            """data: {"type":"response.function_call_arguments.delta","output_index":2,"delta":"\"x\"}"}""",
            """data: {"type":"response.output_item.done","output_index":2,"item":{"type":"function_call","call_id":"c9","name":"web_search","arguments":"{\"q\":\"x\"}"}}""",
            """data: {"type":"response.completed","response":{"usage":{"input_tokens":100,"output_tokens":20,"input_tokens_details":{"cached_tokens":40}}}}""",
        )
        val events = lines.flatMap { p.feed(it) }
        assertTrue(p.finished)
        assertEquals(StreamEvent.Text("想", ""), events[0])
        assertEquals(StreamEvent.Text("", "你好"), events[1])
        assertEquals(StreamEvent.ToolCall(0, "c9", "web_search", ""), events[2])
        // 参数分片拼起来，结束时不重复补
        assertEquals("{\"q\":\"x\"}", events.filterIsInstance<StreamEvent.ToolCall>().joinToString("") { it.arguments })
        assertEquals(StreamEvent.Usage(100, 20, 40), events.filterIsInstance<StreamEvent.Usage>().single())
        assertEquals(StreamEvent.Finish("tool_calls"), events.last())
    }

    @Test fun incompleteMeansTruncated() {
        val p = ResponsesApi.Parser()
        val events = p.feed("""data: {"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""")
        assertEquals(StreamEvent.Finish("length"), events.last())
    }

    @Test fun planLimitIsExplained() {
        val p = ResponsesApi.Parser()
        val e = assertThrows(ApiException::class.java) {
            p.feed("""data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"limit"}}}""")
        }
        assertEquals(429, e.code)
        assertTrue(e.message!!.contains("额度用完"))
        // 换备用模型
        assertTrue(Failover.worthSwitching(e))
        assertEquals(403, ResponsesApi.httpError(403, """{"error":{"code":"subscription_sharing_user_not_eligible"}}""").code)
        assertEquals(503, ResponsesApi.httpError(503, """{"detail":"direct routing unavailable"}""").code)
    }

    @Test fun modelListKeepsOnlyListedSlugs() {
        val ids = ResponsesApi.parseModels("""{"models":[{"slug":"gpt-6.1-sol","display_name":"GPT-6.1","visibility":"list"},{"slug":"hidden","visibility":"hide"}]}""")
        assertEquals(listOf("gpt-6.1-sol"), ids)
    }

    @Test fun idTokenClaimsAreRead() {
        fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val jwt = b64("""{"alg":"RS256"}""") + "." + b64("""{"email":"a@b.c","sub":"user-1","nonce":"n"}""") + ".sig"
        val c = ChatGptAuth.claims(jwt)!!
        assertEquals("a@b.c", c["email"]!!.jsonPrimitive.content)
        assertEquals("user-1", c["sub"]!!.jsonPrimitive.content)
        assertNull(ChatGptAuth.claims("not-a-jwt"))
    }

    @Test fun fourStrengthsLikeChatGpt() {
        ResponsesApi.noXhigh = false; ResponsesApi.noReasoning = false
        assertEquals(listOf("low", "medium", "high", "xhigh"), ThinkLevel.entries.map { ResponsesApi.effort(it) })
        assertEquals(listOf("强度·轻度", "强度·中", "强度·高", "强度·极高"), ThinkLevel.entries.map { it.labelFor(ep) })
        // 别的模型照旧叫
        assertEquals("思考·高", ThinkLevel.HIGH.labelFor(null))
        assertEquals("GPT-6.2 Sol", com.jack.ai.data.modelLabel(customModelKey("g", "gpt-6.2-sol")))
        assertEquals("gpt-4o-mini", com.jack.ai.data.modelLabel(customModelKey("g", "gpt-4o-mini")))
    }

    @Test fun rejectedXhighFallsBackToHigh() {
        ResponsesApi.noXhigh = false; ResponsesApi.noReasoning = false
        try {
            val body = o(ResponsesApi.request(ep, "t", history, ThinkLevel.MAX, null).body)
            assertEquals("xhigh", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            val e = ResponsesApi.httpError(400, """{"error":{"code":"unsupported_value","param":"reasoning.effort","message":"bad"}}""")
            assertTrue(e.message!!.contains("改用「高」"))
            assertFalse(ResponsesApi.noReasoning)
            val again = o(ResponsesApi.request(ep, "t", history, ThinkLevel.MAX, null).body)
            assertEquals("high", again["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        } finally {
            ResponsesApi.noXhigh = false; ResponsesApi.noReasoning = false
        }
    }

    @Test fun gptSummaryHeadingsAreUsedDirectly() {
        val r = "**Checking the prices**\n\nI need to compare **two** shops.\n\n**核对两家的报价**\n\n比一下。\n**Checking the prices**"
        assertEquals(listOf("Checking the prices", "核对两家的报价"), com.jack.ai.chat.ThinkSummary.gptHeadings(r))
    }

    @Test fun gptStyleNotesOnlyForChatGpt() {
        val conv = com.jack.ai.data.Conversation()
        fun prompt(s: Settings) = com.jack.ai.chat.PromptBuilder.systemPrompt(s, conv, null, null, emptyList(), emptyList())
        assertTrue("Style notes for this model" in prompt(settings.copy(model = customModelKey("g", "gpt-6.1-sol"))))
        assertFalse("Style notes for this model" in prompt(settings.copy(model = com.jack.ai.data.MODEL_FLASH)))
    }

    @Test fun gptCompressesBeforeTheLongContextPriceLine() {
        val gpt = settings.copy(model = customModelKey("g", "gpt-6.1-sol"), compressTokens = 800_000)
        assertEquals(200_000, gpt.compressLimit())
        assertEquals(800_000, gpt.copy(model = com.jack.ai.data.MODEL_FLASH).compressLimit())
    }

    @Test fun planUsageIsTrackedLocally() {
        val u = com.jack.ai.data.integrations.ChatGptUsage
        u.reset()
        val now = 10L * 24 * 3600_000
        u.record(1000, 400, 200, now - 3600_000)          // 1 小时前
        u.record(2000, 0, 100, now - 2 * 24 * 3600_000)   // 两天前
        val (five, week) = u.windows(now)
        assertEquals(1, five.requests); assertEquals(1200L, five.tokens); assertEquals(400L, five.cached)
        assertEquals(2, week.requests); assertEquals(3300L, week.tokens)
        // 额度用完的错误记下时间
        ResponsesApi.errorOf(o("""{"code":"subscription_sharing_usage_limit_exceeded"}"""))
        assertTrue(u.limitHitAt > 0)
        // 响应头里只挑跟额度有关的
        u.seeHeaders(mapOf("content-type" to "text/event-stream", "x-ratelimit-remaining-requests" to "42"))
        assertEquals(mapOf("x-ratelimit-remaining-requests" to "42"), u.headers)
        u.reset()
    }
}

