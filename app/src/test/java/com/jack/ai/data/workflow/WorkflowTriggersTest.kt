package com.jack.ai.data.workflow

import android.app.Application
import com.jack.ai.data.Routine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 第 6 步：保存时授权、监控、收到通知、分享、定时任务升级。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkflowTriggersTest {
    private val ctx = RuntimeEnvironment.getApplication().also {
        runCatching { androidx.work.WorkManager.initialize(it, androidx.work.Configuration.Builder().build()) }
    }

    private fun mail(to: String) = WfStep(id = "m", type = "tool", tool = "send_email", args = buildJsonObject { put("to", to); put("subject", "日报") })

    @Test fun grantsFollowTheScope() {
        val w = Workflow(name = "x", steps = listOf(WfStep(id = "a", type = "set", value = JsonPrimitive(1)), mail("boss@a.com")))
        assertEquals(listOf("给 boss@a.com 发邮件"), WfGrants.needed(w).map { it.label })
        assertEquals(1, WfGrants.missing(w).size)
        val ok = WfGrants.apply(w, approved = true)
        assertTrue(WfGrants.missing(ok).isEmpty())
        assertTrue(WfGrants.allowed(ok, mail("boss@a.com")))
        // 换了收件人：授权对不上，要重新同意
        val changed = ok.copy(steps = listOf(mail("other@b.com")))
        assertFalse(WfGrants.allowed(changed, mail("other@b.com")))
        assertTrue(WfGrants.apply(changed, approved = false).grants.isEmpty())
        assertTrue(WfGrants.label("send_email", buildJsonObject { put("to", "{{邮箱}}") }).contains("每次跑时"))
        assertTrue(WfGrants.NEVER.containsMatchIn("确认支付 99 元"))
    }

    @Test fun backgroundRunUsesTheGrant() = runBlocking {
        val base = Workflow(id = "g1", name = "清理", steps = listOf(
            WfStep(id = "f", type = "file", file = "旧.txt", text = "x"),
            WfStep(id = "d", type = "tool", tool = "delete_file", args = buildJsonObject { put("name", "旧.txt") })))
        val no = Workflows.run(ctx, base, "schedule")
        assertEquals("failed", no.status)
        assertTrue(no.error, no.error.contains("保存时同意授权"))
        val r = Workflows.run(ctx, WfGrants.apply(base, approved = true), "schedule")
        assertEquals(r.error, "ok", r.status)
        assertFalse(File(Workflows.filesDir(ctx, "g1"), "旧.txt").exists())
    }

    @Test fun notificationTriggerMatches() {
        val t = WfTrigger("notify", app = "淘宝", keyword = "快递, 取件")
        assertTrue(Workflows.matches(t, "com.taobao.taobao", "淘宝", "物流", "您的快递已到驿站"))
        assertFalse(Workflows.matches(t, "com.taobao.taobao", "淘宝", "活动", "双十一"))
        assertFalse(Workflows.matches(t, "com.tencent.mm", "微信", "", "快递"))
        assertTrue(Workflows.matches(WfTrigger("notify", keyword = "验证码"), "x", "短信", "", "验证码 1234"))
        assertTrue(WorkflowFormat.check(Workflow(name = "x", trigger = WfTrigger("notify"), steps = listOf(WfStep(type = "stop")))).any { it.contains("app") })
        assertEquals("收到「淘宝」的通知、里面有「快递, 取件」时", WorkflowFormat.describe(t))
        assertEquals("从别的 App 分享过来时", WorkflowFormat.describe(WfTrigger("share")))
    }

    @Test fun notificationEnqueuesOncePerMinute() {
        Workflows.save(ctx, Workflow(id = "n1", name = "快递", trigger = WfTrigger("notify", keyword = "快递"), steps = listOf(WfStep(type = "stop"))))
        Workflows.onNotification(ctx, "com.x", "X", "", "快递到了", now = 1_000_000)
        val first = Workflows.get(ctx, "n1")!!.lastFiredAt
        assertEquals(1_000_000L, first)
        Workflows.onNotification(ctx, "com.x", "X", "", "快递到了", now = 1_030_000)
        assertEquals(first, Workflows.get(ctx, "n1")!!.lastFiredAt)
        // 自己的通知不算
        Workflows.onNotification(ctx, ctx.packageName, "Kavi", "", "快递到了", now = 2_000_000)
        assertEquals(first, Workflows.get(ctx, "n1")!!.lastFiredAt)
    }

    @Test fun watchStopsAfterAHit() = runBlocking {
        val w = Workflows.save(ctx, Workflow(id = "w9", name = "等降价", trigger = WfTrigger("schedule", "watch", everyMin = 30),
            steps = listOf(WfStep(id = "a", type = "set", name = "价", value = JsonPrimitive(90)),
                WfStep(id = "b", type = "if", cond = WfCond("{{价}}", ">", "100"), then = listOf(WfStep(id = "c", type = "stop", reason = "还贵"))))))
        assertTrue(WorkflowFormat.nextAt(w, 0) != null)
        Workflows.run(ctx, w, "schedule")
        assertFalse(Workflows.get(ctx, "w9")!!.enabled)
        assertEquals("监控：每 30 分钟查一次，满足就停", WorkflowFormat.describe(w.trigger))
    }

    @Test fun routinesUpgrade() {
        val task = WorkflowUpgrade.from(Routine(title = "AI 早报", kind = "task", prompt = "整理 AI 新闻", hour = 8, minute = 5, repeat = "weekdays"))
        assertEquals(WfTrigger("schedule", "weekdays", "08:05"), task.trigger)
        assertEquals(listOf("ai", "message"), task.steps.map { it.type })
        val remind = WorkflowUpgrade.from(Routine(title = "喝水", hour = 15, minute = 0, repeat = "weekly", date = "2026-11-25"))
        assertEquals(listOf(3), remind.trigger.days)
        val watch = WorkflowUpgrade.from(Routine(title = "盯票", kind = "watch", prompt = "12306 有没有票", hour = 0, minute = 0, everyMin = 20, then = "帮我记下来"))
        assertEquals("watch", watch.trigger.repeat)
        listOf(task, remind, watch).forEach { assertTrue(WorkflowFormat.check(it).toString(), WorkflowFormat.check(it).isEmpty()) }
    }
}
