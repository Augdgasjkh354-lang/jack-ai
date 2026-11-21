package com.jack.ai.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jack.ai.data.design.DesignElements
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/** 网格里的一格：id、名字、SVG、预览框宽高比（宽/高）。 */
private class Cell(val id: String, val name: String, val svg: String, val ratio: Float)

/**
 * 编辑器的「加元素」面板：形状、贴纸、图标、纹理四个页签。点一下就加到画面中间（[onAdd] 收到给 kvEd 的命令）。
 * 预览用这张图自己的配色画（小 WebView 里），看到的就是加进去的样子。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ElementsPanel(editor: WebView?, onAdd: (JsonObject) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    // 这张图的六个配色变量（预览用）
    val vars by produceState<Map<String, String>>(emptyMap(), editor) {
        val raw = editor?.let { w -> suspendCancellableCoroutine<String> { c -> w.evaluateJavascript("kvEd.vars()") { if (c.isActive) c.resume(it ?: "") } } }.orEmpty()
        value = runCatching {
            val s = (Json.parseToJsonElement(raw) as JsonPrimitive).content
            (Json.parseToJsonElement(s) as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        }.getOrDefault(emptyMap())
    }
    val lib = remember { DesignElements.library(ctx) }
    val tabs = listOf("形状", "贴纸", "图标", "纹理")
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i; query = "" }, text = { Text(t) }) }
            }
            TextButton(onClick = onClose) { Text("收起") }
        }
        if (tab == 1 || tab == 2) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            singleLine = true, placeholder = { Text(if (tab == 1) "搜贴纸：箭头、胶带、生日……" else "搜图标：电话、咖啡、礼物……") })
        if (tab == 1 && query.isBlank()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(category == null, { category = null }, label = { Text("全部") })
            lib.categories.forEach { c -> FilterChip(category == c.id, { category = if (category == c.id) null else c.id }, label = { Text(c.name) }) }
        }
        when (tab) {
            0 -> Grid(vars, remember { DesignElements.SHAPES.map { Cell(it.id, it.name, it.svg, it.w.toFloat() / it.h) } }) { id ->
                val s = DesignElements.SHAPES.first { it.id == id }
                onAdd(piece("shape", s.svg, s.w, s.h, if (s.line) "line" else "fill", emptyMap()))
            }
            1 -> {
                val list = remember(query, category) { DesignElements.search(lib, query, if (query.isBlank()) category else null) }
                val cells by produceState(emptyList<Cell>(), list) {
                    value = withContext(Dispatchers.IO) {
                        list.mapNotNull { s -> DesignElements.stickerSvg(ctx, s.id)?.let { svg -> val (w, h) = s.size(); Cell(s.id, s.name, svg, w.toFloat() / h) } }
                    }
                }
                if (list.isEmpty()) Text("没找到，换个词试试", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Grid(vars, cells) { id ->
                    val s = list.first { it.id == id }
                    val svg = cells.first { it.id == id }.svg
                    val (w, h) = s.size()
                    onAdd(piece("sticker", svg, w, h, s.recolor, mapOf("data-sticker" to s.id)))
                }
            }
            2 -> {
                val cells by produceState(emptyList<Cell>(), query) {
                    value = withContext(Dispatchers.Default) {
                        DesignElements.searchIcons(com.jack.ai.data.office.PptDeck.ICONS.keys, query).mapNotNull { n -> DesignElements.iconSvg(n)?.let { Cell(n, n, it, 1f) } }
                    }
                }
                Grid(vars, cells) { name ->
                    val svg = cells.first { it.id == name }.svg
                    onAdd(piece("icon", svg, 160, 160, "stroke", mapOf("data-icon" to name)))
                }
            }
            else -> Textures(vars, onPick = { id, level ->
                onAdd(buildJsonObject {
                    put("op", "texture"); put("id", id); put("level", level)
                    if (id.isNotEmpty()) put("css", DesignElements.textureCss(id, level))
                })
            })
        }
    }
}

private fun piece(el: String, svg: String, w: Int, h: Int, recolor: String, attrs: Map<String, String>) = buildJsonObject {
    put("op", "addPiece"); put("el", el); put("html", svg); put("w", w); put("h", h); put("recolor", recolor)
    put("attrs", JsonObject(attrs.mapValues { JsonPrimitive(it.value) }))
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

/** 配色变量写成 :root 的 CSS。 */
private fun rootVars(vars: Map<String, String>) = vars.entries.joinToString(";") { "--${it.key}:${it.value}" }

/** 一格格的预览（在小 WebView 里画 SVG）；点一格回调它的 id。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Grid(vars: Map<String, String>, cells: List<Cell>, onPick: (String) -> Unit) {
    val label = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val tile = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
    val pick by rememberUpdatedState(onPick)
    fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)
    val html = remember(vars, cells, label, tile) {
        buildString {
            append("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><style>")
            append(":root{").append(rootVars(vars)).append("}")
            append("body{margin:0;font:12px sans-serif;color:${hex(label)};background:transparent}")
            append(".g{display:grid;grid-template-columns:repeat(auto-fill,minmax(84px,1fr));gap:10px 6px;padding:8px 10px 16px}")
            append(".c{display:flex;flex-direction:column;align-items:center;gap:4px;-webkit-tap-highlight-color:transparent}")
            append(".t{width:76px;height:64px;border-radius:12px;background:var(--bg,${hex(tile)});display:flex;align-items:center;justify-content:center;color:var(--fg)}")
            append(".c:active .t{outline:2px solid var(--primary)}")
            append(".n{max-width:84px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
            append("</style><div class=g>")
            cells.forEach { c ->
                // 宽的贴纸在框里横着放，高的竖着放
                val (w, h) = if (c.ratio >= 76f / 64f) 64 to (64 / c.ratio).toInt().coerceAtLeast(8) else (52 * c.ratio).toInt().coerceAtLeast(8) to 52
                append("<div class=c onclick=\"K.pick('").append(esc(c.id)).append("')\"><div class=t><div style='width:${w}px;height:${h}px'>")
                append(c.svg).append("</div></div><div class=n>").append(esc(c.name)).append("</div></div>")
            }
            append("</div>")
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(300.dp),
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(AColor.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: android.webkit.WebResourceRequest) = true
                }
                addJavascriptInterface(object { @JavascriptInterface fun pick(id: String) { post { pick(id) } } }, "K")
            }
        },
        update = { w -> if (w.tag != html) { w.tag = html; w.loadDataWithBaseURL("https://kavi.local/", html, "text/html", "utf-8", null) } },
        onRelease = { it.destroy() },
    )
}

/** 纹理：五种，每种三档强度；再点一次同一档拿掉。预览也用这张图的配色。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Textures(vars: Map<String, String>, onPick: (String, Int) -> Unit) {
    val label = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val pick by rememberUpdatedState(onPick)
    val html = remember(vars, label) {
        buildString {
            append("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><style>")
            append(":root{").append(rootVars(vars)).append("}")
            append("body{margin:0;font:12px sans-serif;color:").append(String.format("#%06X", 0xFFFFFF and label)).append(";background:transparent}")
            append(".r{display:flex;align-items:center;gap:8px;padding:6px 12px}.r b{width:40px;font-weight:500}")
            append(".t{flex:1;height:44px;border-radius:10px;position:relative;overflow:hidden;background:var(--bg,#fff);display:flex;align-items:flex-end;justify-content:flex-end;padding:2px 6px;box-sizing:border-box}")
            append(".t i{position:absolute;inset:0}.t span{position:relative;font-style:normal}")
            append("</style>")
            DesignElements.TEXTURES.forEach { t ->
                append("<div class=r><b>").append(t.name).append("</b>")
                listOf(1 to "淡", 2 to "中", 3 to "浓").forEach { (lv, name) ->
                    append("<div class=t onclick=\"K.pick('${t.id}',$lv)\"><i style=\"").append(DesignElements.textureCss(t.id, lv).replace("\"", "&quot;")).append("\"></i><span>").append(name).append("</span></div>")
                }
                append("</div>")
            }
            append("<div class=r style='justify-content:center;padding:14px' onclick=\"K.pick('',0)\"><u>去掉纹理</u></div>")
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(300.dp),
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(AColor.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: android.webkit.WebResourceRequest) = true
                }
                addJavascriptInterface(object { @JavascriptInterface fun pick(id: String, level: Int) { post { pick(id, level) } } }, "K")
            }
        },
        update = { w -> if (w.tag != html) { w.tag = html; w.loadDataWithBaseURL("https://kavi.local/", html, "text/html", "utf-8", null) } },
        onRelease = { it.destroy() },
    )
}
