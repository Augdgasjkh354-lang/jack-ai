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
        RunLog.step(ctx, run, RunLog.stepOf("web_search", "[1] 标题 …正文很长不该被记下来…"))
        RunLog.step(ctx, run, RunLog.stepOf("github", "已推送到 me/app 的 main 分支（1 个文件，提交 abc1234）：https://…\n要看自动打包的结果用 runs"))
        RunLog.step(ctx, run, RunLog.stepOf("send_email", "工具调用失败：连不上 Jack 专用代理。下一步：…"))
        RunLog.finish(ctx, run, "ok", "今天的 5 条新闻……")
        val r = RunLog.of(ctx, task.id).single()
        assertEquals("ok", r.status); assertEquals("完成", r.statusText())
        assertEquals(listOf(true, true, false), r.steps.map { it.ok })
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
        assertEquals("第 24 次", list.first().result)
    }
}
