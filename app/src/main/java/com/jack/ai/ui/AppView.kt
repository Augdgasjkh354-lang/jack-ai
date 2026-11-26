package com.jack.ai.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jack.ai.data.GeneratedFile
import com.jack.ai.data.SafeFile
import com.jack.ai.data.Verify
import java.io.ByteArrayInputStream
import java.io.File
import com.jack.ai.data.backgroundEndpoint
import com.jack.ai.data.backgroundModelKey
import com.jack.ai.data.endpoint
import com.jack.ai.data.modelChoices
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/** 手指正按在小工具的网页上：这时横着划是在拖滑块，不是要开侧边栏。 */
object AppTouch {
    @Volatile var active = false
}

/** 小工具存的数据放在对话文件夹的 .appdata 里，跟着文件夹一起备份。 */
private fun dataFile(app: File) = File(app.parentFile, ".appdata/${app.name}.json")

/**
 * 网页里能调用的 jack.save / jack.load，以及上报高度和报错。
 * 这些方法在 WebView 的后台线程上被调用，改界面状态要切回主线程。
 */
private class AppBridge(
    private val app: File,
    private val onHeight: (Int) -> Unit,
    private val onError: (String) -> Unit,
    private val ctx: android.content.Context? = null,
    /** 把一段 JS 放回网页里跑（回 jack.ask 的结果）。 */
    private val eval: (String) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    /**
     * jack.ask 的上下文：按 seat 分开（牌桌上每个对手一份，互不相通），只往后加（缓存接得上）；
     * 这个 seat 的 system 或模型换了就重来。
     */
    private val minds = HashMap<String, Pair<String, com.jack.ai.data.games.GameMind>>()
    private val inFlight = java.util.concurrent.atomic.AtomicInteger()

    /**
     * 网页里的 jack.ask(话, {system, keep}) → 后台模型回一段话。小游戏里 Kavi 当对手、出题、猜词用它。
     * 不开思考、输出限短；同时最多两个在问，再多的直接回「太快了」。用量记在「游戏 · 小游戏」。
     */
    @JavascriptInterface
    fun ask(id: String, prompt: String, opts: String) {
        val c = ctx ?: return done(id, false, "这里不能问 Kavi")
        if (inFlight.incrementAndGet() > 2) { inFlight.decrementAndGet(); return done(id, false, "问得太快了，等上一个回完") }
        scope.launch {
            try {
                val o = runCatching { org.json.JSONObject(opts) }.getOrNull() ?: org.json.JSONObject()
                val system = o.optString("system").take(4000).ifBlank { "你在一个手机小游戏里陪用户玩。回答简短，照游戏要的格式回。" }
                val keep = o.optBoolean("keep", true)
                val max = o.optInt("max", 300).coerceIn(16, 800)
                val settings = com.jack.ai.data.Store(c).loadSettings()
                // 指定了模型（设置里能用的那些）就用它，不带主模型的备用；没指定用后台模型
                val model = o.optString("model")
                val ep = (if (model.isNotBlank() && settings.modelChoices().any { it.key == model })
                    settings.endpoint(model)?.takeIf { it.apiKey.isNotBlank() }?.copy(fallbacks = emptyList()) else null)
                    ?: settings.backgroundEndpoint()
                    ?: return@launch done(id, false, "还没设置能用的模型")
                val label = "游戏 · " + app.nameWithoutExtension
                val seat = o.optString("seat")
                val m = if (!keep) null else synchronized(minds) {
                    val sig = system + "\u0000" + ep.key
                    minds[seat]?.takeIf { it.first == sig }?.second
                        ?: com.jack.ai.data.games.GameMind(c, ep, system, label).also { minds[seat] = sig to it }
                }
                val text = if (m != null) m.ask(prompt.take(6000), maxTokens = max, json = o.optBoolean("json", false))
                else com.jack.ai.api.DeepSeekClient(com.jack.ai.data.Store(c).usage(label)).complete(ep,
                    listOf(kotlinx.serialization.json.buildJsonObject { put("role", "system"); put("content", system) },
                        kotlinx.serialization.json.buildJsonObject { put("role", "user"); put("content", prompt.take(6000)) }),
                    maxTokens = max, jsonMode = o.optBoolean("json", false))
                done(id, true, text.trim())
            } catch (e: Exception) {
                done(id, false, e.message ?: "出错了")
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /** 网页里的 jack.models()：设置里能用的模型（给游戏挑对手用的模型），def 是后台模型。 */
    @JavascriptInterface
    fun models(): String {
        val c = ctx ?: return "{\"list\":[],\"def\":\"\"}"
        val s = com.jack.ai.data.Store(c).loadSettings()
        val list = org.json.JSONArray()
        s.modelChoices().forEach { m -> list.put(org.json.JSONObject().put("key", m.key).put("label", m.label).put("provider", m.group)) }
        return org.json.JSONObject().put("list", list).put("def", s.backgroundModelKey().orEmpty()).toString()
    }

    private fun done(id: String, ok: Boolean, text: String) {
        val js = "window.jack&&jack._done(${org.json.JSONObject.quote(id)},$ok,${org.json.JSONObject.quote(text)})"
        main.post { eval(js) }
    }

    @JavascriptInterface
    fun save(json: String) {
        if (json.length > 1_000_000) return
        runCatching { SafeFile.writeText(dataFile(app), json) }
    }

    @JavascriptInterface
    fun load(): String = runCatching { SafeFile.readText(dataFile(app)) }.getOrNull().orEmpty()

    @JavascriptInterface
    fun height(px: Int) { main.post { onHeight(px) } }

    @JavascriptInterface
    fun error(message: String) { main.post { onError(message.take(300)) } }
}

/** 放在网页最前面：提供 jack.save / jack.load，报错和内容高度报给 App。 */
private const val SHIM = """<script>
window.jack={save:function(o){try{JackBridge.save(JSON.stringify(o))}catch(e){}},load:function(){try{var s=JackBridge.load();return s?JSON.parse(s):null}catch(e){return null}},
_n:0,_cb:{},ask:function(p,o){return new Promise(function(res,rej){var id='q'+(++jack._n);jack._cb[id]=[res,rej];try{JackBridge.ask(id,String(p),JSON.stringify(o||{}))}catch(e){delete jack._cb[id];rej(e)}})},
models:function(){try{return JSON.parse(JackBridge.models())}catch(e){return {list:[],def:''}}},
_done:function(id,ok,t){var c=jack._cb[id];if(!c)return;delete jack._cb[id];if(ok)c[0](t);else c[1](new Error(t))}};
window.addEventListener('error',function(e){try{JackBridge.error((e.message||'出错了')+(e.lineno?'（第 '+e.lineno+' 行）':''))}catch(_){}});
(function(){var last=0;function h(){try{var v=Math.ceil(document.documentElement.scrollHeight);if(v!==last){last=v;JackBridge.height(v)}}catch(e){}}
window.addEventListener('load',h);setInterval(h,700);})();
</script>"""

/** 把 SHIM 塞进 <head> 开头；没有 head 就放在最前面。没写 viewport 的补一个，不然手机上会按电脑宽度缩小显示。 */
internal fun withShim(html: String): String {
    val viewport = if (Regex("<meta[^>]+viewport", RegexOption.IGNORE_CASE).containsMatchIn(html)) ""
        else "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
    val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
    return if (m != null) html.substring(0, m.range.last + 1) + viewport + SHIM + html.substring(m.range.last + 1)
    else viewport + SHIM + html
}

/**
 * 显示一个小工具。inline 时高度跟着网页内容走（最高 maxHeightDp，超出就在里面滚动）；全屏时铺满。
 * 联网只放行几个前端库 CDN；点外部链接用浏览器打开；不能读手机文件。
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun AppWebView(file: File, modifier: Modifier = Modifier, inline: Boolean = true, maxHeightDp: Int = 560, onError: (String) -> Unit = {}) {
    var heightPx by remember(file.path) { mutableIntStateOf(0) }
    val base = "https://app.jack.local/"
    // 网页报的是 CSS 像素，加了 viewport 之后它就等于 dp
    val sized = if (inline) modifier.fillMaxWidth().height(heightPx.toFloat().coerceIn(160f, maxHeightDp.toFloat()).dp)
    else modifier.fillMaxSize()
    AndroidView(
        modifier = sized,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setGeolocationEnabled(false)
                settings.mediaPlaybackRequiresUserGesture = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val web = this
                addJavascriptInterface(AppBridge(file, { heightPx = it }, onError, ctx.applicationContext) { js -> web.evaluateJavascript(js, null) }, "JackBridge")
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) onError("${m.message()}（第 ${m.lineNumber()} 行）".take(300))
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    // 网页进程没了（崩溃或内存紧被收回，和 Kavi 浏览器共用一个进程）：丢掉这个网页窗口，不接住的话整个 App 会被系统关掉
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean = view.dropAfterRendererGone()
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url
                        if (url.scheme == "data" || url.scheme == "blob") return null
                        if (url.scheme == "https" && url.host in Verify.APP_HOSTS) return null
                        // 其余联网一律拦下：小工具不该偷偷往外发数据
                        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.toString().startsWith(base) && url.fragment != null) return false
                        if (url.scheme == "http" || url.scheme == "https") Links.open(ctx, url.toString())
                        return true
                    }
                }
                setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> AppTouch.active = true
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> AppTouch.active = false
                    }
                    false
                }
                val html = runCatching { file.readText() }.getOrDefault("<p>小工具文件已被删除</p>")
                loadDataWithBaseURL(base, withShim(html), "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

/** 聊天里的小工具卡片：标题、全屏、分享，下面就是能直接操作的网页；出错时显示错误和「让 Kavi 修」。 */
@Composable
fun AppCard(f: GeneratedFile, onFix: ((String) -> Unit)?) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val file = File(f.path)
    var full by remember { mutableStateOf(false) }
    var error by remember(f.path) { mutableStateOf<String?>(null) }
    // 改过文件或者全屏里用过之后重新加载，显示最新的内容和数据
    var reload by remember { mutableIntStateOf(0) }
    if (!file.exists()) {
        Text("小工具「${f.name}」已被删除", color = colors.outline)
        return
    }
    Surface(
        shape = Radii.m,
        color = colors.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp)) {
                Text(f.name.removeSuffix(".html"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                TextButton(onClick = { full = true }) { Text("全屏") }
                TextButton(onClick = { shareFile(ctx, f) }) { Text("分享") }
            }
            key(f.path, file.lastModified(), reload) {
                AppWebView(file, Modifier.padding(horizontal = 4.dp), onError = { error = it })
            }
            error?.let { e ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp)) {
                    Text("出错：$e", Modifier.weight(1f), color = colors.error, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (onFix != null) TextButton(onClick = {
                        onFix("小工具「${f.name}」运行时出错：$e\n请用 edit_file 修好它的底稿。")
                        error = null
                    }) { Text("让 Kavi 修") }
                }
            }
            Spacer(Modifier.size(6.dp))
        }
    }
    if (full) AppViewer(file) { full = false; reload++ }
}

/** 全屏用小工具。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppViewer(file: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(file.name.removeSuffix(".html"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") } },
                    actions = {
                        // 放到桌面：以后点桌面图标直接打开这个小工具
                        TextButton(onClick = {
                            if (!MiniAppActivity.pin(ctx, file)) android.widget.Toast.makeText(ctx, "这个桌面不支持放快捷方式", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("放到桌面") }
                        TextButton(onClick = {
                            shareFile(ctx, GeneratedFile(file.name, file.absolutePath, "text/html", file.length()))
                        }) { Text("分享") }
                    },
                )
            },
        ) { padding ->
            AppWebView(file, Modifier.padding(padding), inline = false)
        }
    }
}
