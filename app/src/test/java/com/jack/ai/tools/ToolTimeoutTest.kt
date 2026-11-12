package com.jack.ai.tools

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 思路来自 deepseek-ai/deepseek-harness packages/guard/timeout-policy/，MIT。 */
class ToolTimeoutTest {
    @Test fun timeoutCancelsWorkAndReturnsReason() = runBlocking {
        var cancelled = false
        val text = ToolTimeout.run("read_file", 30) { try { delay(200); "不应返回" } finally { cancelled = true } }
        assertTrue(cancelled); assertTrue(text.contains("已取消")); assertTrue(text.contains("read_file"))
    }
    @Test fun confirmationWaitIsExcludedAndParentCancellationStillWorks() = runBlocking {
        assertEquals("完成", ToolTimeout.run("send_email", 100) { ToolTimeout.waiting { delay(200) }; "完成" })
        val entered = CompletableDeferred<Unit>()
        val task = async { ToolTimeout.run("read_file", 500) { entered.complete(Unit); delay(1000); "不应返回" } }
        entered.await()
        task.cancelAndJoin(); assertTrue(task.isCancelled)
    }

    /** 卡在不理会取消的阻塞代码里：过了收尾时间就不等了，照样把结果交回。 */
    @Test(timeout = 5_000) fun blockedWorkDoesNotHoldTheCaller() = runBlocking {
        val old = ToolTimeout.graceMs
        ToolTimeout.graceMs = 100
        try {
            val started = System.nanoTime()
            val text = ToolTimeout.run("run_python", 50) { withContext(Dispatchers.IO) { Thread.sleep(3_000) }; "不应返回" }
            assertTrue(text.contains("已取消")); assertTrue(text.contains("还没完全停下来"))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        } finally { ToolTimeout.graceMs = old }
    }

    /** 卡在阻塞的网络读上：到点直接断开连接，不用等读超时。 */
    @Test(timeout = 15_000) fun blockedHttpReadIsCutOnTimeout() = runBlocking {
        val server = java.net.ServerSocket(0)
        val hold = java.util.concurrent.CountDownLatch(1)
        kotlin.concurrent.thread(isDaemon = true) { runCatching { server.accept().use { hold.await() } } }
        try {
            var failed = false
            val started = System.nanoTime()
            val text = ToolTimeout.run("read_webpage", 300) {
                withContext(Dispatchers.IO) {
                    try {
                        com.jack.ai.api.sharedHttp.newBuilder().proxy(java.net.Proxy.NO_PROXY).build()
                            .newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()).execute().close()
                    } catch (e: java.io.IOException) { failed = true }
                }
                "不应返回"
            }
            assertTrue(text.contains("已取消")); assertFalse(text.contains("还没完全停下来"))
            assertTrue(failed)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        } finally { hold.countDown(); server.close() }
    }
}
