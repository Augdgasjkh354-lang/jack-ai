package com.jack.ai.tools

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLockTest {
    private val owners = List(4) { Any() }

    @After fun tearDown() { owners.forEach { BrowserLock.unlock(it) }; BrowserLock.idleMs = 20_000L }

    @Test fun idleHolderGivesUpItsPlaceWhenFull() = runBlocking {
        BrowserLock.idleMs = 50
        owners.take(3).forEach { assertTrue(BrowserLock.enter(it)); BrowserLock.exit(it) }
        assertTrue(BrowserLock.lock(owners[3], 2_000))
        assertTrue(BrowserLock.takeLost(owners[0]))
        assertFalse(BrowserLock.holds(owners[0]))
    }

    @Test fun busyHoldersAreNotTakenOver() = runBlocking {
        BrowserLock.idleMs = 50
        owners.take(3).forEach { assertTrue(BrowserLock.enter(it)) }
        assertFalse(BrowserLock.lock(owners[3], 700))
    }

    @Test fun onlyOneReplyGetsTheVisiblePage() {
        assertTrue(BrowserLock.enter(owners[0]))
        assertTrue(BrowserLock.enter(owners[1]))
        // 浏览器里的 Kavi 非要眼前那页：被第一个占着就得等
        assertFalse(BrowserLock.enter(owners[2], wantsVisible = true))
        // 后台位子还没开标签页时不钉
        assertNull(BrowserLock.pinOf(owners[1]))
        assertTrue(BrowserLock.needsTab(owners[1]) { true })
        assertFalse(BrowserLock.needsTab(owners[0]) { true })
    }
}
