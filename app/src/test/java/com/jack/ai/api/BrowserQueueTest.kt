package com.jack.ai.api

import com.jack.ai.api.BrowserQueue.State.*
import org.junit.Assert.*
import org.junit.Test

class BrowserQueueTest {
    @Test fun atMostThreeRunningAndPausedTasksDoNotBlockQueue() {
        assertEquals(listOf(0, 1, 2), BrowserQueue.starts(List(5) { QUEUED }))
        assertEquals(listOf(3), BrowserQueue.starts(listOf(RUNNING, PAUSED, RUNNING, QUEUED, QUEUED)))
        assertTrue(BrowserQueue.starts(listOf(RUNNING, RUNNING, RUNNING, QUEUED)).isEmpty())
    }
    @Test fun pauseResumeAndTerminalStates() {
        assertEquals(PAUSED, BrowserQueue.pause(QUEUED))
        assertEquals(QUEUED, BrowserQueue.resume(BrowserQueue.pause(RUNNING)))
        assertEquals(COMPLETE, BrowserQueue.pause(COMPLETE))
        assertEquals(CANCELLED, BrowserQueue.resume(CANCELLED))
    }
}
