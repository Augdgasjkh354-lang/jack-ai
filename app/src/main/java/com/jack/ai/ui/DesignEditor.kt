package com.jack.ai.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import com.jack.ai.data.Attachments
import com.jack.ai.data.DesignFonts
import com.jack.ai.data.DesignStyles
import com.jack.ai.data.DesignTemplates
import com.jack.ai.data.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.coroutines.resume

/**
 * 设计图编辑器（初版框架，长远目标见 docs/设计编辑器_目标.md）：在聊天里点设计图下面的「编辑」打开。
 * 打开的是图旁边的底稿 .html，用和出图同一套页面（DesignRender.serve：字体、照片），加上 editor.js 处理手势：
 * 点一下选中，按住拖动，两指缩放旋转，拖到中线会吸住；下面的工具栏改字、字号、颜色、字体、粗细、对齐、层级、换图、隐藏，整张换配色。
 * 保存时把改好的页面写回底稿、重新出图（同名覆盖），之后让 Kavi 改也是接着这份底稿改。
 */
class DesignEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = sourceOf(this, intent.getStringExtra(EXTRA_PATH))
        val settings = Store(this).loadSettings()
        setContent {
            JackTheme(settings.themeMode, settings.accent, settings.fontScale, settings.appIcon) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (src == null) Text("找不到这张图的底稿（可能被删了或改了名），没法编辑。可以让 Kavi 重新做一张。", Modifier.padding(24.dp))
                    else DesignEditorScreen(src, onClose = { finish() })
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"

        /** 保存过的图：路径 → 次数。聊天里的图按它刷新（同名覆盖，路径不变，不然看不出变化）。 */
        val edits = mutableStateMapOf<String, Int>()

        fun open(ctx: Context, png: File) {
            ctx.startActivity(Intent(ctx, DesignEditorActivity::class.java).putExtra(EXTRA_PATH, png.absolutePath))
        }

        /** 这张图能不能编辑：旁边有出图时存下的底稿（带画布尺寸）。只认 Kavi 自己文件夹里的。 */
        fun sourceOf(ctx: Context, path: String?): File? {
            val f = path?.let { runCatching { File(it).canonicalFile }.getOrNull() } ?: return null
            if (!f.path.startsWith(ctx.filesDir.canonicalPath + File.separator)) return null
            val html = if (f.extension.lowercase() == "html") f else File(f.parentFile, f.nameWithoutExtension + ".html")
            if (!html.isFile) return null
            val head = runCatching { html.bufferedReader().use { r -> CharArray(4096).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } } }.getOrDefault("")
            return html.takeIf { DesignTemplates.savedSize(head) != null }
        }
    }
}

/** 选中的东西（editor.js 发来的）。 */
private data class Picked(
    val kind: String = "none", val slot: String = "", val text: String = "", val color: String = "", val fontSize: Int = 0,
    val fontFamily: String = "", val weight: Int = 400, val align: String = "", val hidden: Boolean = false,
    val lineHeight: Float = 1.4f, val letterSpacing: Float = 0f, val fx: String = "", val added: Boolean = false,
    val editing: Boolean = false, val styled: Boolean = false,
    val opacity: Int = 100, val locked: Boolean = false, val count: Int = 0, val multi: Boolean = false,
)

private val lenient = Json { ignoreUnknownKeys = true }

private fun parsePicked(raw: String): Picked = runCatching {
    val o = lenient.parseToJsonElement(raw).jsonObject
    fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
    Picked(s("kind"), s("slot"), s("text"), s("color"), s("fontSize").toIntOrNull() ?: 0, s("fontFamily"), s("weight").toIntOrNull() ?: 400, s("align"), s("hidden") == "true",
        s("lineHeight").toFloatOrNull() ?: 1.4f, s("letterSpacing").toFloatOrNull() ?: 0f, s("fx"), s("added") == "true", s("editing") == "true", s("styled") == "true",
        s("opacity").toIntOrNull() ?: 100, s("locked") == "true", s("count").toIntOrNull() ?: 0, s("multi") == "true")
}.getOrDefault(Picked())

/** 页面里的 rgb()/rgba() → Compose 颜色（画色块用）。 */
private fun cssColor(s: String): Color? {
    val m = Regex("""rgba?\(([^)]+)\)""").find(s) ?: return runCatching { Color(AColor.parseColor(s.trim())) }.getOrNull()
    val p = m.groupValues[1].split(Regex("[,\\s/]+")).mapNotNull { it.toFloatOrNull() }
    if (p.size < 3) return null
    return Color(p[0] / 255f, p[1] / 255f, p[2] / 255f, p.getOrElse(3) { 1f }.coerceIn(0f, 1f))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun DesignEditorScreen(src: File, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { src.parentFile!!.canonicalFile }
    var picked by remember { mutableStateOf(Picked()) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var askLeave by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 正在图上打字（editor.js 报上来的）；样式面板开着没有（开着时代替工具栏，不挡画布）
    var editingText by remember { mutableStateOf(false) }
    // 代替工具栏的面板：style 文字样式、arrange 排列（对齐、分布、微调、数值）
    var panel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(picked.kind) {
        if (picked.kind == "none") panel = null
        if (picked.kind == "image" && panel == "style") panel = null
    }

    // 页面：底稿 + 填内容的脚本（画图标）+ 编辑器脚本 + 字体
    val loaded by produceState<Triple<ByteArray, Int, Int>?>(null, src) {
        value = withContext(Dispatchers.IO) {
            val html = src.readText()
            val (w, h) = DesignTemplates.savedSize(html) ?: (1080 to 1440)
            val (fontCss, _) = DesignRender.fontCss(ctx, html)
            val js = DesignTemplates.fillJs(ctx) + "\n" + ctx.assets.open("design/editor.js").use { it.readBytes().toString(Charsets.UTF_8) }
            Triple(DesignTemplates.prepare(html, w, h, js, fontCss).toByteArray(), w, h)
        }
    }

    fun cmd(o: JsonObject) { webView?.evaluateJavascript("kvEd.cmd($o)", null); dirty = true }
    fun cmd(op: String, vararg kv: Pair<String, Any>) = cmd(buildJsonObject {
        put("op", op)
        kv.forEach { (k, v) -> when (v) { is Number -> put(k, v); is JsonObject -> put(k, v); else -> put(k, v.toString()) } }
    })
    suspend fun js(code: String): String = suspendCancellableCoroutine { c -> webView?.evaluateJavascript(code) { if (c.isActive) c.resume(it ?: "null") } ?: c.resume("null") }
    fun jsString(raw: String) = runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.content }.getOrNull().orEmpty()

    fun save(thenClose: Boolean) {
        val (_, w, h) = loaded ?: return
        saving = true
        scope.launch {
            val html = jsString(js("kvEd.source()"))
            if (html.isBlank()) { saving = false; Toast.makeText(ctx, "没拿到页面内容，没保存", Toast.LENGTH_LONG).show(); return@launch }
            withContext(Dispatchers.IO) { src.writeText(html) }
            val png = File(src.parentFile, src.nameWithoutExtension + ".png")
            val pdf = File(src.parentFile, src.nameWithoutExtension + ".pdf").takeIf { it.exists() }
            val data = DesignTemplates.data(emptyMap(), null, null, emptyMap(), DesignTemplates.icons(html))
            val r = runCatching { DesignRender.render(ctx, root, html, w, h, data, png, pdf) }.getOrNull()
            // 出图存下来的底稿去掉了临时的东西，以它为准
            if (r != null && r.source.isNotBlank()) withContext(Dispatchers.IO) { src.writeText(r.source) }
            DesignEditorActivity.edits[png.absolutePath] = (DesignEditorActivity.edits[png.absolutePath] ?: 0) + 1
            saving = false
            dirty = false
            val warn = r?.problems.orEmpty()
            Toast.makeText(ctx, if (r == null) "出图失败，底稿已经存好了" else if (warn.isNotEmpty()) "已保存。注意：" + warn.first() else "已保存，聊天里的图也更新了", Toast.LENGTH_LONG).show()
            if (thenClose) onClose()
        }
    }

    BackHandler { if (dirty) askLeave = true else onClose() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(src.nameWithoutExtension, maxLines = 1) },
            navigationIcon = { IconButton(onClick = { if (dirty) askLeave = true else onClose() }) { Icon(Icons.Default.Close, "关闭") } },
            actions = {
                TextButton(onClick = { cmd("undo") }, enabled = canUndo) { Text("撤销") }
                TextButton(onClick = { cmd("redo") }, enabled = canRedo) { Text("重做") }
                TextButton(onClick = { save(false) }, enabled = ready && !saving) { Text(if (saving) "保存中…" else "保存", fontWeight = FontWeight.Bold) }
            },
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            val page = loaded
            if (page == null) CircularProgressIndicator()
            else {
                val (bytes, w, h) = page
                // 固定大小的图：整张放进屏幕；长图：铺满宽，上下滑
                val pad = 12.dp
                val boxW = maxWidth - pad * 2
                val boxH = maxHeight - pad * 2
                val (vw, vh) = if (h <= 0) boxW to boxH
                    else if (boxW / boxH > w.toFloat() / h) (boxH * (w.toFloat() / h)) to boxH else boxW to (boxW * (h.toFloat() / w))
                AndroidView(
                    modifier = Modifier.size(vw, vh),
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.textZoom = 100
                            settings.setSupportZoom(false)
                            setBackgroundColor(AColor.TRANSPARENT)
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            val done = CompletableDeferred<Unit>()
                            addJavascriptInterface(object {
                                @JavascriptInterface fun select(json: String) { post { picked = parsePicked(json) } }
                                @JavascriptInterface fun state(json: String) {
                                    post {
                                        canUndo = "\"undo\":true" in json
                                        canRedo = "\"redo\":true" in json
                                        val now = "\"editing\":true" in json
                                        // 开始打字弹出键盘，打完收起
                                        if (!editingText && now) keyboard(this@apply, true)
                                        if (editingText && !now) keyboard(this@apply, false)
                                        editingText = now
                                    }
                                }
                            }, "KaviEditor")
                            webViewClient = object : WebViewClient() {
                                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean = view.dropAfterRendererGone()
                                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse =
                                    DesignRender.serve(c, root, bytes, request)
                                override fun onPageFinished(v: WebView, url: String) { done.complete(Unit) }
                                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = true
                            }
                            loadUrl("https://${DesignRender.HOST}/")
                            webView = this
                            scope.launch {
                                withTimeoutOrNull(20_000) { done.await() }
                                val html = withContext(Dispatchers.IO) { src.readText() }
                                val data = DesignTemplates.data(emptyMap(), null, null, emptyMap(), DesignTemplates.icons(html))
                                evaluateJavascript("kaviFill($data)", null)
                                withTimeoutOrNull(20_000) { while (jsString(js("window.__kaviDone||''")).isEmpty()) delay(100) }
                                evaluateJavascript("kvEdStart()", null)
                                ready = true
                            }
                        }
                    },
                    onRelease = { it.destroy() },
                )
                if (!ready) CircularProgressIndicator()
            }
        }
        // 工具栏：没选中时是整张图的；选中了按种类给；正在打字时是局部样式；样式面板开着时换成面板
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp)) {
                val many = picked.count > 1
                val label = when {
                    editingText -> "正在改字：选中几个字，可以单独换颜色、加粗、划重点"
                    panel == "style" && picked.kind != "none" -> "文字样式 · ${picked.fontSize}px"
                    panel == "arrange" && picked.kind != "none" -> if (many) "排列：已选 ${picked.count} 个，按它们的外框对齐" else "排列：对齐画布（左右上下留安全边距）"
                    picked.multi -> "多选：点一下加进来，再点一下拿掉" + if (picked.count > 0) "（已选 ${picked.count} 个）" else ""
                    many -> "已选 ${picked.count} 个：拖动一起挪；长按别的再加"
                    picked.locked -> "已锁定：拖不动，点「解锁」再改位置"
                    picked.kind == "text" -> "文字" + (picked.slot.takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: "") + " · ${picked.fontSize}px · 双击直接改字"
                    picked.kind == "image" -> "图片"
                    picked.kind == "group" -> "一组（列表的一项）"
                    picked.kind == "box" -> "图形"
                    else -> "点一下选中，双击改字，长按多选；拖四角缩放，拖顶上圆点旋转"
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1)
                if (!editingText && picked.kind != "none") {
                    if (panel == "style" && picked.kind in setOf("text", "group")) {
                        StylePanel(picked, vars = { webView }, onCmd = { op, kv -> cmd(op, *kv) }, onClose = { panel = null })
                        return@Column
                    }
                    if (panel == "arrange") {
                        ArrangePanel(picked, onCmd = { op, kv -> cmd(op, *kv) }, onClose = { panel = null })
                        return@Column
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    @Composable fun Tool(text: String, bold: Boolean = false, onClick: () -> Unit) =
                        TextButton(onClick = onClick, enabled = ready) { Text(text, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal) }
                    fun span(vararg kv: Pair<String, String>) = cmd("span", "style" to JsonObject(kv.associate { it.first to JsonPrimitive(it.second) }))
                    fun multiMode(on: Boolean) = webView?.evaluateJavascript("kvEd.cmd({op:'multi',on:$on})", null)
                    fun deselect() = webView?.evaluateJavascript("kvEd.cmd({op:'deselect'})", null)
                    when {
                        editingText -> {
                            Tool("完成", bold = true) { cmd("done") }
                            Tool("颜色") { sheet = "spanColor" }
                            Tool("加粗") { span("font-weight" to "900") }
                            Tool("荧光笔") { span("background-image" to "linear-gradient(transparent 58%, color-mix(in srgb, var(--accent) 55%, transparent) 58%)") }
                            Tool("下划线") { span("text-decoration" to "underline", "text-decoration-color" to "var(--accent)", "text-decoration-thickness" to ".08em", "text-underline-offset" to ".14em") }
                            Tool("放大") { span("font-size" to "1.25em") }
                            Tool("清除局部样式") { cmd("spanClear") }
                            Tool("大框编辑") { cmd("done"); sheet = "text" }
                        }
                        picked.kind == "none" -> {
                            Tool("加文字") { webView?.requestFocus(); cmd("addText") }
                            Tool("换配色") { sheet = "palette" }
                            Tool(if (picked.multi) "退出多选" else "多选", bold = picked.multi) { multiMode(!picked.multi) }
                        }
                        many || picked.multi -> {
                            if (picked.multi) Tool("选好了", bold = true) { multiMode(false) }
                            Tool("排列") { panel = "arrange" }
                            Tool("颜色") { sheet = "color" }
                            Tool("上移一层") { cmd("z", "dir" to 1) }
                            Tool("下移一层") { cmd("z", "dir" to -1) }
                            Tool(if (picked.locked) "解锁" else "锁定") { cmd("lock") }
                            Tool("复位") { cmd("reset") }
                            Tool(if (picked.hidden) "显示" else "隐藏") { cmd("hide") }
                            Tool("取消选中") { multiMode(false); deselect() }
                        }
                        else -> {
                            if (picked.kind == "text") Tool("改字", bold = true) { webView?.let { keyboard(it, true) }; cmd("edit") }
                            if (picked.kind == "image") Tool("换图") { sheet = "image" }
                            if (picked.kind == "text" || picked.kind == "group") Tool("样式") { panel = "style" }
                            Tool("排列") { panel = "arrange" }
                            if (picked.kind != "image") Tool("颜色") { sheet = "color" }
                            if (picked.kind == "text") {
                                Tool("字体") { sheet = "font" }
                                Tool(if (picked.weight >= 600) "变细" else "加粗") { cmd("weight", "value" to if (picked.weight >= 600) 400 else 700) }
                                Tool("文字对齐") { cmd("align", "value" to when (picked.align) { "left", "start" -> "center"; "center" -> "right"; else -> "left" }) }
                            }
                            if (picked.kind == "box" || picked.kind == "group") Tool("底色") { sheet = "background" }
                            Tool("上移一层") { cmd("z", "dir" to 1) }
                            Tool("下移一层") { cmd("z", "dir" to -1) }
                            Tool(if (picked.locked) "解锁" else "锁定") { cmd("lock") }
                            Tool("复位") { cmd("reset") }
                            if (picked.added) Tool("删除") { cmd("remove") }
                            else Tool(if (picked.hidden) "显示" else "隐藏") { cmd("hide") }
                            Tool("取消选中") { deselect() }
                        }
                    }
                }
            }
        }
    }

    when (sheet) {
        "text" -> {
            var value by remember(picked.text) { mutableStateOf(picked.text) }
            AlertDialog(
                onDismissRequest = { sheet = null },
                title = { Text("改字") },
                text = { OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 6) },
                confirmButton = { TextButton(onClick = { cmd("text", "value" to value); sheet = null }) { Text("好") } },
                dismissButton = { TextButton(onClick = { sheet = null }) { Text("取消") } },
            )
        }
        "color", "background" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            ColorSheet(webView) { css -> cmd(if (sheet == "color") "color" else "background", "value" to css); sheet = null }
        }
        // 正在改字时选中的那几个字换颜色（没选中字就整段）
        "spanColor" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            ColorSheet(webView) { css -> cmd("span", "style" to JsonObject(mapOf("color" to JsonPrimitive(css)))); sheet = null }
        }
        "font" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            val fonts = remember { DesignFonts.all(ctx) }
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                item { SheetTitle("字体（第一次用要联网下载，稍等一下）") }
                item { FontRow("手机自带黑体", "", picked.fontFamily.startsWith("Noto Sans CJK")) { cmd("font", "value" to "\"Noto Sans CJK SC\",sans-serif"); sheet = null } }
                item { FontRow("手机自带宋体", "", picked.fontFamily.startsWith("Noto Serif CJK")) { cmd("font", "value" to "\"Noto Serif CJK SC\",serif"); sheet = null } }
                items(fonts) { f ->
                    FontRow(f.label, f.use, picked.fontFamily == f.family) {
                        sheet = null
                        scope.launch {
                            val (css, failed) = withContext(Dispatchers.IO) { DesignFonts.css(ctx, listOf(f)) }
                            if (failed.isNotEmpty()) Toast.makeText(ctx, "字体没下载下来，联网后再试", Toast.LENGTH_LONG).show()
                            webView?.evaluateJavascript("kvEd.cmd(${buildJsonObject { put("op", "fontCss"); put("css", css) }})", null)
                            cmd("font", "value" to "\"${f.family}\",\"Noto Sans CJK SC\",sans-serif")
                        }
                    }
                }
            }
        }
        "palette" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            val lib = remember { DesignStyles.load(ctx) }
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                item { SheetTitle("整张换配色（来自风格库）") }
                lib.styles.forEach { s ->
                    val palettes = (s.o["palettes"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    items(palettes) { p ->
                        val vars = listOf("bg", "fg", "primary", "secondary", "accent", "muted").associateWith { (p[it] as? JsonPrimitive)?.content.orEmpty() }
                        Row(Modifier.fillMaxWidth().clickable {
                            cmd("vars", "value" to JsonObject(vars.mapValues { JsonPrimitive(it.value) })); sheet = null
                        }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(3.dp)) {
                                vars.values.forEach { v -> Box(Modifier.size(22.dp).background(cssColor(v) ?: Color.Gray, CircleShape)) }
                            }
                            Spacer(Modifier.width(14.dp))
                            Text("${s.name} · ${(p["name"] as? JsonPrimitive)?.content.orEmpty()}")
                        }
                    }
                }
            }
        }
        "image" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            ImageSheet(root) { file ->
                val rel = file.canonicalFile.relativeTo(root).path
                cmd("image", "src" to DesignRender.fileUrl(rel)); sheet = null
            }
        }
    }

    if (askLeave) AlertDialog(
        onDismissRequest = { askLeave = false },
        title = { Text("改动还没保存") },
        confirmButton = { TextButton(onClick = { askLeave = false; save(true) }) { Text("保存再走") } },
        dismissButton = { TextButton(onClick = { askLeave = false; onClose() }) { Text("不要了") } },
    )
}

/** 弹出或收起键盘（在图上直接改字时）。 */
private fun keyboard(v: View, show: Boolean) {
    val imm = v.context.getSystemService(InputMethodManager::class.java) ?: return
    if (show) { v.requestFocus(); imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) } else imm.hideSoftInputFromWindow(v.windowToken, 0)
}

/**
 * 文字样式面板：代替工具栏放在画布下面（不挡画布，边调边看）。
 * 字号、行距、字距加减；效果（描边、硬投影、软阴影、荧光底、反白块）和效果颜色；四种样式预设。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StylePanel(picked: Picked, vars: () -> WebView?, onCmd: (String, Array<Pair<String, Any>>) -> Unit, onClose: () -> Unit) {
    val palette by produceState<Map<String, String>>(emptyMap()) {
        val raw = vars()?.let { w -> suspendCancellableCoroutine<String> { c -> w.evaluateJavascript("kvEd.vars()") { if (c.isActive) c.resume(it ?: "") } } }.orEmpty()
        value = runCatching {
            val s = (Json.parseToJsonElement(raw) as JsonPrimitive).content
            (Json.parseToJsonElement(s) as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        }.getOrDefault(emptyMap())
    }
    var fxColor by remember { mutableStateOf("var(--accent)") }
    fun cmd(op: String, vararg kv: Pair<String, Any>) = onCmd(op, arrayOf(*kv))
    @Composable fun Stepper(name: String, value: String, minus: () -> Unit, plus: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = minus) { Text("－") }
            Text(value, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp), maxLines = 1)
            TextButton(onClick = plus) { Text("＋") }
        }
    }
    @Composable fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
        Surface(onClick = onClick, shape = Radii.s,
            color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Stepper("字号", "${picked.fontSize}", { cmd("fontSize", "factor" to 0.92) }, { cmd("fontSize", "factor" to 1.08) })
            Stepper("行距", "%.2f".format(picked.lineHeight), { cmd("lineHeight", "delta" to -0.05) }, { cmd("lineHeight", "delta" to 0.05) })
            Stepper("字距", "%.2f".format(picked.letterSpacing), { cmd("letterSpacing", "delta" to -0.02) }, { cmd("letterSpacing", "delta" to 0.02) })
        }
        Text("效果", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "无", "stroke" to "描边", "shadow" to "硬投影", "soft" to "软阴影", "highlight" to "荧光底", "block" to "反白块").forEach { (id, name) ->
                Chip(name, picked.fx == id) { cmd("fx", "fx" to id.ifEmpty { "none" }, "color" to fxColor) }
            }
        }
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("效果颜色", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("bg", "fg", "primary", "secondary", "accent", "muted").forEach { k ->
                val css = "var(--$k)"
                Box(Modifier.size(30.dp).background(cssColor(palette[k].orEmpty()) ?: Color.Gray, CircleShape)
                    .border(if (fxColor == css) 3.dp else 1.dp, if (fxColor == css) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { fxColor = css; if (picked.fx.isNotEmpty()) cmd("fx", "fx" to picked.fx, "color" to css) })
            }
        }
        Text("预设", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("title" to "大标题", "sub" to "小标题", "body" to "正文", "label" to "标签").forEach { (id, name) -> Chip(name, false) { cmd("preset", "name" to id) } }
            }
            TextButton(onClick = onClose) { Text("收起", fontWeight = FontWeight.Bold) }
        }
    }
}

/** 按住连续触发的按钮（微调用：点一下挪一下，按住先停一会儿再连续挪）。 */
@Composable
private fun RepeatButton(text: String, onStep: () -> Unit) {
    val scope = rememberCoroutineScope()
    Surface(shape = Radii.s, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(48.dp).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                val job = scope.launch { onStep(); delay(380); while (true) { onStep(); delay(55) } }
                waitForUpOrCancellation()
                job.cancel()
            }
        }) {
        Box(contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.titleMedium) }
    }
}

/**
 * 排列面板：对齐（一个时对齐画布，几个时对齐它们的外框）、等距分布（三个以上）、
 * 微调方向键（一次 1px，按住连续挪）、缩放和角度、透明度。代替工具栏放在画布下面。
 */
@Composable
private fun ArrangePanel(picked: Picked, onCmd: (String, Array<Pair<String, Any>>) -> Unit, onClose: () -> Unit) {
    fun cmd(op: String, vararg kv: Pair<String, Any>) = onCmd(op, arrayOf(*kv))
    @Composable fun Btn(text: String, onClick: () -> Unit) = TextButton(onClick = onClick) { Text(text) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("left" to "左对齐", "hcenter" to "水平居中", "right" to "右对齐", "top" to "顶对齐", "vcenter" to "垂直居中", "bottom" to "底对齐")
                .forEach { (how, name) -> Btn(name) { cmd("arrange", "how" to how) } }
            if (picked.count >= 3) {
                Btn("横向等距") { cmd("distribute", "axis" to "x") }
                Btn("纵向等距") { cmd("distribute", "axis" to "y") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // 方向键排成一个小十字
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RepeatButton("↑") { cmd("nudge", "dx" to 0, "dy" to -1) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RepeatButton("←") { cmd("nudge", "dx" to -1, "dy" to 0) }
                    RepeatButton("↓") { cmd("nudge", "dx" to 0, "dy" to 1) }
                    RepeatButton("→") { cmd("nudge", "dx" to 1, "dy" to 0) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                @Composable fun Row2(name: String, minus: () -> Unit, plus: () -> Unit, extra: (@Composable () -> Unit)? = null) =
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(48.dp))
                        TextButton(onClick = minus) { Text("－") }
                        TextButton(onClick = plus) { Text("＋") }
                        extra?.invoke()
                    }
                if (picked.count <= 1) {
                    Row2("缩放", { cmd("tf", "ds" to -0.05) }, { cmd("tf", "ds" to 0.05) })
                    Row2("角度", { cmd("tf", "dr" to -1) }, { cmd("tf", "dr" to 1) }) { TextButton(onClick = { cmd("tf", "dr" to 90) }) { Text("转90°") } }
                }
                Row2("透明", { cmd("opacity", "delta" to -0.1) }, { cmd("opacity", "delta" to 0.1) }) {
                    Text("${picked.opacity}%", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { cmd("lock") }) { Text(if (picked.locked) "解锁" else "锁定") }
            TextButton(onClick = onClose) { Text("收起", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SheetTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))

@Composable
private fun FontRow(name: String, use: String, current: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(name + if (current) "  ✓" else "", fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
        if (use.isNotEmpty()) Text(use, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** 颜色：先给这张图自己的六个配色（用变量，之后整张换配色还跟着变），再给黑白和几个常用色。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSheet(webView: WebView?, onPick: (String) -> Unit) {
    val vars by produceState<Map<String, String>>(emptyMap(), webView) {
        val raw = webView?.let { w -> suspendCancellableCoroutine<String> { c -> w.evaluateJavascript("kvEd.vars()") { if (c.isActive) c.resume(it ?: "") } } }.orEmpty()
        value = runCatching {
            val s = (Json.parseToJsonElement(raw) as JsonPrimitive).content
            (Json.parseToJsonElement(s) as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        }.getOrDefault(emptyMap())
    }
    val names = mapOf("bg" to "底色", "fg" to "正文", "primary" to "主色", "secondary" to "辅色", "accent" to "点缀", "muted" to "次要")
    val extra = listOf("#FFFFFF", "#111111", "#E23B2E", "#F28C28", "#F5C518", "#2E9E6A", "#2F6FDB", "#8E5CD9", "#E8579A", "#8A8A8A")
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        Text("这张图的配色", style = MaterialTheme.typography.titleSmall)
        FlowRow(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            vars.forEach { (k, v) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick("var(--$k)") }) {
                    Box(Modifier.size(40.dp).background(cssColor(v) ?: Color.Gray, CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    Text(names[k] ?: k, fontSize = 11.sp)
                }
            }
        }
        Text("其他颜色", style = MaterialTheme.typography.titleSmall)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            extra.forEach { c ->
                Box(Modifier.size(40.dp).background(Color(AColor.parseColor(c)), CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable { onPick(c) })
            }
        }
    }
}

/** 换图：对话文件夹里的图片，或者从相册挑一张（复制进对话文件夹）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageSheet(root: File, onPick: (File) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pics by produceState<List<File>>(emptyList(), root) {
        value = withContext(Dispatchers.IO) {
            root.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif") }?.sortedByDescending { it.lastModified() }?.take(60).orEmpty()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val out = com.jack.ai.data.Workspace.uniqueFile(root, "照片.jpg")
                    ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
                    out
                }.getOrNull()
            }
            if (f != null) onPick(f) else Toast.makeText(ctx, "这张图读不出来", Toast.LENGTH_LONG).show()
        }
    }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("从相册选一张") }
        if (pics.isNotEmpty()) Text("对话里的图片", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pics.forEach { f ->
                val bmp by produceState<android.graphics.Bitmap?>(null, f) { value = withContext(Dispatchers.IO) { Attachments.thumbnail(f.path, 240) } }
                Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceVariant, Radii.s).clickable { onPick(f) }) {
                    bmp?.let { Image(it.asImageBitmap(), f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            }
        }
    }
}
