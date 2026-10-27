package com.jack.ai.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.FreeBudget
import com.jack.ai.data.FreeState
import com.jack.ai.data.FreeTime
import com.jack.ai.data.Reader
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
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

/** 自由时间的工具：心愿和想买的东西、用户回了以后的账；结束对话的门槛。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeTimeToolTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var ended = ""
    private val ui = object : ToolUi {
        override fun started(label: String) {}
        override fun finished(label: String, record: SearchRecord) {}
        override fun attach(file: File) {}
        override suspend fun confirm(title: String, body: String, action: String) = true
        override fun endConversation(reason: String) { ended = reason }
    }

    @Before fun clean() {
        File(ctx.filesDir, "free_state.json").delete(); FreeTime.save(ctx, FreeState())
        // 提心愿会排一个通知任务：测试里先把 WorkManager 准备好
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
    }

    private fun freeSession(savings: Double = 1.0) = ToolSession(BackgroundToolHost(ctx), Settings(apiKey = "k", userName = "Jack"),
        Conversation(id = Reader.CONV_ID, work = true), null, emptyList(), ui).apply {
        interactive = false
        freeBudget = FreeBudget(0.5, 0.1, savings) { "系统：用完了" }
    }

    @Test
    fun wishesAndPurchasesWaitForTheUser() = runBlocking {
        val s = freeSession()
        assertTrue(s.execute("free_time", """{"action":"wish","text":"想做一个记录星空的小网站","amount":2}""").contains("告诉"))
        // 想买的东西：链接要是这次真打开过的；愿意出的钱不能超过积蓄
        assertTrue(s.execute("free_time", """{"action":"buy","text":"一本讲宇宙的电子书","price":25,"url":"https://shop.example.com/book"}""").contains("没打开过"))
        s.seenUrls += "https://shop.example.com/book"
        s.execute("free_time", """{"action":"buy","text":"一本讲宇宙的电子书","price":25,"url":"https://shop.example.com/book","offer":5}""")
        val st = FreeTime.load(ctx)
        assertEquals(2, st.wishes.size)
        assertEquals(1.0, st.wishes.last().offer, 1e-9)  // 积蓄只有 1 元
        assertTrue(FreeTime.latelyNote(ctx).contains("Waiting for an answer"))
        // 用户回了：给预算进积蓄（不受上限），买了从积蓄扣它愿意出的
        var after = FreeTime.answer(st, st.wishes[0].id, "approved")
        after = FreeTime.answer(after, st.wishes[1].id, "bought")
        assertEquals(st.savings + 2.0 - 1.0, after.savings, 1e-9)
        val (news, told) = FreeTime.news(after)
        assertTrue(news.contains("said yes") && news.contains("bought"))
        assertTrue(told.wishes.all { it.told })
        assertEquals("", FreeTime.news(told).first)
    }

    @Test
    fun endingAConversationNeedsSomeHistory() = runBlocking {
        fun chat(n: Int) = ToolSession(BackgroundToolHost(ctx), Settings(apiKey = "k"),
            Conversation(messages = List(n) { ChatMessage(role = "user", content = "第 $it 句") }), null, emptyList(), ui)
        assertTrue(chat(1).execute("end_conversation", """{"reason":"持续辱骂"}""").contains("刚开始"))
        assertEquals("", ended)
        val s = chat(4)
        s.execute("end_conversation", """{"reason":"持续辱骂"}""")
        assertEquals("持续辱骂", ended)
        assertEquals(0, s.maxRounds)
        // 后台没有人对话：不给用
        assertFalse(freeSession().execute("end_conversation", """{"reason":"x"}""").contains("已经结束"))
    }
}
