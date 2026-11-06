package com.jack.ai.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class McpLoginTest {
    /** 只回一次的本机小服务：记下收到的请求正文。 */
    private fun oneShot(body: String, got: StringBuilder): ServerSocket {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            server.accept().use { s ->
                val input = s.getInputStream().bufferedReader()
                var len = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) len = line.substringAfter(':').trim().toInt()
                }
                got.append(CharArray(len).also { input.read(it, 0, len) })
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
            }
        }
        return server
    }

    @Test fun refreshesStaleTokenAndSavesIt() = runBlocking {
        val got = StringBuilder()
        val server = oneShot("""{"access_token":"new","expires_in":3600}""", got)
        val stale = """{"accessToken":"old","refreshToken":"r1","expiresAtMs":1,"clientId":"c","tokenEndpoint":"http://localhost:${server.localPort}/token"}"""
        var saved: McpServer? = null
        McpLogin.save = { saved = it }
        try {
            val s = McpLogin.fresh(McpServer(name = "n", url = "http://localhost/mcp", oauth = stale, route = "direct"))
            val t = McpLogin.tokens(s)!!
            assertEquals("new", t.accessToken)
            assertEquals("r1", t.refreshToken)  // 服务没给新的就沿用旧的
            assertEquals(s, saved)
            assertTrue(got.toString(), got.contains("grant_type=refresh_token"))
        } finally { McpLogin.save = null; server.close() }
    }
}
