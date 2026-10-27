package com.jack.ai.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import com.jack.ai.data.SiteNotes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Work 的几道关：开工前确认清单、卡住提醒、来源核对、网站经验。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkStrengthTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val asked = mutableListOf<String>()
    private val notices = mutableListOf<String>()
    private var answer = true
    private val ui = object : ToolUi {
        override fun started(label: String) {}
        override fun finished(label: String, record: SearchRecord) {}
        override fun attach(file: File) {}
        override suspend fun confirm(title: String, body: String, action: String): Boolean { asked += title; return answer }
        override fun notice(text: String) { notices += text }
    }
    private val conv = Conversation(work = true, messages = listOf(ChatMessage(role = "user", content = "比较 5 家供应商，参考 https://known.com/list")))

    private fun session(s: Settings = Settings(apiKey = "k", userName = "Jack")) =
        ToolSession(BackgroundToolHost(ctx), s, conv, null, emptyList(), ui)

    private fun plan(n: Int, confirm: Boolean = false) = """{"items":[${(1..n).joinToString(",") { """{"text":"第 $it 步","status":"pending"}""" }}],
        "criteria":[{"text":"找满 5 家","status":"pending"}]${if (confirm) ""","confirm":true""" else ""}}"""

    @Before fun clean() { File(ctx.filesDir, "site_notes.json").delete() }

    @Test
    fun bigPlansAreConfirmedFirst() = runBlocking {
        val s = session()
        var shown = 0
        s.planConfirm = { _, _ -> shown++; true }
        s.execute("update_plan", plan(3))
        assertEquals(0, shown)  // 小任务不问
        val big = session()
        big.planConfirm = { _, _ -> shown++; false }
        val out = big.execute("update_plan", plan(6))
        assertEquals(1, shown)
        assertEquals(0, big.maxRounds)  // 不同意：停下问要怎么改
        assertTrue(out.contains("Jack wants to adjust the plan"))
        // 只在第一次写清单时问
        big.maxRounds = Int.MAX_VALUE
        big.execute("update_plan", plan(7))
        assertEquals(1, shown)
        // 模型觉得要问（要求含糊、花费大）：步骤少也问
        val vague = session()
        vague.planConfirm = { _, _ -> shown++; true }
        vague.execute("update_plan", plan(2, confirm = true))
        assertEquals(2, shown)
        // 设成从不：不问
        val never = session(Settings(apiKey = "k", workConfirmPlan = "never"))
        never.planConfirm = { _, _ -> shown++; true }
        never.execute("update_plan", plan(9))
        assertEquals(2, shown)
    }

    @Test
    fun stallIsNoticedWhenThePlanDoesntMove() = runBlocking {
        val s = session()
        val gate = WorkGate(s)
        s.execute("update_plan", plan(3))
        gate.reminder("update_plan")
        val notes = (1..WorkGate.STALL_CALLS).map { gate.reminder("web_search") }
        assertTrue(notes.dropLast(1).all { it.isEmpty() })
        assertTrue(notes.last().contains("without the plan moving forward"))
        // 清单动了就重新计数
        s.execute("update_plan", """{"items":[{"text":"第 1 步","status":"done"},{"text":"第 2 步","status":"doing"}]}""")
        assertEquals("", gate.reminder("update_plan"))
        assertEquals("", gate.reminder("web_search"))
    }

    @Test
    fun linksMustHaveBeenSeen() = runBlocking {
        val s = session()
        val gate = WorkGate(s)
        s.execute("update_plan", """{"items":[{"text":"搜","status":"done"}],"criteria":[{"text":"有来源","status":"met"}]}""")
        s.seenUrls += "https://shop.example.com/item/42?ref=search"
        // 见过的、对话里本来就有的、只写网站首页的都算有据；编出来的打回去
        val back = gate.beforeFinish("对比见 https://shop.example.com/item/42?ref=search 、https://known.com/list 、https://shop.example.com ，还有 https://made-up.com/p/1")
        assertTrue(back!!.contains("https://made-up.com/p/1"))
        assertFalse(back.contains("known.com"))
        assertFalse(back.contains("shop.example.com"))
        assertTrue(notices.any { it.startsWith("来源核对") })
        // 只核对一次
        assertNull(gate.beforeFinish("还是 https://made-up.com/p/1"))
    }

    @Test
    fun siteTroubleIsRememberedForNextTime() = runBlocking {
        SiteNotes.record(ctx, "zhihu.com", Failures.Kind.LOGIN)
        SiteNotes.record(ctx, "zhihu.com", Failures.Kind.LOGIN)
        SiteNotes.record(ctx, "example.org", null)
        val note = SiteNotes.note(SiteNotes.load(ctx))
        assertTrue(note.contains("zhihu.com: needs login ×2"))
        assertFalse(note.contains("example.org"))  // 没出过问题的不提
        assertEquals("taobao.com", SiteNotes.hostOf("https://www.Taobao.com/item?id=1"))
    }
}
