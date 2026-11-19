package com.jack.ai.data

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextArtTest {
    @Test fun stylesDrawTextWithinTheFrame() {
        for (style in VideoPlan.STYLES + "subtitle") {
            val b = TextArt.render("限时五折\n今天下单", style, 1080)
            assertTrue("$style 太宽：${b.width}", b.width <= 1080)
            assertTrue(b.height > 40)
            var ink = 0
            for (x in 0 until b.width step 3) for (y in 0 until b.height step 3) if (Color.alpha(b.getPixel(x, y)) > 200) ink++
            assertTrue("$style 没画出字", ink > 50)
        }
        // 标签有底色块：角上不透明
        val label = TextArt.render("包邮", "label", 1080)
        assertEquals(255, Color.alpha(label.getPixel(label.width / 2, 2)))
        // 长句自动折行，不超宽
        assertTrue(TextArt.render("这是一句特别特别长的话会一直写下去看看会不会自动折成好几行", "title", 1080).width <= 1080)
    }

    @Test fun colorMatricesKeepWhiteAndFadeToBlack() {
        val m = VideoCompose.colorMatrix("bw")
        // 灰度：三行一样
        assertEquals(m[0], m[3], 1e-5f); assertEquals(m[1], m[4], 1e-5f)
        val none = VideoCompose.scaled(VideoCompose.colorMatrix("none"), 1f)
        assertEquals(1f, none[0], 1e-5f); assertEquals(1f, none[5], 1e-5f); assertEquals(1f, none[10], 1e-5f); assertEquals(0f, none[1], 1e-5f)
        val black = VideoCompose.scaled(VideoCompose.colorMatrix("warm"), 0f)
        assertTrue(black.take(11).all { it == 0f }); assertEquals(1f, black[15])
    }
}
