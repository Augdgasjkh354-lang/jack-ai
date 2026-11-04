// Copyright 2026 wangzan101. Licensed under Apache-2.0.
// Ported and adapted from Mika 0091d2d WebBarrier.kt.
// Jack AI modification: classify Kavi page snapshots without Mika's ScreenFacts dependency.
package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/** 只根据真正的主文档 HTTP 状态、拦截页、同站接口提示和可见验证框判断是否需要人工接管。 */
object WebBarrier {
    enum class Kind { LIMITED, CAPTCHA }
    data class Block(val kind: Kind, val detail: String)

    private val riskStatuses = setOf(403, 429, 503)
    private val riskWords = listOf(
        "访问受限", "访问异常", "异常访问", "环境异常", "访问行为异常", "操作频繁",
        "操作过于频繁", "访问过于频繁", "请求过于频繁", "请求异常", "请求太频繁",
        "访问频繁", "过于频繁",
        "unusualtraffic", "unusualactivity", "suspiciousactivity", "automatedqueries",
        "automatedrequests", "accesstemporarilyrestricted", "toomanyrequests",
        "requestwasblocked", "requesthasbeenblocked", "ratelimit", "riskcontrol",
    )
    private val apiRiskWords = listOf(
        "频繁", "访问受限", "账号存在风险", "风控", "toomanyrequests", "ratelimit", "riskcontrol",
        "unusualtraffic", "suspiciousactivity", "accesstemporarilyrestricted",
    )
    private val challengeWords = listOf(
        "请稍候", "确认您是真人", "验证您是否是真人", "请完成人机验证", "请完成安全验证",
        "checkingyourbrowser", "justamoment", "verifyyouarehuman",
        "checkingifthesiteconnectionissecure", "performingsecurityverification",
    )
    private val riskTitles = listOf("安全提示", "安全检查", "securitycheck", "securityverification")
    private val riskCues = listOf("异常", "验证", "频繁", "风险", "verify", "unusual", "suspicious")
    private val captchaHosts = listOf(
        "recaptcha.net", "hcaptcha.com", "challenges.cloudflare.com",
        "geetest.com", "arkoselabs.com", "funcaptcha.com",
    )
    private val apiMessageKeys = setOf(
        "message", "msg", "errmsg", "errormsg", "error_msg",
        "errormessage", "error", "tip", "tips", "toast", "reason",
    )

    fun classify(url: String, title: String, text: String, mainStatus: Int?,
                 frameUrls: List<String>, apiMessage: String? = null): Block? {
        if (mainStatus in riskStatuses) return Block(Kind.LIMITED, "HTTP $mainStatus")
        frameUrls.firstOrNull { captchaFrame(it, url) }?.let {
            return Block(Kind.CAPTCHA, "验证码框架")
        }
        // 只对短的拦截页判敏感词；不能把讲解风控的普通文章误认成拦截页。
        val compact = (title + "\n" + text).lowercase().filterNot(Char::isWhitespace)
        if (compact.length <= 800) {
            if (challengeWords.any { it in compact }) return Block(Kind.CAPTCHA, "需要人机验证")
            if (riskWords.any { it in compact } ||
                (riskTitles.any { it in compact } && riskCues.any { it in compact })) {
                return Block(Kind.LIMITED, "网站提示访问异常或操作频繁")
            }
        }
        if (apiMessage != null) return Block(Kind.LIMITED, "接口提示访问受限")
        return null
    }

    /** 接口只能看顶层错误消息，不读 data 里的商品正文，避免把商品名称误判成风控提示。 */
    fun apiRisk(body: String?): String? {
        val s = body?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val obj = runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull() ?: return null
        for ((key, value) in obj) {
            if (key.lowercase() !in apiMessageKeys) continue
            val msg = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val compact = msg.lowercase().filterNot(Char::isWhitespace)
            // 接口失败常回「请求异常」这类泛泛的话，只认明确的限流、风控说法
            if (compact.length <= 200 && apiRiskWords.any { it in compact }) {
                return msg.take(200)
            }
        }
        return null
    }

    /** frameUrls 由页面脚本只收取可见 iframe 的地址；只认确实提供验证码服务的跨站框架。 */
    fun captchaFrame(frame: String, page: String): Boolean {
        val uri = runCatching { URI(frame) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        val pageHost = runCatching { URI(page).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        if (host == pageHost) return false
        fun under(domain: String) = host == domain || host.endsWith(".$domain")
        val path = uri.path.orEmpty().lowercase()
        return captchaHosts.any(::under) ||
            ((under("google.com") || under("gstatic.com")) && path.startsWith("/recaptcha")) ||
            host.split('.').any { it == "captcha" || it.startsWith("captcha-") || it.endsWith("-captcha") }
    }
}
