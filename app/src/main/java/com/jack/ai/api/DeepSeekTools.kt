package com.jack.ai.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 2026-11-13 开放的工具接口（api.deepseek.com/v1/tools/…），用的就是设置里那把 DeepSeek Key：
 * - asr：语音转文字，WAV/Ogg Opus，一次最多 120 秒、8 MB，自动认语言。0.39 元/小时（限时 5 折，原价 0.78）。
 * - ocr：PDF、Word、PPT、图片认字，按页返回，最多 1000 页、100 MB。1.5 元/1000 页。
 * - tts：文字转语音，stella（女）、tide（男），一次最多 1 万计费字符（汉字算 2 个）。1.5 元/万字符（限时 5 折，原价 3）。
 */
object DeepSeekTools {
    private const val BASE = "https://api.deepseek.com/v1/tools"

    /** 一段录音最长多少秒（接口上限 120，留点余量）。 */
    const val ASR_MAX_SEC = 110

    /** 计入用量统计时的名字和单价（元）。 */
    const val ASR_KEY = "deepseek-asr"
    const val OCR_KEY = "deepseek-ocr"
    const val TTS_KEY = "deepseek-tts"
    const val ASR_PRICE_PER_HOUR = 0.39
    const val OCR_PRICE_PER_PAGE = 0.0015
    const val TTS_PRICE_PER_CHAR = 1.5 / 10_000

    /** TTS 一次最多读多少个计费字符（接口上限 1 万）。 */
    const val TTS_MAX_CHARS = 9_000

    private fun err(what: String, code: Int, body: String): ApiException {
        val msg = runCatching {
            ((Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject)?.get("message")?.let { (it as JsonPrimitive).content }
        }.getOrNull() ?: body.take(200)
        val why = when (code) {
            401 -> "DeepSeek Key 不对"
            402 -> "DeepSeek 余额不足"
            408 -> "上传太慢，网络不好"
            413 -> "文件太大"
            429 -> "同时在做的太多了，等一会儿再试"
            503 -> "DeepSeek 那边忙，等一会儿再试"
            504 -> "处理超时"
            else -> msg
        }
        return ApiException("DeepSeek $what 出错（$code）：$why", code)
    }

    /** 语音转文字：一段不超过 120 秒的 WAV。返回 (文字, 音频毫秒数)。 */
    fun asr(key: String, wav: File): Pair<String, Long> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url("$BASE/asr").header("Authorization", "Bearer $key").post(body).build()
        sharedHttp.newBuilder().readTimeout(180, TimeUnit.SECONDS).callTimeout(300, TimeUnit.SECONDS).build()
            .newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw err("转文字", resp.code, text)
                val o = Json.parseToJsonElement(text) as JsonObject
                return ((o["text"] as? JsonPrimitive)?.content.orEmpty()) to ((o["duration_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L)
            }
    }

    /** 一页认出来的字；truncated = 这一页太长没认完。 */
    data class OcrPage(val text: String, val truncated: Boolean)

    /**
     * 认字：layout = true 时按版面认（每页都走模型，能保留图表位置）；否则 Word、PPT 里没图的直接取文字，其余逐页认。
     * 结果会把 <｜ref｜>…<｜box｜>[[…]] 这类坐标标记去掉，只留文字。
     */
    fun ocr(key: String, file: File, layout: Boolean = false): List<OcrPage> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("purpose", if (layout) "rich_layout_recognition" else "text_recognition")
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val req = Request.Builder().url("$BASE/ocr").header("Authorization", "Bearer $key").post(body).build()
        sharedHttp.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(200, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).build()
            .newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw err("认字", resp.code, text)
                val o = Json.parseToJsonElement(text.trim()) as JsonObject
                // 开始认之后状态已经是 200，失败时正文里是 error
                (o["error"] as? JsonObject)?.let { e ->
                    throw ApiException("DeepSeek 认字出错：" + ((e["message"] as? JsonPrimitive)?.content ?: "未知错误"), 500)
                }
                return (o["pages"] as? JsonArray).orEmpty().map { p ->
                    val po = p as JsonObject
                    OcrPage(cleanOcr((po["text"] as? JsonPrimitive)?.content.orEmpty()), (po["finish_reason"] as? JsonPrimitive)?.content == "length")
                }
            }
    }

    private val OCR_BOX = Regex("""<｜box｜>.*?<｜/box｜>""", RegexOption.DOT_MATCHES_ALL)
    private val OCR_TAG = Regex("""<｜/?[a-z_]+｜>""")

    /** 去掉 OCR 结果里的坐标和标记，只留文字。 */
    fun cleanOcr(s: String): String = s.replace(OCR_BOX, "").replace(OCR_TAG, "").replace(Regex("\n{3,}"), "\n\n").trim()

    /** 计费字符数：汉字（以及其他非 ASCII 的字）算 2 个，别的算 1 个。 */
    fun ttsChars(s: String): Int = s.count { it.code >= 128 } + s.length

    /** 按计费字符把长文切成几段，尽量在句号、换行处断开。 */
    fun ttsChunks(text: String, max: Int = TTS_MAX_CHARS): List<String> {
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            if (ttsChars(rest) <= max) { out += rest; break }
            var n = 0
            var cut = 0
            var lastBreak = -1
            while (cut < rest.length) {
                val w = if (rest[cut].code < 128) 1 else 2
                if (n + w > max) break
                n += w
                if (rest[cut] in "。！？!?；;\n") lastBreak = cut
                cut++
            }
            val end = if (lastBreak > cut / 2) lastBreak + 1 else cut
            out += rest.substring(0, end).trim()
            rest = rest.substring(end).trim()
        }
        return out.filter { it.isNotEmpty() }
    }

    /**
     * 文字转语音：返回 24 kHz、16 位、单声道的 PCM 流（边合成边返回），用完要 close。
     * 文字不能超过 1 万计费字符，长文先用 ttsChunks 切。
     */
    fun ttsStream(key: String, text: String, voice: String): InputStream {
        val json = buildJsonObject {
            put("text", text)
            put("voice_id", if (voice == "tide") "tide" else "stella")
            put("format", "pcm")
            put("filter_markdown", true)
        }.toString()
        val req = Request.Builder().url("$BASE/tts").header("Authorization", "Bearer $key")
            .post(json.toRequestBody("application/json".toMediaType())).build()
        val resp = sharedHttp.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
            .newCall(req).execute()
        if (!resp.isSuccessful) {
            val body = resp.use { it.body?.string().orEmpty() }
            throw err("朗读", resp.code, body)
        }
        return resp.body!!.byteStream()
    }

    const val TTS_RATE = 24_000
}
