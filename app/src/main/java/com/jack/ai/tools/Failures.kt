package com.jack.ai.tools

import com.jack.ai.data.Persona
import com.jack.ai.data.Settings

/**
 * 失败原因分类：工具没做成，或者做成了但没拿到东西（搜索为空、网页读不到、到了登录页），按原因归一类，
 * 在结果后面附一句对应的换法，不让模型只看到一句报错就原地重试。
 * 同一类在这次回答里再次出现时说得更重：同样的路再走大概率还是不行。
 * 只看代码能确定的信号（工具名、状态、结果里的固定说法、HTTP 码），判不准就不加。
 */
object Failures {
    enum class Kind(val label: String) {
        EMPTY_SEARCH("empty search"),
        UNREADABLE_PAGE("unreadable page"),
        LOGIN("login required"),
        BLOCKED("blocked by the site"),
        RATE_LIMIT("rate limited"),
        NETWORK("network"),
        PERMISSION("missing permission"),
        NOT_FOUND("not found"),
        BAD_ARGS("bad arguments"),
        DECLINED("declined by the user"),
    }

    private val EMPTY_SEARCH = Regex("^没有搜到相关结果")
    private val LOGIN = Regex("""登录页|需要登录|请先登录|未登录|登录过期|\b401\b|Unauthorized|sign in to|log in to""", RegexOption.IGNORE_CASE)
    private val BLOCKED = Regex("""\b403\b|Forbidden|验证码|人机验证|captcha|访问受限|拒绝访问|Access Denied|Cloudflare""", RegexOption.IGNORE_CASE)
    private val RATE_LIMIT = Regex("""\b429\b|Too Many Requests|限流|请求过于频繁|太频繁|rate limit""", RegexOption.IGNORE_CASE)
    private val NETWORK = Regex("""网络连接|连接超时|超时|找不到服务器|读写或网络出错|连不上|timed? ?out|Failed to connect""", RegexOption.IGNORE_CASE)
    private val PERMISSION = Regex("""没有权限|未授权|没授权|权限被拒|Permission denied""", RegexOption.IGNORE_CASE)
    private val NOT_FOUND = Regex("""\b404\b|Not Found|找不到|不存在|没有叫「|没有「""", RegexOption.IGNORE_CASE)
    private val BAD_ARGS = Regex("""参数|缺少|格式不对|不是有效的|invalid argument|missing required""", RegexOption.IGNORE_CASE)
    /** 这些已经有自己的说法，不再叠一句：防打转的拦截、交付核实没通过。 */
    private val HANDLED = Regex("""你已经用完全相同的参数调用|【核实失败】""")

    /** 归哪一类；不算失败、或判不准就返回 null。 */
    fun classify(name: String, result: String, status: ToolStatus): Kind? {
        if (HANDLED.containsMatchIn(result)) return null
        if (status == ToolStatus.REJECTED) return Kind.DECLINED
        val head = result.take(600)
        // 做成了但没拿到东西
        if (status == ToolStatus.OK) return when {
            name == "web_search" && EMPTY_SEARCH.containsMatchIn(head.trim()) -> Kind.EMPTY_SEARCH
            name == "read_webpage" && (head.startsWith(com.jack.ai.api.WebClient.NO_CONTENT) || result.trim().length < 80) -> Kind.UNREADABLE_PAGE
            name == "browser" && head.contains("的登录页") -> Kind.LOGIN
            else -> null
        }
        return when {
            RATE_LIMIT.containsMatchIn(head) -> Kind.RATE_LIMIT
            LOGIN.containsMatchIn(head) -> Kind.LOGIN
            BLOCKED.containsMatchIn(head) -> Kind.BLOCKED
            PERMISSION.containsMatchIn(head) -> Kind.PERMISSION
            NETWORK.containsMatchIn(head) -> Kind.NETWORK
            NOT_FOUND.containsMatchIn(head) -> Kind.NOT_FOUND
            BAD_ARGS.containsMatchIn(head) -> Kind.BAD_ARGS
            else -> null
        }
    }

    /** 附在结果后面的那句：times 是这一类在这次回答里第几次出现。 */
    fun note(kind: Kind, times: Int, result: String, s: Settings): String {
        // 工具自己已经写了「下一步」的，第一次不再叠；再出现时才提醒换路
        if (times < 2 && "下一步" in result) return ""
        val next = when (kind) {
            Kind.EMPTY_SEARCH -> if (times < 2) "Rephrase rather than repeat: fewer or different keywords, synonyms, the English or local-language term, a site: filter, or another engine (engine=exa or tavily)."
                else "Several searches came back empty. Change approach: search a broader topic first, or go straight to a known site with read_webpage or the browser."
            Kind.UNREADABLE_PAGE -> "Try another source from the search results, or open the page with the browser skill if it's script-heavy. Don't cite a page you couldn't read."
            Kind.LOGIN -> "The content is behind a login. Don't guess what it says: use a public source instead, or ask {user} to log in once in the Kavi browser (in a background task, report it)."
            Kind.BLOCKED -> "The site is refusing automated access. Don't retry it; use another source, or the browser skill while {user} is around."
            Kind.RATE_LIMIT -> "The service is rate-limiting. Don't retry right away: do other steps first, or switch source or engine."
            Kind.NETWORK -> if (times < 2) "Retry once at most. If it fails again, stop and tell {user} what couldn't be reached."
                else "Network failures keep happening. Stop making network calls for now, finish what you can without them, and tell {user} plainly what couldn't be reached."
            Kind.PERMISSION -> "A permission is missing. Check with self_check, then tell {user} exactly which setting to turn on; don't look for workarounds."
            Kind.NOT_FOUND -> "Check the exact name, link or id (the folder list, the search results, or list first) instead of guessing again."
            Kind.BAD_ARGS -> "The call's arguments were wrong. Re-read the tool's parameters and fix them instead of sending the same call."
            Kind.DECLINED -> "{User} declined this. Don't retry it or find a way around it; carry on without it, or ask."
        }
        val again = if (times >= 2 && kind != Kind.DECLINED) ", seen $times times in this task" else ""
        return Persona.fill("\n\n【Failure: ${kind.label}$again】$next", s)
    }
}
