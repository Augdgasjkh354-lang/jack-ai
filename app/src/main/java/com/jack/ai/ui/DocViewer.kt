package com.jack.ai.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jack.ai.data.OfficeHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/** 能在 App 里看的文档：PDF、Word、Excel、PPT。 */
fun isViewableDoc(name: String) = name.endsWith(".pdf", true) || OfficeHtml.supports(name)

/**
 * 在 App 里看 PDF、Word、Excel、PPT：PDF 一页页画出来（两指缩放，双击复原）；Word、PPT 按原样排版（分页、页眉页脚、形状和位置），
 * 画不出来时和 Excel 一样转成简单的网页显示。
 * 看不清的还能从菜单里用其他应用打开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocViewer(file: File, onDismiss: () -> Unit, onShare: (() -> Unit)? = null, onOpenWith: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            info?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") } },
                    actions = {
                        if (onShare != null || onOpenWith != null) Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                onShare?.let { DropdownMenuItem(text = { Text("分享") }, onClick = { menu = false; it() }) }
                                onOpenWith?.let { DropdownMenuItem(text = { Text("用其他应用打开") }, onClick = { menu = false; it() }) }
                            }
                        }
                    },
                )
            },
        ) { padding ->
            val m = Modifier.fillMaxSize().padding(padding)
            if (file.name.endsWith(".pdf", true)) PdfPages(file, m) { info = it }
            else {
                // Word、PPT 先按原样排版画；画不出来（库没打包进来、文件太怪）就换成简单版，Excel 一直用简单版
                var simple by remember(file.path) { mutableStateOf(file.extension.lowercase() !in FAITHFUL) }
                if (simple) OfficePage(file, m) else FaithfulPage(file, m) { simple = true }
            }
        }
    }
}

// ---------- PDF ----------

/** PdfRenderer 一次只能开一页，几页同时要画时排队。 */
private class PdfDoc(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    val count = renderer.pageCount
    /** 每页的宽高比（高/宽），开头量一遍，占位不跳。 */
    val ratios: List<Float> = (0 until count).map { i -> renderer.openPage(i).use { it.height.toFloat() / it.width.coerceAtLeast(1) } }

    suspend fun render(i: Int, width: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(i).use { p ->
                var w = width
                var h = (w * p.height.toFloat() / p.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                // 超长页（小票、长图）按总像素封顶：等比缩小，免得一张位图吃掉几百 MB 内存
                val maxPx = 16_000_000L
                if (w.toLong() * h > maxPx) {
                    val s = kotlin.math.sqrt(maxPx.toDouble() / (w.toLong() * h))
                    w = (w * s).toInt().coerceAtLeast(1)
                    h = (h * s).toInt().coerceAtLeast(1)
                }
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                    b.eraseColor(android.graphics.Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    fun close() { runCatching { renderer.close() }; runCatching { fd.close() } }
}

@Composable
private fun PdfPages(file: File, modifier: Modifier, onInfo: (String) -> Unit) {
    val doc by produceState<Result<PdfDoc>?>(null, file.path) {
        value = withContext(Dispatchers.IO) { runCatching { PdfDoc(file) } }
    }
    DisposableEffect(doc) { onDispose { doc?.getOrNull()?.close() } }
    val d = doc
    when {
        d == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        d.isFailure -> Text("打不开这个 PDF：${d.exceptionOrNull()?.message ?: "可能加了密码或已损坏"}", modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error)
        else -> {
            val pdf = d.getOrThrow()
            val list = rememberLazyListState()
            val page by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
            androidx.compose.runtime.LaunchedEffect(page, pdf.count) { onInfo("第 $page / ${pdf.count} 页") }
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            var widthPx by remember { mutableStateOf(0) }
            Box(
                modifier.background(Color(0xFF6B6B6B)).onSizeChanged { widthPx = it.width }
                    // 两指缩放、拖动；一指照常翻页
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                    val max = widthPx * (scale - 1) / 2
                                    offset = if (scale == 1f) Offset.Zero else Offset((offset.x + event.calculatePan().x).coerceIn(-max, max), 0f)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) },
            ) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x },
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pdf.count) { i -> PdfPage(pdf, i, widthPx) }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(pdf: PdfDoc, i: Int, widthPx: Int) {
    // 画得比屏幕宽一倍半，放大了也还清楚；太宽的屏幕封顶，免得内存吃紧
    val target = (widthPx * 1.5f).toInt().coerceIn(600, 2400)
    val bmp by produceState<Bitmap?>(null, i, target) { value = runCatching { pdf.render(i, target) }.getOrNull() }
    val ratio = pdf.ratios.getOrElse(i) { 1.414f }.coerceIn(0.2f, 5f)
    Box(Modifier.fillMaxWidth().aspectRatio(1f / ratio).background(Color.White), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b == null) CircularProgressIndicator(Modifier.padding(24.dp))
        else Image(b.asImageBitmap(), "第 ${i + 1} 页", Modifier.fillMaxSize())
    }
}

// ---------- Word / PPT 按原样排版 ----------

/** 这两种用前端库按原样画（库在打包时下载，见 app/build.gradle.kts）。 */
private val FAITHFUL = setOf("docx", "pptx")
internal const val DOC_HOST = "doc.jack.local"
/** 这么久还没画完就当画不出来。 */
private const val FAITHFUL_TIMEOUT_MS = 25_000L

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FaithfulPage(file: File, modifier: Modifier, onFail: () -> Unit) {
    var done by remember(file.path) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(file.path) {
        kotlinx.coroutines.delay(FAITHFUL_TIMEOUT_MS)
        if (!done) onFail()
    }
    Box(modifier.background(Color(0xFF6B6B6B))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    // 脚本要开（库是 JS 写的），但只能拿到查看器自己的几个文件和这一个文档，别处一律不通
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                            val text = m.message()
                            if (text.startsWith("DOCVIEW_OK")) done = true
                            else if (text.startsWith("DOCVIEW_FAIL") && !done) { done = true; onFail() }
                            return true
                        }
                    }
                    webViewClient = docViewClient(file)
                    loadUrl("https://$DOC_HOST/viewer.html?kind=" + file.extension.lowercase())
                }
            },
        )
        if (!done) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

/**
 * 查看器网页的请求只放行三样：viewer.html 和几个库（App 自带的 assets/docview）、这一个文档（/file），别的一律拦下。
 * 点链接交给系统浏览器。截图（[PageShots]）也用它。
 */
internal fun docViewClient(file: File) = object : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.scheme == "data" || url.scheme == "blob") return null
        val name = url.path.orEmpty().removePrefix("/")
        if (url.scheme == "https" && url.host == DOC_HOST) {
            if (name == "file") return runCatching { WebResourceResponse("application/octet-stream", null, file.inputStream()) }.getOrNull()
            if (name.matches(Regex("[a-z.-]+\\.(html|js)"))) return runCatching {
                WebResourceResponse(if (name.endsWith(".html")) "text/html" else "application/javascript", "utf-8", view.context.assets.open("docview/$name"))
            }.getOrNull() ?: blocked()
        }
        return blocked()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (request.url.host != DOC_HOST) runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
        return true
    }
}

private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

// ---------- Word / Excel / PPT 简单版 ----------

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfficePage(file: File, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)
    val fg = hex(colors.onSurface); val bg = hex(colors.surface); val line = hex(colors.outlineVariant); val accent = hex(colors.primary)
    val html by produceState<Result<String>?>(null, file.path, fg, bg) {
        value = withContext(Dispatchers.IO) { runCatching { OfficeHtml.page(OfficeHtml.body(file), fg, bg, line, accent) } }
    }
    val h = html
    when {
        h == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        h.isFailure -> Text("打不开这个文件：${h.exceptionOrNull()?.message ?: "格式可能不对"}", modifier.padding(16.dp), color = colors.error)
        else -> AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    // 只是看：不跑脚本、不读文件、不联网；点链接交给浏览器
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            if (request.url.scheme == "data") null
                            else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                            return true
                        }
                    }
                    loadDataWithBaseURL(null, h.getOrThrow(), "text/html", "utf-8", null)
                }
            },
        )
    }
}
