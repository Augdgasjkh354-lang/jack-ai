package com.jack.ai.data

import android.content.Context
import android.util.AtomicFile
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.api.sharedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant
import java.time.ZoneId

@Serializable
data class FreeModel(val providerId: String, val model: String, val exhaustedAt: Long = 0, val baseUrl: String = "")

@Serializable
data class FreePoolState(val checkedAt: Long = 0, val models: List<FreeModel> = emptyList(), val error: String = "")

internal fun isFreeTierOnly(body: String?): Boolean {
    val obj = runCatching { Json.parseToJsonElement(body.orEmpty()) as? JsonObject }.getOrNull() ?: return false
    return (obj["code"] as? JsonPrimitive)?.content == "AllocationQuota.FreeTierOnly" ||
        ((obj["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content == "AllocationQuota.FreeTierOnly"
}

internal fun probeResult(statusOk: Boolean, body: String?): Int = when {
    isFreeTierOnly(body) -> 2
    statusOk && runCatching { (Json.parseToJsonElement(body.orEmpty()) as? JsonObject)?.get("choices") is JsonArray }.getOrDefault(false) -> 1
    else -> 0
}

internal fun isChatModel(id: String): Boolean {
    val lower = id.lowercase()
    return listOf("embedding", "rerank", "tts", "asr", "audio", "speech", "image", "video", "transcription", "whisper", "omni", "vl", "ocr", "realtime", "moderation", "translation", "paraformer", "cosyvoice", "sensevoice", "wanx", "wan2", "multimodal", "music").none { it in lower }
}

internal fun freePoolProvider(baseUrl: String): Boolean {
    val host = runCatching { java.net.URI(baseUrl).host.orEmpty().lowercase() }.getOrDefault("")
    return (host.endsWith(".aliyuncs.com") && "dashscope" in host) ||
        host == "api.siliconflow.cn" || host == "api-st.siliconflow.cn"
}

internal fun isSiliconFlow(baseUrl: String): Boolean =
    runCatching { java.net.URI(baseUrl).host.orEmpty().lowercase() in setOf("api.siliconflow.cn", "api-st.siliconflow.cn") }.getOrDefault(false)

/** 仅接收价格页同时明确标为输入、输出免费的模型；页面结构改变时不猜价格。 */
internal fun parseSiliconFreeModels(html: String): Set<String> {
    val chunk = Regex("""self\.__next_f\.push\(\[1,("(?:\\.|[^"\\])*")\]\)""")
    val data = chunk.findAll(html).mapNotNull { match ->
        runCatching { (Json.parseToJsonElement(match.groupValues[1]) as JsonPrimitive).content }.getOrNull()
    }.joinToString("")
    val objects = data.lineSequence().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) return@mapNotNull null
        val key = line.substring(0, i)
        val value = runCatching { Json.parseToJsonElement(line.substring(i + 1)) }.getOrNull() ?: return@mapNotNull null
        key to value
    }.toMap()
    fun resolve(element: JsonElement?): JsonElement? {
        val ref = (element as? JsonPrimitive)?.content ?: return element
        return if (ref.startsWith("$")) objects[ref.drop(1)] else element
    }
    return objects.values.mapNotNull { element ->
        val model = element as? JsonObject ?: return@mapNotNull null
        fun field(key: String) = (model[key] as? JsonPrimitive)?.content.orEmpty()
        if (field("type") != "text" || field("subType") != "chat" || field("status") != "normal") return@mapNotNull null
        val pricing = resolve(model["pricing"]) as? JsonArray ?: return@mapNotNull null
        val prices = pricing.map { resolve(it) as? JsonObject ?: return@mapNotNull null }
        val specs = prices.map { (it["specification"] as? JsonPrimitive)?.content }
        if (!specs.contains("prompt") || !specs.contains("completion")) return@mapNotNull null
        if (prices.any { (it["price"] as? JsonPrimitive)?.content?.toDoubleOrNull() != 0.0 }) return@mapNotNull null
        if (field("price").toDoubleOrNull() != 0.0 || field("inputPrice").toDoubleOrNull() != 0.0) return@mapNotNull null
        field("modelName").takeIf { it.isNotBlank() }
    }.toSet()
}

internal suspend fun siliconFreeModels(): Set<String> = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("https://siliconflow.cn/pricing").get().build()
    sharedHttp.newBuilder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw com.jack.ai.api.ApiException("硅基流动价格查询失败（${resp.code}）")
        parseSiliconFreeModels(resp.body?.string().orEmpty()).takeIf { it.isNotEmpty() }
            ?: throw com.jack.ai.api.ApiException("无法识别硅基流动免费模型价格，请稍后再试")
    }
}

internal fun freeCandidates(state: FreePoolState, settings: Settings, start: Int): List<Endpoint> {
    val eligible = state.models.filter { it.exhaustedAt == 0L }.mapNotNull { m ->
        val p = settings.providers.firstOrNull { it.id == m.providerId && it.apiKey.isNotBlank() && freePoolProvider(it.baseUrl) } ?: return@mapNotNull null
        val base = p.baseUrl.trimEnd('/')
        if (m.baseUrl.isNotBlank() && m.baseUrl != base && m.baseUrl != "$base/v1") return@mapNotNull null
        settings.endpoint(customModelKey(p.id, m.model))?.let { ep -> if (m.baseUrl.isNotBlank()) ep.copy(baseUrl = m.baseUrl) else ep }
    }
    if (eligible.isEmpty()) return emptyList()
    val offset = Math.floorMod(start, eligible.size)
    return eligible.drop(offset) + eligible.take(offset)
}

internal fun chooseFreeOrFallback(candidates: List<Endpoint>, fallback: Endpoint?): Endpoint? =
    candidates.firstOrNull() ?: fallback

/** 本地池只存模型和状态，不保存密钥。 */
object FreePool {
    private val mutex = Mutex()
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "free_pool.json"))
    fun load(context: Context): FreePoolState = synchronized(lock) {
        runCatching { json.decodeFromString<FreePoolState>(String(file(context).readFully())) }.getOrDefault(FreePoolState())
    }
    private fun save(context: Context, state: FreePoolState) = synchronized(lock) {
        val f = file(context)
        val out = f.startWrite()
        try { out.write(json.encodeToString(FreePoolState.serializer(), state).toByteArray()); f.finishWrite(out) }
        catch (e: Exception) { f.failWrite(out); throw e }
    }
    fun exhaust(context: Context, ep: Endpoint) = synchronized(lock) {
        val state = load(context)
        save(context, state.copy(models = state.models.map { if (customModelKey(it.providerId, it.model) == ep.key) it.copy(exhaustedAt = System.currentTimeMillis()) else it }))
    }
    suspend fun probe(context: Context, settings: Settings, force: Boolean = false): FreePoolState = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val old = load(context)
            val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            if (!force && old.checkedAt > 0 && Instant.ofEpochMilli(old.checkedAt).atZone(ZoneId.systemDefault()).toLocalDate() == today) return@withContext old
            val providers = settings.providers.filter { it.apiKey.isNotBlank() && freePoolProvider(it.baseUrl) }
            if (providers.isEmpty()) return@withContext old
            val found = mutableListOf<FreeModel>()
            val errors = mutableListOf<String>()
            val client = DeepSeekClient()
            for (p in providers) {
                val freePrices = if (isSiliconFlow(p.baseUrl)) {
                    try { siliconFreeModels() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { errors += "${p.name}：${e.message ?: "价格查询失败"}"; continue }
                } else null
                val listed = try { client.listModels(p.baseUrl, p.apiKey, p.workspaceId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { errors += "${p.name}：${e.message ?: "获取模型列表失败"}"; null }
                if (listed == null) { found += old.models.filter { it.providerId == p.id && it.exhaustedAt != 0L }; continue }
                for ((model, _) in listed.second.filter { isChatModel(it.first) && (freePrices == null || it.first in freePrices) }) {
                    val body = buildJsonObject {
                        put("model", model); put("max_tokens", 1); put("stream", false)
                        put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "Hi") }) })
                    }.toString()
                    val result = try {
                        val request = Request.Builder().url(listed.first.trimEnd('/') + "/chat/completions").header("Authorization", "Bearer ${p.apiKey}").post(body.toRequestBody("application/json".toMediaType())).build()
                        sharedHttp.newCall(request).execute().use { resp ->
                            val text = resp.body?.string()
                            when (probeResult(resp.isSuccessful, text)) {
                                2 -> FreeModel(p.id, model, now, listed.first)
                                1 -> FreeModel(p.id, model, baseUrl = listed.first)
                                else -> null
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                    if (result != null) found += result
                    else old.models.firstOrNull { it.providerId == p.id && it.model == model }?.takeIf { it.exhaustedAt != 0L }?.let { found += it }
                }
            }
            synchronized(lock) {
                val latest = load(context)
                val merged = found.map { m -> latest.models.firstOrNull { it.providerId == m.providerId && it.model == m.model && it.exhaustedAt > now } ?: m }
                FreePoolState(now, merged, errors.joinToString("；")).also { save(context, it) }
            }
        }
    }
}
