package com.jack.ai.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.jack.ai.data.Workspace
import com.jack.ai.data.phone.LinuxSandbox
import com.jack.ai.data.phone.TerminalHub
import com.jack.ai.data.phone.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

private val TERM_BG = Color(0xFF111318)

/** 终端自己的「主目录」（从别处打开、不对应哪个对话时用）。 */
private fun homeDir(ctx: Context) = Workspace.dir(ctx, "terminal-home")

/**
 * 终端页：上面一排开着的终端（Kavi 跑的命令记录也在里面），中间是终端，下面一排手机键盘上没有的键。
 * 终端离开这页也接着跑，回来从缓冲里把画面重建出来。
 */
@Composable
fun TerminalScreen(folder: String, title: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sessions by TerminalHub.sessions.collectAsState()
    val workspace = remember(folder) { if (folder.isEmpty()) homeDir(ctx) else Workspace.dir(ctx, folder) }
    var selected by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    // 字号记住：以前每次进来都回到 13，调大的人每次都要再点几下
    val termPrefs = remember { ctx.getSharedPreferences("terminal", Context.MODE_PRIVATE) }
    var font by remember { mutableStateOf(termPrefs.getInt("font", 13)) }
    val handle = remember { mutableStateOf<TerminalHandle?>(null) }
    fun setFont(v: Int) { font = v; termPrefs.edit().putInt("font", v).apply(); handle.value?.js("kvFont($v)") }
    val scope = rememberCoroutineScope()
    val ready = remember { LinuxSandbox.ready(ctx) }

    fun openNew() = scope.launch {
        error = null
        runCatching { TerminalHub.open(ctx, if (folder.isEmpty()) "终端" else title.ifBlank { "对话" }.take(12), workspace) }
            .onSuccess { selected = it.id }
            .onFailure { error = it.message ?: "开不了终端" }
    }
    // 进来时：这个文件夹已经有在跑的就接着用，没有就开一个
    LaunchedEffect(folder) {
        if (!ready) return@LaunchedEffect
        val live = sessions.firstOrNull { !it.readOnly && it.workspace == workspace && it.phase.value == TerminalSession.Phase.Running }
        if (live != null) selected = live.id else openNew()
    }
    val current = sessions.firstOrNull { it.id == selected } ?: sessions.firstOrNull { it.workspace == workspace } ?: sessions.firstOrNull()

    Column(Modifier.fillMaxSize().background(TERM_BG).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack)
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sessions, key = { it.id }) { s ->
                    val phase by s.phase.collectAsState()
                    Pill((if (phase == TerminalSession.Phase.Running || s.readOnly) "" else "· ") + s.title, s.id == current?.id) { selected = s.id }
                }
            }
            if (ready) CircleIconButton(Icons.Default.Add, "新终端") { openNew() }
            Box {
                CircleIconButton(Icons.Default.MoreVert, "更多") { menu = true }
                RoundMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuRow("复制", glyph = "⧉", sub = "选中的内容，没选就复制全部") { menu = false; handle.value?.js("kvCopyAll()") }
                    MenuRow("粘贴", glyph = "⎘") {
                        menu = false
                        val text = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                        if (text.isNotEmpty()) handle.value?.js("kvPaste('${Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)}')")
                        else android.widget.Toast.makeText(ctx, "剪贴板是空的", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    MenuRow("字大一点", glyph = "A+", sub = "现在 $font") { setFont((font + 1).coerceAtMost(22)) }
                    MenuRow("字小一点", glyph = "A−") { setFont((font - 1).coerceAtLeast(9)) }
                    current?.let { s -> MenuRow("关掉这个终端", glyph = "✕", danger = true) { menu = false; TerminalHub.close(s.id); selected = null } }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !ready -> Hint("Linux 沙箱还没装。到 设置 → 运行代码 → Linux 沙箱 装好以后就能用终端。")
                error != null -> Hint(error!!)
                current == null -> Hint("正在打开终端…")
                else -> key(current.id) {
                    AndroidView(
                        factory = { c -> createTerminalView(c, current, font, handle) { cc, aa -> ctrl = cc; alt = aa }.view },
                        modifier = Modifier.fillMaxSize(),
                    )
                    DisposableEffect(current.id) { onDispose { handle.value?.close(); handle.value = null } }
                }
            }
        }
        if (current != null && !current.readOnly) KeyRow(ctrl, alt,
            onCtrl = { ctrl = !ctrl; handle.value?.js("kvMods($ctrl, $alt)") },
            onAlt = { alt = !alt; handle.value?.js("kvMods($ctrl, $alt)") },
        ) { k ->
            handle.value?.js("kvKey(atob('${Base64.encodeToString(k.toByteArray(), Base64.NO_WRAP)}'))")
            if (ctrl || alt) { ctrl = false; alt = false }
        }
        else if (current?.readOnly == true) Text("Kavi 在这个对话里跑过的命令和输出（只能看）", style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 手机键盘上没有的键。Ctrl、Alt 按一下亮起来，下一个字带上它。 */
@Composable
private fun KeyRow(ctrl: Boolean, alt: Boolean, onCtrl: () -> Unit, onAlt: () -> Unit, onKey: (String) -> Unit) {
    val keys = listOf("ESC" to "\u001b", "TAB" to "\t", "CTRL" to "", "ALT" to "", "←" to "\u001b[D", "↑" to "\u001b[A", "↓" to "\u001b[B", "→" to "\u001b[C",
        "HOME" to "\u001b[H", "END" to "\u001b[F", "PGUP" to "\u001b[5~", "PGDN" to "\u001b[6~", "|" to "|", "-" to "-", "/" to "/", "~" to "~", "*" to "*", "&" to "&")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEach { (label, seq) ->
            val on = (label == "CTRL" && ctrl) || (label == "ALT" && alt)
            Surface(
                onClick = { when (label) { "CTRL" -> onCtrl(); "ALT" -> onAlt(); else -> onKey(seq) } },
                shape = RoundedCornerShape(8.dp),
                color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f),
            ) {
                Text(label, color = Color.White.copy(alpha = 0.9f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
            }
        }
    }
}

/** 终端 WebView 的句柄：给页面发指令、离开时收尾。 */
class TerminalHandle internal constructor(val view: WebView, private val bridge: TerminalBridge) {
    fun js(code: String) { view.evaluateJavascript(code, null); view.requestFocus() }
    fun close() { bridge.close(); runCatching { view.destroy() } }
}

private const val TERMINAL_URL = "https://appassets.androidplatform.net/assets/terminal/index.html"

/** 照 Mika 的 TerminalWebView（Apache-2.0）：xterm.js 只从 App 自带的 assets 读，不联网、不开文件访问。 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createTerminalView(context: Context, session: TerminalSession, font: Int, out: androidx.compose.runtime.MutableState<TerminalHandle?>,
                               onMods: (Boolean, Boolean) -> Unit): TerminalHandle {
    val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
    val view = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        settings.setSupportZoom(false)
        settings.textZoom = 100
        setBackgroundColor(android.graphics.Color.rgb(0x11, 0x13, 0x18))
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = true
        isFocusableInTouchMode = true
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { v.requestFocus(); post { evaluateJavascript("kvFocus()", null) } }
            false
        }
    }
    val bridge = TerminalBridge(context, view, session, font, onMods)
    view.addJavascriptInterface(bridge, "Bridge")
    view.loadUrl(TERMINAL_URL)
    return TerminalHandle(view, bridge).also { out.value = it }
}

/** JS 和 Kotlin 之间：输入按字节进伪终端，输出按块写回 xterm.js（只在主线程喂 WebView，输入在单独的线程上按顺序写）。 */
class TerminalBridge internal constructor(
    private val context: Context, private val view: WebView, private val session: TerminalSession, private val font: Int,
    /** 页面里用掉了 Ctrl / Alt（按了下一个字）：按键行上的灯跟着灭。 */
    private val onMods: (Boolean, Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writes = Executors.newSingleThreadExecutor { r -> Thread(r, "kavi-terminal-input").apply { isDaemon = true } }
    @Volatile private var ready = false
    @Volatile private var closed = false

    @JavascriptInterface
    fun ready(cols: Int, rows: Int) {
        main.post {
            if (closed || ready) return@post
            ready = true
            if (font != 13) view.evaluateJavascript("kvFont($font)", null)
            session.resize(cols, rows)
            val snap = session.snapshot()
            var off = 0
            while (off < snap.size) { val n = minOf(64 * 1024, snap.size - off); feed(snap.copyOfRange(off, off + n)); off += n }
            scope.launch { session.output.collect { feed(it) } }
            view.requestFocus()
            view.evaluateJavascript("kvFocus()", null)
        }
    }

    @JavascriptInterface
    fun write(base64: String) {
        if (closed) return
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
        runCatching { writes.execute { if (!closed) session.writeBytes(bytes) } }
    }

    @JavascriptInterface
    fun resize(cols: Int, rows: Int) { if (!closed) session.resize(cols, rows) }

    @JavascriptInterface
    fun mods(ctrl: Boolean, alt: Boolean) { main.post { if (!closed) onMods(ctrl, alt) } }

    @JavascriptInterface
    fun copy(text: String) {
        main.post {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("terminal", text))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    fun close() {
        closed = true
        scope.cancel()
        writes.shutdownNow()
    }

    private fun feed(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        view.evaluateJavascript("kvWrite('${Base64.encodeToString(bytes, Base64.NO_WRAP)}')", null)
    }
}
