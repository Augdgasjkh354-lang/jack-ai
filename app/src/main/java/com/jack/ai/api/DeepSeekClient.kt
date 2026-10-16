package com.jack.ai.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    data class Usage(val promptTokens: Int, val completionTokens: Int, val cacheHit: Int? = null) : StreamEvent
}

class ApiException(message: String, val code: Int = 0) : Exception(message)

internal fun JsonElement?.str(): String =
    if (this == null || this is JsonNull) "" else (this as? JsonPrimitive)?.content ?: ""

internal val sharedHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES) // 深度思考模型可能很久才出第一个字
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

class DeepSeekClient(
    /** 非流式请求的用量回调：模型、输入、缓存命中、输出。 */
    private val onUsage: ((Endpoint, Int, Int?, Int) -> Unit)? = null,
) {
    private val http = sharedHttp
    private val json = Json { ignoreUnknownKeys = true }

    fun stream(
        ep: Endpoint,
        messages: List<JsonObject>,
        thinking: ThinkLevel,
        temperature: Float,
        tools: JsonArray? = null,
    ): Flow<StreamEvent> = flow {
        val body = buildJsonObject {
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
            .url(ep.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${ep.apiKey}")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        call.execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(describeError(resp.code, resp.body?.string()), resp.code)
            val source = resp.body?.source() ?: throw ApiException("服务器没有返回内容")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                (obj["usage"] as? JsonObject)?.let { u ->
                    val p = (u["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
                    val c = (u["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
                    if (p > 0) emit(StreamEvent.Usage(p, c, cacheHitOf(u)))
                }
                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"] as? JsonObject ?: continue
                // DeepSeek、通义、Kimi 用 reasoning_content，OpenRouter 用 reasoning
                val r = delta["reasoning_content"].str().ifEmpty { delta["reasoning"].str() }
                val c = delta["content"].str()
                if (r.isNotEmpty() || c.isNotEmpty()) emit(StreamEvent.Text(r, c))
                (delta["tool_calls"] as? JsonArray)?.forEach { el ->
                    val tc = el.jsonObject
                    val fn = tc["function"] as? JsonObject
                    emit(
                        StreamEvent.ToolCall(
                            index = (tc["index"] as? JsonPrimitive)?.intOrNull ?: 0,
                            id = tc["id"].str(),
                            name = fn?.get("name").str(),
                            arguments = fn?.get("arguments").str(),
                        )
                    )
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 一次性（非流式）请求，用于压缩、做梦这类后台任务。不开思考，速度快、篇幅留给正文。
     */
    suspend fun complete(
        ep: Endpoint,
        messages: List<JsonObject>,
        maxTokens: Int = 16384,
        jsonMode: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", ep.model)
            put("stream", false)
            if (ep.deepseek) {
                put("thinking", buildJsonObject { put("type", "disabled") })
                put("max_tokens", maxTokens)
            }
            // 推理模型大多不接受自定义 temperature
            if (!ep.reasoning || ep.deepseek) put("temperature", 0.7)
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
            val msg = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
            msg?.get("content").str().ifBlank { throw ApiException("模型没有返回内容") }
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
    suspend fun listModels(baseUrl: String, apiKey: String): Pair<String, List<Pair<String, Price?>>> = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        if (!base.startsWith("http")) throw ApiException("接口地址要以 http:// 或 https:// 开头")
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
