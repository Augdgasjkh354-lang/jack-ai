package com.jack.ai.a11y

import com.wangzan101.mika.core.uistate.TreeQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneScreenAnalysisTest {
    private val size = 1000 to 1000

    private fun labelledTree(): UiNode = UiNode(bounds = intArrayOf(0, 0, 1000, 1000),
        children = (0 until 6).map { i ->
            UiNode(text = "项目$i", clickable = true, bounds = intArrayOf(0, i * 60, 300, i * 60 + 50))
        })

    @Test fun treeQualityKeepsNormalScreenTextOnlyAndFlagsBlindAreas() {
        val good = labelledTree()
        assertEquals(TreeQuality.Adequate, PhoneScreenAnalysis.quality(good, size).quality)
        val missing = good.copy(children = good.children + UiNode(
            cls = "android.view.SurfaceView", bounds = intArrayOf(0, 400, 1000, 1000)))
        assertEquals(TreeQuality.Partial, PhoneScreenAnalysis.quality(missing, size).quality)
    }

    @Test fun unreadableTreeIsRecognized() {
        val unreadable = PhoneScreenAnalysis.quality(UiNode(bounds = intArrayOf(0, 0, 1000, 1000)), size)
        assertEquals(TreeQuality.Unusable, unreadable.quality)
        assertEquals(TreeQuality.Protected, PhoneScreenAnalysis.withFrame(unreadable, black = true).quality)
    }

    private fun item(n: Int, name: String, x: Int, path: Int) =
        Element(n, "按钮", name, intArrayOf(x, 100, x + 80, 180), listOf(path),
            clickable = true, editable = false, scrollable = false, password = false, checked = null, enabled = true)

    @Test fun newElementGetsNewNumberWithoutRenumberingExistingElements() {
        val original = Screen("com.demo", "测试", listOf(
            item(1, "甲", 100, 0), item(2, "乙", 250, 1), item(3, "丙", 400, 2)))
        val first = PhoneScreenAnalysis.stabilize(null, original, size)
        val newRaw = Screen("com.demo", "测试", listOf(
            item(1, "新", 600, 0), item(2, "甲", 100, 1),
            item(3, "乙", 250, 2), item(4, "丙", 400, 3)))
        val second = PhoneScreenAnalysis.stabilize(first, newRaw, size)
        assertEquals(listOf(4, 1, 2, 3), second.elements.map { it.n })
        assertEquals(second.elements.map { it.n },
            PhoneScreenAnalysis.stabilize(second, newRaw, size).elements.map { it.n })
    }

    @Test fun duplicateLabelsKeepNumbersOnlyAtSamePositions() {
        val before = Screen("com.demo", "测试", listOf(item(1, "关注", 100, 0), item(2, "关注", 300, 1)))
        val first = PhoneScreenAnalysis.stabilize(null, before, size)
        val same = PhoneScreenAnalysis.stabilize(first, before, size)
        assertEquals(listOf(1, 2), same.elements.map { it.n })
        val changed = Screen("com.demo", "测试", listOf(item(1, "新账号", 100, 0), item(2, "关注", 300, 1)))
        assertEquals(listOf(3, 4), PhoneScreenAnalysis.stabilize(same, changed, size).elements.map { it.n })
    }
}
