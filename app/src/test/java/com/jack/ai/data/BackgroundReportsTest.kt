package com.jack.ai.data

import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundReportsTest {
    /** 给模型看的话要说清：这是系统通知、文件在哪、接下来做什么。 */
    @Test
    fun doneTextTellsWhereAndWhatNext() {
        val t = BackgroundReports.doneText("报告.pdf", 3L * 1024 * 1024, "对话文件夹", "https://example.com/a.pdf")
        assertTrue(t.contains("不是用户说的话"))
        assertTrue(t.contains("「报告.pdf」"))
        assertTrue(t.contains("对话文件夹"))
        assertTrue(t.contains("接着做"))
    }

    @Test
    fun failedTextCarriesReason() {
        val t = BackgroundReports.failedText("https://example.com/a.zip", "连接中断")
        assertTrue(t.contains("连接中断"))
        assertTrue(t.contains("不要原样再试"))
    }
}
