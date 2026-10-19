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

    private val MARKDOWN_TOOLS = setOf("create_word", "create_pdf")

    /** 给 AI 看、给 AI 改的底稿文字。Word/PDF 是 Markdown，其他是 JSON。 */
    fun sourceText(tool: String, args: JsonObject): String =
        if (tool in MARKDOWN_TOOLS) (args["markdown"] as? JsonPrimitive)?.content.orEmpty()
        else pretty.encodeToString(JsonElement.serializer(), JsonObject(args - "filename"))

    /** 改过的底稿文字 → 新的参数。 */
    fun argsFromText(tool: String, old: JsonObject, text: String): JsonObject =
        if (tool in MARKDOWN_TOOLS) JsonObject(old + ("markdown" to JsonPrimitive(text)))
        else {
            val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw TextEdit.EditException("改完后底稿不是有效的 JSON 了（可能少了引号、逗号或括号），这次修改没有保存，请检查后重试")
            JsonObject(parsed + ("filename" to (old["filename"] ?: JsonPrimitive(""))))
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
    fun readText(file: File): String {
        source(file)?.let { (tool, args) -> return sourceText(tool, args) }
        return when (ext(file)) {
            "docx" -> ZipFile(file).use { z ->
                z.getEntry("word/document.xml")?.let { TextEdit.docxText(z.getInputStream(it).readBytes().toString(Charsets.UTF_8)) }.orEmpty()
            }
            "pdf" -> PDDocument.load(file).use { PDFTextStripper().getText(it) }
            "pptx" -> ZipFile(file).use { z ->
                z.entries().toList().filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                    .sortedBy { it.name.filter(Char::isDigit).toIntOrNull() ?: 0 }
                    .mapIndexed { i, e ->
                        val xml = z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                        "【第 ${i + 1} 页】\n" + Regex("<a:t>(.*?)</a:t>").findAll(xml).joinToString("\n") { TextEdit.unescape(it.groupValues[1]) }
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
