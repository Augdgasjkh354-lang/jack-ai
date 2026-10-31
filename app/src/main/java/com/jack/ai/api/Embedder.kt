package com.jack.ai.api

import android.content.Context
import com.jack.ai.data.LocalModel
import com.jack.ai.data.LocalModels
import com.jack.ai.data.Provider
import com.jack.ai.data.Settings
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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 文字向量，两个来源：
 * - 硅基流动的 BAAI/bge-m3（借用「其他模型服务」里的 Key，中英文都好，1024 维，很便宜）；
 * - 手机上的本地模型（设置 → 本地模型，下载后不联网、不花钱）。
 * 不同模型的向量不能混着比，所以各自存一份（按 Engine.id 分开），相似度门槛也各用各的。都没有时返回空，检索退回只用关键词。
 */
object Embedder {
    /** 一个向量来源。recall 算「想起来」，same 算「是同一件事」，similar 算「很像」，floor 以下的不排进检索结果。 */
    sealed interface Engine {
        val id: String
        val label: String
        val recall: Float
        val same: Float
        val similar: Float
        val floor: Float
    }

    data class Remote(val baseUrl: String, val apiKey: String, val model: String = MODEL) : Engine {
        override val id get() = REMOTE_ID
        override val label get() = "硅基流动 bge-m3"
        // bge-m3 上相关的一般 0.6 以上，不相关的 0.3 到 0.5
        override val recall get() = 0.6f
        override val same get() = 0.93f
        override val similar get() = 0.80f
        override val floor get() = 0.35f
    }

    data class Local(val model: LocalModel, val dir: File) : Engine {
        override val id get() = "local-" + model.id
        override val label get() = "本地 " + model.name.substringBefore('（')
        override val recall get() = model.recall
        override val same get() = model.same
        override val similar get() = model.similar
        override val floor get() = model.floor
    }

    const val MODEL = "BAAI/bge-m3"
    const val REMOTE_ID = "bge-m3"
    private const val BATCH = 32
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { sharedHttp.newBuilder().callTimeout(60, TimeUnit.SECONDS).build() }
    /** 每轮回忆用：等不了太久，超时就退回关键词。 */
    private val quickClient by lazy { sharedHttp.newBuilder().callTimeout(6, TimeUnit.SECONDS).build() }

    /** 硅基流动（配了 Key 才有）。 */
    fun remote(providers: List<Provider>): Remote? =
        providers.firstOrNull { it.apiKey.isNotBlank() && "siliconflow" in it.baseUrl.lowercase() }
            ?.let { Remote(it.baseUrl.trimEnd('/'), it.apiKey) }

    /** 下好、选了的本地模型。 */
    fun local(ctx: Context, s: Settings): Local? = LocalModels.active(ctx, s)?.let { Local(it, LocalModels.dir(ctx, it)) }

    /** 按先后试的来源：默认硅基流动在前、本地兜底（没配或连不上时用）；设了「优先用本地」就反过来。 */
    fun engines(ctx: Context, s: Settings): List<Engine> {
        val r = remote(s.providers)
        val l = local(ctx, s)
        return if (s.localPrefer) listOfNotNull(l, r) else listOfNotNull(r, l)
    }

    /** 一批文字的向量（顺序和输入一样）。use 只对本地模型有用（问题和资料加的前缀不同）。出错抛异常。 */
    fun embed(e: Engine, texts: List<String>, quick: Boolean = false, use: EmbedUse = EmbedUse.DOC): List<FloatArray> = when (e) {
        is Remote -> remoteEmbed(e, texts, quick)
        is Local -> LocalEmbed.embed(e.dir, e.model.spec!!, texts.map { it.take(4000) }, use)
    }

    private fun remoteEmbed(e: Remote, texts: List<String>, quick: Boolean): List<FloatArray> = texts.chunked(BATCH).flatMap { part ->
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
