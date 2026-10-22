package com.jack.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 连接器上的一个工具。readOnly / destructive 是服务自己标的（没标就都是 false）。 */
@Serializable
data class McpTool(
    val name: String,
    val description: String = "",
    /** 参数的 JSON Schema，原样存着。 */
    val schema: String = "",
    val readOnly: Boolean = false,
    val destructive: Boolean = false,
)

/**
 * 连接器：一个 MCP 服务（网址 + 可选的 Key）。tools 是上次连上时拿到的工具列表，
 * 存下来是为了每次请求的工具说明不变（缓存才能命中），也不用每轮都先连一次。
 */
@Serializable
data class McpServer(
    val id: String = newId(),
    val name: String,
    val url: String,
    /** 「xxx」当 Bearer 令牌；「Header-Name: value」当自定义请求头。不进备份。 */
    val key: String = "",
    val enabled: Boolean = true,
    /** 每次调用都先问用户。 */
    val askFirst: Boolean = false,
    val tools: List<McpTool> = emptyList(),
)

class McpException(message: String) : Exception(message)

/**
 * MCP 客户端：支持现在通用的 Streamable HTTP，连不上时退回老式的 SSE（不少国内托管的服务还是这种）。
 * 每次用的时候连一次、用完就断，不常驻后台。
 */
object Mcp {
    const val PREFIX = "mcp_"
    /** 网址里的这个占位符会换成 Key（高德这类把 Key 放在网址里的服务）。 */
    const val KEY_SLOT = "{key}"

    /** 一键添加的常用连接器：名字、网址、去哪申请 Key。 */
    data class Preset(val name: String, val url: String, val keyHelp: String)

    val PRESETS = listOf(
        Preset("高德地图", "https://mcp.amap.com/mcp?key={key}",
            "到高德开放平台 lbs.amap.com 登录 → 控制台 → 应用管理 → 创建应用 → 添加 Key，服务平台选「Web 服务」，免费额度够个人用。"),
    )
    private const val PROTOCOL = "2025-06-18"
    private val json = Json { ignoreUnknownKeys = true }

    /** 给模型的工具名：mcp_ + 服务编号前 4 位 + 原名（只留字母数字，接口只认这些），最长 64。 */
    fun toolName(server: McpServer, index: Int): String {
        val raw = server.tools[index].name
        val clean = raw.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
        val base = if (clean.any { it.isLetterOrDigit() }) clean else "t$index"
        return (PREFIX + server.id.filter { it.isLetterOrDigit() }.take(4) + "_" + base).take(64)
    }

    fun active(s: Settings): List<McpServer> = s.mcpServers.filter { it.enabled && it.tools.isNotEmpty() }

    /** 做了可能收不回的事，或者用户要求每次都问。 */
    fun needsConfirm(server: McpServer, tool: McpTool) = server.askFirst || (tool.destructive && !tool.readOnly)

    /** 给模型的工具定义。filter 用来在后台只留下不用确认的工具。 */
    fun toolDefs(servers: List<McpServer>, filter: (McpServer, McpTool) -> Boolean = { _, _ -> true }): List<JsonObject> =
        servers.flatMap { server ->
            server.tools.indices.filter { filter(server, server.tools[it]) }.map { i ->
                val t = server.tools[i]
                val params = runCatching { json.parseToJsonElement(t.schema).jsonObject }.getOrNull()
                    ?.takeIf { it["type"]?.let { ty -> (ty as? JsonPrimitive)?.content == "object" } ?: false }
                    ?: buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) }
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", toolName(server, i))
                        put("description", "【连接器「${server.name}」】" + t.description.take(1000))
                        put("parameters", params)
                    })
                }
            }
        }

    /** 按给模型的工具名找回是哪个服务的哪个工具。 */
    fun find(servers: List<McpServer>, name: String): Pair<McpServer, McpTool>? {
        if (!name.startsWith(PREFIX)) return null
        for (s in servers) for (i in s.tools.indices) if (toolName(s, i) == name) return s to s.tools[i]
        return null
    }

    /** 连上去拿工具列表（添加、刷新连接器时用）。 */
    suspend fun listTools(server: McpServer): List<McpTool> = withContext(Dispatchers.IO) {
        Session(server).use { s ->
            val out = mutableListOf<McpTool>()
            var cursor: String? = null
            repeat(10) {
                val r = s.request("tools/list", buildJsonObject { cursor?.let { c -> put("cursor", c) } })
                (r["tools"] as? JsonArray).orEmpty().forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    val name = (o["name"] as? JsonPrimitive)?.content ?: return@forEach
                    val ann = o["annotations"] as? JsonObject
                    fun hint(k: String) = (ann?.get(k) as? JsonPrimitive)?.booleanOrNull == true
                    out += McpTool(
                        name = name,
                        description = (o["description"] as? JsonPrimitive)?.content.orEmpty()
                            .ifBlank { (o["title"] as? JsonPrimitive)?.content.orEmpty() },
                        schema = o["inputSchema"]?.toString().orEmpty(),
                        readOnly = hint("readOnlyHint"),
                        destructive = hint("destructiveHint"),
                    )
                }
                cursor = (r["nextCursor"] as? JsonPrimitive)?.content?.takeIf { c -> c.isNotBlank() }
                if (cursor == null) return@use out
            }
            out
        }
    }

    /** 调一个工具，返回给模型看的文字。 */
    suspend fun call(server: McpServer, tool: McpTool, args: JsonObject): String = withContext(Dispatchers.IO) {
        Session(server).use { s ->
            resultText(s.request("tools/call", buildJsonObject { put("name", tool.name); put("arguments", args) }))
        }
    }

    /** tools/call 的结果整理成文字：文字原样，图片和文件只写个占位。 */
    fun resultText(r: JsonObject): String {
        val parts = (r["content"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            when ((o["type"] as? JsonPrimitive)?.content) {
                "text" -> (o["text"] as? JsonPrimitive)?.content
                "image" -> "[图片]"
                "audio" -> "[音频]"
                "resource" -> (o["resource"] as? JsonObject)?.let { res ->
                    (res["text"] as? JsonPrimitive)?.content ?: "[文件 ${(res["uri"] as? JsonPrimitive)?.content.orEmpty()}]"
                }
                "resource_link" -> "[链接 ${(o["uri"] as? JsonPrimitive)?.content.orEmpty()}]"
                else -> null
            }
        }
        val text = parts.joinToString("\n").ifBlank { r["structuredContent"]?.toString().orEmpty() }.ifBlank { "（没有返回内容）" }
        val error = (r["isError"] as? JsonPrimitive)?.booleanOrNull == true
        return (if (error) "连接器返回错误：" else "") + if (text.length > 30_000) text.take(30_000) + "\n…（太长，后面省略了）" else text
    }

    /** 读 SSE：每个事件交给 onEvent(事件名, 数据)，返回 false 就停。 */
    internal fun readEvents(source: BufferedSource, onEvent: (String, String) -> Boolean) {
        var event = "message"
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty() && !onEvent(event, data.toString())) return
                    event = "message"; data.clear()
                }
                line.startsWith(":") -> {}
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
        if (data.isNotEmpty()) onEvent(event, data.toString())
    }

    /** 一次连接：握手后发请求。先试 Streamable HTTP，服务器不认就换老式 SSE。 */
    private class Session(val server: McpServer) : Closeable {
        /**
         * 这个连接器专用的客户端（和 sharedHttp 共用连接池）。服务器把请求跳转到别的网站时，
         * 跳过去的那一跳去掉 Key 所在的请求头，不把 Key 带给别人。
         */
        val http = com.jack.ai.api.sharedHttp.newBuilder().addNetworkInterceptor { chain ->
            val req = chain.request()
            if (req.url.host.equals(server.url.trim().toHttpUrlOrNull()?.host, true)) chain.proceed(req)
            else chain.proceed(req.newBuilder().removeHeader("Authorization").apply { keyHeader()?.let { removeHeader(it) } }.build())
        }.build()
        private val jsonType = "application/json".toMediaType()
        private var nextId = 1
        private var sessionId: String? = null
        private var legacy: Legacy? = null

        /** 网址里写了 {key} 的（比如高德），Key 填进网址；否则 Key 放请求头。 */
        val url: String = server.url.trim().let { u ->
            if (KEY_SLOT in u) u.replace(KEY_SLOT, java.net.URLEncoder.encode(server.key.trim(), "UTF-8")) else u
        }

        init {
            if (!server.url.startsWith("http")) throw McpException("连接器地址要以 http:// 或 https:// 开头")
            val init = buildJsonObject {
                put("protocolVersion", PROTOCOL)
                put("capabilities", JsonObject(emptyMap()))
                put("clientInfo", buildJsonObject { put("name", "JackAI"); put("version", "1.0") })
            }
            try {
                request("initialize", init)
            } catch (e: HttpStatus) {
                // 404/405/400：多半是老式 SSE 服务
                if (e.code !in listOf(400, 404, 405)) throw McpException(e.message ?: "连接失败")
                legacy = Legacy(this)
                request("initialize", init)
            }
            notify("notifications/initialized")
        }

        private class HttpStatus(val code: Int, message: String) : Exception(message)

        /** Key 写成「名字: 值」时那个请求头的名字。 */
        fun keyHeader(): String? {
            val k = server.key.trim()
            val colon = k.indexOf(':')
            val name = if (colon > 0) k.substring(0, colon).trim() else return null
            return name.takeIf { it.isNotEmpty() && !it.contains(' ') && !it.equals("Bearer", true) }
        }

        fun auth(b: Request.Builder): Request.Builder {
            val k = server.key.trim()
            if (k.isEmpty() || KEY_SLOT in server.url) return b
            val colon = k.indexOf(':')
            val name = if (colon > 0) k.substring(0, colon).trim() else ""
            return when {
                name.isNotEmpty() && !name.contains(' ') && !name.equals("Bearer", true) -> b.header(name, k.substring(colon + 1).trim())
                k.startsWith("Bearer ", true) -> b.header("Authorization", k)
                else -> b.header("Authorization", "Bearer $k")
            }
        }

        private fun message(method: String, params: JsonObject?, id: Int?) = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

        /** 通知不需要回复；有的服务对它回个错误码，不影响后面的请求，忽略就好。 */
        fun notify(method: String) {
            runCatching {
                legacy?.let { it.post(message(method, null, null)); return }
                post(message(method, null, null), expectId = null)
            }
        }

        fun request(method: String, params: JsonObject): JsonObject {
            val id = nextId++
            val reply = legacy?.request(id, message(method, params, id)) ?: post(message(method, params, id), id)
                ?: throw McpException("连接器没有回应")
            (reply["error"] as? JsonObject)?.let { err ->
                throw McpException("连接器出错：" + ((err["message"] as? JsonPrimitive)?.content ?: err.toString()))
            }
            // 有的服务出错时不按 MCP 的格式回（比如高德 Key 不对时回 {"info":"INVALID_USER_KEY"}），别当成成功
            return reply["result"] as? JsonObject ?: throw McpException(
                if ("KEY" in reply.toString().uppercase()) "连接器拒绝了：Key 不对或没有权限（${reply.toString().take(120)}）"
                else "连接器回的不是 MCP 格式：${reply.toString().take(160)}"
            )
        }

        /** Streamable HTTP：POST 一条消息，回的可能是 JSON，也可能是一段 SSE。 */
        private fun post(body: String, expectId: Int?): JsonObject? {
            val req = auth(Request.Builder().url(url))
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL)
                .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
                .post(body.toRequestBody(jsonType))
                .build()
            http.newCall(req).execute().use { resp ->
                resp.header("Mcp-Session-Id")?.let { sessionId = it }
                if (resp.code == 401 || resp.code == 403) throw McpException("连接器拒绝了（${resp.code}）：Key 不对或没有权限")
                if (!resp.isSuccessful) throw HttpStatus(resp.code, "连接器出错（${resp.code}）")
                if (expectId == null) return null
                val type = resp.header("Content-Type").orEmpty()
                val source = resp.body?.source() ?: return null
                if ("event-stream" in type) {
                    var found: JsonObject? = null
                    readEvents(source) { _, data ->
                        val o = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                        if (o != null && idOf(o) == expectId) { found = o; false } else true
                    }
                    return found
                }
                return runCatching { json.parseToJsonElement(source.readUtf8()).jsonObject }.getOrNull()
                    ?: throw McpException("连接器返回的不是有效的数据")
            }
        }

        override fun close() {
            legacy?.close()
            // 告诉服务器这次会话结束了；失败也无所谓
            sessionId?.let { sid ->
                runCatching {
                    auth(Request.Builder().url(url)).header("Mcp-Session-Id", sid).delete().build()
                        .let { http.newCall(it).execute().close() }
                }
            }
        }
    }

    private fun idOf(o: JsonObject): Int? = (o["id"] as? JsonPrimitive)?.intOrNull

    /**
     * 老式 SSE：先 GET 一条一直开着的事件流，服务器在第一个 endpoint 事件里告诉往哪 POST；
     * POST 只回 202，真正的回复从事件流里来。
     */
    private class Legacy(private val session: Session) : Closeable {
        private val server = session.server
        private val http = session.http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        private val endpoint = CompletableFuture<String>()
        private val waiting = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
        private val stream: Call

        init {
            val req = session.auth(Request.Builder().url(session.url)).header("Accept", "text/event-stream").get().build()
            stream = http.newCall(req)
            Thread {
                try {
                    stream.execute().use { resp ->
                        if (!resp.isSuccessful) throw McpException("连接器出错（${resp.code}）")
                        val source = resp.body?.source() ?: throw McpException("连接器没有回应")
                        readEvents(source) { event, data ->
                            if (event == "endpoint") {
                                val target = session.url.toHttpUrl().resolve(data.trim())
                                val base = session.url.toHttpUrl()
                                // 只往同一个网站发：服务器让我们把带 Key 的请求发去别的网站，一律拒绝
                                if (target == null || !target.host.equals(base.host, true) || target.port != base.port || target.scheme != base.scheme) {
                                    endpoint.completeExceptionally(McpException("连接器要求把请求发到另一个网站（${target?.host ?: data.trim().take(60)}），为了不把 Key 交出去，已拒绝"))
                                } else endpoint.complete(target.toString())
                            } else {
                                runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()?.let { o ->
                                    idOf(o)?.let { waiting.remove(it)?.complete(o) }
                                }
                            }
                            true
                        }
                    }
                    fail(McpException("连接器断开了"))
                } catch (e: Exception) {
                    fail(if (e is McpException) e else McpException("连不上连接器：${e.message ?: e.javaClass.simpleName}"))
                }
            }.apply { isDaemon = true }.start()
        }

        private fun fail(e: Exception) {
            endpoint.completeExceptionally(e)
            waiting.values.forEach { it.completeExceptionally(e) }
        }

        private fun <T> CompletableFuture<T>.await(seconds: Long): T = try {
            get(seconds, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: java.util.concurrent.TimeoutException) {
            throw McpException("连接器超时没有回应")
        }

        fun post(body: String) {
            val url = endpoint.await(30)
            val req = session.auth(Request.Builder().url(url)).post(body.toRequestBody("application/json".toMediaType())).build()
            session.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw McpException("连接器出错（${resp.code}）")
            }
        }

        fun request(id: Int, body: String): JsonObject {
            val f = CompletableFuture<JsonObject>()
            waiting[id] = f
            try {
                post(body)
                return f.await(180)
            } finally {
                waiting.remove(id)
            }
        }

        override fun close() = stream.cancel()
    }
}
