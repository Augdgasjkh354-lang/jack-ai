package com.jack.ai.api

import com.jack.ai.data.ProxyCore
import java.net.URI

/**
 * Kavi 浏览器的网络规则（纯逻辑，界面在 ui/BrowserActivity）。
 * 线路：auto 跟 Kavi 专用代理的分流走（国内直连、国外走代理；代理没开就全部直连）；
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
    /** always：用户设了「总走代理」的网站，自动模式下也不直连。 */
    fun plan(mode: String, c: Net.Config = Net.config, always: Set<String> = emptySet(), ensure: (Net.Config) -> Boolean = { Net.ensureCore(it) }): Plan {
        if (mode == DIRECT) return Plan.Direct
        if (!c.enabled) {
            return if (mode == PROXY) Plan.Blocked("Kavi 专用代理没开（设置 → Kavi 专用代理），选了「全部走代理」就不会直连")
            else Plan.Direct
        }
        if (!ensure(c)) return Plan.Blocked("代理没能启动：" + (ProxyCore.lastError ?: "原因不明"))
        return Plan.Proxy(ProxyCore.port, if (mode == PROXY) emptyList() else bypassRules(always))
    }

    /**
     * Net.DOMESTIC 换成 WebView 认的直连规则：".cn" → "*.cn"，"qq.com" → "qq.com" 和 "*.qq.com"。
     * always 里的网站（总走代理）从直连名单里拿掉：它属于哪条名单，那条就不直连了。
     */
    fun bypassRules(always: Set<String> = emptySet()): List<String> = Net.DOMESTIC
        .filterNot { d -> !d.startsWith(".") && always.any { it == d || it.endsWith(".$d") } }
        .flatMap { d -> if (d.startsWith(".")) listOf("*$d") else listOf(d, "*.$d") }

    /** 这个网站能不能单独设成总走代理：只靠后缀（比如 .cn）直连的不行，拿掉会让整个后缀都走代理。 */
    fun canForceProxy(site: String): Boolean {
        val h = site.lowercase()
        return Net.DOMESTIC.none { d -> d.startsWith(".") && h.endsWith(d) }
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
        // fc00::/7、fe80:: 只认 IPv6 地址（带冒号），fc2.com、fdroid.org 这类网站不算
        if (':' in h && (h == "::1" || h == "::" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd"))) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || h.count { it == '.' } != 3) return false
        return p[0] == 127 || p[0] == 10 || p[0] == 0 || (p[0] == 169 && p[1] == 254) ||
            (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 100 && p[1] in 64..127)
    }

    /** 做了收不回的事：买、付、发、删、提交…（中英文按钮上常见的字） */
    private val RISKY = Regex(
        "购买|买入|下单|支付|付款|结算|提交|确认订单|转账|汇款|充值|捐|发送|发布|发表|评论|回复|删除|注销|退订|取消订阅|清空|授权|" +
            "\\b(pay|buy|purchase|order|checkout|donate|send|post|publish|tweet|reply|comment|delete|remove|unsubscribe|transfer|submit|authorize|confirm)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Kavi 在网页上做这一步之前要不要先问用户；要问就返回原因。
     * 点到上面那类按钮要问；点提交按钮、在输入框里回车，提交的是 POST 表单（登录除外）也要问。
     * 输入、删字、选择本身不会提交，不问（密码框另外拦）。
     */
    /** 下单、付款、发出、删除这类：点两次就是两单、两条，重复了收不回。 */
    fun isRisky(text: String) = RISKY.containsMatchIn(text)

    fun confirmReason(action: String, label: String, submit: Boolean, formPost: Boolean, formPassword: Boolean, formButtons: String, key: String = ""): String? {
        return when {
            action == "click" && RISKY.containsMatchIn(label) -> "按钮上写着「$label」，可能会下单、付款、发出内容或删除东西。"
            action == "click" && submit && formPost && !formPassword -> "这会提交网页上的表单。"
            action == "key" && key == "enter" && formPost && !formPassword -> "回车会提交网页上的表单" +
                (if (RISKY.containsMatchIn(formButtons)) "（表单的按钮是「$formButtons」）" else "") + "。"
            action == "key" && key == "enter" && RISKY.containsMatchIn(formButtons) -> "回车会提交表单，表单的按钮是「$formButtons」。"
            else -> null
        }
    }

    /**
     * 代理要密码时，只把口令交给 Kavi 自己的代理（本机地址 + sing-box 的 realm）。
     * sing-box 的响应头写成 `Basic realm="sing-box" charset="UTF-8"`，Chromium 读出来的 realm 是
     * `sing-box" charset="UTF-8`（实测），所以按开头比。
     */
    fun isOurProxy(host: String?, realm: String?): Boolean {
        val h = host.orEmpty().removePrefix("http://")
        return (h == "127.0.0.1" || h == "127.0.0.1:${ProxyCore.port}") && realm.orEmpty().startsWith("sing-box")
    }
}
