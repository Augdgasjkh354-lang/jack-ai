package com.jack.ai.data

import com.jack.ai.api.SecureDns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NodeSecurityTest {
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    private fun level(link: String) = NodeSecurity.of(ProxyNodes.parseUri(link)!!).level

    @Test
    fun ratesCommonLinks() {
        assertEquals(NodeSecurity.Level.STRONG, level("ss://" + b64("aes-256-gcm:pw") + "@1.2.3.4:8388#a"))
        assertEquals(NodeSecurity.Level.STRONG, level("ss://" + b64("2022-blake3-aes-128-gcm:a2V5") + "@1.2.3.4:8388#a"))
        assertEquals(NodeSecurity.Level.WEAK, level("ss://" + b64("aes-256-cfb:pw") + "@1.2.3.4:8388#a"))
        assertEquals(NodeSecurity.Level.STRONG, level("trojan://pw@x.com:443?sni=x.com#t"))
        assertEquals(NodeSecurity.Level.WEAK, level("trojan://pw@x.com:443?sni=x.com&allowInsecure=1#t"))
        assertEquals(NodeSecurity.Level.NONE, level("vless://11111111-2222-3333-4444-555555555555@x.com:80?type=ws&path=/a#v"))
        assertEquals(NodeSecurity.Level.STRONG, level("vless://11111111-2222-3333-4444-555555555555@x.com:443?security=tls&sni=x.com#v"))
        assertEquals(NodeSecurity.Level.STRONG, level("vless://11111111-2222-3333-4444-555555555555@x.com:443?security=reality&sni=x.com&pbk=abc&sid=01#v"))
        assertEquals(NodeSecurity.Level.STRONG, level("hysteria2://pw@x.com:443?sni=x.com#h"))
        val vmess = { extra: String -> "vmess://" + b64("""{"v":"2","ps":"m","add":"x.com","port":"443","id":"11111111-2222-3333-4444-555555555555"$extra}""") }
        assertEquals(NodeSecurity.Level.STRONG, level(vmess(""","aid":"0","scy":"auto"""")))
        assertEquals(NodeSecurity.Level.NONE, level(vmess(""","aid":"0","scy":"none"""")))
        assertEquals(NodeSecurity.Level.WEAK, level(vmess(""","aid":"64"""")))
        assertEquals(NodeSecurity.Level.STRONG, level(vmess(""","aid":"0","scy":"none","tls":"tls","sni":"x.com"""")))
    }

    @Test
    fun autoPicksOnlyStrongNodes() {
        val strong = ProxyNodes.parseUri("trojan://pw@x.com:443?sni=x.com#strong")!!
        val plain = ProxyNodes.parseUri("vless://11111111-2222-3333-4444-555555555555@y.com:80#plain")!!
        assertEquals(listOf(1), NodeSecurity.autoPool(listOf(plain, strong)))
        // 一个强的都没有：照旧全用（界面上提醒）
        assertEquals(listOf(0), NodeSecurity.autoPool(listOf(plain)))
        val cfg = Json.parseToJsonElement(ProxyCore.config(listOf(plain, strong), "", 1080, "u", "p")).jsonObject
        val auto = cfg["outbounds"]!!.jsonArray.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "auto" }.jsonObject
        assertEquals(listOf("n1"), auto["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content })
        // 解析节点域名用加密 DNS
        val dns = cfg["dns"]!!.jsonObject["servers"]!!.jsonArray[0].jsonObject
        assertEquals("https", dns["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun localNamesSkipEncryptedDns() {
        listOf("localhost", "127.0.0.1", "192.168.1.5", "::1", "nas", "printer.local", "router.lan").forEach { assertTrue(it, SecureDns.skip(it)) }
        listOf("api.deepseek.com", "github.com").forEach { assertFalse(it, SecureDns.skip(it)) }
    }
}
