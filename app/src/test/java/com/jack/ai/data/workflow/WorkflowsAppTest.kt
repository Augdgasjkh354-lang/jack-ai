package com.jack.ai.data.workflow

import android.app.Application
import com.jack.ai.data.Conversation
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.SkillIds
import com.jack.ai.data.Store
import com.jack.ai.data.allSkills
import com.jack.ai.tools.BackgroundToolHost
import com.jack.ai.tools.ToolSession
import com.jack.ai.tools.ToolUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** App 里的工作流：存取、真跑（代码、存文件、发收件箱）、聊天里的 workflow 工具。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkflowsAppTest {
    private val ctx = RuntimeEnvironment.getApplication().also {
        runCatching { androidx.work.WorkManager.initialize(it, androidx.work.Configuration.Builder().build()) }
    }

    private val json = """{"id":"w1","name":"算一算","steps":[
        {"id":"a","type":"code","name":"和","code":"return { total: [1,2,3].reduce((a, b) => a + b, 0), who: vars.输入 }"},
        {"id":"b","type":"if","cond":{"left":"{{和.total}}","op":">","right":"5"},"then":[
          {"id":"c","type":"file","file":"结果.txt","text":"总数 {{和.total}}"},
          {"id":"d","type":"message","text":"{{和.who}}：总数是 {{和.total}}"}]}]}"""

    @Test fun saveRunAndKeepRecords() = runBlocking {
        val w = Workflows.save(ctx, WorkflowFormat.parse(json).getOrThrow())
        assertEquals(listOf("w1"), Workflows.load(ctx).map { it.id })
        val r = Workflows.run(ctx, w, "manual", kotlinx.serialization.json.JsonPrimitive("Jack"))
        assertEquals(r.error, "ok", r.status)
        assertEquals("总数 6", File(Workflows.filesDir(ctx, "w1"), "结果.txt").readText())
        val inbox = Store(ctx).takeInbox()
        assertTrue(inbox.toString(), inbox.any { it.result == "Jack：总数是 6" })
        assertEquals(1, Workflows.runs(ctx, "w1").size)
        Workflows.update(ctx, "w1") { it.copy(enabled = false) }
        assertEquals(false, Workflows.get(ctx, "w1")!!.enabled)
        Workflows.delete(ctx, "w1")
        assertTrue(Workflows.load(ctx).isEmpty())
        assertTrue(Workflows.runs(ctx, "w1").isEmpty())
    }

    @Test fun codeStepCannotReachTheNetwork() = runBlocking {
        val w = WorkflowFormat.parse("""{"name":"x","steps":[{"id":"a","type":"code","code":"await kavi.http.get('https://a.com'); return 1"}]}""").getOrThrow()
        val r = Workflows.run(ctx, w, "manual")
        assertEquals("failed", r.status)
        assertTrue(r.error, r.error.contains("只能算东西"))
    }

    @Test fun chatToolSavesAfterAskingThenRuns() = runBlocking {
        var asked = 0; var answer = true
        val ui = object : ToolUi {
            override fun started(label: String) {}
            override fun finished(label: String, record: SearchRecord) {}
            override fun attach(file: File) {}
            override suspend fun confirm(title: String, body: String, action: String): Boolean { asked++; return answer }
        }
        val s = Store(ctx).loadSettings()
        val session = ToolSession(BackgroundToolHost(ctx), s, Conversation(), null, s.allSkills().filter { it.id == SkillIds.ROUTINE }, ui)
        assertTrue(session.execute("workflow", """{"action":"help"}""").contains("forEach"))
        val bad = session.execute("workflow", """{"action":"save","workflow":{"name":"","steps":[]}}""")
        assertTrue(bad, bad.contains("name 不能空"))
        answer = false
        assertTrue(session.execute("workflow", """{"action":"save","workflow":$json}""").contains("没存"))
        answer = true
        val saved = session.execute("workflow", """{"action":"save","workflow":$json}""")
        assertTrue(saved, saved.contains("已存好「算一算」"))
        assertEquals("kavi", Workflows.get(ctx, "w1")!!.by)
        assertTrue(session.execute("workflow", """{"action":"list"}""").contains("算一算（id w1）"))
        val ran = session.execute("workflow", """{"action":"run","id":"算一算","input":"小明"}""")
        assertTrue(ran, ran.contains("跑完了") && ran.contains("✓ 和") && ran.contains("小明：总数是 6"))
        assertTrue(session.execute("workflow", """{"action":"runs","id":"w1"}""").contains("在聊天里跑"))
        assertEquals(2, asked)
    }
}
