package com.jack.ai.data

import com.jack.ai.api.Net
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 用真的 sing-box 内核跑一遍（设了 SINGBOX_BIN 才跑，指向电脑版的同款内核）：
 * 生成的配置能通过内核自己的检查；本机起一个 Shadowsocks 服务器当“机场”，Jack 的请求真的从代理走过去。
 */
class ProxyCoreLiveTest {
    private val bin = System.getenv("SINGBOX_BIN")?.let(::File)

    @Test
    fun generatedConfigsPassCoreCheck() {
        assumeTrue(bin?.exists() == true)
        val nodes = ProxyNodes.parse(ProxyNodesTest().samples.joinToString("\n"))
        val dir = Files.createTempDirectory("sb").toFile()
        // 单跳（自动、手动选）和双跳（先经过 nodes[0]）都要过得了内核检查
        for ((selected, entry) in listOf("" to "", nodes[3].name to "", nodes[3].name to nodes[0].name, "" to nodes[0].name)) {
            val cfg = File(dir, "c.json").apply { writeText(ProxyCore.config(nodes, selected, 20000, "u", "p", entry = entry)) }
            val p = ProcessBuilder(bin!!.path, "check", "-c", cfg.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS))
            assertEquals("内核说配置不对：$out", 0, p.exitValue())
        }
    }

    @Test
    fun requestsReallyGoThroughTheNode() {
        assumeTrue(bin?.exists() == true)
        val dir = Files.createTempDirectory("sb").toFile()
        // 1. 本机的“目标网站”：记下是谁连过来的
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
        // 2. 本机的“机场”：一个 Shadowsocks 服务器
        val ssPort = ServerSocket(0).use { it.localPort }
        val serverCfg = File(dir, "server.json").apply {
            writeText("""{"log":{"level":"warn"},"inbounds":[{"type":"shadowsocks","listen":"127.0.0.1","listen_port":$ssPort,"method":"aes-128-gcm","password":"jack-test"}],"outbounds":[{"type":"direct"}]}""")
        }
        val server = ProcessBuilder(bin!!.path, "run", "-c", serverCfg.path).redirectErrorStream(true).start()
        try {
            Thread.sleep(800)
            // 3. Jack 这边：订阅里就这一个节点
            val link = "ss://" + Base64.getEncoder().encodeToString("aes-128-gcm:jack-test".toByteArray()) + "@127.0.0.1:$ssPort#测试节点"
            val nodes = ProxyNodes.parse(Base64.getEncoder().encodeToString(link.toByteArray()))
            assertTrue(ProxyCore.lastError ?: "", ProxyCore.ensure(bin, File(dir, "client"), nodes, ""))
            assertFalse("节点密码不该留在磁盘上", File(dir, "client/config.json").exists())
            val proxied = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ProxyCore.port)))
                .proxyAuthenticator(Net.authenticator)
                .build()
            val body = proxied.newCall(Request.Builder().url("http://127.0.0.1:${site.localPort}/").build()).execute().use { it.body!!.string() }
            assertEquals("ok", body)
            // 没口令的（比如手机上别的 App）用不了这个端口
            val noAuth = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ProxyCore.port))).build()
            val code = noAuth.newCall(Request.Builder().url("http://127.0.0.1:${site.localPort}/").build()).execute().use { it.code }
            assertEquals(407, code)
            // 停掉内核后，同样的请求失败，不会偷偷直连
            ProxyCore.stop()
            val failed = runCatching { proxied.newCall(Request.Builder().url("http://127.0.0.1:${site.localPort}/").build()).execute().close() }.isFailure
            assertTrue(failed)
        } finally {
            ProxyCore.stop()
            server.destroy()
            site.close()
        }
    }
}
