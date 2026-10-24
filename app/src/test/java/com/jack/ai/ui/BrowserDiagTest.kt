package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDiagTest {
    @Test
    fun logDropsRepeatsAndKeepsNewest() {
        val log = BrowserDiag.Log()
        log.add("去广告拦下请求 a.com", now = 0)
        log.add("去广告拦下请求 a.com", now = 1000)
        log.add("点击 button「登录」", now = 2000)
        assertEquals(2, log.lines().size)
        repeat(BrowserDiag.MAX_LINES + 10) { log.add("请求 403 /x$it") }
        assertEquals(BrowserDiag.MAX_LINES, log.lines().size)
        assertTrue(log.lines().last().endsWith("/x${BrowserDiag.MAX_LINES + 9}"))
    }

    @Test
    fun reportHasAllSections() {
        val r = BrowserDiag.report(listOf("App 1.1.15"), "网址 https://chatgpt.com/", listOf("12:00:00 点击 button「登录」"), recording = true)
        assertTrue(r.contains("App 1.1.15"))
        assertTrue(r.contains("【网页现在】\n网址 https://chatgpt.com/"))
        assertTrue(r.contains("正在记录"))
        assertTrue(r.contains("点击 button「登录」"))
        assertTrue(BrowserDiag.report(emptyList(), "", emptyList(), false).contains("（没有）"))
    }
}
