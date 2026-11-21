package com.jack.ai.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import com.jack.ai.data.media.SfxSynth
import com.jack.ai.data.media.VideoFollow
import com.jack.ai.data.media.VideoPlan

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoFxTest {
    private fun parse(s: String) = VideoPlan.parse(Json.parseToJsonElement(s).jsonObject) { it.endsWith(".jpg") }

    @Test fun transitionsPerCutWithOverrides() {
        val p = parse("""{"transition":"zoom","clips":[{"file":"a.mp4"},{"file":"b.mp4","transition":"flash"},{"file":"c.mp4"}]}""").first!!
        assertEquals("cut", p.transitionInto(0))
        assertEquals("flash", p.transitionInto(1)); assertEquals("flash", p.transitionOutOf(0))
        assertEquals("zoom", p.transitionInto(2)); assertEquals("cut", p.transitionOutOf(2))
        val f = parse("""{"transition":"fade","clips":[{"file":"a.mp4"},{"file":"b.mp4"}]}""").first!!
        assertEquals("fade", f.transitionInto(0)); assertEquals("fade", f.transitionOutOf(1))
        assertTrue(parse("""{"transition":"spin","clips":[{"file":"a.mp4"}]}""").second.contains("transition"))
        // fit：fill 默认跟着人，center 只裁中间
        val q = parse("""{"clips":[{"file":"a.mp4"},{"file":"b.mp4","fit":"center"},{"file":"c.mp4","fit":"fit"}]}""").first!!
        assertEquals(listOf(true, false, false), q.clips.map { it.follow })
        assertEquals(listOf(true, true, false), q.clips.map { it.fill })
    }

    @Test fun autoSfxFollowsTransitionsAndTexts() {
        val p = parse("""{"transition":"zoom","cover":"封面.jpg","cover_duration":1,
            "clips":[{"file":"a.mp4","start":0,"end":2},{"file":"b.mp4","start":0,"end":3,"transition":"shake"},{"file":"c.mp4","start":0,"end":2}],
            "texts":[{"text":"¥9.9","start":1,"end":2,"style":"price"},{"text":"好用","start":4,"end":5,"style":"highlight"}],
            "sfx":[{"sound":"ding","at":4.05}]}""").first!!
        val len = VideoPlan.lengths(p.clips) { 10_000 }
        val auto = VideoPlan.autoSfx(p, len)
        // 封面后第一个切点（a→b）在 2 秒：shake 配咚；b→c 在 5 秒：zoom 配唰（提前 0.15 秒）
        assertTrue(auto.toString(), auto.any { it.sound == "boom" && it.atMs == 2000L })
        assertTrue(auto.any { it.sound == "whoosh" && it.atMs == 4850L })
        assertTrue(auto.any { it.sound == "coin" && it.atMs == 1000L })
        // 4 秒的花字本来要配啵，但已经手写了 4.05 秒的叮，不重复
        assertTrue(auto.none { it.sound == "pop" })
        // 封面和第一段之间不算切点
        assertEquals(3, auto.size)
    }

    @Test fun followPathIgnoresJitterAndHoldsThroughMisses() {
        val samples = listOf(0L to 0.70f, 500L to null, 1000L to 0.72f, 1500L to 0.10f, 2000L to 0.71f, 2500L to 0.69f, 3000L to 0.30f, 3500L to 0.31f, 4000L to 0.29f, 4500L to 0.30f)
        val path = VideoPlan.followPath(samples)
        // 1.5 秒那一下认错（0.10）被去掉；小抖动不动镜头
        assertTrue(path.toString(), path.take(4).all { kotlin.math.abs(it.second - 0.70f) < 0.03f })
        // 人真的走到左边以后镜头跟过去
        assertTrue(path.last().second < 0.35f)
        // 夹在裁剪框能到的范围里：窗口 0.32 宽，最左 0.16
        assertEquals(0.16f, VideoPlan.followAt(listOf(0L to 0.0f), 0, 0.32f), 1e-4f)
        assertEquals(0.5f, VideoPlan.followAt(listOf(0L to 0.4f, 1000L to 0.6f), 500, 0.32f), 1e-4f)
        assertEquals(0.5f, VideoPlan.followPath(listOf(0L to null, 500L to null)).first().second, 1e-4f)
    }

    @Test fun detailFindsTheBusySide() {
        val b = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.rgb(120, 140, 160))
        val p = Paint()
        // 右边一块有很多细节（棋盘格），左边是平的天空
        for (x in 220 until 300 step 8) for (y in 40 until 160 step 8) { p.color = if ((x + y) / 8 % 2 == 0) Color.BLACK else Color.WHITE; c.drawRect(x.toFloat(), y.toFloat(), x + 8f, y + 8f, p) }
        val cx = VideoFollow.detail(b)!!
        assertTrue("$cx", cx > 0.6f)
        // 全平的画面：没有主体，返回 null（镜头停在中间）
        assertEquals(null, VideoFollow.detail(Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }))
    }

    @Test fun soundsAreSynthesizedAndMixed() {
        for (s in VideoPlan.SOUNDS.keys) {
            val a = SfxSynth.sound(s)
            assertTrue("$s 是空的", a.size > 400)
            assertTrue("$s 没声音", a.maxOf { kotlin.math.abs(it) } > 0.1f)
            assertTrue("$s 破音", a.all { it in -1.01f..1.01f })
        }
        val pcm = SfxSynth.track(listOf(VideoPlan.Sfx("ding", 500), VideoPlan.Sfx("ding", 500), VideoPlan.Sfx("boom", 900, 2f)), 2000)
        assertEquals(2 * SfxSynth.RATE * 2, pcm.size)
        val bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(bb.remaining()).also { bb.get(it) }
        // 0.5 秒之前没声音；叠在一起也不破音（整体压到 0.95 以内）
        assertTrue(samples.take(SfxSynth.RATE / 2 - 10).all { it.toInt() == 0 })
        assertTrue(samples.maxOf { kotlin.math.abs(it.toInt()) } <= (0.951 * 32767).toInt())
    }
}
