package com.jack.ai.tools

import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 思路来自 deepseek-ai/deepseek-harness packages/guard/timeout-policy/，MIT。统一时限，取消完成后再返回模型。 */
object ToolTimeout {
    val seconds = mapOf(
        "web_search" to 180L, "read_webpage" to 180L, "browser" to 360L,
        "download_file" to 600L, "phone" to 360L, "phone_files" to 180L,
        "run_python" to 360L, "delegate_tasks" to 600L, "run_code" to 65L,
    )
    fun secondsFor(name: String) = seconds[name] ?: 90L
    private class Expired : CancellationException()

    private class Clock(ms: Long, val parent: Clock?) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Clock>
        private var remaining = ms * 1_000_000
        private var at = System.nanoTime()
        private var paused = 0
        @Synchronized fun left(): Long {
            val now = System.nanoTime()
            if (paused == 0) remaining -= now - at
            at = now
            return remaining / 1_000_000
        }
        @Synchronized fun pause() { left(); paused++; parent?.pause() }
        @Synchronized fun resume() { left(); paused--; parent?.resume() }
    }

    /** 思路来自 deepseek-ai/deepseek-harness packages/guard/timeout-policy/，MIT。确认弹窗不消耗工具执行时限。 */
    suspend fun <T> waiting(action: suspend () -> T): T {
        val clock = currentCoroutineContext()[Clock]
        clock?.pause()
        return try { action() } finally { clock?.resume() }
    }

    /** 思路来自 deepseek-ai/deepseek-harness packages/guard/timeout-policy/，MIT。保留上游取消，只处理本次截止时间。 */
    suspend fun run(name: String, limitMs: Long = secondsFor(name) * 1000, action: suspend () -> String): String = coroutineScope {
        val clock = Clock(limitMs, currentCoroutineContext()[Clock])
        val task = async(clock) { action() }
        val timer = launch {
            while (task.isActive) {
                val left = clock.left()
                if (left <= 0) { task.cancel(Expired()); break }
                delay(left.coerceIn(1, 50))
            }
        }
        try { task.await() }
        catch (e: Expired) {
            currentCoroutineContext().ensureActive()
            "$name 工具超过 ${limitMs / 1000} 秒没返回，已取消，换个办法或告诉用户。"
        } finally { timer.cancel() }
    }
}
