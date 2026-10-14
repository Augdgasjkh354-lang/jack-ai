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
import com.jack.ai.data.ThinkLevel

sealed interface StreamEvent {
    data class Text(val reasoning: String, val content: String) : StreamEvent
    /** 工具调用是分片流式返回的，按 index 拼起来。 */
    data class ToolCall(val index: Int, val id: String, val name: String, val arguments: String) : StreamEvent
    /** 本次请求用掉的 token（最后一个数据块里返回）。 */
    data class Usage(val promptTokens: Int, val completionTokens: Int) : StreamEvent
}

class ApiException(message: String, val code: Int = 0) : Exception(message)

internal fun JsonElement?.str(): String =
    if (this == null || this is JsonNull) "" else (this as? JsonPrimitive)?.content ?: ""

internal val sharedHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES) // 深度思考模型可能很久才出第一个字
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

class DeepSeekClient {
    private val http = sharedHttp
    private val json = Json { ignoreUnknownKeys = true }

    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<JsonObject>,
        thinking: ThinkLevel,
        temperature: Float,
        tools: JsonArray? = null,
    ): Flow<StreamEvent> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("thinking", buildJsonObject { put("type", if (thinking.effort == null) "disabled" else "enabled") })
            if (thinking.effort != null) {
                put("reasoning_effort", thinking.effort)
            } else {
                // 思考模式下 temperature 不起作用，只在不思考时传
                put("temperature", temperature.toDouble())
            }
            put("messages", JsonArray(messages))
            if (tools != null) put("tools", tools)
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }.toString()

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
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
                    if (p > 0) emit(StreamEvent.Usage(p, c))
                }
                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"] as? JsonObject ?: continue
                val r = delta["reasoning_content"].str()
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
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<JsonObject>,
        maxTokens: Int = 16384,
        jsonMode: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("temperature", 0.7)
            put("max_tokens", maxTokens)
            if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", JsonArray(messages))
        }.toString()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw ApiException(describeError(resp.code, text), resp.code)
            val obj = json.parseToJsonElement(text ?: "{}").jsonObject
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

    private fun describeError(code: Int, body: String?): String {
        val detail = body?.let {
            runCatching {
                json.parseToJsonElement(it).jsonObject["error"]?.jsonObject?.get("message").str()
            }.getOrNull()
        }.orEmpty()
        val hint = when (code) {
            400 -> "请求格式有问题"
            401 -> "API Key 不对，请到设置里检查"
            402 -> "账户余额不足，请到 DeepSeek 官网充值"
            422 -> "参数有误"
            429 -> "请求太频繁，稍等一下再试"
            500, 502, 503 -> "DeepSeek 服务器繁忙，稍后再试"
            else -> "请求失败"
        }
        return if (detail.isNotBlank()) "$hint（$code：$detail）" else "$hint（$code）"
    }
}
