package com.jack.ai.api

import kotlinx.serialization.Serializable

/** Kavi 浏览器的小规则（纯逻辑）：最近访问、书签、浏览器标识、选文件的类型。 */
object BrowserPages {
    /** 一个网页：最近访问和书签都用它。 */
    @Serializable
    data class Visit(val url: String, val title: String = "", val at: Long = 0)

    const val RECENT_MAX = 30

    /** 记一次访问：同一个网址只留最新的一条，排最前；只记 http/https。 */
    fun visit(list: List<Visit>, url: String, title: String, now: Long, max: Int = RECENT_MAX): List<Visit> {
        if (!isWeb(url)) return list
        return (listOf(Visit(url, title.trim(), now)) + list.filterNot { it.url == url }).take(max)
    }

    /** 网页标题晚到时补上（只改这个网址的，不改顺序）。 */
    fun retitle(list: List<Visit>, url: String, title: String): List<Visit> =
        if (title.isBlank() || list.none { it.url == url && it.title != title.trim() }) list
        else list.map { if (it.url == url) it.copy(title = title.trim()) else it }

    /** 书签：有就去掉，没有就加在最前。 */
    fun toggle(marks: List<Visit>, url: String, title: String, now: Long): List<Visit> =
        if (marks.any { it.url == url }) marks.filterNot { it.url == url }
        else if (!isWeb(url)) marks
        else listOf(Visit(url, title.trim(), now)) + marks

    fun isWeb(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

    /** 列表里显示的名字：有标题用标题，没有用网站名。 */
    fun label(v: Visit): String = v.title.ifBlank { host(v.url) }

    fun host(url: String): String = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url

    /**
     * 去掉网页组件自带的「App 内嵌」标记（`; wv` 和 `Version/4.0`），让网站当成普通手机 Chrome。
     * 带着这个标记，有的网站会给简化版页面，或者不让登录。
     */
    fun cleanUserAgent(ua: String): String =
        ua.replace("; wv)", ")").replace(Regex("""Version/\d+(\.\d+)* """), "")

    /** 电脑版用的浏览器标识：Windows 上的 Chrome，版本号跟手机上的网页组件一致。 */
    fun desktopUserAgent(mobileUa: String): String {
        val v = Regex("""Chrome/([\d.]+)""").find(mobileUa)?.groupValues?.get(1) ?: "130.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Safari/537.36"
    }

    /** 「电脑版」按网站记：网站名去掉 www.。 */
    fun site(url: String): String = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.").orEmpty()

    /** 这个网址在不在电脑版名单里（名单里的网站和它的子域名都算）。 */
    fun isDesktop(sites: Set<String>, url: String): Boolean {
        val h = site(url)
        return h.isNotEmpty() && sites.any { h == it || h.endsWith(".$it") }
    }

    /** 「问 Kavi」带过去的文件名：网页标题，去掉文件名里不能用的字符。 */
    fun fileName(title: String, url: String): String =
        title.ifBlank { host(url) }.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().take(40).ifBlank { "网页" } + ".txt"

    /**
     * 网页 <input accept> 要的类型 → 选文件时的 (type, 额外的 MIME 列表)。
     * 「.pdf」这种扩展名换成 MIME；只有一种就直接用，几种就用 * / * 加列表，看不懂的不限制。
     */
    fun pickTypes(accept: Array<String>?): Pair<String, List<String>> {
        val types = accept.orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .map { t -> if (t.startsWith(".")) EXT_MIME[t.drop(1)] ?: "" else t }
        if (types.isEmpty() || types.any { it.isEmpty() || '/' !in it }) return "*/*" to emptyList()
        val distinct = types.distinct()
        return if (distinct.size == 1) distinct[0] to emptyList() else "*/*" to distinct
    }

    private val EXT_MIME = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "heic" to "image/heic", "pdf" to "application/pdf", "txt" to "text/plain", "csv" to "text/csv",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "zip" to "application/zip", "mp4" to "video/mp4", "mp3" to "audio/mpeg", "json" to "application/json",
    )
}
