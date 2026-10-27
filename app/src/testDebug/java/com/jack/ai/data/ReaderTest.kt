package com.jack.ai.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 阅读时间：小结里认得出话题；按自然日算预算；预算用完、没开联网就不读（记一笔原因）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun clean() { File(ctx.filesDir, "reading_log.json").delete() }

    @Test
    fun reportHeaderIsParsed() {
        val r = Reader.parseReport("Did: build\nTitle: 记账小工具\nTopics: 端侧大模型; 劳动法 加班费；咖啡因\n做了一个记账的小网页。")
        assertEquals("build", r.kind)
        assertEquals("记账小工具", r.title)
        assertEquals(listOf("端侧大模型", "劳动法 加班费", "咖啡因"), r.topics)
        assertEquals("做了一个记账的小网页。", r.body)
        // 模型加了粗体、没写 Did：有话题就当作读书
        val b = Reader.parseReport("**Topics**: 咖啡因\n读了点东西")
        assertEquals("read", b.kind); assertEquals(listOf("咖啡因"), b.topics)
        assertEquals(Reader.Report("other", "", emptyList(), "随便写的"), Reader.parseReport("随便写的"))
    }

    @Test
    fun budgetCountsOnlyToday() {
        val now = System.currentTimeMillis()
        val log = listOf(ReadEntry(at = now - 1000, spent = 0.2), ReadEntry(at = now - 3 * 86_400_000L, spent = 5.0))
        assertEquals(0.2, Reader.spentToday(log, now), 1e-9)
    }

    @Test
    fun skipsWithAReasonWhenItCant() = runBlocking {
        val store = Store(ctx)
        // 没有任何 Key：做不了，记下原因
        store.saveSettings(store.loadSettings().copy(apiKey = "", tavilyKey = "", exaKey = "", readingEnabled = true, readingBudget = 0.5))
        val e = Reader.read(ctx)!!
        assertTrue(e.skipped.isNotEmpty())
        // 预算是 0：不去
        store.saveSettings(store.loadSettings().copy(readingBudget = 0.0))
        assertTrue(Reader.read(ctx)!!.skipped.contains("预算"))
        assertEquals(2, Reader.log(ctx).size)
        // 关着又不是手动点的：什么都不做
        store.saveSettings(store.loadSettings().copy(readingEnabled = false, readingBudget = 0.5))
        assertEquals(null, Reader.read(ctx))
    }

    @Test
    fun budgetNoteSaysGoodnight() {
        assertEquals("系统：Kavi，你的今日预算已经用完了哦，你可以把你要做的事情收尾，也可以等明天再做，新的预算是 ¥0.10，不必必须花完，收尾即可，晚安。",
            Reader.budgetNote(0.1))
    }
}
