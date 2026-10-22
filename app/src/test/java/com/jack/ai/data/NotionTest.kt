package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** 本机起一个假的 Notion，按 2025-09-03 版接口的样子回话，走一遍搜、读、建、改、删。 */
class NotionTest {
    private val PAGE = "11111111-1111-1111-1111-111111111111"
    private val DB = "22222222-2222-2222-2222-222222222222"
    private val DS = "33333333-3333-3333-3333-333333333333"
    private val TOKEN = "ntn_test"

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val log = mutableListOf<String>()
    private val bodies = mutableMapOf<String, JsonObject>()

    /** 假数据库里的行：id → 页面对象。 */
    private val rows = linkedMapOf<String, JsonObject>()
    private var pageTrash = false
    private var pageChildren = mutableListOf<JsonObject>()

    private val schema = Json.parseToJsonElement(
        """{"名称":{"id":"title","type":"title","title":{}},
            "状态":{"id":"s","type":"status","status":{"options":[{"name":"未开始"},{"name":"进行中"},{"name":"完成"}]}},
            "截止日期":{"id":"d","type":"date","date":{}},
            "标签":{"id":"t","type":"multi_select","multi_select":{"options":[{"name":"工作"},{"name":"紧急"}]}},
            "完成":{"id":"c","type":"checkbox","checkbox":{}},
            "预算":{"id":"n","type":"number","number":{}}}""",
    ).jsonObject

    private fun rt(s: String) = buildJsonArray { add(buildJsonObject { put("type", "text"); put("plain_text", s); put("text", buildJsonObject { put("content", s) }) }) }

    private fun pageObj() = buildJsonObject {
        put("object", "page"); put("id", PAGE); put("url", "https://www.notion.so/$PAGE")
        put("in_trash", pageTrash)
        put("parent", buildJsonObject { put("type", "workspace"); put("workspace", true) })
        put("last_edited_time", "2026-10-20T08:00:00.000Z")
        put("properties", buildJsonObject { put("title", buildJsonObject { put("id", "title"); put("type", "title"); put("title", rt("读书笔记")) }) })
    }

    /** 把写进来的属性（Notion 写法）变成读回来的样子。 */
    private fun echoProps(props: JsonObject): JsonObject = buildJsonObject {
        schema.forEach { (name, def) ->
            val type = def.jsonObject["type"]!!.jsonPrimitive.content
            val w = props[name]?.jsonObject?.get(type)
            put(name, buildJsonObject {
                put("type", type)
                when (type) {
                    "title" -> put("title", w?.jsonArray?.let { a -> rt(a.joinToString("") { it.jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content }) } ?: JsonArray(emptyList()))
                    "status" -> put("status", w ?: kotlinx.serialization.json.JsonNull)
                    "date" -> put("date", w ?: kotlinx.serialization.json.JsonNull)
                    "multi_select" -> put("multi_select", w ?: JsonArray(emptyList()))
                    "checkbox" -> put("checkbox", w ?: JsonPrimitive(false))
                    "number" -> put("number", w ?: kotlinx.serialization.json.JsonNull)
                }
            })
        }
    }

    private fun row(id: String, props: JsonObject) = buildJsonObject {
        put("object", "page"); put("id", id); put("url", "https://www.notion.so/$id"); put("in_trash", false)
        put("parent", buildJsonObject { put("type", "data_source_id"); put("data_source_id", DS) })
        put("properties", props)
    }

    @Before
    fun start() {
        rows["44444444-4444-4444-4444-444444444444"] = row("44444444-4444-4444-4444-444444444444", echoProps(buildJsonObject {
            put("名称", buildJsonObject { put("title", buildJsonArray { add(buildJsonObject { put("text", buildJsonObject { put("content", "交周报") }) }) }) })
            put("状态", buildJsonObject { put("status", buildJsonObject { put("name", "进行中") }) })
        }))
        pageChildren = mutableListOf(
            Json.parseToJsonElement("""{"id":"b1","type":"heading_2","has_children":false,"heading_2":{"rich_text":[{"plain_text":"第一章"}]}}""").jsonObject,
            Json.parseToJsonElement("""{"id":"b2","type":"to_do","has_children":false,"to_do":{"checked":true,"rich_text":[{"plain_text":"读完"}]}}""").jsonObject,
            Json.parseToJsonElement("""{"id":"b3","type":"toggle","has_children":true,"toggle":{"rich_text":[{"plain_text":"展开看"}]}}""").jsonObject,
        )
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { serve(it) }
            }
        }
        Notion.base = "http://127.0.0.1:${server.localPort}/v1"
    }

    /** 读一个 HTTP 请求、回一个，然后关连接。 */
    private fun serve(sock: java.net.Socket) {
        val input = sock.getInputStream().buffered()
        fun line(): String {
            val b = java.io.ByteArrayOutputStream()
            while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }
            return b.toString("UTF-8")
        }
        val (method, target) = line().split(" ").let { it[0] to it[1] }
        val headers = mutableMapOf<String, String>()
        while (true) { val h = line(); if (h.isEmpty()) break; val i = h.indexOf(':'); headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim() }
        val len = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(len).also { var r = 0; while (r < len) { val n = input.read(it, r, len - r); if (n < 0) break; r += n } }.decodeToString()
        val path = target.substringBefore('?').removePrefix("/v1")
        synchronized(log) {
            log += "$method $path"
            if (body.isNotBlank()) bodies["$method $path"] = Json.parseToJsonElement(body).jsonObject
        }
        val (code, resp) = if (headers["authorization"] != "Bearer $TOKEN") 401 to """{"object":"error","code":"unauthorized","message":"API token is invalid."}"""
        else if (headers["notion-version"] != Notion.VERSION) 400 to """{"code":"missing_version"}"""
        else route(method, path, body)
        val bytes = resp.toByteArray()
        val out = sock.getOutputStream()
        out.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes); out.flush()
    }

    private val notFound = 404 to """{"object":"error","code":"object_not_found","message":"Could not find."}"""

    private fun route(method: String, path: String, body: String): Pair<Int, String> {
        val b = if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body).jsonObject
        return when {
            method == "GET" && path == "/users/me" -> 200 to """{"object":"user","name":"Jack","bot":{"workspace_name":"我的空间"}}"""
            method == "POST" && path == "/search" -> 200 to buildJsonObject {
                put("results", buildJsonArray {
                    val kind = b["filter"]?.jsonObject?.get("value")?.jsonPrimitive?.content
                    if (kind != "data_source") add(pageObj())
                    if (kind != "page") add(buildJsonObject { put("object", "data_source"); put("id", DS); put("title", rt("任务")); put("last_edited_time", "2026-10-21T08:00:00.000Z") })
                })
            }.toString()
            method == "GET" && path == "/pages/$PAGE" -> 200 to pageObj().toString()
            method == "PATCH" && path == "/pages/$PAGE" -> { b["in_trash"]?.jsonPrimitive?.boolean?.let { pageTrash = it }; 200 to pageObj().toString() }
            method == "GET" && path.startsWith("/pages/") -> rows[path.removePrefix("/pages/")]?.let { 200 to it.toString() } ?: notFound
            method == "PATCH" && path.startsWith("/pages/") -> {
                val id = path.removePrefix("/pages/")
                val old = rows[id] ?: return notFound
                val merged = JsonObject(old["properties"]!!.jsonObject + echoProps(b["properties"]?.jsonObject ?: JsonObject(emptyMap())).filterKeys { b["properties"]?.jsonObject?.containsKey(it) == true })
                rows[id] = row(id, merged)
                200 to rows[id].toString()
            }
            method == "POST" && path == "/pages" -> {
                val parent = b["parent"]!!.jsonObject
                if (parent["type"]!!.jsonPrimitive.content == "data_source_id") {
                    val id = "55555555-5555-5555-5555-55555555555${rows.size}"
                    rows[id] = row(id, echoProps(b["properties"]!!.jsonObject))
                    200 to rows[id].toString()
                } else 200 to pageObj().toString()
            }
            method == "GET" && path == "/data_sources/$DS" -> 200 to buildJsonObject { put("object", "data_source"); put("id", DS); put("title", rt("任务")); put("properties", schema) }.toString()
            method == "GET" && path.startsWith("/data_sources/") -> notFound
            method == "GET" && path == "/databases/$DB" -> 200 to """{"object":"database","id":"$DB","data_sources":[{"id":"$DS","name":"任务"}]}"""
            method == "POST" && path == "/data_sources/$DS/query" -> 200 to buildJsonObject { put("results", JsonArray(rows.values.toList())); put("has_more", false) }.toString()
            method == "GET" && path == "/blocks/$PAGE/children" -> 200 to buildJsonObject { put("results", JsonArray(pageChildren)); put("has_more", false) }.toString()
            method == "GET" && path == "/blocks/b3/children" -> 200 to """{"results":[{"id":"b4","type":"paragraph","has_children":false,"paragraph":{"rich_text":[{"plain_text":"藏起来的话"}]}}],"has_more":false}"""
            method == "GET" && path.startsWith("/blocks/") -> 200 to """{"results":[{"id":"x","type":"paragraph","has_children":false,"paragraph":{"rich_text":[{"plain_text":"正文"}]}}],"has_more":false}"""
            method == "PATCH" && path == "/blocks/$PAGE/children" -> {
                val kids = b["children"]!!.jsonArray
                200 to buildJsonObject { put("results", JsonArray(kids.map { it })) }.toString()
            }
            else -> notFound
        }
    }

    @After
    fun stop() { server.close(); Notion.base = "https://api.notion.com/v1" }

    @Test
    fun idFromLinkOrBareId() {
        assertEquals(PAGE, Notion.idOf("https://www.notion.so/me/Reading-11111111111111111111111111111111?pvs=4"))
        assertEquals(PAGE, Notion.idOf(PAGE))
        assertNull(Notion.idOf("读书笔记"))
    }

    @Test
    fun badTokenSaysSo() {
        val e = runCatching { Notion.me("ntn_wrong") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("密钥不对"))
        assertEquals("Jack · 我的空间", Notion.me(TOKEN))
    }

    @Test
    fun searchListsPagesAndDatabases() {
        val all = Notion.search(TOKEN, "")
        assertTrue(all, all.contains("📄 页面「读书笔记」") && all.contains("📊 数据库「任务」 id=$DS"))
        Notion.search(TOKEN, "任务", "database")
        assertEquals("data_source", bodies["POST /search"]!!["filter"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun readPageIncludesNestedBlocks() {
        val t = Notion.read(TOKEN, "https://www.notion.so/11111111111111111111111111111111")
        assertTrue(t, t.startsWith("# 读书笔记"))
        assertTrue(t, t.contains("## 第一章") && t.contains("- [x] 读完") && t.contains("▸ 展开看") && t.contains("  藏起来的话"))
    }

    @Test
    fun readDatabaseByDatabaseIdFallsBackToItsDataSource() {
        val t = Notion.read(TOKEN, DB)
        assertTrue(t, t.contains("📊 数据库「任务」") && t.contains("状态（状态：未开始 / 进行中 / 完成）"))
        assertTrue(t, t.contains("「交周报」 状态：进行中"))
        assertTrue(log.toString(), log.contains("POST /data_sources/$DS/query"))
    }

    @Test
    fun readPassesFilterAndSorts() {
        val filter = Json.parseToJsonElement("""{"property":"状态","status":{"does_not_equal":"完成"}}""")
        val sorts = Json.parseToJsonElement("""[{"property":"截止日期","direction":"ascending"}]""")
        Notion.read(TOKEN, DS, filter, sorts, 10)
        val q = bodies["POST /data_sources/$DS/query"]!!
        assertEquals(filter, q["filter"]); assertEquals(sorts, q["sorts"]); assertEquals(10, q["page_size"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun createTaskInDatabaseConvertsValuesAndVerifies() {
        val props = Json.parseToJsonElement("""{"状态":"未开始","截止日期":"2026-10-30","标签":["工作","紧急"],"完成":"否","预算":"1,200"}""").jsonObject
        val r = Notion.create(TOKEN, DB, "写方案", props, "- [ ] 列提纲\n**重点**")
        assertTrue(r, r.contains("已新建「写方案」"))
        assertTrue(r, r.contains("【核实】✓"))
        val sent = bodies["POST /pages"]!!
        assertEquals(DS, sent["parent"]!!.jsonObject["data_source_id"]!!.jsonPrimitive.content)
        val p = sent["properties"]!!.jsonObject
        assertEquals("未开始", p["状态"]!!.jsonObject["status"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("2026-10-30", p["截止日期"]!!.jsonObject["date"]!!.jsonObject["start"]!!.jsonPrimitive.content)
        assertEquals(2, p["标签"]!!.jsonObject["multi_select"]!!.jsonArray.size)
        assertFalse(p["完成"]!!.jsonObject["checkbox"]!!.jsonPrimitive.boolean)
        assertEquals(1200.0, p["预算"]!!.jsonObject["number"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("to_do", sent["children"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownFieldIsRejectedWithTheRealNames() {
        val e = runCatching { Notion.create(TOKEN, DS, "x", buildJsonObject { put("优先级", "高") }, "") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("没有字段「优先级」") && e!!.message!!.contains("状态"))
    }

    @Test
    fun updateRowStatusAndDate() {
        val id = rows.keys.first()
        val r = Notion.update(TOKEN, id, null, Json.parseToJsonElement("""{"状态":"完成","截止日期":"2026-11-01 14:30"}""").jsonObject, null)
        assertTrue(r, r.contains("【核实】✓"))
        val start = bodies["PATCH /pages/$id"]!!["properties"]!!.jsonObject["截止日期"]!!.jsonObject["date"]!!.jsonObject["start"]!!.jsonPrimitive.content
        assertTrue(start, start.startsWith("2026-11-01T14:30:00"))
    }

    @Test
    fun trashAndAppendOnPage() {
        val a = Notion.append(TOKEN, PAGE, "## 新的一节\n> 引用\n```py\nprint(1)\n```")
        assertTrue(a, a.contains("追加 3 块") && a.contains("【核实】✓"))
        val kids = bodies["PATCH /blocks/$PAGE/children"]!!["children"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("heading_2", "quote", "code"), kids)
        val t = Notion.update(TOKEN, PAGE, null, null, true)
        assertTrue(t, t.contains("废纸篓") && t.contains("【核实】✓"))
    }

    @Test
    fun pageNotSharedExplainsHowToConnect() {
        val e = runCatching { Notion.read(TOKEN, "99999999999999999999999999999999") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("连接"))
    }

    @Test
    fun markdownInline() {
        val runs = Notion.Markdown.inline("看 **这里** 和 `代码` 还有 [链接](https://a.com/x)")
        assertEquals(6, runs.size)
        assertTrue(runs[1].jsonObject["annotations"]!!.jsonObject["bold"]!!.jsonPrimitive.boolean)
        assertEquals("https://a.com/x", runs[5].jsonObject["text"]!!.jsonObject["link"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        val long = Notion.Markdown.toBlocks("x".repeat(4500))
        assertEquals(3, long[0]["paragraph"]!!.jsonObject["rich_text"]!!.jsonArray.size)
    }
}
