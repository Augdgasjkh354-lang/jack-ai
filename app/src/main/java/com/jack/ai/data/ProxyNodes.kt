package com.jack.ai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** 一个代理节点：名字 + 写好的 sing-box 出站配置（JSON）。节点里有密码，只存在手机上，不进备份。 */
@Serializable
data class ProxyNode(val name: String, val type: String, val outbound: String)

/**
 * 把订阅内容、节点链接（ss:// vmess:// vless:// trojan:// hysteria2:// tuic://）、Clash 的 proxies 配置，
 * 转成 sing-box 认得的出站配置。认不出的节点跳过，不影响其他的。
 */
object ProxyNodes {
    private val json = Json { ignoreUnknownKeys = true }

    /** 订阅返回的内容：可能是 base64 编码的一行一个链接，也可能直接是链接，也可能是 Clash 的 YAML。 */
    fun parse(content: String): List<ProxyNode> {
        val text = content.trim().removePrefix("﻿")
        if (Regex("(?m)^proxies\\s*:").containsMatchIn(text)) return parseClash(text)
        val lines = (if ("://" in text) text else decodeBase64(text) ?: text).lines()
        return lines.map { it.trim() }.filter { "://" in it }.mapNotNull { runCatching { parseUri(it) }.getOrNull() }.distinctNames()
    }

    /** 名字重复时加编号，sing-box 要求 tag 不重复。 */
    private fun List<ProxyNode>.distinctNames(): List<ProxyNode> {
        val seen = HashMap<String, Int>()
        return map { n ->
            val c = (seen[n.name] ?: 0) + 1
            seen[n.name] = c
            if (c == 1) n else n.copy(name = "${n.name} ($c)")
        }
    }

    fun decodeBase64(s: String): String? {
        val t = s.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (t.isEmpty()) return null
        val padded = t + "=".repeat((4 - t.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded.replace('-', '+').replace('_', '/')), Charsets.UTF_8) }.getOrNull()
    }

    private fun dec(s: String?) = s?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.orEmpty()

    private fun query(q: String?): Map<String, String> =
        q.orEmpty().split('&').filter { '=' in it }.associate { it.substringBefore('=').lowercase() to dec(it.substringAfter('=')) }

    /** 一个节点链接 → 节点。 */
    fun parseUri(uri: String): ProxyNode? {
        val scheme = uri.substringBefore("://").lowercase()
        return when (scheme) {
            "ss" -> ss(uri)
            "vmess" -> vmess(uri)
            "vless" -> generic(uri, "vless")
            "trojan" -> generic(uri, "trojan")
            "hysteria2", "hy2" -> generic(uri, "hysteria2")
            "tuic" -> generic(uri, "tuic")
            else -> null
        }
    }

    private fun node(name: String, type: String, body: JsonObject) = ProxyNode(name.ifBlank { type }.take(60), type, body.toString())

    /** ss://base64(method:password)@host:port#名字，或者老格式 ss://base64(method:password@host:port)#名字 */
    private fun ss(uri: String): ProxyNode? {
        val name = dec(uri.substringAfter('#', "")).trim()
        var rest = uri.removePrefix("ss://").substringBefore('#')
        val plugin = query(rest.substringAfter('?', "")).get("plugin")
        rest = rest.substringBefore('?').trimEnd('/')
        val (userInfo, hostPort) = if ('@' in rest) {
            val ui = rest.substringBeforeLast('@')
            (decodeBase64(ui)?.takeIf { ':' in it } ?: dec(ui)) to rest.substringAfterLast('@')
        } else {
            val all = decodeBase64(rest) ?: return null
            all.substringBeforeLast('@') to all.substringAfterLast('@')
        }
        val (host, port) = hostPort(hostPort) ?: return null
        return node(name.ifBlank { host }, "shadowsocks", buildJsonObject {
            put("type", "shadowsocks"); put("server", host); put("server_port", port)
            put("method", userInfo.substringBefore(':')); put("password", userInfo.substringAfter(':'))
            plugin?.let { p ->
                val parts = p.split(';')
                val pName = parts.first().let { if (it == "simple-obfs") "obfs-local" else it }
                put("plugin", pName)
                put("plugin_opts", parts.drop(1).joinToString(";"))
            }
        })
    }

    private fun hostPort(s: String): Pair<String, Int>? {
        val hp = s.trim()
        val host = if (hp.startsWith("[")) hp.substringAfter('[').substringBefore(']') else hp.substringBeforeLast(':')
        val port = hp.substringAfterLast(':').toIntOrNull() ?: return null
        return host to port
    }

    /** vmess://base64({"add","port","id","aid","net","type","host","path","tls","sni","ps"...}) */
    private fun vmess(uri: String): ProxyNode? {
        val o = json.parseToJsonElement(decodeBase64(uri.removePrefix("vmess://")) ?: return null).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
        val server = s("add")
        val port = s("port").toIntOrNull() ?: return null
        val tls = s("tls") == "tls"
        return node(s("ps").ifBlank { server }, "vmess", buildJsonObject {
            put("type", "vmess"); put("server", server); put("server_port", port)
            put("uuid", s("id")); put("security", s("scy").ifBlank { "auto" }); put("alter_id", s("aid").toIntOrNull() ?: 0)
            if (tls) put("tls", tls(s("sni").ifBlank { s("host") }.ifBlank { server }, insecure = false, alpn = s("alpn"), fp = s("fp")))
            transport(s("net"), s("host"), s("path"), s("path"), s("type"))?.let { put("transport", it) }
        })
    }

    /** vless:// trojan:// hysteria2:// tuic:// 都是 用户@主机:端口?参数#名字 的格式。 */
    private fun generic(uri: String, type: String): ProxyNode? {
        val u = URI(uri.substringBefore('#').replace(" ", "%20"))
        val name = dec(uri.substringAfter('#', "")).trim()
        val host = u.host ?: return null
        val port = if (u.port > 0) u.port else 443
        val q = query(u.rawQuery)
        val user = dec(u.rawUserInfo)
        val sni = q["sni"] ?: q["peer"] ?: host
        val insecure = q["allowinsecure"] == "1" || q["insecure"] == "1" || q["allowinsecure"] == "true"
        return node(name.ifBlank { host }, type, buildJsonObject {
            put("type", type); put("server", host); put("server_port", port)
            when (type) {
                "vless" -> {
                    put("uuid", user)
                    q["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
                    val security = q["security"].orEmpty()
                    if (security == "tls" || security == "reality") {
                        put("tls", tls(sni, insecure, q["alpn"].orEmpty(), q["fp"].orEmpty(),
                            reality = if (security == "reality") q["pbk"].orEmpty() to q["sid"].orEmpty() else null))
                    }
                    transport(q["type"].orEmpty(), q["host"].orEmpty(), q["path"].orEmpty(), q["servicename"].orEmpty(), q["headertype"].orEmpty())?.let { put("transport", it) }
                }
                "trojan" -> {
                    put("password", user)
                    put("tls", tls(sni, insecure, q["alpn"].orEmpty(), q["fp"].orEmpty()))
                    transport(q["type"].orEmpty(), q["host"].orEmpty(), q["path"].orEmpty(), q["servicename"].orEmpty(), q["headertype"].orEmpty())?.let { put("transport", it) }
                }
                "hysteria2" -> {
                    put("password", user)
                    put("tls", tls(sni, insecure, q["alpn"].orEmpty().ifBlank { "h3" }, ""))
                    q["obfs"]?.takeIf { it.isNotBlank() && it != "none" }?.let { o ->
                        put("obfs", buildJsonObject { put("type", o); put("password", q["obfs-password"].orEmpty()) })
                    }
                }
                "tuic" -> {
                    put("uuid", user.substringBefore(':')); put("password", user.substringAfter(':', ""))
                    put("congestion_control", q["congestion_control"].orEmpty().ifBlank { "bbr" })
                    q["udp_relay_mode"]?.let { put("udp_relay_mode", it) }
                    put("tls", tls(sni, insecure, q["alpn"].orEmpty().ifBlank { "h3" }, ""))
                }
            }
        })
    }

    private fun tls(sni: String, insecure: Boolean, alpn: String, fp: String, reality: Pair<String, String>? = null) = buildJsonObject {
        put("enabled", true)
        put("server_name", sni)
        if (insecure) put("insecure", true)
        alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { put("alpn", JsonArray(it.map(::JsonPrimitive))) }
        // Reality 必须带 uTLS 指纹
        val finger = fp.ifBlank { if (reality != null) "chrome" else "" }
        if (finger.isNotBlank()) put("utls", buildJsonObject { put("enabled", true); put("fingerprint", finger) })
        reality?.let { (pbk, sid) -> put("reality", buildJsonObject { put("enabled", true); put("public_key", pbk); put("short_id", sid) }) }
    }

    /** 传输方式：ws、grpc、http/h2、httpupgrade；tcp 返回 null。 */
    private fun transport(net: String, host: String, path: String, serviceName: String, headerType: String): JsonObject? = when (net.lowercase()) {
        "ws" -> buildJsonObject {
            put("type", "ws")
            if (path.isNotBlank()) put("path", path)
            if (host.isNotBlank()) put("headers", buildJsonObject { put("Host", host) })
        }
        "grpc" -> buildJsonObject { put("type", "grpc"); put("service_name", serviceName.ifBlank { path }) }
        "h2", "http" -> buildJsonObject {
            put("type", "http")
            if (host.isNotBlank()) put("host", JsonArray(host.split(',').map { JsonPrimitive(it.trim()) }))
            if (path.isNotBlank()) put("path", path)
        }
        "httpupgrade" -> buildJsonObject {
            put("type", "httpupgrade")
            if (host.isNotBlank()) put("host", host)
            if (path.isNotBlank()) put("path", path)
        }
        "tcp" -> if (headerType == "http") buildJsonObject {
            put("type", "http")
            if (host.isNotBlank()) put("host", JsonArray(host.split(',').map { JsonPrimitive(it.trim()) }))
            if (path.isNotBlank()) put("path", path)
        } else null
        else -> null
    }

    // ---------- Clash 配置 ----------

    /** Clash 订阅：proxies 列表里每一项是一个节点。 */
    fun parseClash(yaml: String): List<ProxyNode> {
        val root = runCatching { org.yaml.snakeyaml.Yaml().load<Any?>(yaml) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val list = root["proxies"] as? List<*> ?: return emptyList()
        return list.mapNotNull { p -> runCatching { clashNode(p as Map<*, *>) }.getOrNull() }.distinctNames()
    }

    private fun clashNode(p: Map<*, *>): ProxyNode? {
        fun s(k: String) = p[k]?.toString().orEmpty()
        fun b(k: String) = p[k] == true || p[k]?.toString() == "true"
        fun m(k: String) = p[k] as? Map<*, *>
        val name = s("name")
        val server = s("server")
        val port = s("port").toIntOrNull() ?: return null
        val sni = s("sni").ifBlank { s("servername") }.ifBlank { server }
        val insecure = b("skip-cert-verify")
        val alpn = (p["alpn"] as? List<*>)?.joinToString(",").orEmpty()
        val fp = s("client-fingerprint")
        val net = s("network")
        val ws = m("ws-opts")
        val grpc = m("grpc-opts")
        val h2 = m("h2-opts")
        val wsHost = (ws?.get("headers") as? Map<*, *>)?.get("Host")?.toString().orEmpty()
        val tr = transport(net, if (net == "h2") (h2?.get("host") as? List<*>)?.joinToString(",").orEmpty() else wsHost,
            ws?.get("path")?.toString() ?: h2?.get("path")?.toString().orEmpty(),
            grpc?.get("grpc-service-name")?.toString().orEmpty(), "")
        val type = when (s("type")) { "ss" -> "shadowsocks"; "hy2" -> "hysteria2"; else -> s("type") }
        return node(name.ifBlank { server }, type, buildJsonObject {
            put("type", type); put("server", server); put("server_port", port)
            when (type) {
                "shadowsocks" -> {
                    put("method", s("cipher")); put("password", s("password"))
                    if (s("plugin") == "obfs") {
                        val o = m("plugin-opts")
                        put("plugin", "obfs-local")
                        put("plugin_opts", "obfs=${o?.get("mode") ?: "http"};obfs-host=${o?.get("host") ?: ""}")
                    }
                }
                "vmess" -> {
                    put("uuid", s("uuid")); put("security", s("cipher").ifBlank { "auto" }); put("alter_id", s("alterId").toIntOrNull() ?: 0)
                    if (b("tls")) put("tls", tls(sni, insecure, alpn, fp))
                    tr?.let { put("transport", it) }
                }
                "vless" -> {
                    put("uuid", s("uuid"))
                    s("flow").takeIf { it.isNotBlank() }?.let { put("flow", it) }
                    val reality = m("reality-opts")
                    if (b("tls") || reality != null) put("tls", tls(sni, insecure, alpn, fp,
                        reality = reality?.let { it["public-key"]?.toString().orEmpty() to it["short-id"]?.toString().orEmpty() }))
                    tr?.let { put("transport", it) }
                }
                "trojan" -> {
                    put("password", s("password"))
                    put("tls", tls(sni, insecure, alpn, fp))
                    tr?.let { put("transport", it) }
                }
                "hysteria2" -> {
                    put("password", s("password"))
                    put("tls", tls(sni, insecure, alpn.ifBlank { "h3" }, ""))
                    s("obfs").takeIf { it.isNotBlank() }?.let { o -> put("obfs", buildJsonObject { put("type", o); put("password", s("obfs-password")) }) }
                }
                "tuic" -> {
                    put("uuid", s("uuid")); put("password", s("password"))
                    put("congestion_control", s("congestion-controller").ifBlank { "bbr" })
                    put("tls", tls(sni, insecure, alpn.ifBlank { "h3" }, ""))
                }
                else -> return null
            }
        })
    }

    /** 节点的出站配置，加上 tag。 */
    fun outbound(n: ProxyNode, tag: String): JsonObject =
        JsonObject(json.parseToJsonElement(n.outbound).jsonObject + ("tag" to JsonPrimitive(tag)))
}
