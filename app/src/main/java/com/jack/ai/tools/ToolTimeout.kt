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

    /** 叫停之后最多再等这么久让工具收尾；还不出来就不等了（测试里调小）。 */
    @Volatile internal var graceMs = 8_000L

    /**
     * 思路来自 deepseek-ai/deepseek-harness packages/guard/timeout-policy/，MIT。保留上游取消，只处理本次截止时间。
     *
     * 到点后取消工具，并断开它还卡着的网络请求（阻塞读收不到协程取消，不断开就得等读超时）；
     * 再等 [graceMs] 让它收尾。还不出来（卡在别的阻塞代码里）就不等了，先把结果交回模型，任务不至于一直停在这一步。
     * 用户按停止（上游取消）也一样：断开请求、等一小会儿就走。
     */
    suspend fun run(name: String, limitMs: Long = secondsFor(name) * 1000, action: suspend () -> String): String {
        val ctx = currentCoroutineContext()
        val clock = Clock(limitMs, ctx[Clock])
        val calls = com.jack.ai.api.ActiveCalls(ctx[com.jack.ai.api.ActiveCalls])
        // 不挂在调用方的 Job 下面：挂着的话，工具不出来调用方也走不了
        val scope = CoroutineScope(ctx.minusKey(Job) + SupervisorJob() + clock + calls)
        val task = scope.async { action() }
        suspend fun stop(cause: CancellationException?) {
            task.cancel(cause)
            calls.cancelAll()
            withContext(NonCancellable) { withTimeoutOrNull(graceMs) { task.join() } }
        }
        try {
            while (!task.isCompleted) {
                val left = clock.left()
                if (left <= 0) {
                    stop(Expired())
                    return "$name 工具超过 ${limitMs / 1000} 秒没返回，已取消，换个办法或告诉用户。" +
                        if (task.isCompleted) "" else "（它还没完全停下来，先不要马上再调 $name。）"
                }
                withTimeoutOrNull(left.coerceIn(1, 50)) { task.join() }
            }
            return task.await()
        } catch (e: CancellationException) {
            // 上游取消（用户按了停止），或者工具自己被取消：都收拾干净再往上抛
            if (!task.isCompleted) stop(null)
            throw e
        }
    }
}
