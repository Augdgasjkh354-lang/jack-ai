package com.jack.ai.features

import android.app.Application
import com.jack.ai.WorkService
import com.jack.ai.data.Store
import com.jack.ai.tools.BrowserLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 几个对话同时回答：确认弹窗排队、浏览器一个一个用、通知栏合成一条。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParallelRepliesTest {
    @Test
    fun confirmsQueueUpOneAtATime() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Application
        val core = AppCore(app, Store(app), CoroutineScope(Dispatchers.Unconfined))
        val a = async { core.confirm("发邮件？", "A", "发送") }
        val b = async { core.confirm("删文件？", "B", "删除") }
        yield()
        // 先问先到的那个，后来的排在后面，不把前一个挤掉
        val first = core.state.value.confirm!!
        assertEquals("A", first.body)
        core.answerConfirm(first.id, true)
        assertTrue(withTimeout(1000) { a.await() })
        val second = core.state.value.confirm!!
        assertEquals("B", second.body)
        core.answerConfirm(second.id, false)
        assertFalse(withTimeout(1000) { b.await() })
        assertNull(core.state.value.confirm)
    }

    @Test
    fun browserIsSharedByAtMostThreeReplies() = runBlocking {
        val owners = List(4) { Any() }
        // 第一个用用户眼前那页，再来两个各开后台标签页，第四个排队
        owners.take(3).forEach { assertTrue(BrowserLock.enter(it)) }
        assertTrue(BrowserLock.holds(owners[0]))
        assertFalse(BrowserLock.enter(owners[3]))
        // 等不到就超时，不会一直卡着（占着的都在用，不接手）
        assertFalse(BrowserLock.lock(owners[3], 50))
        val waiting = async(Dispatchers.Default) { BrowserLock.lock(owners[3], 5_000) }
        delay(50)
        BrowserLock.unlock(owners[0])
        assertTrue(waiting.await())
        assertTrue(BrowserLock.holds(owners[3]))
        // 不是自己占着的放不掉别人的
        BrowserLock.unlock(owners[0])
        assertTrue(BrowserLock.holds(owners[3]))
        owners.forEach { BrowserLock.unlock(it) }
        assertTrue(BrowserLock.enter(owners[0]))
        BrowserLock.unlock(owners[0])
        Unit
    }

    @Test
    fun notificationSummarisesParallelReplies() {
        val one = WorkService.summaryText(listOf("c1" to Triple("写周报", "联网搜索", false)))
        assertEquals("正在回答：写周报" to "联网搜索", one)
        val two = WorkService.summaryText(listOf("c1" to Triple("写周报", "联网搜索", false), "c2" to Triple("比价", "", true)))
        assertEquals("2 个在同时进行", two.first)
        assertEquals("写周报：联网搜索\n比价：进行中…", two.second)
    }
}
