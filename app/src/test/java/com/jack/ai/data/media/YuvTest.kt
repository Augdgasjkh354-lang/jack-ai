package com.jack.ai.data.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class YuvTest {
    private fun b(x: Byte) = x.toInt() and 0xff

    @Test fun planarWhiteBlackRed() {
        val w = 4; val h = 2
        val argb = IntArray(w * h) { i -> if (i % w < 2) 0xFFFFFFFF.toInt() else 0xFFFF0000.toInt() }
        argb[0] = 0xFF000000.toInt()
        val y = ByteBuffer.allocate(w * h); val u = ByteBuffer.allocate(w * h / 4); val v = ByteBuffer.allocate(w * h / 4)
        Yuv.write(argb, w, h, Yuv.Plane(y, w, 1), Yuv.Plane(u, w / 2, 1), Yuv.Plane(v, w / 2, 1))
        assertEquals(16, b(y[0]))          // 黑
        assertEquals(235, b(y[1]))         // 白（有限范围上限）
        assertEquals(63, b(y[2]))          // 红：16 + 47*255/256
        // 右边 2×2 全是红：V 高、U 低
        assertEquals(true, b(v[1]) > 200)
        assertEquals(true, b(u[1]) < 128)
    }

    @Test fun semiPlanarInterleaves() {
        val w = 2; val h = 2
        val argb = IntArray(4) { 0xFF0000FF.toInt() }   // 蓝
        val y = ByteBuffer.allocate(4); val uv = ByteBuffer.allocate(2)
        // NV12：U 在偶数位、V 在奇数位，同一块内存
        Yuv.write(argb, w, h, Yuv.Plane(y, w, 1), Yuv.Plane(uv, 2, 2), Yuv.Plane(uv.duplicate().also { it.position(1) }.slice(), 2, 2))
        assertEquals(true, b(uv[0]) > 200)  // U 高
        assertEquals(true, b(uv[1]) < 128)  // V 低
    }
}
