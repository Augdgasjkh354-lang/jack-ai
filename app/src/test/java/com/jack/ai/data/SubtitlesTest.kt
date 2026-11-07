package com.jack.ai.data

import com.jack.ai.data.Subtitles.Cue
import com.jack.ai.data.Subtitles.Seg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {
    @Test fun srtRoundTrips() {
        val cues = listOf(Cue(1_200, 3_450, "大家好"), Cue(3_700_000, 3_702_500, "第二句\n两行"))
        assertEquals(cues, Subtitles.parseSrt(Subtitles.toSrt(cues)))
        assertTrue(Subtitles.toSrt(cues).contains("01:01:40,000 --> 01:01:42,500"))
    }

    @Test fun parsesWindowsSrtAndSkipsBrokenBlocks() {
        val text = "1\r\n00:00:01,000 --> 00:00:02,000\r\n你好\r\n\r\n2\r\n坏的\r\n\r\n3\r\n00:00:05.5 --> 00:00:04,000\r\n倒着的\r\n"
        assertEquals(listOf(Cue(1000, 2000, "你好")), Subtitles.parseSrt(text))
    }

    @Test fun parsesSegmentsInGivenOrder() {
        val (segs, _) = Subtitles.parseSegments("1:05-1:40，0:12.5 - 0:31、2:00到2:10", VideoEdit::parseTime)
        assertEquals(listOf(Seg(65_000, 100_000), Seg(12_500, 31_000), Seg(120_000, 130_000)), segs)
        val (bad, err) = Subtitles.parseSegments("0:30-0:20", VideoEdit::parseTime)
        assertNull(bad)
        assertTrue(err.contains("晚"))
    }

    @Test fun remapsCuesOntoJoinedTimeline() {
        val cues = listOf(Cue(0, 4_000, "开场"), Cue(10_000, 14_000, "精彩一"), Cue(14_000, 20_100, "精彩二"), Cue(30_000, 33_000, "结尾"))
        // 先放 30-33 秒，再放 10-20 秒
        val out = Subtitles.remap(cues, listOf(Seg(30_000, 33_000), Seg(10_000, 20_000)))
        assertEquals(listOf(Cue(0, 3_000, "结尾"), Cue(3_000, 7_000, "精彩一"), Cue(7_000, 13_000, "精彩二")), out)
    }

    @Test fun splitsLongCueByPunctuationWithProportionalTimes() {
        val long = Cue(0, 6_000, "今天我们来聊一聊怎么用手机剪辑视频，其实非常简单，三步就能做完一个短视频。")
        val shown = Subtitles.forScreen(listOf(long), maxChars = 10)
        assertTrue(shown.size >= 2)
        assertEquals(0L, shown.first().startMs)
        assertEquals(6_000L, shown.last().endMs)
        shown.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        // 每屏最多两行，每行不超过 10 个字
        shown.forEach { c ->
            val lines = c.text.split('\n')
            assertTrue(c.text, lines.size <= 2)
            lines.forEach { assertTrue(it, it.length <= 10) }
        }
        assertEquals(long.text, shown.joinToString("") { it.text.replace("\n", "") })
    }

    @Test fun shortCueStaysOneLine() {
        assertEquals(listOf(Cue(0, 1000, "你好")), Subtitles.forScreen(listOf(Cue(0, 1000, "你好"))))
    }
}
