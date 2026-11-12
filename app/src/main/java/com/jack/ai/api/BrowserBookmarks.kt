package com.jack.ai.api

/**
 * 书签导入、导出：Chrome、Edge、Firefox、Safari 都能导出成同一种 bookmarks.html（Netscape 书签格式），
 * 导进来时拍平成一列，记下每个书签所在的文件夹；导出也写成这种格式，别的浏览器能直接导入。
 */
object BrowserBookmarks {
    /** 一次最多导进多少个（太多起始页和联想都变慢）。 */
    const val MAX = 5000

    /** 解析 bookmarks.html：(网址, 标题, 添加时间, 最里层的文件夹名)。只要 http/https 的，同一网址只留第一个。 */
    fun parse(html: String): List<BrowserPages.Visit> {
        val out = ArrayList<BrowserPages.Visit>()
        val seen = HashSet<String>()
        val folders = ArrayList<String>()
        var pendingFolder: String? = null
        val token = Regex("""<h3\b[^>]*>([\s\S]*?)</h3>|<dl\b[^>]*>|</dl>|<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in token.findAll(html)) {
            val t = m.value
            when {
                t.startsWith("<h3", true) -> pendingFolder = text(m.groupValues[1])
                t.startsWith("</dl", true) -> if (folders.isNotEmpty()) folders.removeAt(folders.lastIndex)
                t.startsWith("<dl", true) -> { folders += pendingFolder.orEmpty(); pendingFolder = null }
                else -> {
                    val attrs = m.groupValues[2]
                    val url = attr(attrs, "href")?.let(::unescape)?.trim() ?: continue
                    if (!BrowserPages.isWeb(url) || !seen.add(url)) continue
                    val at = attr(attrs, "add_date")?.trim()?.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else if (it < 100_000_000_000_000L) it else it / 1000 } ?: 0L
                    out += BrowserPages.Visit(url, text(m.groupValues[3]), at, folders.lastOrNull { it.isNotBlank() }.orEmpty())
                    if (out.size >= MAX) return out
                }
            }
        }
        return out
    }

    /** 导进来的接在原有书签后面，已经有的网址不重复加。返回 (新的书签列表, 新加了几个)。 */
    fun merge(marks: List<BrowserPages.Visit>, imported: List<BrowserPages.Visit>): Pair<List<BrowserPages.Visit>, Int> {
        val have = marks.mapTo(HashSet()) { it.url }
        val add = imported.filter { have.add(it.url) }.take((MAX - marks.size).coerceAtLeast(0))
        return (marks + add) to add.size
    }

    /** 写成 bookmarks.html：按文件夹分组，没有文件夹的在最外层。 */
    fun export(marks: List<BrowserPages.Visit>): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        fun line(v: BrowserPages.Visit, indent: String) {
            sb.append(indent).append("<DT><A HREF=\"").append(escape(v.url)).append('"')
            if (v.at > 0) sb.append(" ADD_DATE=\"").append(v.at / 1000).append('"')
            sb.append('>').append(escape(BrowserPages.label(v))).append("</A>\n")
        }
        marks.filter { it.folder.isBlank() }.forEach { line(it, "    ") }
        marks.filter { it.folder.isNotBlank() }.groupBy { it.folder }.forEach { (folder, list) ->
            sb.append("    <DT><H3>").append(escape(folder)).append("</H3>\n    <DL><p>\n")
            list.forEach { line(it, "        ") }
            sb.append("    </DL><p>\n")
        }
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(attrs)?.let { m ->
            m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
        }

    private fun text(html: String) = unescape(html.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()

    fun unescape(s: String): String = s.replace(Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            else -> runCatching {
                val code = if (e.startsWith("#x", true)) e.substring(2).toInt(16) else e.substring(1).toInt()
                String(Character.toChars(code))
            }.getOrDefault(m.value)
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
