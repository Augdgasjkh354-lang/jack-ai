package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 节点的加密强不强：手机到节点这一段走在外面的网络上（公共 Wi-Fi、运营商），靠节点协议加密。
 * 不加密的（没开 TLS 的 VLESS、security=none 的 VMess、明文 SOCKS/HTTP）和弱的（老式 Shadowsocks 算法、
 * 跳过证书校验、老式 VMess 认证）在「自动选最快」里不选，手动选时提醒。
 */
object NodeSecurity {
    enum class Level(val label: String) { STRONG("加密强"), WEAK("加密弱"), NONE("不加密") }
    data class Rating(val level: Level, val why: String = "")

    /** Shadowsocks 里算得上安全的算法（AEAD 和 2022 系列）。 */
    private val SS_STRONG = setOf("aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305")

    fun of(node: ProxyNode): Rating = runCatching { of(Json.parseToJsonElement(node.outbound) as JsonObject) }.getOrDefault(Rating(Level.WEAK, "看不出用的什么加密"))

    fun of(o: JsonObject): Rating {
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
        val tls = o["tls"] as? JsonObject
        val tlsOn = tls != null && tls.s("enabled") != "false"
        val insecure = tlsOn && tls!!.s("insecure") == "true"
        val reality = tlsOn && (tls!!["reality"] as? JsonObject)?.s("enabled") == "true"
        val skipCert = Rating(Level.WEAK, "跳过了证书校验，可能被人冒充节点截获")
        return when (o.s("type")) {
            "shadowsocks" -> {
                val m = o.s("method").lowercase()
                when {
                    m == "none" || m == "plain" -> Rating(Level.NONE, "Shadowsocks 没设加密算法")
                    m in SS_STRONG -> Rating(Level.STRONG)
                    else -> Rating(Level.WEAK, "Shadowsocks 用的是老算法 $m，已经不安全")
                }
            }
            "vmess" -> when {
                insecure -> skipCert
                tlsOn -> Rating(Level.STRONG)
                o.s("security").lowercase() in setOf("none", "zero") -> Rating(Level.NONE, "VMess 关了加密，也没开 TLS")
                (o.s("alter_id").toIntOrNull() ?: 0) > 0 -> Rating(Level.WEAK, "VMess 用的是老式认证（alterId 不为 0）")
                else -> Rating(Level.STRONG)
            }
            // VLESS 自己不加密，全靠 TLS / Reality
            "vless" -> when {
                reality -> Rating(Level.STRONG)
                insecure -> skipCert
                tlsOn -> Rating(Level.STRONG)
                else -> Rating(Level.NONE, "VLESS 没开 TLS，传的是明文")
            }
            "trojan", "hysteria2", "hysteria", "tuic", "anytls", "shadowtls" -> when {
                insecure -> skipCert
                else -> Rating(Level.STRONG)
            }
            "socks", "http" -> if (tlsOn && !insecure) Rating(Level.STRONG) else if (insecure) skipCert else Rating(Level.NONE, "${o.s("type").uppercase()} 代理是明文的")
            "wireguard" -> Rating(Level.STRONG)
            else -> Rating(Level.WEAK, "认不出 ${o.s("type")} 的加密方式")
        }
    }

    /** 「自动选最快」能用的节点序号：只要加密强的；一个强的都没有就照旧全用（界面上会提醒）。 */
    fun autoPool(nodes: List<ProxyNode>): List<Int> =
        nodes.indices.filter { of(nodes[it]).level == Level.STRONG }.ifEmpty { nodes.indices.toList() }
}
