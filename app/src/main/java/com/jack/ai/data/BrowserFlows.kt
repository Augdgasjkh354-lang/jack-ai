package com.jack.ai.data

import com.jack.ai.api.BrowserPages
import kotlinx.serialization.Serializable

/**
 * 浏览器流程：在某个网站上办一件事的做法（文字步骤，不录点击位置——网页改版也能照着意思做）。
 * 平时不占上下文：技能列表里只有「浏览器流程」一行；打开对应网站时工具提醒一句，用到才读出步骤。
 */
@Serializable
data class BrowserFlow(
    val id: String = newId(),
    val name: String,
    /** 网站名，不带 www.（比如 1688.com），子域名也算。 */
    val site: String,
    /** 什么时候用，一句话。 */
    val whenToUse: String = "",
    /** 编号的文字步骤；每次会变的写成 {关键词} 这样的空。 */
    val steps: String,
    val createdAt: Long = System.currentTimeMillis(),
    val usedAt: Long = 0,
    val uses: Int = 0,
)

/** 网站备忘：在这个网站上踩过的小坑（要先关弹窗、按钮要等一会才能点……），下次少走弯路。 */
@Serializable
data class SiteNote(
    val id: String = newId(),
    val site: String,
    val text: String,
    val at: Long = System.currentTimeMillis(),
)

object BrowserFlows {
    /** 每个网站最多记几条备忘（新的挤掉旧的）。 */
    const val NOTES_PER_SITE = 8

    /** 自带的网站备忘：已知的坑，不用等踩过再记（不在用户的备忘列表里显示）。 */
    private val BUILTIN_NOTES = mapOf(
        "chatgpt.com" to "要登录就直接打开 https://chatgpt.com/auth/login（整页的登录表单）；首页右上角「登录」弹出的框在有些手机上出不来",
    )

    /** 这个网址上自带的备忘。 */
    fun builtinNotesFor(url: String): List<SiteNote> {
        val h = BrowserPages.site(url)
        if (h.isEmpty()) return emptyList()
        return BUILTIN_NOTES.filter { (site, _) -> h == site || h.endsWith(".$site") }.map { (site, text) -> SiteNote(id = "builtin-$site", site = site, text = text, at = 0) }
    }

    /** 这个网址上的备忘。 */
    fun notesFor(notes: List<SiteNote>, url: String): List<SiteNote> {
        val h = BrowserPages.site(url)
        if (h.isEmpty()) return emptyList()
        return notes.filter { n -> h == n.site || h.endsWith(".${n.site}") }
    }

    /** 记一条：同样的话不重复记，每个网站只留最新的几条。 */
    fun addNote(notes: List<SiteNote>, site: String, text: String, now: Long = System.currentTimeMillis()): List<SiteNote> {
        val t = text.trim().take(200)
        if (t.isEmpty() || notes.any { it.site == site && it.text == t }) return notes
        val mine = (notes.filter { it.site == site } + SiteNote(site = site, text = t, at = now)).sortedByDescending { it.at }.take(NOTES_PER_SITE)
        return notes.filter { it.site != site } + mine
    }

    /** 网址或网站名 → 网站名（小写、不带 www.）。 */
    fun siteOf(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        return BrowserPages.site(if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) t else "https://$t")
    }

    /** 这个网址上能用的流程（流程的网站和它的子域名）。 */
    fun forUrl(flows: List<BrowserFlow>, url: String): List<BrowserFlow> {
        val h = BrowserPages.site(url)
        if (h.isEmpty()) return emptyList()
        return flows.filter { f -> f.site.isNotEmpty() && (h == f.site || h.endsWith(".${f.site}")) }
    }

    /** 按名字找：先找一字不差的，再找包含的。 */
    fun find(flows: List<BrowserFlow>, name: String): BrowserFlow? {
        val n = name.trim()
        return flows.firstOrNull { it.name == n } ?: flows.firstOrNull { it.name.contains(n, true) || n.contains(it.name, true) }
    }

    /** 步骤里要填的空：{关键词}、{页数}……（去重，按出现顺序）。 */
    fun blanks(steps: String): List<String> = Regex("""\{([^{}\n]{1,20})\}""").findAll(steps).map { it.groupValues[1].trim() }.distinct().toList()

    /** 给模型看的流程清单（读「浏览器流程」技能时附上）。 */
    fun index(flows: List<BrowserFlow>): String =
        if (flows.isEmpty()) "（还没有存过流程）"
        else flows.sortedByDescending { maxOf(it.usedAt, it.createdAt) }.joinToString("\n") { f ->
            "- 「${f.name}」（${f.site}）" + f.whenToUse.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        }

    /** 打开有流程或备忘的网站时，工具附在页面前面的提醒。 */
    fun hint(flows: List<BrowserFlow>, notes: List<SiteNote> = emptyList()): String = buildString {
        if (flows.isNotEmpty()) append("【这个网站存了流程】" + flows.joinToString("；") { f -> "「${f.name}」" + f.whenToUse.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty() } +
            "。和用户要做的事对得上，就先用 action=flow 填 name 读出步骤照着做；对不上就照常做。\n")
        if (notes.isNotEmpty()) append("【这个网站的备忘】" + notes.joinToString("；") { it.text } + "\n")
    }
}
