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
    fun reportTopicsAreParsed() {
        val (t, body) = Reader.parseReport("Topics: 端侧大模型; 劳动法 加班费；咖啡因\n今天读了三件事。")
        assertEquals(listOf("端侧大模型", "劳动法 加班费", "咖啡因"), t)
        assertEquals("今天读了三件事。", body)
        assertEquals(emptyList<String>() to "随便写的", Reader.parseReport("随便写的"))
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
        // 没有任何 Key：联网搜索技能用不了
        store.saveSettings(store.loadSettings().copy(apiKey = "", tavilyKey = "", exaKey = "", readingEnabled = true, readingBudget = 0.5))
        val e = Reader.read(ctx)!!
        assertTrue(e.skipped, e.skipped.contains("联网"))
        // 预算是 0：不读
        store.saveSettings(store.loadSettings().copy(readingBudget = 0.0))
        assertTrue(Reader.read(ctx)!!.skipped.contains("预算"))
        assertEquals(2, Reader.log(ctx).size)
    }
}
