package com.wangzan101.mika.core.screen.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jack AI：屏图移植的自检（认屏口径改过，所以按 Kavi 的口径重写用例）。 */
class ScreenMapTest {
    private val app = "com.example.chat"
    private val home = listOf("首页", "消息", "我的", "推荐", "关注")
    private val inbox = listOf("首页", "消息", "我的", "通知", "私信")
    private val settings = listOf("设置", "账号与安全", "通用", "隐私")
    private val about = listOf("设置", "关于", "版本更新", "隐私")

    private fun move(b: List<String>, target: String?, a: List<String>, action: String = "tap", afterApp: String = app) =
        ScreenMove(app, b, action, target, afterApp, a, effective = true)

    private val moves = listOf(
        move(home, "消息", inbox),
        move(inbox, "我的", home + "编辑资料"),
        move(home + "编辑资料", "设置", settings),
        move(settings, "通用", about),
        move(about, null, settings, action = "back"),
        move(settings, null, settings - "隐私" + "清理缓存", action = "scroll"),
    )

    @Test fun buildIsDeterministicAndMergesNearIdenticalScreens() {
        val a = ScreenMapBuilder().apply(moves)
        val b = ScreenMapBuilder().apply(moves)
        assertEquals(ScreenGraphCodec.encode(a.getValue(app)), ScreenGraphCodec.encode(b.getValue(app)))
        val g = a.getValue(app)
        // 首页带不带「编辑资料」是同一页；设置页往下翻少了「隐私」多了「清理缓存」也还是设置页
        assertEquals(4, g.nodes.size)
        val homeId = ScreenRecognizer.identify(home.toSet(), g.nodes())!!.id
        assertEquals(3, g.nodes.getValue(homeId).visits)
    }

    @Test fun tabBarScreensAreToldApartButStillRecognized() {
        val g = ScreenMapBuilder().apply(moves).getValue(app)
        val h = ScreenRecognizer.identify(home.toSet(), g.nodes())
        val i = ScreenRecognizer.identify(inbox.toSet(), g.nodes())
        assertNotNull(h); assertNotNull(i)
        assertNotEquals(h!!.id, i!!.id)
        // 只剩底栏几个到处都有的字：不知道是哪页，不猜
        assertNull(ScreenRecognizer.identify(setOf("首页", "消息", "我的"), g.nodes()))
        // 完全没见过的页
        assertNull(ScreenRecognizer.identify(setOf("购物车", "结算"), g.nodes()))
    }

    @Test fun pathFindsTheShortestRouteAcrossScreens() {
        val g = ScreenMapBuilder().apply(moves).getValue(app)
        fun id(t: List<String>) = ScreenRecognizer.identify(t.toSet(), g.nodes())!!.id
        val p = g.path(id(home), id(about))!!
        assertEquals(listOf("设置", "通用"), p.map { it.target })
        assertEquals(emptyList<Transition>(), g.path(id(home), id(home)))
        assertNull(g.path(id(about), id(inbox)))
    }

    @Test fun growMergesIntoTheStoredGraphByScreenId() {
        val store = object : ScreenMapStore {
            val m = mutableMapOf<String, ScreenGraph>()
            override fun load(app: String) = m[app]
            override fun save(app: String, graph: ScreenGraph) { m[app] = graph }
            override fun apps() = m.keys.toList()
        }
        ScreenMapGrower.grow(store, ScreenMapBuilder().apply(moves))
        val first = store.load(app)!!
        ScreenMapGrower.grow(store, ScreenMapBuilder().apply(listOf(move(home, "消息", inbox)), mapOf(app to first)))
        val second = store.load(app)!!
        assertEquals(first.nodes.keys, second.nodes.keys)
        val homeId = ScreenRecognizer.identify(home.toSet(), second.nodes())!!.id
        val edge = second.nodes.getValue(homeId).transitions.values.first { it.target == "消息" }
        assertEquals(2, edge.count)
        assertEquals("confirmed", second.nodes.getValue(homeId).status)
    }

    @Test fun leavingTheAppAndEmptyScreensAreHandled() {
        val g = ScreenMapBuilder().apply(listOf(
            move(home, "分享", listOf("微信", "朋友圈"), afterApp = "com.tencent.mm"),
            move(emptyList(), "随便", home),
        ))
        val edge = g.getValue(app).nodes.values.single().transitions.values.single()
        assertEquals(TransitionEffect.LEFT_APP, edge.effect)
        assertNull(edge.to)
        assertTrue("com.tencent.mm" in g)
    }

    @Test fun renderShowsWhereYouAreAndWhereYouCanGo() {
        val g = ScreenMapBuilder().apply(moves).getValue(app)
        val text = ScreenMapRender.renderMapFor(g, home.toSet())!!
        assertTrue(text, "走过的页面（4）" in text)
        assertTrue(text, "现在在「" in text)
        assertTrue(text, "点「消息」" in text)
    }

    @Test fun mergeGateIsZero() {
        val all = ScreenMapBuilder().apply(moves)
        val g = all.getValue(app)
        fun id(t: List<String>) = ScreenRecognizer.identify(t.toSet(), g.nodes())!!.id
        val labeled = mapOf(id(home) to "首页", id(inbox) to "消息", id(settings) to "设置", id(about) to "关于")
        assertEquals(emptyList<Pair<String, String>>(), ScreenMapMergeGate.wrongMerge(g.nodes(), labeled))
        assertEquals(emptyList<String>(), ScreenMapMergeGate.wrongOwner(moves, all))
    }
}
