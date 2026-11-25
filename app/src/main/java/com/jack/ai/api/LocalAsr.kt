package com.jack.ai.api

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.jack.ai.data.LocalModels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * 离线语音识别：阿里开源的 SenseVoice-Small（量化版，约 230MB，魔搭下载），整个在手机上跑，不联网、不花钱。
 * 中文准，自带标点；也认英文、粤语、日语、韩语。
 * 流程和 FunASR 的 ONNX 推理一样：Kaldi 风格的 80 维梅尔特征 → 每 6 帧拼 7 帧（LFR）→ 按 am.mvn 归一化 → 模型 → CTC 贪心解码。
 * 下好以后 [Transcriber] 优先用它：语音输入、语音对话、录音纪要、视频转文字都不再走网上的接口。
 */
object LocalAsr {
    private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** 模型下好了、核对过了。 */
    fun ready(): Boolean = app?.let { LocalModels.ready(it, LocalModels.SENSEVOICE) } ?: false

    /** 一次喂给模型最长多少秒：再长内存吃紧，长录音切成这么长的段。 */
    const val CHUNK_SEC = 30

    private class Loaded(val session: OrtSession, val tokens: List<String>, val shift: FloatArray, val scale: FloatArray)
    private var loaded: Loaded? = null

    @Synchronized
    private fun load(): Loaded {
        loaded?.let { return it }
        val ctx = app ?: error("离线语音识别还没准备好")
        val dir = LocalModels.dir(ctx, LocalModels.SENSEVOICE)
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        val session = env.createSession(File(dir, "model_quant.onnx").path, opts)
        val tokens = (Json.parseToJsonElement(File(dir, "tokens.json").readText()) as JsonArray).map { it.jsonPrimitive.content }
        val (shift, scale) = AsrFeatures.parseMvn(File(dir, "am.mvn").readText())
        return Loaded(session, tokens, shift, scale).also { loaded = it }
    }

    /** 删了模型、内存紧时放掉。 */
    @Synchronized
    fun release() { loaded?.session?.close(); loaded = null }

    /** 一个 16kHz、16 位、单声道的 WAV（Kavi 自己录的、MediaMux 转的都是）转文字；长的按 [CHUNK_SEC] 切段。 */
    fun transcribeWav(wav: File): String {
        val bytes = wav.readBytes()
        val start = AsrFeatures.dataOffset(bytes)
        val n = (bytes.size - start) / 2
        val pcm = ShortArray(n)
        ByteBuffer.wrap(bytes, start, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        val step = CHUNK_SEC * 16000
        return (0 until n step step).joinToString("") { from -> transcribe(pcm.copyOfRange(from, minOf(n, from + step))) }.trim()
    }

    /** 一段 PCM（16kHz）转文字。太短（不到 0.1 秒）返回空。 */
    @Synchronized
    fun transcribe(pcm: ShortArray): String {
        if (pcm.size < 1600) return ""
        val m = load()
        val feats = AsrFeatures.normalize(AsrFeatures.lfr(AsrFeatures.fbank(pcm)), m.shift, m.scale)
        val t = feats.size
        val flat = FloatBuffer.allocate(t * AsrFeatures.LFR_DIM)
        feats.forEach { flat.put(it) }
        flat.rewind()
        val env = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(env, flat, longArrayOf(1, t.toLong(), AsrFeatures.LFR_DIM.toLong())).use { speech ->
            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(t)), longArrayOf(1)).use { len ->
                // 语言 0 是自动认；textnorm 14 是带标点、数字写成阿拉伯数字
                OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(0)), longArrayOf(1)).use { lang ->
                    OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(14)), longArrayOf(1)).use { norm ->
                        m.session.run(mapOf("speech" to speech, "speech_lengths" to len, "language" to lang, "textnorm" to norm)).use { out ->
                            val logits = (out[0] as OnnxTensor).floatBuffer
                            val outLen = (out[1] as OnnxTensor).intBuffer.get(0)
                            val vocab = m.tokens.size
                            val ids = IntArray(outLen) { f ->
                                var best = 0; var bv = Float.NEGATIVE_INFINITY
                                val base = f * vocab
                                for (k in 0 until vocab) { val v = logits.get(base + k); if (v > bv) { bv = v; best = k } }
                                best
                            }
                            return AsrFeatures.decode(ids, m.tokens)
                        }
                    }
                }
            }
        }
    }
}

/** 离线识别的特征提取和解码（纯计算，单测能跑）。和 Kaldi 的 fbank、FunASR 的 WavFrontend 对齐。 */
object AsrFeatures {
    const val MELS = 80
    const val LFR_M = 7
    const val LFR_N = 6
    const val LFR_DIM = MELS * LFR_M
    private const val FRAME = 400   // 25ms
    private const val SHIFT = 160   // 10ms
    private const val NFFT = 512
    private const val RATE = 16000

    private val window = DoubleArray(FRAME) { 0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1)) }

    private fun mel(f: Double) = 1127.0 * ln(1 + f / 700.0)

    /** 80 个三角滤波器（Kaldi 的画法：20Hz 到 8kHz，梅尔刻度上等分），只存非零的那一段。 */
    private class Bank(val from: Int, val w: DoubleArray)
    private val banks: List<Bank> by lazy {
        val lo = mel(20.0); val hi = mel(RATE / 2.0)
        val delta = (hi - lo) / (MELS + 1)
        val bw = RATE.toDouble() / NFFT
        (0 until MELS).map { b ->
            val l = lo + b * delta; val c = lo + (b + 1) * delta; val r = lo + (b + 2) * delta
            val w = DoubleArray(NFFT / 2 + 1) { k ->
                val m = mel(bw * k)
                if (k == NFFT / 2 || m <= l || m >= r) 0.0 else if (m <= c) (m - l) / (c - l) else (r - m) / (r - c)
            }
            val first = w.indexOfFirst { it > 0 }.coerceAtLeast(0)
            val last = w.indexOfLast { it > 0 }.coerceAtLeast(first)
            Bank(first, w.copyOfRange(first, last + 1))
        }
    }

    /** 每 10ms 一帧的 80 维对数梅尔能量。 */
    fun fbank(pcm: ShortArray): Array<FloatArray> {
        if (pcm.size < FRAME) return emptyArray()
        val n = 1 + (pcm.size - FRAME) / SHIFT
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        val frame = DoubleArray(FRAME)
        val power = DoubleArray(NFFT / 2 + 1)
        return Array(n) { i ->
            val off = i * SHIFT
            var mean = 0.0
            for (j in 0 until FRAME) { frame[j] = pcm[off + j].toDouble(); mean += frame[j] }
            mean /= FRAME
            for (j in 0 until FRAME) frame[j] -= mean
            // 预加重：从后往前做，第一个点用自己
            for (j in FRAME - 1 downTo 1) frame[j] -= 0.97 * frame[j - 1]
            frame[0] -= 0.97 * frame[0]
            re.fill(0.0); im.fill(0.0)
            for (j in 0 until FRAME) re[j] = frame[j] * window[j]
            fft(re, im)
            for (k in 0..NFFT / 2) power[k] = re[k] * re[k] + im[k] * im[k]
            FloatArray(MELS) { b ->
                val bank = banks[b]
                var s = 0.0
                for (k in bank.w.indices) s += bank.w[k] * power[bank.from + k]
                ln(max(s, 1.1920929e-7)).toFloat()
            }
        }
    }

    /** 每 [LFR_N] 帧取一帧，把它和后面一共 [LFR_M] 帧拼成 560 维；开头补 3 帧第一帧，结尾不够用最后一帧补。 */
    fun lfr(f: Array<FloatArray>): Array<FloatArray> {
        if (f.isEmpty()) return emptyArray()
        val padded = List((LFR_M - 1) / 2) { f[0] } + f.toList()
        val out = (f.size + LFR_N - 1) / LFR_N
        return Array(out) { i ->
            FloatArray(LFR_DIM).also { row ->
                for (j in 0 until LFR_M) padded[minOf(i * LFR_N + j, padded.lastIndex)].copyInto(row, j * MELS)
            }
        }
    }

    /** am.mvn 里的两行：先加（AddShift），再乘（Rescale），各 560 个数。 */
    fun parseMvn(text: String): Pair<FloatArray, FloatArray> {
        val vecs = Regex("""\[([^\]]*)]""").findAll(text).map { it.groupValues[1].trim().split(Regex("\\s+")) }
            .filter { it.size == LFR_DIM }.map { v -> FloatArray(v.size) { v[it].toFloat() } }.toList()
        require(vecs.size >= 2) { "am.mvn 读不出来" }
        return vecs[0] to vecs[1]
    }

    fun normalize(f: Array<FloatArray>, shift: FloatArray, scale: FloatArray): Array<FloatArray> {
        f.forEach { row -> for (k in row.indices) row[k] = (row[k] + shift[k]) * scale[k] }
        return f
    }

    /** CTC 贪心解码：相邻重复的合成一个、去掉空白（0），拼成字，去掉 <|zh|> 这类标签。 */
    fun decode(ids: IntArray, tokens: List<String>): String {
        val sb = StringBuilder()
        var prev = -1
        for (t in ids) {
            if (t != prev && t != 0) tokens.getOrNull(t)?.let { sb.append(it) }
            prev = t
        }
        return sb.toString().replace('▁', ' ').replace(Regex("""<\|[^|]*\|>"""), "").trim()
    }

    /** WAV 里数据从哪开始：找 "data" 块；找不到当成 44 字节的标准头。 */
    fun dataOffset(b: ByteArray): Int {
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val size = (b[i + 4].toInt() and 0xff) or ((b[i + 5].toInt() and 0xff) shl 8) or ((b[i + 6].toInt() and 0xff) shl 16) or ((b[i + 7].toInt() and 0xff) shl 24)
            if (id == "data") return i + 8
            i += 8 + size
        }
        return minOf(44, b.size)
    }

    /** 原地做 512 点的复数 FFT（基 2）。 */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
