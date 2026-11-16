package com.jack.ai.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipInputTest {
    // 540×1200 的截图放进 1000×1000 的框：按高度缩放 1000/1200，左右各留黑边
    private fun map(x: Float, y: Float) = PipInput.map(x, y, 1000f, 1000f, 540, 1200, 1080, 2400)

    @Test fun pointsInsideThePictureMapToTheRealScreen() {
        val scale = 1000f / 1200
        val left = (1000 - 540 * scale) / 2
        assertEquals(PipInput.Point(0, 0), map(left, 0f))
        assertEquals(PipInput.Point(540, 1200), map(left + 270 * scale, 600 * scale))
        assertEquals(PipInput.Point(1079, 2399), map(left + 540 * scale, 1000f))
    }

    @Test fun letterboxIsIgnored() {
        assertNull(map(10f, 500f))
        assertNull(map(990f, 500f))
        assertNull(PipInput.map(1f, 1f, 0f, 0f, 1, 1, 1, 1))
    }

    @Test fun smallMovesAreTapsAndStrokesAreTrimmedAndTimed() {
        assertTrue(PipInput.isTap(PipInput.Gesture(listOf(500f, 510f), listOf(500f, 505f), listOf(0, 80))))
        val xs = (0..199).map { 500f }
        val ys = (0..199).map { 200f + it * 3 }
        val g = PipInput.Gesture(xs, ys, (0..199).map { it.toLong() * 2 })
        assertFalse(PipInput.isTap(g))
        val (sx, sy, st) = PipInput.stroke(g, ::map, maxPoints = 20)!!
        assertEquals(20, st.size)
        assertEquals(sx.size, sy.size)
        assertEquals(0L, st.first())
        assertTrue((1 until st.size).all { st[it] >= st[it - 1] })
        assertTrue(sy.last() > sy.first())
        // 一下子划过去的也拉开到能被认出是滑动
        val fast = PipInput.stroke(PipInput.Gesture(listOf(500f, 500f), listOf(200f, 800f), listOf(0, 1)), ::map)!!
        assertEquals(120L, fast.third.last())
        assertNull(PipInput.stroke(PipInput.Gesture(listOf(10f, 12f), listOf(10f, 12f), listOf(0, 50)), ::map))
    }
}
