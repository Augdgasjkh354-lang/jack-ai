package com.jack.ai.data

import java.io.File
import java.io.RandomAccessFile

/**
 * 转文字前的声音处理（纯计算，能在电脑上测）：解码出来的 PCM 合成单声道、换成 16 kHz，
 * 按段写成 WAV（语音识别接口都认 16 kHz 单声道 WAV，一段 5 分钟约 9.6 MB）。
 */
object AudioPcm {
    const val RATE = 16_000

    /** 线性插值换采样率。一块块喂进来，块与块之间接得上。 */
    class Resampler(private var from: Int, private val to: Int = RATE) {
        // 下一个要输出的点在当前块里的位置（-1 到 0 之间表示落在上一块最后一个点和这一块第一个点之间）
        private var pos = 0.0
        private var prev = 0f
        private var started = false

        fun changeRate(rate: Int) { if (rate > 0) from = rate }

        fun push(samples: FloatArray, n: Int, out: (Float) -> Unit) {
            if (n <= 0) return
            if (!started) { prev = samples[0]; started = true }
            val step = from.toDouble() / to
            while (true) {
                val i0 = kotlin.math.floor(pos).toInt()
                if (i0 + 1 >= n) break
                val a = if (i0 < 0) prev else samples[i0]
                val b = samples[i0 + 1]
                out(a + (b - a) * (pos - i0).toFloat())
                pos += step
            }
            pos -= n
            prev = samples[n - 1]
        }
    }

    /** 16 位 PCM（多声道交错）合成单声道，范围 -1..1。返回帧数。 */
    fun mono(pcm: ShortArray, len: Int, channels: Int, into: FloatArray): Int {
        val ch = channels.coerceAtLeast(1)
        val frames = len / ch
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) sum += pcm[f * ch + c]
            into[f] = sum / ch / 32768f
        }
        return frames
    }

    /** 把 16 kHz 单声道一段段写成 WAV：每段 chunkSec 秒，文件名 part-001.wav……。 */
    class WavChunks(private val dir: File, private val chunkSec: Int) {
        val files = mutableListOf<File>()
        private var raf: RandomAccessFile? = null
        private var samples = 0L
        private val buf = ByteArray(8192)
        private var bufLen = 0

        fun write(sample: Float) {
            if (raf == null || samples >= chunkSec.toLong() * RATE) next()
            val v = (sample.coerceIn(-1f, 1f) * 32767).toInt()
            buf[bufLen++] = v.toByte(); buf[bufLen++] = (v shr 8).toByte()
            if (bufLen == buf.size) flush()
            samples++
        }

        private fun flush() { raf?.write(buf, 0, bufLen); bufLen = 0 }

        private fun next() {
            close()
            val f = File(dir, "part-%03d.wav".format(files.size + 1))
            files += f
            raf = RandomAccessFile(f, "rw").also { it.setLength(0); it.write(header(0)) }
            samples = 0
        }

        /** 写完：补上文件头里的长度。 */
        fun close() {
            val r = raf ?: return
            flush()
            val data = samples * 2
            r.seek(0); r.write(header(data))
            r.close()
            raf = null
        }

        fun header(dataBytes: Long): ByteArray = wavHeader(dataBytes)
    }

    /** 16 kHz 单声道 16 位 WAV 的文件头。 */
    fun wavHeader(dataBytes: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        return b.array()
    }

    /** 一段 16 kHz 单声道写成一个 WAV。 */
    fun writeWav(f: File, samples: FloatArray, n: Int) {
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
            out[i * 2] = v.toByte(); out[i * 2 + 1] = (v shr 8).toByte()
        }
        f.outputStream().use { it.write(wavHeader(n * 2L)); it.write(out) }
    }

    /**
     * 按说话的停顿切成一句句（带时间的文字稿、字幕用）：30 毫秒一帧算音量，比背景噪声响很多算在说话。
     * 说了至少 [minSec] 秒、又停了 0.3 秒就切；一直不停（背景音乐、语速快）到 [maxSec] 秒就在最近 2 秒里最安静的地方切。
     * 开头的静音不算进句子里（字幕不会提前出来），句尾留 0.15 秒。整段都没说话的丢掉。
     * onSegment(这句从第几个采样开始, 采样, 个数)。
     */
    class SpeechSplitter(
        private val onSegment: (startSample: Long, samples: FloatArray, n: Int) -> Unit,
        minSec: Double = 1.5, maxSec: Double = 15.0,
    ) {
        private val frame = RATE * 30 / 1000
        private val minFrames = (minSec * 1000 / 30).toInt()
        private val maxFrames = (maxSec * 1000 / 30).toInt()
        private val buf = FloatArray((maxFrames + 2) * frame)
        private var len = 0
        private val loud = ArrayList<Float>()
        private val voiced = ArrayList<Boolean>()
        private var start = 0L
        private var floor = 0.003f

        fun push(sample: Float) {
            buf[len++] = sample
            if (len % frame == 0) onFrame()
        }

        fun finish() {
            val frames = voiced.size
            // 句尾的静音只留 5 帧
            var end = frames
            while (end > 0 && !voiced[end - 1]) end--
            val k = minOf(frames, end + 5)
            emit(k, tail = if (k == frames) len - frames * frame else 0)
            len = 0; loud.clear(); voiced.clear()
        }

        private fun onFrame() {
            var sum = 0.0
            for (i in len - frame until len) sum += buf[i] * buf[i]
            val r = kotlin.math.sqrt(sum / frame).toFloat()
            // 噪声底：往下立刻跟，往上慢慢跟（说话时不会被拉上去）
            floor = if (r < floor) r else floor * 0.998f + r * 0.002f
            // 阈值有上限：一直很响（背景音乐、不停顿）时噪声底会慢慢涨上去，不能把整段都当成静音
            val v = r >= maxOf(0.006f, minOf(floor * 3, 0.03f))
            loud += r; voiced += v
            val frames = voiced.size
            val anyVoice = voiced.any { it }
            var silence = 0
            while (silence < frames && !voiced[frames - 1 - silence]) silence++
            when {
                !anyVoice && frames > 5 -> drop(1)
                anyVoice && silence >= 10 && frames - silence >= minFrames -> cut(frames - silence + 5)
                frames >= maxFrames -> {
                    val from = maxOf(minFrames, frames - 66)
                    var q = frames - 1
                    for (i in from until frames) if (loud[i] < loud[q]) q = i
                    cut(q + 1)
                }
            }
        }

        private fun cut(k: Int) { emit(k, 0); drop(k) }

        /** 前 k 帧（加上 tail 个不满一帧的采样）里说话够 5 帧就交出去。 */
        private fun emit(k: Int, tail: Int) {
            if (voiced.take(k).count { it } < 5) return
            val n = minOf(len, k * frame + maxOf(0, tail))
            onSegment(start, buf.copyOf(n), n)
        }

        private fun drop(k: Int) {
            val n = minOf(len, k * frame)
            System.arraycopy(buf, n, buf, 0, len - n)
            len -= n; start += n
            repeat(minOf(k, voiced.size)) { loud.removeAt(0); voiced.removeAt(0) }
        }
    }

    /** 第几段从几分几秒开始，标在文字稿里。 */
    fun stamp(sec: Long): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
}
