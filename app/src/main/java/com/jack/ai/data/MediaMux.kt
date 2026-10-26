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
}
