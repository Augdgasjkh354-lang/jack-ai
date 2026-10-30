package com.jack.ai.data

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipFile

/**
 * 每个对话的文件夹（项目里的对话共用项目的文件夹）。
 * 用户发的附件、AI 生成的文件都放这里，AI 能列出、读取、局部修改。
 * AI 生成的 Word/PPT/Excel/PDF/图表会在 .src 里留一份底稿，改底稿再重新生成，不用整份重写。
 */
object Workspace {
    private val pretty = Json { prettyPrint = true }

    fun root(ctx: Context) = File(ctx.filesDir, "workspaces")

    fun key(conv: Conversation): String = conv.projectId?.let { "p_$it" } ?: conv.id

    fun dir(ctx: Context, key: String) = File(root(ctx), key)

    fun isInside(ctx: Context, path: String) = runCatching {
        File(path).canonicalPath.startsWith(root(ctx).canonicalPath + File.separator)
    }.getOrDefault(false)

    fun files(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun safeName(name: String): String =
        name.trim().replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trimStart('.').take(80).ifBlank { "未命名" }

    /** 同名时加 (2) (3)…… */
    fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        val safe = safeName(name)
        var f = File(dir, safe)
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "").let { if (it.isEmpty() || it == safe) "" else ".$it" }
        var n = 2
        while (f.exists()) f = File(dir, "$base($n)$ext").also { n++ }
        return f
    }

    /** 按名字找文件，名字不完全一样时宽松匹配。 */
    fun find(dir: File, name: String): File? {
        val n = name.trim().substringAfterLast('/')
        val list = files(dir)
        return list.firstOrNull { it.name == n } ?: list.firstOrNull { it.name.equals(n, true) }
            ?: list.firstOrNull { it.name.substringBeforeLast('.') == n.substringBeforeLast('.') }
    }

    /** 只认名字完全一样的（不分大小写）。删文件、发附件这类收不回的操作用它，免得「报告.pdf」认成「报告.docx」。 */
    fun findExact(dir: File, name: String): File? {
        val n = name.trim().substringAfterLast('/')
        val list = files(dir)
        return list.firstOrNull { it.name == n } ?: list.firstOrNull { it.name.equals(n, true) }
    }

    fun delete(file: File) {
        file.delete()
        sourceFile(file).delete()
    }

    // ---------- 底稿 ----------

    private fun sourceFile(file: File) = File(file.parentFile, ".src/${file.name}.json")

    /** 底稿：生成这个文件用的工具名和参数。 */
    fun saveSource(file: File, tool: String, args: JsonObject) {
        val f = sourceFile(file)
        f.parentFile?.mkdirs()
        f.writeText(buildJsonObject { put("tool", tool); put("args", args) }.toString())
    }

    fun source(file: File): Pair<String, JsonObject>? = runCatching {
        val o = Json.parseToJsonElement(sourceFile(file).readText()).jsonObject
        (o["tool"] as JsonPrimitive).content to o["args"]!!.jsonObject
    }.getOrNull()

    fun hasSource(file: File) = sourceFile(file).exists()

    /** 文件被整份重写了：旧底稿作废，不然读到、改到的还是旧底稿。 */
    fun dropSource(file: File) { sourceFile(file).delete() }

    /** 底稿就是一整段文字的工具：Word/PDF 是 Markdown，小工具是 HTML。其他的底稿是 JSON。 */
    private val TEXT_FIELD = mapOf("create_word" to "markdown", "create_pdf" to "markdown", "create_app" to "html")
    private val MARKDOWN_TOOLS = TEXT_FIELD.keys

    /** 给 AI 看、给 AI 改的底稿文字。Word/PDF 是 Markdown，其他是 JSON。 */
    fun sourceText(tool: String, args: JsonObject): String =
        if (tool in MARKDOWN_TOOLS) (args[TEXT_FIELD.getValue(tool)] as? JsonPrimitive)?.content.orEmpty()
        else pretty.encodeToString(JsonElement.serializer(), JsonObject(args - "filename"))

    /** 改过的底稿文字 → 新的参数。 */
    fun argsFromText(tool: String, old: JsonObject, text: String): JsonObject =
        if (tool in MARKDOWN_TOOLS) JsonObject(old + (TEXT_FIELD.getValue(tool) to JsonPrimitive(text)))
        else {
            val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw TextEdit.EditException("改完后底稿不是有效的 JSON 了（可能少了引号、逗号或括号），这次修改没有保存，请检查后重试")
            JsonObject(parsed + ("filename" to (old["filename"] ?: JsonPrimitive(""))))
        }

    /** 图表部件里缓存的数：类型、分类、每组的数，一段话写清，给模型看。 */
    fun chartText(xml: String): String? {
        val type = Regex("""<c:(bar|line|pie|doughnut|area|scatter)Chart>""").find(xml)?.groupValues?.get(1) ?: return null
        val kind = when (type) { "bar" -> if (xml.contains("<c:barDir val=\"bar\"/>")) "横条图" else "柱状图"; "line" -> "折线图"; "pie" -> "饼图"; "doughnut" -> "环形图"; else -> type }
        val title = Regex("""<c:title>.*?<a:t>(.*?)</a:t>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim().orEmpty()
        fun pts(block: String) = Regex("""<c:pt idx="\d+">\s*<c:v>(.*?)</c:v>""").findAll(block).map { TextEdit.unescape(it.groupValues[1]) }.toList()
        val sers = Regex("""<c:ser>(.*?)</c:ser>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }.toList()
        if (sers.isEmpty()) return null
        val cats = Regex("""<c:cat>(.*?)</c:cat>""", RegexOption.DOT_MATCHES_ALL).find(sers[0])?.groupValues?.get(1)?.let(::pts).orEmpty()
        val lines = sers.map { s ->
            val name = Regex("""<c:tx>(.*?)</c:tx>""", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1)?.let(::pts)?.firstOrNull()?.trim().orEmpty()
            val vals = Regex("""<c:val>(.*?)</c:val>""", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1)?.let(::pts).orEmpty()
            (name.ifBlank { "数值" }) + "：" + vals.joinToString("、")
        }
        return kind + (if (title.isNotBlank()) "「$title」" else "") + "\n分类：" + cats.joinToString("、") + "\n" + lines.joinToString("\n")
    }

    // ---------- 读文字 ----------

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "ts", "kt", "java", "py",
        "c", "cpp", "h", "go", "rs", "sh", "yaml", "yml", "ini", "toml", "sql", "log", "srt", "tex", "jsx", "tsx", "vue", "swift",
    )
    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun ext(file: File) = file.extension.lowercase()

    fun isText(file: File): Boolean {
        if (ext(file) in TEXT_EXT) return true
        if (ext(file) in IMAGE_EXT || ext(file) in setOf("docx", "pptx", "xlsx", "pdf", "zip", "apk")) return false
        return runCatching {
            file.inputStream().use { s ->
                val buf = ByteArray(4000)
                val n = s.read(buf).coerceAtLeast(0)
                (0 until n).none { buf[it] == 0.toByte() }
            }
        }.getOrDefault(false)
    }

    /** 读出文件的文字内容。有底稿的读底稿（可改），否则尽量抽出文字（Word 可改，其他只读）。 */
    /** extracted=true 时不看底稿，直接从文件里抽文字（核对生成出来的文件对不对）。 */
    fun readText(file: File, extracted: Boolean = false): String {
        if (!extracted) source(file)?.let { (tool, args) -> return sourceText(tool, args) }
        return when (ext(file)) {
            "docx" -> ZipFile(file).use { z ->
                val text = z.getEntry("word/document.xml")?.let { TextEdit.docxText(z.getInputStream(it).readBytes().toString(Charsets.UTF_8)) }.orEmpty()
                // 图表里的数（转成 PPT、Excel 时用得上）
                val charts = z.entries().toList().map { it.name }.filter { it.matches(Regex("word/charts/chart\\d+\\.xml")) }
                    .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
                    .mapIndexedNotNull { i, n -> chartText(z.getInputStream(z.getEntry(n)).readBytes().toString(Charsets.UTF_8))?.let { "【图表 ${i + 1}】$it" } }
                if (charts.isEmpty()) text else text + "\n\n" + charts.joinToString("\n\n")
            }
            "pdf" -> PDDocument.load(file).use { PDFTextStripper().getText(it) }
            "pptx" -> ZipFile(file).use { z ->
                fun read(name: String) = z.getEntry(name)?.let { e -> z.getInputStream(e).readBytes().toString(Charsets.UTF_8) }
                fun texts(xml: String) = Regex("<a:p>(.*?)</a:p>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
                    .map { p -> Regex("<a:t>(.*?)</a:t>").findAll(p.value).joinToString("") { TextEdit.unescape(it.groupValues[1]) } }
                    .filter { it.isNotBlank() }.joinToString("\n")
                z.entries().toList().filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                    .sortedBy { it.name.filter(Char::isDigit).toIntOrNull() ?: 0 }
                    .mapIndexed { i, e ->
                        val xml = z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                        // 这页的关系：图表、讲稿
                        val rels = read("ppt/slides/_rels/" + e.name.substringAfterLast('/') + ".rels").orEmpty()
                        fun target(type: String) = Regex("""Type="[^"]*/$type" Target="\.\./([^"]+)"""").findAll(rels).map { "ppt/" + it.groupValues[1] }.toList()
                        val charts = target("chart").mapNotNull { read(it)?.let(::chartText) }
                        val notes = target("notesSlide").firstOrNull()?.let(::read)?.let { n ->
                            // 讲稿在 body 占位符里，缩略图占位符没有字
                            Regex("""<p:sp>.*?</p:sp>""", RegexOption.DOT_MATCHES_ALL).findAll(n).map { it.value }
                                .filter { it.contains("type=\"body\"") }.joinToString("\n") { texts(it) }
                        }.orEmpty()
                        "【第 ${i + 1} 页】\n" + texts(xml) +
                            charts.joinToString("") { "\n【图表】$it" } +
                            (if (notes.isNotBlank()) "\n【讲稿】$notes" else "")
                    }.joinToString("\n\n")
            }
            "xlsx" -> ZipFile(file).use { z ->
                z.getEntry("xl/sharedStrings.xml")?.let { e ->
                    val xml = z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                    "（只读出了表格里的文字）\n" + Regex("<t[^>]*>(.*?)</t>").findAll(xml).joinToString("\n") { TextEdit.unescape(it.groupValues[1]) }
                }.orEmpty()
            }
            in IMAGE_EXT -> throw TextEdit.EditException("这是图片，没法当文字读")
            else -> if (isText(file)) file.readText() else throw TextEdit.EditException("这种文件读不出文字")
        }
    }

    /** 这个文件能不能局部修改，以及怎么改。给 AI 的说明里用。 */
    fun editKind(file: File): String = when {
        hasSource(file) -> "可改（改底稿后自动重新生成）"
        ext(file) == "docx" -> "可改文字"
        isText(file) -> "可改"
        else -> "只读"
    }
}
