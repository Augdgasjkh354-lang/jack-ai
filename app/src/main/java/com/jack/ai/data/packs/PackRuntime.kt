package com.jack.ai.data.packs

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executors

/**
 * 工具包代码能用的那些本事（`kavi.*`）。由 App 实现，按权限放行；测试里换成假的。
 * 参数和返回都是 JSON；做不了就抛异常，异常的文字会原样交给工具包代码（再交给模型）。
 */
interface PackHost {
    /** 同步的：文件、存储、通知、日志。 */
    fun call(op: String, arg: JsonElement): JsonElement
    /** 要等的：联网、问模型、调 Kavi 的工具。 */
    suspend fun callAsync(op: String, arg: JsonElement): JsonElement
}

/** 一次调用的结果：给模型的文字，和跑的时候 console.log 打的。 */
data class PackRun(val ok: Boolean, val text: String, val logs: List<String>, val ms: Long)

/**
 * 在 QuickJS 里跑工具包的一个工具：每次调用开一个新的引擎（几毫秒），跑完就关，调用之间互不影响。
 * main.js 按 ES 模块加载（动态 import），导出和工具同名的（async）函数：`export async function track(args, kavi)`。
 */
object PackRuntime {
    const val MEMORY_LIMIT = 64L * 1024 * 1024
    const val RESULT_MAX = 20_000
    private val json = Json { prettyPrint = false }
    private val pretty = Json { prettyPrint = true }

    /** 引擎里先跑的这段：把宿主的两个桥包成好用的 `kavi` 对象。 */
    internal fun prelude(pack: PackManifest, config: Map<String, String>): String {
        val info = buildJsonObject { put("id", pack.id); put("name", pack.name); put("version", pack.version) }
        val cfg = JsonObject(config.mapValues { JsonPrimitive(it.value) })
        return """
const kavi = (() => {
  const unwrap = (s) => { const r = JSON.parse(s); if (r.error !== undefined) throw new Error(r.error); return r.value; };
  const call = (op, a) => unwrap(__kv(op, JSON.stringify(a === undefined ? null : a)));
  const acall = async (op, a) => unwrap(await __kva(op, JSON.stringify(a === undefined ? null : a)));
  const wrap = (r) => Object.freeze({ ...r, text: () => r.body, json: () => JSON.parse(r.body) });
  const request = async (o) => wrap(await acall("http", o || {}));
  const http = Object.freeze({
    request,
    get: (url, o) => request({ ...(o || {}), url, method: "GET" }),
    post: (url, body, o) => request({ ...(o || {}), url, method: "POST", body }),
    put: (url, body, o) => request({ ...(o || {}), url, method: "PUT", body }),
    delete: (url, o) => request({ ...(o || {}), url, method: "DELETE" }),
  });
  const files = Object.freeze({
    read: (p) => call("files.read", { path: p }),
    write: (p, text) => call("files.write", { path: p, text: String(text) }),
    list: (p) => call("files.list", { path: p || "" }),
    remove: (p) => call("files.remove", { path: p }),
    exists: (p) => call("files.exists", { path: p }),
  });
  const store = Object.freeze({
    get: (k, d) => { const v = call("store.get", { key: String(k) }); return v === null ? (d === undefined ? null : d) : v; },
    set: (k, v) => call("store.set", { key: String(k), value: v === undefined ? null : v }),
    remove: (k) => call("store.set", { key: String(k), value: null }),
    keys: () => call("store.keys", null),
  });
  const log = (...a) => call("log", a.map((x) => typeof x === "string" ? x : JSON.stringify(x)).join(" "));
  return Object.freeze({
    pack: Object.freeze(${json.encodeToString(JsonObject.serializer(), info)}),
    config: Object.freeze(${json.encodeToString(JsonObject.serializer(), cfg)}),
    http, files, store, log,
    notify: (title, body) => call("notify", { title: String(title), body: body === undefined ? "" : String(body) }),
    ai: (prompt, o) => acall("ai", { ...(o || {}), prompt: String(prompt) }),
    tool: (name, args) => acall("tool", { name: String(name), args: args || {} }),
    sleep: (ms) => acall("sleep", { ms: Number(ms) || 0 }),
  });
})();
globalThis.kavi = kavi;
globalThis.console = Object.freeze({ log: kavi.log, info: kavi.log, warn: kavi.log, error: kavi.log, debug: kavi.log });
""".trimIndent()
    }

    /** 调用入口：找到导出的函数，把参数和 kavi 传进去，结果写到全局 __out。 */
    private fun runner(tool: String, args: JsonObject): String {
        val a = json.encodeToString(JsonObject.serializer(), args)
        val name = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(tool))
        return """
(async () => {
const name = $name;
try {
  const m = await import("main");
  const f = m[name] ?? (m.default && m.default[name]) ?? globalThis[name];
  if (typeof f !== "function") throw new Error("main.js 里没有导出 " + name + " 这个函数（要写 export async function " + name + "(args, kavi)）");
  const r = await f($a, kavi);
  globalThis.__out = JSON.stringify({ ok: true, value: r === undefined ? null : r });
} catch (e) {
  globalThis.__out = JSON.stringify({ ok: false, error: String(e && e.message !== undefined ? e.message : e), stack: String((e && e.stack) || "") });
}
})();
""".trimIndent()
    }

    /**
     * 同一时间只开一个引擎：quickjs-kt 的原生库只在全局记一份 Java 虚拟机，哪个引擎关掉都会把它清掉，
     * 别的引擎正在等的异步回调就拿不到结果了（实测）。工具一个个按顺序跑，一次通常几十毫秒。
     */
    private val engine = Mutex()

    /**
     * 跑一个工具。超时就不等了，直接告诉模型；等联网这类的会被取消、引擎正常关掉。
     * 代码要是卡在死循环里，那根线程会一直占着引擎，直到 App 进程结束（这个封装没有中断的口子），后面的调用会说明原因。
     */
    suspend fun run(
        pack: PackManifest, code: String, tool: String, args: JsonObject, config: Map<String, String>,
        host: PackHost, timeoutMs: Long = (pack.tools.firstOrNull { it.name == tool }?.timeout?.takeIf { it > 0 } ?: PackFormat.DEFAULT_TIMEOUT) * 1000L,
    ): PackRun {
        val start = System.currentTimeMillis()
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "kavi-pack-${pack.id}").apply { isDaemon = true } }
        val dispatcher = pool.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var started = false
        val job = scope.async {
            engine.withLock {
            started = true
            val js = QuickJs.create(dispatcher)
            try {
                js.memoryLimit = MEMORY_LIMIT
                js.maxStackSize = 1024L * 1024
                js.function("__kv") { a -> bridge(a) { op, arg ->
                    if (op == "log") { logs += (arg as? JsonPrimitive)?.content.orEmpty().take(2000); JsonNull } else host.call(op, arg)
                } }
                js.asyncFunction("__kva") { a ->
                    val op = a.getOrNull(0)?.toString().orEmpty()
                    val arg = parseArg(a.getOrNull(1))
                    try { ok(host.callAsync(op, arg)) } catch (e: Throwable) { err(e) }
                }
                js.evaluate<Any?>(prelude(pack, config), "kavi.js")
                js.addModule("main", code)
                // 这版 quickjs-kt 直接按模块执行会出错，用动态 import 加载 main 没问题
                js.evaluate<Any?>(runner(tool, args), "run.js")
                js.evaluate<String?>("globalThis.__out", "out.js")
            } finally {
                runCatching { js.close() }
            }
            }
        }
        val out = try {
            withTimeoutOrNull(timeoutMs) { runCatching { job.await() } }
        } finally {
            if (!job.isCompleted) job.cancel()
            pool.shutdown()
        }
        val ms = System.currentTimeMillis() - start
        if (out == null) return PackRun(false,
            if (started) "超时了：${timeoutMs / 1000} 秒还没跑完，停掉了。"
            else "前一个工具包还占着引擎（可能卡在死循环里），这次没跑。过一会再试，还不行就重开 App。", logs.toList(), ms)
        val raw = out.getOrElse { e -> return PackRun(false, "代码出错：" + describe(e), logs.toList(), ms) }
            ?: return PackRun(false, "代码没有跑完（可能有没处理的 Promise）", logs.toList(), ms)
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return PackRun(false, "返回值转不成文字", logs.toList(), ms)
        if ((o["ok"] as? JsonPrimitive)?.content != "true") {
            val msg = (o["error"] as? JsonPrimitive)?.content.orEmpty()
            val at = (o["stack"] as? JsonPrimitive)?.content.orEmpty().lineSequence().map { it.trim() }.firstOrNull { "main" in it }
            return PackRun(false, "出错了：$msg" + (at?.let { "（$it）" } ?: ""), logs.toList(), ms)
        }
        return PackRun(true, render(o["value"] ?: JsonNull), logs.toList(), ms)
    }

    /** 返回值交给模型：字符串原样，别的转成 JSON；太长就截断。 */
    internal fun render(v: JsonElement): String {
        val s = when {
            v is JsonNull -> "（完成，没有返回内容）"
            v is JsonPrimitive && v.isString -> v.content
            v is JsonPrimitive -> v.content
            else -> pretty.encodeToString(JsonElement.serializer(), v)
        }
        return if (s.length <= RESULT_MAX) s else s.take(RESULT_MAX) + "\n…（结果太长，后面 ${s.length - RESULT_MAX} 个字截掉了）"
    }

    private fun parseArg(a: Any?): JsonElement = (a as? String)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull

    private inline fun bridge(a: Array<Any?>, f: (String, JsonElement) -> JsonElement): String {
        val op = a.getOrNull(0)?.toString().orEmpty()
        return try { ok(f(op, parseArg(a.getOrNull(1)))) } catch (e: Throwable) { err(e) }
    }

    private fun ok(v: JsonElement) = json.encodeToString(JsonObject.serializer(), buildJsonObject { put("value", v) })
    private fun err(e: Throwable) = json.encodeToString(JsonObject.serializer(), buildJsonObject { put("error", e.message ?: e.javaClass.simpleName) })

    private fun describe(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "out of memory" in m.lowercase() -> "内存超了（最多 ${MEMORY_LIMIT / 1024 / 1024}MB）"
            "stack overflow" in m.lowercase() -> "递归太深，栈溢出了"
            m.isNotBlank() -> m.take(600)
            else -> e.javaClass.simpleName
        }
    }
}
