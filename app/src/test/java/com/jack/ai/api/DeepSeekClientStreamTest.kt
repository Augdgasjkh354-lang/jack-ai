package com.jack.ai.api

import com.jack.ai.data.Endpoint
import com.jack.ai.data.ThinkLevel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class DeepSeekClientStreamTest {
    @Test
    fun eofWithoutDoneOrFinishReasonFailsAsInterrupted() {
        val error = assertThrows(java.io.IOException::class.java) {
            collect(
                """
                data: {"choices":[{"delta":{"content":"半截"}}]}

                """.trimIndent()
            )
        }

        assertTrue(error.message.orEmpty().contains("回答中途断开"))
    }

    @Test
    fun choiceErrorIsReported() {
        val error = assertThrows(ApiException::class.java) {
            collect(
                """
                data: {"choices":[{"delta":{"content":"一半"}}]}

                data: {"choices":[{"error":{"message":"上游模型失败","code":"provider_failed"},"finish_reason":"error"}]}

                """.trimIndent()
            )
        }

        assertTrue(error.message.orEmpty().contains("上游模型失败"))
        assertEquals("provider_failed", error.serviceCode)
    }

    @Test
    fun nonSseJsonErrorIsReported() {
        val error = assertThrows(ApiException::class.java) {
            collect(
                """{"error":{"message":"余额不足","code":"insufficient_quota"}}""",
                contentType = "application/json",
            )
        }

        assertTrue(error.message.orEmpty().contains("余额不足"))
        assertEquals("insufficient_quota", error.serviceCode)
    }

    @Test
    fun finishReasonWithoutDoneIsSuccessful() {
        val events = collect(
            """
            data: {"choices":[{"delta":{"content":"完整"}}]}

            data: {"choices":[{"finish_reason":"stop","delta":{}}]}

            """.trimIndent()
        )

        assertEquals(
            listOf(
                StreamEvent.Text("", "完整"),
                StreamEvent.Finish("stop"),
            ),
            events,
        )
    }

    /** 服务器发了半句就没动静、连接也不断：到点自己断开报错，不干等。 */
    @Test(timeout = 10_000)
    fun stalledMidStreamFailsFast() {
        val server = ServerSocket(0)
        val hold = java.util.concurrent.CountDownLatch(1)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                        write("data: {\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}\n\n".toByteArray())
                        flush()
                    }
                    hold.await()
                }
            }
        }
        streamIdleOverrideMs = 300
        try {
            val error = assertThrows(java.io.IOException::class.java) {
                runBlocking {
                    DeepSeekClient().stream(
                        ep = Endpoint("http://127.0.0.1:${server.localPort}", "test-key", "test-model", deepseek = false, vision = false, reasoning = false),
                        messages = emptyList(),
                        thinking = ThinkLevel.OFF,
                        temperature = 1f,
                    ).toList()
                }
            }
            assertTrue(error.message.orEmpty().contains("卡住"))
        } finally {
            streamIdleOverrideMs = null
            hold.countDown()
            server.close()
        }
    }

    private fun collect(body: String, contentType: String = "text/event-stream"): List<StreamEvent> =
        withServer(body, contentType) { baseUrl ->
            runBlocking {
                DeepSeekClient().stream(
                    ep = Endpoint(
                        baseUrl = baseUrl,
                        apiKey = "test-key",
                        model = "test-model",
                        deepseek = false,
                        vision = false,
                        reasoning = false,
                    ),
                    messages = emptyList(),
                    thinking = ThinkLevel.OFF,
                    temperature = 1f,
                ).toList()
            }
        }

    private fun <T> withServer(body: String, contentType: String, block: (String) -> T): T {
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toInt()
                    }
                }
                var remaining = contentLength
                val chars = CharArray(1024)
                while (remaining > 0) {
                    val read = reader.read(chars, 0, minOf(chars.size, remaining))
                    if (read < 0) break
                    remaining -= read
                }

                val bytes = body.toByteArray()
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: $contentType\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray()
                    )
                    write(bytes)
                    flush()
                }
            }
        }

        return try {
            block("http://127.0.0.1:${server.localPort}")
        } finally {
            server.close()
            worker.join(1_000)
        }
    }
}
