package com.jack.ai.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MemoryResetTest {
    @Test fun clearsWhatIsAboutTheUserKeepsExperienceAndCanRestore() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = Store(ctx)
        store.saveMemories(listOf(MemoryItem(text = "小林在杭州")))
        MemoryOps.saveDeep(ctx, listOf(MemoryItem(text = "对芒果过敏")))
        Threads.save(ctx, listOf(OpenThread(text = "答应帮他查机票")))
        store.saveFlows(listOf(BrowserFlow(name = "1688 比价", site = "1688.com", steps = "1. 搜索")))
        store.saveSiteNotes(listOf(SiteNote(site = "1688.com", text = "要先关弹窗")))

        MemoryReset.reset(ctx, store, withChatFiles = false, now = 1_000_000L)
        assertTrue(store.loadMemories().isEmpty())
        assertTrue(MemoryOps.deep(ctx).isEmpty())
        assertTrue(Threads.load(ctx).isEmpty())
        // 做事的经验留着
        assertEquals("1688 比价", store.loadFlows().single().name)
        assertEquals(1, store.loadSiteNotes().size)

        val b = MemoryReset.latest(ctx, now = 1_000_000L + 86_400_000L)
        assertNotNull(b)
        MemoryReset.restore(ctx, store, b!!)
        assertEquals("小林在杭州", store.loadMemories().single().text)
        assertEquals("对芒果过敏", MemoryOps.deep(ctx).single().text)

        // 过了 7 天就没得恢复了
        MemoryReset.reset(ctx, store, withChatFiles = false, now = 2_000_000L)
        assertNull(MemoryReset.latest(ctx, now = 2_000_000L + MemoryReset.KEEP_DAYS * 86_400_000L))
    }
}
