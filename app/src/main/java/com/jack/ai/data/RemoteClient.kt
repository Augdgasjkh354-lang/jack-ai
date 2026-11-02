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

/** 配过对的一台设备（iPhone、电脑上的浏览器）。时间是毫秒。 */
data class RemoteDevice(val id: String, val name: String, val created: Long, val lastSeen: Long)

/** 发起一次配对：二维码里放 token，用户也可以输 6 位码；expires 是秒。 */
data class RemotePairing(val code: String, val token: String, val expires: Long)

/**
 * 认领站点的结果：新认领的（第一台是站长）、本来就是成员的、
 * 站点已经有站长了要邀请码、邀请码不对（用过、过期）、只认一台手机的老站点被别的密钥占了。
 */
enum class ClaimResult { CLAIMED, ALREADY_MINE, NEED_INVITE, BAD_INVITE, TAKEN }

/** 站点情况：代码第几版、这台手机是不是站长。 */
data class SiteInfo(val version: Int, val admin: Boolean)

/** 站长发的邀请码：一次性，expires 是秒。 */
data class RemoteInvite(val code: String, val expires: Long)

/** 站点成员（共用这个站点的手机）。me 是自己这台。 */
data class RemoteMember(val id: String, val name: String, val admin: Boolean, val me: Boolean, val created: Long, val lastSeen: Long)

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
            if (r.code == 401) throw IOException(if ("尚未认领" in text) "站点还没被认领：点「连接站点」" else "站点不认这把密钥：可能站点重新部署过、被站长移出了，或者还没加入")
            if (!r.isSuccessful) throw IOException("站点返回 ${r.code}：" + text.take(200))
            return text
        }
    }

    private fun post(path: String, o: JsonObject) = call(path, o.toString().toRequestBody(jsonType))

    /**
     * 认领站点：新部署的站点第一台来认领的手机是站长；之后的手机要带站长给的邀请码（invite）才能加入。
     * name 是成员列表里显示的名字。409 是只认一台手机的老站点被别的密钥占了（用 KAVI_KEY 管的老站点，那把和这把一样也算是自己的）。
     */
    fun claim(invite: String = "", name: String = ""): ClaimResult {
        val body = buildJsonObject { if (invite.isNotBlank()) put("invite", invite.trim()); if (name.isNotBlank()) put("name", name) }
        val req = Request.Builder().url(url("/api/device/claim")).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(jsonType)).build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 409) return if (runCatching { ping() }.isSuccess) ClaimResult.ALREADY_MINE else ClaimResult.TAKEN
            if (r.code == 403) return if ("need_invite" in text) ClaimResult.NEED_INVITE else ClaimResult.BAD_INVITE
            if (r.code == 429) throw IOException("试错太多次，十分钟后再试")
            if (r.code == 404) throw IOException("站点代码太旧，不支持自动认领：按下面的说明重新部署一下")
            if (!r.isSuccessful) throw IOException("站点返回 ${r.code}：" + text.take(200))
            val claimed = runCatching { json.parseToJsonElement(text).jsonObject.str("claimed") }.getOrNull() == "true"
            return if (claimed) ClaimResult.CLAIMED else ClaimResult.ALREADY_MINE
        }
    }

    /** 换密钥：用旧密钥证明身份，换成新的；站点上配过对的设备全部作废。 */
    fun rekey(newKey: String) { post("/api/device/rekey", buildJsonObject { put("new_key", newKey) }) }

    /** 发起配对：5 分钟有效、只能用一次，新的出来旧的作废。 */
    fun startPairing(): RemotePairing {
        val o = json.parseToJsonElement(post("/api/device/pair/start", buildJsonObject {})).jsonObject
        return RemotePairing(o.str("code"), o.str("token"), o.str("expires").toLongOrNull() ?: 0)
    }

    /** 扫码配对的网址：token 放在 # 后面，不进站点的访问日志。 */
    fun pairUrl(token: String) = "$base/pair#t=$token"

    fun devices(): List<RemoteDevice> = (json.parseToJsonElement(call("/api/device/devices")) as? JsonArray).orEmpty().map { it.jsonObject }.map { d ->
        RemoteDevice(d.str("id"), d.str("name"), d.str("created").toLongOrNull() ?: 0, d.str("last_seen").toLongOrNull() ?: 0)
    }

    fun revoke(id: String) { post("/api/device/devices/revoke", buildJsonObject { put("id", id) }) }

    /** 站点版本、自己是不是站长（老站点没有 admin，只有一台手机，算站长）。 */
    fun info(): SiteInfo {
        val o = json.parseToJsonElement(call("/api/device/ping")).jsonObject
        return SiteInfo(o.str("version").toIntOrNull() ?: 0, o["admin"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: true)
    }

    /** 站长发一个邀请码（7 天有效、只能用一次）。 */
    fun invite(): RemoteInvite {
        val o = json.parseToJsonElement(post("/api/device/invite", buildJsonObject {})).jsonObject
        return RemoteInvite(o.str("code"), o.str("expires").toLongOrNull() ?: 0)
    }

    fun members(): List<RemoteMember> = (json.parseToJsonElement(call("/api/device/members")) as? JsonArray).orEmpty().map { it.jsonObject }.map { m ->
        RemoteMember(m.str("id"), m.str("name"), m.str("admin") == "true", m.str("me") == "true",
            m.str("created").toLongOrNull() ?: 0, m.str("last_seen").toLongOrNull() ?: 0)
    }

    /** 站长把一个成员移出：他的对话、文件、配过的设备一起删。 */
    fun removeMember(id: String) { post("/api/device/members/remove", buildJsonObject { put("id", id) }) }

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
