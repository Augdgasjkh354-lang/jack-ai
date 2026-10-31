package com.jack.ai.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jack.ai.tools.BackgroundToolHost
import com.jack.ai.tools.ToolSession
import com.jack.ai.tools.ToolUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 事实库：只收真读过的出处；同一条合并出处、两个网站算核对过；更正留历史；检索关键词和向量一起排；向量存取。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FactsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val ui = object : ToolUi {
        override fun started(label: String) {}
        override fun finished(label: String, record: SearchRecord) {}
        override fun attach(file: File) {}
        override suspend fun confirm(title: String, body: String, action: String) = true
    }

    @Before fun clean() { File(ctx.filesDir, "facts").deleteRecursively(); Facts.resetForTest() }

    private fun session() = ToolSession(BackgroundToolHost(ctx), Settings(apiKey = "k"), Conversation(work = true), null, emptyList(), ui)

    private fun save(s: ToolSession, json: String) = runBlocking { s.execute("facts", json.replaceFirst("{", "{\"action\":\"save\",")) }

    @Test
    fun onlySourcesActuallyReadAreAccepted() {
        val s = session()
        s.seenUrls += "https://a.com/news/1"
        val out = save(s, """{"facts":[{"text":"DeepSeek V4 于 2026 年 4 月发布","sources":["https://a.com/news/1"]},
            {"text":"编出来的一条事实内容","sources":["https://made-up.com/x"]},{"text":"没给出处的一条事实","sources":[]}]}""")
        assertTrue(out, out.contains("＋"))
        assertTrue(out.contains("不是这次真读过的网页"))
        assertTrue(out.contains("没给"))
        assertEquals(1, Facts.load(ctx).size)
        assertEquals("single", Facts.load(ctx).single().status)
    }

    @Test
    fun sameFactFromAnotherSiteBecomesVerified() {
        val s = session()
        s.seenUrls += listOf("https://a.com/1", "https://b.org/2")
        save(s, """{"facts":[{"text":"珠穆朗玛峰海拔 8848.86 米","sources":["https://a.com/1"]}]}""")
        val out = save(s, """{"facts":[{"text":"珠穆朗玛峰海拔 8848.86 米。","sources":["https://b.org/2"]}]}""")
        assertTrue(out, out.contains("合并出处"))
        val f = Facts.load(ctx).single()
        assertEquals("verified", f.status)
        assertEquals(2, f.sources.size)
    }

    @Test
    fun correctionsRetireTheOldOneWithHistory() {
        val s = session()
        s.seenUrls += "https://a.com/1"
        save(s, """{"facts":[{"text":"某某公司 CEO 是张三","sources":["https://a.com/1"]}]}""")
        val old = Facts.load(ctx).single()
        save(s, """{"facts":[{"text":"某某公司 CEO 已换成李四","sources":["https://a.com/1"],"replaces":"${old.id.take(8)}"}]}""")
        val all = Facts.load(ctx)
        assertEquals("retired", all.first { it.id == old.id }.status)
        assertEquals(1, all.count { it.status != "retired" })
        // 检索不出作废的
        val hits = Facts.search(all, "CEO", null, emptyMap())
        assertEquals(listOf("某某公司 CEO 已换成李四"), hits.map { it.text })
        assertTrue(runBlocking { s.execute("facts", """{"action":"recall","query":"某某公司 CEO"}""") }.contains("李四"))
    }

    @Test
    fun privateProjectsDontWrite() {
        val s = ToolSession(BackgroundToolHost(ctx), Settings(apiKey = "k"), Conversation(work = true, projectId = "p"),
            Project(id = "p", name = "私密", isolated = true), emptyList(), ui)
        s.seenUrls += "https://a.com/1"
        assertTrue(save(s, """{"facts":[{"text":"一条普通的事实内容","sources":["https://a.com/1"]}]}""").contains("隐私项目"))
        assertTrue(Facts.load(ctx).isEmpty())
    }

    @Test
    fun vectorsAreKeptPerEngine() {
        val a = FloatArray(8) { if (it == 0) 1f else 0f }
        val b = FloatArray(4) { 1f }
        Facts.putVector(ctx, "fa", a)
        Facts.putVector(ctx, "fa", b, "local-x")
        Facts.putVector(ctx, "fc", b, "local-x")
        Facts.resetForTest()
        assertEquals(8, Facts.vectors(ctx)["fa"]!!.size)
        assertEquals(setOf("fa", "fc"), Facts.vectors(ctx, "local-x").keys)
        // 删掉一条：每个来源的都删
        Facts.removeVector(ctx, "fa")
        Facts.resetForTest()
        assertTrue(Facts.vectors(ctx).isEmpty())
        assertEquals(setOf("fc"), Facts.vectors(ctx, "local-x").keys)
    }

    @Test
    fun vectorsRoundTripAndRank() {
        val a = FloatArray(8) { if (it == 0) 1f else 0f }
        val b = FloatArray(8) { if (it == 1) 1f else 0f }
        Facts.putVector(ctx, "fa", a); Facts.putVector(ctx, "fb", b); Facts.removeVector(ctx, "fb"); Facts.putVector(ctx, "fb", b)
        Facts.resetForTest()
        val v = Facts.vectors(ctx)
        assertEquals(setOf("fa", "fb"), v.keys)
        val list = listOf(Fact(id = "fa", text = "苹果"), Fact(id = "fb", text = "香蕉"))
        // 关键词对不上，靠向量找到
        assertEquals("fa", Facts.search(list, "水果甲", a, v, 1).single().id)
        assertEquals(listOf("x", "y", "z"), Facts.fuse(listOf("x", "y"), listOf("x", "z", "y")).let { it.take(1) + it.drop(1).sorted() })
        assertFalse(Facts.cosine(Facts.quantize(a), Facts.quantize(b)) > 0.1f)
    }
}
