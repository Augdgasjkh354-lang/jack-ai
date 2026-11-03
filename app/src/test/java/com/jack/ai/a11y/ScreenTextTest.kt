package com.jack.ai.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {
    private fun b(l: Int, t: Int, r: Int, bt: Int) = intArrayOf(l, t, r, bt)

    private fun e(n: Int, label: String, clickable: Boolean = false, editable: Boolean = false) = Element(
        n = n,
        role = if (editable) "输入框" else if (clickable) "按钮" else "文字",
        label = label,
        bounds = b(0, 0, 100, 100),
        path = listOf(n),
        clickable = clickable,
        editable = editable,
        scrollable = false,
        password = false,
        checked = null,
        enabled = true,
    )

    private fun screen(vararg elements: Element) = Screen("p", "测试", elements.toList())

    /** 一个聊天界面：标题、一行聊天（能点，字在里面）、输入框、密码框、发送按钮、一个看不见的控件。 */
    private val root = UiNode(
        cls = "FrameLayout", bounds = b(0, 0, 1080, 2400),
        children = listOf(
            UiNode(cls = "TextView", text = "微信", bounds = b(0, 0, 1080, 100)),
            UiNode(
                cls = "LinearLayout", clickable = true, bounds = b(0, 100, 1080, 300),
                children = listOf(
                    UiNode(cls = "TextView", text = "张三", bounds = b(100, 110, 500, 160)),
                    UiNode(cls = "TextView", text = "明天见", bounds = b(100, 170, 500, 220)),
                ),
            ),
            UiNode(cls = "EditText", text = "你好", editable = true, clickable = true, bounds = b(0, 2200, 800, 2300)),
            UiNode(cls = "EditText", text = "••••", hint = "密码", editable = true, password = true, bounds = b(0, 2000, 800, 2100)),
            UiNode(cls = "Button", text = "发送", clickable = true, bounds = b(800, 2200, 1080, 2300)),
            UiNode(cls = "View", text = "看不见", bounds = b(0, 0, 0, 0)),
            UiNode(
                cls = "RecyclerView", scrollable = true, bounds = b(0, 300, 1080, 2000),
                children = listOf(UiNode(cls = "Switch", text = "免打扰", checkable = true, checked = true, clickable = true, bounds = b(0, 400, 1080, 500))),
            ),
        ),
    )

    @Test
    fun listsWhatYouCanSeeAndDo() {
        val els = ScreenText.elements(root)
        val text = ScreenText.format(Screen("com.tencent.mm", "微信", els))
        assertTrue(text, "[2] 按钮「张三 明天见」" in text)
        // 合进按钮名字的字不再单列
        assertFalse(text, "文字「张三」" in text)
        assertTrue(text, "输入框：你好" in text)
        assertTrue(text, "按钮「发送」" in text)
        assertTrue(text, "开关「免打扰」 ·开" in text)
        assertFalse(text, "看不见" in text)
        // 密码框不读
        assertTrue(text, "密码框（不读）" in text)
        assertFalse(text, "••••" in text)
    }

    @Test
    fun plainTextForAskingAboutTheScreen() {
        val text = ScreenText.plain(Screen("p", "微信", ScreenText.elements(root)))
        assertTrue(text, text.startsWith("「微信」屏幕上的内容"))
        assertTrue(text, "张三 明天见\n" in text)
        assertTrue(text, "（输入框）你好" in text)
        assertFalse(text, "[" in text)
        assertFalse(text, "••••" in text)
    }

    @Test
    fun findsByNumberTextAndPoint() {
        val s = Screen("p", "微信", ScreenText.elements(root))
        assertEquals("发送", s.byText("发送")!!.label)
        assertEquals("张三 明天见", s.byText("张三")!!.label)
        assertEquals(listOf(4), s.byText("发送")!!.path)
        assertEquals("发送", s.at(900, 2250)!!.label)
        assertEquals(s.byNumber(2)!!.label, s.at(50, 200)!!.label)
    }

    @Test
    fun byTextIgnoresSpacesWidthAndPunctuation() {
        val s = screen(
            e(1, "发送", clickable = true),
            e(2, "确定", clickable = true),
            e(3, "ＡＢＣ", clickable = true),
        )
        assertEquals("发送", s.byText("发 送")!!.label)
        assertEquals("发送", s.byText("发送 ")!!.label)
        assertEquals("确定", s.byText("确定。")!!.label)
        assertEquals("ＡＢＣ", s.byText("ABC")!!.label)
    }

    @Test
    fun byTextMatchesLongModelWordingAndQuotes() {
        val s = screen(e(1, "搜索", clickable = true))
        assertEquals("搜索", s.byText("「搜索」按钮")!!.label)
    }

    @Test
    fun byTextPrefersActionableCandidate() {
        val s = screen(
            e(1, "搜索按钮"),
            e(2, "搜索", editable = true),
        )
        assertEquals(2, s.byText("「搜索」按钮")!!.n)
    }

    @Test
    fun byTextPrefersClosestLengthAmongActionableCandidates() {
        val s = screen(
            e(1, "搜索", clickable = true),
            e(2, "搜索结果页", clickable = true),
        )
        assertEquals(2, s.byText("搜索结果")!!.n)
    }

    @Test
    fun byTextDoesNotFallBackToShortLabelInsideQuery() {
        val s = screen(e(1, "我", clickable = true), e(2, "消息", clickable = true))
        assertNull(s.byText("我的收藏"))
        assertNull(s.byText("消息免打扰设置"))
    }
}
