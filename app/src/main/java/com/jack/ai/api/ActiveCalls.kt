package com.jack.ai.api

import kotlinx.coroutines.ThreadContextElement
import okhttp3.Call
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 一次工具调用里发出去的阻塞请求（execute()）。协程取消了，卡在读网络的线程并不知道，要等读超时才返回；
 * 把这些请求记下来，工具超时或用户按停止时直接断开连接，线程马上就能出来。
 *
 * 作为协程上下文带着走：切到哪个线程，哪个线程的 ThreadLocal 就指向它；sharedHttp 的拦截器在发请求的线程上登记。
 * 流式请求（enqueue）跑在 OkHttp 自己的线程上，不登记，它们本来就能随协程取消。
 */
class ActiveCalls(private val parent: ActiveCalls? = null) : ThreadContextElement<ActiveCalls?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActiveCalls> {
        private val local = ThreadLocal<ActiveCalls?>()
        /** 拦截器调：当前线程在工具调用里就记下这个请求。 */
        fun track(call: Call) { local.get()?.add(call) }
    }

    private val calls: MutableSet<Call> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    @Volatile private var cancelled = false

    private fun add(call: Call) {
        synchronized(calls) { calls += call }
        // 已经叫停了还在发新请求（工具里的重试）：直接断掉，让它尽快退出来
        if (cancelled) call.cancel()
        parent?.add(call)
    }

    /** 断开这次工具调用里所有还没结束的请求；之后新发的也立刻断开。 */
    fun cancelAll() {
        cancelled = true
        synchronized(calls) { calls.toList() }.forEach { it.cancel() }
    }

    override fun updateThreadContext(context: CoroutineContext): ActiveCalls? = local.get().also { local.set(this) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: ActiveCalls?) = local.set(oldState)
}
