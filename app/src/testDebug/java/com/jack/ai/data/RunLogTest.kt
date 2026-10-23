package com.jack.ai.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunLogTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val task = Routine(kind = "task", title = "AI 早报", hour = 8, minute = 0, repeat = "daily")

    @Before fun clean() { File(ctx.filesDir, "runlogs.json").delete() }

    @Test
    fun aRunRecordsStepsAndOutcome() {
        val run = RunLog.start(ctx, task)
        RunLog.step(ctx, run, RunLog.stepOf("web_search", OK, "[1] 标题 …正文很长不该被记下来…"))
        RunLog.step(ctx, run, RunLog.stepOf("github", OK, "已推送到 me/app 的 main 分支（1 个文件，提交 abc1234）：https://…\n要看自动打包的结果用 runs"))
        RunLog.step(ctx, run, RunLog.stepOf("send_email", FAILED, "工具调用失败：连不上 Jack 专用代理。下一步：…"))
        RunLog.step(ctx, run, RunLog.stepOf("send_email", REJECTED, "用户没有同意"))
        RunLog.finish(ctx, run, "ok")
        val r = RunLog.of(ctx, task.id).single()
        assertEquals("ok", r.status); assertEquals("完成", r.statusText())
        assertEquals(listOf(true, true, false, false), r.steps.map { it.ok })
        assertTrue(r.steps[3].rejected)
        // 不记正文，只记编号、提交号这类，以及失败原因
        assertEquals("", r.steps[0].note)
        assertTrue(r.steps[1].note, r.steps[1].note.contains("提交 abc1234"))
        assertTrue(r.steps[2].note, r.steps[2].note.startsWith("连不上 Jack 专用代理"))
        assertTrue(RunLog.lastText(ctx, task.id)!!.contains("完成"))
    }

    @Test
    fun failuresAndInterruptionsAreVisible() {
        val run = RunLog.start(ctx, task)
        RunLog.finish(ctx, run, "failed", error = "网络连接超时。下一步：稍后重试", retry = true)
        val r = RunLog.of(ctx, task.id).single()
        assertEquals("失败（会自动再试）", r.statusText())
        assertTrue(RunLog.lastText(ctx, task.id)!!.contains("网络连接超时"))
        // 一直停在「正在跑」超过半小时：当成被系统打断
        val stuck = RunRecord(routineId = "x", title = "t", kind = "task", startedAt = System.currentTimeMillis() - 3_600_000)
        assertTrue(stuck.interrupted()); assertTrue(stuck.statusText().contains("打断"))
        assertFalse(stuck.copy(startedAt = System.currentTimeMillis()).interrupted())
    }

    @Test
    fun keepsTheLatestTwentyPerRoutine() {
        repeat(25) { RunLog.once(ctx, task, "not_met", "第 $it 次") }
        val list = RunLog.of(ctx, task.id)
        assertEquals(20, list.size)
        assertEquals("第 24 次", list.first().note)
    }

    @Test
    fun totalCapDropsTheOldestNotTheNewest() {
        // 16 个提醒各跑 20 次 = 320 次，超过总上限 300
        val routines = (1..16).map { Routine(kind = "task", title = "任务$it", hour = 8, minute = 0, repeat = "daily") }
        for (round in 0 until 20) routines.forEach { RunLog.once(ctx, it, "ok", "第 $round 轮") }
        // 最后再跑一次的，一定要留下
        val last = Routine(kind = "task", title = "最新", hour = 9, minute = 0)
        RunLog.once(ctx, last, "ok", "最新的一次")
        val all = RunLog.all(ctx)
        assertEquals(300, all.size)
        assertEquals("最新的一次", RunLog.of(ctx, last.id).single().note)
        assertTrue(all.groupBy { it.routineId }.values.all { it.size <= 20 })
        // 丢掉的是最早那几轮：每个提醒最新的「第 19 轮」都在
        routines.forEach { r -> assertEquals("第 19 轮", RunLog.of(ctx, r.id).first().note) }
        assertTrue(all.none { it.note == "第 0 轮" && it.routineId == routines.first().id })
    }

    @Test
    fun okIsDecidedByStatusNotByWording() {
        // 文字看着像失败，但代码说成了：算成功；反过来，文字看着正常，代码说失败：算失败
        assertTrue(RunLog.stepOf("notion_write", OK, "权限不足这个词出现在页面标题里").ok)
        assertFalse(RunLog.stepOf("notion_write", FAILED, "已写进 Notion").ok)
        assertEquals("写完读回核实没通过", RunLog.stepOf("notion_write", FAILED, "已写好" + Verify.note(listOf("标题对不上"), "")).note)
    }

    @Test
    fun newStatusesReadPlainly() {
        fun text(status: String, kind: String = "watch") = RunRecord(routineId = "x", title = "t", kind = kind, status = status).statusText()
        assertEquals("这次没查成，下次再查", text("unknown"))
        assertEquals("条件满足了，但后续步骤没做完", text("partial"))
        assertTrue(text("needs_setup", "task").contains("没配置好"))
    }

    @Test
    fun nothingPrivateReachesTheFile() {
        val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
        val mail = "Hi 张三，你的体检报告显示血压偏高，请于周五前回电 13812345678。"
        val link = "https://accounts.example.com/oauth/callback?code=4/0AbCdEf123456&state=xyz"
        val run = RunLog.start(ctx, task)
        // 成功的结果里有正文、令牌、链接：一样都不该留下，只留编号
        RunLog.step(ctx, run, RunLog.stepOf("read_email", OK, "编号 4821｜$mail\n令牌：$token\n$link"))
        // 失败原因里带着这些：脱敏后再存
        RunLog.step(ctx, run, RunLog.stepOf("github", FAILED, "工具调用失败：401 Bad credentials token=$token 回调 $link 联系 me@example.com"))
        RunLog.finish(ctx, run, "failed", error = "Authorization: Bearer $token；验证码：883412；回电 13812345678")
        val saved = File(ctx.filesDir, "runlogs.json").readText()
        listOf(token, "4/0AbCdEf123456", "code=4", "me@example.com", "体检报告", "13812345678", "883412", "血压").forEach {
            assertFalse("记录里不该有：$it\n$saved", it in saved)
        }
        val r = RunLog.of(ctx, task.id).single()
        assertEquals("编号 4821", r.steps[0].note)
        assertTrue(r.steps[1].note, r.steps[1].note.contains("401"))
        assertTrue(r.steps[1].note, r.steps[1].note.contains("https://accounts.example.com"))
    }

    @Test
    fun redactKeepsUsefulBits() {
        assertEquals("网络连接超时。下一步：稍后重试", Redact.text("网络连接超时。下一步：稍后重试"))
        assertEquals("HTTP 404 找不到 https://api.github.com/…", Redact.text("HTTP 404 找不到 https://api.github.com/repos/me/x?access_token=abc"))
        assertEquals("邮箱密码不对", Redact.text("邮箱密码不对"))
        assertEquals("密码[已隐去]", Redact.text("密码：hunter2"))
    }

    private val OK = com.jack.ai.tools.ToolStatus.OK
    private val FAILED = com.jack.ai.tools.ToolStatus.FAILED
    private val REJECTED = com.jack.ai.tools.ToolStatus.REJECTED
}
