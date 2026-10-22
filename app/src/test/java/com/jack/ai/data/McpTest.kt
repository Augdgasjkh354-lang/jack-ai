package com.jack.ai.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 在本机起一个假的 MCP 服务，分别按新的 Streamable HTTP 和老式 SSE 走一遍握手、列工具、调工具。 */
class McpTest {
    /** 极简 HTTP 服务器：每个连接一个请求，处理完就关。 */
    private class Req(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String, val out: OutputStream) {
        val responseHeaders = mutableMapOf<String, String>()
        fun header(name: String) = headers[name.lowercase()]
        fun send(code: Int, body: String, type: String = "application/json") {
            val bytes = body.toByteArray()
            val head = StringBuilder("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            responseHeaders.forEach { (k, v) -> head.append("$k: $v\r\n") }
            out.write(head.append("\r\n").toString().toByteArray()); out.write(bytes); out.flush()
        }
        /** 一直开着的事件流（不写长度，靠关连接结束）。 */
        fun startStream() {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
        }
    }

    private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val base get() = "http://127.0.0.1:${socket.localPort}"
    private val routes = mutableMapOf<String, (Req) -> Unit>()
    private val seenAuth = java.util.Collections.synchronizedList(mutableListOf<String?>())

    init {
        Thread {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                Thread { handle(c) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(c: Socket) = c.use {
        val input = c.getInputStream().buffered()
        fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }
        val (method, target) = line().split(' ').let { it[0] to it[1] }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val l = line()
            if (l.isEmpty()) break
            headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
        }
        val len = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(len).also { var n = 0; while (n < len) n += input.read(it, n, len - n) }.decodeToString()
        val req = Req(method, target.substringBefore('?'), target.substringAfter('?', "").ifEmpty { null }, headers, body, c.getOutputStream())
        routes[req.path]?.invoke(req) ?: req.send(404, "")
    }

    @After fun stop() = socket.close()

    private fun reply(id: Int, method: String, params: JsonObject?): String {
        val result = when (method) {
            "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}"""
            "tools/list" -> """{"tools":[{"name":"get_weather","description":"查天气","inputSchema":{"type":"object","properties":{"city":{"type":"string"}}},"annotations":{"readOnlyHint":true}},""" +
                """{"name":"删除订单","description":"删订单","inputSchema":{"type":"object"},"annotations":{"destructiveHint":true}}]}"""
            "tools/call" -> {
                val city = params!!["arguments"]!!.jsonObject["city"]!!.jsonPrimitive.content
                """{"content":[{"type":"text","text":"$city 晴 20 度"}]}"""
            }
            else -> "{}"
        }
        return """{"jsonrpc":"2.0","id":$id,"result":$result}"""
    }

    private fun parse(r: Req) = Json.parseToJsonElement(r.body).jsonObject

    @Test
    fun streamableHttp() = runBlocking {
        routes["/mcp"] = { ex ->
            seenAuth += ex.header("Authorization")
            if (ex.method == "DELETE") ex.send(200, "")
            else {
                val msg = parse(ex)
                val id = msg["id"]?.jsonPrimitive?.int
                if (id == null) ex.send(202, "")
                else {
                    val method = msg["method"]!!.jsonPrimitive.content
                    if (method == "initialize") ex.responseHeaders["Mcp-Session-Id"] = "s1"
                    else assertEquals("s1", ex.header("Mcp-Session-Id"))
                    // 列工具用 SSE 回，其余用 JSON 回，两种都要认
                    if (method == "tools/list") ex.send(200, "event: message\ndata: ${reply(id, method, null)}\n\n", "text/event-stream")
                    else ex.send(200, reply(id, method, msg["params"] as? JsonObject))
                }
            }
        }
        val s = McpServer(id = "abcd1234", name = "天气", url = "$base/mcp", key = "k1")
        val tools = Mcp.listTools(s)
        assertEquals(listOf("get_weather", "删除订单"), tools.map { it.name })
        assertTrue(tools[0].readOnly)
        assertTrue(tools[1].destructive)
        assertEquals("Bearer k1", seenAuth.first())
        val saved = s.copy(tools = tools)
        assertFalse(Mcp.needsConfirm(saved, tools[0]))
        assertTrue(Mcp.needsConfirm(saved, tools[1]))
        // 中文工具名换成编号，名字只含字母数字
        assertEquals(listOf("mcp_abcd_get_weather", "mcp_abcd_t1"), tools.indices.map { Mcp.toolName(saved, it) })
        assertEquals("get_weather", Mcp.find(listOf(saved), "mcp_abcd_get_weather")!!.second.name)
        val text = Mcp.call(saved, tools[0], buildJsonObject { put("city", "杭州") })
        assertEquals("杭州 晴 20 度", text)
    }

    @Test
    fun legacySse() = runBlocking {
        // 每条事件流一个会话编号，回复只从自己的流里发出去（和真的服务一样）
        val outboxes = java.util.concurrent.ConcurrentHashMap<String, LinkedBlockingQueue<String>>()
        val sessions = java.util.concurrent.atomic.AtomicInteger()
        routes["/sse"] = { ex ->
            if (ex.method != "GET") ex.send(405, "")
            else {
                assertEquals("tok", ex.header("X-Api-Key"))
                val sid = "s" + sessions.incrementAndGet()
                val outbox = LinkedBlockingQueue<String>().also { outboxes[sid] = it }
                ex.startStream()
                fun emit(s: String) { ex.out.write(s.toByteArray()); ex.out.flush() }
                runCatching {
                    emit("event: endpoint\ndata: /messages?sid=$sid\n\n")
                    while (true) {
                        val m = outbox.poll(10, TimeUnit.SECONDS) ?: break
                        emit("event: message\ndata: $m\n\n")
                    }
                }
            }
        }
        routes["/messages"] = { ex ->
            val outbox = outboxes[ex.query!!.removePrefix("sid=")]!!
            val msg = parse(ex)
            msg["id"]?.jsonPrimitive?.int?.let { id -> outbox += reply(id, msg["method"]!!.jsonPrimitive.content, msg["params"] as? JsonObject) }
            ex.send(202, "")
        }
        val s = McpServer(name = "老服务", url = "$base/sse", key = "X-Api-Key: tok")
        val tools = Mcp.listTools(s)
        assertEquals(2, tools.size)
        assertEquals("上海 晴 20 度", Mcp.call(s.copy(tools = tools), tools[0], buildJsonObject { put("city", "上海") }))
    }

    @Test
    fun keyInUrlAndNonMcpErrors() = runBlocking {
        routes["/amap"] = { ex ->
            // 高德的做法：Key 在网址里，不对时回一段不是 MCP 格式的 JSON
            if (ex.query != "key=good%2Bkey") ex.send(200, """{"status":"0","info":"INVALID_USER_KEY","infocode":"10001"}""")
            else {
                assertEquals(null, ex.header("Authorization"))
                val msg = parse(ex)
                val id = msg["id"]?.jsonPrimitive?.int
                if (id == null) ex.send(202, "") else ex.send(200, reply(id, msg["method"]!!.jsonPrimitive.content, msg["params"] as? JsonObject))
            }
        }
        val good = McpServer(name = "高德", url = "$base/amap?key={key}", key = "good+key")
        assertEquals(2, Mcp.listTools(good).size)
        val err = runCatching { Mcp.listTools(good.copy(key = "bad")) }.exceptionOrNull()
        assertTrue(err.toString(), err is McpException && "Key 不对" in err.message!!)
    }

    @Test
    fun resultTextHandlesErrorsAndNonText() {
        val r = Json.parseToJsonElement("""{"isError":true,"content":[{"type":"text","text":"城市不存在"},{"type":"image","data":"x","mimeType":"image/png"}]}""").jsonObject
        assertEquals("连接器返回错误：城市不存在\n[图片]", Mcp.resultText(r))
    }
}
