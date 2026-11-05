package com.jack.ai.data

import android.app.Application
import com.jack.ai.tools.BackgroundToolHost
import com.jack.ai.tools.ToolSession
import com.jack.ai.tools.ToolUi
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 思路来自 deepseek-ai/deepseek-harness packages/goal/goal/，MIT。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GoalTest {
    @Test fun statusChangesKeepAcceptanceAndProgress() {
        val g = Goal("整理报价", listOf("列出三家报价"))
            .update("paused", "等资料").update("active", "资料已齐").update("blocked", "需要登录").update("complete", "验收通过")
        assertEquals("complete", g.status); assertEquals(4, g.progress.size)
        assertEquals(listOf("列出三家报价"), g.criteria)
    }
    @Test fun conversationSurvivesSerializationWithGoal() {
        val c = Conversation(goal = Goal("整理邮件", listOf("读完十封邮件"), autoRounds = 3))
        // 时间戳默认值在编码时和默认值相同会被省掉，解码时按新的时间补，跨过一毫秒就对不上：全写进去再比
        val all = Json { encodeDefaults = true }
        assertEquals(c, all.decodeFromString(Conversation.serializer(), all.encodeToString(Conversation.serializer(), c)))
        assertNull(Json.decodeFromString(Conversation.serializer(), "{}").goal)
    }
    @Test fun staleConversationCannotRestoreClearedGoal() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = Store(ctx)
        val c = Conversation(id = "goal-test", messages = listOf(ChatMessage(role = "user", content = "整理报价")))
        store.saveConversations(listOf(c))
        store.changeGoal(c.id, null) { Goal("整理报价", listOf("三家报价")) }
        store.saveConversations(listOf(c))
        val saved = Store(ctx).loadConversations().single()
        assertNotNull(saved.goal)
        store.changeGoal(c.id, null) { null }
        store.saveConversations(listOf(saved))
        assertNull(Store(ctx).loadConversations().single().goal)
    }
    @Test fun continuationStopsAtFiveOrBudget() {
        assertTrue(Goal("整理报价", listOf("三家报价"), autoRounds = 4).canContinue)
        assertFalse(Goal("整理报价", listOf("三家报价"), autoRounds = 5).canContinue)
        assertFalse(Goal("整理报价", listOf("三家报价"), status = "paused").canContinue)
        assertTrue(Goal.budgetReached(10.0, 10.0)); assertFalse(Goal.budgetReached(0.0, 100.0))
    }
    @Test fun automaticGoalDoesNotBypassConfirmationUnderFullAuthorization() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        Store(ctx).saveSettings(Settings(fullAuthUntil = Long.MAX_VALUE))
        var confirmations = 0
        val ui = object : ToolUi {
            override fun started(label: String) {}
            override fun finished(label: String, record: SearchRecord) {}
            override fun attach(file: File) {}
            override suspend fun confirm(title: String, body: String, action: String): Boolean { confirmations++; return false }
        }
        val session = ToolSession(BackgroundToolHost(ctx), Settings(), Conversation(), null, emptyList(), ui)
        session.goalRun = true
        assertFalse(session.approve("发送邮件", "需要用户确认", "发送"))
        assertEquals(1, confirmations)
        session.goalRun = false
        assertTrue(session.approve("发送邮件", "普通调用沿用已有授权", "发送"))
        assertEquals(1, confirmations)
    }

}
