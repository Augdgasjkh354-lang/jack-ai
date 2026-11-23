package com.jack.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.data.Attachments
import com.jack.ai.data.DraftCard
import com.jack.ai.tools.PLATFORMS
import com.jack.ai.tools.draftText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 各平台的 App 包名：「去发」时直接打开它的分享；没装就弹系统的分享面板。 */
private val PACKAGES = mapOf("xhs" to "com.xingin.xhs", "moments" to "com.tencent.mm", "wechat" to "com.tencent.mm",
    "weibo" to "com.sina.weibo", "douyin" to "com.ss.android.ugc.aweme")

private val BRAND = mapOf("xhs" to Color(0xFFFF2442), "moments" to Color(0xFF07C160), "wechat" to Color(0xFF07C160),
    "weibo" to Color(0xFFE6162D), "douyin" to Color(0xFF161823), "gzh" to Color(0xFF07C160))

/**
 * 发帖草稿卡片（show_draft）：按平台排成发出去的样子。复制、去发随时能点；「改一下」只有最后一条回答、没在写的时候能点。
 */
@Composable
fun DraftCards(drafts: List<DraftCard>, active: Boolean, onAnswer: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("draft-cards"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        drafts.forEach { DraftView(it, active, onAnswer) }
    }
}

@Composable
private fun DraftView(d: DraftCard, active: Boolean, onAnswer: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val brand = BRAND[d.platform] ?: colors.primary
    var editing by remember(d) { mutableStateOf(false) }
    var note by remember(d) { mutableStateOf("") }
    var viewing by remember(d) { mutableStateOf<String?>(null) }
    Surface(color = colors.surface, shape = Radii.m, tonalElevation = 1.dp, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(brand).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(PLATFORMS[d.platform] ?: "帖子", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text("草稿预览" + (if (d.to.isNotEmpty()) " · 发给 ${d.to}" else ""), style = MaterialTheme.typography.labelMedium, color = colors.outline, modifier = Modifier.weight(1f))
                Text("${d.body.count { !it.isWhitespace() }} 字" + if (d.images.isNotEmpty()) " · ${d.images.size} 图" else "",
                    style = MaterialTheme.typography.labelSmall, color = colors.outline)
            }
            Spacer(Modifier.height(10.dp))
            when (d.platform) {
                "moments" -> Moments(d) { viewing = it }
                "wechat" -> WeChat(d) { viewing = it }
                else -> Post(d, brand) { viewing = it }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { copy(ctx, draftText(d)) }) { Text("复制") }
                TextButton(onClick = { send(ctx, d) }) { Text("去发") }
                if (active) TextButton(onClick = { editing = !editing }) { Text("改一下") }
            }
            if (editing && active) {
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), placeholder = { Text("哪里要改？比如：开头再抓人一点") }, minLines = 1, maxLines = 4)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("短一点", "口语一点", "换个标题").forEach { q -> OutlinedButton(onClick = { onAnswer("这篇${PLATFORMS[d.platform]}草稿改一下：$q") }) { Text(q) } }
                }
                Spacer(Modifier.height(6.dp))
                Button(onClick = { if (note.isNotBlank()) onAnswer("这篇${PLATFORMS[d.platform]}草稿改一下：${note.trim()}") }, enabled = note.isNotBlank()) { Text("发给 Kavi") }
            }
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

/** 小红书、微博、抖音、公众号：图片横着排（小红书 3:4），标题、正文、话题。 */
@Composable
private fun Post(d: DraftCard, brand: Color, onImage: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (d.images.isNotEmpty()) {
        val ratio = if (d.platform == "xhs") 3f / 4f else if (d.platform == "gzh") 2.35f else 1f
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(d.images) { i, p ->
                Box {
                    Thumb(p, Modifier.width(if (ratio > 1.5f) 260.dp else 140.dp).aspectRatio(ratio).clip(RoundedCornerShape(8.dp)).clickable { onImage(p) })
                    if (i == 0 && d.images.size > 1) Text("封面", style = MaterialTheme.typography.labelSmall, color = Color.White,
                        modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    if (d.title.isNotEmpty()) {
        Text(d.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
        Spacer(Modifier.height(6.dp))
    }
    Body(d.body)
    if (d.tags.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(d.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.bodyMedium,
            color = if (d.platform == "xhs") Color(0xFF13386C).let { if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF8AB4F8) else it } else brand)
    }
}

/** 朋友圈：头像、名字、正文，图片九宫格。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Moments(d: DraftCard, onImage: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
            Text("我", color = colors.onPrimaryContainer)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("我", style = MaterialTheme.typography.titleSmall, color = Color(0xFF576B95))
            Spacer(Modifier.height(4.dp))
            Body(d.body)
            if (d.images.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val one = d.images.size == 1
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = if (d.images.size == 4) 2 else 3) {
                    d.images.take(9).forEach { p ->
                        Thumb(p, Modifier.size(if (one) 180.dp else 80.dp).clickable { onImage(p) })
                    }
                }
            }
            if (d.tags.isNotEmpty()) Text(d.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.bodySmall, color = Color(0xFF576B95), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 微信消息：右边的绿色气泡。 */
@Composable
private fun WeChat(d: DraftCard, onImage: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF95EC69)).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(draftText(d), style = MaterialTheme.typography.bodyMedium, color = Color(0xFF111111))
        }
        d.images.forEach { p ->
            Spacer(Modifier.height(6.dp))
            Thumb(p, Modifier.width(140.dp).clip(RoundedCornerShape(6.dp)).clickable { onImage(p) }, crop = false)
        }
    }
}

/** 正文：长的先收起，点「展开」看全部。 */
@Composable
private fun Body(text: String) {
    var open by remember(text) { mutableStateOf(false) }
    val long = text.lines().size > 10 || text.length > 400
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
        maxLines = if (long && !open) 8 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    if (long) Text(if (open) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp).clickable { open = !open })
}

@Composable
private fun Thumb(path: String, modifier: Modifier, crop: Boolean = true) {
    val bmp by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { Attachments.thumbnail(path, 600) } }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = if (crop) ContentScale.Crop else ContentScale.FillWidth)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).let { if (crop) it else it.height(100.dp) })
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("草稿", text))
    copiedToast(ctx)
}

/** 去发：文字先复制好（多数 App 分享进去不带文字），图片按顺序带过去；装了那个 App 就直接打开它，没装弹分享面板。 */
private fun send(ctx: Context, d: DraftCard) {
    copy(ctx, draftText(d))
    val uris = d.images.map { File(it) }.filter { it.exists() }.map { androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) }
    val intent = when {
        uris.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        uris.size == 1 -> Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uris[0])
        else -> Intent(Intent.ACTION_SEND).setType("text/plain")
    }.putExtra(Intent.EXTRA_TEXT, draftText(d)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val pkg = PACKAGES[d.platform]?.takeIf { p -> runCatching { ctx.packageManager.getPackageInfo(p, 0) }.isSuccess }
    Toast.makeText(ctx, "文字已复制，到了${PLATFORMS[d.platform]}里粘贴到正文", Toast.LENGTH_LONG).show()
    runCatching { ctx.startActivity(if (pkg != null) Intent(intent).setPackage(pkg) else Intent.createChooser(intent, "发到")) }
        .onFailure { runCatching { ctx.startActivity(Intent.createChooser(intent, "发到")) } }
}
