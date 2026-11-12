package com.jack.ai.api

/**
 * 「这个网站的数据」：只清一个网站（连同它的子域名）的 Cookie 和网页存储，别的网站的登录不动。
 * 网页组件没有按网站删 Cookie 的接口，只能把看得到的每个 Cookie 按各种可能的域名、路径写成过期。
 */
object BrowserSiteData {
    /** 两段式的后缀（com.cn 这类），主域名要多取一段。 */
    private val SECOND = setOf("com", "net", "org", "gov", "edu", "co", "ac", "or", "ne", "go")

    /** 主域名：m.taobao.com → taobao.com，www.bbc.co.uk → bbc.co.uk。IP 和单段名原样返回。 */
    fun root(host: String): String {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || h.all { it.isDigit() || it == '.' } || ':' in h) return h
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val n = if (parts[parts.size - 2] in SECOND && parts.last().length == 2) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    /** 这个网址属不属于这个主域名（本身或子域名）。 */
    fun belongs(host: String, root: String) = host.lowercase().let { it == root || it.endsWith(".$root") }

    /** Cookie 头里的名字（"a=1; b=2" → [a, b]）。 */
    fun names(cookieHeader: String?): List<String> =
        cookieHeader.orEmpty().split(';').mapNotNull { it.substringBefore('=').trim().takeIf { n -> n.isNotEmpty() } }.distinct()

    /** 从 host 一路往上到主域名的每一级：www.a.taobao.com → [www.a.taobao.com, a.taobao.com, taobao.com]。 */
    fun levels(host: String, root: String): List<String> {
        val h = host.lowercase()
        if (!belongs(h, root)) return listOf(h)
        val out = mutableListOf(h)
        var cur = h
        while (cur != root && '.' in cur) { cur = cur.substringAfter('.'); out += cur }
        return out.distinct()
    }

    /** 路径的每一级：/a/b/c → [/, /a, /a/b, /a/b/c]。 */
    fun paths(path: String): List<String> {
        val segs = path.split('/').filter { it.isNotEmpty() }.take(6)
        return listOf("/") + segs.indices.map { i -> "/" + segs.take(i + 1).joinToString("/") }
    }

    private const val GONE = "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"

    /**
     * 要写的过期 Cookie：(给哪个网址设, Set-Cookie 内容)。每个名字都按「只给这个域名」和「给 .域名」、
     * 各级路径各写一遍（不知道当初是怎么设的），https 的加上 Secure（__Secure- / __Host- 开头的必须有）。
     */
    fun expire(names: Collection<String>, hosts: Collection<String>, root: String, path: String = "/"): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        val ps = paths(path)
        for (h in hosts) {
            val domains = levels(h, root)
            for (n in names) for (p in ps) {
                for (scheme in listOf("https", "http")) {
                    val secure = if (scheme == "https") "; Secure" else ""
                    out += "$scheme://$h" to "$n=; Path=$p; $GONE$secure"
                    domains.forEach { d -> out += "$scheme://$h" to "$n=; Domain=$d; Path=$p; $GONE$secure" }
                }
            }
        }
        return out.toList()
    }

    /** 网页存储的大小写给人看。 */
    fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> "0"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
