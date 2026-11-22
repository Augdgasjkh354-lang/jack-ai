package com.jack.ai.data.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * 出片后的质检（思路来自 KyaniteLabs/kinocut 的出片关卡、zhouxiaoka/autoclip 的「技术底线」）：
 * 量响度，差得多就把整条声音调到 -14 LUFS（只重做声音，画面原样拷过去）；找黑屏、一大段没声音。结果写成几句给 Kavi 看。
 */
object VideoQc {
    class Report(val lufs: Double?, val gainDb: Double, val peakLimited: Boolean, val silences: List<Pair<Long, Long>>, val black: List<Pair<Long, Long>>, val firstBlack: Boolean, val hasAudio: Boolean) {
        fun text(): String = buildString {
            when {
                !hasAudio -> append("质检：成片没有声音。")
                lufs == null -> append("质检：成片几乎没有声音。")
                gainDb != 0.0 -> append("质检：响度原来 ${"%.1f".format(lufs)} LUFS，" +
                    (if (gainDb > 0) "调大了 ${"%.1f".format(gainDb)} dB" else "调小了 ${"%.1f".format(-gainDb)} dB") +
                    (if (peakLimited) "（再大就要爆音，没调到 -14，配音或原声本身太小，可以把 voiceover_volume、clips 的 volume 调高再出）" else "，到了平台标准的 -14 LUFS 左右") + "。")
                else -> append("质检：响度 ${"%.1f".format(lufs)} LUFS，正好。")
            }
            if (firstBlack) append("开头第一帧是黑的（平台常拿第一帧当默认封面，最好加 cover 或换个开头）。")
            if (black.isNotEmpty()) append("黑屏：" + black.joinToString("、") { "${Subtitles.stamp(it.first)}-${Subtitles.stamp(it.second)}" } + "（素材本身黑的、或片段时间超出了素材，检查一下）。")
            if (silences.isNotEmpty()) append("这几段超过 2 秒没声音：" + silences.take(5).joinToString("、") { "${Subtitles.stamp(it.first)}-${Subtitles.stamp(it.second)}" } + "（故意留白就没事；不是的话配点音乐或剪掉）。")
        }
    }

    suspend fun check(ctx: Context, f: File, work: File, progress: (String) -> Unit): Report {
        progress("质检：量响度、找黑屏")
        val channels = withContext(Dispatchers.IO) { audioChannels(f) }
        val meter = Loudness(AudioPcm.RATE)
        val decoded = channels > 0 && withContext(Dispatchers.IO) { runCatching { MediaMux.decode16k(f, 3 * 3600L) { meter.push(it) } }.isSuccess }
        val lufs = if (decoded) meter.integrated(channels) else null
        val (gain, limited) = lufs?.let { Loudness.gainDb(it, meter.peak) } ?: (0.0 to false)
        var applied = 0.0
        if (gain != 0.0) {
            progress("质检：把响度调到 -14 LUFS")
            val tmp = File(work, "loud-${System.currentTimeMillis()}.mp4")
            val k = Math.pow(10.0, gain / 20).toFloat()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f))).setEffects(Effects(listOf(ConstGain(k)), emptyList())).build()
            val ok = runCatching { VideoEdit.export(ctx, Composition.Builder(EditedMediaItemSequence(item)).build(), tmp, null) {} }.isSuccess
            if (ok && tmp.length() > 0) { withContext(Dispatchers.IO) { tmp.copyTo(f, overwrite = true) }; applied = gain }
            tmp.delete()
        }
        val (black, first) = withContext(Dispatchers.IO) { blackFrames(f) }
        return Report(lufs, applied, limited, if (decoded) meter.silences() else emptyList(), black, first, channels > 0)
    }

    private fun audioChannels(f: File): Int = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
        } finally { ex.release() }
    }.getOrDefault(0)

    /** 每 0.5 秒（最多 80 处）看一眼画面，连着两处以上全黑的算黑屏；第一帧单独看。 */
    private fun blackFrames(f: File): Pair<List<Pair<Long, Long>>, Boolean> = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@runCatching emptyList<Pair<Long, Long>>() to false
            val step = maxOf(500L, dur / 80)
            fun dark(ms: Long): Boolean {
                val b = (if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 64, 64)
                    else r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)) ?: return false
                val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); b.recycle()
                return isBlack(px)
            }
            val first = dark(0)
            val marks = (0 until (dur / step).toInt()).map { i -> (i * step + step / 2) to dark(i * step + step / 2) }
            runs(marks, step) to first
        } finally { r.release() }
    }.getOrDefault(emptyList<Pair<Long, Long>>() to false)

    /** 画面全黑：平均亮度很低，最亮的地方也不亮。 */
    fun isBlack(px: IntArray): Boolean {
        if (px.isEmpty()) return false
        var sum = 0L; var max = 0
        for (c in px) { val y = (((c shr 16) and 0xff) * 3 + ((c shr 8) and 0xff) * 6 + (c and 0xff)) / 10; sum += y; if (y > max) max = y }
        return sum / px.size < 16 && max < 48
    }

    /** 连着两个以上的黑点合成一段。 */
    fun runs(marks: List<Pair<Long, Boolean>>, step: Long): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var start = -1
        for (i in 0..marks.size) {
            val d = i < marks.size && marks[i].second
            if (d && start < 0) start = i
            if (!d && start >= 0) { if (i - start >= 2) out += (marks[start].first - step / 2) to (marks[i - 1].first + step / 2); start = -1 }
        }
        return out
    }

    /** 整条声音乘一个倍数（16 位 PCM）。 */
    private class ConstGain(private val k: Float) : BaseAudioProcessor() {
        override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
            if (f.encoding == C.ENCODING_PCM_16BIT) f else AudioProcessor.AudioFormat.NOT_SET
        override fun queueInput(input: ByteBuffer) {
            val out = replaceOutputBuffer(input.remaining())
            while (input.remaining() >= 2) out.putShort((input.short * k).toInt().coerceIn(-32768, 32767).toShort())
            input.position(input.limit())
            out.flip()
        }
    }
}
