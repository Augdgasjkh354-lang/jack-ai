package com.jack.ai.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 用手机自带的 MediaExtractor / MediaMuxer 拼视频：不重新编码，只是换个盒子装，很快。
 * - 分开下的视频和音频（B 站这类 DASH）合成一个 mp4；
 * - HLS 拼出来的 .ts 换成 mp4（相册能直接放）；
 * - 只要声音时把音轨单独存成 .m4a。
 * 遇到 mp4 装不下的编码（老的 mp3 音轨之类）会抛错，调用方留着原文件。
 */
object MediaMux {
    /** 从 inputs 里取视频轨（audioOnly 时不要）和音频轨（各取第一条），写进 out。返回写进去的轨道数。 */
    fun mux(inputs: List<File>, out: File, audioOnly: Boolean = false): Int {
        data class Src(val ex: MediaExtractor, val track: Int, var dst: Int = -1, var done: Boolean = false)
        val opened = mutableListOf<MediaExtractor>()
        val srcs = mutableListOf<Src>()
        var haveVideo = audioOnly
        var haveAudio = false
        try {
            for (f in inputs) {
                val ex = MediaExtractor().also { opened += it }
                ex.setDataSource(f.absolutePath)
                for (i in 0 until ex.trackCount) {
                    val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                    val take = (mime.startsWith("video/") && !haveVideo).also { if (it) haveVideo = true } ||
                        (mime.startsWith("audio/") && !haveAudio).also { if (it) haveAudio = true }
                    if (take) srcs += Src(ex, i)
                }
            }
            if (srcs.isEmpty()) throw IllegalStateException(if (audioOnly) "里面没有音轨" else "认不出里面的音视频")
            out.delete()
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                var maxInput = 1024 * 1024
                for (s in srcs) {
                    val fmt = s.ex.getTrackFormat(s.track)
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = maxOf(maxInput, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    s.ex.selectTrack(s.track)
                    s.dst = muxer.addTrack(fmt)
                }
                muxer.start()
                val buf = ByteBuffer.allocate(maxInput)
                val info = MediaCodec.BufferInfo()
                // 按时间先后交替写，播放器边读边放才顺
                while (true) {
                    val s = srcs.filter { !it.done }.minByOrNull { it.ex.sampleTime.takeIf { t -> t >= 0 } ?: Long.MAX_VALUE } ?: break
                    // 一个文件里选了两条轨（.ts 里的音视频）：读到的样本可能是另一条的
                    val track = s.ex.sampleTrackIndex
                    if (track < 0) { s.done = true; continue }
                    val owner = srcs.firstOrNull { it.ex === s.ex && it.track == track } ?: s
                    buf.clear()
                    val size = s.ex.readSampleData(buf, 0)
                    if (size < 0) { srcs.filter { it.ex === s.ex }.forEach { it.done = true }; continue }
                    info.set(0, size, s.ex.sampleTime, if (s.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(owner.dst, buf, info)
                    if (!s.ex.advance()) srcs.filter { it.ex === s.ex }.forEach { it.done = true }
                }
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
            }
            if (out.length() == 0L) throw IllegalStateException("拼出来是空的")
            return srcs.size
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            opened.forEach { runCatching { it.release() } }
        }
    }

    /**
     * 把几段同样参数录的音频首尾接成一个 m4a（录音分段存的那几段）：不重新编码，后一段的时间接着前一段往后排。
     * 返回总时长（微秒）。
     */
    fun concatAudio(inputs: List<File>, out: File): Long {
        if (inputs.isEmpty()) throw IllegalArgumentException("没有可拼的录音")
        out.delete()
        var muxer: MediaMuxer? = null
        try {
            var dst = -1
            var offset = 0L
            var buf = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for (f in inputs) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(f.absolutePath)
                    val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                        ?: throw IllegalStateException("「${f.name}」里没有声音")
                    val fmt = ex.getTrackFormat(track)
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).let { if (it > buf.capacity()) buf = ByteBuffer.allocate(it) }
                    if (muxer == null) {
                        muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        dst = muxer.addTrack(fmt)
                        muxer.start()
                    }
                    ex.selectTrack(track)
                    // AAC 一帧 1024 个采样：每段最后一帧的时长按这个算，下一段从它后面接上
                    val frameUs = 1024L * 1_000_000 / (if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 16_000)
                    var last = -1L
                    while (true) {
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        last = ex.sampleTime
                        info.set(0, size, offset + last, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer!!.writeSampleData(dst, buf, info)
                        if (!ex.advance()) break
                    }
                    if (last >= 0) offset += last + frameUs
                } finally {
                    runCatching { ex.release() }
                }
            }
            muxer!!.stop()
            if (out.length() == 0L) throw IllegalStateException("拼出来是空的")
            return offset
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            runCatching { muxer?.release() }
        }
    }

    /**
     * 解码第一条音轨，换成 16 kHz 单声道，按 chunkSec 秒一段写成 WAV，放进 dir。返回 (各段文件, 总秒数)。
     * progress(已解到第几微秒, 总微秒或 -1)。
     */
    fun toWav(input: File, dir: File, chunkSec: Int, maxSec: Long, progress: (Long, Long) -> Unit = { _, _ -> }): Pair<List<File>, Long> {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val chunks = AudioPcm.WavChunks(dir.apply { mkdirs() }, chunkSec)
        try {
            ex.setDataSource(input.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalStateException("里面没有声音")
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val total = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else -1L
            if (total > maxSec * 1_000_000) throw IllegalStateException("有 ${AudioPcm.stamp(total / 1_000_000)} 长，超过了 ${AudioPcm.stamp(maxSec)} 的上限")
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            c.configure(fmt, null, null, 0)
            c.start()
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val resampler = AudioPcm.Resampler(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var shorts = ShortArray(0)
            var mono = FloatArray(0)
            var written = 0L
            while (true) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    resampler.changeRate(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val b = c.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder())
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val sb = b.asShortBuffer()
                        val len = sb.remaining()
                        if (shorts.size < len) { shorts = ShortArray(len); mono = FloatArray(len) }
                        sb.get(shorts, 0, len)
                        val frames = AudioPcm.mono(shorts, len, channels, mono)
                        resampler.push(mono, frames) { chunks.write(it); written++ }
                        // 元数据里没写时长时上面的上限拦不住：按实际解出来的采样数算，超了就停（已写的文件由外层 catch 删掉）
                        if (written > maxSec * AudioPcm.RATE) throw IllegalStateException("转出来已超过 ${AudioPcm.stamp(maxSec)} 的上限")
                        progress(info.presentationTimeUs, total)
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            chunks.close()
            if (written == 0L) throw IllegalStateException("没解出声音")
            return chunks.files to written / AudioPcm.RATE
        } catch (e: Exception) {
            chunks.close()
            chunks.files.forEach { it.delete() }
            throw e
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
