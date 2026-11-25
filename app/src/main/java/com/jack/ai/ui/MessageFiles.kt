package com.jack.ai.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jack.ai.data.Attachments
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Delivered
import com.jack.ai.data.Deliverables
import com.jack.ai.data.GeneratedFile
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.runtime.key
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.modelLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 消息里的图片、视频、生成的文件：缩略图、大图查看、文件卡片、打开和分享（从 Messages.kt 拆出来）。

@Composable
fun Thumbnail(path: String, size: Dp, onClick: (() -> Unit)? = null) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { Attachments.thumbnail(path, px * 2) }
    }
    val shape = Radii.s
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        // 读出来以后淡入，不是从灰块一下跳成图
        androidx.compose.animation.AnimatedVisibility(bmp != null, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.NORMAL))) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }
}

/**
 * 视频封面：第一秒的画面按视频比例缩进 maxW×maxH 里，中间一个播放键，右下角是时长。点一下用手机的播放器放。
 */
@Composable
internal fun VideoThumb(path: String, maxW: Dp, maxH: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { maxOf(maxW, maxH).roundToPx() }
    val loaded by produceState<Pair<Bitmap?, Long>?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val f = File(path)
            val info = com.jack.ai.data.media.VideoFrames.info(f)
            val b = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(path)
                    val at = minOf(1_000_000L, (info?.durationMs ?: 0) * 500)
                    if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(at, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px, px)
                    else r.getFrameAtTime(at)
                } finally { r.release() }
            }.getOrNull()
            b to (info?.durationMs ?: -1L)
        }
    }
    val bmp = loaded?.first
    val ratio = bmp?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (maxW / maxH)
    val (w, h) = if (ratio >= maxW / maxH) maxW to (maxW / ratio) else (maxH * ratio) to maxH
    Box(
        modifier.size(w, h).clip(Radii.s).background(Color.Black)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedVisibility(bmp != null, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.NORMAL))) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Box(Modifier.size(if (maxW < 80.dp) 22.dp else 44.dp).background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, "播放", tint = Color.White, modifier = Modifier.size(if (maxW < 80.dp) 16.dp else 30.dp))
        }
        val dur = loaded?.second ?: -1L
        if (dur > 0 && maxW >= 80.dp) Text(com.jack.ai.data.media.VideoFrames.stamp(dur), color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.5f), Radii.s).padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/** 用手机的播放器放对话文件夹里的视频。 */
internal fun playVideo(ctx: android.content.Context, f: File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { android.widget.Toast.makeText(ctx, "手机上没有能放视频的 App", android.widget.Toast.LENGTH_SHORT).show() }
}

@Composable
internal fun ImageViewer(path: String, onDismiss: () -> Unit) = ImageViewer(listOf(path), 0, onDismiss)

/**
 * 看大图。[paths] 有几张时左右滑着翻（一条消息里发了好几张图，不用关了再点下一张），上面标「2 / 5」；
 * 放大了的那张拖着看细节，还原了才能翻页。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ImageViewer(paths: List<String>, start: Int, onDismiss: () -> Unit) {
    if (paths.isEmpty()) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start.coerceIn(0, paths.lastIndex)) { paths.size }
    val path = paths[pager.currentPage.coerceIn(0, paths.lastIndex)]
    // 成组的设计图（名字-01、-02……）可以整组存
    val group by produceState(emptyList<File>(), path) {
        value = withContext(Dispatchers.IO) { groupOf(File(path)) }
    }
    var menu by remember { mutableStateOf(false) }
    // 当前这张放大着：不翻页，手指拖的是图
    var zoomed by remember { mutableStateOf(false) }
    fun save(files: List<File>) {
        menu = false
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                com.jack.ai.data.design.DesignSeries.exportToGallery(ctx, files, com.jack.ai.data.design.DesignSeries.baseOf(files.first()) ?: files.first().nameWithoutExtension)
            }
            Toast.makeText(ctx, if (n > 0) "已存 $n 张到相册（Pictures/Kavi）" else "没存成，看看是不是没给存储权限", Toast.LENGTH_LONG).show()
        }
    }
    // 打开时黑底淡入、图从 92% 放大到满屏，像从缩略图「展开」出来
    val opening = remember { androidx.compose.animation.core.Animatable(if (animationsOff(ctx)) 1f else 0f) }
    LaunchedEffect(Unit) { opening.animateTo(1f, Springs.defaultSpatial()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = opening.value.coerceIn(0f, 1f) }.background(Color.Black)) {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed && paths.size > 1,
                pageSpacing = 16.dp,
                key = { paths[it] + "#" + it },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ZoomableImage(paths[page], opening.value, onDismiss) { z -> if (page == pager.currentPage) zoomed = z }
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ViewerButton({ onDismiss() }) { Icon(Icons.Default.Close, "关闭", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                if (paths.size > 1) Text("${pager.currentPage + 1} / ${paths.size}", color = Color.White,
                    style = MaterialTheme.typography.labelLarge.tabular)
                Spacer(Modifier.weight(1f))
                // 直接发给别人，不用先存相册再去找
                ViewerButton({
                    val f = File(path)
                    shareFile(ctx, GeneratedFile(f.name, f.absolutePath, com.jack.ai.tools.ToolSession.mimeOf(f.name).takeIf { m -> m.startsWith("image/") } ?: "image/*"))
                }) { Icon(AppIcons.Share, "分享", tint = Color.White) }
                Spacer(Modifier.width(12.dp))
                ViewerButton({ com.jack.ai.ui.photo.PhotoEditorActivity.open(ctx, File(path)) }) { Icon(Icons.Default.Edit, "修图", tint = Color.White) }
                Spacer(Modifier.width(12.dp))
                Box {
                    ViewerButton({ if (group.size > 1) menu = true else save(listOf(File(path))) }) { Icon(AppIcons.Download, "存到相册", tint = Color.White) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("存这一张") }, onClick = { save(listOf(File(path))) })
                        DropdownMenuItem(text = { Text("整组存（${group.size} 张）") }, onClick = { save(group) })
                    }
                }
            }
        }
    }
}

/**
 * 大图里的一张：双指缩放、放大后拖着看细节；双击放大 / 还原；没放大时点一下关掉。
 * 没放大时单指拖动不吃掉，留给外面翻页。
 */
@Composable
private fun ZoomableImage(path: String, opening: Float, onDismiss: () -> Unit, onZoomed: (Boolean) -> Unit) {
    val bmp by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { Attachments.thumbnail(path, 2000) }
    }
    val scope = rememberCoroutineScope()
    var scale by remember(path) { mutableStateOf(1f) }
    var offset by remember(path) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var zoomJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val isZoomed = scale > 1.01f
    LaunchedEffect(isZoomed) { onZoomed(isZoomed) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(path) {
                detectTapGestures(
                    onTap = { if (scale <= 1.01f) onDismiss() },
                    onDoubleTap = { at ->
                        // 放大、还原都是弹簧过渡，看得出是从哪里放大的
                        val (toScale, toOffset) = if (scale > 1.01f) 1f to androidx.compose.ui.geometry.Offset.Zero
                            else 2.5f to (androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f) - at) * 1.5f
                        val fromScale = scale
                        val fromOffset = offset
                        zoomJob?.cancel()
                        zoomJob = scope.launch {
                            androidx.compose.animation.core.animate(0f, 1f, animationSpec = Springs.defaultSpatial()) { t, _ ->
                                scale = fromScale + (toScale - fromScale) * t
                                offset = fromOffset + (toOffset - fromOffset) * t
                            }
                        }
                    },
                )
            }
            .pointerInput(path) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // 两根手指在缩放，或者已经放大了在拖：自己处理；一根手指、没放大：不管，外面翻页
                        if (fingers >= 2 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale <= 1.01f) androidx.compose.ui.geometry.Offset.Zero else {
                                val o = offset + pan
                                val mx = size.width * (scale - 1) / 2f
                                val my = size.height * (scale - 1) / 2f
                                androidx.compose.ui.geometry.Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().graphicsLayer {
                val open = 0.92f + 0.08f * opening
                scaleX = scale * open; scaleY = scale * open; translationX = offset.x; translationY = offset.y
            }, contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun ViewerButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(onClick = onClick, shape = androidx.compose.foundation.shape.CircleShape, color = Color.White.copy(alpha = 0.14f), modifier = Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** 这张图所在的一组设计图（按页码顺序的 PNG）；不是成组的设计图就只有它自己。 */
internal fun groupOf(png: File): List<File> {
    val html = File(png.parentFile, png.nameWithoutExtension + ".html")
    if (!html.isFile) return listOf(png)
    return com.jack.ai.data.design.DesignSeries.pagesOf(html).map { File(it.parentFile, it.nameWithoutExtension + ".png") }.filter { it.isFile }.ifEmpty { listOf(png) }
}

@Composable
internal fun ChartImage(f: GeneratedFile) {
    val ctx = LocalContext.current
    var viewing by remember { mutableStateOf(false) }
    // 在编辑器里保存过就重新读（同名覆盖，路径不变）
    val version = DesignEditorActivity.version(f.path)
    val bmp by produceState<Bitmap?>(null, f.path, version) {
        value = withContext(Dispatchers.IO) { Attachments.thumbnail(f.path, 1400) }
    }
    // 设计图（旁边有底稿）可以点「编辑」自己动手改
    val editable by produceState(false, f.path) { value = withContext(Dispatchers.IO) { DesignEditorActivity.sourceOf(ctx, f.path) != null } }
    Column(Modifier.widthIn(max = 420.dp)) {
        bmp?.let {
            Image(
                it.asImageBitmap(), f.name,
                Modifier.fillMaxWidth()
                    .clip(Radii.m)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radii.m)
                    .clickable { viewing = true },
                contentScale = ContentScale.FillWidth,
            )
        } ?: Text("图表文件已被删除", color = MaterialTheme.colorScheme.outline)
        Row {
            TextButton(onClick = { viewing = true }) { Text("查看大图") }
            if (editable) TextButton(onClick = { DesignEditorActivity.open(ctx, File(f.path)) }) { Text("编辑") }
            TextButton(onClick = { shareFile(ctx, f) }) { Text("分享") }
        }
    }
    if (viewing) key(version) { ImageViewer(f.path) { viewing = false } }
}

@Composable
internal fun GeneratedFileCard(f: GeneratedFile) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // 网页（小工具）、文字、PDF、Office 都在 App 里看（手机上多半没有能打开它们的 App）
    val opener = rememberFileOpener()
    val open = { opener(f) }
    val (tag, tagColor) = when (f.name.substringAfterLast('.').lowercase()) {
        "docx" -> "W" to FileColors.word
        "pptx" -> "P" to FileColors.slides
        "xlsx" -> "X" to FileColors.sheet
        "pdf" -> "PDF" to FileColors.pdf
        "png", "jpg", "jpeg", "webp", "gif" -> "图" to FileColors.image
        "txt", "md", "csv", "json" -> "TXT" to FileColors.text
        "html", "htm", "js", "py", "kt", "java", "css", "ts" -> "</>" to FileColors.code
        // 认不出的类型用中性的灰，不用强调色（黑白主题深色模式下强调色是白的，白字压上去看不见）
        else -> "F" to FileColors.text
    }
    val path = File(f.path)
    Surface(
        shape = Radii.l,
        color = colors.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth()
            .clip(Radii.l)
            .clickable { open() },
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(Radii.s).background(tagColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(tag, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (tag.length > 1) 12.sp else 19.sp)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    if (path.exists()) formatSize(f.size) + " · " + fileTime(path.lastModified()) else "文件已被删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Surface(onClick = { open() }, shape = Radii.s, color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
                Text("打开", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
            IconButton(onClick = { shareFile(ctx, f) }, modifier = Modifier.size(40.dp)) {
                Icon(AppIcons.Share, "分享", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** 文件卡上的时间：今天的只写几点，别的写日期。 */
private fun fileTime(ms: Long): String {
    val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
    val today = day.format(java.util.Date()) == day.format(java.util.Date(ms))
    return java.text.SimpleDateFormat(if (today) "今天 HH:mm" else "M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun fileUri(ctx: Context, f: GeneratedFile) =
    FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(f.path))

/**
 * 点开一个文件，和聊天里的文件卡片一样：网页（小工具）、txt、md、json、代码、PDF、Word、Excel、PPT 在 App 里看，别的交给手机上的 App。
 * 手机上多半没有能打开 md 的 App，直接丢出去会一闪就退回来。查看器由它自己画，返回打开用的函数。
 */
@Composable
internal fun rememberFileOpener(): (GeneratedFile) -> Unit {
    val ctx = LocalContext.current
    var app by remember { mutableStateOf<GeneratedFile?>(null) }
    var text by remember { mutableStateOf<GeneratedFile?>(null) }
    var doc by remember { mutableStateOf<GeneratedFile?>(null) }
    app?.let { f -> AppViewer(File(f.path)) { app = null } }
    doc?.let { f -> DocViewer(File(f.path), onDismiss = { doc = null }, onShare = { shareFile(ctx, f) }, onOpenWith = { openFile(ctx, f) }) }
    text?.let { f ->
        TextViewer(f.name, load = { com.jack.ai.data.TextPreview.read(File(f.path)) }, onDismiss = { text = null },
            onShare = { shareFile(ctx, f) }, onOpenWith = { openFile(ctx, f) },
            // App 自己文件夹里的（Kavi 做出来的、对话文件夹里的）才能改
            editFile = File(f.path).takeIf { it.canonicalPath.startsWith(ctx.filesDir.canonicalPath) })
    }
    return { f ->
        when {
            !File(f.path).exists() -> openFile(ctx, f)
            f.name.endsWith(".html", true) || f.name.endsWith(".htm", true) -> app = f
            com.jack.ai.data.TextPreview.isText(f.name) -> text = f
            isViewableDoc(f.name) -> doc = f
            else -> openFile(ctx, f)
        }
    }
}

internal fun openFile(ctx: Context, f: GeneratedFile) {
    if (!File(f.path).exists()) {
        Toast.makeText(ctx, "文件已被删除", Toast.LENGTH_SHORT).show()
        return
    }
    // 文件不在能分享出去的文件夹里时 getUriForFile 会抛错，不能让它把 App 带崩
    val uri = runCatching { fileUri(ctx, f) }.getOrElse {
        Toast.makeText(ctx, "这个文件打不开：${f.name}", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, f.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // 不再每次都弹「用什么打开」：交给系统，选过「始终」的直接打开，没选过的系统自己会问
    try {
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "手机上没有能打开这种文件的应用，可以装个 WPS 或微软 Office", Toast.LENGTH_LONG).show()
    }
}

internal fun shareFile(ctx: Context, f: GeneratedFile) {
    if (!File(f.path).exists()) {
        Toast.makeText(ctx, "文件已被删除", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = runCatching { fileUri(ctx, f) }.getOrElse {
        Toast.makeText(ctx, "这个文件分享不了：${f.name}", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND)
        .setType(f.mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, "分享到"))
}

/** 复制后提示一下；安卓 13 起系统自己会在底下弹复制的提示，就不再重复。 */
internal fun copiedToast(ctx: Context) {
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
}
