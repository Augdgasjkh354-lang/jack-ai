package com.jack.ai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 站点上的一个文件：R2 里的 key、名字、大小、类型。 */
@Serializable
data class RemoteFile(val key: String, val name: String, val size: Long = 0, val type: String = "")

/** 用户在别的设备上发来的一条消息。 */
data class RemoteMessage(val id: Long, val conv: String, val text: String, val files: List<RemoteFile>)

/** 来取一次的结果：新消息、用户回了的确认（id → 同不同意）、要停的回答（用户消息的 id）。 */
data class RemotePoll(val messages: List<RemoteMessage>, val answers: Map<String, Boolean>, val stops: List<Long>)

/**
 * 和「Kavi 远程」站点说话（站点代码在仓库的 remote-site/）：手机主动去取，不用公网地址。
 * 走 App 的网络（开着 Kavi 专用代理时国外站点自动走代理）。所有请求带 Authorization: Bearer <密钥>。
 */
class RemoteClient(base: String, private val key: String, http: OkHttpClient) {
    private val base = base.trim().trimEnd('/')
    private val client = http.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    private fun url(path: String, vararg query: Pair<String, String>): String {
        val u = ("$base$path").toHttpUrlOrNull() ?: throw IOException("站点地址不对：$base")
        return u.newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()
    }

    private fun call(path: String, body: RequestBody? = null, vararg query: Pair<String, String>): String {
        val req = Request.Builder().url(url(path, *query)).header("Authorization", "Bearer $key")
            .apply { if (body != null) post(body) }.build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) throw IOException("站点说密钥不对：Kavi 里的密钥要和站点 Secrets 里的 KAVI_KEY 一样")
            if (!r.isSuccessful) throw IOException("站点返回 ${r.code}：" + text.take(200))
            return text
        }
    }

    private fun post(path: String, o: JsonObject) = call(path, o.toString().toRequestBody(jsonType))

    /** 测试连接：密钥对不对、站点代码是第几版（不取消息）。 */
    fun ping(): Int = json.parseToJsonElement(call("/api/device/ping")).jsonObject.str("version").toIntOrNull() ?: 0

    fun poll(): RemotePoll {
        val o = json.parseToJsonElement(call("/api/device/poll")).jsonObject
        val msgs = (o["messages"] as? JsonArray).orEmpty().map { it.jsonObject }.map { m ->
            RemoteMessage(
                id = m.str("id").toLong(), conv = m.str("conv"), text = m.str("text"),
                files = (m["files"] as? JsonArray).orEmpty().mapNotNull { runCatching { json.decodeFromJsonElement(RemoteFile.serializer(), it) }.getOrNull() },
            )
        }
        val answers = (o["answers"] as? JsonArray).orEmpty().map { it.jsonObject }.associate { a -> a.str("id") to (a.str("ok") == "true") }
        val stops = (o["stops"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
        return RemotePoll(msgs, answers, stops)
    }

    /** 写回答（同一条消息反复写，网页上实时更新）。state：running / done / error。 */
    fun reply(replyTo: Long, text: String, steps: List<String>, files: List<RemoteFile>, state: String) {
        post("/api/device/reply", buildJsonObject {
            put("reply_to", replyTo); put("text", text); put("state", state)
            put("steps", buildJsonArray { steps.forEach { add(JsonPrimitive(it)) } })
            put("files", buildJsonArray { files.forEach { add(json.encodeToJsonElement(RemoteFile.serializer(), it)) } })
        })
    }

    /** 请用户在网页上确认一件事，返回确认的 id（答案下次 poll 时拿到）。 */
    fun ask(msg: Long, title: String, body: String, action: String): String =
        json.parseToJsonElement(post("/api/device/ask", buildJsonObject { put("msg", msg); put("title", title); put("body", body); put("action", action) }))
            .jsonObject.str("id")

    /** 不等了：把网页上的确认撤掉。 */
    fun cancelAsk(id: String) { post("/api/device/cancel", buildJsonObject { put("id", id) }) }

    fun upload(file: File, type: String): RemoteFile =
        json.decodeFromString(RemoteFile.serializer(), call("/api/device/upload", file.asRequestBody(type.toMediaType()), "name" to file.name, "type" to type))

    fun download(f: RemoteFile, to: File): File {
        val req = Request.Builder().url(url("/api/device/file", "key" to f.key)).header("Authorization", "Bearer $key").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("取文件「${f.name}」失败：${r.code}")
            to.parentFile?.mkdirs()
            r.body!!.byteStream().use { input -> to.outputStream().use { input.copyTo(it) } }
        }
        return to
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()

    companion object {
        /** 生成一个密钥：32 个随机字节，URL 安全的 base64。 */
        fun newKey(): String {
            val b = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
    }
}
