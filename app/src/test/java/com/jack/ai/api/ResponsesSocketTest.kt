package com.jack.ai.api

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** ChatGPT 常连接：第一步发全量，之后只发新内容并接上一步；接不上就发全量。 */
class ResponsesSocketTest {
    private fun o(s: String) = Json.parseToJsonElement(s).jsonObject
    private val dev = o("""{"type":"message","role":"developer","content":[{"type":"input_text","text":"你是 Kavi"}]}""")
    private val u1 = o("""{"type":"message","role":"user","content":[{"type":"input_text","text":"查天气"}]}""")
    private val call = o("""{"type":"function_call","call_id":"c1","name":"web_search","arguments":"{}"}""")
    private val out = o("""{"type":"function_call_output","call_id":"c1","output":"晴"}""")
    private val head = o("""{"model":"gpt","store":false}""")

    @Test fun deltaKeepsOnlyNewItems() {
        val chain = ResponsesSocket.Chain("r1", head, listOf(dev, u1))
        assertEquals(listOf(out), ResponsesSocket.delta(chain, head, listOf(dev, u1, call, out)))
        // 参数变了、前面改过、没有上一步的输出、没有新内容：都发全量
        assertNull(ResponsesSocket.delta(chain, o("""{"model":"gpt2"}"""), listOf(dev, u1, call, out)))
        assertNull(ResponsesSocket.delta(chain, head, listOf(dev, out, call, out)))
        assertNull(ResponsesSocket.delta(chain, head, listOf(dev, u1, out)))
        assertNull(ResponsesSocket.delta(chain, head, listOf(dev, u1, call)))
        assertNull(ResponsesSocket.delta(null, head, listOf(dev, u1, call, out)))
    }

    private lateinit var server: MockWebServer
    private val got: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())

    @Before fun setUp() {
        ResponsesSocket.reset(); ResponsesSocket.enabled = true
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            var n = 0
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = o(text); got += m
                n++
                // 带「慢」字的：半秒后才回，模拟一个还在跑的回答
                if ("慢" in text) {
                    val id = "r$n"
                    Thread { Thread.sleep(500); webSocket.send("""{"type":"response.completed","response":{"id":"$id","usage":{"input_tokens":1,"output_tokens":1}}}""") }.start()
                    return
                }
                val prev = m["previous_response_id"]?.jsonPrimitive?.content
                if (prev == "gone") {
                    webSocket.send("""{"type":"error","status":400,"error":{"type":"invalid_request_error","code":"previous_response_not_found","message":"x"}}""")
                    return
                }
                webSocket.send("""{"type":"response.created","response":{"id":"r$n"}}""")
                webSocket.send("""{"type":"response.output_text.delta","output_index":0,"delta":"好$n"}""")
                webSocket.send("""{"type":"response.completed","response":{"id":"r$n","usage":{"input_tokens":100,"output_tokens":5,"input_tokens_details":{"cached_tokens":90}}}}""")
            }
        }))
        server.start()
    }

    @After fun tearDown() { ResponsesSocket.reset(); server.shutdown() }

    private fun req(vararg input: JsonElement) = NativeRequest(
        server.url("/v1/responses").toString(), mapOf("Authorization" to "Bearer t"),
        buildJsonObject { put("model", "gpt"); put("store", false); put("stream", true); put("input", JsonArray(input.toList())) }.toString(),
    )

    private fun send(r: NativeRequest): List<StreamEvent> = runBlocking {
        val evs = mutableListOf<StreamEvent>()
        ResponsesSocket.send(OkHttpClient(), r, 5_000) { evs += it }
        evs
    }

    @Test fun secondStepSendsOnlyTheNewItems() {
        val first = send(req(dev, u1))
        assertEquals(StreamEvent.Text("", "好1"), first[0])
        assertTrue(first.any { it is StreamEvent.Usage })
        send(req(dev, u1, call, out))
        assertEquals(2, got.size)
        assertEquals("response.create", got[0]["type"]!!.jsonPrimitive.content)
        assertFalse("stream" in got[0])
        assertNull(got[0]["previous_response_id"])
        assertEquals(2, got[0]["input"]!!.jsonArray.size)
        assertEquals("r1", got[1]["previous_response_id"]!!.jsonPrimitive.content)
        assertEquals(listOf<JsonElement>(out), got[1]["input"]!!.jsonArray.toList())
        assertEquals("gpt:ws", ResponsesApi.lastVerdict)
    }

    @Test fun lostPreviousResponseResendsEverything() {
        send(req(dev, u1))
        // 模拟连接里的缓存丢了上一步
        val key = ResponsesSocket.keyOf(listOf(dev, u1))
        ResponsesSocket.forTest(key, ResponsesSocket.Chain("gone", o("""{"model":"gpt","store":false}"""), listOf(dev, u1)))
        val evs = send(req(dev, u1, call, out))
        assertEquals(3, got.size)
        assertEquals(4, got[2]["input"]!!.jsonArray.size)
        assertNull(got[2]["previous_response_id"])
        assertTrue(evs.contains(StreamEvent.Text("", "好3")))
    }

    @Test fun refusedHandshakeFallsBackToHttp() {
        val s = MockWebServer().apply { enqueue(MockResponse().setResponseCode(404)); start() }
        val r = NativeRequest(s.url("/v1/responses").toString(), mapOf("Authorization" to "Bearer t2"), """{"input":[]}""")
        var unavailable = false
        runBlocking {
            try { ResponsesSocket.send(OkHttpClient(), r, 5_000) {} } catch (e: ResponsesSocket.Unavailable) { unavailable = true }
        }
        assertTrue(unavailable)
        assertFalse(ResponsesSocket.enabled)
        s.shutdown()
    }

    @Test fun newTokenMeansFullSendAndSaysWhy() {
        send(req(dev, u1))
        // 令牌换了：连接重开，链没了，从头发全量并说明原因
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                got += o(text)
                webSocket.send("""{"type":"response.completed","response":{"id":"z1","usage":{"input_tokens":10,"output_tokens":1}}}""")
            }
        }))
        val r = NativeRequest(server.url("/v1/responses").toString(), mapOf("Authorization" to "Bearer NEW"),
            buildJsonObject { put("model", "gpt"); put("store", false); put("stream", true); put("input", JsonArray(listOf(dev, u1, call, out))) }.toString())
        ResponsesApi.lastVerdict = "gpt:ok"
        send(r)
        assertEquals(4, got.last()["input"]!!.jsonArray.size)
        assertEquals("gpt:wslost:token", ResponsesApi.lastVerdict)
    }

    @Test fun parallelRequestsEachGetTheirOwnConnection() {
        // 第一条连接被一个慢的回答占着（服务端半秒后才回）；这时另一个请求新开第二条，不退回 HTTP
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                got += o(text)
                webSocket.send("""{"type":"response.completed","response":{"id":"b1","usage":{"input_tokens":10,"output_tokens":1}}}""")
            }
        }))
        val slow = o("""{"type":"message","role":"user","content":[{"type":"input_text","text":"慢"}]}""")
        val u2 = o("""{"type":"message","role":"user","content":[{"type":"input_text","text":"写周报"}]}""")
        send(req(dev, u2))
        runBlocking {
            val a = async(kotlinx.coroutines.Dispatchers.IO) { ResponsesSocket.send(OkHttpClient(), req(dev, slow), 5_000) {} }
            Thread.sleep(100)
            ResponsesSocket.send(OkHttpClient(), req(dev, u1), 5_000) {}
            a.await()
        }
        assertEquals(2, ResponsesSocket.size())
        assertEquals(3, got.size)
    }
}
