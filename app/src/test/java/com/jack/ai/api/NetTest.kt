package com.jack.ai.api

import com.jack.ai.data.ProxyCore
import com.jack.ai.data.ProxyNodes
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.Base64

class NetTest {
    private val node = ProxyNodes.parse("ss://" + Base64.getEncoder().encodeToString("aes-128-gcm:x".toByteArray()) + "@127.0.0.1:1#n")

    @After fun reset() { Net.setConfig(Net.Config()); ProxyCore.stop() }

    @Test
    fun domesticDirectForeignProxied() {
        val on = Net.Config(enabled = true, nodes = node)
        assertFalse(Net.shouldProxy("api.deepseek.com", on))
        assertFalse(Net.shouldProxy("kyfw.12306.cn", on))
        assertFalse(Net.shouldProxy("mcp.amap.com", on))
        assertFalse(Net.shouldProxy("127.0.0.1", on))
        assertTrue(Net.shouldProxy("api.githubcopilot.com", on))
        assertTrue(Net.shouldProxy("openrouter.ai", on))
        // 没开代理：一律不走
        assertFalse(Net.shouldProxy("openrouter.ai", Net.Config()))
        // 像 notamap.com 这种不能因为后缀碰巧对上就直连
        assertTrue(Net.shouldProxy("notamap.com", on))
    }

    @Test
    fun forcedProxyWithProxyOffFailsInsteadOfGoingDirect() {
        Net.setConfig(Net.Config())
        val p = Net.selectorFor("proxy").select(URI("https://example.com/")).single()
        assertEquals(Proxy.Type.HTTP, p.type())
        assertEquals(9, (p.address() as java.net.InetSocketAddress).port)
        // 直连和自动（没开）都跟随系统（这台机器自己可能设了系统代理）
        val uri = URI("https://example.com/")
        assertEquals(java.net.ProxySelector.getDefault().select(uri), Net.selectorFor("direct").select(uri))
        assertEquals(java.net.ProxySelector.getDefault().select(uri), Net.selectorFor("auto").select(uri))
    }

    /** 真内核（设了 SINGBOX_BIN 才跑）：经 Jack 的分流，国外地址走节点，内核不在时失败而不是直连。 */
    @Test
    fun routedThroughRealCore() {
        val bin = System.getenv("SINGBOX_BIN")?.let(::File)
        assumeTrue(bin?.exists() == true)
        val dir = Files.createTempDirectory("net").toFile()
        val site = ServerSocket(0)
        Thread {
            while (!site.isClosed) {
                val c = runCatching { site.accept() }.getOrNull() ?: break
                c.use {
                    val r = it.getInputStream().bufferedReader()
                    while (r.readLine().orEmpty().isNotEmpty()) {}
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                }
            }
        }.apply { isDaemon = true }.start()
        val ssPort = ServerSocket(0).use { it.localPort }
        val cfg = File(dir, "s.json").apply {
            writeText("""{"inbounds":[{"type":"shadowsocks","listen":"127.0.0.1","listen_port":$ssPort,"method":"aes-128-gcm","password":"x"}],"outbounds":[{"type":"direct"}]}""")
        }
        val server = ProcessBuilder(bin!!.path, "run", "-c", cfg.path).redirectErrorStream(true).start()
        try {
            Thread.sleep(800)
            val nodes = ProxyNodes.parse("ss://" + Base64.getEncoder().encodeToString("aes-128-gcm:x".toByteArray()) + "@127.0.0.1:$ssPort#n")
            Net.setPaths(bin, File(dir, "core"))
            Net.setConfig(Net.Config(enabled = true, nodes = nodes))
            // 指定走代理的客户端（连接器选了「走代理」时就是这样）
            val client = OkHttpClient.Builder().proxySelector(Net.selectorFor("proxy")).proxyAuthenticator(Net.authenticator).build()
            // 127.0.0.2 也指向本机，但不在“不走代理”的名单里，所以会经过内核和节点
            val body = client.newCall(Request.Builder().url("http://127.0.0.2:${site.localPort}/").build()).execute().use { it.body!!.string() }
            assertEquals("ok", body)
            assertTrue(ProxyCore.isRunning)
            // 内核停掉、节点又起不来时（这里把节点换成没人听的端口），请求失败，不会直连
            ProxyCore.stop()
            Net.setConfig(Net.Config(enabled = true, nodes = node))
            Net.setPaths(File(dir, "没有这个内核"), File(dir, "core"))
            val failed = runCatching { client.newCall(Request.Builder().url("http://127.0.0.2:${site.localPort}/").build()).execute().close() }.isFailure
            assertTrue(failed)
        } finally {
            server.destroy(); site.close()
        }
    }
}
