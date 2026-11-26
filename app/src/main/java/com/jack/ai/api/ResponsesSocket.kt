package com.jack.ai.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
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
 * - 一条连接一次只跑一个回答；同时有几个请求（子 agent 分头做、后台任务、起标题）就各开一条，最多 [MAX_CONNS] 条，
 *   再多的走 HTTP，不排队。上一步只记在跑它的那条连接里，下一步优先回到那条连接上。
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

    private const val MAX_CONNS = 6

    private class Conn(val ws: WebSocket, val auth: String, val opened: Long, val inbox: Channel<String>,
                       private val gone: java.util.concurrent.atomic.AtomicBoolean,
                       /** 握手时带的 session-id：ChatGPT 按它把这条连接分到一台机器，之后不能改。 */
                       val session: String = "") {
        /** 连接已经被断开（切到后台、网络切换、服务端关掉）。 */
        val closed get() = gone.get()
        /** 正在跑一个回答。 */
        var busy = false
        /** 按对话（第一条消息）记链；只留最近几条，连接里的缓存也只记得最近的。 */
        val chains = object : LinkedHashMap<Int, Chain>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Chain>?) = size > 4
        }
    }

    /** 连接断了、链跟着没了的对话 → 为什么（统计页说明这一次为什么从头发、没命中）。 */
    private val lost = HashMap<Int, String>()

    /** 一条链：上一步的回答 id、那一步的开头（除 input 外的参数）和发过的全部 input。 */
    internal data class Chain(val id: String, val head: JsonObject, val input: List<JsonElement>)

    /** 连接池（全部读写都锁着它）。 */
    private val pool = mutableListOf<Conn>()
    /** 正在握手、还没进池的连接数：算进上限里。 */
    private var opening = 0

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

    internal fun forTest(key: Int, chain: Chain) = synchronized(pool) { pool.first().chains[key] = chain }

    /** 测试用：清掉连接和链。 */
    internal fun reset() = synchronized(pool) { pool.forEach { it.ws.cancel() }; pool.clear(); opening = 0; lost.clear() }

    /** 有几条连接（测试用）。 */
    internal fun size() = synchronized(pool) { pool.size }

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
        val body = json.parseToJsonElement(req.body).o() ?: throw Unavailable("body")
        val c = acquire(client, req, keyOf((body["input"] as? JsonArray).orEmpty()))
        var used = c
        try {
            used = run(client, req, c, body, idleMs, emit)
        } finally {
            synchronized(pool) { used.busy = false; c.busy = false }
        }
    }

    /**
     * 挑一条空着的连接并占上：先找记着这个对话上一步的，再找握手时 session-id 就是这个对话的；
     * 都没有就新开一条（session-id 是这个对话的）。到了上限就把一条空着的、别的对话的换掉重连——
     * session-id 只在握手时发，用别的对话的连接等于没带，缓存很难命中。全忙抛 [Unavailable]，调用方走 HTTP。
     * 断了、过期了、令牌换了的空闲连接顺手清掉。
     */
    private suspend fun acquire(client: OkHttpClient, req: NativeRequest, key: Int): Conn {
        val auth = req.headers["Authorization"].orEmpty()
        val now = System.currentTimeMillis()
        synchronized(pool) {
            pool.filter { !it.busy && (it.closed || it.auth != auth || now - it.opened >= MAX_AGE_MS) }.forEach { stale ->
                dropLocked(stale, when { stale.closed -> "closed"; stale.auth != auth -> "token"; else -> "age" })
            }
            val session = req.headers[ResponsesApi.SESSION_HEADER].orEmpty()
            val free = pool.filter { !it.busy }
            val pick = free.firstOrNull { key in it.chains } ?: free.firstOrNull { it.session == session }
            if (pick != null) { pick.busy = true; return pick }
            if (pool.size + opening >= MAX_CONNS) {
                // 满了：换掉一条空着的（记的链最少的那条），给这个对话开一条带对的 session-id 的
                val spare = free.minByOrNull { it.chains.size } ?: throw Unavailable("busy")
                dropLocked(spare, "swap")
            }
            opening++
        }
        val c = try { connect(client, req) } finally { synchronized(pool) { opening-- } }
        synchronized(pool) { c.busy = true; pool += c }
        return c
    }

    /** 跑一个回答；中途连接到了 60 分钟换了一条的话，返回最后用的那条（调用方把它也放开）。 */
    private suspend fun run(client: OkHttpClient, req: NativeRequest, first: Conn, body: JsonObject, idleMs: Long, emit: suspend (StreamEvent) -> Unit): Conn {
        val input = (body["input"] as? JsonArray).orEmpty()
        val head = JsonObject(body - "input" - "stream")
        val key = keyOf(input)
        var c = first
        // 这个对话上一步还接着、这次因为连接换了只能从头发：记下原因
        synchronized(pool) { lost.remove(key) }?.let { why -> if (synchronized(pool) { pool.none { key in it.chains } }) ResponsesApi.lastVerdict = "gpt:wslost:$why" }
        var emitted = false
        var retried = false
        while (true) {
            val chain = synchronized(pool) { c.chains[key] }
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
            // 同一个对话的旧链（可能在别的连接上）都作废：这一步做完只在这条连接上记新的
            synchronized(pool) { pool.forEach { it.chains.remove(key) } }
            if (!c.ws.send(msg.toString())) { drop(c, "closed"); throw IOException("和 ChatGPT 的连接断了") }
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
                        // 连接到了 60 分钟：换一条新的、发全量
                        if (code == "websocket_connection_limit_reached") {
                            retried = true; done = true
                            drop(c, "age")
                            c = connect(client, req)
                            synchronized(pool) { c.busy = true; pool += c }
                            break
                        }
                    }
                    for (ev in parser.feed("data: $text")) { emitted = true; emit(ev) }
                    if (parser.finished) {
                        val id = o["response"].o()?.get("id").s()
                        if (type == "response.completed" && id.isNotEmpty()) synchronized(pool) { c.chains[key] = Chain(id, head, input) }
                        done = true
                        return c
                    }
                }
            } finally {
                // 没收完就停了（用户停下、出错）：连接里还在跑的那个回答会把事件塞进来，扔掉这条连接
                if (!done) drop(c, "stopped")
            }
            // 到这里是要重试一次全量（链已经删了，下一圈 delta 为 null）
        }
    }

    private fun drop(c: Conn, why: String) = synchronized(pool) { dropLocked(c, why) }

    /** 关掉一条连接，它记着的链都没了：记下原因。只影响这一条，别的连接上的对话照常接着。 */
    private fun dropLocked(c: Conn, why: String) {
        c.ws.cancel()
        pool.remove(c)
        c.chains.keys.forEach { lost[it] = why }
        c.chains.clear()
        while (lost.size > 32) lost.remove(lost.keys.first())
    }

    private suspend fun connect(client: OkHttpClient, req: NativeRequest): Conn {
        val auth = req.headers["Authorization"].orEmpty()
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
        return Conn(ws, auth, System.currentTimeMillis(), inbox, gone, req.headers[ResponsesApi.SESSION_HEADER].orEmpty())
    }
}
