package com.jack.ai.data.packs

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具包在 QuickJS 里跑：模块导出、async/await、kavi 的桥、出错和超时。 */
class PackRuntimeTest {
    private val pack = PackManifest(id = "demo", name = "示例", tools = listOf(PackToolSpec("hello", "打招呼"), PackToolSpec("slow", "慢", timeout = 1)))

    private class FakeHost : PackHost {
        val calls = mutableListOf<String>()
        val store = mutableMapOf<String, JsonElement>()
        override fun call(op: String, arg: JsonElement): JsonElement {
            calls += op
            return when (op) {
                "store.get" -> store[arg.jsonObject["key"]!!.jsonPrimitive.content] ?: JsonNull
                "store.set" -> { store[arg.jsonObject["key"]!!.jsonPrimitive.content] = arg.jsonObject["value"]!!; JsonNull }
                "files.read" -> throw IllegalStateException("没有声明文件权限")
                else -> JsonNull
            }
        }
        override suspend fun callAsync(op: String, arg: JsonElement): JsonElement {
            calls += op
            return when (op) {
                "http" -> { delay(20); buildJsonObject { put("status", 200); put("ok", true); put("body", """{"temp":21}"""); put("url", arg.jsonObject["url"]!!.jsonPrimitive.content) } }
                "sleep" -> { delay(arg.jsonObject["ms"]!!.jsonPrimitive.content.toDouble().toLong()); JsonNull }
                else -> throw IllegalStateException("不支持 $op")
            }
        }
    }

    private fun run(code: String, tool: String = "hello", args: JsonObject = buildJsonObject { put("who", "Jack") }, host: PackHost = FakeHost(), timeoutMs: Long? = null) = runBlocking {
        if (timeoutMs != null) PackRuntime.run(pack, code, tool, args, mapOf("apiKey" to "k1"), host, timeoutMs)
        else PackRuntime.run(pack, code, tool, args, mapOf("apiKey" to "k1"), host)
    }

    @Test fun exportedAsyncFunctionGetsArgsAndConfig() {
        val r = run("""export async function hello({ who }, kavi) { return "你好 " + who + " " + kavi.config.apiKey + " " + kavi.pack.name; }""")
        assertTrue(r.text, r.ok)
        assertEquals("你好 Jack k1 示例", r.text)
    }

    @Test fun awaitsHostCallsAndReturnsObjectsAsJson() {
        val host = FakeHost()
        val r = run("""
            export async function hello(args, kavi) {
              const r = await kavi.http.get("https://api.example.com/w", { query: { city: "北京" } });
              const n = (kavi.store.get("n", 0)) + 1;
              kavi.store.set("n", n);
              console.log("拿到", r.status);
              await kavi.sleep(5);
              return { temp: r.json().temp, n, ok: r.ok };
            }
        """.trimIndent(), host = host)
        assertTrue(r.text, r.ok)
        assertTrue(r.text, "\"temp\": 21" in r.text && "\"n\": 1" in r.text)
        assertEquals(listOf("拿到 200"), r.logs)
        assertEquals(JsonPrimitive(1), host.store["n"])
        assertTrue("http" in host.calls)
    }

    @Test fun hostErrorsBecomeCatchableJsErrors() {
        val r = run("""
            export async function hello(a, kavi) {
              try { kavi.files.read("x.txt"); return "不该到这"; } catch (e) { return "接住了：" + e.message; }
            }
        """.trimIndent())
        assertEquals("接住了：没有声明文件权限", r.text)
    }

    @Test fun thrownErrorsAndMissingExportsAreExplained() {
        val r1 = run("""export async function hello() { throw new Error("单号不对"); }""")
        assertFalse(r1.ok); assertTrue(r1.text, "单号不对" in r1.text)
        val r2 = run("""export function other() { return 1 }""")
        assertFalse(r2.ok); assertTrue(r2.text, "没有导出 hello" in r2.text)
        val r3 = run("""export function hello( { syntax error""")
        assertFalse(r3.ok)
    }

    @Test fun defaultExportObjectWorksToo() {
        val r = run("""export default { hello: (a) => "默认导出 " + a.who }""")
        assertEquals("默认导出 Jack", r.text)
    }

    @Test fun slowCallTimesOut() {
        val r = run("""export async function slow(a, kavi) { await kavi.sleep(5000); return "完" }""", tool = "slow", timeoutMs = 300)
        assertFalse(r.ok)
        assertTrue(r.text, "超时" in r.text)
    }

    @Test fun closingOneEngineDoesNotBreakTheNext() {
        // 上一个超时被取消的引擎晚一点才关，不能把下一个的异步回调弄坏
        run("""export async function slow(a, kavi) { await kavi.sleep(2000); return "完" }""", tool = "slow", timeoutMs = 100)
        val r = run("""export async function hello(a, kavi) { const r = await kavi.http.get("https://a.com"); return r.status }""")
        assertEquals("200", r.text)
    }

    @Test fun eachCallStartsClean() {
        val code = """let count = 0; export function hello() { count++; globalThis.g = (globalThis.g || 0) + 1; return count + "," + globalThis.g; }"""
        assertEquals("1,1", run(code).text)
        assertEquals("1,1", run(code).text)
    }
}
