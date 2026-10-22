package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Notion：用户在 Notion 里建的「内部集成」密钥（ntn_ 开头），直接调官方接口。
 * 只能看到用户在页面上「连接」给这个集成的页面和数据库。
 * 用 2025-09-03 版接口：数据库下面是「数据源」，查行、建行都对着数据源。
 */
object Notion {
    const val VERSION = "2025-09-03"
    internal var base = "https://api.notion.com/v1"

    class NotionException(message: String, val code: String = "") : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }
    private const val FIX = "用 notion_write 的 update 改"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 调接口。出错时把 Notion 的说法翻成能照着做的中文。 */
    fun call(token: String, method: String, path: String, body: JsonObject? = null): JsonObject {
        if (token.isBlank()) throw NotionException("还没填 Notion 密钥：设置 → Notion")
        val req = Request.Builder().url(base + path)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Notion-Version", VERSION)
            .method(method, body?.let { it.toString().toRequestBody(JSON_TYPE) } ?: if (method == "GET") null else "{}".toRequestBody(JSON_TYPE))
            .build()
        com.jack.ai.api.sharedHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (resp.isSuccessful && obj != null) return obj
            val code = obj?.get("code").str()
            val msg = obj?.get("message").str()
            throw NotionException(
                when {
                    resp.code == 401 || code == "unauthorized" -> "Notion 密钥不对或已失效，到 设置 → Notion 重新填"
                    resp.code == 404 || code == "object_not_found" ->
                        "Notion 里找不到它。可能 ID 不对，或者这个页面还没连接给 Jack 的集成（在 Notion 里打开页面 → 右上角 ··· → 连接 → 选你的集成）"
                    code == "restricted_resource" -> "这个集成没有权限做这件事（在 Notion 的集成设置里打开读取/更新/插入内容的权限）"
                    resp.code == 429 -> "Notion 说请求太频繁，过几秒再试"
                    code == "validation_error" -> "Notion 不接受这个写法：$msg"
                    else -> "Notion 出错（${resp.code}）" + if (msg.isNotBlank()) "：$msg" else ""
                },
                code,
            )
        }
    }

    /** 用户贴来的链接或 ID 里抠出 32 位的 ID。 */
    fun idOf(s: String): String? {
        val hex = Regex("""[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}""")
            .findAll(s.trim()).lastOrNull()?.value?.replace("-", "") ?: return null
        return hex.lowercase().let { "${it.take(8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }
    }

    /** 测密钥：通了返回「集成名 · 工作区名」。 */
    fun me(token: String): String {
        val me = call(token, "GET", "/users/me")
        val ws = (me["bot"] as? JsonObject)?.get("workspace_name").str()
        return me["name"].str().ifBlank { "集成" } + if (ws.isNotBlank()) " · $ws" else ""
    }

    // ---------- 读 ----------

    /** 搜标题。kind = page / database / 空（都要）。 */
    fun search(token: String, query: String, kind: String = ""): String {
        val body = buildJsonObject {
            if (query.isNotBlank()) put("query", query.trim())
            put("page_size", 20)
            when (kind) {
                "page" -> put("filter", buildJsonObject { put("property", "object"); put("value", "page") })
                "database" -> put("filter", buildJsonObject { put("property", "object"); put("value", "data_source") })
            }
            put("sort", buildJsonObject { put("timestamp", "last_edited_time"); put("direction", "descending") })
        }
        val results = call(token, "POST", "/search", body)["results"]?.jsonArray.orEmpty()
        if (results.isEmpty()) {
            return "没找到" + (if (query.isNotBlank()) "标题含「$query」的" else "") + "页面或数据库。" +
                "Jack 只能看到连接给集成的页面：在 Notion 里打开页面 → 右上角 ··· → 连接 → 选你的集成（连上父页面，下面的子页面也都能看到）。"
        }
        return "找到 ${results.size} 个（按最近编辑排序）：\n" + results.joinToString("\n") { r ->
            val o = r.jsonObject
            val isDb = o["object"].str() == "data_source"
            val title = if (isDb) plain(o["title"]) else pageTitle(o)
            val edited = o["last_edited_time"].str().take(10)
            (if (isDb) "📊 数据库" else "📄 页面") + "「${title.ifBlank { "无标题" }}」 id=${o["id"].str()} 最近编辑 $edited"
        }
    }

    /**
     * 读一个页面（属性 + 正文）或一个数据库（字段 + 行）。id 可以是页面、数据库或数据源的，也可以是链接。
     * filter / sorts 是 Notion 原样的查询条件，只对数据库有用。
     */
    fun read(token: String, idOrUrl: String, filter: JsonElement? = null, sorts: JsonElement? = null, limit: Int = 50): String {
        val id = idOf(idOrUrl) ?: throw NotionException("这不是 Notion 的页面 ID 或链接：$idOrUrl")
        val page = try {
            call(token, "GET", "/pages/$id")
        } catch (e: NotionException) {
            if (e.code != "object_not_found" && e.code != "validation_error") throw e
            null
        }
        if (page != null) return readPage(token, page)
        return readDatabase(token, dataSource(token, id), filter, sorts, limit)
    }

    private fun readPage(token: String, page: JsonObject): String {
        val id = page["id"].str()
        val sb = StringBuilder("# ${pageTitle(page).ifBlank { "无标题" }}\n")
        sb.append("id=$id  链接 ${page["url"].str()}\n")
        if (page["in_trash"]?.jsonPrimitive?.booleanOrNull == true) sb.append("（这个页面在废纸篓里）\n")
        val props = page["properties"]?.jsonObject.orEmpty().filterValues { it.jsonObject["type"].str() != "title" }
        if (props.isNotEmpty()) {
            sb.append("属性：\n")
            props.forEach { (name, v) -> sb.append("- $name：${propText(v.jsonObject).ifBlank { "（空）" }}\n") }
        }
        sb.append("\n")
        val budget = intArrayOf(400)
        blocksText(token, id, 0, budget, sb)
        if (budget[0] <= 0) sb.append("\n……正文太长，后面的没读。")
        return sb.toString().take(15000)
    }

    /** 找到 id 对应的数据源：本身就是数据源，或者是数据库（取它的第一个数据源）。 */
    internal fun dataSource(token: String, id: String): JsonObject {
        return try {
            call(token, "GET", "/data_sources/$id")
        } catch (e: NotionException) {
            if (e.code != "object_not_found" && e.code != "validation_error") throw e
            val db = call(token, "GET", "/databases/$id")
            val first = db["data_sources"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id").str()
            if (first.isBlank()) throw NotionException("这个数据库里没有数据源")
            call(token, "GET", "/data_sources/$first")
        }
    }

    private fun readDatabase(token: String, ds: JsonObject, filter: JsonElement?, sorts: JsonElement?, limit: Int): String {
        val id = ds["id"].str()
        val schema = ds["properties"]?.jsonObject.orEmpty()
        val sb = StringBuilder("📊 数据库「${plain(ds["title"]).ifBlank { "无标题" }}」 id=$id\n字段：\n")
        schema.forEach { (name, p) -> sb.append("- $name（${fieldDesc(p.jsonObject)}）\n") }
        val body = buildJsonObject {
            put("page_size", limit.coerceIn(1, 100))
            if (filter != null && filter !is JsonNull && filter.toString() != "{}") put("filter", filter)
            if (sorts != null && sorts !is JsonNull && sorts.toString() != "[]") put("sorts", sorts)
        }
        val res = call(token, "POST", "/data_sources/$id/query", body)
        val rows = res["results"]?.jsonArray.orEmpty()
        sb.append("\n")
        if (rows.isEmpty()) sb.append("没有符合条件的行。") else {
            sb.append("${rows.size} 行" + (if (res["has_more"]?.jsonPrimitive?.booleanOrNull == true) "（还有更多，可以加 filter 缩小范围）" else "") + "：\n")
            rows.forEach { r ->
                val o = r.jsonObject
                val parts = o["properties"]?.jsonObject.orEmpty().entries
                    .filter { it.value.jsonObject["type"].str() != "title" }
                    .mapNotNull { (n, v) -> propText(v.jsonObject).takeIf { it.isNotBlank() }?.let { "$n：$it" } }
                sb.append("- 「${pageTitle(o).ifBlank { "无标题" }}」 " + parts.joinToString("；") + "  id=${o["id"].str()}\n")
            }
        }
        return sb.toString().take(15000)
    }

    private fun fieldDesc(p: JsonObject): String {
        val type = p["type"].str()
        val options = (p[type] as? JsonObject)?.get("options")?.let { it as? JsonArray }?.mapNotNull { (it as? JsonObject)?.get("name").str().takeIf { s -> s.isNotBlank() } }
        val name = TYPE_NAMES[type] ?: type
        return if (!options.isNullOrEmpty()) "$name：" + options.joinToString(" / ") else name
    }

    private val TYPE_NAMES = mapOf(
        "title" to "标题", "rich_text" to "文本", "number" to "数字", "select" to "单选", "multi_select" to "多选",
        "status" to "状态", "date" to "日期", "checkbox" to "勾选", "url" to "链接", "email" to "邮箱", "phone_number" to "电话",
        "people" to "成员", "relation" to "关联", "formula" to "公式（只读）", "rollup" to "汇总（只读）", "files" to "文件",
        "created_time" to "创建时间（只读）", "last_edited_time" to "编辑时间（只读）", "unique_id" to "编号（只读）",
    )

    /** 正文块翻成 Markdown。有子块（折叠、列表嵌套）的往下读，最多 3 层；budget 是总共还能读几块。 */
    private fun blocksText(token: String, id: String, depth: Int, budget: IntArray, sb: StringBuilder) {
        var cursor: String? = null
        val indent = "  ".repeat(depth)
        var n = 0
        do {
            val res = call(token, "GET", "/blocks/$id/children?page_size=100" + (cursor?.let { "&start_cursor=$it" } ?: ""))
            for (b in res["results"]?.jsonArray.orEmpty()) {
                if (budget[0]-- <= 0) return
                val o = b.jsonObject
                val type = o["type"].str()
                val data = o[type] as? JsonObject
                val text = plain(data?.get("rich_text"))
                if (type == "numbered_list_item") n++ else n = 0
                val line = when (type) {
                    "paragraph" -> text
                    "heading_1" -> "# $text"
                    "heading_2" -> "## $text"
                    "heading_3" -> "### $text"
                    "bulleted_list_item" -> "- $text"
                    "numbered_list_item" -> "$n. $text"
                    "to_do" -> (if (data?.get("checked")?.jsonPrimitive?.booleanOrNull == true) "- [x] " else "- [ ] ") + text
                    "toggle" -> "▸ $text"
                    "quote" -> "> $text"
                    "callout" -> "> 💡 $text"
                    "code" -> "```${data?.get("language").str()}\n$text\n```"
                    "divider" -> "---"
                    "child_page" -> "📄 子页面「${data?.get("title").str()}」 id=${o["id"].str()}"
                    "child_database" -> "📊 子数据库「${data?.get("title").str()}」 id=${o["id"].str()}"
                    "bookmark", "embed", "link_preview" -> data?.get("url").str()
                    "image", "file", "pdf", "video", "audio" -> "[$type] " + plain(data?.get("caption")).ifBlank { fileUrl(data) }
                    "equation" -> "$$" + data?.get("expression").str() + "$$"
                    "table_row" -> "| " + (data?.get("cells") as? JsonArray).orEmpty().joinToString(" | ") { plain(it) } + " |"
                    "table", "column_list", "column", "synced_block" -> null
                    else -> "[暂不支持显示的内容：$type]"
                }
                if (line != null) sb.append(indent).append(line.replace("\n", "\n$indent")).append("\n")
                val hasChildren = o["has_children"]?.jsonPrimitive?.booleanOrNull == true
                if (hasChildren && type != "child_page" && type != "child_database" && depth < 3) {
                    blocksText(token, o["id"].str(), if (line == null) depth else depth + 1, budget, sb)
                }
            }
            cursor = res["next_cursor"].str().takeIf { res["has_more"]?.jsonPrimitive?.booleanOrNull == true && it.isNotBlank() }
        } while (cursor != null)
    }

    private fun fileUrl(data: JsonObject?): String {
        val t = data?.get("type").str()
        return ((data?.get(t) as? JsonObject)?.get("url")).str()
    }

    /** 一个属性值写成人能看的字。 */
    internal fun propText(p: JsonObject): String {
        val type = p["type"].str()
        val v = p[type]
        return when (type) {
            "title", "rich_text" -> plain(v)
            "number" -> v.num()
            "select", "status" -> (v as? JsonObject)?.get("name").str()
            "multi_select" -> (v as? JsonArray).orEmpty().joinToString("、") { (it as? JsonObject)?.get("name").str() }
            "date" -> (v as? JsonObject)?.let { d -> d["start"].str() + (d["end"].str().takeIf { it.isNotBlank() }?.let { " → $it" } ?: "") }.orEmpty()
            "checkbox" -> if ((v as? JsonPrimitive)?.booleanOrNull == true) "✓" else "✗"
            "url", "email", "phone_number", "created_time", "last_edited_time" -> v.str()
            "people" -> (v as? JsonArray).orEmpty().joinToString("、") { (it as? JsonObject)?.get("name").str().ifBlank { "成员" } }
            "relation" -> (v as? JsonArray).orEmpty().joinToString("、") { (it as? JsonObject)?.get("id").str() }
            "files" -> (v as? JsonArray).orEmpty().joinToString("、") { (it as? JsonObject)?.get("name").str() }
            "unique_id" -> (v as? JsonObject)?.let { u -> u["prefix"].str().let { if (it.isBlank()) "" else "$it-" } + u["number"].num() }.orEmpty()
            "formula" -> (v as? JsonObject)?.let { f -> val t = f["type"].str(); if (t == "date") (f["date"] as? JsonObject)?.get("start").str() else if (t == "number") f[t].num() else f[t].str() }.orEmpty()
            "rollup" -> (v as? JsonObject)?.let { r ->
                when (r["type"].str()) {
                    "number" -> r["number"].num()
                    "array" -> (r["array"] as? JsonArray).orEmpty().joinToString("、") { propText(it.jsonObject) }
                    else -> ""
                }
            }.orEmpty()
            else -> ""
        }
    }

    internal fun pageTitle(page: JsonObject): String =
        page["properties"]?.jsonObject?.values?.firstOrNull { it.jsonObject["type"].str() == "title" }?.let { plain(it.jsonObject["title"]) }.orEmpty()

    /** rich_text 数组拼成纯文字。 */
    internal fun plain(e: JsonElement?): String =
        (e as? JsonArray).orEmpty().joinToString("") { (it as? JsonObject)?.get("plain_text").str().ifEmpty { ((it as? JsonObject)?.get("text") as? JsonObject)?.get("content").str() } }

    // ---------- 写 ----------

    /**
     * 新建页面：parent 是页面就建子页面，是数据库就建一行（properties 按字段名填，人能看懂的值就行）。
     * 返回给模型的话，末尾附读回核实。
     */
    fun create(token: String, parent: String, title: String, properties: JsonObject?, markdown: String): String {
        val pid = idOf(parent) ?: throw NotionException("parent 要填页面或数据库的 ID 或链接")
        val blocks = Markdown.toBlocks(markdown)
        // 先当页面试，不是页面再当数据库
        val parentPage = try { call(token, "GET", "/pages/$pid") } catch (e: NotionException) {
            if (e.code != "object_not_found" && e.code != "validation_error") throw e
            null
        }
        val body = if (parentPage != null) {
            if (properties != null && properties.isNotEmpty()) throw NotionException("父级是普通页面，子页面只有标题，不能填 properties；要填字段请建在数据库里")
            buildJsonObject {
                put("parent", buildJsonObject { put("type", "page_id"); put("page_id", pid) })
                put("properties", buildJsonObject { put("title", buildJsonObject { put("title", richText(title)) }) })
                put("children", JsonArray(blocks.take(100)))
            }
        } else {
            val ds = dataSource(token, pid)
            val schema = ds["properties"]?.jsonObject.orEmpty()
            val titleName = schema.entries.firstOrNull { it.value.jsonObject["type"].str() == "title" }?.key
            val props = buildJsonObject {
                if (titleName != null && title.isNotBlank()) put(titleName, buildJsonObject { put("title", richText(title)) })
                propsFor(schema, properties).forEach { (k, v) -> put(k, v) }
            }
            buildJsonObject {
                put("parent", buildJsonObject { put("type", "data_source_id"); put("data_source_id", ds["id"].str()) })
                put("properties", props)
                if (blocks.isNotEmpty()) put("children", JsonArray(blocks.take(100)))
            }
        }
        val page = call(token, "POST", "/pages", body)
        val newId = page["id"].str()
        if (blocks.size > 100) appendBlocks(token, newId, blocks.drop(100))
        return "已新建「${pageTitle(page).ifBlank { title }}」 id=$newId 链接 ${page["url"].str()}" + checkPage(token, newId, title, properties, blocks.size)
    }

    /** 在页面末尾追加内容。 */
    fun append(token: String, pageId: String, markdown: String): String {
        val id = idOf(pageId) ?: throw NotionException("id 要填页面的 ID 或链接")
        val blocks = Markdown.toBlocks(markdown)
        if (blocks.isEmpty()) throw NotionException("content 是空的")
        val added = appendBlocks(token, id, blocks)
        val problems = if (added != blocks.size) listOf("要加 ${blocks.size} 块，Notion 只确认收到 $added 块") else emptyList()
        return "已在页面末尾追加 ${blocks.size} 块内容。" + Verify.note(problems, "Notion 确认收到 $added 块", FIX)
    }

    private fun appendBlocks(token: String, id: String, blocks: List<JsonObject>): Int =
        blocks.chunked(100).sumOf { chunk ->
            call(token, "PATCH", "/blocks/$id/children", buildJsonObject { put("children", JsonArray(chunk)) })["results"]?.jsonArray?.size ?: 0
        }

    /** 改页面：标题、字段（数据库里的行），或者扔进 / 拿出废纸篓。 */
    fun update(token: String, pageId: String, title: String?, properties: JsonObject?, trash: Boolean?): String {
        val id = idOf(pageId) ?: throw NotionException("id 要填页面的 ID 或链接")
        val page = call(token, "GET", "/pages/$id")
        val parent = page["parent"]?.jsonObject
        val schema = when (parent?.get("type").str()) {
            "data_source_id" -> call(token, "GET", "/data_sources/${parent?.get("data_source_id").str()}")["properties"]?.jsonObject.orEmpty()
            "database_id" -> dataSource(token, parent?.get("database_id").str())["properties"]?.jsonObject.orEmpty()
            else -> null
        }
        val titleName = page["properties"]?.jsonObject?.entries?.firstOrNull { it.value.jsonObject["type"].str() == "title" }?.key ?: "title"
        val body = buildJsonObject {
            val props = buildJsonObject {
                if (!title.isNullOrBlank()) put(titleName, buildJsonObject { put("title", richText(title)) })
                if (properties != null && properties.isNotEmpty()) {
                    if (schema == null) throw NotionException("这是普通页面，只有标题，没有别的字段可改")
                    propsFor(schema, properties).forEach { (k, v) -> put(k, v) }
                }
            }
            if (props.isNotEmpty()) put("properties", props)
            if (trash != null) put("in_trash", trash)
        }
        if (body.isEmpty()) throw NotionException("没说要改什么：填 title、properties 或 trash")
        call(token, "PATCH", "/pages/$id", body)
        if (trash == true) {
            val back = call(token, "GET", "/pages/$id")
            val ok = back["in_trash"]?.jsonPrimitive?.booleanOrNull == true
            return "已移到 Notion 的废纸篓（30 天内能在 Notion 里恢复）。" + Verify.note(if (ok) emptyList() else listOf("读回来看页面不在废纸篓里"), "读回确认已在废纸篓", FIX)
        }
        return "已更新。" + checkPage(token, id, title, properties, 0)
    }

    /** 读回页面，看标题和字段是不是真改成了要的样子。 */
    private fun checkPage(token: String, id: String, title: String?, properties: JsonObject?, blocks: Int): String {
        val page = call(token, "GET", "/pages/$id")
        val problems = mutableListOf<String>()
        if (!title.isNullOrBlank() && pageTitle(page) != title.trim()) problems += "标题读回来是「${pageTitle(page)}」"
        val props = page["properties"]?.jsonObject.orEmpty()
        properties?.forEach { (name, want) ->
            val key = props.keys.firstOrNull { it == name } ?: props.keys.firstOrNull { it.equals(name.trim(), true) }
            val got = key?.let { propText(props[it]!!.jsonObject) }
            if (got == null) problems += "读回来没有字段「$name」"
            else if (!sameValue(want, got)) problems += "「$name」读回来是「$got」"
        }
        var ok = "读回页面，标题" + (if (properties.isNullOrEmpty()) "" else "和 ${properties.size} 个字段") + "都对"
        if (blocks > 0) {
            val n = call(token, "GET", "/blocks/$id/children?page_size=100")["results"]?.jsonArray?.size ?: 0
            if (n == 0) problems += "正文读回来是空的"
            ok += "，正文有内容"
        }
        return Verify.note(problems, ok, FIX)
    }

    /** 写进去的值和读回来的字是不是一回事（宽松比较：日期只比前缀、勾选认 是/否）。 */
    private fun sameValue(want: JsonElement, got: String): Boolean {
        val w = when (want) {
            is JsonArray -> want.joinToString("、") { it.str() }
            is JsonObject -> want["start"].str()
            else -> want.str()
        }.trim()
        if (w.isEmpty()) return got.isEmpty() || got == "✗"
        if (got == "✓" || got == "✗") return (got == "✓") == truthy(want)
        val wn = w.replace(",", "").toDoubleOrNull()
        if (wn != null && got.toDoubleOrNull() != null) return wn == got.toDouble()
        val wd = w.replace(" ", "T").take(16)
        if (Regex("""\d{4}-\d{2}-\d{2}.*""").matches(w)) return got.startsWith(wd.take(10))
        return got == w || got.split("、").toSet() == w.split(Regex("[,，、]")).map { it.trim() }.toSet()
    }

    private fun truthy(v: JsonElement): Boolean {
        val p = v as? JsonPrimitive ?: return false
        p.booleanOrNull?.let { return it }
        return p.content.trim().lowercase() in setOf("是", "true", "yes", "1", "✓", "完成", "已完成", "勾", "对")
    }

    /** 人能看懂的值 → Notion 的属性格式。字段名不对、类型不能写的，报出来让模型改。 */
    internal fun propsFor(schema: Map<String, JsonElement>, values: JsonObject?): Map<String, JsonObject> {
        if (values == null) return emptyMap()
        val out = linkedMapOf<String, JsonObject>()
        values.forEach { (rawName, v) ->
            val name = schema.keys.firstOrNull { it == rawName } ?: schema.keys.firstOrNull { it.equals(rawName.trim(), true) }
                ?: throw NotionException("数据库里没有字段「$rawName」，有这些：" + schema.keys.joinToString("、"))
            val type = schema[name]!!.jsonObject["type"].str()
            val s = when (v) { is JsonPrimitive -> v.contentOrNull.orEmpty(); JsonNull -> ""; else -> "" }.trim()
            val list = when (v) {
                is JsonArray -> v.map { it.str().trim() }
                else -> s.split(Regex("[,，、]")).map { it.trim() }
            }.filter { it.isNotEmpty() }
            val value: JsonElement = when (type) {
                "title" -> richText(s)
                "rich_text" -> richText(s)
                "number" -> if (s.isEmpty()) JsonNull else JsonPrimitive(s.replace(",", "").toDoubleOrNull() ?: throw NotionException("「$name」要填数字，收到「$s」"))
                "select", "status" -> if (s.isEmpty()) JsonNull else buildJsonObject { put("name", s) }
                "multi_select" -> JsonArray(list.map { buildJsonObject { put("name", it) } })
                "checkbox" -> JsonPrimitive(truthy(v))
                "url", "email", "phone_number" -> if (s.isEmpty()) JsonNull else JsonPrimitive(s)
                "date" -> date(v) ?: JsonNull
                "relation" -> JsonArray(list.map { r -> buildJsonObject { put("id", idOf(r) ?: throw NotionException("「$name」要填关联页面的 ID")) } })
                else -> throw NotionException("「$name」是${TYPE_NAMES[type] ?: type}字段，Jack 不能写")
            }
            out[name] = buildJsonObject { put(type, value) }
        }
        return out
    }

    /** "2026-10-30"、"2026-10-30 14:00"、"开始 ~ 结束" 或 {"start":..,"end":..} → Notion 的日期。 */
    private fun date(v: JsonElement): JsonObject? {
        val (start, end) = when (v) {
            is JsonObject -> v["start"].str() to v["end"].str()
            else -> v.str().split(Regex("""\s*(?:~|→|至|到)\s*""")).let { it[0] to it.getOrElse(1) { "" } }
        }
        fun iso(s: String): String? {
            val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2})[:：](\d{2}))?""").find(s.trim()) ?: return null
            val d = "%s-%02d-%02d".format(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt())
            if (m.groupValues[4].isEmpty()) return d
            val off = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
            val tz = "%s%02d:%02d".format(if (off < 0) "-" else "+", Math.abs(off) / 60, Math.abs(off) % 60)
            return "%sT%02d:%02d:00%s".format(d, m.groupValues[4].toInt(), m.groupValues[5].toInt(), tz)
        }
        val s = iso(start) ?: return if (start.isBlank()) null else throw NotionException("日期要写成 yyyy-MM-dd 或 yyyy-MM-dd HH:mm，收到「$start」")
        return buildJsonObject {
            put("start", s)
            iso(end)?.let { put("end", it) }
        }
    }

    /** 纯文字 → rich_text（每段不超过 2000 字）。 */
    internal fun richText(s: String): JsonArray = buildJsonArray {
        s.chunked(2000).forEach { part -> add(buildJsonObject { put("type", "text"); put("text", buildJsonObject { put("content", part) }) }) }
    }

    /** Markdown ↔ Notion 块。只认常用写法：标题、列表、待办、引用、代码、分隔线、加粗、行内代码、链接。 */
    object Markdown {
        fun toBlocks(md: String): List<JsonObject> {
            val out = mutableListOf<JsonObject>()
            val lines = md.replace("\r\n", "\n").lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val t = line.trim()
                when {
                    t.startsWith("```") -> {
                        val lang = t.removePrefix("```").trim()
                        val code = mutableListOf<String>()
                        i++
                        while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                        out += block("code", buildJsonObject {
                            put("rich_text", buildJsonArray { code.joinToString("\n").chunked(2000).forEach { add(textRun(it)) } })
                            put("language", LANGS[lang.lowercase()] ?: "plain text")
                        })
                    }
                    t.isEmpty() -> {}
                    t == "---" || t == "***" -> out += buildJsonObject { put("object", "block"); put("type", "divider"); put("divider", buildJsonObject {}) }
                    t.startsWith("### ") -> out += textBlock("heading_3", t.drop(4))
                    t.startsWith("## ") -> out += textBlock("heading_2", t.drop(3))
                    t.startsWith("# ") -> out += textBlock("heading_1", t.drop(2))
                    Regex("""^[-*] \[[ xX]\] """).containsMatchIn(t) ->
                        out += textBlock("to_do", t.drop(6), checked = t[3].lowercaseChar() == 'x')
                    t.startsWith("- ") || t.startsWith("* ") -> out += textBlock("bulleted_list_item", t.drop(2))
                    Regex("""^\d+[.)] """).containsMatchIn(t) -> out += textBlock("numbered_list_item", t.replaceFirst(Regex("""^\d+[.)] """), ""))
                    t.startsWith("> ") || t == ">" -> out += textBlock("quote", t.removePrefix(">").trim())
                    else -> out += textBlock("paragraph", t)
                }
                i++
            }
            return out
        }

        private val LANGS = mapOf(
            "py" to "python", "python" to "python", "js" to "javascript", "javascript" to "javascript", "ts" to "typescript",
            "kotlin" to "kotlin", "kt" to "kotlin", "java" to "java", "json" to "json", "sql" to "sql", "bash" to "bash",
            "sh" to "shell", "shell" to "shell", "html" to "html", "css" to "css", "markdown" to "markdown", "md" to "markdown",
            "c" to "c", "cpp" to "c++", "c++" to "c++", "go" to "go", "rust" to "rust", "yaml" to "yaml", "xml" to "xml",
        )

        private fun block(type: String, data: JsonObject) = buildJsonObject { put("object", "block"); put("type", type); put(type, data) }

        private fun textBlock(type: String, text: String, checked: Boolean? = null) = block(type, buildJsonObject {
            put("rich_text", inline(text))
            if (checked != null) put("checked", checked)
        })

        private fun textRun(s: String, bold: Boolean = false, code: Boolean = false, link: String? = null) = buildJsonObject {
            put("type", "text")
            put("text", buildJsonObject {
                put("content", s)
                if (link != null) put("link", buildJsonObject { put("url", link) })
            })
            if (bold || code) put("annotations", buildJsonObject { if (bold) put("bold", true); if (code) put("code", true) })
        }

        private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\((https?://[^)\s]+)\)""")

        /** 一行文字里的 **加粗**、`代码`、[链接](网址)。 */
        fun inline(s: String): JsonArray = buildJsonArray {
            var last = 0
            fun plainRun(p: String) = p.chunked(2000).forEach { add(textRun(it)) }
            INLINE.findAll(s).forEach { m ->
                if (m.range.first > last) plainRun(s.substring(last, m.range.first))
                when {
                    m.groupValues[1].isNotEmpty() -> m.groupValues[1].chunked(2000).forEach { add(textRun(it, bold = true)) }
                    m.groupValues[2].isNotEmpty() -> add(textRun(m.groupValues[2].take(2000), code = true))
                    else -> add(textRun(m.groupValues[3].take(2000), link = m.groupValues[4]))
                }
                last = m.range.last + 1
            }
            if (last < s.length) plainRun(s.substring(last))
        }
    }

    private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonElement?.num(): String = (this as? JsonPrimitive)?.doubleOrNull?.let { d ->
        if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }.orEmpty()
}
