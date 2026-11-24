package com.jack.ai.data.packs

import android.content.Context
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import com.jack.ai.data.backgroundEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * App 里的 `kavi.*`：每样都先看说明文件里声明过没有，没声明的直接报错（错误文字会交给工具包代码）。
 * [workspace]：当前对话的文件夹（声明了 files: workspace 才能碰）；[callTool]：调 Kavi 自带的只读工具。
 */
class AppPackHost(
    private val ctx: Context,
    private val settings: Settings,
    private val pack: InstalledPack,
    private val workspace: File?,
    private val callTool: (suspend (String, String) -> String)?,
) : PackHost {
    private val m = pack.manifest
    private val perm = m.permissions
    private val json = Json { ignoreUnknownKeys = true }

    override fun call(op: String, arg: JsonElement): JsonElement {
        val a = arg as? JsonObject ?: JsonObject(emptyMap())
        return when (op) {
            "files.read" -> JsonPrimitive(file(a.s("path")).also { if (!it.isFile) error("没有这个文件：${a.s("path")}") }.readText())
            "files.write" -> {
                val f = file(a.s("path"))
                val text = a.s("text")
                if (text.length > 5_000_000) error("一次最多写 5MB")
                f.parentFile?.mkdirs(); f.writeText(text); JsonNull
            }
            "files.list" -> {
                val d = file(a.s("path"), allowRoot = true)
                JsonArray(d.listFiles().orEmpty().sortedBy { it.name }.map { JsonPrimitive(it.name + if (it.isDirectory) "/" else "") })
            }
            "files.remove" -> { file(a.s("path")).deleteRecursively(); JsonNull }
            "files.exists" -> JsonPrimitive(file(a.s("path")).exists())
            "store.get" -> loadStore()[a.s("key")] ?: JsonNull
            "store.set" -> {
                val all = loadStore().toMutableMap()
                val v = a["value"]
                if (v == null || v is JsonNull) all.remove(a.s("key")) else all[a.s("key")] = v
                val text = json.encodeToString(JsonObject.serializer(), JsonObject(all))
                if (text.length > 256 * 1024) error("kavi.store 最多存 256KB，大的东西用 kavi.files")
                Packs.storeFile(ctx, m.id).apply { parentFile?.mkdirs() }.writeText(text)
                JsonNull
            }
            "store.keys" -> JsonArray(loadStore().keys.map { JsonPrimitive(it) })
            "notify" -> {
                if (!perm.notify) error("这个工具包没有声明 notify 权限，不能发通知")
                com.jack.ai.data.Routines.notify(ctx, "pack_${m.id}_${System.currentTimeMillis() % 100_000}", a.s("title").take(60).ifBlank { m.name }, a.s("body").take(500))
                JsonNull
            }
            else -> error("不认识的操作 $op")
        }
    }

    override suspend fun callAsync(op: String, arg: JsonElement): JsonElement {
        val a = arg as? JsonObject ?: JsonObject(emptyMap())
        return when (op) {
            "http" -> http(a)
            "sleep" -> { delay(a.s("ms").toDoubleOrNull()?.toLong()?.coerceIn(0, 60_000) ?: 0); JsonNull }
            "ai" -> {
                if (!perm.ai) error("这个工具包没有声明 ai 权限，不能用模型")
                val ep = settings.backgroundEndpoint() ?: error("还没有设置能用的模型")
                val msgs = buildList {
                    a.s("system").takeIf { it.isNotBlank() }?.let { s -> add(buildJsonObject { put("role", "system"); put("content", s.take(20_000)) }) }
                    add(buildJsonObject { put("role", "user"); put("content", a.s("prompt").take(60_000)) })
                }
                JsonPrimitive(com.jack.ai.api.DeepSeekClient(Store(ctx).usage("工具包 · ${m.name}")).complete(ep, msgs, maxTokens = 2000, jsonMode = a.s("json") == "true"))
            }
            "tool" -> {
                val name = a.s("name")
                if (name !in perm.tools) error("这个工具包没有声明能调 $name（permissions.tools）")
                if (name !in PackFormat.CALLABLE_TOOLS) error("工具包不能调 $name")
                val run = callTool ?: error("这里调不了 Kavi 的工具")
                JsonPrimitive(run(name, (a["args"] as? JsonObject ?: JsonObject(emptyMap())).toString()))
            }
            else -> error("不认识的操作 $op")
        }
    }

    // ---------- 联网 ----------

    private suspend fun http(a: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val base = a.s("url").toHttpUrlOrNull() ?: error("网址不对：${a.s("url").take(200)}")
        if (base.scheme != "https" && base.scheme != "http") error("只能访问 http/https 网址")
        if (!PackFormat.allows(perm.net, base.host)) error("这个工具包没有声明能访问 ${base.host}（permissions.net 里只有：${perm.net.joinToString().ifBlank { "无" }}）")
        val url = base.newBuilder().apply {
            (a["query"] as? JsonObject)?.forEach { (k, v) -> if (v !is JsonNull) addQueryParameter(k, (v as? JsonPrimitive)?.content ?: v.toString()) }
        }.build()
        val method = a.s("method").uppercase().ifBlank { "GET" }
        if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) error("不支持 $method")
        val headers = (a["headers"] as? JsonObject).orEmpty()
        val body = a["body"]
        val type = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.let { (it as? JsonPrimitive)?.content }
        val reqBody = when {
            body == null || body is JsonNull -> if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody(null) else null
            body is JsonPrimitive && body.isString -> body.content.toRequestBody((type ?: "text/plain; charset=utf-8").toMediaTypeOrNull())
            else -> body.toString().toRequestBody((type ?: "application/json; charset=utf-8").toMediaTypeOrNull())
        }
        val req = Request.Builder().url(url).method(method, reqBody).header("User-Agent", "KaviPack/${m.id}").apply {
            headers.forEach { (k, v) -> if (v !is JsonNull) header(k, (v as? JsonPrimitive)?.content ?: v.toString()) }
        }.build()
        // 跳转到没声明的网站不跟
        val client = com.jack.ai.api.sharedHttp.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var r = req
        repeat(5) {
            client.newCall(r).execute().use { resp ->
                val loc = resp.header("Location")
                if (resp.isRedirect && loc != null) {
                    val next = resp.request.url.resolve(loc) ?: error("跳转地址不对")
                    if (!PackFormat.allows(perm.net, next.host)) error("网站跳转到了没声明的 ${next.host}，没跟过去")
                    r = r.newBuilder().url(next).apply { if (resp.code == 303) method("GET", null) }.build()
                    return@use
                }
                val bytes = resp.body?.source()?.let { src -> src.request(MAX_BODY + 1L); src.buffer.readByteArray(minOf(src.buffer.size, MAX_BODY + 1L)) } ?: ByteArray(0)
                if (bytes.size > MAX_BODY) error("返回的内容太大（超过 ${MAX_BODY / 1024 / 1024}MB）")
                return@withContext buildJsonObject {
                    put("status", resp.code)
                    put("ok", resp.isSuccessful)
                    put("url", resp.request.url.toString())
                    put("headers", buildJsonObject { resp.headers.names().forEach { n -> put(n.lowercase(), resp.headers(n).joinToString(", ")) } })
                    put("body", String(bytes, resp.body?.contentType()?.charset() ?: Charsets.UTF_8))
                }
            }
        }
        error("跳转太多次了")
    }

    // ---------- 文件 ----------

    /** 「workspace/…」指当前对话的文件夹（要声明 workspace），别的都在工具包自己的数据文件夹里。不许跑到外面去。 */
    private fun file(rel: String, allowRoot: Boolean = false): File {
        if (perm.files.isEmpty()) error("这个工具包没有声明 files 权限，不能读写文件")
        val clean = rel.trim().trimStart('/')
        val (root, sub) = if (clean == "workspace" || clean.startsWith("workspace/")) {
            if (perm.files != "workspace") error("这个工具包只能用自己的数据文件夹（声明 files: workspace 才能碰对话文件夹）")
            (workspace ?: error("这里没有对话文件夹")) to clean.removePrefix("workspace").trimStart('/')
        } else Packs.dataDir(ctx, m.id).apply { mkdirs() } to clean
        if (sub.isEmpty() && !allowRoot) error("要写文件名")
        val f = File(root, sub).canonicalFile
        val r = root.canonicalFile
        if (f != r && !f.path.startsWith(r.path + File.separator)) error("不能访问文件夹外面的：$rel")
        return f
    }

    private fun loadStore(): Map<String, JsonElement> =
        runCatching { json.parseToJsonElement(Packs.storeFile(ctx, m.id).readText()).jsonObject }.getOrNull().orEmpty()

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()

    companion object {
        const val MAX_BODY = 5 * 1024 * 1024
    }
}
