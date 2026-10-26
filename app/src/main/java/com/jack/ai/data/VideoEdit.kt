package com.jack.ai.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 剪视频、压视频：用 Media3 Transformer（手机自带的硬件编解码器），输出 H.264 + AAC 的 mp4，哪里都能放。
 * Transformer 要在有 Looper 的线程上用，这里统一放主线程，干活在它自己的后台线程。
 */
object VideoEdit {
    /** startMs / endMs：只要这一段（null = 从头 / 到尾）；height：缩到多高（null = 不缩）；bitrate：视频码率（bps，null = 编码器自己定）。 */
    data class Spec(val startMs: Long? = null, val endMs: Long? = null, val height: Int? = null, val bitrate: Int? = null, val mute: Boolean = false)

    suspend fun run(ctx: Context, input: File, out: File, spec: Spec, progress: (Int) -> Unit = {}): Unit = withContext(Dispatchers.Main) {
        out.delete()
        suspendCancellableCoroutine { cont ->
            val clip = MediaItem.ClippingConfiguration.Builder().apply {
                spec.startMs?.let { setStartPositionMs(it) }
                spec.endMs?.let { setEndPositionMs(it) }
            }.build()
            val item = MediaItem.Builder().setUri(Uri.fromFile(input)).setClippingConfiguration(clip).build()
            val effects = spec.height?.let { Effects(emptyList(), listOf(Presentation.createForHeight(it))) } ?: Effects.EMPTY
            val edited = EditedMediaItem.Builder(item).setEffects(effects).setRemoveAudio(spec.mute).build()
            val builder = Transformer.Builder(ctx).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            spec.bitrate?.let { b ->
                builder.setEncoderFactory(DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(b).build()).build())
            }
            val handler = Handler(Looper.getMainLooper())
            var t: Transformer? = null
            val tick = object : Runnable {
                val holder = ProgressHolder()
                override fun run() {
                    val tr = t ?: return
                    if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress)
                    handler.postDelayed(this, 1000)
                }
            }
            t = builder.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    handler.removeCallbacks(tick)
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                    handler.removeCallbacks(tick)
                    out.delete()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("视频处理失败（${e.errorCodeName}）：${e.message.orEmpty()}"))
                }
            }).build()
            t.start(edited, out.absolutePath)
            handler.postDelayed(tick, 1000)
            cont.invokeOnCancellation { handler.post { handler.removeCallbacks(tick); t.cancel(); out.delete() } }
        }
    }

    /** 视频多长（毫秒）、宽、高（已按旋转换过）；读不出返回 (-1, 0, 0)。 */
    fun info(f: File): Triple<Long, Int, Int> = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            fun k(key: Int) = r.extractMetadata(key)?.toLongOrNull() ?: 0L
            val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            var w = k(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            var h = k(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            if (k(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 == 90L) { val x = w; w = h; h = x }
            Triple(d, w, h)
        } finally {
            r.release()
        }
    }.getOrDefault(Triple(-1L, 0, 0))

    /** 「720p」指短边 720：横屏视频输出高 720，竖屏视频输出高按比例放大（宽 720）。返回要交给 Presentation 的高度。 */
    fun heightFor(short: Int, w: Int, h: Int): Int =
        if (w <= 0 || h <= 0 || h <= w) short else (short.toLong() * h / w).toInt().let { it - it % 2 }

    /** "1:23"、"01:02:03"、"83"、"83.5" → 毫秒；认不出返回 null。 */
    fun parseTime(s: String): Long? {
        val t = s.trim().ifEmpty { return null }
        val parts = t.split(':', '：')
        if (parts.size > 3) return null
        var sec = 0.0
        for (p in parts) sec = sec * 60 + (p.trim().toDoubleOrNull() ?: return null)
        return (sec * 1000).toLong().takeIf { it >= 0 }
    }
}
