package com.jack.ai.data

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoFramesTest {
    private fun solid(c: Int, w: Int = 160, h: Int = 90) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }

    @Test fun shortVideosGetAFrameASecond() {
        assertEquals(9, VideoFrames.keepFor(5_000))
        assertEquals(30, VideoFrames.keepFor(30_000))
        assertEquals(60, VideoFrames.keepFor(60_000))
        // 过了 1 分钟不会突然变少，每 3 秒 1 帧，封顶 72 帧
        assertEquals(60, VideoFrames.keepFor(61_000))
        assertEquals(70, VideoFrames.keepFor(210_000))
        assertEquals(72, VideoFrames.keepFor(3 * 3600_000L))
        val all = (1..7200).map { VideoFrames.keepFor(it * 1000L) }
        assertTrue(all.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(60, VideoFrames.candidatesFor(30)); assertEquals(96, VideoFrames.candidatesFor(60)); assertEquals(96, VideoFrames.candidatesFor(72))
    }

    @Test fun candidatesSpreadInsideTheRange() {
        val t = VideoFrames.candidateTimes(10_000, 20_000, 10)
        assertEquals(10, t.size)
        assertEquals(10_500L, t.first()); assertEquals(19_500L, t.last())
        assertTrue(t.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun pickKeepsEvenSpreadAndSceneChanges() {
        // 20 个候选，第 13 帧是转场（和前一帧差很多）
        val diffs = List(20) { if (it == 13) 0.9f else 0.01f }
        val p = VideoFrames.pick(diffs, 6)
        assertEquals(6, p.size)
        assertTrue(13 in p)
        assertEquals(p.sorted(), p)
        // 前后都有，不挤在一起
        assertTrue(p.first() < 5 && p.last() > 14)
        // 候选不多时全留
        assertEquals(listOf(0, 1, 2), VideoFrames.pick(listOf(0f, 0f, 0f), 9))
    }

    @Test fun signatureTellsDifferentScenesApart() {
        val a = VideoFrames.signature(solid(Color.BLACK))
        val b = VideoFrames.signature(solid(Color.WHITE))
        val a2 = VideoFrames.signature(solid(Color.BLACK))
        assertTrue(VideoFrames.diff(a, b) > 0.9f)
        assertEquals(0f, VideoFrames.diff(a, a2), 1e-4f)
    }

    @Test fun gridHasThreeColumnsAndTimeLabels() {
        val frames = (0 until 7).map { VideoFrames.Frame(it * 61_000L, solid(Color.rgb(30 * it, 100, 200))) }
        val g = VideoFrames.grid(frames)
        // 横的视频：3 列 × 400 宽，3 行
        assertEquals(3 * 400 + 4 * 6, g.width)
        assertEquals(3 * 225 + 4 * 6, g.height)
        // 左上角有时间标签（白字黑底），格子中间是画面本身的颜色
        val cell = g.getPixel(6 + 200, 6 + 150)
        assertEquals(Color.rgb(0, 100, 200), cell)
        var white = 0
        for (x in 12..60) for (y in 12..36) if (g.getPixel(x, y) == Color.WHITE) white++
        assertTrue("时间标签没画出来", white > 5)
        val portrait = VideoFrames.grid(listOf(VideoFrames.Frame(0, solid(Color.RED, 90, 160))))
        assertEquals(300 + 12, portrait.width)
        assertTrue(VideoFrames.dataUrl(g).startsWith("data:image/jpeg;base64,"))
        assertEquals("1:01", VideoFrames.stamp(61_000)); assertEquals("1:00:00", VideoFrames.stamp(3_600_000))
    }

    @Test fun transcriptIsCutToTheSegment() {
        val timed = "[0:01.0-0:05.0] 开场\n[0:58.0-1:03.0] 跨进来的一句\n[1:10.0-1:20.0] 中间\n[2:00.0-2:05.0] 结尾"
        assertEquals("[0:58.0-1:03.0] 跨进来的一句\n[1:10.0-1:20.0] 中间", VideoFrames.transcriptBetween(timed, 60_000, 90_000))
    }

    @Test fun videoNamesAreRecognised() {
        assertTrue(Attachments.isVideo("VID_2026.MP4")); assertTrue(Attachments.isVideo("a.mov"))
        assertFalse(Attachments.isVideo("录音.m4a")); assertFalse(Attachments.isVideo("报告.pdf"))
    }
}
