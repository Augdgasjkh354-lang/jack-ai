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

object BrowserFlows {
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

    /** 打开有流程的网站时，工具附在页面前面的一句提醒。 */
    fun hint(flows: List<BrowserFlow>): String =
        "【这个网站存了流程】" + flows.joinToString("；") { f -> "「${f.name}」" + f.whenToUse.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty() } +
            "。和用户要做的事对得上，就先用 action=flow 填 name 读出步骤照着做；对不上就照常做。\n"
}
