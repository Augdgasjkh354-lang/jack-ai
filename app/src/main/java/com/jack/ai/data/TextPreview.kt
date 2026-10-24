package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 在 App 里直接看文本类文件（txt、md、csv、json、代码……），不用找别的 App：
 * 手机上大多没有能打开 .md、.json 的应用，点了只会弹「没有能打开的 App」。
 */
object TextPreview {
    /** 只读前面这么多字节：再大的文件看个开头就够了，也不会把内存撑爆。 */
    const val MAX_BYTES = 2 * 1024 * 1024
    /** Markdown 超过这么多字就不排版了，按纯文本显示（太长排版会卡）。 */
    const val MARKDOWN_MAX = 120_000

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "log", "xml", "yaml", "yml", "ini", "conf", "cfg", "toml", "properties",
        "py", "kt", "kts", "java", "js", "ts", "jsx", "tsx", "css", "scss", "sh", "bat", "sql", "c", "h", "cpp", "go", "rs",
        "swift", "rb", "php", "lua", "r", "gradle", "srt", "vtt", "tex",
    )
    private val CODE_EXT = TEXT_EXT - setOf("txt", "md", "markdown", "log", "srt", "vtt")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isText(name: String) = ext(name) in TEXT_EXT
    fun isMarkdown(name: String) = ext(name) in setOf("md", "markdown")
    /** 用等宽字体显示（代码、表格数据、配置）。 */
    fun isCode(name: String) = ext(name) in CODE_EXT

    /** 读文件：太大只读开头；返回 (文字, 是否截断)。 */
    fun read(f: File): Pair<String, Boolean> {
        val cut = f.length() > MAX_BYTES
        val bytes = f.inputStream().use { input ->
            if (!cut) input.readBytes() else ByteArray(MAX_BYTES).also { buf ->
                var off = 0
                while (off < buf.size) { val n = input.read(buf, off, buf.size - off); if (n < 0) break; off += n }
            }
        }
        return decode(bytes, cut) to cut
    }

    /**
     * 先当 UTF-8 解；解不通就是 Windows 上常见的 GBK（用 GB18030，它包含 GBK）。
     * 截断的文件末尾可能断在半个字上，那几个字节忽略掉。
     */
    fun decode(bytes: ByteArray, truncated: Boolean = false): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            bytes.copyOfRange(3, bytes.size) else bytes
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(if (truncated) trimPartialUtf8(body) else body)).toString()
        } catch (e: CharacterCodingException) {
            String(body, Charset.forName("GB18030"))
        }
    }

    /** UTF-8 末尾没写完的那个字（最多 3 个字节）去掉。 */
    private fun trimPartialUtf8(b: ByteArray): ByteArray {
        var i = b.size - 1
        var back = 0
        while (i >= 0 && back < 4 && (b[i].toInt() and 0xC0) == 0x80) { i--; back++ }
        if (i < 0) return b
        val lead = b[i].toInt() and 0xFF
        val need = when { lead >= 0xF0 -> 4; lead >= 0xE0 -> 3; lead >= 0xC0 -> 2; else -> 1 }
        return if (b.size - i < need) b.copyOfRange(0, i) else b
    }

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** JSON 排成缩进好读的样子；不是合法 JSON 就原样返回。 */
    fun prettyJson(text: String): String = runCatching {
        pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text))
    }.getOrDefault(text)

    /** 长文分段显示（按行切，每段大约 size 字），列表只画看得见的那几段，几十万字也不卡。 */
    fun chunks(text: String, size: Int = 4000): List<String> {
        if (text.length <= size) return listOf(text)
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + size).coerceAtMost(text.length)
            if (end < text.length) {
                val nl = text.lastIndexOf('\n', end)
                if (nl > start) end = nl + 1
            }
            out += text.substring(start, end).removeSuffix("\n")
            start = end
        }
        return out
    }
}
