package com.jack.ai.tools

import com.jack.ai.api.Net
import com.jack.ai.data.ProxyNode
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/** 报错要说清楚为什么失败、下一步做什么。 */
class ErrorTextTest {
    @After fun reset() = Net.setConfig(Net.Config())

    @Test
    fun foreignServiceWithoutProxySaysTurnItOn() {
        Net.setConfig(Net.Config())
        val t = ToolSession.errorText(java.net.UnknownHostException("api.notion.com"))
        assertTrue(t, t.contains("找不到服务器 api.notion.com") && t.contains("打开 Kavi 专用代理"))
        val d = ToolSession.errorText(java.net.UnknownHostException("api.deepseek.com"))
        assertTrue(d, d.contains("检查手机网络") && !d.contains("打开 Kavi 专用代理"))
    }

    @Test
    fun deadProxyPortIsNamed() {
        Net.setConfig(Net.Config(enabled = true, nodes = listOf(ProxyNode("a", "ss", "{}"))))
        val t = ToolSession.errorText(java.net.ConnectException("Failed to connect to /127.0.0.1:9"))
        assertTrue(t, t.contains("连不上 Kavi 专用代理") && t.contains("点「测试」"))
        val timeout = ToolSession.errorText(java.net.SocketTimeoutException("timeout"))
        assertTrue(timeout, timeout.contains("超时") && timeout.contains("换节点"))
    }

    @Test
    fun permissionsAndFiles() {
        assertTrue(ToolSession.errorText(SecurityException("Permission Denial: calendar")).contains("提醒和任务"))
        assertTrue(ToolSession.errorText(java.io.FileNotFoundException("报告.docx")).contains("对话文件夹"))
        assertTrue(ToolSession.errorText(IllegalArgumentException("要写 text")) == "要写 text")
    }
}
