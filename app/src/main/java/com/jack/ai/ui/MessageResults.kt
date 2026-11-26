package com.jack.ai.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.data.GeneratedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 回答里做出来的东西按类型显示，并且接得上下一步：
 * 视频有封面、能播放、存相册；音频能直接听；最后一条回答的成果下面给几个「接着做」，点一下就把要求发给 Kavi
 * （加字幕、做封面、切短视频、做成小红书、总结、做 PPT……），不用自己想怎么说、也不用记文件名。
 */
object ResultKinds {
    private val VIDEO = setOf("mp4", "mov", "m4v", "webm", "mkv", "3gp")
    private val AUDIO = setOf("wav", "m4a", "mp3", "aac", "ogg", "flac", "opus")
    private val IMAGE = setOf("png", "jpg", "jpeg", "webp", "gif")
    private val DOC = setOf("pdf", "docx", "doc", "txt", "md")

    fun isVideo(name: String) = name.substringAfterLast('.').lowercase() in VIDEO
    fun isAudio(name: String) = name.substringAfterLast('.').lowercase() in AUDIO

    /** 「接着做」：按这条回答里做出来的东西给 2～4 个下一步，每个是（按钮上的字，发给 Kavi 的话）。预览图、中间文件不算。 */
    fun nextSteps(names: List<String>): List<Pair<String, String>> {
        val main = names.filterNot { it.endsWith("-预览.png") || it.endsWith(".srt") || it.endsWith(".html") || it.endsWith(".htm") }
        if (main.isEmpty()) return emptyList()
        fun ext(n: String) = n.substringAfterLast('.').lowercase()
        fun q(list: List<String>) = if (list.size == 1) "「${list[0]}」" else "这 ${list.size} 个文件（${list.take(3).joinToString("、") { "「$it」" }}${if (list.size > 3) "等" else ""}）"
        val videos = main.filter { ext(it) in VIDEO }
        val audios = main.filter { ext(it) in AUDIO }
        val images = main.filter { ext(it) in IMAGE }
        val docs = main.filter { ext(it) in DOC }
        val ppt = main.filter { ext(it) == "pptx" }
        val sheets = main.filter { ext(it) in setOf("xlsx", "csv") }
        return when {
            videos.isNotEmpty() -> {
                val v = q(videos.take(1))
                listOf("加字幕" to "给${v}加上字幕", "配乐" to "给${v}配个合适的背景音乐", "做封面" to "给${v}做个封面",
                    "写发布文案" to "给${v}写抖音的标题、简介和话题")
            }
            audios.isNotEmpty() -> {
                val a = q(audios.take(1))
                listOf("转文字" to "把${a}转成文字，再总结要点", "做成视频" to "用${a}做一条竖屏视频（配画面和字幕）")
            }
            images.size > 1 -> listOf("做成小红书" to "把${q(images)}做成一篇小红书：写标题、正文和话题，按顺序配图", "存相册" to "把${q(images)}存进相册",
                "改成方图" to "把${q(images)}都改成方图（1:1）再出一份")
            images.isNotEmpty() -> {
                val i = q(images)
                listOf("写配文" to "给${i}写一段发朋友圈的配文", "做成小红书" to "用${i}做一篇小红书", "改尺寸" to "把${i}再出一个方图版和一个公众号封面版", "抠图" to "把${i}抠图，去掉背景")
            }
            ppt.isNotEmpty() -> listOf("写演讲稿" to "给${q(ppt)}每一页写演讲稿", "换个配色" to "把${q(ppt)}换一套更好看的配色")
            sheets.isNotEmpty() -> listOf("做图表" to "给${q(sheets)}做几张图表，看出趋势", "找异常" to "看看${q(sheets)}里有没有异常或不对劲的数")
            docs.isNotEmpty() -> listOf("总结要点" to "总结${q(docs)}的要点", "做成 PPT" to "把${q(docs)}做成一份 PPT", "做成小红书" to "把${q(docs)}做成一组小红书卡片")
            else -> emptyList()
        }
    }
}

/**
 * 只有文字的回答也猜下一步：看回答里有什么（表格、代码、一串步骤、很长），给最多 3 个顺手的按钮。
 * 都不像就不给，不硬凑。
 */
object TextSteps {
    private val TABLE_RULE = Regex("""(?m)^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$""")
    private val FENCE = Regex("(?m)^\\s*```")
    private val NUMBERED = Regex("(?m)^\\s*(\\d{1,2})[.、)）]\\s+\\S")
    private val TIME_WORDS = Regex("(每天|每周|明天|早上|晚上|点前|截止|提醒|到期|\\d{1,2}[:：]\\d{2})")

    fun of(text: String): List<Pair<String, String>> {
        val t = text.trim()
        if (t.length < 40) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        if (TABLE_RULE.containsMatchIn(t)) {
            out += "导出 Excel" to "把上面的表格做成 Excel 文件给我"
            out += "做成图表" to "把上面表格里的数据画成图表"
        }
        if (FENCE.findAll(t).count() >= 2) {
            out += "存成文件" to "把上面的代码存成文件给我"
            out += "逐行解释" to "把上面的代码逐行解释一下，说说每部分在做什么"
        }
        val steps = NUMBERED.findAll(t).count()
        if (steps >= 3) {
            if (TIME_WORDS.containsMatchIn(t)) out += "设成提醒" to "把上面这些按时间设成提醒"
            else out += "做成清单" to "把上面这几条做成一张能打勾的待办清单"
        }
        if (t.length > 1500) out += "总结成三句" to "把上面的回答压缩成三句话"
        return out.distinctBy { it.first }.take(3)
    }
}

/** 只有文字的回答下面的「下一步可以」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextNextSteps(text: String, onAsk: (String) -> Unit) {
    val steps = remember(text) { TextSteps.of(text) }
    StepChips(steps, onAsk)
}

/** 「下一步可以」一排按钮（和回答下面那个「接着做」按钮分开叫）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NextSteps(names: List<String>, onAsk: (String) -> Unit) {
    val steps = remember(names) { ResultKinds.nextSteps(names) }
    StepChips(steps, onAsk)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepChips(steps: List<Pair<String, String>>, onAsk: (String) -> Unit) {
    if (steps.isEmpty()) return
    Column(Modifier.padding(top = 6.dp).testTag("next-steps")) {
        Text("下一步可以", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEach { (label, ask) -> AssistChip(onClick = { onAsk(ask) }, label = { Text(label) }) }
        }
    }
}

/** 做出来的视频：封面（点了放）、时长，下面播放、存相册、分享。 */
@Composable
internal fun VideoResult(f: GeneratedFile) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = File(f.path)
    if (!file.exists()) { GeneratedFileCard(f); return }
    Column(Modifier.widthIn(max = 380.dp)) {
        VideoThumb(f.path, 300.dp, 300.dp) { playVideo(ctx, file) }
        Text(f.name + " · " + formatSize(f.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Row {
            TextButton(onClick = { playVideo(ctx, file) }) { Text("播放") }
            TextButton(onClick = {
                scope.launch {
                    val name = withContext(Dispatchers.IO) { runCatching { com.jack.ai.data.Gallery.save(ctx, file) }.getOrNull() }
                    Toast.makeText(ctx, if (name != null) "存进相册了（Movies/Kavi）" else "没存进去", Toast.LENGTH_SHORT).show()
                }
            }) { Text("存相册") }
            TextButton(onClick = { shareFile(ctx, f) }) { Text("分享") }
        }
    }
}

/** 做出来的声音（配音、录音）：直接在聊天里听，有进度条。 */
@Composable
internal fun AudioResult(f: GeneratedFile) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val file = File(f.path)
    if (!file.exists()) { GeneratedFileCard(f); return }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var length by remember { mutableStateOf(0) }
    DisposableEffect(f.path) { onDispose { player?.release(); player = null } }
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { p -> if (p.duration > 0) progress = p.currentPosition.toFloat() / p.duration }
            delay(200)
        }
    }
    Surface(shape = Radii.l, color = colors.surfaceContainerLowest, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                val p = player ?: runCatching {
                    MediaPlayer().apply {
                        setDataSource(file.absolutePath); prepare()
                        setOnCompletionListener { playing = false; progress = 0f }
                    }
                }.getOrNull().also { player = it; length = it?.duration ?: 0 }
                if (p == null) { Toast.makeText(ctx, "放不了这个文件", Toast.LENGTH_SHORT).show(); return@IconButton }
                if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
            }) {
                if (playing) Text("❚❚", fontWeight = FontWeight.Bold, color = colors.primary)
                else Icon(Icons.Default.PlayArrow, "播放", tint = colors.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Text((if (length > 0) com.jack.ai.data.media.VideoFrames.stamp(length.toLong()) + " · " else "") + formatSize(f.size),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.size(4.dp))
            IconButton(onClick = { shareFile(ctx, f) }) { Icon(AppIcons.Share, "分享", Modifier.size(18.dp), tint = colors.onSurfaceVariant) }
        }
    }
}
