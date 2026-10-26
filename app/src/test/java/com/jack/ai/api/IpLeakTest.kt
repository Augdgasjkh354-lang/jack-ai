package com.jack.ai.api

import com.jack.ai.ui.JackBrowser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpLeakTest {
    @Test
    fun forcedSitesLeaveTheDirectList() {
        val all = BrowserNet.bypassRules()
        assertTrue("taobao.com" in all && "*.taobao.com" in all)
        val forced = BrowserNet.bypassRules(setOf("taobao.com"))
        assertFalse("taobao.com" in forced); assertFalse("*.taobao.com" in forced)
        // 子域名设了总走代理：整个网站都不直连
        assertFalse("*.zhihu.com" in BrowserNet.bypassRules(setOf("www.zhihu.com")))
        // 别的照旧
        assertTrue("*.jd.com" in forced && "*.cn" in forced)
        // 只靠 .cn 后缀直连的没法单独走代理（12306.cn 虽然单列了，也会被 *.cn 接住）
        assertFalse(BrowserNet.canForceProxy("12306.cn")); assertFalse(BrowserNet.canForceProxy("example.cn"))
        assertTrue(BrowserNet.canForceProxy("taobao.com")); assertTrue(BrowserNet.canForceProxy("github.com"))
    }

    @Test
    fun webrtcIsForcedToRelay() {
        val cx = org.mozilla.javascript.Context.enter()
        try {
            val scope = cx.initStandardObjects()
            // 假的浏览器环境：记下网页建连接时实际用的配置
            cx.evaluateString(scope, """
                var window = this; var seen = [];
                function RTCPeerConnection(c) { seen.push(c); }
                RTCPeerConnection.prototype.setConfiguration = function (c) { seen.push(c); };
            """.trimIndent(), "env", 1, null)
            cx.evaluateString(scope, JackBrowser.RTC_JS, "rtc", 1, null)
            val out = cx.evaluateString(scope, """
                var pc = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] });
                pc.setConfiguration({ iceTransportPolicy: 'all' });
                var none = new RTCPeerConnection();
                [seen[0].iceTransportPolicy, seen[0].iceServers.length, seen[1].iceTransportPolicy, seen[2].iceTransportPolicy, pc instanceof RTCPeerConnection].join(',')
            """.trimIndent(), "use", 1, null).toString()
            assertEquals("relay,1,relay,relay,true", out)
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }
}
