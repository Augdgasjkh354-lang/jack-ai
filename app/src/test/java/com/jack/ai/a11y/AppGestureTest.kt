package com.jack.ai.a11y

import com.wangzan101.mika.core.cap.phone.GesturePacing
import com.wangzan101.mika.core.cap.phone.TypingPacing
import com.wangzan101.mika.core.uistate.ActionEffect
import com.wangzan101.mika.core.uistate.EffectElement
import com.wangzan101.mika.core.uistate.EffectKind
import com.wangzan101.mika.core.uistate.EffectScreen
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AppGestureTest {
    @Test fun gestureStaysInsideElementAndMakesCurvedSwipe() {
        val pace = GesturePacing.human(Random(12))
        repeat(100) {
            val (x, y) = pace.within(100, 200, 180, 250)
            assertTrue(x in 100 until 180 && y in 200 until 250)
        }
        val stroke = pace.stroke(listOf(100.0 to 200.0, 400.0 to 650.0), 360, bow = true, bounds = 1080 to 1920)
        assertTrue(stroke.points.size > 3)
        assertEquals(100.0, stroke.points.first().x, 0.01)
        assertEquals(650.0, stroke.points.last().y, 0.01)
        assertTrue(stroke.points.zipWithNext().all { (a, b) -> b.t >= a.t })
    }

    @Test fun effectChecksChangesAndUnchangedTrees() {
        fun node(label: String, value: String = "") =
            EffectElement("输入框", label, value, listOf(0, 0, 100, 100), null)
        val before = EffectScreen("app", listOf(node("搜索"), node("确定"), node("取消")))
        assertEquals(EffectKind.NO_EFFECT, ActionEffect.classify(before, before))
        val after = before.copy(elements = listOf(node("搜索", "你好"), node("确定"), node("取消")))
        assertEquals(EffectKind.CHANGED, ActionEffect.classify(before, after, typed = "你好"))
        assertEquals(EffectKind.UNCERTAIN, ActionEffect.classify(null, after))
    }

    @Test fun typingPlansChineseByChunksAndFastWritesOnce() {
        val human = TypingPacing.human(Random(4))
        val plan = human.plan("你好世界，测试一下")
        assertTrue(plan.size > 1)
        assertEquals("你好世界，测试一下".length, plan.last().end)
        assertTrue(plan.zipWithNext().all { (a, b) -> a.end < b.end })
        assertEquals(1, TypingPacing.Instant.plan("你好世界").size)
    }
}
