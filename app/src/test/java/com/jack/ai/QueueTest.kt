package com.jack.ai

import android.app.Application
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** 排队的消息：撤回、按顺序取、对话没在回答时不排。 */
@RunWith(RobolectricTestRunner::class)
class QueueTest {
    private fun vm(): ChatViewModel {
        val app = RuntimeEnvironment.getApplication() as Application
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        return ChatViewModel(app)
    }

    @Test fun notBusyDoesNotQueue() {
        val vm = vm()
        vm._state.update { it.copy(currentId = "c1") }
        assertFalse(vm.queue("顺便算一下 9 月"))
        assertNull(vm.state.value.queued["c1"])
    }

    @Test fun takesInOrderAndUndoRemovesOne() {
        val vm = vm()
        vm._state.update { it.copy(queued = mapOf("c1" to listOf("a", "b", "c"), "c2" to listOf("x"))) }
        assertEquals("b", vm.unqueue("c1", 1))
        assertEquals(listOf("a", "c"), vm.state.value.queued["c1"])
        assertEquals("a", vm.takeQueued("c1"))
        assertEquals("c", vm.takeQueued("c1"))
        // 取空了就把这个对话从表里拿掉；别的对话不受影响
        assertFalse("c1" in vm.state.value.queued)
        assertNull(vm.takeQueued("c1"))
        assertNull(vm.unqueue("c2", 5))
        assertEquals(listOf("x"), vm.state.value.queued["c2"])
    }
}
