package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.cap.web.ElementFusion.AxNode
import com.wangzan101.mika.core.cap.web.ElementFusion.Box
import com.wangzan101.mika.core.cap.web.ElementFusion.Extra
import com.wangzan101.mika.core.cap.web.ElementFusion.Frame
import com.wangzan101.mika.core.cap.web.ElementFusion.PageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementFusionTest {
    private val screen = Box(0.0, 0.0, 400.0, 800.0)
    private fun ax(id: String, backend: Int, role: String, name: String, frame: String = "main") =
        AxNode(id, "root", frame, 1, backend, role, name)

    @Test fun backendIdMergesExistingButtonAndPreservesItsNumber() {
        val page = listOf(PageNode(17, "按钮", "搜", Box(20.0, 50.0, 90.0, 90.0)))
        val tree = listOf(ax("1", 10, "button", "搜索"))
        val out = ElementFusion.fuse(page, listOf(10), tree, emptyList(), screen)
        assertEquals(1, out.elements.size)
        assertEquals(17, out.elements.single().oldN)
        assertEquals("搜索", out.elements.single().label)
        assertEquals(1, out.matched)
    }

    @Test fun crossOriginFrameClipsVisibleChildAndSkipsCoveredButton() {
        val page = listOf(PageNode(1, "按钮", "原有", Box(10.0, 10.0, 60.0, 30.0)))
        val frame = Box(80.0, 100.0, 240.0, 200.0)
        val visible = ax("pay", 90, "button", "付款", "child")
        val blocked = ax("hidden", 91, "button", "遮挡按钮", "child")
        val candidates = ElementFusion.candidates(page, listOf(10),
            listOf(visible, blocked), listOf(Frame("main", null), Frame("child", "main")),
            mapOf(70 to frame), mapOf(70 to "child"))
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.ownerId == 70 && it.clip == frame })
        val extras = listOf(
            Extra(90, "child", 1, "按钮", "付款", Box(200.0, 150.0, 300.0, 190.0),
                frame, 70, 90, setOf(90)),
            Extra(91, "child", 2, "按钮", "遮挡按钮", Box(100.0, 120.0, 150.0, 160.0),
                frame, 70, 8, setOf(91)),
        )
        val out = ElementFusion.fuse(page, listOf(10), listOf(visible, blocked), extras, screen)
        assertEquals(2, out.elements.size)
        assertEquals("付款", out.elements.last().label)
        assertEquals(Box(200.0, 150.0, 240.0, 190.0), out.elements.last().box)
    }

    @Test fun closedShadowAndDirectListenerAreAddedWithoutDuplicatingExisting() {
        val page = listOf(PageNode(3, "链接", "已有", Box(0.0, 10.0, 150.0, 40.0)))
        val shadow = ax("shadow", 50, "button", "展开")
        val extras = listOf(
            Extra(50, "main", 2, "按钮", "展开", Box(10.0, 90.0, 100.0, 125.0),
                null, null, 50, setOf(50)),
            Extra(51, "main", 3, "可点", "显示更多", Box(10.0, 180.0, 120.0, 210.0),
                null, null, 51, setOf(51)),
            Extra(52, "main", 4, "按钮", "已有", Box(0.0, 10.0, 150.0, 40.0),
                null, null, 52, setOf(52)),
        )
        val out = ElementFusion.fuse(page, listOf(1), listOf(shadow), extras, screen)
        assertEquals(listOf("已有", "展开", "显示更多"), out.elements.map { it.label })
        assertEquals(2, out.added)
        assertTrue(out.elements[1].backendId == 50)
        assertFalse(out.elements.any { it.backendId == 52 })
    }
}
