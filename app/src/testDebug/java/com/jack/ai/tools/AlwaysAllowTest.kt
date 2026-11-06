package com.jack.ai.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jack.ai.data.Conversation
import com.jack.ai.data.InjectionGuard
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import com.jack.ai.data.Workspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 「以后都允许」：点过的那一类直接做、只报一句；严格的照样问。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlwaysAllowTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val asked = mutableListOf<String>()
    private val notices = mutableListOf<String>()
    private val ui = object : ToolUi {
        override fun started(label: String) {}
        override fun finished(label: String, record: SearchRecord) {}
        override fun attach(file: File) {}
        override suspend fun confirm(title: String, body: String, action: String): Boolean { asked += title; return false }
        override fun notice(text: String) { notices += text }
    }

    @Test fun allowedKindSkipsTheQuestionButStrictStillAsks() = runBlocking {
        Store(ctx).saveSettings(Settings(apiKey = "k", alwaysAllow = listOf(com.jack.ai.data.AllowRule("phone:com.tencent.mm:发送", "在「微信」里点「发送」"))))
        val s = ToolSession(BackgroundToolHost(ctx), Settings(apiKey = "k"), Conversation(id = "allow"), null, emptyList(), ui)
        assertTrue(s.approve("发？", "", "点", kind = "phone:com.tencent.mm:发送" to "在「微信」里点「发送」"))
        assertTrue(asked.isEmpty() && notices.single().contains("以后都允许"))
        // 别的一类、严格的：照样问
        assertFalse(s.approve("删？", "", "点", kind = "phone:com.tencent.mm:删除" to "在「微信」里点「删除」"))
        assertFalse(s.approve("发？", "", "点", strict = true, kind = "phone:com.tencent.mm:发送" to "在「微信」里点「发送」"))
        assertEquals(2, asked.size)
    }
}
