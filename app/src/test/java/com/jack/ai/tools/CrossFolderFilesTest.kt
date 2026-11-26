package com.jack.ai.tools

import android.app.Application
import com.jack.ai.data.Conversation
import com.jack.ai.data.Project
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Store
import com.jack.ai.data.Workspace
import com.jack.ai.data.allSkills
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 别的对话里的文件：search_my_data where=files 找出来，用「@文件夹/文件名」读和改；删不了，隐私项目的碰不到。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CrossFolderFilesTest {
    private val ctx = RuntimeEnvironment.getApplication()

    private fun session(conv: Conversation): ToolSession {
        val ui = object : ToolUi {
            override fun started(label: String) {}
            override fun finished(label: String, record: SearchRecord) {}
            override fun attach(file: File) {}
            override suspend fun confirm(title: String, body: String, action: String): Boolean = true
        }
        val settings = Store(ctx).loadSettings()
        return ToolSession(BackgroundToolHost(ctx), settings, conv, null, settings.allSkills(), ui)
    }

    @Test fun findReadEditButNotDeleteOtherFolders() = runBlocking {
        val other = Conversation(title = "周报那次")
        val secret = Project(id = "sec", name = "私密", isolated = true)
        val store = Store(ctx)
        store.saveConversations(listOf(other))
        store.saveProjects(listOf(secret))
        File(Workspace.dir(ctx, other.id).apply { mkdirs() }, "周报.md").writeText("本周完成：A\n下周计划：B\n")
        File(Workspace.dir(ctx, "p_sec").apply { mkdirs() }, "工资.md").writeText("秘密")

        val s = session(Conversation(title = "新的"))
        val found = s.execute("search_my_data", """{"where":"files","query":"周报"}""")
        assertTrue(found, "@${other.id}/周报.md" in found && "在「周报那次」" in found)
        assertTrue("工资" !in s.execute("search_my_data", """{"where":"files"}"""))

        val ref = "@${other.id}/周报.md"
        assertTrue(s.execute("read_file", """{"name":"$ref"}""").contains("下周计划：B"))
        s.execute("edit_file", """{"name":"$ref","old_text":"下周计划：B","new_text":"下周计划：C"}""")
        assertEquals("本周完成：A\n下周计划：C\n", File(Workspace.dir(ctx, other.id), "周报.md").readText())

        assertTrue(s.execute("delete_file", """{"names":["$ref"]}""").contains("不能在这里删"))
        assertTrue(File(Workspace.dir(ctx, other.id), "周报.md").exists())
        assertTrue(s.execute("read_file", """{"name":"@p_sec/工资.md"}""").contains("找不到"))
    }
}
