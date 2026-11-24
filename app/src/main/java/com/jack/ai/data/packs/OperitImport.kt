package com.jack.ai.data.packs

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 把 Operit 的单文件脚本（开头是 METADATA 注释的 .js）转成 Kavi 的工具包。
 * 只照着它公开的接口名（Tools.Net、OkHttp、Tools.Files、complete、getEnv……）写了一层转换，接到 Kavi 自己的 kavi.*，
 * 没有用 Operit 的代码（它是 LGPL-3.0）。权限照样由 Kavi 管：用到哪些网站从代码里找出来写进说明，用户装之前看得到。
 * 不支持的（操作界面、改系统设置、调 Java 这些）列出来，让用户决定还装不装；真调到时会报错说明。
 */
object OperitImport {
    /** 转好的：Kavi 的说明文件、要放进 main.js 的代码、不支持的那些。 */
    data class Converted(val manifest: PackManifest, val main: String, val unsupported: List<String>, val readme: String)

    private val META = Regex("""/\*\s*METADATA\s*([\s\S]*?)\*/""")
    private val URL = Regex("""https?://([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,63})""")
    private val SECRET = Regex("(?i)key|token|secret|password|passwd|cookie|sessdata|auth")

    /** 用到了就记下来的不支持的接口：正则 → 给人看的说法（$1 是匹配到的名字）。 */
    private val UNSUPPORTED = listOf(
        Regex("""Tools\.UI\.(\w+)""") to "操作手机界面（Tools.UI.$1）",
        Regex("""Tools\.System\.(?!sleep\b)(\w+)""") to "系统操作（Tools.System.$1）",
        Regex("""Tools\.Net\.(browser\w*|startBrowser|stopBrowser|uploadFile|cookies)""") to "浏览器和上传（Tools.Net.$1）",
        Regex("""Tools\.Files\.(?!read\b|write\b|list\b|exists\b|deleteFile\b|mkdir\b)(\w+)""") to "文件操作（Tools.Files.$1）",
        Regex("""Tools\.(Chat|Workflow|SoftwareSettings|Memory|Tasker|FFmpeg|Media|Calendar|Package|Device)\.(\w+)""") to "Operit 自己的功能（Tools.$1.$2）",
        Regex("""\b(Java|Kotlin)\.\w+""") to "直接调用 $1",
        Regex("""\btoolCall\s*\(""") to "调用 Operit 的内置工具（toolCall）",
        Regex("""\b(CryptoJS|pako|Jimp)\b""") to "第三方库 $1",
    )

    fun looksLikeOperit(text: String) = META.containsMatchIn(text.take(20_000))

    /** 读 METADATA：标准 JSON、JS 对象写法、HJSON（不写逗号和引号）都有人用，用宽松的解析器读。 */
    private fun metadata(text: String): JsonObject {
        val block = META.find(text)?.groupValues?.get(1) ?: throw PackError(listOf("开头没有 /* METADATA … */，不像 Operit 的脚本"))
        val v = runCatching { LooseJson.parse(block) }.getOrElse { e -> throw PackError(listOf("METADATA 读不懂：${e.message?.take(200)}")) }
        return v as? JsonObject ?: throw PackError(listOf("METADATA 不是一个对象"))
    }

    /** 多语言的字段（{"zh": …, "en": …}）取中文，没有就取第一个。 */
    private fun text(e: JsonElement?): String = when (e) {
        is JsonPrimitive -> e.content
        is JsonObject -> ((e["zh"] ?: e["zh-CN"] ?: e["en"] ?: e.values.firstOrNull()) as? JsonPrimitive)?.content.orEmpty()
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString("、")
        else -> ""
    }

    suspend fun convert(text: String, fileName: String = "script.js"): Converted {
        val meta = metadata(text)
        val rawName = (meta["name"] as? JsonPrimitive)?.content ?: fileName.substringBeforeLast('.')
        val id = rawName.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-', '_').take(28).let { if (it.length < 2) "operit-script" else "op-$it".take(32) }
        val tools = (meta["tools"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { (it["advice"] as? JsonPrimitive)?.content != "true" }
            .mapNotNull { t ->
                val name = (t["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val params = (t["parameters"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { p ->
                    val n = (p["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val type = (p["type"] as? JsonPrimitive)?.content?.lowercase().let { if (it in setOf("string", "number", "integer", "boolean", "array", "object")) it!! else "string" }
                    n to PackParam(type, text(p["description"]).take(500), (p["required"] as? JsonPrimitive)?.content == "true")
                }.toMap()
                PackToolSpec(name, text(t["description"]).ifBlank { name }.take(1000), params)
            }
        if (tools.isEmpty()) throw PackError(listOf("METADATA 里没有工具（tools）"))
        val env = (meta["env"] as? JsonArray).orEmpty().mapNotNull { e ->
            when (e) {
                is JsonPrimitive -> PackConfigField(e.content, e.content, secret = SECRET.containsMatchIn(e.content), required = true)
                is JsonObject -> (e["name"] as? JsonPrimitive)?.content?.let { n ->
                    PackConfigField(n, text(e["description"]).ifBlank { n }.take(60), secret = SECRET.containsMatchIn(n), required = (e["required"] as? JsonPrimitive)?.content != "false")
                }
                else -> null
            }
        }.filter { Regex("^[A-Za-z_][A-Za-z0-9_]{0,39}$").matches(it.key) }.distinctBy { it.key }
        // 代码里写死的网址就是它要访问的网站
        val hosts = URL.findAll(text).map { it.groupValues[1].lowercase() }
            .filterNot { it.endsWith("example.com") || it == "localhost" || it.endsWith(".d.ts") }.distinct().take(30).toList()
        val unsupported = UNSUPPORTED.flatMap { (re, say) ->
            re.findAll(text).map { m -> say.replace("$1", m.groupValues.getOrElse(1) { "" }).replace("$2", m.groupValues.getOrElse(2) { "" }) }.toList()
        }.distinct()
        val usesFiles = Regex("""Tools\.Files\.""").containsMatchIn(text)
        val usesVisit = Regex("""Tools\.Net\.visit\b""").containsMatchIn(text)
        val manifest = PackManifest(
            id = id,
            name = text(meta["display_name"]).ifBlank { rawName }.take(40),
            version = (meta["version"] as? JsonPrimitive)?.content?.take(20) ?: "1.0.0",
            description = text(meta["description"]).take(400),
            author = text(meta["author"]).ifBlank { "Operit 脚本" }.take(60),
            permissions = PackPermissions(net = hosts, files = if (usesFiles) "pack" else "", tools = if (usesVisit) listOf("read_webpage") else emptyList()),
            config = env,
            tools = tools,
        )
        PackFormat.check(manifest).takeIf { it.isNotEmpty() }?.let { throw PackError(it) }
        val readme = buildString {
            append("# ").append(manifest.name).append("\n\n").append(manifest.description).append("\n\n")
            append("从 Operit 的脚本「$fileName」转过来的。原文件留在 original.js。\n\n")
            if (hosts.isEmpty()) append("代码里没找到写死的网址：它要是拼出网址再访问，会被拦下。\n\n")
            if (unsupported.isNotEmpty()) append("Kavi 不支持它用到的这些，调到时会报错：\n").append(unsupported.joinToString("\n") { "- $it" }).append("\n")
        }
        return Converted(manifest, shim(tools.map { it.name }) + "\n// ---- 下面是原脚本 ----\n" + text + "\n" + exports(tools.map { it.name }), unsupported, readme)
    }

    /** 转好放进临时文件夹，交给 [Packs.stage] 核对（然后照常给用户看确认页）。 */
    suspend fun stage(ctx: Context, text: String, fileName: String, source: String = "operit"): Packs.Staged {
        val c = convert(text, fileName)
        val dir = File(ctx.cacheDir, "pack_stage/" + System.nanoTime().toString(36)).apply { mkdirs() }
        File(dir, PackFormat.FILE).writeText(PackFormat.encode(c.manifest))
        File(dir, "main.js").writeText(c.main)
        File(dir, "original.js").writeText(text)
        File(dir, "README.md").writeText(c.readme)
        return Packs.stage(dir, source).copy(warnings = c.unsupported)
    }

    /** 原脚本前面那一段：Operit 的接口名接到 kavi.*。 */
    private fun shim(tools: List<String>): String = """
// Kavi 的兼容层：把 Operit 脚本用的接口接到 kavi.*（Kavi 自己写的，不是 Operit 的代码）
var exports = {};
var module = { exports: exports };
const __unsupported = (what) => { throw new Error("Kavi 不支持 " + what + "：这个工具包是从 Operit 脚本转来的，这部分用不了"); };
const __stub = (ns) => new Proxy({}, { get: (_, k) => (typeof k === "string" ? (..._a) => __unsupported(ns + "." + k) : undefined) });
const __resp = (r) => ({ url: r.url, statusCode: r.status, statusMessage: r.ok ? "OK" : "", headers: r.headers || {},
  contentType: (r.headers || {})["content-type"] || "", content: r.body, size: (r.body || "").length, toString() { return r.body; } });
const __path = (p) => String(typeof p === "object" && p ? (p.path || p.file_path || "") : p).replace(/^\/+/, "");
const __http = async (o) => __resp(await kavi.http.request({ url: o.url, method: (o.method || "GET").toUpperCase(), headers: o.headers || {}, body: o.body }));
const Tools = {
  System: new Proxy({ sleep: (ms) => kavi.sleep(ms) }, { get: (t, k) => k in t ? t[k] : (typeof k === "string" ? (..._a) => __unsupported("Tools.System." + k) : undefined) }),
  Net: new Proxy({
    httpGet: (url) => __http({ url }),
    httpPost: (url, body) => __http({ url, method: "POST", body }),
    http: (o) => __http(o),
    visit: async (o) => {
      const url = typeof o === "string" ? o : o.url;
      const text = await kavi.tool("read_webpage", { url });
      return { url, title: "", content: text, links: [], toString() { return text; } };
    },
  }, { get: (t, k) => k in t ? t[k] : (typeof k === "string" ? (..._a) => __unsupported("Tools.Net." + k) : undefined) }),
  Files: new Proxy({
    read: async (p) => { const content = kavi.files.read(__path(p)); return { path: __path(p), content, size: content.length, toString() { return content; } }; },
    write: async (p, content, append) => { const q = __path(p); const old = append && kavi.files.exists(q) ? kavi.files.read(q) : ""; kavi.files.write(q, old + String(content)); return { path: q, successful: true }; },
    list: async (p) => ({ path: __path(p), entries: kavi.files.list(__path(p)).map((n) => ({ name: n.replace(/\/$/, ""), isDirectory: n.endsWith("/") })) }),
    exists: async (p) => ({ path: __path(p), exists: kavi.files.exists(__path(p)) }),
    deleteFile: async (p) => { kavi.files.remove(__path(p)); return { path: __path(p), successful: true }; },
    mkdir: async (p) => ({ path: __path(p), successful: true }),
  }, { get: (t, k) => k in t ? t[k] : (typeof k === "string" ? (..._a) => __unsupported("Tools.Files." + k) : undefined) }),
  UI: __stub("Tools.UI"), Chat: __stub("Tools.Chat"), Workflow: __stub("Tools.Workflow"), SoftwareSettings: __stub("Tools.SoftwareSettings"),
  Memory: __stub("Tools.Memory"), Tasker: __stub("Tools.Tasker"), FFmpeg: __stub("Tools.FFmpeg"),
};
const __okResp = (r) => Object.assign(__resp(r), { raw: __resp(r), json() { return JSON.parse(r.body); }, text() { return r.body; },
  isSuccessful() { return r.ok; }, bodyAsBase64() { __unsupported("bodyAsBase64"); } });
const __client = () => {
  const c = {
    newRequest() {
      const q = { url: "", method: "GET", headers: {}, body: undefined, form: null };
      const b = {
        url(u) { q.url = u; return b; }, method(m) { q.method = String(m).toUpperCase(); return b; },
        header(n, v) { q.headers[n] = v; return b; }, headers(h) { Object.assign(q.headers, h); return b; },
        body(x, type) {
          if (type === "form" && typeof x === "object") { q.body = Object.entries(x).map(([k, v]) => encodeURIComponent(k) + "=" + encodeURIComponent(v)).join("&"); q.headers["Content-Type"] = "application/x-www-form-urlencoded"; }
          else q.body = x;
          return b;
        },
        jsonBody(x) { q.body = x; q.headers["Content-Type"] = "application/json"; return b; },
        formParam(n, v) { q.form = q.form || []; q.form.push(encodeURIComponent(n) + "=" + encodeURIComponent(v)); return b; },
        multipartParam() { __unsupported("multipartParam"); },
        build() {
          if (q.form) { q.body = q.form.join("&"); q.headers["Content-Type"] = "application/x-www-form-urlencoded"; }
          return { ...q, execute: () => c.execute(q) };
        },
      };
      return b;
    },
    execute: async (q) => __okResp(await kavi.http.request({ url: q.url, method: q.method, headers: q.headers, body: q.body })),
    get: (url, headers) => c.execute({ url, method: "GET", headers }),
    post: (url, body, headers) => c.execute({ url, method: "POST", body, headers }),
    put: (url, body, headers) => c.execute({ url, method: "PUT", body, headers }),
    delete: (url, headers) => c.execute({ url, method: "DELETE", headers }),
    streamExecute: () => __unsupported("streamExecute"),
  };
  return c;
};
const __builder = () => { const b = new Proxy({ build: () => __client() }, { get: (t, k) => k in t ? t[k] : () => b }); return b; };
const OkHttp = { newClient: __client, newBuilder: __builder };
OkHttp.OkHttpClient = { newBuilder: __builder };
const getEnv = (k) => (kavi.config[k] === undefined || kavi.config[k] === "" ? undefined : kavi.config[k]);
const sendIntermediateResult = (x) => console.log(typeof x === "string" ? x : JSON.stringify(x));
const getLang = () => "zh";
const getState = () => undefined;
const getCallerName = () => "Kavi";
const getChatId = () => undefined;
const getToolSessionId = () => undefined;
// 插件的配置文件夹：放在工具包自己的数据文件夹里
const getPluginConfigDir = (id) => "plugins/" + String(id || kavi.pack.id).replace(/[^A-Za-z0-9_.-]/g, "_");
// QuickJS 没有定时器：用 kavi.sleep 垫一个
const __timers = new Set(); let __tid = 0;
if (typeof globalThis.setTimeout !== "function") {
  globalThis.setTimeout = (fn, ms, ...a) => { const id = ++__tid; __timers.add(id); kavi.sleep(ms || 0).then(() => { if (__timers.delete(id)) fn(...a); }); return id; };
  globalThis.clearTimeout = (id) => { __timers.delete(id); };
}
if (typeof globalThis.btoa !== "function") {
  const A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  globalThis.btoa = (s) => { let o = ""; for (let i = 0; i < s.length; i += 3) { const n = (s.charCodeAt(i) << 16) | ((s.charCodeAt(i + 1) || 0) << 8) | (s.charCodeAt(i + 2) || 0);
    o += A[(n >> 18) & 63] + A[(n >> 12) & 63] + (i + 1 < s.length ? A[(n >> 6) & 63] : "=") + (i + 2 < s.length ? A[n & 63] : "="); } return o; };
  globalThis.atob = (s) => { s = String(s).replace(/[^A-Za-z0-9+/]/g, ""); let o = ""; for (let i = 0; i < s.length; i += 4) { const n = (A.indexOf(s[i]) << 18) | (A.indexOf(s[i + 1]) << 12) | ((A.indexOf(s[i + 2]) & 63) << 6) | (A.indexOf(s[i + 3]) & 63);
    o += String.fromCharCode((n >> 16) & 255); if (s[i + 2]) o += String.fromCharCode((n >> 8) & 255); if (s[i + 3]) o += String.fromCharCode(n & 255); } return o; };
}
const toolCall = (..._a) => __unsupported("toolCall（Operit 的内置工具）");
const Java = __stub("Java"); const Kotlin = __stub("Kotlin");
let __complete = null;
var complete = (r) => { if (__complete) { const f = __complete; __complete = null; f(r); } };
globalThis.complete = complete;
""".trimIndent()

    /** 原脚本后面那一段：每个工具导出一个 Kavi 的函数，等它 complete() 或 return，再把 Operit 的结果格式拆开。 */
    private fun exports(tools: List<String>): String = buildString {
        append("""
const __call = (name, params) => new Promise((resolve, reject) => {
  const f = (module.exports && module.exports[name]) || exports[name];
  if (typeof f !== "function") { reject(new Error("脚本没有导出 " + name)); return; }
  let done = false;
  const finish = (ok, v) => { if (done) return; done = true; __complete = null; ok ? resolve(v) : reject(v); };
  __complete = (r) => finish(true, r);
  try { Promise.resolve(f(params || {})).then((v) => { if (v !== undefined) finish(true, v); }, (e) => finish(false, e)); }
  catch (e) { finish(false, e); }
});
const __unwrap = (r) => {
  if (r && typeof r === "object" && "success" in r) {
    if (r.success === false) throw new Error(r.message || r.error || "脚本说没成功");
    return r.data !== undefined && r.data !== null ? r.data : (r.message !== undefined ? r.message : r);
  }
  return r;
};
""".trimIndent()).append("\n")
        // 起别名再导出：原脚本里常有同名的函数，直接 export function 同名会撞
        tools.forEachIndexed { i, t -> append("const __tool$i = async (args) => __unwrap(await __call(${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(t))}, args));\n") }
        append("export { " + tools.mapIndexed { i, t -> "__tool$i as $t" }.joinToString(", ") + " };\n")
    }

    /** 读 METADATA 时用的：什么都不给。 */
    private object NoHost : PackHost {
        override fun call(op: String, arg: JsonElement): JsonElement = throw IllegalStateException("不行")
        override suspend fun callAsync(op: String, arg: JsonElement): JsonElement = throw IllegalStateException("不行")
    }
}
