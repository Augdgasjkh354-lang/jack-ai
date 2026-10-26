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

        fun header(dataBytes: Long): ByteArray {
            val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt()); b.put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
            b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
            return b.array()
        }
    }

    /** 第几段从几分几秒开始，标在文字稿里。 */
    fun stamp(sec: Long): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
}
