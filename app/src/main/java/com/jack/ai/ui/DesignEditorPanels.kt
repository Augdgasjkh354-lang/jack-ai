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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.jack.ai.data.design.DesignSeries
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import com.jack.ai.data.Attachments
import com.jack.ai.data.design.DesignFonts
import com.jack.ai.data.design.DesignStyles
import com.jack.ai.data.design.DesignTemplates
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

// 设计编辑器底部的面板和弹层：文字样式、图片、排列、页面条、配色、换图、让 Kavi 改、点评（从 DesignEditor.kt 拆出来）。

/**
 * 文字样式面板：代替工具栏放在画布下面（不挡画布，边调边看）。
 * 字号、行距、字距加减；效果（描边、硬投影、软阴影、荧光底、反白块）和效果颜色；四种样式预设。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StylePanel(picked: Picked, vars: () -> WebView?, onCmd: (String, Array<Pair<String, Any>>) -> Unit, onClose: () -> Unit) {
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

/**
 * 图片面板：滤镜预设、调色（亮度、对比度、饱和度、色温）、蒙层（让压在照片上的字看得清）、形状、描边、阴影。
 * 色温和蒙层要真照片才有（占位色块只调滤镜、形状、描边）。
 */
@Composable
internal fun ImagePanel(picked: Picked, vars: () -> WebView?, onCmd: (String, Array<Pair<String, Any>>) -> Unit, onClose: () -> Unit) {
    val palette by produceState<Map<String, String>>(emptyMap()) {
        val raw = vars()?.let { w -> suspendCancellableCoroutine<String> { c -> w.evaluateJavascript("kvEd.vars()") { if (c.isActive) c.resume(it ?: "") } } }.orEmpty()
        value = runCatching {
            val s = (Json.parseToJsonElement(raw) as JsonPrimitive).content
            (Json.parseToJsonElement(s) as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        }.getOrDefault(emptyMap())
    }
    val img = picked.img
    fun cmd(op: String, vararg kv: Pair<String, Any>) = onCmd(op, arrayOf(*kv))
    fun set(vararg kv: Pair<String, Any>) = cmd("img", "set" to JsonObject(kv.associate { (k, v) ->
        k to when (v) { is Boolean -> JsonPrimitive(v); is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
    }))
    @Composable fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
        Surface(onClick = onClick, shape = Radii.s,
            color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
    @Composable fun Title(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    @Composable fun Chips(content: @Composable () -> Unit) =
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    @Composable fun Stepper(name: String, key: String) {
        val v = img[key]?.toFloatOrNull()?.toInt() ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelLarge)
            RepeatButton("－") { cmd("imgAdd", "key" to key, "delta" to -5) }
            Text(if (v > 0) "+$v" else "$v", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(36.dp), maxLines = 1)
            RepeatButton("＋") { cmd("imgAdd", "key" to key, "delta" to 5) }
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Title("滤镜")
        Chips {
            listOf("" to "原图", "film" to "胶片", "fresh" to "清新", "warm" to "暖阳", "cool" to "冷调", "vivid" to "鲜艳", "fade" to "褪色", "mono" to "黑白")
                .forEach { (id, name) -> Chip(name, img["f"].orEmpty() == id) { set("f" to id) } }
        }
        Title("调色")
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Stepper("亮度", "b"); Stepper("对比", "c"); Stepper("饱和", "s")
            if (picked.photo) Stepper("色温", "t")
        }
        if (picked.photo) {
            Title("蒙层（照片上压字时更清楚）")
            Chips {
                listOf("" to "无", "bottom" to "底部压暗", "top" to "顶部压暗", "dark" to "整体压暗", "light" to "整体提亮", "fade" to "渐隐到底色")
                    .forEach { (id, name) -> Chip(name, img["m"].orEmpty() == id) { set("m" to id) } }
            }
        }
        Title("形状")
        Chips {
            listOf("" to "直角", "16px" to "小圆角", "40px" to "大圆角", "50%" to "圆形").forEach { (id, name) -> Chip(name, img["r"].orEmpty() == id) { set("r" to id) } }
            Chip(if (img["sh"] == "true") "阴影 ✓" else "阴影", img["sh"] == "true") { set("sh" to (img["sh"] != "true")) }
        }
        Title("描边")
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ol = img["ol"]?.toFloatOrNull()?.toInt() ?: 0
            listOf(0 to "无", 6 to "细", 16 to "粗", 32 to "相框").forEach { (w, name) -> Chip(name, ol == w) { set("ol" to w) } }
            val cur = img["olc"].orEmpty().ifEmpty { "var(--bg)" }
            listOf("bg", "fg", "primary", "accent").forEach { k ->
                val css = "var(--$k)"
                Box(Modifier.size(30.dp).background(cssColor(palette[k].orEmpty()) ?: Color.Gray, CircleShape)
                    .border(if (cur == css) 3.dp else 1.dp, if (cur == css) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { if (ol == 0) set("olc" to css, "ol" to 6) else set("olc" to css) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { cmd("imgReset") }) { Text("全部还原") }
            TextButton(onClick = onClose) { Text("收起", fontWeight = FontWeight.Bold) }
        }
    }
}

/** 一组的缩略图条：当前页描边，没保存的改动打个点；长按出菜单（复制、前移、后移、删除）；最后是「＋」。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PageStrip(
    pages: List<PageDoc>, cur: Int, root: File,
    onPick: (Int) -> Unit, onDuplicate: (Int) -> Unit, onMove: (Int, Int) -> Unit, onDelete: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    LazyRow(
        Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(vertical = 8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(pages, key = { _, p -> p.key }) { i, p ->
            var menu by remember { mutableStateOf(false) }
            val png = p.origin?.let { File(it.parentFile, it.nameWithoutExtension + ".png") }
            val version = png?.let { DesignEditorActivity.version(it.path) } ?: 0
            val bmp by produceState<android.graphics.Bitmap?>(null, png?.path, version) {
                value = png?.takeIf { it.isFile }?.let { withContext(Dispatchers.IO) { Attachments.thumbnail(it.path, 200) } }
            }
            Box {
                Box(
                    Modifier.size(54.dp, 72.dp).clip(Radii.s).background(colors.surfaceVariant)
                        .border(if (i == cur) 3.dp else 1.dp, if (i == cur) colors.primary else colors.outlineVariant, Radii.s)
                        .combinedClickable(onClick = { onPick(i) }, onLongClick = { menu = true }),
                    contentAlignment = Alignment.Center,
                ) {
                    // 新加的页还没出过图：显示页码
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        ?: Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                    if (p.dirty) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp).background(colors.primary, CircleShape))
                    Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000), Radii.s).padding(horizontal = 4.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("复制这页") }, onClick = { menu = false; onDuplicate(i) })
                    if (i > 0) DropdownMenuItem(text = { Text("往前移") }, onClick = { menu = false; onMove(i, -1) })
                    if (i < pages.size - 1) DropdownMenuItem(text = { Text("往后移") }, onClick = { menu = false; onMove(i, 1) })
                    if (pages.size > 1) DropdownMenuItem(text = { Text("删除这页", color = colors.error) }, onClick = { menu = false; onDelete(i) })
                }
            }
        }
        item {
            Box(
                Modifier.size(54.dp, 72.dp).clip(Radii.s).border(1.dp, colors.outlineVariant, Radii.s).clickable { onDuplicate(cur) },
                contentAlignment = Alignment.Center,
            ) { Text("＋", style = MaterialTheme.typography.titleLarge, color = colors.primary) }
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
internal fun ArrangePanel(picked: Picked, onCmd: (String, Array<Pair<String, Any>>) -> Unit, onClose: () -> Unit) {
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
internal fun SheetTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))

@Composable
internal fun FontRow(name: String, use: String, current: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(name + if (current) "  ✓" else "", fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
        if (use.isNotEmpty()) Text(use, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** 颜色：先给这张图自己的六个配色（用变量，之后整张换配色还跟着变），再给黑白和几个常用色。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorSheet(webView: WebView?, onPick: (String) -> Unit) {
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
internal fun ImageSheet(root: File, onPick: (File) -> Unit) {
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

/** 让 Kavi 改：几个常用说法一点就发，或者自己写一句。 */
@Composable
internal fun KaviPanel(quick: List<String>, busy: Boolean, onAsk: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            quick.forEach { q ->
                androidx.compose.material3.AssistChip(onClick = { onAsk(q) }, enabled = !busy, label = { Text(q) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it }, Modifier.weight(1f), enabled = !busy, maxLines = 3,
                placeholder = { Text("比如：标题换成手写体、颜色暖一点") },
            )
            TextButton(onClick = { onAsk(text); text = "" }, enabled = !busy && text.isNotBlank()) { Text("发送", fontWeight = FontWeight.Bold) }
        }
        TextButton(onClick = onClose, enabled = !busy) { Text("收起") }
    }
}

/** Kavi 点评的结果：一条一行，编号和图上的小圆点对应；点「应用」让 Kavi 照着改那一块。 */
@Composable
internal fun ReviewPanel(advice: List<DesignKavi.Advice>, busy: Boolean, onApply: (DesignKavi.Advice) -> Unit, onApplyAll: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        advice.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text("${a.label}", color = Color(0xFFFF3B7F), fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.problem, style = MaterialTheme.typography.bodyMedium)
                    Text(a.fix, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onApply(a) }, enabled = !busy) { Text("应用") }
            }
        }
        Row {
            TextButton(onClick = onApplyAll, enabled = !busy && advice.isNotEmpty()) { Text("全部应用", fontWeight = FontWeight.Bold) }
            TextButton(onClick = onClose, enabled = !busy) { Text("收起") }
        }
    }
}
