package com.jack.ai.api

import com.jack.ai.data.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 文字向量：借用「其他模型服务」里硅基流动的 Key，用 BAAI/bge-m3（中英文都好，1024 维，很便宜）。
 * 没配就返回 null，知识库退回只用关键词检索。
 */
object Embedder {
    data class Engine(val baseUrl: String, val apiKey: String, val model: String = MODEL)

    const val MODEL = "BAAI/bge-m3"
    const val DIM = 1024
    private const val BATCH = 32
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { sharedHttp.newBuilder().callTimeout(60, TimeUnit.SECONDS).build() }
    /** 每轮回忆用：等不了太久，超时就退回关键词。 */
    private val quickClient by lazy { sharedHttp.newBuilder().callTimeout(6, TimeUnit.SECONDS).build() }

    fun engine(providers: List<Provider>): Engine? =
        providers.firstOrNull { it.apiKey.isNotBlank() && "siliconflow" in it.baseUrl.lowercase() }
            ?.let { Engine(it.baseUrl.trimEnd('/'), it.apiKey) }

    /** 一批文字的向量（顺序和输入一样）。网络或接口出错抛 IOException。 */
    fun embed(e: Engine, texts: List<String>, quick: Boolean = false): List<FloatArray> = texts.chunked(BATCH).flatMap { part ->
        val body = buildJsonObject {
            put("model", e.model)
            put("input", buildJsonArray { part.forEach { add(JsonPrimitive(it.take(4000))) } })
            put("encoding_format", "float")
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(e.baseUrl + "/embeddings").header("Authorization", "Bearer ${e.apiKey}").post(body).build()
        (if (quick) quickClient else client).newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("向量服务返回 ${r.code}：${text.take(200)}")
            val data = json.parseToJsonElement(text).jsonObject["data"] as? JsonArray ?: throw IOException("向量服务的返回看不懂")
            data.map { it.jsonObject }.sortedBy { (it["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
                .map { o -> o["embedding"]!!.jsonArray.map { (it as JsonPrimitive).content.toFloat() }.toFloatArray() }
        }
    }

    /** 余弦相似度。 */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0f || nb == 0f) 0f else dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }
}
