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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import com.jack.ai.data.DesignSeries
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
        private val edits = mutableStateMapOf<String, Int>()
        /** /data/user/0 和 /data/data 是同一个文件夹，统一成真实路径再对，不然聊天里认不出改过。 */
        private fun keyOf(path: String) = runCatching { File(path).canonicalPath }.getOrDefault(path)
        fun version(path: String): Int = edits[keyOf(path)] ?: 0
        fun bump(file: File) { val k = keyOf(file.path); edits[k] = (edits[k] ?: 0) + 1 }

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

/** 一组里的一页：底稿内容放在内存里，翻页、加页、调顺序都先改这里，保存时一起写盘。 */
internal data class PageDoc(val key: Long, val origin: File?, val html: String, val dirty: Boolean = false, val hadPdf: Boolean = false)

/** 选中的东西（editor.js 发来的）。 */
internal data class Picked(
    val kind: String = "none", val slot: String = "", val text: String = "", val color: String = "", val fontSize: Int = 0,
    val fontFamily: String = "", val weight: Int = 400, val align: String = "", val hidden: Boolean = false,
    val lineHeight: Float = 1.4f, val letterSpacing: Float = 0f, val fx: String = "", val added: Boolean = false,
    val editing: Boolean = false, val styled: Boolean = false,
    val opacity: Int = 100, val locked: Boolean = false, val count: Int = 0, val multi: Boolean = false,
    // 图片：有没有照片（占位色块没有）、是不是在裁剪、图片设置（data-kv-img：滤镜 f、蒙层 m、形状 r、描边 ol/olc、阴影 sh、调色 b/c/s/t、放大 z）
    val photo: Boolean = false, val cropping: Boolean = false, val img: Map<String, String> = emptyMap(),
    // 加进来的元素：sticker 贴纸、shape 形状、icon 图标（空的是页面原有的）；贴纸怎么换色（fill / stroke）；形状描边多粗
    val piece: String = "", val recolor: String = "", val stroke: Float = 0f,
)

private val lenient = Json { ignoreUnknownKeys = true }

private fun parsePicked(raw: String): Picked = runCatching {
    val o = lenient.parseToJsonElement(raw).jsonObject
    fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
    Picked(s("kind"), s("slot"), s("text"), s("color"), s("fontSize").toIntOrNull() ?: 0, s("fontFamily"), s("weight").toIntOrNull() ?: 400, s("align"), s("hidden") == "true",
        s("lineHeight").toFloatOrNull() ?: 1.4f, s("letterSpacing").toFloatOrNull() ?: 0f, s("fx"), s("added") == "true", s("editing") == "true", s("styled") == "true",
        s("opacity").toIntOrNull() ?: 100, s("locked") == "true", s("count").toIntOrNull() ?: 0, s("multi") == "true",
        s("photo") == "true", s("cropping") == "true",
        (o["img"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty(),
        s("piece"), s("recolor"), s("stroke").toFloatOrNull() ?: 0f)
}.getOrDefault(Picked())

/** 页面里的 rgb()/rgba() → Compose 颜色（画色块用）。 */
internal fun cssColor(s: String): Color? {
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
    // 抠图：正在抠（显示在工具栏上方）
    var cutting by remember { mutableStateOf(false) }
    var kaviBusy by remember { mutableStateOf(false) }
    // 改尺寸：正在做时显示进度（null 是没在做）
    var resizing by remember { mutableStateOf<String?>(null) }
    var advice by remember { mutableStateOf<List<DesignKavi.Advice>>(emptyList()) }
    val modelState by com.jack.ai.data.LocalModels.state.collectAsState()
    // 换配色后等着问「整组都换吗」的那套
    var pendingVars by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(picked.kind) {
        if (picked.kind == "none" && panel != "kavi" && panel != "review" && panel != "elements") panel = null
        if (picked.kind == "image" && panel == "style") panel = null
        if (picked.kind != "image" && panel == "image") panel = null
    }

    // 一组的所有页（design action=series 出的「名字-01、名字-02……」）；单张就一页
    val base = remember { DesignSeries.baseOf(src) }
    val pages = remember { mutableStateListOf<PageDoc>() }
    var cur by remember { mutableIntStateOf(0) }
    // 加过页、删过页、调过顺序（保存时要重排文件名、改页码）
    var reshaped by remember { mutableStateOf(false) }
    var nextKey by remember { mutableLongStateOf(1L) }
    LaunchedEffect(src) {
        val files = withContext(Dispatchers.IO) { DesignSeries.pagesOf(src) }
        val docs = withContext(Dispatchers.IO) {
            files.map { f -> PageDoc(nextKey++, f, f.readText(), hadPdf = File(f.parentFile, f.nameWithoutExtension + ".pdf").exists()) }
        }
        pages.clear(); pages.addAll(docs)
        cur = files.indexOfFirst { it.canonicalPath == src.canonicalPath }.coerceAtLeast(0)
    }
    val group = base != null && pages.size > 1 || reshaped
    val page = pages.getOrNull(cur)
    val anyDirty = dirty || reshaped || pages.any { it.dirty }

    // 当前页：底稿 + 填内容的脚本（画图标）+ 编辑器脚本 + 字体
    val loadedPage by produceState<Pair<Long, Triple<ByteArray, Int, Int>>?>(null, page?.key) {
        value = null
        val doc = page ?: return@produceState
        value = doc.key to withContext(Dispatchers.IO) {
            val html = doc.html
            val (w, h) = DesignTemplates.savedSize(html) ?: (1080 to 1440)
            val (fontCss, _) = DesignRender.fontCss(ctx, html)
            val js = DesignTemplates.fillJs(ctx) + "\n" + ctx.assets.open("design/editor.js").use { it.readBytes().toString(Charsets.UTF_8) }
            Triple(DesignTemplates.prepare(html, w, h, js, fontCss).toByteArray(), w, h)
        }
    }
    // 翻页的那一下，上一页的内容不拿来建页面
    val loaded = loadedPage?.takeIf { it.first == page?.key }?.second

    fun cmd(o: JsonObject) { webView?.evaluateJavascript("kvEd.cmd($o)", null); dirty = true }
    fun cmd(op: String, vararg kv: Pair<String, Any>) = cmd(buildJsonObject {
        put("op", op)
        kv.forEach { (k, v) -> when (v) { is Number -> put(k, v); is Boolean -> put(k, v); is JsonObject -> put(k, v); else -> put(k, v.toString()) } }
    })
    suspend fun js(code: String): String = suspendCancellableCoroutine { c -> webView?.evaluateJavascript(code) { if (c.isActive) c.resume(it ?: "null") } ?: c.resume("null") }
    fun jsString(raw: String) = runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.content }.getOrNull().orEmpty()

    /** 抠选中的照片：存成「原名-抠图.png」放进对话文件夹，换进这个图片位（透明底，露出框的底色）。模型没下载就先问下哪个。 */
    fun cutout() {
        if (com.jack.ai.data.LocalModels.cutoutReady(ctx) == null) { sheet = "cutoutModel"; return }
        scope.launch {
            val url = jsString(js("kvEd.photo()"))
            val rel = android.net.Uri.decode(url.substringAfter("/files/", "")).takeIf { url.contains("/files/") && it.isNotBlank() }
            val src = rel?.let { PageCheck.resolve(root, it) }
            if (src == null || !src.isFile) { Toast.makeText(ctx, "这张图不在对话文件夹里，先换图再抠", Toast.LENGTH_SHORT).show(); return@launch }
            val m = com.jack.ai.data.LocalModels.cutoutReady(ctx) ?: return@launch
            cutting = true
            val out = runCatching {
                withContext(Dispatchers.IO) {
                    val o = com.jack.ai.data.Workspace.uniqueFile(root, src.nameWithoutExtension.removeSuffix("-抠图") + "-抠图.png")
                    com.jack.ai.api.LocalCutout.cut(File(com.jack.ai.data.LocalModels.dir(ctx, m), m.files.first().path), src, o)
                    o
                }
            }
            cutting = false
            out.onSuccess { o -> cmd("image", "src" to DesignRender.fileUrl(o.canonicalFile.relativeTo(root).path)) }
                .onFailure { e -> Toast.makeText(ctx, "抠图失败：${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    // Kavi 在聊天里改了某一页的底稿：开着的是这页就当场换成新版（一步可撤销），别的页换掉内存里那份
    var kaviConflict by remember { mutableStateOf<Pair<Int, String>?>(null) }
    suspend fun applyExternal(i: Int, html: String) {
        if (i == cur) {
            val (css, _) = DesignRender.fontCss(ctx, html)
            if (css.isNotEmpty()) js("kvEd.cmd(${buildJsonObject { put("op", "fontCss"); put("css", css) }})")
            js("kvEd.cmd(${buildJsonObject { put("op", "external"); put("html", html) }})")
            dirty = false
            Toast.makeText(ctx, "Kavi 刚改了这一页，改动闪了一下；不想要点「撤销」", Toast.LENGTH_LONG).show()
        }
        pages[i] = pages[i].copy(html = html, dirty = false)
    }
    LaunchedEffect(Unit) {
        com.jack.ai.data.DesignEvents.sourceChanged.collect { path ->
            val i = pages.indexOfFirst { p -> p.origin?.let { runCatching { it.canonicalPath }.getOrNull() } == path }
            if (i < 0 || saving) return@collect
            val html = withContext(Dispatchers.IO) { runCatching { File(path).readText() }.getOrNull() } ?: return@collect
            if (html == pages[i].html) return@collect
            // 这边也有没保存的改动：问一句用哪个
            if ((i == cur && dirty) || pages[i].dirty) kaviConflict = i to html else applyExternal(i, html)
        }
    }
    kaviConflict?.let { (i, html) ->
        AlertDialog(
            onDismissRequest = { kaviConflict = null },
            title = { Text("Kavi 改了第 ${i + 1} 页") },
            text = { Text("你这边也有没保存的改动。用 Kavi 的新版，你没保存的改动会丢掉；保留你的，保存时会盖掉 Kavi 的修改。") },
            confirmButton = { TextButton(onClick = { kaviConflict = null; scope.launch { applyExternal(i, html) } }) { Text("用 Kavi 的") } },
            dismissButton = { TextButton(onClick = { kaviConflict = null }) { Text("保留我的") } },
        )
    }
    // 修图：选中的照片拿去修，修好的新图换进图片位
    val photoEdit = androidx.activity.compose.rememberLauncherForActivityResult(com.jack.ai.ui.photo.PhotoEditorActivity.Edit) { out ->
        if (out != null) runCatching { cmd("image", "src" to DesignRender.fileUrl(out.canonicalFile.relativeTo(root).path)) }
    }
    fun editPhoto() = scope.launch {
        val url = jsString(js("kvEd.photo()"))
        val rel = android.net.Uri.decode(url.substringAfter("/files/", "")).takeIf { url.contains("/files/") && it.isNotBlank() }
        val f = rel?.let { PageCheck.resolve(root, it) }
        if (f == null || !f.isFile) Toast.makeText(ctx, "这张图不在对话文件夹里，先换图再修", Toast.LENGTH_SHORT).show() else photoEdit.launch(f)
    }

    // 让 Kavi 改：选中的一块（或整张）+ 一句话，单独请一次模型，换进画布（一步可撤销）。path 不为空时改那一块（空列表是整张）
    suspend fun kaviEdit(ask: String, path: List<Int>? = null): Boolean {
        val r = runCatching {
            val arg = path?.let { "[" + it.joinToString(",") + "]" } ?: ""
            val t = DesignKavi.parseTarget(jsString(js("kvEd.target($arg)"))) ?: error("没拿到要改的内容")
            val res = withContext(Dispatchers.IO) { DesignKavi.edit(ctx, t, ask) }
            // 换了字体的先把字体下好、装进页面
            val (css, _) = DesignRender.fontCss(ctx, res.html)
            if (css.isNotEmpty()) webView?.evaluateJavascript("kvEd.cmd(${buildJsonObject { put("op", "fontCss"); put("css", css) }})", null)
            cmd(buildJsonObject {
                put("op", "kavi"); put("scope", t.scope); put("html", res.html)
                put("path", kotlinx.serialization.json.JsonArray(t.path.map { JsonPrimitive(it) }))
            })
            res.note
        }
        r.onSuccess { if (path == null) Toast.makeText(ctx, it.ifEmpty { "改好了" } + "（不满意点撤销）", Toast.LENGTH_LONG).show() }
            .onFailure { e -> Toast.makeText(ctx, e.message ?: "没改成", Toast.LENGTH_LONG).show() }
        return r.isSuccess
    }
    fun askKavi(ask: String) {
        if (ask.isBlank() || kaviBusy) return
        scope.launch { kaviBusy = true; kaviEdit(ask); kaviBusy = false }
    }
    // Kavi 点评：跑一遍检查，请模型挑出最该改的几处，编号标在画布上
    fun showMarks() {
        val items = kotlinx.serialization.json.JsonArray(advice.map { a ->
            buildJsonObject { put("label", a.label.toString()); put("path", kotlinx.serialization.json.JsonArray(a.path.map { JsonPrimitive(it) })) }
        })
        webView?.evaluateJavascript("kvEd.marks($items)", null)
    }
    fun startReview() {
        if (kaviBusy) return
        scope.launch {
            kaviBusy = true
            webView?.evaluateJavascript("kvEd.cmd({op:'deselect'})", null)
            val r = runCatching {
                val t = DesignKavi.parseTarget(jsString(js("kvEd.target([])"))) ?: error("没拿到这张图")
                withContext(Dispatchers.IO) { DesignKavi.review(ctx, jsString(js("kvEd.review()")), t) }
            }
            kaviBusy = false
            r.onSuccess { advice = it; panel = "review"; showMarks() }
                .onFailure { e -> Toast.makeText(ctx, e.message ?: "没看成", Toast.LENGTH_LONG).show() }
        }
    }
    fun applyAdvice(list: List<DesignKavi.Advice>) {
        if (kaviBusy || list.isEmpty()) return
        scope.launch {
            kaviBusy = true
            for (a in list) {
                if (kaviEdit(a.fix, a.path)) { advice = advice - a; showMarks() } else break
            }
            kaviBusy = false
            if (advice.isEmpty()) { panel = null; Toast.makeText(ctx, "改好了（不满意点撤销）", Toast.LENGTH_LONG).show() }
        }
    }

    // 当前页改过的话，把页面里的内容收回到内存里那份
    suspend fun capture() {
        if (!dirty || page == null) return
        val html = jsString(js("kvEd.source()"))
        if (html.isNotBlank()) pages[cur] = pages[cur].copy(html = html, dirty = true)
        dirty = false
    }
    fun goTo(i: Int) = scope.launch {
        if (i == cur || i !in pages.indices) return@launch
        capture()
        ready = false; picked = Picked(); panel = null; canUndo = false; canRedo = false
        cur = i
    }
    // 加页、删页、调顺序：当前显示的页换了才重新加载（没换的话页面和撤销历史都留着）
    fun reshape(change: () -> Unit) = scope.launch {
        capture()
        val before = pages.getOrNull(cur)?.key
        change()
        reshaped = true
        if (pages.getOrNull(cur)?.key != before) { ready = false; picked = Picked(); panel = null; canUndo = false; canRedo = false }
    }

    /**
     * 保存：一组的话按现在的顺序写成 名字-01、-02……（页码位置跟着改），多出来的旧文件删掉；
     * 内容变了的页重新出图（同名覆盖，聊天里的图跟着变）。单张就是写回底稿、重新出图。
     */
    fun save(thenClose: Boolean, after: (suspend () -> Unit)? = null) {
        if (pages.isEmpty()) return
        saving = true
        scope.launch {
            capture()
            val n = pages.size
            val problems = mutableListOf<String>()
            var failed = 0
            for (i in 0 until n) {
                val doc = pages[i]
                val target = if (base != null && group) File(root, DesignSeries.pageName(base, i) + ".html") else doc.origin ?: src
                val html = if (base != null && group) DesignSeries.withPageNumber(doc.html, i, n) else doc.html
                val old = withContext(Dispatchers.IO) { target.takeIf { it.isFile }?.readText() }
                if (old == html && !doc.dirty) { pages[i] = doc.copy(origin = target); continue }
                withContext(Dispatchers.IO) { target.writeText(html) }
                val (w, h) = DesignTemplates.savedSize(html) ?: (1080 to 1440)
                val png = File(target.parentFile, target.nameWithoutExtension + ".png")
                val pdf = File(target.parentFile, target.nameWithoutExtension + ".pdf").takeIf { it.exists() || doc.hadPdf }
                val data = DesignTemplates.data(emptyMap(), null, null, emptyMap(), DesignTemplates.icons(html), com.jack.ai.data.DesignElements.stickers(ctx, html))
                val r = runCatching { DesignRender.render(ctx, root, html, w, h, data, png, pdf) }.getOrNull()
                // 出图存下来的底稿去掉了临时的东西，以它为准
                val saved = r?.source?.takeIf { it.isNotBlank() } ?: html
                if (r != null) withContext(Dispatchers.IO) { target.writeText(saved) } else failed++
                r?.problems?.firstOrNull()?.let { problems += "第 ${i + 1} 页：$it" }
                pages[i] = doc.copy(origin = target, html = saved, dirty = false, hadPdf = pdf != null)
                DesignEditorActivity.bump(png)
            }
            // 删过页：后面多出来的旧文件删掉
            if (base != null && group) withContext(Dispatchers.IO) {
                var j = n
                while (true) {
                    val stem = DesignSeries.pageName(base, j)
                    val fs = listOf("html", "png", "pdf").map { File(root, "$stem.$it") }.filter { it.exists() }
                    if (fs.isEmpty()) break
                    fs.forEach { it.delete() }
                    j++
                }
            }
            val added = reshaped
            reshaped = false
            saving = false
            dirty = false
            Toast.makeText(ctx, when {
                failed > 0 -> "有 $failed 页出图失败，底稿已经存好了"
                problems.isNotEmpty() -> "已保存。注意：" + problems.first()
                added -> "已保存。加的页在对话文件夹里，聊天里原来的图也更新了"
                else -> "已保存，聊天里的图也更新了"
            }, Toast.LENGTH_LONG).show()
            after?.invoke()
            if (thenClose) onClose()
        }
    }

    // 整组（或这一张）存进相册：先保存，再按顺序存
    fun exportAll() = save(false) {
        val pngs = pages.mapNotNull { it.origin?.let { f -> File(f.parentFile, f.nameWithoutExtension + ".png") } }
        val n = withContext(Dispatchers.IO) { DesignSeries.exportToGallery(ctx, pngs, base ?: src.nameWithoutExtension) }
        Toast.makeText(ctx, if (n > 0) "已存 $n 张到相册（Pictures/Kavi）" else "没存成，看看是不是没给存储权限", Toast.LENGTH_LONG).show()
    }

    // 一键改尺寸：先保存，再按新尺寸重排（一张或整组），出好的新图在编辑器里打开（原图不动）
    fun resize(key: String, all: Boolean) {
        if (resizing != null || kaviBusy) return
        val (w, h) = DesignTemplates.SIZES[key]?.let { it.second to it.third } ?: return
        val label = DesignResize.LABELS[key] ?: key
        val run: suspend () -> Unit = {
            val files = if (all && group) pages.mapNotNull { it.origin } else listOfNotNull(pages.getOrNull(cur)?.origin ?: src)
            resizing = "正在改成$label…"
            val r = runCatching {
                DesignResize.resizeAll(ctx, root, files, w, h, label) { done, n ->
                    resizing = if (n > 1) "正在改成$label：$done/$n 张，每张半分钟左右…" else "正在改成$label，半分钟左右…"
                }
            }
            resizing = null
            r.onSuccess { outs ->
                val miss = files.size - outs.size
                val bad = outs.count { it.problems.isNotEmpty() }
                Toast.makeText(ctx, "改好了：${outs.first().png.nameWithoutExtension}" + (if (outs.size > 1) " 等 ${outs.size} 张" else "") +
                    (if (miss > 0) "，有 $miss 张没改成" else "") + (if (bad > 0) "，有 $bad 张还有小毛病，可以接着调" else "") +
                    "。原图没动，新图在对话文件夹里", Toast.LENGTH_LONG).show()
                DesignEditorActivity.open(ctx, outs.first().png)
            }.onFailure { e -> Toast.makeText(ctx, e.message ?: "没改成", Toast.LENGTH_LONG).show() }
        }
        if (anyDirty) save(false) { run() } else scope.launch { run() }
    }

    // 把这页的配色和字体用到整组别的页
    fun syncTheme(theme: Map<String, String>) {
        for (i in pages.indices) if (i != cur) pages[i] = pages[i].copy(html = DesignSeries.withCanvasVars(pages[i].html, theme), dirty = true)
        Toast.makeText(ctx, "整组 ${pages.size} 页都换好了，保存后生效", Toast.LENGTH_SHORT).show()
    }
    suspend fun currentTheme(): Map<String, String> = runCatching {
        val raw = jsString(js("kvEd.theme()"))
        (Json.parseToJsonElement(raw) as JsonObject).mapValues { (it.value as JsonPrimitive).content }
    }.getOrDefault(emptyMap())

    BackHandler { if (anyDirty) askLeave = true else onClose() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (group) "${base ?: src.nameWithoutExtension} · ${cur + 1}/${pages.size}" else src.nameWithoutExtension, maxLines = 1) },
            navigationIcon = { IconButton(onClick = { if (anyDirty) askLeave = true else onClose() }) { Icon(Icons.Default.Close, "关闭") } },
            actions = {
                TextButton(onClick = { cmd("undo") }, enabled = canUndo) { Text("撤销") }
                TextButton(onClick = { cmd("redo") }, enabled = canRedo) { Text("重做") }
                TextButton(onClick = { save(false) }, enabled = pages.isNotEmpty() && !saving) { Text(if (saving) "保存中…" else "保存", fontWeight = FontWeight.Bold) }
            },
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            val shown = loaded
            if (shown == null) CircularProgressIndicator()
            else key(page?.key) {
                val (bytes, w, h) = shown
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
                                val html = page?.html.orEmpty()
                                val data = DesignTemplates.data(emptyMap(), null, null, emptyMap(), DesignTemplates.icons(html), com.jack.ai.data.DesignElements.stickers(ctx, html))
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
        // 一组的话：缩略图条，点一下翻到那页，长按复制、移动、删除；最后的「＋」复制当前页加在后面
        if (group || base != null) PageStrip(
            pages = pages, cur = cur, root = root,
            onPick = { goTo(it) },
            onDuplicate = { i -> reshape { pages.add(i + 1, pages[i].copy(key = nextKey++, origin = null, dirty = true, hadPdf = false)); cur = i + 1 } },
            onMove = { i, d -> reshape { val j = i + d; if (j in pages.indices) { val t = pages[i]; pages[i] = pages[j]; pages[j] = t; if (cur == i) cur = j else if (cur == j) cur = i } } },
            onDelete = { i ->
                if (pages.size > 1) reshape {
                    pages.removeAt(i)
                    // 删的是前面的页，当前页往前挪一位；删的就是当前页，停在原位置（最后一页就退一位）
                    cur = if (i < cur) cur - 1 else cur.coerceAtMost(pages.size - 1)
                }
            },
        )
        // 工具栏：没选中时是整张图的；选中了按种类给；正在打字时是局部样式；样式面板开着时换成面板
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp)) {
                val many = picked.count > 1
                val label = when {
                    cutting -> "正在抠图，十几秒…"
                    resizing != null -> resizing!!
                    kaviBusy -> "Kavi 在改" + (if (picked.kind == "none") "整张" else "这一块") + "，几秒到十几秒…"
                    panel == "review" -> "Kavi 点评：${advice.size} 处可以改，编号标在图上"
                    panel == "elements" -> "加元素：点一下放到画面中间，颜色跟着配色走"
                    panel == "kavi" -> if (picked.kind == "none") "让 Kavi 改整张图：说说想怎么改" else if (picked.count > 1) "选了好几个：Kavi 改整张图" else "让 Kavi 改选中的这一块"
                    editingText -> "正在改字：选中几个字，可以单独换颜色、加粗、划重点"
                    panel == "style" && picked.kind != "none" -> "文字样式 · ${picked.fontSize}px"
                    panel == "arrange" && picked.kind != "none" -> if (many) "排列：已选 ${picked.count} 个，按它们的外框对齐" else "排列：对齐画布（左右上下留安全边距）"
                    picked.multi -> "多选：点一下加进来，再点一下拿掉" + if (picked.count > 0) "（已选 ${picked.count} 个）" else ""
                    many -> "已选 ${picked.count} 个：拖动一起挪；长按别的再加"
                    picked.locked -> "已锁定：拖不动，点「解锁」再改位置"
                    picked.kind == "text" -> "文字" + (picked.slot.takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: "") + " · ${picked.fontSize}px · 双击直接改字"
                    picked.cropping -> "裁剪：拖动照片挪位置，两指缩放（框不动）" + (picked.img["z"]?.toFloatOrNull()?.takeIf { it > 1.001f }?.let { " · %.1f 倍".format(it) } ?: "")
                    panel == "image" && picked.kind == "image" -> "图片：滤镜、调色、蒙层、形状"
                    picked.kind == "image" -> if (picked.photo) "图片 · 双击裁剪" else "图片（占位，换上照片后能裁剪、加蒙层）"
                    picked.kind == "group" -> "一组（列表的一项）"
                    picked.piece == "sticker" -> "贴纸 · 「颜色」换色，拖四角缩放"
                    picked.piece == "shape" -> "形状 · 「颜色」换填充，「描边」加边框"
                    picked.piece == "icon" -> "图标 · 「颜色」换色"
                    picked.kind == "box" -> "图形"
                    else -> "点一下选中，双击改字，长按多选；拖四角缩放，拖顶上圆点旋转"
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1)
                if (!editingText && panel == "review") {
                    ReviewPanel(advice, busy = kaviBusy, onApply = { applyAdvice(listOf(it)) }, onApplyAll = { applyAdvice(advice) },
                        onClose = { panel = null; advice = emptyList(); showMarks() })
                    return@Column
                }
                if (!editingText && panel == "elements") {
                    ElementsPanel(webView, onAdd = { o -> webView?.requestFocus(); cmd(o); panel = null }, onClose = { panel = null })
                    return@Column
                }
                if (!editingText && panel == "kavi") {
                    KaviPanel(
                        quick = when { picked.kind == "none" || picked.count > 1 -> DesignKavi.QUICK_PAGE; picked.kind == "text" -> DesignKavi.QUICK_TEXT; else -> DesignKavi.QUICK_BOX },
                        busy = kaviBusy, onAsk = { askKavi(it) }, onClose = { panel = null },
                    )
                    return@Column
                }
                if (!editingText && picked.kind != "none") {
                    if (panel == "style" && picked.kind in setOf("text", "group")) {
                        StylePanel(picked, vars = { webView }, onCmd = { op, kv -> cmd(op, *kv) }, onClose = { panel = null })
                        return@Column
                    }
                    if (panel == "image" && picked.kind == "image" && !picked.cropping) {
                        ImagePanel(picked, vars = { webView }, onCmd = { op, kv -> cmd(op, *kv) }, onClose = { panel = null })
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
                            Tool("让 Kavi 改", bold = true) { panel = "kavi" }
                            Tool("Kavi 点评") { startReview() }
                            Tool("加文字") { webView?.requestFocus(); cmd("addText") }
                            Tool("加元素") { panel = "elements" }
                            Tool("换配色") { sheet = "palette" }
                            Tool("改尺寸") { sheet = "resize" }
                            Tool(if (picked.multi) "退出多选" else "多选", bold = picked.multi) { multiMode(!picked.multi) }
                            if (group) Tool("同步到整组") { sheet = "syncTheme" }
                            Tool(if (group) "整组存到相册" else "存到相册") { exportAll() }
                        }
                        picked.cropping -> {
                            Tool("完成", bold = true) { cmd("crop", "on" to false) }
                            Tool("放大") { cmd("cropZoom", "delta" to 0.2) }
                            Tool("缩小") { cmd("cropZoom", "delta" to -0.2) }
                            Tool("还原裁剪") { cmd("cropReset") }
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
                            if (picked.kind != "image") Tool("让 Kavi 改") { panel = "kavi" }
                            if (picked.kind == "image") {
                                Tool("换图") { sheet = "image" }
                                if (picked.photo) Tool("裁剪") { cmd("crop", "on" to true) }
                                if (picked.photo) Tool("修图") { editPhoto() }
                                if (picked.photo && !cutting) Tool("抠图") { cutout() }
                                Tool("滤镜调色") { panel = "image" }
                            }
                            if (picked.kind == "text" || picked.kind == "group") Tool("样式") { panel = "style" }
                            Tool("排列") { panel = "arrange" }
                            if (picked.kind != "image") Tool("颜色") { sheet = "color" }
                            if (picked.kind == "text") {
                                Tool("字体") { sheet = "font" }
                                Tool(if (picked.weight >= 600) "变细" else "加粗") { cmd("weight", "value" to if (picked.weight >= 600) 400 else 700) }
                                Tool("文字对齐") { cmd("align", "value" to when (picked.align) { "left", "start" -> "center"; "center" -> "right"; else -> "left" }) }
                            }
                            if (picked.piece == "shape") Tool(if (picked.recolor == "line") "粗细" else when (picked.stroke) { 0f -> "加描边"; 4f -> "描边加粗"; else -> "去掉描边" }) { cmd("stroke") }
                            if ((picked.kind == "box" || picked.kind == "group") && picked.piece.isEmpty()) Tool("底色") { sheet = "background" }
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
        "resize" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            var all by remember { mutableStateOf(true) }
            val (cw, ch) = loaded?.let { it.second to it.third } ?: (0 to 0)
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                item { SheetTitle("改尺寸：Kavi 按新尺寸重新排版，另存一份，原图不动") }
                if (group) item {
                    Row(Modifier.fillMaxWidth().clickable { all = !all }.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("整组 ${pages.size} 张一起改", Modifier.weight(1f))
                        androidx.compose.material3.Switch(all, { all = it })
                    }
                }
                items(DesignResize.TARGETS) { k ->
                    val (label, w, h) = DesignTemplates.SIZES[k]!!
                    val same = w == cw && h == ch
                    Row(Modifier.fillMaxWidth().clickable(enabled = !same) { sheet = null; resize(k, all) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        // 按比例画个小框，一眼看出横竖
                        val scale = 36f / maxOf(w, h)
                        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size((w * scale).dp, (h * scale).dp).border(1.5.dp, if (same) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, color = if (same) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                            Text("$w×$h" + if (same) " · 现在就是这个尺寸" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            cmd("vars", "value" to JsonObject(vars.mapValues { JsonPrimitive(it.value) }))
                            if (group) { pendingVars = vars; sheet = "syncPalette" } else sheet = null
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
        "cutoutModel" -> {
            val ms = com.jack.ai.data.LocalModels
            val running = ms.CUTOUTS.firstOrNull { modelState[it.id]?.running == true }
            // 下好了就自动抠
            LaunchedEffect(modelState) { if (running == null && ms.cutoutReady(ctx) != null) { sheet = null; cutout() } }
            AlertDialog(
                onDismissRequest = { sheet = null },
                title = { Text("下载抠图模型") },
                text = {
                    Column {
                        if (running != null) {
                            val st = modelState[running.id]
                            Text("正在下载${running.name}：${ms.mb(st?.done ?: 0)} / ${ms.mb(running.bytes)}，可以先关掉，下好后再点「抠图」。")
                        } else {
                            Text("抠图在手机上做，不联网、不花钱，只要下载一次。选一个：")
                            ms.CUTOUTS.mapNotNull { m -> modelState[m.id]?.error?.takeIf { it.isNotEmpty() }?.let { m.name + "：" + it } }.forEach {
                                Text("上次失败：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            listOf(ms.CUTOUT_SMALL, ms.CUTOUT_HD).forEach { m ->
                                Column(Modifier.fillMaxWidth().clickable { ms.start(ctx, m) }.padding(vertical = 10.dp)) {
                                    Text(m.name, fontWeight = FontWeight.Bold)
                                    Text(m.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { sheet = null }) { Text(if (running != null) "后台下载" else "先不用") } },
            )
        }
        "image" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            ImageSheet(root) { file ->
                val rel = file.canonicalFile.relativeTo(root).path
                cmd("image", "src" to DesignRender.fileUrl(rel)); sheet = null
            }
        }
    }

    when (sheet) {
        "syncPalette" -> AlertDialog(
            onDismissRequest = { sheet = null },
            title = { Text("整组都换成这套配色？") },
            text = { Text("这一组有 ${pages.size} 页，换成一样的配色才像一套。") },
            confirmButton = { TextButton(onClick = { syncTheme(pendingVars); sheet = null }) { Text("整组都换") } },
            dismissButton = { TextButton(onClick = { sheet = null }) { Text("只改这页") } },
        )
        "syncTheme" -> AlertDialog(
            onDismissRequest = { sheet = null },
            title = { Text("同步到整组") },
            text = { Text("把这一页的配色和字体用到整组 ${pages.size} 页。各页的文字和排版不变。") },
            confirmButton = { TextButton(onClick = { sheet = null; scope.launch { syncTheme(currentTheme()) } }) { Text("同步") } },
            dismissButton = { TextButton(onClick = { sheet = null }) { Text("取消") } },
        )
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
