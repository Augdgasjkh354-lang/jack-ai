package com.jack.ai.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import com.jack.ai.data.browser.SiteNotes
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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

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
    fun siteTroubleIsRememberedForNextTime() = runBlocking {
        SiteNotes.record(ctx, "zhihu.com", Failures.Kind.LOGIN)
        SiteNotes.record(ctx, "zhihu.com", Failures.Kind.LOGIN)
        SiteNotes.record(ctx, "example.org", null)
        val note = SiteNotes.note(SiteNotes.load(ctx))
        assertTrue(note.contains("zhihu.com: needs login ×2"))
        assertFalse(note.contains("example.org"))  // 没出过问题的不提
        assertEquals("taobao.com", SiteNotes.hostOf("https://www.Taobao.com/item?id=1"))
    }

    @Test
    fun planToolIsRetiredButOldCallsStillGetAnAnswer() = runBlocking {
        // 任务清单停用了：旧对话里模型照着历史调 update_plan，回一句让它接着做，不报错
        val out = session().execute("update_plan", plan(3))
        assertTrue(out.contains("已经停用"))
        // 手机上存的旧做事原则里那条「先用 update_plan」去掉，后面的重新编号
        val old = "Work mode.\n\n1. Always start with update_plan: steps.\n2. Do the work.\n3. Check once."
        assertEquals("Work mode.\n\n1. Do the work.\n2. Check once.", com.jack.ai.data.Persona.withoutPlanRule(old))
    }
}
