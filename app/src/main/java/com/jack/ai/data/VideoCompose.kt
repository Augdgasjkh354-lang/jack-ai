package com.jack.ai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * 照剪辑方案出片（Media3 Transformer，手机硬件编码，出 H.264 + AAC 的 mp4）：
 * 1. 图片先各自转成一小段视频（按成片尺寸铺好）；
 * 2. 第一遍：所有片段按顺序拼起来，每段各自剪头尾、变速、铺满或留边、推拉镜头、调色、过黑场、调音量；
 * 3. 第二遍（有花字、字幕或背景音乐才做）：在拼好的片子上压花字和字幕，混进背景音乐，有人说话时音乐自动压低。
 * 分两遍是为了花字、字幕、音乐都按成片的时间走，最稳。
 */
object VideoCompose {
    class Done(val durationMs: Long, val width: Int, val height: Int, val notes: List<String>, val subtitleCount: Int)

    private const val FADE_US = 250_000L

    suspend fun make(
        ctx: Context, plan: VideoPlan.Plan, fileOf: (String) -> File, subtitles: Pair<List<Subtitles.Cue>, String>?, music: File?, voice: File?,
        work: File, out: File, progress: (String) -> Unit,
    ): Done {
        work.mkdirs()
        val notes = mutableListOf<String>()
        // 素材信息：视频读时长、宽高；图片读宽高
        val infos = plan.clips.map { c ->
            val f = fileOf(c.file)
            if (c.image) null else withContext(Dispatchers.IO) { VideoFrames.info(f) }
                ?: throw IllegalStateException("「${c.file}」读不出来：可能不是视频，或者是手机不支持的格式")
        }
        plan.clips.forEachIndexed { i, c ->
            val inf = infos[i] ?: return@forEachIndexed
            if (c.startMs >= inf.durationMs) throw IllegalStateException("第 ${i + 1} 段：「${c.file}」只有 ${Subtitles.stamp(inf.durationMs)} 长，从 ${Subtitles.stamp(c.startMs)} 开始超出了")
        }
        val (w, h) = plan.size ?: run {
            val first = infos.firstOrNull { it != null && it.width > 0 }
            val (sw, sh) = first?.let { it.width to it.height } ?: (1080 to 1920)
            val k = minOf(1f, 1920f / maxOf(sw, sh))
            ((sw * k).toInt() and 1.inv()) to ((sh * k).toInt() and 1.inv())
        }
        val lengths = VideoPlan.lengths(plan.clips) { c -> infos[plan.clips.indexOf(c)]?.durationMs ?: 0L }
        val total = lengths.sum()
        // 字幕：按原视频时间写的对到成片上；按成片时间写的（配音字幕）只往后挪封面的长度
        val cues = subtitles?.let { (list, source) ->
            if (plan.subtitlesOnOutput) list.map { it.copy(startMs = it.startMs + plan.coverMs, endMs = it.endMs + plan.coverMs) }
            else VideoPlan.remapSubtitles(list, plan.clips, lengths, source)
        }.orEmpty()
        val texts = plan.texts.map { it.copy(startMs = it.startMs + plan.coverMs, endMs = it.endMs + plan.coverMs) }
        // 配音：前面补上封面那么长的静音；配音比画面长就说清楚让 Kavi 补画面
        val voiceFile = voice?.let { v ->
            val ms = withContext(Dispatchers.IO) { Wav.durationMs(v) } ?: throw IllegalStateException("「${v.name}」读不出来：配音要用 voiceover 做出来的 wav")
            if (ms + plan.coverMs > total + 300) throw IllegalStateException(
                "配音有 ${Subtitles.stamp(ms)}，画面只有 ${Subtitles.stamp(total - plan.coverMs)}：加几段画面、或者让片段长一点，画面要不短于配音")
            if (plan.coverMs > 0) File(work, "voice.wav").also { withContext(Dispatchers.IO) { Wav.padStart(v, it, plan.coverMs) } } else v
        }
        if (subtitles != null && cues.isEmpty()) notes += "字幕和选的片段对不上（字幕是「${subtitles.second}」的吗？），没加字幕"
        val late = texts.count { it.startMs >= total }
        if (late > 0) notes += "有 $late 条花字的时间超出了成片长度（${Subtitles.stamp(total)}），没显示"

        // 1. 图片 → 一小段视频
        val clips = plan.clips.mapIndexed { i, c ->
            if (!c.image) c.copy(file = fileOf(c.file).absolutePath)
            else {
                progress("出片：准备第 ${i + 1} 段图片")
                val jpg = File(work, "img-$i.jpg")
                withContext(Dispatchers.IO) { prepareImage(fileOf(c.file), jpg) }
                val mp4 = File(work, "img-$i.mp4")
                val item = EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(Uri.fromFile(jpg)).setMimeType(MimeTypes.IMAGE_JPEG).setImageDurationMs(c.durationMs).build(),
                ).setDurationUs(c.durationMs * 1000).setFrameRate(30)
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(w, h, if (c.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT))))
                    .build()
                VideoEdit.export(ctx, Composition.Builder(EditedMediaItemSequence(item)).experimentalSetForceAudioTrack(true).build(), mp4, null) {}
                c.copy(file = mp4.absolutePath, image = false, startMs = 0, endMs = c.durationMs)
            }
        }

        // 2. 拼起来
        val decorate = texts.isNotEmpty() || cues.isNotEmpty() || music != null || voiceFile != null
        val joined = if (decorate) File(work, "joined.mp4") else out
        val items = clips.mapIndexed { i, c ->
            val endMs = c.endMs ?: infos[i]?.durationMs
            val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.startMs).apply { endMs?.let { setEndPositionMs(it) } }.build()
            val lenUs = lengths[i] * 1000
            val video = buildList {
                if (c.speed != 1f) add(SpeedChangeEffect(c.speed))
                add(Presentation.createForWidthAndHeight(w, h, if (c.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT))
                if (c.zoom != "none") add(Zoom(c.zoom == "in", lenUs))
                if (c.filter != "none" || plan.fade) add(Grade(c.filter, plan.fade, plan.fade, lenUs))
            }
            val audio = buildList<AudioProcessor> {
                if (c.speed != 1f) add(SpeedChangingAudioProcessor(ConstantSpeed(c.speed)))
                if (c.volume != 1f && c.volume > 0f) add(volume(c.volume))
            }
            EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(File(c.file))).setClippingConfiguration(clip).build())
                .setRemoveAudio(c.volume == 0f).setEffects(Effects(audio, video)).build()
        }
        progress("出片：拼接 ${items.size} 段")
        val comp = Composition.Builder(EditedMediaItemSequence(items)).experimentalSetForceAudioTrack(true)
            .apply { if (android.os.Build.VERSION.SDK_INT >= 29) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }.build()
        VideoEdit.export(ctx, comp, joined, bitrate(w, h, if (decorate) 1.4f else 1f)) { p -> progress("出片：拼接 $p%") }
        if (!decorate) return Done(total, w, h, notes, 0)

        // 3. 花字、字幕、背景音乐
        val overlays = mutableListOf<TextureOverlay>()
        texts.forEach { t -> overlays += TimedText(TextArt.render(t.text, t.style, w), t.startMs * 1000, t.endMs * 1000, TextArt.anchorY(t.position)) }
        if (cues.isNotEmpty()) overlays += SubtitleTrack(Subtitles.forScreen(cues, 14), w)
        val videoEffects = if (overlays.isEmpty()) emptyList() else listOf(OverlayEffect(ImmutableList.copyOf(overlays)))
        val main = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(joined))).setEffects(Effects(emptyList(), videoEffects)).build()
        val sequences = mutableListOf(EditedMediaItemSequence(main))
        if (voiceFile != null) {
            val fx = if (plan.voiceVolume != 1f) Effects(listOf(volume(plan.voiceVolume)), emptyList()) else Effects.EMPTY
            sequences += EditedMediaItemSequence(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(voiceFile))).setEffects(fx).build())
            notes += "配了音"
        }
        if (music != null) {
            val speech = if (!plan.duck) emptyList() else if (cues.isNotEmpty()) cues.map { it.startMs to it.endMs } else {
                progress("出片：找有人说话的地方（背景音乐要让开）")
                withContext(Dispatchers.IO) { speechIn(joined) + (voiceFile?.let { speechIn(it) }.orEmpty()) }
            }
            if (plan.duck && speech.isNotEmpty()) notes += "背景音乐在有人说话时自动压低了"
            val duck = VideoPlan.duckGain(speech)
            val vol = plan.musicVolume
            val bgm = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(music))).setRemoveVideo(true)
                .setEffects(Effects(listOf(Gain { ms -> vol * duck(ms) }), emptyList())).build()
            sequences += EditedMediaItemSequence(listOf(bgm), true)
        }
        progress("出片：加花字、字幕和音乐")
        VideoEdit.export(ctx, Composition.Builder(sequences).experimentalSetForceAudioTrack(true).build(), out, bitrate(w, h, 1f)) { p -> progress("出片：加花字、字幕和音乐 $p%") }
        return Done(total, w, h, notes, cues.size)
    }

    /** 配音用的 wav（16 位 PCM）：读时长、在前面补静音。 */
    internal object Wav {
        class Fmt(val rate: Int, val channels: Int, val dataOffset: Long, val dataBytes: Long)

        fun fmt(f: File): Fmt? = runCatching {
            java.io.RandomAccessFile(f, "r").use { r ->
                val h = ByteArray(12); r.readFully(h)
                if (String(h, 0, 4) != "RIFF" || String(h, 8, 4) != "WAVE") return null
                var rate = 0; var ch = 0; var bits = 0
                while (r.filePointer < r.length()) {
                    val id = ByteArray(4); r.readFully(id)
                    val size = Integer.reverseBytes(r.readInt()).toLong() and 0xffffffffL
                    when (String(id)) {
                        "fmt " -> { val b = ByteArray(size.toInt()); r.readFully(b)
                            val bb = ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            bb.short; ch = bb.short.toInt(); rate = bb.int; bb.int; bb.short; bits = bb.short.toInt() }
                        "data" -> return if (bits == 16 && rate > 0 && ch > 0) Fmt(rate, ch, r.filePointer, minOf(size, r.length() - r.filePointer)) else null
                        else -> r.seek(r.filePointer + size + (size and 1))
                    }
                }
                null
            }
        }.getOrNull()

        fun durationMs(f: File): Long? = fmt(f)?.let { it.dataBytes * 1000 / (it.rate.toLong() * it.channels * 2) }

        fun write(f: File, pcm: ByteArray, rate: Int, channels: Int = 1) {
            val h = ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            h.put("data".toByteArray()).putInt(pcm.size)
            f.outputStream().use { it.write(h.array()); it.write(pcm) }
        }

        fun padStart(src: File, dst: File, ms: Long) {
            val fm = fmt(src) ?: throw IllegalStateException("「${src.name}」不是 16 位 wav")
            val pad = (fm.rate.toLong() * ms / 1000).toInt() * fm.channels * 2
            val data = java.io.RandomAccessFile(src, "r").use { r -> r.seek(fm.dataOffset); ByteArray(fm.dataBytes.toInt()).also { r.readFully(it) } }
            write(dst, ByteArray(pad) + data, fm.rate, fm.channels)
        }
    }

    /** 码率：1080×1920 约 10Mbps（抖音、视频号上传不会被压得太糊）；中间文件高一点，第二遍再压。 */
    private fun bitrate(w: Int, h: Int, k: Float) = (w.toLong() * h * 30 * 0.16 * k).toInt().coerceIn(2_000_000, 20_000_000)

    /** 图片：按 EXIF 转正、缩到长边 2560 以内，存成 JPEG（HEIC 之类 Transformer 不认的也转成能认的）。 */
    private fun prepareImage(src: File, dst: File) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, o)
        if (o.outWidth <= 0) throw IllegalStateException("「${src.name}」打不开，不像是图片")
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 2560) sample *= 2
        var b = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IllegalStateException("「${src.name}」打不开")
        val deg = runCatching {
            when (android.media.ExifInterface(src.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        }.getOrDefault(0f)
        if (deg != 0f) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        // 编码器要偶数宽高
        if (b.width % 2 != 0 || b.height % 2 != 0) b = Bitmap.createBitmap(b, 0, 0, b.width and 1.inv(), b.height and 1.inv())
        dst.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    /** 拼好的片子里有人说话的时间段（毫秒），给背景音乐让路用。 */
    private fun speechIn(f: File): List<Pair<Long, Long>> = runCatching {
        val out = mutableListOf<Pair<Long, Long>>()
        val split = AudioPcm.SpeechSplitter({ start, _, n -> out += start * 1000 / AudioPcm.RATE to (start + n) * 1000 / AudioPcm.RATE })
        MediaMux.decode16k(f, 3 * 3600L) { split.push(it) }
        split.finish()
        out
    }.getOrDefault(emptyList())

    private fun volume(v: Float) = ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(v))
        putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(v))
    }

    private class ConstantSpeed(val s: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long) = s
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
    }

    /** 推拉镜头：这一段从头到尾慢慢放大（in）或缩小（out）12%。时间从这一段的第一帧算。 */
    private class Zoom(val zoomIn: Boolean, val lengthUs: Long) : MatrixTransformation {
        private var base = Long.MIN_VALUE
        override fun getMatrix(presentationTimeUs: Long): Matrix {
            if (base == Long.MIN_VALUE) base = presentationTimeUs
            val p = ((presentationTimeUs - base).toFloat() / lengthUs.coerceAtLeast(1)).coerceIn(0f, 1f)
            val s = if (zoomIn) 1f + 0.12f * p else 1.12f - 0.12f * p
            return Matrix().apply { setScale(s, s) }
        }
    }

    /** 调色加黑场过渡：颜色矩阵（滤镜）乘上亮度（开头结尾 0.25 秒从黑渐亮、渐暗到黑）。 */
    private class Grade(filter: String, val fadeIn: Boolean, val fadeOut: Boolean, val lengthUs: Long) : RgbMatrix {
        private val m = colorMatrix(filter)
        private var base = Long.MIN_VALUE
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            if (base == Long.MIN_VALUE) base = presentationTimeUs
            val t = presentationTimeUs - base
            var k = 1f
            if (fadeIn) k = minOf(k, t.toFloat() / FADE_US)
            if (fadeOut) k = minOf(k, (lengthUs - t).toFloat() / FADE_US)
            return scaled(m, k.coerceIn(0f, 1f))
        }
    }

    /** 3×3 颜色矩阵（行优先：输出 r、g、b 各由输入 r、g、b 怎么加起来）。 */
    internal fun colorMatrix(filter: String): FloatArray {
        val lr = 0.299f; val lg = 0.587f; val lb = 0.114f
        fun sat(s: Float) = floatArrayOf(
            lr * (1 - s) + s, lg * (1 - s), lb * (1 - s),
            lr * (1 - s), lg * (1 - s) + s, lb * (1 - s),
            lr * (1 - s), lg * (1 - s), lb * (1 - s) + s,
        )
        fun times(a: FloatArray, r: Float, g: Float, b: Float) = FloatArray(9) { i -> a[i] * floatArrayOf(r, g, b)[i / 3] }
        return when (filter) {
            "warm" -> times(sat(1.05f), 1.08f, 1.0f, 0.88f)
            "cool" -> times(sat(1.0f), 0.92f, 1.0f, 1.08f)
            "bw" -> sat(0f)
            "vivid" -> sat(1.35f)
            "film" -> times(sat(0.8f), 1.05f, 0.98f, 0.9f)
            "fade" -> times(sat(0.7f), 0.94f, 0.94f, 0.94f)
            else -> sat(1f)
        }
    }

    /** 3×3 乘亮度 k，转成 Media3 要的 4×4 列优先矩阵。 */
    internal fun scaled(m: FloatArray, k: Float): FloatArray {
        val out = FloatArray(16)
        for (r in 0 until 3) for (c in 0 until 3) out[c * 4 + r] = m[r * 3 + c] * k
        out[15] = 1f
        return out
    }

    /** 一条花字：start 到 end 之间显示，刚出现的 0.15 秒从 85% 弹到原大。 */
    private class TimedText(private val bmp: Bitmap, private val startUs: Long, private val endUs: Long, private val y: Float) : BitmapOverlay() {
        override fun getBitmap(presentationTimeUs: Long) = bmp
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val on = presentationTimeUs in startUs until endUs
            val p = ((presentationTimeUs - startUs) / 150_000f).coerceIn(0f, 1f)
            val s = 0.85f + 0.15f * p
            return OverlaySettings.Builder().setAlphaScale(if (on) 1f else 0f).setScale(s, s).setBackgroundFrameAnchor(0f, y).build()
        }
    }

    /** 字幕：一个叠加层，按时间换成这一句的图（白字黑边，画面下方）；没字幕的时候是一个透明点。 */
    private class SubtitleTrack(private val cues: List<Subtitles.Cue>, private val frameW: Int) : BitmapOverlay() {
        private val starts = LongArray(cues.size) { cues[it].startMs }
        private val empty = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        private var shown = -1
        private var current: Bitmap = empty
        private fun at(ms: Long): Int {
            var i = java.util.Arrays.binarySearch(starts, ms)
            if (i < 0) i = -i - 2
            return if (i >= 0 && ms < cues[i].endMs) i else -1
        }
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val i = at(presentationTimeUs / 1000)
            if (i != shown) {
                shown = i
                current = if (i < 0) empty else TextArt.render(cues[i].text, "subtitle", frameW)
            }
            return current
        }
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.74f).build()
    }

    /** 背景音乐的音量：按成片时间（从开始一直累计，循环播放也接着算）乘上倍数。只处理 16 位 PCM，别的格式原样放过。 */
    private class Gain(private val gainAt: (Long) -> Float) : BaseAudioProcessor() {
        private var frames = 0L
        override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
            if (f.encoding == C.ENCODING_PCM_16BIT) f else AudioProcessor.AudioFormat.NOT_SET
        override fun queueInput(input: ByteBuffer) {
            val ch = inputAudioFormat.channelCount.coerceAtLeast(1)
            val rate = inputAudioFormat.sampleRate.coerceAtLeast(1)
            val out = replaceOutputBuffer(input.remaining())
            var g = gainAt(frames * 1000 / rate)
            while (input.remaining() >= 2 * ch) {
                if (frames % 441 == 0L) g = gainAt(frames * 1000 / rate)
                repeat(ch) { out.putShort((input.short * g).toInt().coerceIn(-32768, 32767).toShort()) }
                frames++
            }
            input.position(input.limit())
            out.flip()
        }
        override fun onReset() { frames = 0 }
    }
}
