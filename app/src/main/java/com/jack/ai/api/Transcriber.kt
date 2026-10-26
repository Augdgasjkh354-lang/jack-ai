package com.jack.ai.api

import com.jack.ai.data.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 语音转文字：借用「其他模型服务」里填过的 Key，走 OpenAI 格式的 /audio/transcriptions。
 * 优先硅基流动的 SenseVoice（中文好、便宜），其次 OpenAI、Groq 的 Whisper。
 */
object Transcriber {
    /** 用哪家：(服务, 模型)。 */
    data class Engine(val name: String, val baseUrl: String, val apiKey: String, val model: String)

    private val KNOWN = listOf(
        "siliconflow" to "FunAudioLLM/SenseVoiceSmall",
        "groq.com" to "whisper-large-v3-turbo",
        "api.openai.com" to "whisper-1",
    )

    fun engine(providers: List<Provider>): Engine? {
        for ((host, model) in KNOWN) {
            val p = providers.firstOrNull { it.apiKey.isNotBlank() && host in it.baseUrl.lowercase() } ?: continue
            return Engine(p.name, p.baseUrl.trimEnd('/'), p.apiKey, model)
        }
        return null
    }

    const val NO_ENGINE = "转文字要用硅基流动（推荐，SenseVoice 中文准、几乎免费）、OpenAI 或 Groq 的 Key。" +
        "请用户在 设置 → 其他模型服务 里添加「硅基流动」并填上 Key（cloud.siliconflow.cn 注册就有），填好再试。"

    /** SenseVoice 会在文字里带上语言、情绪的标记（<|zh|><|NEUTRAL|>），去掉。 */
    fun clean(text: String): String = text.replace(Regex("""<\|[^|>]*\|>"""), "").trim()

    fun transcribe(e: Engine, wav: File): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", e.model)
            .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url("${e.baseUrl}/audio/transcriptions")
            .header("Authorization", "Bearer ${e.apiKey}")
            .post(body).build()
        var last: IOException? = null
        repeat(2) {
            try {
                sharedHttp.newBuilder().readTimeout(180, TimeUnit.SECONDS).callTimeout(300, TimeUnit.SECONDS).build()
                    .newCall(req).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) throw ApiException(
                            "${e.name} 转文字出错（${resp.code}）：" + text.take(200) + if (resp.code == 401) "。Key 可能不对，请用户到设置里检查。" else "")
                        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                        return clean((o?.get("text") as? JsonPrimitive)?.content ?: text)
                    }
            } catch (x: IOException) { last = x }
        }
        throw last ?: IOException("转文字失败")
    }
}
