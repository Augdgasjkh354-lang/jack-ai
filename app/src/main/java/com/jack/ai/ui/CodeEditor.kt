package com.jack.ai.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebViewAssetLoader
import com.jack.ai.data.CodeHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 能在编辑器里改的文件：文字类、不太大。 */
object Editable {
    const val MAX_BYTES = 2L * 1024 * 1024
    fun ok(f: File) = f.isFile && f.length() <= MAX_BYTES && com.jack.ai.data.TextPreview.isText(f.name)
}

/**
 * 代码编辑器（全屏）：CodeMirror 6 在 WebView 里（assets/editor），语法高亮、行号、查找替换、撤销重做、括号补全。
 * 保存前先把原来的存一版（CodeHistory，和 Kavi 改代码一样），菜单里能和「Kavi 上次改之前」比，一段段保留或退回。
 */
@Composable
fun CodeEditor(file: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val text by produceState<Result<String>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (file.length() > Editable.MAX_BYTES) error("文件太大（超过 2 MB），用终端里的 vim 改吧")
                file.readText()
            }
        }
    }
    var dirty by remember { mutableStateOf(false) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    var font by remember { mutableStateOf(13) }
    var diffing by remember { mutableStateOf<String?>(null) }   // 正在和哪一版比
    var leaving by remember { mutableStateOf(false) }
    var closeAfterSave by remember { mutableStateOf(false) }
    val versions = remember(file, dirty) { CodeHistory.versions(file) }
    val web = remember { mutableStateOf<WebView?>(null) }
    fun js(code: String) = web.value?.evaluateJavascript(code, null)
    fun back() { if (dirty) leaving = true else onDismiss() }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = ::back)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = ::back)
                    Column(Modifier.weight(1f)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull("$lines 行".takeIf { lines > 0 }, "没保存".takeIf { dirty }, diffing?.let { "对比：$it" }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { js("kvUndo()") }, enabled = canUndo, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text("↶", style = MaterialTheme.typography.titleLarge) }
                    TextButton(onClick = { js("kvRedo()") }, enabled = canRedo, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text("↷", style = MaterialTheme.typography.titleLarge) }
                    CircleIconButton(Icons.Default.Search, "查找替换", size = 38.dp, iconSize = 18.dp) { js("kvSearch()") }
                    TextButton(onClick = { js("kvSave()") }, enabled = dirty) { Text("保存") }
                    Box {
                        CircleIconButton(Icons.Default.MoreVert, "更多", size = 38.dp, iconSize = 18.dp) { menu = true }
                        RoundMenu(expanded = menu, onDismiss = { menu = false }) {
                            MenuRow("自动换行", glyph = "↩", checked = wrap) { wrap = !wrap; js("kvWrap($wrap)") }
                            MenuRow("字大一点", glyph = "A+") { font = (font + 1).coerceAtMost(22); js("kvFont($font)") }
                            MenuRow("字小一点", glyph = "A−") { font = (font - 1).coerceAtLeast(9); js("kvFont($font)") }
                            if (diffing != null) MenuRow("不看对比了", glyph = "±") { menu = false; diffing = null; js("kvDiff(null)") }
                            // 旧版本：Kavi 每次改之前、你每次保存之前存的
                            versions.take(5).forEach { v ->
                                MenuRow("和第 ${v.n} 版比", glyph = "±", sub = com.jack.ai.data.Timeline.ago(v.at, System.currentTimeMillis()) + " · " + whyLabel(v.why)) {
                                    menu = false
                                    val old = runCatching { v.file.readText() }.getOrNull() ?: return@MenuRow
                                    diffing = "第 ${v.n} 版"
                                    js("kvDiff('${b64(old)}')")
                                }
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val t = text) {
                        null -> {}
                        else -> t.fold(
                            onSuccess = { content ->
                                AndroidView(factory = { c ->
                                    createEditor(c, file.name, content, dark,
                                        onState = { d, u, r, n -> dirty = d; canUndo = u; canRedo = r; lines = n },
                                        onSave = { saved ->
                                            val ok = runCatching { CodeHistory.save(file, "user"); file.writeText(saved) }.isSuccess
                                            web.value?.evaluateJavascript(if (ok) "kvSaved()" else "", null)
                                            Toast.makeText(c, if (ok) "已保存" else "保存失败", Toast.LENGTH_SHORT).show()
                                            if (ok && closeAfterSave) onDismiss()
                                        }).also { web.value = it }
                                }, modifier = Modifier.fillMaxSize())
                            },
                            onFailure = { e ->
                                Text(e.message ?: "打不开", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                            },
                        )
                    }
                }
            }
        }
        DisposableEffect(Unit) { onDispose { web.value?.destroy(); web.value = null } }
    }
    if (leaving) AlertDialog(
        onDismissRequest = { leaving = false },
        title = { Text("改的还没保存") },
        confirmButton = { TextButton(onClick = { leaving = false; closeAfterSave = true; js("kvSave()") }) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = { leaving = false; onDismiss() }) { Text("不保存") }
                TextButton(onClick = { leaving = false }) { Text("接着改") }
            }
        },
    )
}

private fun whyLabel(why: String) = when (why) {
    "write_file", "edit_file" -> "Kavi 改之前"
    "user" -> "你保存之前"
    "restore" -> "退回之前"
    else -> why
}

private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

private const val EDITOR_URL = "https://appassets.androidplatform.net/assets/editor/index.html"

@SuppressLint("SetJavaScriptEnabled")
private fun createEditor(
    context: Context, name: String, content: String, dark: Boolean,
    onState: (Boolean, Boolean, Boolean, Int) -> Unit, onSave: (String) -> Unit,
): WebView {
    val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
    val main = Handler(Looper.getMainLooper())
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(false)
        settings.textZoom = 100
        setBackgroundColor(if (dark) android.graphics.Color.rgb(0x28, 0x2C, 0x34) else android.graphics.Color.WHITE)
        overScrollMode = View.OVER_SCROLL_NEVER
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        val view = this
        addJavascriptInterface(object {
            @JavascriptInterface fun ready() = main.post {
                view.evaluateJavascript("kvOpen('${b64(content)}', ${org.json.JSONObject.quote(name)}, $dark, true, false)", null)
            }.let { }
            @JavascriptInterface fun state(dirty: Boolean, undo: Boolean, redo: Boolean, lines: Int) = main.post { onState(dirty, undo, redo, lines) }.let { }
            @JavascriptInterface fun save(b: String) {
                val text = runCatching { String(Base64.decode(b, Base64.DEFAULT)) }.getOrNull() ?: return
                main.post { onSave(text) }
            }
        }, "Bridge")
        loadUrl(EDITOR_URL)
    }
}
