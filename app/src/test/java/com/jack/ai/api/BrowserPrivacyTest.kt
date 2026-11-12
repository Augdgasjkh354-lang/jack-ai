package com.jack.ai.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拦跟踪器、只用加密连接、网页脚本。 */
class BrowserPrivacyTest {
    @Test
    fun trackers() {
        assertTrue(BrowserAds.isTracker("static.hotjar.com"))
        assertTrue(BrowserAds.isTracker("www.clarity.ms"))
        assertTrue(BrowserAds.isTracker("api.growingio.com"))
        // 登录、防刷会用到的不拦
        assertFalse(BrowserAds.isTracker("connect.facebook.net"))
        assertFalse(BrowserAds.isTracker("g.alicdn.com"))
        assertFalse(BrowserAds.isTracker("hotjar.com.evil.cn"))
    }

    @Test
    fun httpsUpgrade() {
        assertEquals("https://example.com/a?b=1", BrowserNet.httpsOf("http://example.com/a?b=1"))
        assertNull(BrowserNet.httpsOf("https://example.com"))
        assertEquals("http://example.com/x", BrowserNet.httpOf("https://example.com/x"))
    }

    @Test
    fun scriptSites() {
        assertEquals(listOf("taobao.com", "jd.com"), UserScripts.cleanSites("https://www.taobao.com/abc, *.jd.com"))
        assertEquals(listOf(UserScripts.ALL), UserScripts.cleanSites("*"))
        assertEquals(listOf(UserScripts.ALL), UserScripts.cleanSites("*://*/*"))
        val s = UserScripts.Script("a", "t", listOf("taobao.com"), "x")
        assertTrue(UserScripts.matches(s, "item.taobao.com"))
        assertTrue(UserScripts.matches(s, "www.taobao.com"))
        assertFalse(UserScripts.matches(s, "nottaobao.com"))
        assertFalse(UserScripts.matches(s.copy(enabled = false), "taobao.com"))
        assertTrue(UserScripts.matches(s.copy(sites = listOf("*")), "anything.org"))
    }

    @Test
    fun tampermonkeyHeader() {
        val code = """
            // ==UserScript==
            // @name         Expand all
            // @name:zh-CN   自动展开全文
            // @match        *://*.zhihu.com/*
            // @include      https://www.jianshu.com/p/*
            // @grant        GM_addStyle
            // ==/UserScript==
            GM_addStyle('.x{display:none}');
        """.trimIndent()
        val (name, sites) = UserScripts.header(code)!!
        assertEquals("自动展开全文", name)
        assertEquals(listOf("zhihu.com", "jianshu.com"), sites)
        assertNull(UserScripts.header("console.log(1)"))
    }

    @Test
    fun wrappedScriptRunsOnceAndSurvivesErrors() {
        val cx = org.mozilla.javascript.Context.enter()
        try {
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, """
                var window = this, errors = [], runs = 0, store = {};
                var console = { log: function () {}, error: function (m) { errors.push(m); } };
                var localStorage = { getItem: function (k) { return k in store ? store[k] : null; }, setItem: function (k, v) { store[k] = String(v); }, removeItem: function (k) { delete store[k]; } };
            """.trimIndent(), "env", 1, null)
            val ok = UserScripts.wrap(UserScripts.Script("s1", "计数", listOf("*"), "runs++; GM_setValue('n', GM_getValue('n', 0) + 1);"))
            cx.evaluateString(scope, ok, "a", 1, null)
            cx.evaluateString(scope, ok, "b", 1, null)
            cx.evaluateString(scope, UserScripts.wrap(UserScripts.Script("s2", "坏的", listOf("*"), "nope();")), "c", 1, null)
            val out = cx.evaluateString(scope, "[runs, store['kaviUS:n'], errors.length, errors[0].indexOf('坏的') >= 0].join(',')", "d", 1, null).toString()
            assertEquals("1,1,1,true", out)
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }
}
