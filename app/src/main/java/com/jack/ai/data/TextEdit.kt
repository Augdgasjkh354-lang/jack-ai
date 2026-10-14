package com.jack.ai.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 局部修改：只把一段原文换成新内容，其余一个字都不动。 */
object TextEdit {

    class EditException(message: String) : Exception(message)

    /** 返回 (新文本, 替换次数)。原文找不到或不唯一时抛出 EditException，信息直接给 AI 看。 */
    fun replace(text: String, old: String, new: String, all: Boolean = false): Pair<String, Int> {
        if (old.isEmpty()) throw EditException("old_text 不能为空")
        if (old == new) throw EditException("old_text 和 new_text 一样，不需要修改")
        val count = countOf(text, old)
        if (count == 0) {
            val hint = if (old.trim() != old && countOf(text, old.trim()) > 0) "（去掉首尾空白后能找到，注意空格和换行要和原文完全一致）" else
                "。先用 read_file 看一下原文，复制要改的那段，空格、标点、换行都要完全一致"
            throw EditException("文件里找不到这段原文$hint")
        }
        if (count > 1 && !all) {
            throw EditException("这段原文在文件里出现了 $count 次。多带几个前后的字让它唯一，或者把 replace_all 设为 true 全部替换")
        }
        return (if (all) text.replace(old, new) else text.replaceFirst(old, new)) to (if (all) count else 1)
    }

    fun countOf(text: String, part: String): Int {
        if (part.isEmpty()) return 0
        var n = 0
        var i = text.indexOf(part)
        while (i >= 0) { n++; i = text.indexOf(part, i + part.length) }
        return n
    }

    // ---------- Word ----------

    private val PARAGRAPH = Regex("""<w:p[ >].*?</w:p>|<w:p/>""", RegexOption.DOT_MATCHES_ALL)
    private val RUN_TEXT = Regex("""<w:t(\s[^>]*)?>(.*?)</w:t>|<w:t(\s[^>]*)?/>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * 在 docx 里替换文字，尽量保留原来的格式。
     * Word 常把一句话拆成好几段（run）存，这里把一个段落里的文字拼起来找，
     * 替换后的文字放进第一段，后面被覆盖的部分删掉，所以改动处沿用开头那段的格式。
     */
    fun replaceInDocx(docx: ByteArray, old: String, new: String, all: Boolean = false): Pair<ByteArray, Int> {
        if (old.contains('\n')) throw EditException("Word 里一次只能改同一段之内的文字，old_text 不能跨段（不能含换行）")
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(docx.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) entries[e.name] = zip.readBytes()
            }
        }
        val xml = entries["word/document.xml"]?.toString(Charsets.UTF_8) ?: throw EditException("这不是有效的 Word 文件")
        val (newXml, n) = replaceInDocumentXml(xml, old, new, all)
        entries["word/document.xml"] = newXml.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return out.toByteArray() to n
    }

    fun replaceInDocumentXml(xml: String, old: String, new: String, all: Boolean): Pair<String, Int> {
        if (old.isEmpty()) throw EditException("old_text 不能为空")
        val total = PARAGRAPH.findAll(xml).sumOf { countOf(paragraphText(it.value), old) }
        if (total == 0) throw EditException("Word 里找不到这段文字。先用 read_file 看原文，复制时注意标点和空格要完全一致")
        if (total > 1 && !all) throw EditException("这段文字在文档里出现了 $total 次。多带几个前后的字让它唯一，或者把 replace_all 设为 true")
        val result = PARAGRAPH.replace(xml) { m ->
            val p = m.value
            if (countOf(paragraphText(p), old) == 0) p else editParagraph(p, old, new)
        }
        return result to total
    }

    private fun paragraphText(p: String) = RUN_TEXT.findAll(p).joinToString("") { unescape(it.groupValues[2]) }

    private fun editParagraph(p: String, old: String, new: String): String {
        val runs = RUN_TEXT.findAll(p).toList()
        val texts = runs.map { unescape(it.groupValues[2]) }.toMutableList()
        val changed = BooleanArray(runs.size)
        // 从后往前替换，前面的位置不受影响
        val full = texts.joinToString("")
        val hits = mutableListOf<Int>()
        var i = full.indexOf(old)
        while (i >= 0) { hits += i; i = full.indexOf(old, i + old.length) }
        for (start in hits.asReversed()) {
            val end = start + old.length
            val starts = IntArray(texts.size)
            var acc = 0
            texts.forEachIndexed { k, t -> starts[k] = acc; acc += t.length }
            val s = texts.indices.first { starts[it] <= start && start < starts[it] + texts[it].length }
            val e = texts.indices.first { starts[it] < end && end <= starts[it] + texts[it].length }
            val prefix = texts[s].substring(0, start - starts[s])
            val suffix = texts[e].substring(end - starts[e])
            if (s == e) {
                texts[s] = prefix + new + suffix
            } else {
                texts[s] = prefix + new
                for (k in s + 1 until e) texts[k] = ""
                texts[e] = suffix
            }
            for (k in s..e) changed[k] = true
        }
        val sb = StringBuilder()
        var last = 0
        runs.forEachIndexed { k, r ->
            sb.append(p, last, r.range.first)
            sb.append(if (changed[k]) "<w:t xml:space=\"preserve\">${escape(texts[k])}</w:t>" else r.value)
            last = r.range.last + 1
        }
        sb.append(p, last, p.length)
        return sb.toString()
    }

    fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 读出 docx 的文字，一段一行。 */
    fun docxText(xml: String): String = PARAGRAPH.findAll(xml).joinToString("\n") { paragraphText(it.value) }
}
