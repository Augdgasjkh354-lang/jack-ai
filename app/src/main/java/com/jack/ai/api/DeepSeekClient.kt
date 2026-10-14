package com.jack.ai.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ApiMessage(val role: String, val content: String)

data class StreamDelta(val reasoning: String, val content: String)

class ApiException(message: String) : Exception(message)

class DeepSeekClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES) // 深度思考模型可能很久才出第一个字
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ApiMessage>,
        temperature: Float,
    ): Flow<StreamDelta> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            // 深度思考模型不支持 temperature，传了也会被忽略
            if (model != "deepseek-reasoner") put("temperature", temperature.toDouble())
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject { put("role", m.role); put("content", m.content) })
                }
            })
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
            if (!resp.isSuccessful) throw ApiException(describeError(resp.code, resp.body?.string()))
            val source = resp.body?.source() ?: throw ApiException("服务器没有返回内容")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"]?.jsonObject ?: continue
                val r = delta["reasoning_content"].str()
                val c = delta["content"].str()
                if (r.isNotEmpty() || c.isNotEmpty()) emit(StreamDelta(r, c))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 查询账户余额，返回类似 "¥12.34" 的文字。 */
    suspend fun balance(baseUrl: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/user/balance")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw ApiException(describeError(resp.code, text))
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

    private fun JsonElement?.str(): String =
        if (this == null || this is JsonNull) "" else (this as? JsonPrimitive)?.content ?: ""

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
