package com.jack.ai.ui.photo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoOpsTest {
    /** 左半红、右半蓝的 200×100 图。 */
    private fun img(): Bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until 100) for (x in 0 until 200) setPixel(x, y, if (x < 100) Color.rgb(200, 40, 40) else Color.rgb(40, 40, 200))
    }

    @Test
    fun geometry() {
        val b = img()
        val r = PhotoOps.orient(b, 1, false, false, 0f)
        assertEquals(100, r.width); assertEquals(200, r.height)
        val f = PhotoOps.orient(b, 0, true, false, 0f)
        assertTrue(Color.blue(f.getPixel(10, 50)) > 150) // 翻过来左边变蓝
        val c = PhotoOps.crop(b, RectF(0.5f, 0f, 1f, 1f))
        assertEquals(100, c.width)
        val s = PhotoOps.straighten(b, 10f)
        assertEquals(200, s.width); assertTrue(Color.alpha(s.getPixel(0, 0)) == 255) // 放大后四角不露空
        val fit = PhotoOps.fitRatio(200, 100, 1f)
        assertEquals(0.25f, fit.left, 0.001f); assertEquals(0.75f, fit.right, 0.001f)
    }

    @Test
    fun colorAndEffects() {
        val b = img()
        val gray = PhotoOps.applyMatrix(b, PhotoOps.filterMatrix(PhotoOps.FILTERS.first { it.first == "黑白" }.second, 1f))
        val p = gray.getPixel(10, 10)
        assertTrue(abs(Color.red(p) - Color.blue(p)) < 4)
        val bright = PhotoOps.adjust(b, mapOf("brightness" to 60f))
        assertTrue(Color.red(bright.getPixel(10, 10)) > 200)
        assertEquals(b, PhotoOps.adjust(b, emptyMap()))
        val framed = PhotoOps.frame(b, "polaroid", Color.WHITE)
        assertTrue(framed.height > framed.width - 200 + 100 && framed.getPixel(1, 1) == Color.WHITE)
        val m = PhotoOps.mosaic(b, listOf(PhotoOps.Stroke("pixel", listOf(0.45f to 0.5f, 0.55f to 0.5f), 0, 0.2f)))
        assertNotEquals(b.getPixel(98, 50), m.getPixel(98, 50))
        val blurred = PhotoOps.focusBlur(b, PhotoOps.Focus(cx = 0.1f, cy = 0.5f, radius = 0.1f, strength = 1f))
        assertNotEquals(b.getPixel(100, 50), blurred.getPixel(100, 50)) // 焦点外的分界线被糊开
    }

    @Test
    fun itemsHitTest() {
        val it = PhotoOps.Item(1, "你好", cx = 0.5f, cy = 0.5f, size = 0.1f)
        assertEquals(1L, PhotoOps.hit(listOf(it), 0.5f, 0.5f, 1000f, 1000f)?.id)
        assertEquals(null, PhotoOps.hit(listOf(it), 0.05f, 0.05f, 1000f, 1000f))
    }

    private fun abs(i: Int) = kotlin.math.abs(i)
}
