package com.jack.ai.a11y

import com.wangzan101.mika.core.screen.map.ScreenMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PhoneScreenMapsTest {
    private var n = 0
    private fun el(label: String, role: String = "按钮", top: Int = (++n) * 100, editable: Boolean = false, left: Int = 0, right: Int = 1000) =
        Element(n, role, label, intArrayOf(left, top, right, top + 80), listOf(n), clickable = true, editable = editable,
            scrollable = role == "列表", password = false, checked = null, enabled = true)

    private fun screen(pkg: String, vararg labels: String) = Screen(pkg, "测试", labels.map { el(it) })

    @Test fun skeletonKeepsFrameDropsContentNumbersAndInput() {
        val list = Element(90, "列表", "", intArrayOf(0, 1000, 1000, 2000), listOf(90), clickable = false, editable = false,
            scrollable = true, password = false, checked = null, enabled = true)
        val s = Screen("p", "测试", listOf(
            el("消息"), el("搜索", role = "输入框", editable = true), el("23 条未读"), el("这是一条很长很长很长很长的聊天内容"),
            list, Element(91, "文字", "张三", intArrayOf(0, 1100, 1000, 1180), listOf(91), false, false, false, false, null, true),
            el("通讯录"), el("消息"),
        ))
        assertEquals(listOf("消息", "通讯录"), PhoneScreenMaps.skeleton(s))
    }

    @Test fun storeRoundTripsAndMismatchSpotsTheWrongPage() {
        val ctx = RuntimeEnvironment.getApplication()
        val pkg = "com.example.shop"
        val home = arrayOf("首页", "分类", "购物车", "我的", "扫一扫")
        val cart = arrayOf("购物车", "管理", "结算", "全选")
        val mine = arrayOf("我的", "设置", "订单", "收藏")
        fun mv(a: Array<String>, t: String, b: Array<String>) =
            ScreenMove(pkg, a.toList(), "tap", t, pkg, b.toList(), true)
        PhoneScreenMaps.grow(ctx, listOf(mv(home, "购物车", cart), mv(cart, "我的", mine)))
        val g = PhoneScreenMaps.store(ctx).load(pkg)!!
        assertEquals(3, g.nodes.size)
        // 进 App 时的地图：认得出在首页，列出从首页走过的路
        val hint = PhoneScreenMaps.hint(ctx, screen(pkg, *home))
        assertTrue(hint, "现在在「" in hint && "点「购物车」" in hint)
        // 重放核对：本该到购物车，结果在「我的」页 → 说清楚；到对了不说
        assertNull(PhoneScreenMaps.mismatch(ctx, cart.toList(), screen(pkg, *cart)))
        val bad = PhoneScreenMaps.mismatch(ctx, cart.toList(), screen(pkg, *mine))
        assertTrue(bad.orEmpty(), bad.orEmpty().startsWith("落到的页面不对"))
        // 落到认不出的页，但录下的字大半还在（多了个弹窗）：不误停
        assertNull(PhoneScreenMaps.mismatch(ctx, cart.toList(), screen(pkg, "购物车", "管理", "结算", "限时优惠弹窗")))
        // 没有图的 App 不给地图、不判
        assertEquals("", PhoneScreenMaps.hint(ctx, screen("com.none", "首页")))
        assertNull(PhoneScreenMaps.mismatch(ctx, listOf("首页"), screen("com.none", "设置")))
    }
}
