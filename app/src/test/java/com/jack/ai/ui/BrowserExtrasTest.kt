package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtrasTest {
    @Test
    fun blockRulesPerSite() {
        var r = BrowserBlocks.add(emptyMap(), "jd.com", "#float-service")
        r = BrowserBlocks.add(r, "jd.com", "#float-service")
        r = BrowserBlocks.add(r, "jd.com", "div.recommend")
        assertEquals(listOf("#float-service", "div.recommend"), r["jd.com"])
        // 子域名也藏，别的网站不藏
        assertEquals(2, BrowserBlocks.forSite(r, "item.jd.com").size)
        assertTrue(BrowserBlocks.forSite(r, "taobao.com").isEmpty())
        r = BrowserBlocks.remove(r, "jd.com", "#float-service")
        r = BrowserBlocks.remove(r, "jd.com", "div.recommend")
        assertTrue(r.isEmpty())
        assertTrue(BrowserBlocks.injectJs(listOf("#a")).contains("#a{display:none!important}"))
    }

    @Test
    fun parsesPickedElement() {
        val p = BrowserBlocks.parsePick(BrowserBlocks.PREFIX + """{"selector":"div.ad","count":3,"label":"div「广告」 300×100"}""")!!
        assertEquals("div.ad", p.selector)
        assertEquals(3, p.count)
        assertNull(BrowserBlocks.parsePick("随便一行"))
        assertNull(BrowserBlocks.parsePick(BrowserBlocks.PREFIX + """{"selector":""}"""))
    }

    @Test
    fun teachStepsReadLikeInstructions() {
        fun step(json: String) = BrowserTeach.stepOf(BrowserTeach.PREFIX + json)
        assertEquals("点按钮「搜索」", step("""{"a":"click","k":"按钮","l":"搜索"}"""))
        assertEquals("在输入框「搜索商品」 输入「保温杯」", step("""{"a":"type","l":"搜索商品","v":"保温杯"}"""))
        assertEquals("在密码框「密码」 填密码（请用户自己填）", step("""{"a":"password","l":"密码"}"""))
        assertNull(step("""{"a":"type","l":"x","v":""}"""))
        var steps = BrowserTeach.append(emptyList(), "按回车")
        steps = BrowserTeach.append(steps, "按回车")
        assertEquals(1, steps.size)
        steps = BrowserTeach.append(listOf("在输入框「关键词」 输入「保温杯」", "按回车"), "在输入框「关键词」 输入「保温杯」")
        assertEquals(2, steps.size)
        val req = BrowserTeach.request("1688.com", listOf("打开 https://1688.com", "点按钮「搜索」"))
        assertTrue(req.contains("save_flow") && req.contains("1. 打开 https://1688.com") && req.contains("2. 点按钮「搜索」"))
    }
}
