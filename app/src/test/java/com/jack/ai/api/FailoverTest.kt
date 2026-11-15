package com.jack.ai.api

import com.jack.ai.data.Endpoint
import com.jack.ai.data.Provider
import com.jack.ai.data.ProviderModel
import com.jack.ai.data.Settings
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.customModelKey
import com.jack.ai.data.endpoint
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class FailoverTest {
    @Before fun clear() = Failover.reset()
    @After fun restore() { Failover.reset(); Failover.now = System::currentTimeMillis }

    private val ok = "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}\n\n" +
        "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n\ndata: [DONE]\n\n"

    @Test fun settingsAttachUpToTwoBackupsSkippingTheMainModelAndKeylessOnes() {
        val p = Provider(id = "p", name = "P", baseUrl = "http://x", apiKey = "k", models = listOf(ProviderModel("a"), ProviderModel("b"), ProviderModel("c")))
        val noKey = Provider(id = "q", name = "Q", baseUrl = "http://y", apiKey = "", models = listOf(ProviderModel("z")))
        val s = Settings(apiKey = "", model = customModelKey("p", "a"), providers = listOf(p, noKey),
            fallbackModels = listOf(customModelKey("p", "a"), customModelKey("q", "z"), customModelKey("p", "b"), customModelKey("p", "c")))
        val ep = s.endpoint()!!
        assertEquals(listOf("b", "c"), ep.fallbacks.map { it.model })
        assertTrue(ep.fallbacks.all { it.fallbacks.isEmpty() })
        assertTrue(Settings(apiKey = "k").endpoint()!!.fallbacks.isEmpty())
    }

    @Test fun onlyServerAndNetworkTroubleIsWorthSwitching() {
        assertTrue(Failover.worthSwitching(ApiException("忙", 503)))
        assertTrue(Failover.worthSwitching(ApiException("余额", 402)))
        assertTrue(Failover.worthSwitching(ApiException("限流", 429)))
        assertTrue(Failover.worthSwitching(java.io.IOException("断了")))
        assertFalse(Failover.worthSwitching(ApiException("太长", 400)))
        assertFalse(Failover.worthSwitching(kotlinx.coroutines.CancellationException("停")))
    }

    @Test fun switchesToTheBackupBeforeAnyTextAndSticksForTenMinutes() {
        val mainHits = AtomicInteger()
        val main = server(mainHits) { 503 to "{\"error\":{\"message\":\"busy\"}}" }
        val backupHits = AtomicInteger()
        val backup = server(backupHits) { 200 to ok }
        try {
            var t = 1_000L
            Failover.now = { t }
            val ep = ep(main.localPort, "main").copy(fallbacks = listOf(ep(backup.localPort, "backup")))
            val events = run(ep)
            assertEquals(StreamEvent.Text("", "好"), events.first())
            val usage = events.filterIsInstance<StreamEvent.Usage>().single()
            assertEquals("backup", usage.endpoint?.key)
            assertEquals(1, mainHits.get())
            // 粘住：下一次直接问备用，不再撞主模型
            run(ep)
            assertEquals(1, mainHits.get())
            assertEquals(2, backupHits.get())
            // 过了 10 分钟回去试主模型
            t += Failover.STICKY_MS + 1
            run(ep)
            assertEquals(2, mainHits.get())
        } finally { main.close(); backup.close() }
    }

    @Test fun mainModelUsageHasNoEndpointAndBadRequestsDoNotSwitch() {
        val good = server(AtomicInteger()) { 200 to ok }
        val bad = server(AtomicInteger()) { 400 to "{\"error\":{\"message\":\"too long\"}}" }
        val backupHits = AtomicInteger()
        val backup = server(backupHits) { 200 to ok }
        try {
            val usage = run(ep(good.localPort, "main").copy(fallbacks = listOf(ep(backup.localPort, "backup"))))
                .filterIsInstance<StreamEvent.Usage>().single()
            assertNull(usage.endpoint)
            assertThrows(ApiException::class.java) { run(ep(bad.localPort, "main2").copy(fallbacks = listOf(ep(backup.localPort, "backup")))) }
            assertEquals(0, backupHits.get())
        } finally { good.close(); bad.close(); backup.close() }
    }

    @Test fun lastErrorIsReportedWhenEveryModelFails() {
        val a = server(AtomicInteger()) { 503 to "{}" }
        val b = server(AtomicInteger()) { 402 to "{\"error\":{\"message\":\"no money\"}}" }
        try {
            val e = assertThrows(ApiException::class.java) { run(ep(a.localPort, "a").copy(fallbacks = listOf(ep(b.localPort, "b")))) }
            assertEquals(402, e.code)
        } finally { a.close(); b.close() }
    }

    private fun ep(port: Int, key: String) =
        Endpoint("http://127.0.0.1:$port", "k", key, deepseek = false, vision = false, reasoning = false, key = key)

    private fun run(ep: Endpoint) = runBlocking { DeepSeekClient().stream(ep, emptyList(), ThinkLevel.OFF, 1f).toList() }

    /** 一个一直接请求的假服务：每个请求按 reply 回状态码和正文。 */
    private fun server(hits: AtomicInteger, reply: () -> Pair<Int, String>): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use {
                        val input = it.getInputStream().bufferedReader()
                        var len = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) len = line.substringAfter(':').trim().toInt()
                        }
                        val buf = CharArray(1024); var left = len
                        while (left > 0) { val n = input.read(buf, 0, minOf(buf.size, left)); if (n < 0) break; left -= n }
                        hits.incrementAndGet()
                        val (code, body) = reply()
                        val bytes = body.toByteArray()
                        val type = if (code == 200) "text/event-stream" else "application/json"
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                }
            }
        }
        return server
    }
}
