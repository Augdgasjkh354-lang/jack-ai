package com.jack.ai.data

import com.jack.ai.api.Transcriber
import com.jack.ai.tools.pageList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileToolboxTest {
    @Test
    fun resamplesAcrossChunks() {
        // 48 kHz 的一条斜线换成 16 kHz：每 3 个点取 1 个，分块喂和一次喂结果一样
        val input = FloatArray(4800) { it / 4800f }
        val whole = mutableListOf<Float>()
        AudioPcm.Resampler(48_000).push(input, input.size) { whole += it }
        val parts = mutableListOf<Float>()
        val r = AudioPcm.Resampler(48_000)
        listOf(0 until 1000, 1000 until 1001, 1001 until 4800).forEach { range ->
            val chunk = input.sliceArray(range)
            r.push(chunk, chunk.size) { parts += it }
        }
        assertEquals(1600, whole.size)
        assertEquals(whole.size, parts.size)
        whole.indices.forEach { assertEquals(whole[it], parts[it], 1e-5f) }
        assertEquals(input[300], whole[100], 1e-5f)
    }

    @Test
    fun upsamplesToo() {
        val out = mutableListOf<Float>()
        AudioPcm.Resampler(8_000).push(floatArrayOf(0f, 1f, 0f), 3) { out += it }
        // 8 kHz → 16 kHz：两点之间插一个中点
        assertEquals(listOf(0f, 0.5f, 1f, 0.5f), out)
    }

    @Test
    fun downmixesStereo() {
        val mono = FloatArray(2)
        val n = AudioPcm.mono(shortArrayOf(16384, 0, -32768, -32768), 4, 2, mono)
        assertEquals(2, n); assertEquals(0.25f, mono[0], 1e-6f); assertEquals(-1f, mono[1], 1e-6f)
    }

    @Test
    fun writesWavChunks() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wav-${System.nanoTime()}").apply { mkdirs() }
        try {
            val w = AudioPcm.WavChunks(dir, 1)
            repeat(16_000 * 2 + 100) { w.write(0.5f) }
            w.close()
            assertEquals(3, w.files.size)
            val first = w.files[0].readBytes()
            assertEquals(44 + 32_000, first.size)
            assertEquals("RIFF", String(first, 0, 4)); assertEquals("WAVE", String(first, 8, 4))
            val dataLen = java.nio.ByteBuffer.wrap(first, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            assertEquals(32_000, dataLen)
            assertEquals(44 + 200, w.files[2].length())
        } finally {
            dir.deleteRecursively()
        }
        assertEquals("5:00", AudioPcm.stamp(300)); assertEquals("1:02:03", AudioPcm.stamp(3723))
    }

    @Test
    fun picksTranscriptionService() {
        val sf = Provider(name = "硅基流动", baseUrl = "https://api.siliconflow.cn/v1/", apiKey = "k1")
        val oa = Provider(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKey = "k2")
        assertEquals("FunAudioLLM/SenseVoiceSmall", Transcriber.engine(listOf(oa, sf))!!.model)
        assertEquals("https://api.siliconflow.cn/v1", Transcriber.engine(listOf(sf))!!.baseUrl)
        assertEquals("whisper-1", Transcriber.engine(listOf(oa, sf.copy(apiKey = "")))!!.model)
        assertNull(Transcriber.engine(listOf(Provider(name = "Kimi", baseUrl = "https://api.moonshot.cn/v1", apiKey = "x"))))
        assertEquals("你好，今天开会。", Transcriber.clean("<|zh|><|NEUTRAL|><|Speech|><|withitn|>你好，今天开会。"))
    }

    @Test
    fun timesPagesAndSizes() {
        assertEquals(83_000L, VideoEdit.parseTime("1:23")); assertEquals(3_723_000L, VideoEdit.parseTime("1:02:03"))
        assertEquals(90_500L, VideoEdit.parseTime("90.5")); assertEquals(83_000L, VideoEdit.parseTime("1：23"))
        assertNull(VideoEdit.parseTime("abc")); assertNull(VideoEdit.parseTime(""))
        // 720p：横屏高 720；竖屏（1080×1920）宽 720，高按比例
        assertEquals(720, VideoEdit.heightFor(720, 1920, 1080)); assertEquals(1280, VideoEdit.heightFor(720, 1080, 1920))
        assertEquals(listOf(0, 1, 2, 4, 7, 8, 9), pageList("1-3,5，8-", 10))
        assertEquals((0 until 4).toList(), pageList("", 4))
        assertEquals(listOf(3), pageList("4-99", 4))
    }

    @Test
    fun phonePaths() {
        val a = PhoneFolder("content://a", "Download")
        val b = PhoneFolder("content://b", "工作")
        assertEquals(a to listOf("x", "y.pdf"), PhoneFiles.split(listOf(a, b), "Download/x/y.pdf"))
        assertEquals(b to listOf("报价.xlsx"), PhoneFiles.split(listOf(a, b), "/工作/报价.xlsx"))
        // 只有一个文件夹：可以不写文件夹名
        assertEquals(a to listOf("y.pdf"), PhoneFiles.split(listOf(a), "y.pdf"))
        assertTrue(runCatching { PhoneFiles.split(listOf(a, b), "别处/y.pdf") }.exceptionOrNull() is PhoneFiles.Missing)
        assertTrue(runCatching { PhoneFiles.split(listOf(a), "Download/../x") }.exceptionOrNull() is PhoneFiles.Missing)
        assertTrue(runCatching { PhoneFiles.split(emptyList(), "x") }.exceptionOrNull() is PhoneFiles.Missing)
    }

    @Test
    fun shareRequestFindsTheLink() {
        val r = Downloads.shareRequest("7.94 复制打开抖音，看看【小猫的作品】好可爱 https://v.douyin.com/iABCdEf/ 07/18 a@b.cn")
        assertTrue(r, r.contains("链接：https://v.douyin.com/iABCdEf/\n"))
        assertTrue(r.contains("to=phone"))
    }
}
