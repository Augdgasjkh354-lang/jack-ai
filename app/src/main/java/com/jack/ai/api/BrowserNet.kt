package com.jack.ai.api

import com.jack.ai.data.ProxyCore
import java.net.URI

/**
 * Jack 浏览器的网络规则（纯逻辑，界面在 ui/BrowserActivity）。
 * 线路：auto 跟 Jack 专用代理的分流走（国内直连、国外走代理；代理没开就全部直连）；
 * proxy 全部走代理；direct 全部直连。走代理时代理起不来就不打开，不偷偷直连。
 */
object BrowserNet {
    const val AUTO = "auto"
    const val PROXY = "proxy"
    const val DIRECT = "direct"

    /** 这次要怎么上网。 */
    sealed class Plan {
        object Direct : Plan()
        /** 走 127.0.0.1:port；bypass 里的直连。 */
        data class Proxy(val port: Int, val bypass: List<String>) : Plan()
        /** 要走代理但代理用不了：不打开网页，告诉用户为什么。 */
        data class Blocked(val reason: String) : Plan()
    }

    /** 按线路和代理设置算出怎么上网；要走代理时顺手把内核拉起来。 */
    fun plan(mode: String, c: Net.Config = Net.config, ensure: (Net.Config) -> Boolean = { Net.ensureCore(it) }): Plan {
        if (mode == DIRECT) return Plan.Direct
        if (!c.enabled) {
            return if (mode == PROXY) Plan.Blocked("Jack 专用代理没开（设置 → Jack 专用代理），选了「全部走代理」就不会直连")
            else Plan.Direct
        }
        if (!ensure(c)) return Plan.Blocked("代理没能启动：" + (ProxyCore.lastError ?: "原因不明"))
        return Plan.Proxy(ProxyCore.port, if (mode == PROXY) emptyList() else bypassRules())
    }

    /** Net.DOMESTIC 换成 WebView 认的直连规则：".cn" → "*.cn"，"qq.com" → "qq.com" 和 "*.qq.com"。 */
    fun bypassRules(): List<String> = Net.DOMESTIC.flatMap { d ->
        if (d.startsWith(".")) listOf("*$d") else listOf(d, "*.$d")
    }

    /** 地址栏的输入 → 要打开的网址。像网址就补上 https://，不像就去搜。 */
    fun normalize(input: String, proxied: Boolean): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
        val looksLikeHost = !t.contains(' ') && Regex("""^[\w-]+(\.[\w-]+)+(:\d+)?(/.*)?$""").matches(t)
        if (looksLikeHost) return "https://$t"
        val q = java.net.URLEncoder.encode(t, "UTF-8")
        return if (proxied) "https://www.google.com/search?q=$q" else "https://cn.bing.com/search?q=$q"
    }

    /** 浏览器里一个网址该怎么处理。 */
    enum class Kind { OPEN, EXTERNAL, BLOCKED }

    /**
     * http/https 照常打开，但不许碰本机和局域网（防止网页借浏览器摸到手机上别的服务或代理端口）；
     * mailto、tel、intent、market 之类交给手机上的 App；file、content、javascript 等一律不开。
     */
    fun classify(url: String): Kind {
        val uri = runCatching { URI(url) }.getOrNull() ?: return Kind.BLOCKED
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> if (isLocal(uri.host.orEmpty())) Kind.BLOCKED else Kind.OPEN
            "mailto", "tel", "sms", "geo", "market", "intent", "weixin", "alipays", "zhihu", "bilibili", "snssdk1128" -> Kind.EXTERNAL
            else -> Kind.BLOCKED
        }
    }

    /** 本机、局域网、链路本地地址。 */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']').trimEnd('.')
        if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        if (h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd") || h == "::") return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || h.count { it == '.' } != 3) return false
        return p[0] == 127 || p[0] == 10 || p[0] == 0 || (p[0] == 169 && p[1] == 254) ||
            (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 100 && p[1] in 64..127)
    }

    /**
     * 代理要密码时，只把口令交给 Jack 自己的代理（本机地址 + sing-box 的 realm）。
     * sing-box 的响应头写成 `Basic realm="sing-box" charset="UTF-8"`，Chromium 读出来的 realm 是
     * `sing-box" charset="UTF-8`（实测），所以按开头比。
     */
    fun isOurProxy(host: String?, realm: String?): Boolean {
        val h = host.orEmpty().removePrefix("http://")
        return (h == "127.0.0.1" || h == "127.0.0.1:${ProxyCore.port}") && realm.orEmpty().startsWith("sing-box")
    }
}
