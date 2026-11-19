package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlanTest {
    private fun parse(s: String) = VideoPlan.parse(Json.parseToJsonElement(s).jsonObject) { it.endsWith(".jpg") }

    @Test fun readsAFullPlan() {
        val (p, err) = parse("""{"size":"9:16","transition":"fade","clips":[
            {"file":"a.mp4","start":"0:03","end":"8.5","speed":2,"zoom":"in","filter":"warm"},
            {"file":"p.jpg","duration":2.5,"fit":"fit"}],
            "texts":[{"text":"重点\\n来了","start":1,"end":"0:03","style":"highlight","position":"center"}],
            "subtitles":"a-字幕.srt","music":"bgm.mp3","music_volume":0.5,"duck":false}""")
        assertEquals("", err)
        p!!
        assertEquals(1080 to 1920, p.size)
        assertTrue(p.fade)
        assertEquals(VideoPlan.Clip("a.mp4", false, 3000, 8500, speed = 2f, zoom = "in", filter = "warm"), p.clips[0])
        assertEquals(VideoPlan.Clip("p.jpg", true, durationMs = 2500, fill = false), p.clips[1])
        assertEquals(VideoPlan.Text("重点\n来了", 1000, 3000, "highlight", "center"), p.texts[0])
        assertEquals(0.5f, p.musicVolume); assertEquals(false, p.duck)
    }

    @Test fun mistakesAreExplained() {
        assertTrue(parse("""{"clips":[]}""").second.contains("clips"))
        assertTrue(parse("""{"clips":[{"file":"a.mp4","speed":9}]}""").second.contains("speed"))
        assertTrue(parse("""{"clips":[{"file":"a.mp4","start":5,"end":5.1}]}""").second.contains("太短"))
        assertTrue(parse("""{"clips":[{"file":"a.mp4"}],"texts":[{"text":"x","start":3,"end":2}]}""").second.contains("晚"))
        assertTrue(parse("""{"size":"2:1","clips":[{"file":"a.mp4"}]}""").second.contains("size"))
        assertNull(parse("""{"clips":[{"file":"a.mp4","filter":"lomo"}]}""").first)
        // 原尺寸、自定义尺寸（取偶数）
        assertNull(parse("""{"size":"original","clips":[{"file":"a.mp4"}]}""").first!!.size)
        assertEquals(720 to 1280, parse("""{"size":"721x1280","clips":[{"file":"a.mp4"}]}""").first!!.size)
    }

    @Test fun timelineCountsSpeedAndImages() {
        val clips = listOf(
            VideoPlan.Clip("a.mp4", false, 2000, 6000, speed = 2f),
            VideoPlan.Clip("p.jpg", true, durationMs = 1500),
            VideoPlan.Clip("a.mp4", false, 10_000, null),
        )
        val len = VideoPlan.lengths(clips) { 15_000 }
        assertEquals(listOf(2000L, 1500L, 5000L), len)
        assertEquals(listOf(0L, 2000L, 3500L), VideoPlan.offsets(len))
    }

    @Test fun subtitlesFollowTheCutAndTheSpeed() {
        val cues = listOf(
            Subtitles.Cue(1000, 3000, "剪掉的"),
            Subtitles.Cue(4000, 6000, "第一句"),
            Subtitles.Cue(10_000, 12_000, "第二句"),
            Subtitles.Cue(5800, 6050, "擦边"),
        )
        val clips = listOf(
            VideoPlan.Clip("a.mp4", false, 10_000, 12_000, speed = 2f),
            VideoPlan.Clip("b.mp4", false, 0, 3000),
            VideoPlan.Clip("a.mp4", false, 4000, 6000),
        )
        val len = VideoPlan.lengths(clips) { 20_000 }
        val out = VideoPlan.remapSubtitles(cues, clips, len, "a.mp4")
        assertEquals(listOf(
            Subtitles.Cue(0, 1000, "第二句"),
            Subtitles.Cue(4000, 6000, "第一句"),
        ), out.filter { it.text != "擦边" })
        // 只擦到 0.2 秒的那句不要
        assertTrue(out.none { it.text == "擦边" } || out.first { it.text == "擦边" }.let { it.endMs - it.startMs >= 300 })
    }

    @Test fun musicDucksUnderSpeechWithRamps() {
        val g = VideoPlan.duckGain(listOf(2000L to 4000L, 4500L to 6000L), duck = 0.3f, rampMs = 300)
        assertEquals(1f, g(0), 1e-4f)
        assertEquals(0.3f, g(3000), 1e-4f)
        // 两句之间只停了 0.5 秒：不放大
        assertEquals(0.3f, g(4200), 1e-4f)
        // 渐变：说话前 150 毫秒在中间
        assertEquals(0.65f, g(1850), 1e-3f)
        assertEquals(1f, g(7000), 1e-4f)
        assertEquals(1f, VideoPlan.duckGain(emptyList())(5000), 1e-4f)
    }
}
