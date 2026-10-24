package com.jack.ai.tools

import com.jack.ai.ui.JackBrowser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDiffTest {
    private fun item(n: Int, label: String, value: String? = null) =
        JackBrowser.Item(n, "按钮", label, value, null, emptyList(), null, 0f, 0f, 10f, 10f)

    private fun page(items: List<JackBrowser.Item>, text: String, url: String = "https://a.com/s") =
        JackBrowser.Page(url, "标题", items, 0, 0, 0, 2000, 800, text, 0f, 0f, 400f, null, null)

    private val base = page((1..20).map { item(it, "按钮$it") }, (1..30).joinToString("\n") { "第 $it 行商品介绍" })

    @Test
    fun samePageSendsOnlyWhatChanged() {
        val now = page(base.items.map { if (it.n == 3) item(3, "按钮3", value = "蓝牙耳机") else it } + item(21, "搜索结果"),
            base.text + "\n共找到 120 件")
        val out = describePage(now, base)
        assertTrue(out, out.contains("页面没跳转"))
        assertTrue(out.contains("[3] 按钮「按钮3」 内容：「蓝牙耳机」") && out.contains("[21] 按钮「搜索结果」"))
        assertFalse(out, out.contains("[5] 按钮"))
        assertTrue(out.contains("新出现的文字：\n共找到 120 件"))
        assertFalse(out.contains("第 7 行商品介绍"))
        // 比整页短得多
        assertTrue(out.length * 3 < describePage(now).length)
    }

    @Test
    fun nothingChangedSaysSo() {
        val out = describePage(base, base)
        assertTrue(out.contains("能操作的没有变化（这一屏共 20 个）") && out.contains("文字没有变化"))
    }

    @Test
    fun goneButtonsAreListed() {
        val now = page(base.items.filter { it.n != 7 }, base.text)
        assertEquals(listOf(7), BrowserDiff.of(base, now)!!.gone)
    }

    @Test
    fun newPageOrBigChangeSendsEverything() {
        assertNull(BrowserDiff.of(null, base))
        assertNull(BrowserDiff.of(base, page(base.items, base.text, url = "https://a.com/other")))
        val mostlyNew = page((100..130).map { item(it, "新$it") }, base.text)
        assertNull(BrowserDiff.of(base, mostlyNew))
        val newText = page(base.items, (1..60).joinToString("\n") { "完全不同的一段内容第 $it 段，讲的是别的事情" })
        assertNull(BrowserDiff.of(base, newText))
        assertTrue(describePage(mostlyNew, base).contains("能操作的（用编号 click / type）"))
    }

    @Test
    fun loginPagesAreRecognised() {
        assertTrue(looksLikeLogin(page(emptyList(), "", url = "https://passport.jd.com/new/login.aspx?ReturnUrl=x")))
        assertTrue(looksLikeLogin(page(emptyList(), "", url = "https://login.1688.com/member/signin.htm")))
        val pwd = JackBrowser.Item(1, "密码框", "密码", "", null, emptyList(), null, 0f, 0f, 10f, 10f)
        assertTrue(looksLikeLogin(page(listOf(pwd), "账号登录\n忘记密码")))
        val sms = JackBrowser.Item(2, "输入框", "请输入验证码", "", null, emptyList(), null, 0f, 0f, 10f, 10f)
        assertTrue(looksLikeLogin(page(listOf(sms), "手机号快捷登录")))
        // 普通页面：网址里带 login 字样的参数不算，只有密码框没有「登录」字样也不算
        assertFalse(looksLikeLogin(page(emptyList(), "商品列表", url = "https://s.1688.com/search?from=login")))
        assertFalse(looksLikeLogin(page(listOf(pwd), "修改设置")))
        assertFalse(looksLikeLogin(base))
    }
}
