package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceTrackTest {
    @get:Rule val tmp = TemporaryFolder()

    /** ms 毫秒静音 + ms 毫秒响声 + ms 毫秒静音（24kHz 16 位）。 */
    private fun clip(silence: Int, loud: Int, rate: Int = 24_000): ByteArray {
        val n = rate * (silence * 2 + loud) / 1000
        val b = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val a = rate * silence / 1000; val z = a + rate * loud / 1000
        for (i in 0 until n) b.putShort(if (i in a until z) (if (i % 2 == 0) 8000 else -8000).toShort() else 0)
        return b.array()
    }

    @Test fun trimsEdgeSilenceKeepingALittle() {
        val t = VoiceTrack.trim(clip(500, 1000), 24_000)
        // 1 秒声音 + 两头各留 60 毫秒
        assertEquals((1000 + 120) * 24, t.size / 2)
        assertEquals(0, VoiceTrack.trim(ByteArray(4800), 24_000).size)
    }

    @Test fun joinsWithPausesAndTimesEachLine() {
        val parts = listOf(VoiceTrack.trim(clip(300, 1000), 24_000), ByteArray(0), VoiceTrack.trim(clip(300, 2000), 24_000))
        val (pcm, cues) = VoiceTrack.join(listOf("第一句", "没声音的", "第二句"), parts, 24_000, 250)
        assertEquals(listOf("第一句", "第二句"), cues.map { it.text })
        assertEquals(0L, cues[0].startMs); assertEquals(1120L, cues[0].endMs)
        assertEquals(1370L, cues[1].startMs); assertEquals(1370L + 2120, cues[1].endMs)
        assertEquals(cues[1].endMs, pcm.size / 2 * 1000L / 24_000)
    }

    @Test fun wavRoundTripAndPadding() {
        val f = tmp.newFile("a.wav")
        val pcm = clip(0, 500)
        VideoCompose.Wav.write(f, pcm, 24_000)
        assertEquals(500L, VideoCompose.Wav.durationMs(f))
        val g = tmp.newFile("b.wav")
        VideoCompose.Wav.padStart(f, g, 800)
        assertEquals(1300L, VideoCompose.Wav.durationMs(g))
        val fm = VideoCompose.Wav.fmt(g)!!
        val data = java.io.RandomAccessFile(g, "r").use { r -> r.seek(fm.dataOffset); ByteArray(fm.dataBytes.toInt()).also { r.readFully(it) } }
        assertTrue(data.copyOfRange(0, 800 * 24 * 2).all { it == 0.toByte() })
        assertArrayEquals(pcm, data.copyOfRange(800 * 24 * 2, data.size))
    }

    @Test fun coverGoesFirstAndOutputSubtitlesAreAllowed() {
        val (p, err) = VideoPlan.parse(Json.parseToJsonElement("""{"cover":"封面.png","cover_duration":1,"voiceover":"配音.wav",
            "subtitles":"配音.srt","subtitles_source":"output","clips":[{"file":"a.mp4","start":0,"end":5}]}""").jsonObject) { it.endsWith(".png") }
        assertEquals("", err)
        p!!
        assertEquals(VideoPlan.Clip("封面.png", true, durationMs = 1000), p.clips[0])
        assertEquals(2, p.clips.size)
        assertTrue(p.subtitlesOnOutput)
        assertEquals(1000L, p.coverMs); assertEquals("配音.wav", p.voiceover)
        // 原视频上的字幕：封面那段自动算进偏移
        val len = VideoPlan.lengths(p.clips) { 10_000 }
        val out = VideoPlan.remapSubtitles(listOf(Subtitles.Cue(1000, 2000, "x")), p.clips, len, "a.mp4")
        assertEquals(Subtitles.Cue(2000, 3000, "x"), out.single())
        assertTrue(VideoPlan.parse(Json.parseToJsonElement("""{"cover":"a.mp4","clips":[{"file":"a.mp4"}]}""").jsonObject) { false }.second.contains("cover"))
    }
}
