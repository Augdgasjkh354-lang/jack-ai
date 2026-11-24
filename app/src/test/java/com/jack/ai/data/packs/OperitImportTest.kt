package com.jack.ai.data.packs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入 Operit 格式的单文件脚本：读 METADATA、找网站、列不支持的、接口转到 kavi.* 上真的跑。脚本是照它公开的格式自己写的。 */
class OperitImportTest {
    private val script = """
/* METADATA
{
    // 注释和末尾逗号也要认
    name: "Book_Search",
    display_name: { zh: "查书", en: "Book search" },
    description: { zh: "按书名查书", en: "Find books" },
    author: ["某人", "另一个人"],
    env: ["BOOK_API_KEY", { name: "REGION", description: { zh: "地区" }, required: false }],
    tools: [
        { name: "find", description: { zh: "按书名查" }, parameters: [ { name: "title", description: "书名", type: "string", required: true } ] },
        { name: "save", description: "存一下", parameters: [ { name: "text", type: "string", required: true } ] },
        { name: "slow", description: "回调里才 complete", parameters: [] },
        { name: "tap", description: "点屏幕", parameters: [] },
        { name: "guide", description: "用法说明", advice: true, parameters: [] },
    ],
}
*/
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const BookSearch = (function () {
    const client = OkHttp.newBuilder().connectTimeout(1000).readTimeout(1000).build();
    async function wrap(fn, params) {
        try { complete(await fn(params)); } catch (e) { complete({ success: false, message: "失败：" + e.message }); }
    }
    async function find(params) {
        const key = getEnv("BOOK_API_KEY");
        const req = client.newRequest().url("https://api.books.example.org/search?q=" + encodeURIComponent(params.title)).method("GET").header("X-Key", key || "").build();
        const r = await client.execute(req);
        if (!r.isSuccessful()) throw new Error("HTTP " + r.statusCode);
        await Tools.System.sleep(1);
        return { success: true, message: "找到了", data: r.json().items };
    }
    async function save(params) {
        await Tools.Files.write("/sdcard/Download/Operit/books.txt", params.text, false);
        const back = await Tools.Files.read("/sdcard/Download/Operit/books.txt");
        return { success: true, message: "存好了：" + back.content };
    }
    function slow(params) { Tools.System.sleep(5).then(() => complete({ success: true, data: { late: true } })); }
    async function tap() { await Tools.UI.tap(1, 2); return { success: true }; }
    return { find: (p) => wrap(find, p), save: (p) => wrap(save, p), slow, tap: (p) => wrap(tap, p) };
})();
function find() { return "同名的函数不该撞"; }
exports.find = BookSearch.find;
exports.save = BookSearch.save;
exports.slow = BookSearch.slow;
exports.tap = BookSearch.tap;
""".trimIndent()

    private class Host : PackHost {
        val files = mutableMapOf<String, String>()
        val urls = mutableListOf<String>()
        override fun call(op: String, arg: JsonElement): JsonElement {
            val p = arg.jsonObject["path"]?.jsonPrimitive?.content
            return when (op) {
                "files.write" -> { files[p!!] = arg.jsonObject["text"]!!.jsonPrimitive.content; JsonNull }
                "files.read" -> JsonPrimitive(files[p] ?: error("没有这个文件"))
                "files.exists" -> JsonPrimitive(p in files)
                else -> JsonNull
            }
        }
        override suspend fun callAsync(op: String, arg: JsonElement): JsonElement = when (op) {
            "sleep" -> JsonNull
            "http" -> {
                urls += arg.jsonObject["url"]!!.jsonPrimitive.content + " " + arg.jsonObject["headers"]
                buildJsonObject { put("status", 200); put("ok", true); put("url", "x"); put("body", """{"items":[{"t":"三体"}]}""") }
            }
            else -> error("不支持 $op")
        }
    }

    private val c = runBlocking { OperitImport.convert(script, "book.js") }
    private fun run(tool: String, args: String, host: Host = Host()) = runBlocking {
        PackRuntime.run(c.manifest, c.main, tool, kotlinx.serialization.json.Json.parseToJsonElement(args).jsonObject, mapOf("BOOK_API_KEY" to "k9"), host)
    }

    @Test fun metadataBecomesAManifest() {
        val m = c.manifest
        assertEquals("op-book_search", m.id)
        assertEquals("查书", m.name)
        assertEquals("某人、另一个人", m.author)
        assertEquals(listOf("find", "save", "slow", "tap"), m.tools.map { it.name })
        assertTrue(m.tools[0].parameters["title"]!!.required)
        assertEquals(listOf("BOOK_API_KEY", "REGION"), m.config.map { it.key })
        assertTrue(m.config[0].secret && m.config[0].required)
        assertFalse(m.config[1].secret || m.config[1].required)
        assertEquals(listOf("api.books.example.org"), m.permissions.net)
        assertEquals("pack", m.permissions.files)
        assertEquals(listOf("操作手机界面（Tools.UI.tap）"), c.unsupported)
        assertTrue(PackFormat.check(m).isEmpty())
    }

    @Test fun okHttpAndCompleteWork() {
        val h = Host()
        val r = run("find", """{"title":"三体"}""", h)
        assertTrue(r.text, r.ok)
        assertTrue(r.text, r.text.contains("\"t\": \"三体\""))
        assertTrue(h.urls.single(), h.urls.single().contains("q=%E4%B8%89%E4%BD%93") && h.urls.single().contains("k9"))
    }

    @Test fun filesAreMappedIntoThePackFolder() {
        val h = Host()
        val r = run("save", """{"text":"读过了"}""", h)
        assertEquals("存好了：读过了", r.text)
        assertEquals(setOf("sdcard/Download/Operit/books.txt"), h.files.keys)
    }

    @Test fun hjsonMetadataAndHelpers() = runBlocking {
        val hjson = "/* METADATA\n{\n  name: tiny\n  description: {\n    zh: 很小的脚本, 逗号也算在里面\n  }\n  # 注释\n  tools: [\n    { name: go, description: 走一下, parameters: [] }\n  ]\n}\n*/\n" +
            "exports.go = async function () {\n  await new Promise((r) => setTimeout(r, 5));\n" +
            "  return { success: true, data: { dir: getPluginConfigDir(), b: btoa(\"Kavi\"), a: atob(\"S2F2aQ==\") } };\n};"
        val c = OperitImport.convert(hjson, "tiny.js")
        assertEquals("很小的脚本, 逗号也算在里面", c.manifest.description)
        assertEquals(listOf("go"), c.manifest.tools.map { it.name })
        val r = PackRuntime.run(c.manifest, c.main, "go", kotlinx.serialization.json.JsonObject(emptyMap()), emptyMap(), Host())
        assertTrue(r.text, r.ok && r.text.contains("\"b\": \"S2F2aQ==\"") && r.text.contains("\"a\": \"Kavi\"") && r.text.contains("plugins/op-tiny"))
    }

    @Test fun looseJsonForms() {
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("""{"a":[1,2.5,true,null,"x"],"b":{"c":"d e"}}"""),
            LooseJson.parse("{ a: [1, 2.5, true, null, 'x',], // 注释\n b: { c: d e } }"))
        assertEquals(JsonPrimitive("第一行\n第二行"), LooseJson.parse("'''\n  第一行\n  第二行\n  '''"))
    }

    @Test fun lateCompleteAndUnsupportedCalls() {
        assertTrue(run("slow", "{}").text.contains("\"late\": true"))
        val tap = run("tap", "{}")
        assertFalse(tap.ok)
        assertTrue(tap.text, tap.text.contains("Kavi 不支持 Tools.UI.tap"))
    }

    @Test fun notOperitIsRefused() {
        assertFalse(OperitImport.looksLikeOperit("export function x(){}"))
        assertTrue(runCatching { runBlocking { OperitImport.convert("/* METADATA { name: 'x', tools: [] } */", "x.js") } }.exceptionOrNull() is PackError)
    }
}
