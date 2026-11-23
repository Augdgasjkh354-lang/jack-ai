package com.jack.ai.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ChatGPT 的常连接模式（Responses WebSocket mode）：和 ChatGPT 保持一条连接，每一步只发新增的内容
 * （工具结果、新的话），用 previous_response_id 接上一步。前面的对话留在 ChatGPT 那台机器的内存里，
 * 不用每步重发、也不靠缓存碰运气。HTTP 每步都发全量，实测前缀一字不差也几乎不命中。
 *
 * - store=false 时上一步只在这条连接里记着：连接断了、到了 60 分钟、换了令牌，就从头发全量（新的一条链）。
 * - 一条连接一次只跑一个回答；同时有别的请求（起标题、子任务）就让它走 HTTP，不排队。
 * - 连不上（服务不支持、被拒）就退回 HTTP；4xx 拒绝的这次进程里不再试。
 * 文档：developers.openai.com/api/docs/guides/websocket-mode。
 */
internal object ResponsesSocket {
    /** 关掉就全走 HTTP（服务明确不支持时自动关；测试里也关）。 */
    @Volatile var enabled = true

    /** 这次没走常连接（连不上、正忙），调用方改走 HTTP。还没发出任何内容。 */
    class Unavailable(msg: String) : Exception(msg)

    private const val MAX_AGE_MS = 55 * 60_000L
    private val json = Json { ignoreUnknownKeys = true }

    private class Conn(val ws: WebSocket, val auth: String, val opened: Long, val inbox: Channel<String>,
                       private val gone: java.util.concurrent.atomic.AtomicBoolean) {
        /** 连接已经被断开（切到后台、网络切换、服务端关掉）。 */
        val closed get() = gone.get()
    }

    /** 连接断了、链跟着没了的对话 → 为什么（统计页说明这一次为什么从头发、没命中）。 */
    private val lost = HashMap<Int, String>()

    /** 一条链：上一步的回答 id、那一步的开头（除 input 外的参数）和发过的全部 input。 */
    internal data class Chain(val id: String, val head: JsonObject, val input: List<JsonElement>)

    private val busy = Mutex()
    private var conn: Conn? = null
    /** 按对话（第一条消息）记链；只留最近几条，连接里的缓存也只记得最近的。 */
    private val chains = object : LinkedHashMap<Int, Chain>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Chain>?) = size > 4
    }

    private fun JsonElement?.o() = this as? JsonObject
    private fun JsonElement?.s() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    /** 助手那边的输出项（上一步的思考、回答、工具调用）：previous_response_id 已经带着它们，不用再发。 */
    private fun assistantSide(e: JsonElement): Boolean {
        val o = e.o() ?: return false
        return when (o["type"].s()) {
            "reasoning", "function_call" -> true
            "message" -> o["role"].s() == "assistant"
            else -> false
        }
    }

    /** 认对话用：系统提示后面的第一项。 */
    internal fun keyOf(input: List<JsonElement>): Int =
        (input.firstOrNull { it.o()?.get("role").s() != "developer" } ?: JsonPrimitive("")).toString().hashCode()

    /**
     * 能接上一步就返回只含新内容的 input；接不上返回 null（发全量）。
     * 接得上：开头参数一样，这次的 input 原样以上次的开头，后面先是上一步的输出（至少一项），再是新内容（至少一项）。
     */
    internal fun delta(chain: Chain?, head: JsonObject, input: List<JsonElement>): List<JsonElement>? {
        if (chain == null || chain.head != head || input.size <= chain.input.size) return null
        if (input.subList(0, chain.input.size) != chain.input) return null
        val after = input.drop(chain.input.size)
        val output = after.takeWhile(::assistantSide).size
        val rest = after.drop(output)
        return rest.takeIf { output > 0 && it.isNotEmpty() }
    }

    internal fun forTest(key: Int, chain: Chain) = synchronized(chains) { chains[key] = chain }

    /** 测试用：清掉连接和链。 */
    internal fun reset() { conn?.ws?.cancel(); conn = null; synchronized(chains) { chains.clear() } }

    /**
     * 发一次请求，事件交给 [emit]。走不了常连接抛 [Unavailable]（这时什么都没发出去）。
     * [idleMs]：开始回答后多久没动静算卡住。
     */
    suspend fun send(
        client: OkHttpClient,
        req: NativeRequest,
        idleMs: Long,
        emit: suspend (StreamEvent) -> Unit,
    ) {
        if (!enabled) throw Unavailable("off")
        if (!busy.tryLock()) throw Unavailable("busy")
        try {
            run(client, req, idleMs, emit)
        } finally {
            busy.unlock()
        }
    }

    private suspend fun run(client: OkHttpClient, req: NativeRequest, idleMs: Long, emit: suspend (StreamEvent) -> Unit) {
        val body = json.parseToJsonElement(req.body).o() ?: throw Unavailable("body")
        val input = (body["input"] as? JsonArray).orEmpty()
        val head = JsonObject(body - "input" - "stream")
        val key = keyOf(input)
        var c = connect(client, req)
        // 这个对话上一步还接着、这次因为连接换了只能从头发：记下原因
        synchronized(chains) { lost.remove(key) }?.let { why -> if (synchronized(chains) { chains[key] } == null) ResponsesApi.lastVerdict = "gpt:wslost:$why" }
        var emitted = false
        var retried = false
        while (true) {
            val chain = synchronized(chains) { chains[key] }
            val delta = delta(chain, head, input)
            val msg = JsonObject(buildMap {
                put("type", JsonPrimitive("response.create"))
                putAll(head)
                if (delta != null) {
                    put("previous_response_id", JsonPrimitive(chain!!.id))
                    put("input", JsonArray(delta))
                } else put("input", JsonArray(input))
            })
            // 统计页：接着上一步发的标一下（命不命中照样看 ChatGPT 报的数）
            if (delta != null) ResponsesApi.lastVerdict = "gpt:ws"
            synchronized(chains) { chains.remove(key) }
            if (!c.ws.send(msg.toString())) { drop("closed"); throw IOException("和 ChatGPT 的连接断了") }
            val parser = ResponsesApi.Parser((head["model"] as? JsonPrimitive)?.content.orEmpty())
            var done = false
            try {
                while (true) {
                    val r = withTimeoutOrNull(if (emitted) idleMs else 300_000L) { c.inbox.receiveCatching() }
                        ?: throw IOException("回答传到一半卡住了（${idleMs / 1000} 秒没有新内容）")
                    val text = r.getOrNull() ?: throw (r.exceptionOrNull() as? IOException ?: IOException("和 ChatGPT 的连接断了", r.exceptionOrNull()))
                    val o = runCatching { json.parseToJsonElement(text).o() }.getOrNull() ?: continue
                    val type = o["type"].s()
                    if (type == "error" && !emitted && !retried) {
                        val code = o["error"].o()?.get("code").s()
                        // 上一步不在连接的缓存里了：发全量再来一次
                        if (code == "previous_response_not_found") { retried = true; done = true; break }
                        // 连接到了 60 分钟：重连、发全量
                        if (code == "websocket_connection_limit_reached") { retried = true; done = true; drop("age"); c = connect(client, req); break }
                    }
                    for (ev in parser.feed("data: $text")) { emitted = true; emit(ev) }
                    if (parser.finished) {
                        val id = o["response"].o()?.get("id").s()
                        if (type == "response.completed" && id.isNotEmpty()) synchronized(chains) { chains[key] = Chain(id, head, input) }
                        done = true
                        return
                    }
                }
            } finally {
                // 没收完就停了（用户停下、出错）：连接里还在跑的那个回答会把事件塞进来，扔掉这条连接
                if (!done) drop("stopped")
            }
            // 到这里是要重试一次全量（链已经删了，下一圈 delta 为 null）
        }
    }

    private fun drop(why: String) {
        conn?.ws?.cancel()
        conn = null
        synchronized(chains) { chains.keys.forEach { lost[it] = why }; chains.clear() }
    }

    private suspend fun connect(client: OkHttpClient, req: NativeRequest): Conn {
        val auth = req.headers["Authorization"].orEmpty()
        conn?.let {
            if (!it.closed && it.auth == auth && System.currentTimeMillis() - it.opened < MAX_AGE_MS) return it
            drop(when { it.closed -> "closed"; it.auth != auth -> "token"; else -> "age" })
        }
        val url = req.url.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val inbox = Channel<String>(Channel.UNLIMITED)
        val gone = java.util.concurrent.atomic.AtomicBoolean()
        val opened = CompletableDeferred<Result<Unit>>()
        val request = Request.Builder().url(url).apply { req.headers.forEach { (k, v) -> header(k, v) } }
            .header("OpenAI-Beta", "responses_websockets=2026-02-06")
            .build()
        val ws = client.newBuilder().pingInterval(20, TimeUnit.SECONDS).build().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                com.jack.ai.data.integrations.ChatGptUsage.seeHeaders(response.headers.toMap())
                opened.complete(Result.success(Unit))
            }
            override fun onMessage(webSocket: WebSocket, text: String) { inbox.trySend(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                gone.set(true)
                inbox.close(IOException("ChatGPT 关掉了连接（$code）"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { gone.set(true); inbox.close(IOException("ChatGPT 关掉了连接（$code）")) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                response?.close()
                if (!opened.isCompleted) {
                    // 服务不认常连接（404、426、400 之类）：这次进程里不再试；401、429 是令牌、额度的事，下次再试
                    if (code in 400..499 && code != 401 && code != 408 && code != 429) enabled = false
                    opened.complete(Result.failure(Unavailable("handshake $code: ${t.message}")))
                }
                gone.set(true)
                inbox.close(t as? IOException ?: IOException(t.message, t))
            }
        })
        val r = withTimeoutOrNull(20_000L) { opened.await() }
        if (r == null || r.isFailure) {
            ws.cancel()
            throw Unavailable(r?.exceptionOrNull()?.message ?: "handshake timeout")
        }
        return Conn(ws, auth, System.currentTimeMillis(), inbox, gone).also { conn = it }
    }
}
