package com.jack.ai.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.jack.ai.data.Endpoint
import com.jack.ai.data.Price
import com.jack.ai.data.Pricing
import com.jack.ai.data.ThinkLevel

sealed interface StreamEvent {
    data class Text(val reasoning: String, val content: String) : StreamEvent
    /** 工具调用是分片流式返回的，按 index 拼起来。 */
    data class ToolCall(val index: Int, val id: String, val name: String, val arguments: String) : StreamEvent
    /** 本次请求用掉的 token（最后一个数据块里返回）。 */
    /** cacheHit 为 null 表示服务没告诉我们缓存命中了多少。 */
    /** endpoint：换成备用模型答的时候是那个备用（记账要记到它头上）；主模型答的为 null。 */
    data class Usage(val promptTokens: Int, val completionTokens: Int, val cacheHit: Int? = null, val endpoint: Endpoint? = null,
        /** 请求开头的指纹（见 PrefixPrint），缓存没命中时用来判断是哪一段变了。 */
        val prefix: String? = null,
        /** 服务直接告诉了这次扣了多少（元，OpenRouter 的 usage.cost 换算过来）：有就用它，不按本地单价估。 */
        val exactCost: Double? = null) : StreamEvent
    /** 这一轮怎么结束的：length 表示写到长度上限被截断了。 */
    data class Finish(val reason: String) : StreamEvent
}

class ApiException(message: String, val code: Int = 0, val serviceCode: String = "") : Exception(message)

internal fun JsonElement?.str(): String =
    if (this == null || this is JsonNull) "" else (this as? JsonPrimitive)?.content ?: ""

/** 百炼模型列表使用 output.models 和分页字段，不是 OpenAI 的 data。 */
internal fun parseBailianModels(body: String?): Pair<List<String>, Int> {
    val obj = runCatching { Json.parseToJsonElement(body.orEmpty()) as? JsonObject }.getOrNull()
    val output = obj?.get("output") as? JsonObject ?: throw ApiException("百炼没有返回模型列表")
    val models = output["models"] as? JsonArray ?: throw ApiException("百炼没有返回模型列表")
    val ids = models.mapNotNull { ((it as? JsonObject)?.get("model") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
    val total = (output["total"] as? JsonPrimitive)?.intOrNull ?: throw ApiException("百炼没有返回模型总数")
    return ids to total
}

internal val sharedHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    // 搜索、读网页、连接器这类请求：一分钟一个字节都没来就是连接死了，早点报错让 Kavi 换办法，
    // 不然任务会在这里干等五分钟。要等模型思考的用下面的 modelHttp。
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    // 工具调用里发的请求记下来，工具超时或用户按停止时能直接断开（见 ActiveCalls）
    .addInterceptor { chain -> ActiveCalls.track(chain.call()); chain.proceed(chain.request()) }
    // 开了 Kavi 专用代理时按规则分流；没开时和原来一样跟随系统
    .proxySelector(Net.selector)
    .proxyAuthenticator(Net.authenticator)
    // 直连时查域名走加密 DNS；走代理的由节点解析
    .dns(SecureDns)
    .build()

/** 调模型用的：深度思考模型可能很久才出第一个字，等得久一些。 */
internal val modelHttp: OkHttpClient = sharedHttp.newBuilder().readTimeout(5, TimeUnit.MINUTES)
    // 等第一个字的那几分钟里连接悄悄死了（代理、基站把它丢了）手机这边是不知道的：HTTP/2 连接每 30 秒探一次，
    // 对面没回应就当连接断了，马上报错重发，不用干等到五分钟。只对 HTTP/2 的服务起作用。
    .pingInterval(30, TimeUnit.SECONDS)
    .build()

/** 回答已经开始往回传之后，这么久一行都没来就算卡住。DeepSeek 等待时会一直发 keep-alive，别家不一定，放宽些。 */
internal fun streamIdleMs(ep: Endpoint): Long = streamIdleOverrideMs ?: if (ep.deepseek) 90_000L else 180_000L

/** 测试里把卡住的时限调小。 */
@Volatile internal var streamIdleOverrideMs: Long? = null

class DeepSeekClient(
    /** 非流式请求的用量回调：模型、输入、缓存命中、输出。 */
    private val onUsage: ((Endpoint, Int, Int?, Int) -> Unit)? = null,
) {
    private val http = modelHttp
    private val json = Json { ignoreUnknownKeys = true }

    /** 流式请求；主模型还没回字就出错时按顺序换备用模型（见 Failover）。 */
    fun stream(
        ep: Endpoint,
        messages: List<JsonObject>,
        thinking: ThinkLevel,
        temperature: Float,
        tools: JsonArray? = null,
    ): Flow<StreamEvent> {
        if (ep.fallbacks.isEmpty()) return streamOne(ep, messages, thinking, temperature, tools)
        return kotlinx.coroutines.flow.flow {
            val order = Failover.order(ep)
            for ((i, cand) in order.withIndex()) {
                var started = false
                try {
                    streamOne(cand, messages, if (cand.reasoning) thinking else ThinkLevel.OFF, temperature, tools).collect { ev ->
                        started = true
                        emit(if (ev is StreamEvent.Usage && cand.key != ep.key) ev.copy(endpoint = cand) else ev)
                    }
                    Failover.succeeded(ep, cand)
                    return@flow
                } catch (e: Throwable) {
                    if (started || i == order.lastIndex || !Failover.worthSwitching(e)) throw e
                }
            }
        }
    }

    private fun streamOne(
        ep: Endpoint,
        messages: List<JsonObject>,
        thinking: ThinkLevel,
        temperature: Float,
        tools: JsonArray? = null,
        jsonMode: Boolean = false,
    ): Flow<StreamEvent> = callbackFlow {
        val format = ApiFormat.of(ep)
        val native = when (format) {
            ApiFormat.ANTHROPIC -> AnthropicApi.request(ep, messages, thinking, tools, stream = true)
            ApiFormat.GEMINI -> GeminiApi.request(ep, messages, thinking, temperature, tools, stream = true, jsonMode = jsonMode)
            // 令牌一小时过期：发之前现取（快过期就先续）
            ApiFormat.RESPONSES -> ResponsesApi.request(ep, com.jack.ai.data.integrations.ChatGptAuth.accessToken(), messages, thinking, tools)
            ApiFormat.OPENAI -> null
        }
        val parser: SseParser? = when (format) {
            ApiFormat.ANTHROPIC -> AnthropicApi.Parser()
            ApiFormat.GEMINI -> GeminiApi.Parser()
            ApiFormat.RESPONSES -> ResponsesApi.Parser()
            ApiFormat.OPENAI -> null
        }
        val body = native?.body ?: buildJsonObject {
            put("model", ep.model)
            put("stream", true)
            if (ep.deepseek) {
                put("thinking", buildJsonObject { put("type", if (thinking.effort == null) "disabled" else "enabled") })
                if (thinking.effort != null) {
                    put("reasoning_effort", thinking.effort)
                } else {
                    // 思考模式下 temperature 不起作用，只在不思考时传
                    put("temperature", temperature.toDouble())
                }
            } else if (ThinkDialect.of(ep) != ThinkDialect.Kind.NONE) {
                // 百炼 Qwen3、Kimi：按它们自己的参数开关思考（见 ThinkDialect）；它们的温度有固定要求，不发
                ThinkDialect.put(this, ThinkDialect.of(ep), thinking.effort != null)
            } else if (ep.reasoning && thinking.effort != null) {
                // OpenAI 格式只认 low / medium / high
                put("reasoning_effort", if (thinking.effort == "max") "high" else thinking.effort)
            } else if (!ep.reasoning) {
                put("temperature", temperature.toDouble())
            }
            put("messages", JsonArray(messages))
            if (tools != null) put("tools", tools)
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }.toString()

        val request = Request.Builder()
            .url(native?.url ?: (ep.baseUrl.trimEnd('/') + "/chat/completions"))
            .apply { if (native != null) native.headers.forEach { (k, v) -> header(k, v) } else header("Authorization", "Bearer ${ep.apiKey}") }
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)
        // 最近一次收到一行的时间；0 表示还没开始传（第一个字之前按 modelHttp 的五分钟算）
        val lastLineAt = java.util.concurrent.atomic.AtomicLong(0)
        // 传到一半没了动静（代理、基站悄悄把连接丢了，手机这边收不到断开）：不干等五分钟，到点就断开报错，
        // 外面会按「网络断了一下」重试或自动接着做
        val limit = streamIdleMs(ep)
        val watchdog = launch {
            while (true) {
                delay((limit / 4).coerceIn(50, 5_000))
                val at = lastLineAt.get()
                if (at != 0L && (System.nanoTime() - at) / 1_000_000 > limit) {
                    close(IOException("回答传到一半卡住了（${limit / 1000} 秒没有新内容）"))
                    call.cancel()
                    break
                }
            }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // 收到过用量没有、已经生成了多少字：传到一半断了又没收到用量，服务那边多半已经扣了钱，估一笔记上（见下面的 catch）
                var usageSent = false
                var generated = 0
                try {
                    response.use { resp ->
                        // ChatGPT 套餐：响应头里要是带了额度信息，记下来给统计页
                        if (format == ApiFormat.RESPONSES) com.jack.ai.data.integrations.ChatGptUsage.seeHeaders(resp.headers.toMap())
                        if (!resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (format == ApiFormat.RESPONSES) {
                                if (resp.code == 401) com.jack.ai.data.integrations.ChatGptAuth.invalidate()
                                throw ResponsesApi.httpError(resp.code, body)
                            }
                            val quota = com.jack.ai.data.isFreeTierOnly(body)
                            throw ApiException(describeError(resp.code, body), resp.code, if (quota) "AllocationQuota.FreeTierOnly" else "")
                        }
                        val source = resp.body?.source() ?: throw ApiException("服务器没有返回内容")
                        var finished = false
                        var validData = false
                        val raw = StringBuilder()
                        fun streamError(err: JsonObject) = ApiException(
                            "服务返回错误：" + err["message"].str().ifBlank { err.toString() },
                            serviceCode = err["code"].str(),
                        )
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            lastLineAt.set(System.nanoTime())
                            if (raw.length < 65_536) {
                                if (raw.isNotEmpty()) raw.append('\n')
                                raw.append(line.take(65_536 - raw.length))
                            }
                            if (parser != null) {
                                for (ev in parser.feed(line)) {
                                    if (ev is StreamEvent.Usage) usageSent = true
                                    if (ev is StreamEvent.Text) generated += ev.content.length + ev.reasoning.length
                                    if (trySendBlocking(ev).isFailure) return
                                }
                                validData = parser.sawData
                                if (parser.finished) { finished = true; if (format == ApiFormat.ANTHROPIC || format == ApiFormat.RESPONSES) break }
                                continue
                            }
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") {
                                finished = true
                                break
                            }
                            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                            validData = true
                            // 有的服务出错时仍回 200，把错误放在流里；不报出来就只会显示“没有收到回复”
                            (obj["error"] as? JsonObject)?.let { err -> throw streamError(err) }
                            (obj["usage"] as? JsonObject)?.let { u ->
                                val p = (u["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
                                val c = (u["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
                                if (p > 0) usageSent = true
                                if (p > 0 && trySendBlocking(StreamEvent.Usage(p, c, cacheHitOf(u), prefix = PrefixPrint.of(ep.model, thinking.effort, messages, tools),
                                        exactCost = exactCostOf(ep, u))).isFailure) return
                            }
                            // 有的服务在最后的用量块里写 "choices": null
                            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
                            (choice["error"] as? JsonObject)?.let { err -> throw streamError(err) }
                            choice["finish_reason"].str().takeIf { it.isNotEmpty() }?.let { reason ->
                                finished = true
                                if (trySendBlocking(StreamEvent.Finish(reason)).isFailure) return
                            }
                            val delta = choice["delta"] as? JsonObject ?: continue
                            val r = delta["reasoning_content"].str().ifEmpty { delta["reasoning"].str() }
                            val c = delta["content"].str()
                            generated += r.length + c.length
                            if ((r.isNotEmpty() || c.isNotEmpty()) && trySendBlocking(StreamEvent.Text(r, c)).isFailure) return
                            (delta["tool_calls"] as? JsonArray)?.forEach { el ->
                                val tc = el as? JsonObject ?: return@forEach
                                val fn = tc["function"] as? JsonObject
                                generated += fn?.get("arguments").str().length
                                if (trySendBlocking(StreamEvent.ToolCall(
                                    index = (tc["index"] as? JsonPrimitive)?.intOrNull ?: 0,
                                    id = tc["id"].str(),
                                    name = fn?.get("name").str(),
                                    arguments = fn?.get("arguments").str(),
                                )).isFailure) return
                            }
                        }
                        if (!validData && !finished) {
                            val body = raw.toString()
                            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                            (obj?.get("error") as? JsonObject)?.let { err -> throw streamError(err) }
                            val preview = body.take(200).ifBlank { "<空响应>" }
                            throw ApiException("服务返回了无法识别的内容：" + preview)
                        }
                        if (!finished) throw IOException("回答中途断开")
                    }
                    close()
                } catch (e: Exception) {
                    // 网断了（不是服务报错，也不是用户停下）：已经写了一些字却没收到用量，估一笔
                    if (e is IOException && !call.isCanceled() && !usageSent && generated > 0) trySendBlocking(estimatedUsage(messages, generated))
                    close(e)
                }
            }
        })
        // Collector 被取消时立即断开 HTTP 连接，而不是等同步读取自行返回。
        awaitClose { watchdog.cancel(); call.cancel() }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO) // 拼请求体（可能带大图）不占主线程

    /**
     * 一次性（非流式）请求，用于压缩、做梦这类后台任务。不开思考，速度快、篇幅留给正文。
     */
    suspend fun complete(
        ep: Endpoint,
        messages: List<JsonObject>,
        maxTokens: Int = 16384,
        jsonMode: Boolean = false,
    ): String {
        if (ep.fallbacks.isEmpty()) return completeOne(ep, messages, maxTokens, jsonMode)
        val order = Failover.order(ep)
        for ((i, cand) in order.withIndex()) {
            try {
                return completeOne(cand, messages, maxTokens, jsonMode).also { Failover.succeeded(ep, cand) }
            } catch (e: Throwable) {
                if (i == order.lastIndex || !Failover.worthSwitching(e)) throw e
            }
        }
        error("unreachable")
    }

    private suspend fun completeOne(
        ep: Endpoint,
        messages: List<JsonObject>,
        maxTokens: Int,
        jsonMode: Boolean,
    ): String = withContext(Dispatchers.IO) {
        // Anthropic、Gemini 原生接口：借流式请求收齐全文（不开思考），用量照样记
        if (ApiFormat.of(ep) != ApiFormat.OPENAI) {
            val text = StringBuilder()
            streamOne(ep, messages, ThinkLevel.OFF, 0.7f, null, jsonMode).collect { ev ->
                when (ev) {
                    is StreamEvent.Text -> text.append(ev.content)
                    is StreamEvent.Usage -> onUsage?.invoke(ep, ev.promptTokens, ev.cacheHit, ev.completionTokens)
                    else -> {}
                }
            }
            return@withContext text.toString().ifBlank { throw ApiException("模型没有返回内容") }
        }
        val body = buildJsonObject {
            put("model", ep.model)
            put("stream", false)
            val dialect = ThinkDialect.of(ep)
            if (ep.deepseek) {
                put("thinking", buildJsonObject { put("type", "disabled") })
                put("max_tokens", maxTokens)
            } else {
                // 后台请求（压缩、做梦、复核）不开思考、限住输出长度：以前只对 DeepSeek 管用，别家照样想、照样写长
                ThinkDialect.put(this, dialect, false)
                // 只在调用方要得短时发（思考摘要 60、标题这类）：默认的 16384 超过不少模型的输出上限（8192），发了反而报错。
                // OpenAI 官方的新模型不认 max_tokens，要用 max_completion_tokens
                if (maxTokens <= 8192) put(if (runCatching { java.net.URI(ep.baseUrl).host }.getOrNull()?.endsWith("openai.com") == true) "max_completion_tokens" else "max_tokens", maxTokens)
            }
            // 推理模型大多不接受自定义 temperature；百炼 Qwen3、Kimi 的温度有固定要求，也不发
            if ((!ep.reasoning || ep.deepseek) && dialect == ThinkDialect.Kind.NONE) put("temperature", 0.7)
            if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", JsonArray(messages))
        }.toString()
        val request = Request.Builder()
            .url(ep.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${ep.apiKey}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw ApiException(describeError(resp.code, text), resp.code)
            val obj = json.parseToJsonElement(text ?: "{}").jsonObject
            (obj["usage"] as? JsonObject)?.let { u ->
                fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull ?: 0
                onUsage?.invoke(ep, n("prompt_tokens"), cacheHitOf(u), n("completion_tokens"))
            }
            val msg = ((obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            msg?.get("content").str().ifBlank { throw ApiException("模型没有返回内容") }
        }
    }

    /** 用 ChatGPT 账号登录后能用的模型（GET /v1/models，带登录令牌）。 */
    private suspend fun listChatGptModels(): List<String> {
        val token = com.jack.ai.data.integrations.ChatGptAuth.accessToken()
        val req = Request.Builder().url(com.jack.ai.data.integrations.ChatGptAuth.BASE_URL + "/models").header("Authorization", "Bearer $token").get().build()
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw ResponsesApi.httpError(resp.code, text)
            ResponsesApi.parseModels(text)
        }
    }

    /** 查询账户余额，返回类似 "¥12.34" 的文字。 */
    suspend fun balance(baseUrl: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/user/balance")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw ApiException(describeError(resp.code, text), resp.code)
            val obj = json.parseToJsonElement(text ?: "{}").jsonObject
            val infos = obj["balance_infos"]?.jsonArray ?: return@use "未知"
            infos.joinToString("  ") { el ->
                val o = el.jsonObject
                val cur = o["currency"].str()
                val symbol = if (cur == "CNY") "¥" else "$cur "
                symbol + o["total_balance"].str()
            }.ifEmpty { "未知" }
        }
    }

    /**
     * 列出服务上能用的模型（OpenAI 格式的 GET /models）。
     * 用户填的地址少了 /v1 时自动补上再试一次，返回实际可用的地址和模型列表。
     */
    suspend fun listModels(baseUrl: String, apiKey: String, workspaceId: String = ""): Pair<String, List<Pair<String, Price?>>> = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        if (base == com.jack.ai.data.CHATGPT_PROVIDER_URL) return@withContext base to listChatGptModels().map { it to com.jack.ai.data.CHATGPT_PRICE }
        if (!base.startsWith("http")) throw ApiException("接口地址要以 http:// 或 https:// 开头")
        // Anthropic、Gemini 原生接口各有自己的模型列表
        when (ApiFormat.of(base)) {
            ApiFormat.ANTHROPIC -> {
                val ep = Endpoint(base, apiKey, "", deepseek = false, vision = false, reasoning = false)
                val req = Request.Builder().url(AnthropicApi.url(base, "models?limit=1000")).apply { AnthropicApi.headers(ep).forEach { (k, v) -> header(k, v) } }.get().build()
                val ids = http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string()
                    if (!resp.isSuccessful) throw ApiException(describeError(resp.code, text), resp.code)
                    AnthropicApi.parseModels(text)
                }
                return@withContext base to ids.map { it to null }
            }
            ApiFormat.GEMINI -> {
                val req = Request.Builder().url(GeminiApi.base(base) + "/models?pageSize=1000").header("x-goog-api-key", apiKey).get().build()
                val ids = http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string()
                    if (!resp.isSuccessful) throw ApiException(describeError(resp.code, text), resp.code)
                    GeminiApi.parseModels(text)
                }
                return@withContext base to ids.map { it to null }
            }
            ApiFormat.RESPONSES, ApiFormat.OPENAI -> {}
        }
        val host = runCatching { java.net.URI(base).host.orEmpty().lowercase() }.getOrDefault("")
        // 百炼的模型目录：北京要填业务空间 ID；没填就照常走 OpenAI 兼容的 /models（以前就是这么拿列表的）
        val bailian = host == "dashscope-intl.aliyuncs.com" || host == "cn-hongkong.dashscope.aliyuncs.com" || host.endsWith(".cn-beijing.maas.aliyuncs.com") ||
            (host == "dashscope.aliyuncs.com" && Regex("[a-zA-Z0-9-]+").matches(workspaceId.trim()))
        if (bailian) {
            val listHost = if (host == "dashscope.aliyuncs.com") "${workspaceId.trim()}.cn-beijing.maas.aliyuncs.com" else host
            val result = mutableListOf<Pair<String, Price?>>()
            var page = 1
            while (true) {
                val request = Request.Builder().url("https://$listHost/api/v1/models?capabilities=TG&page_no=$page&page_size=100")
                    .header("Authorization", "Bearer $apiKey").get().build()
                val response = http.newCall(request).execute().use { resp ->
                    val text = resp.body?.string()
                    if (!resp.isSuccessful) throw ApiException(describeError(resp.code, text), resp.code)
                    parseBailianModels(text)
                }
                result += response.first.map { it to null }
                if (result.size >= response.second || response.first.isEmpty()) break
                page++
            }
            return@withContext base to result.distinctBy { it.first }
        }
        val candidates = listOf(base) + (if (!Regex("""/v\d+[a-z]*$""").containsMatchIn(base)) listOf("$base/v1") else emptyList())
        var last: ApiException? = null
        for (b in candidates) {
            val request = Request.Builder().url("$b/models").header("Authorization", "Bearer $apiKey").get().build()
            val result = http.newCall(request).execute().use { resp ->
                val text = resp.body?.string()
                if (!resp.isSuccessful) {
                    last = ApiException(describeError(resp.code, text), resp.code)
                    return@use null
                }
                val el = runCatching { json.parseToJsonElement(text ?: "") }.getOrNull()
                val arr = when (el) {
                    is JsonArray -> el
                    is JsonObject -> (el["data"] ?: el["models"]) as? JsonArray
                    else -> null
                }
                if (arr == null) {
                    last = ApiException("这个地址返回的不是模型列表，检查一下地址对不对")
                    return@use null
                }
                arr.mapNotNull { m ->
                    when (m) {
                        is JsonObject -> m["id"].str().ifEmpty { m["name"].str() }.takeIf { it.isNotEmpty() }?.let { it to priceOf(m) }
                        is JsonPrimitive -> m.content to null
                        else -> null
                    }
                }.distinctBy { it.first }.sortedBy { it.first }
            }
            if (result != null) return@withContext b to result
            // 401 这类错误换地址也没用
            if (last?.code == 401 || last?.code == 403) break
        }
        throw last ?: ApiException("获取模型列表失败")
    }

    /** OpenRouter 的模型列表带价格（美元 / token），换算成 元 / 百万 token。别家没有就是 null。 */
    private fun priceOf(m: JsonObject): Price? {
        val p = m["pricing"] as? JsonObject ?: return null
        fun usd(k: String) = p[k].str().toDoubleOrNull()?.takeIf { it >= 0 }?.let { it * 1_000_000 * Pricing.USD_TO_CNY }
        val miss = usd("prompt") ?: return null
        val out = usd("completion") ?: return null
        return Price(hit = usd("input_cache_read") ?: miss, miss = miss, out = out)
    }

    /**
     * 查其他服务的余额。支持 Kimi、硅基流动、OpenRouter 和 DeepSeek 兼容地址；不支持的返回 null。
     */
    suspend fun providerBalance(baseUrl: String, apiKey: String): String? = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        val host = runCatching { java.net.URI(base).host.orEmpty() }.getOrDefault("")
        fun get(url: String): JsonObject {
            val req = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").get().build()
            return http.newCall(req).execute().use { resp ->
                val text = resp.body?.string()
                if (!resp.isSuccessful) throw ApiException(describeError(resp.code, text), resp.code)
                json.parseToJsonElement(text ?: "{}").jsonObject
            }
        }
        fun num(o: JsonObject?, vararg keys: String) = keys.firstNotNullOfOrNull { o?.get(it).str().toDoubleOrNull() }
        when {
            "moonshot" in host -> num(get("$base/users/me/balance")["data"] as? JsonObject, "available_balance")
                ?.let { String.format(java.util.Locale.US, "¥%.2f", it) } ?: "未知"
            "siliconflow" in host -> num(get("$base/user/info")["data"] as? JsonObject, "totalBalance", "balance")
                ?.let { String.format(java.util.Locale.US, "¥%.2f", it) } ?: "未知"
            "openrouter" in host -> {
                val d = get("https://openrouter.ai/api/v1/credits")["data"] as? JsonObject
                val left = (num(d, "total_credits") ?: 0.0) - (num(d, "total_usage") ?: 0.0)
                String.format(java.util.Locale.US, "$%.2f", left)
            }
            "deepseek" in host -> balance(base.removeSuffix("/v1"), apiKey)
            else -> null
        }
    }

    /** OpenRouter 在 usage.cost 里直接给这次扣了多少美元（含缓存写入的溢价），换成元；别家没有这个字段。 */
    internal fun exactCostOf(ep: Endpoint, u: JsonObject): Double? {
        val host = runCatching { java.net.URI(ep.baseUrl).host }.getOrNull().orEmpty()
        if (!host.endsWith("openrouter.ai")) return null
        return (u["cost"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { it * com.jack.ai.data.Pricing.USD_TO_CNY }
    }

    /**
     * 传到一半断了、没收到用量：按发出去的字数和已经收到的字数估一笔（图片按每张 1000 记），缓存命中按不知道（全按没命中算，宁多勿少）。
     * 不记的话这次的钱就漏了，之后重试成功只记重试那一笔，统计和预算都比账单少。
     */
    internal fun estimatedUsage(messages: List<JsonObject>, generated: Int): StreamEvent.Usage {
        var images = 0
        val text = StringBuilder()
        for (m in messages) when (val c = m["content"]) {
            is JsonPrimitive -> text.append(c.content)
            is JsonArray -> c.forEach { part ->
                val o = part as? JsonObject ?: return@forEach
                if (o["type"].str() == "text") text.append(o["text"].str()) else images++
            }
            else -> {}
        }
        messages.forEach { m -> (m["tool_calls"] as? JsonArray)?.let { text.append(it.toString()) } }
        val prompt = com.jack.ai.chat.PromptBuilder.estTokens(text.toString()) + images * 1000
        return StreamEvent.Usage(prompt.coerceAtLeast(1), (generated * 0.6).toInt().coerceAtLeast(1), null)
    }

    /** DeepSeek 用 prompt_cache_hit_tokens，OpenAI 格式用 prompt_tokens_details.cached_tokens；都没有就是 null。 */
    private fun cacheHitOf(u: JsonObject): Int? =
        (u["prompt_cache_hit_tokens"] as? JsonPrimitive)?.intOrNull
            ?: ((u["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.intOrNull

    private fun describeError(code: Int, body: String?): String {
        val detail = body?.let {
            runCatching {
                json.parseToJsonElement(it).jsonObject["error"]?.jsonObject?.get("message").str()
            }.getOrNull()
        }.orEmpty()
        val hint = when (code) {
            400 -> "请求格式有问题"
            401, 403 -> "API Key 不对或没有权限，请到设置里检查"
            404 -> "接口地址或模型名不对"
            402 -> "账户余额不足，请先充值"
            422 -> "参数有误"
            429 -> "请求太频繁，稍等一下再试"
            500, 502, 503 -> "服务器繁忙，稍后再试"
            else -> "请求失败"
        }
        return if (detail.isNotBlank()) "$hint（$code：$detail）" else "$hint（$code）"
    }
}
