package com.jack.ai.data.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

/**
 * 一帧帧的画面（ARGB 像素）编成 H.264 的 MP4（没有声音，配音配乐交给 make_video 合）。
 * 用手机自带的编码器，输入走 YUV420（[Yuv] 转），时间戳按第几帧算，不看真实时间：渲染多慢都不影响成片的节奏。
 */
class FrameEncoder(private val out: File, val width: Int, val height: Int, val fps: Int) : Closeable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var started = false
    private var frames = 0L
    private val info = MediaCodec.BufferInfo()
    private var closed = false

    init {
        require(width % 2 == 0 && height % 2 == 0) { "宽高要是双数" }
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * fps / 8).toInt().coerceIn(2_000_000, 12_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            runCatching { codec.release() }; runCatching { muxer.release() }
            throw IllegalStateException("手机的视频编码器不支持 ${width}×$height：${e.message}", e)
        }
    }

    val count: Long get() = frames

    /** 加一帧：argb 是 width × height 个像素（Bitmap.getPixels 拿到的）。 */
    fun add(argb: IntArray) {
        val pts = frames * 1_000_000L / fps
        while (true) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val img = codec.getInputImage(i) ?: throw IllegalStateException("编码器没给输入画面")
                val p = img.planes
                Yuv.write(argb, width, height,
                    Yuv.Plane(p[0].buffer, p[0].rowStride, p[0].pixelStride),
                    Yuv.Plane(p[1].buffer, p[1].rowStride, p[1].pixelStride),
                    Yuv.Plane(p[2].buffer, p[2].rowStride, p[2].pixelStride))
                codec.queueInputBuffer(i, 0, width * height * 3 / 2, pts, 0)
                break
            }
            drain(false)
        }
        frames++
        drain(false)
    }

    /** 收尾：告诉编码器没有了，把剩下的写完。 */
    fun finish() {
        while (true) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) { codec.queueInputBuffer(i, 0, 0, frames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
            drain(false)
        }
        drain(true)
    }

    private fun drain(toEnd: Boolean) {
        var idle = 0
        while (true) {
            val i = codec.dequeueOutputBuffer(info, 10_000)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!toEnd || ++idle > 500) return }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                }
                i >= 0 -> {
                    val buf: ByteBuffer = codec.getOutputBuffer(i) ?: ByteBuffer.allocate(0)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }; runCatching { codec.release() }
        runCatching { if (started) muxer.stop() }; runCatching { muxer.release() }
    }
}

/** ARGB 转 YUV420（BT.709、有限范围，和编码器里写的一致）：亮度每个像素一个，色度 2×2 取平均。 */
object Yuv {
    class Plane(val buf: ByteBuffer, val rowStride: Int, val pixelStride: Int)

    fun write(argb: IntArray, w: Int, h: Int, y: Plane, u: Plane, v: Plane) {
        val row = ByteArray(w)
        for (j in 0 until h) {
            val base = j * w
            for (i in 0 until w) {
                val c = argb[base + i]
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                row[i] = (16 + ((47 * r + 157 * g + 16 * b + 128) shr 8)).toByte()
            }
            if (y.pixelStride == 1) { y.buf.position(j * y.rowStride); y.buf.put(row, 0, w) }
            else for (i in 0 until w) y.buf.put(j * y.rowStride + i * y.pixelStride, row[i])
        }
        for (j in 0 until h / 2) {
            for (i in 0 until w / 2) {
                var r = 0; var g = 0; var b = 0
                for (k in 0..3) {
                    val c = argb[(2 * j + k / 2) * w + 2 * i + k % 2]
                    r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff
                }
                r = r shr 2; g = g shr 2; b = b shr 2
                val cu = 128 + ((-26 * r - 87 * g + 112 * b + 128) shr 8)
                val cv = 128 + ((112 * r - 102 * g - 10 * b + 128) shr 8)
                u.buf.put(j * u.rowStride + i * u.pixelStride, cu.coerceIn(0, 255).toByte())
                v.buf.put(j * v.rowStride + i * v.pixelStride, cv.coerceIn(0, 255).toByte())
            }
        }
    }
}
