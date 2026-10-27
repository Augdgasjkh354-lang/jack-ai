package com.jack.ai.api

import com.jack.ai.data.Endpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * DeepSeek Files API：图片上传一次，之后每轮只发 file_id，不用每次把整张图转成文字重传。
 * 上传结果存在 deepseek_files.json（按 图片路径 + Key 记），30 天有效，快到期就重传。
 * 上传失败的图片照旧内联发送。
 */
class DeepSeekFiles(private val dir: File) {
    @Serializable
    private data class Entry(val id: String, val expiresAt: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private val file get() = File(dir, "deepseek_files.json")
    private val lock = Any()
    /** 这次运行里上传接口不可用（比如自定义了不支持的地址），就别每轮都试。 */
    @Volatile private var broken = false

    private fun load(): MutableMap<String, Entry> =
        runCatching { json.decodeFromString(serializer, file.readText()).toMutableMap() }.getOrDefault(mutableMapOf())

    private fun save(map: Map<String, Entry>) {
        runCatching {
            val tmp = File(dir, "deepseek_files.json.tmp")
            tmp.writeText(json.encodeToString(serializer, map))
            tmp.renameTo(file)
        }
    }

    private fun keyOf(ep: Endpoint, path: String) = "${ep.apiKey.hashCode()}:$path"

    /** 确保这些图片都已上传，返回 路径 → file_id（没传成的不在里面）。 */
    suspend fun ensure(ep: Endpoint, paths: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (paths.isEmpty() || !ep.deepseek) return@withContext emptyMap()
        val now = System.currentTimeMillis()
        val map = synchronized(lock) { load() }
        // 顺手清掉过期的
        map.entries.removeIf { it.value.expiresAt < now }
        val out = mutableMapOf<String, String>()
        var changed = false
        for (path in paths.distinct()) {
            val k = keyOf(ep, path)
            val hit = map[k]
            if (hit != null && hit.expiresAt - now > TimeUnit.DAYS.toMillis(1)) {
                out[path] = hit.id
                continue
            }
            if (broken) continue
            val f = File(path)
            if (!f.isFile) continue
            val id = runCatching { upload(ep, f) }.getOrElse { e ->
                if (e is ApiException && e.code in listOf(404, 405)) broken = true
                null
            } ?: continue
            map[k] = Entry(id, now + TimeUnit.DAYS.toMillis(30))
            out[path] = id
            changed = true
        }
        // 几个对话可能同时在传：存的时候和文件里现有的合起来，不把别人刚记下的盖掉
        if (changed) synchronized(lock) { save(load().apply { entries.removeIf { it.value.expiresAt < now }; putAll(map) }) }
        out
    }

    /** 某次请求说文件找不到时调用：清空记录，下次全部重传。 */
    fun forget() = synchronized(lock) { file.delete() }

    private fun upload(ep: Endpoint, f: File): String {
        val base = ep.baseUrl.trimEnd('/').removeSuffix("/v1")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", f.name, f.asRequestBody("image/jpeg".toMediaType()))
            .addFormDataPart("purpose", "user_data")
            .addFormDataPart("expires_after[anchor]", "created_at")
            .addFormDataPart("expires_after[seconds]", TimeUnit.DAYS.toSeconds(30).toString())
            .build()
        val request = Request.Builder()
            .url("$base/files")
            .header("Authorization", "Bearer ${ep.apiKey}")
            .post(body)
            .build()
        sharedHttp.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException("上传图片失败（${resp.code}）", resp.code)
            return json.parseToJsonElement(text).jsonObject["id"].str().ifBlank { throw ApiException("上传图片失败", 0) }
        }
    }
}
