package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProxyNodesTest {
    private fun o(n: ProxyNode) = Json.parseToJsonElement(n.outbound).jsonObject
    private fun JsonObject.s(path: String): String = path.split('.').fold(this as Any?) { acc, k -> (acc as? JsonObject)?.get(k)?.let { if (it is JsonObject) it else it.jsonPrimitive.content } }.toString()
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    val samples = listOf(
        "ss://" + b64("aes-256-gcm:pass1") + "@1.2.3.4:8388#%E9%A6%99%E6%B8%AF%2001",
        "ss://chacha20-ietf-poly1305:p%40ss@[2001:db8::1]:443#v6",
        "vmess://" + b64("""{"v":"2","ps":"日本","add":"jp.example.com","port":"443","id":"11111111-2222-3333-4444-555555555555","aid":"0","net":"ws","host":"cdn.example.com","path":"/ray","tls":"tls","sni":"cdn.example.com"}"""),
        "vless://11111111-2222-3333-4444-555555555555@us.example.com:443?security=reality&sni=www.apple.com&pbk=XrPXYcCWPqzTZWnjZ7o84o3DBotOwYR-ukACjoBX-jM&sid=ab12&flow=xtls-rprx-vision&fp=chrome&type=tcp#美国",
        "trojan://secret@tw.example.com:443?sni=tw.example.com&type=grpc&serviceName=gun#台湾",
        "hysteria2://pw@sg.example.com:8443?sni=sg.example.com&obfs=salamander&obfs-password=xx&insecure=1#新加坡",
        "tuic://11111111-2222-3333-4444-555555555555:pw@kr.example.com:443?congestion_control=bbr&alpn=h3&sni=kr.example.com#韩国",
    )

    @Test
    fun parsesEachLinkType() {
        val nodes = ProxyNodes.parse(samples.joinToString("\n"))
        assertEquals(listOf("shadowsocks", "shadowsocks", "vmess", "vless", "trojan", "hysteria2", "tuic"), nodes.map { it.type })
        assertEquals("香港 01", nodes[0].name)
        assertEquals("pass1", o(nodes[0]).s("password"))
        assertEquals("2001:db8::1", o(nodes[1]).s("server"))
        assertEquals("p@ss", o(nodes[1]).s("password"))
        assertEquals("ws", o(nodes[2]).s("transport.type"))
        assertEquals("cdn.example.com", o(nodes[2]).s("tls.server_name"))
        assertEquals("XrPXYcCWPqzTZWnjZ7o84o3DBotOwYR-ukACjoBX-jM", o(nodes[3]).s("tls.reality.public_key"))
        assertEquals("chrome", o(nodes[3]).s("tls.utls.fingerprint"))
        assertEquals("gun", o(nodes[4]).s("transport.service_name"))
        assertEquals("salamander", o(nodes[5]).s("obfs.type"))
        assertEquals("true", o(nodes[5]).s("tls.insecure"))
        assertEquals("pw", o(nodes[6]).s("password"))
    }

    @Test
    fun base64SubscriptionAndDuplicateNames() {
        val sub = b64(listOf(samples[0], samples[0], "这不是节点").joinToString("\n"))
        val nodes = ProxyNodes.parse(sub)
        assertEquals(listOf("香港 01", "香港 01 (2)"), nodes.map { it.name })
    }

    @Test
    fun clashYaml() {
        val yaml = """
            port: 7890
            proxies:
              - {name: 香港A, type: ss, server: hk.example.com, port: 8388, cipher: aes-128-gcm, password: abc}
              - name: 日本B
                type: vmess
                server: jp.example.com
                port: 443
                uuid: 11111111-2222-3333-4444-555555555555
                alterId: 0
                cipher: auto
                tls: true
                network: ws
                ws-opts: {path: /ws, headers: {Host: cdn.example.com}}
              - {name: 美国C, type: hysteria2, server: us.example.com, port: 443, password: pw, sni: us.example.com}
              - {name: 不支持, type: snell, server: x, port: 1}
        """.trimIndent()
        val nodes = ProxyNodes.parse(yaml)
        assertEquals(listOf("香港A", "日本B", "美国C"), nodes.map { it.name })
        assertEquals("/ws", o(nodes[1]).s("transport.path"))
        assertEquals("cdn.example.com", o(nodes[1]).s("transport.headers.Host"))
    }

    @Test
    fun configPicksNodeOrAuto() {
        val nodes = ProxyNodes.parse(samples.take(3).joinToString("\n"))
        assertEquals("auto", ProxyCore.tagFor(nodes, ""))
        assertEquals("n2", ProxyCore.tagFor(nodes, "日本"))
        val cfg = Json.parseToJsonElement(ProxyCore.config(nodes, "", 12345, "u", "p")).jsonObject
        assertTrue(cfg.toString().contains("\"listen\":\"127.0.0.1\""))
        assertTrue(cfg.toString().contains("\"username\":\"u\""))
    }
}
