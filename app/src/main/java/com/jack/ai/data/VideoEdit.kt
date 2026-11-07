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
import androidx.media3.transformer.EditedMediaItemSequence
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
 * 剪视频、压视频、拼片段、烧字幕、配背景音乐：用 Media3 Transformer（手机自带的硬件编解码器），输出 H.264 + AAC 的 mp4，哪里都能放。
 * Transformer 要在有 Looper 的线程上用，这里统一放主线程，干活在它自己的后台线程。
 */
object VideoEdit {
    /** startMs / endMs：只要这一段（null = 从头 / 到尾）；height：缩到多高（null = 不缩）；bitrate：视频码率（bps，null = 编码器自己定）。 */
    data class Spec(val startMs: Long? = null, val endMs: Long? = null, val height: Int? = null, val bitrate: Int? = null, val mute: Boolean = false)

    suspend fun run(ctx: Context, input: File, out: File, spec: Spec, progress: (Int) -> Unit = {}) {
        val clip = MediaItem.ClippingConfiguration.Builder().apply {
            spec.startMs?.let { setStartPositionMs(it) }
            spec.endMs?.let { setEndPositionMs(it) }
        }.build()
        val item = MediaItem.Builder().setUri(Uri.fromFile(input)).setClippingConfiguration(clip).build()
        val effects = spec.height?.let { Effects(emptyList(), listOf(Presentation.createForHeight(it))) } ?: Effects.EMPTY
        val edited = EditedMediaItem.Builder(item).setEffects(effects).setRemoveAudio(spec.mute).build()
        export(ctx, Composition.Builder(EditedMediaItemSequence(edited)).build(), out, spec.bitrate, progress)
    }

    /** 按片段（原视频上的时间）剪下来，照填的顺序拼成一个视频。 */
    suspend fun concat(ctx: Context, input: File, segs: List<Subtitles.Seg>, out: File, progress: (Int) -> Unit = {}) {
        val items = segs.map { g ->
            val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(g.startMs).setEndPositionMs(g.endMs).build()
            EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(input)).setClippingConfiguration(clip).build()).build()
        }
        export(ctx, Composition.Builder(EditedMediaItemSequence(items)).build(), out, null, progress)
    }

    /**
     * 整个视频烧上字幕（[cues] 用这个视频上的时间）、配背景音乐（循环到视频结束，音量 [musicVolume]，0..1）。
     * 字幕白字半透明黑底，放在画面下方，一屏最多两行。
     */
    suspend fun decorate(ctx: Context, input: File, out: File, cues: List<Subtitles.Cue>, music: File?, musicVolume: Float, progress: (Int) -> Unit = {}) {
        val shown = Subtitles.forScreen(cues)
        val overlays = if (shown.isEmpty()) emptyList()
            else listOf(SubtitleLine(shown, 0), SubtitleLine(shown, 1))
        val effects = if (overlays.isEmpty()) Effects.EMPTY
            else Effects(emptyList(), listOf(androidx.media3.effect.OverlayEffect(com.google.common.collect.ImmutableList.copyOf<androidx.media3.effect.TextureOverlay>(overlays))))
        val video = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).setEffects(effects).build()
        val sequences = mutableListOf(EditedMediaItemSequence(video))
        if (music != null) {
            val vol = androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
                val v = musicVolume.coerceIn(0f, 1f)
                putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(1, 1).scaleBy(v))
                putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(2, 2).scaleBy(v))
            }
            val bgm = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(music))).setRemoveVideo(true)
                .setEffects(Effects(listOf(vol), emptyList())).build()
            // 循环的音轨跟着最长的那条（视频）一起结束
            sequences += EditedMediaItemSequence(listOf(bgm), true)
        }
        // 视频本身没声音时也要能混进背景音乐
        export(ctx, Composition.Builder(sequences).experimentalSetForceAudioTrack(music != null).build(), out, null, progress)
    }

    /** 字幕的第 [line] 行（0 = 上面那行，只有一行时放在下面那行的位置）。 */
    private class SubtitleLine(private val cues: List<Subtitles.Cue>, private val line: Int) : androidx.media3.effect.TextOverlay() {
        private val starts = LongArray(cues.size) { cues[it].startMs }
        private val blank = android.text.SpannableString(" ")
        private var sizePx = 48
        private var frameH = 1080
        private var cache = HashMap<Int, android.text.SpannableString>()

        override fun configure(videoSize: androidx.media3.common.util.Size) {
            frameH = videoSize.height.coerceAtLeast(1)
            // 字高取短边的 1/18：竖屏 1080 宽是 60 像素，横屏 1080 高也是 60
            sizePx = (minOf(videoSize.width, videoSize.height) / 18).coerceAtLeast(16)
            cache.clear()
        }

        private fun lines(i: Int): List<String> = cues[i].text.split('\n')

        private fun at(ms: Long): Int {
            var i = java.util.Arrays.binarySearch(starts, ms)
            if (i < 0) i = -i - 2
            return if (i >= 0 && ms < cues[i].endMs) i else -1
        }

        override fun getText(presentationTimeUs: Long): android.text.SpannableString {
            val i = at(presentationTimeUs / 1000)
            if (i < 0) return blank
            val ls = lines(i)
            // 两行时 line 0 放上面那行、line 1 放下面；一行时只有 line 1 画
            val text = when {
                ls.size >= 2 -> ls.getOrNull(line)
                line == 1 -> ls[0]
                else -> null
            }?.trim().orEmpty()
            if (text.isEmpty()) return blank
            return cache.getOrPut(i) {
                android.text.SpannableString(" $text ").apply {
                    val n = length
                    setSpan(android.text.style.AbsoluteSizeSpan(sizePx), 0, n, 0)
                    setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE), 0, n, 0)
                    setSpan(android.text.style.BackgroundColorSpan(0x99000000.toInt()), 0, n, 0)
                }
            }
        }

        override fun getOverlaySettings(presentationTimeUs: Long): androidx.media3.effect.OverlaySettings {
            // 坐标是 -1（下边）到 1（上边）；一行高约 1.25 个字高
            val step = 2f * sizePx * 1.25f / frameH
            val y = -0.86f + if (line == 0) step else 0f
            return androidx.media3.effect.OverlaySettings.Builder().setBackgroundFrameAnchor(0f, y).setOverlayFrameAnchor(0f, -1f).build()
        }
    }

    /** 交给 Transformer 导出成 H.264 + AAC 的 mp4，等它做完（取消时停掉、删掉半成品）。 */
    private suspend fun export(ctx: Context, composition: Composition, out: File, bitrate: Int?, progress: (Int) -> Unit): Unit = withContext(Dispatchers.Main) {
        out.delete()
        suspendCancellableCoroutine { cont ->
            val builder = Transformer.Builder(ctx).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            bitrate?.let { b ->
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
            t.start(composition, out.absolutePath)
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
