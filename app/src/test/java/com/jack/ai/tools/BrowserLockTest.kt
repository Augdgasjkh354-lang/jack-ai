package com.jack.ai.tools

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLockTest {
    private val a = Any()
    private val b = Any()

    @After fun tearDown() { BrowserLock.unlock(a); BrowserLock.unlock(b); BrowserLock.idleMs = 20_000L }

    @Test fun idleHolderIsTakenOverAndToldLater() = runBlocking {
        BrowserLock.idleMs = 50
        assertTrue(BrowserLock.enter(a)); BrowserLock.exit(a)
        assertTrue(BrowserLock.lock(b, 2_000))
        assertTrue(BrowserLock.takeLost(a))
        assertFalse(BrowserLock.enter(a))
    }

    @Test fun busyHolderIsNotTakenOver() = runBlocking {
        BrowserLock.idleMs = 50
        assertTrue(BrowserLock.enter(a))
        assertFalse(BrowserLock.lock(b, 700))
        BrowserLock.exit(a)
    }
}
