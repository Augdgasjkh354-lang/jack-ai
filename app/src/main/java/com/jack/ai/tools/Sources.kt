package com.jack.ai.tools

/**
 * 来源核对：交付里引用的链接，得是这次任务里真的搜到过、读到过的（或者对话里本来就有的）。
 * 不在任何工具结果里出现过的链接，多半是模型编的。
 */
object Sources {
    private val URL = Regex("""https?://[^\s<>()\[\]{}"'，。、；：！？）》」】]+""", RegexOption.IGNORE_CASE)

    /** 文字里的链接（去掉结尾的标点）。 */
    fun find(text: String): List<String> = URL.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?', '*', '_', '`') }.toList()

    /** 比较用的形式：去掉协议、www、#后面、结尾的斜杠，主机名小写。 */
    fun norm(url: String): String {
        val noScheme = url.substringAfter("://").substringBefore('#').trimEnd('/')
        val host = noScheme.substringBefore('/').lowercase().removePrefix("www.")
        val rest = noScheme.substring(noScheme.substringBefore('/').length)
        return host + rest
    }

    private fun host(n: String) = n.substringBefore('/').substringBefore('?')

    /**
     * cited 里哪些没见过：完全一样的见过、或者只写了网站首页而这个网站见过，都算有据；
     * 比见过的更具体的（见过搜索页、引用的是某个商品页）不算。
     */
    fun unknown(cited: Collection<String>, seen: Collection<String>): List<String> {
        val seenNorm = seen.map(::norm).toSet()
        val seenHosts = seenNorm.map(::host).toSet()
        return cited.distinct().filter { u ->
            val n = norm(u)
            val homepage = !n.contains('/') && !n.contains('?')
            !(n in seenNorm || (homepage && host(n) in seenHosts) || seenNorm.any { it.startsWith("$n/") || it.startsWith("$n?") })
        }
    }
}
