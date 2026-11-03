package com.jack.ai.ui

import com.jack.ai.data.SearchRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/** 长任务执行记录的总览行。 */
class ToolDigestTest {
    @Test
    fun summarizesLongRuns() {
        val t0 = 1_000_000_000_000L
        val steps = List(8) { SearchRecord("浏览器：点「下一页」", at = t0 + it * 60_000L) } +
            List(3) { SearchRecord("生成 PPT：报告.pptx", at = t0 + 9 * 60_000L) } +
            SearchRecord("操作手机：点 发送", error = "没点成", at = t0 + 80 * 60_000L) +
            SearchRecord("搜索：天气", error = "超时", at = t0 + 83 * 60_000L)
        assertEquals("用时 1 小时 23 分 · 浏览器 8 · 文件 3 · 手机 1 · 出错 2", ToolDigest.line(steps))
    }

    @Test
    fun shortRunsHaveNoDigest() {
        assertEquals("", ToolDigest.line(List(9) { SearchRecord("搜索：x", at = 1) }))
    }
}
