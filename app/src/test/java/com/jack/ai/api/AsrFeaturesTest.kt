package com.jack.ai.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 离线语音识别的特征要和参考实现（numpy 版，拿 SenseVoice 跑示例音频认对了「开放时间早上9点至下午5点。」）一致。
 * 用一段确定的合成信号两边各算一遍，对比几个点。
 */
class AsrFeaturesTest {
    private val pcm = ShortArray(16000) { i ->
        (3000 * Math.sin(2 * Math.PI * 440 * i / 16000) + 1500 * Math.sin(2 * Math.PI * 1234 * i / 16000) + ((i.toLong() * 7919 % 201) - 100)).toInt().toShort()
    }

    @Test
    fun fbankAndLfrMatchTheReference() {
        val f = AsrFeatures.fbank(pcm)
        assertEquals(98, f.size)
        assertEquals(80, f[0].size)
        val tol = 2e-3
        assertEquals(9.990597225663574, f[0][0].toDouble(), tol)
        assertEquals(9.65803679925802, f[0][5].toDouble(), tol)
        assertEquals(11.605298981379475, f[10][40].toDouble(), tol)
        assertEquals(13.244315025766346, f[50][79].toDouble(), tol)
        assertEquals(17.724591696656415, f[97][12].toDouble(), tol)
        val l = AsrFeatures.lfr(f)
        assertEquals(17, l.size)
        assertEquals(560, l[0].size)
        assertEquals(9.990597225663574, l[0][0].toDouble(), tol)
        assertEquals(12.683801342174267, l[5][300].toDouble(), tol)
        assertEquals(13.200053398498287, l[16][559].toDouble(), tol)
    }

    @Test
    fun ctcDecodeCollapsesRepeatsDropsBlanksAndTags() {
        val tokens = listOf("<unk>", "<|zh|>", "<|withitn|>", "开", "放", "▁ok", "。")
        // 3,3 合成一个「开」；中间隔了空白的 3 再算一个「开」；4,4 合成「放」
        assertEquals("开开放 ok。", AsrFeatures.decode(intArrayOf(1, 2, 0, 3, 3, 0, 3, 4, 4, 5, 6), tokens))
    }

    @Test
    fun mvnAndWavHeader() {
        val a = (1..560).joinToString(" ") { "1" }
        val b = (1..560).joinToString(" ") { "2" }
        val (shift, scale) = AsrFeatures.parseMvn("<AddShift> 560 560\n<LearnRateCoef> 0 [ $a ]\n<Rescale> 560 560\n<LearnRateCoef> 0 [ $b ]\n")
        assertEquals(1f, shift[0]); assertEquals(2f, scale[559])
        val row = arrayOf(FloatArray(560) { 3f })
        assertEquals(8f, AsrFeatures.normalize(row, shift, scale)[0][100])
        val wav = ByteArray(44 + 4).also { "RIFF".toByteArray().copyInto(it, 0); "WAVE".toByteArray().copyInto(it, 8)
            "fmt ".toByteArray().copyInto(it, 12); it[16] = 16; "data".toByteArray().copyInto(it, 36) }
        assertEquals(44, AsrFeatures.dataOffset(wav))
    }
}
