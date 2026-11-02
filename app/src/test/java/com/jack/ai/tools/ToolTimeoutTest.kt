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
}
