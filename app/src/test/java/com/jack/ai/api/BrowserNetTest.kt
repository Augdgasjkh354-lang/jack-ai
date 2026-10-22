package com.jack.ai.api

import com.jack.ai.api.BrowserNet.Kind
import com.jack.ai.data.ProxyCore
import com.jack.ai.data.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNetTest {
    private val node = ProxyNode("香港", "shadowsocks", "{}")
    private val on = Net.Config(enabled = true, nodes = listOf(node))
    private val off = Net.Config()

    @Test
    fun planFollowsRouteAndFailsClosed() {
        assertEquals(BrowserNet.Plan.Direct, BrowserNet.plan(BrowserNet.DIRECT, on) { error("不该启动内核") })
        // 代理没开：自动 = 直连；全部走代理 = 不打开
        assertEquals(BrowserNet.Plan.Direct, BrowserNet.plan(BrowserNet.AUTO, off) { error("不该启动内核") })
        assertTrue(BrowserNet.plan(BrowserNet.PROXY, off) { true } is BrowserNet.Plan.Blocked)
        // 内核起不来：不直连
        assertTrue(BrowserNet.plan(BrowserNet.AUTO, on) { false } is BrowserNet.Plan.Blocked)
        val auto = BrowserNet.plan(BrowserNet.AUTO, on) { true } as BrowserNet.Plan.Proxy
        assertTrue("*.cn" in auto.bypass && "deepseek.com" in auto.bypass && "*.deepseek.com" in auto.bypass)
        val all = BrowserNet.plan(BrowserNet.PROXY, on) { true } as BrowserNet.Plan.Proxy
        assertTrue(all.bypass.isEmpty())
    }

    @Test
    fun addressBar() {
        assertEquals("https://github.com", BrowserNet.normalize("github.com", true))
        assertEquals("https://zh.wikipedia.org/wiki/安卓", BrowserNet.normalize("zh.wikipedia.org/wiki/安卓", true))
        assertEquals("http://example.com/a", BrowserNet.normalize(" http://example.com/a ", false))
        assertTrue(BrowserNet.normalize("kotlin 协程", true).startsWith("https://www.google.com/search?q="))
        assertTrue(BrowserNet.normalize("天气", false).startsWith("https://cn.bing.com/search?q="))
        assertEquals("", BrowserNet.normalize("  ", true))
    }

    @Test
    fun localAndOddSchemesAreBlocked() {
        assertEquals(Kind.OPEN, BrowserNet.classify("https://www.notion.so/x"))
        listOf(
            "http://127.0.0.1:8080/", "http://localhost/", "http://192.168.1.1/", "http://10.0.0.2/", "http://172.20.1.1/",
            "http://169.254.169.254/latest", "http://[::1]:9/", "http://printer.local/", "file:///sdcard/a.txt",
            "content://com.jack.ai.files/x", "javascript:alert(1)", "data:text/html,hi",
        ).forEach { assertEquals(it, Kind.BLOCKED, BrowserNet.classify(it)) }
        assertEquals(Kind.EXTERNAL, BrowserNet.classify("mailto:a@b.com"))
        assertEquals(Kind.EXTERNAL, BrowserNet.classify("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(BrowserNet.isLocal("172.32.0.1"))
        assertFalse(BrowserNet.isLocal("github.com"))
    }

    @Test
    fun proxyPasswordOnlyGoesToOurProxy() {
        // Chromium 实测读出来的 realm
        assertTrue(BrowserNet.isOurProxy("127.0.0.1", "sing-box\" charset=\"UTF-8"))
        assertTrue(BrowserNet.isOurProxy("127.0.0.1:${ProxyCore.port}", "sing-box"))
        assertFalse(BrowserNet.isOurProxy("evil.com", "sing-box"))
        assertFalse(BrowserNet.isOurProxy("127.0.0.1", "Login"))
        assertFalse(BrowserNet.isOurProxy("127.0.0.1:${ProxyCore.port + 1}", "sing-box"))
    }

    @Test
    fun whatNeedsTheUsersOk() {
        // 下单、付款、发送、删除：点了就要问
        listOf("立即购买", "确认支付", "发送", "Delete repository", "Place order", "提交订单").forEach {
            assertTrue(it, BrowserNet.confirmReason("click", it, false, false, false, "") != null)
        }
        // 普通链接和按钮不问
        listOf("下一页", "Sign in", "Trending", "搜索", "展开").forEach {
            assertEquals(it, null, BrowserNet.confirmReason("click", it, false, false, false, ""))
        }
        // 提交 POST 表单要问；登录表单（有密码框）不问；GET 搜索框回车不问
        assertTrue(BrowserNet.confirmReason("click", "继续", true, true, false, "继续") != null)
        assertEquals(null, BrowserNet.confirmReason("click", "登录", true, true, true, "登录"))
        assertTrue(BrowserNet.confirmReason("key", "留言", false, true, false, "发表", "enter") != null)
        assertEquals(null, BrowserNet.confirmReason("key", "搜点什么", false, false, false, "搜索", "enter"))
        assertEquals(null, BrowserNet.confirmReason("type", "发表评论", false, true, false, "发表"))
    }
}
