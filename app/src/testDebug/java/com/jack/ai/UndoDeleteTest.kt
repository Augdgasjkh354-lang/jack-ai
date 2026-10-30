package com.jack.ai

import android.app.Application
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UndoDeleteTest {
    private fun vm(c: Conversation): ChatViewModel {
        val app = RuntimeEnvironment.getApplication() as Application
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        Store(app).saveConversations(listOf(c))
        return ChatViewModel(app).also { it.select(c.id) }
    }

    private val msgs = listOf(
        ChatMessage(role = "user", content = "一"),
        ChatMessage(role = "assistant", content = "二"),
        ChatMessage(role = "user", content = "三"),
        ChatMessage(role = "assistant", content = "四"),
    )

    @Test fun deleteThenUndoPutsMessagesBack() {
        val c = Conversation(title = "t", messages = msgs)
        val vm = vm(c)
        assertEquals(2, vm.deleteFrom(msgs[2].id))
        assertEquals(2, vm.state.value.current!!.messages.size)
        assertTrue(vm.undoDelete())
        assertEquals(msgs.map { it.id }, vm.state.value.current!!.messages.map { it.id })
        assertFalse(vm.undoDelete())
    }

    @Test fun secondDeleteReplacesUndo() {
        val c = Conversation(title = "t", messages = msgs)
        val vm = vm(c)
        vm.deleteFrom(msgs[3].id)
        vm.deleteFrom(msgs[1].id)
        assertTrue(vm.undoDelete())
        // 只能撤销最近一次：回到删第二次之前（三条）
        assertEquals(msgs.take(3).map { it.id }, vm.state.value.current!!.messages.map { it.id })
    }
}
