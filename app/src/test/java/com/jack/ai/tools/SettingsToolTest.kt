package com.jack.ai.tools

import android.app.Application
import com.jack.ai.data.Conversation
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Store
import com.jack.ai.data.ThemeMode
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

/** Kavi 改设置：白名单里的直接改，会花钱的先问，Key 这类碰不到。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsToolTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private var answer = true
    private var asked = 0
    private val notices = mutableListOf<String>()

    private fun session(): ToolSession {
        val ui = object : ToolUi {
            override fun started(label: String) {}
            override fun finished(label: String, record: SearchRecord) {}
            override fun attach(file: File) {}
            override suspend fun confirm(title: String, body: String, action: String): Boolean { asked++; return answer }
            override fun notice(text: String) { notices += text }
        }
        val settings = Store(ctx).loadSettings()
        return ToolSession(BackgroundToolHost(ctx), settings, Conversation(), null, settings.allSkills(), ui)
    }

    @Test fun changesSafeSettingsAndAsksBeforeCostlyOnes() = runBlocking {
        val s = session()
        assertTrue(s.execute("settings", """{"action":"list"}""").contains("tab_bar｜"))
        val r = s.execute("settings", """{"action":"set","changes":{"theme":"深色","font_scale":"1.2","tab_bar":"games, memory","apiKey":"x"}}""")
        val now = Store(ctx).loadSettings()
        assertEquals(ThemeMode.DARK, now.themeMode)
        assertEquals(1.2f, now.fontScale, 0.001f)
        assertEquals(listOf("games", "memory"), now.tabBar)
        assertEquals("", now.apiKey)
        assertTrue(r, "「apiKey」不能改" in r)
        assertEquals(0, asked)
        assertTrue(notices.single().startsWith("Kavi 改了设置"))

        // 关记忆要先问；用户说不就不改
        answer = false
        assertTrue(s.execute("settings", """{"action":"set","changes":{"memory":"false"}}""").contains("没同意"))
        assertEquals(1, asked)
        assertTrue(Store(ctx).loadSettings().memoryEnabled)
        assertTrue(s.execute("settings", """{"action":"set","changes":{"font_scale":"3"}}""").contains("0.85 到 1.3"))
    }
}
