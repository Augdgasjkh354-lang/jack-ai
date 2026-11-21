package com.jack.ai.ui.photo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.ai.data.Store
import com.jack.ai.ui.JackTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 修图（像手机相册自带的那种）：裁剪旋转、滤镜、调节、边框、画笔、文字、马赛克、贴纸、虚化、抠图。
 * 每个工具点「✓」才落到图上（进撤销历史），点「✕」放弃这次。处理都在 PhotoOps 里，预览用小图、保存用大图。
 * 从聊天里的大图点「修图」打开：存成新图（不覆盖原图），同时存进相册。从设计编辑器打开：存好后把新图交回去。
 */
class PhotoEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val forResult = intent.getBooleanExtra(EXTRA_RESULT, false)
        val settings = Store(this).loadSettings()
        setContent {
            JackTheme(settings.themeMode, settings.accent, settings.fontScale, settings.appIcon) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val file = path?.let { File(it) }?.takeIf { it.isFile }
                    if (file == null) Text("找不到这张图（可能被删了）", Modifier.padding(24.dp))
                    else PhotoEditorScreen(file, forResult, onSaved = { out ->
                        if (forResult) setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PATH, out.path))
                        finish()
                    }, onClose = { finish() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val EXTRA_RESULT = "result"

        fun open(ctx: Context, file: File) = ctx.startActivity(Intent(ctx, PhotoEditorActivity::class.java).putExtra(EXTRA_PATH, file.path))

        /** 修好的图交回来（设计编辑器换图用）：输入原图，输出新图；放弃了是 null。 */
        val Edit = object : ActivityResultContract<File, File?>() {
            override fun createIntent(context: Context, input: File) =
                Intent(context, PhotoEditorActivity::class.java).putExtra(EXTRA_PATH, input.path).putExtra(EXTRA_RESULT, true)
            override fun parseResult(resultCode: Int, intent: Intent?) =
                intent?.getStringExtra(EXTRA_PATH)?.takeIf { resultCode == Activity.RESULT_OK }?.let { File(it) }
        }
    }
}

private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(pathData = addPathNodes(path), fill = androidx.compose.ui.graphics.SolidColor(Color.Black)).build()

/** 工具：id、名字、图标（Material Icons 的路径，Apache-2.0）。 */
private data class Tool(val id: String, val name: String, val icon: ImageVector)

private val TOOLS = listOf(
    Tool("crop", "裁剪", icon("Crop", "M17,15h2V7c0,-1.1 -0.9,-2 -2,-2H9v2h8v8zM7,17V1H5v4H1v2h4v10c0,1.1 0.9,2 2,2h10v4h2v-4h4v-2H7z")),
    Tool("filter", "滤镜", icon("Filter", "M15.96,10.29l-2.75,3.54 -1.96,-2.36L8.5,15h11l-3.54,-4.71zM3,5H1v16c0,1.1 0.9,2 2,2h16v-2H3V5zM21,1H7c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-2 -2,-2zM21,17H7V3h14v14z")),
    Tool("adjust", "调节", icon("Tune", "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z")),
    Tool("frame", "边框", icon("Frame", "M18,4H6C4.9,4 4,4.9 4,6v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM18,18H6V6h12v12z")),
    Tool("doodle", "画笔", icon("Brush", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")),
    Tool("text", "文字", icon("Title", "M5,4v3h5.5v12h3V7H19V4z")),
    Tool("mosaic", "马赛克", icon("Grid", "M20,2H4c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM8,20H4v-4h4v4zM8,14H4v-4h4v4zM8,8H4V4h4v4zM14,20h-4v-4h4v4zM14,14h-4v-4h4v4zM14,8h-4V4h4v4zM20,20h-4v-4h4v4zM20,14h-4v-4h4v4zM20,8h-4V4h4v4z")),
    Tool("sticker", "贴纸", icon("Emoji", "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM15.5,11c0.83,0 1.5,-0.67 1.5,-1.5S16.33,8 15.5,8 14,8.67 14,9.5s0.67,1.5 1.5,1.5zM8.5,11c0.83,0 1.5,-0.67 1.5,-1.5S9.33,8 8.5,8 7,8.67 7,9.5 7.67,11 8.5,11zM12,17.5c2.33,0 4.31,-1.46 5.11,-3.5L6.89,14c0.8,2.04 2.78,3.5 5.11,3.5z")),
    Tool("blur", "虚化", icon("Lens", "M12,2C6.47,2 2,6.47 2,12s4.47,10 10,10 10,-4.47 10,-10S17.53,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8zM12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4z")),
    Tool("cutout", "抠图", icon("Cut", "M9.64,7.64c0.23,-0.5 0.36,-1.05 0.36,-1.64 0,-2.21 -1.79,-4 -4,-4S2,3.79 2,6s1.79,4 4,4c0.59,0 1.14,-0.13 1.64,-0.36L10,12l-2.36,2.36C7.14,14.13 6.59,14 6,14c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4c0,-0.59 -0.13,-1.14 -0.36,-1.64L12,14l7,7h3v-1L9.64,7.64zM6,8c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM6,20c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM19,3l-6,6 2,2 7,-7V3z")),
)

private val PALETTE = listOf(0xFFFFFFFF, 0xFF111111, 0xFFE23B2E, 0xFFFF7A1A, 0xFFFFC300, 0xFF34C759, 0xFF14B8A6, 0xFF2F7CF6, 0xFF8B5CF6, 0xFFFF5C9A, 0xFF8B5E3C, 0xFF9CA3AF).map { it.toInt() }
private val EMOJI = "😀😄😆😂🤣😊😍🥰😘😎🤩🥳😋🤔😴😭😤😡👍👏🙌🙏💪✌️👌🤞❤️🧡💛💚💙💜🖤💯✨🔥⭐🌟💫🎉🎊🎁🎈🌸🌹🌷🌻🍀🌈☀️🌙☁️❄️🍰🍓🍑🍉☕🧋📍📌✅❌⚠️💡📷🎵👀💰🛒🏠🚗✈️".let { s ->
    val out = mutableListOf<String>(); val it = java.text.BreakIterator.getCharacterInstance(); it.setText(s)
    var start = it.first(); var end = it.next()
    while (end != java.text.BreakIterator.DONE) { out += s.substring(start, end); start = end; end = it.next() }
    out
}

@Composable
internal fun PhotoEditorScreen(file: File, forResult: Boolean, onSaved: (File) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf<Bitmap?>(null) }
    val history = remember { mutableStateListOf<Bitmap>() }
    val future = remember { mutableStateListOf<Bitmap>() }
    var tool by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    LaunchedEffect(file) { base = withContext(Dispatchers.IO) { PhotoOps.load(file) } }
    val small by produceState<Bitmap?>(null, base) { value = base?.let { b -> withContext(Dispatchers.Default) { PhotoOps.scaleDown(b, 1280) } } }

    /** 工具点了 ✓：在大图上做一遍，进撤销历史。 */
    fun commit(op: (Bitmap) -> Bitmap) {
        val b = base ?: return
        scope.launch {
            busy = true
            val out = runCatching { withContext(Dispatchers.Default) { op(b) } }.getOrNull()
            busy = false
            if (out == null) { Toast.makeText(ctx, "没做成，图可能太大", Toast.LENGTH_SHORT).show(); return@launch }
            history += b; if (history.size > 8) history.removeAt(0)
            future.clear()
            base = out; tool = null
        }
    }
    fun save() {
        val b = base ?: return
        scope.launch {
            saving = true
            val out = runCatching {
                withContext(Dispatchers.IO) {
                    val png = b.hasAlpha() && hasTransparency(b)
                    val dir = file.parentFile?.takeIf { it.canWrite() } ?: ctx.cacheDir
                    val f = com.jack.ai.data.Workspace.uniqueFile(dir, file.nameWithoutExtension.removeSuffix("-修图") + "-修图." + if (png) "png" else "jpg")
                    f.outputStream().use { b.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
                    if (!forResult) com.jack.ai.data.design.DesignSeries.exportToGallery(ctx, listOf(f), f.nameWithoutExtension)
                    f
                }
            }
            saving = false
            out.onSuccess { f ->
                if (!forResult) Toast.makeText(ctx, "已存进相册（Pictures/Kavi），对话文件夹里也有一份「${f.name}」", Toast.LENGTH_LONG).show()
                onSaved(f)
            }.onFailure { e -> Toast.makeText(ctx, "没存成：${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
    BackHandler { if (tool != null) tool = null else if (history.isNotEmpty()) askLeave = true else onClose() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // 顶栏：没在用工具时是 ✕、撤销重做、保存；用工具时让给工具自己的 ✕ / ✓
        if (tool == null) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (history.isNotEmpty()) askLeave = true else onClose() }) { Icon(Icons.Default.Close, "关闭") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { base?.let { future += it }; base = history.removeAt(history.lastIndex) }, enabled = history.isNotEmpty() && !busy) { Text("撤销") }
            TextButton(onClick = { base?.let { history += it }; base = future.removeAt(future.lastIndex) }, enabled = future.isNotEmpty() && !busy) { Text("重做") }
            Surface(onClick = { save() }, enabled = !saving && base != null, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp, end = 8.dp)) {
                Text(if (saving) "保存中…" else if (forResult) "完成" else "保存", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            }
        } else Spacer(Modifier.statusBarsPadding())

        val b = base
        val sm = small
        if (b == null || sm == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        when (tool) {
            "crop" -> CropTool(sm, busy, onCancel = { tool = null }) { q, fh, fv, a, r -> commit { PhotoOps.crop(PhotoOps.orient(it, q, fh, fv, a), r) } }
            "filter" -> FilterTool(sm, busy, onCancel = { tool = null }) { m -> commit { PhotoOps.applyMatrix(it, m) } }
            "adjust" -> AdjustTool(sm, busy, onCancel = { tool = null }) { v -> commit { PhotoOps.adjust(it, v) } }
            "frame" -> FrameTool(sm, busy, onCancel = { tool = null }) { st, c -> commit { PhotoOps.frame(it, st, c) } }
            "doodle" -> DoodleTool(sm, busy, onCancel = { tool = null }) { strokes ->
                commit { src -> src.copy(Bitmap.Config.ARGB_8888, true).also { out -> PhotoOps.drawStrokes(android.graphics.Canvas(out), strokes, out.width.toFloat(), out.height.toFloat()) } }
            }
            "mosaic" -> MosaicTool(sm, busy, onCancel = { tool = null }) { strokes -> commit { PhotoOps.mosaic(it, strokes) } }
            "text", "sticker" -> ItemsTool(sm, busy, sticker = tool == "sticker", onCancel = { tool = null }) { items ->
                commit { src -> src.copy(Bitmap.Config.ARGB_8888, true).also { out -> val c = android.graphics.Canvas(out); items.forEach { PhotoOps.drawItem(c, it, out.width.toFloat(), out.height.toFloat()) } } }
            }
            "blur" -> BlurTool(sm, busy, onCancel = { tool = null }) { f -> commit { PhotoOps.focusBlur(it, f) } }
            "cutout" -> CutoutTool(b, onCancel = { tool = null }) { out -> commit { out } }
            else -> {
                PhotoView(sm, Modifier.weight(1f))
                ToolBar(onPick = { tool = it })
            }
        }
    }
    if (askLeave) AlertDialog(
        onDismissRequest = { askLeave = false },
        title = { Text("不保存就退出？") },
        text = { Text("修过的地方会丢掉，原图不受影响。") },
        confirmButton = { TextButton(onClick = { askLeave = false; onClose() }) { Text("不保存") } },
        dismissButton = { TextButton(onClick = { askLeave = false }) { Text("接着修") } },
    )
}

/** 有没有真透明的像素（抠过图、圆角边框）：有就存 PNG。抽样看，不逐个查。 */
private fun hasTransparency(b: Bitmap): Boolean {
    val step = max(1, min(b.width, b.height) / 64)
    var y = 0
    while (y < b.height) { var x = 0; while (x < b.width) { if ((b.getPixel(x, y) ushr 24) < 250) return true; x += step }; y += step }
    return false
}

// —— 共用的部件 ——

/** 图片显示区：图按比例放进框里；rect 回报图在框里的位置（手势换算坐标用）。 */
@Composable
private fun PhotoView(
    bmp: Bitmap, modifier: Modifier, gesture: Modifier = Modifier,
    onRect: (Rect) -> Unit = {}, overlay: DrawScope.(Rect) -> Unit = {},
) {
    val img = remember(bmp) { bmp.asImageBitmap() }
    BoxWithConstraints(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(12.dp)) {
        Canvas(Modifier.fillMaxSize().then(gesture)) {
            val k = min(size.width / bmp.width, size.height / bmp.height)
            val w = bmp.width * k; val h = bmp.height * k
            val r = Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
            onRect(r)
            // 透明的地方（抠图后）铺浅灰格子
            if (bmp.hasAlpha()) checker(r)
            drawImage(img, dstOffset = IntOffset(r.left.toInt(), r.top.toInt()), dstSize = IntSize(r.width.toInt(), r.height.toInt()))
            overlay(r)
        }
    }
}

private fun DrawScope.checker(r: Rect) {
    val s = 16.dp.toPx()
    var y = r.top; var row = 0
    while (y < r.bottom) {
        var x = r.left + if (row % 2 == 0) 0f else s
        while (x < r.right) { drawRect(Color(0xFFDDDDDD), Offset(x, y), Size(min(s, r.right - x), min(s, r.bottom - y))); x += s * 2 }
        y += s; row++
    }
}

/** 底部的工具条：图标 + 名字，横着滑。 */
@Composable
private fun ToolBar(onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TOOLS.forEach { t ->
            Column(Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable { onPick(t.id) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(t.icon, t.name, Modifier.size(24.dp))
                Text(t.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** 工具面板的头：✕ 名字 ✓。 */
@Composable
private fun PanelHead(title: String, busy: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel, enabled = !busy) { Icon(Icons.Default.Close, "放弃") }
        Text(title, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
        if (busy) CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
        else IconButton(onClick = onDone) { Icon(Icons.Default.Check, "好了") }
    }
}

@Composable
private fun Chips(content: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) = FilterChip(selected = on, onClick = onClick, label = { Text(text) })

@Composable
private fun ColorRow(color: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PALETTE.forEach { c ->
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable { onPick(c) })
        }
    }
}

/** 粗细：五档圆点。 */
@Composable
private fun SizeRow(level: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        (0..4).forEach { i ->
            Box(Modifier.size(44.dp).clickable { onPick(i) }, contentAlignment = Alignment.Center) {
                Box(Modifier.size((8 + i * 5).dp).clip(CircleShape).background(if (i == level) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
    }
}

private val WIDTHS = floatArrayOf(0.006f, 0.012f, 0.02f, 0.032f, 0.05f)

/** 屏幕上的点 → 图上的归一化坐标。 */
private fun norm(p: Offset, r: Rect) = ((p.x - r.left) / r.width) to ((p.y - r.top) / r.height)

/** 在预览上画笔画（nativeCanvas，和保存时同一套画法）。 */
private fun DrawScope.drawStrokesOn(r: Rect, strokes: List<PhotoOps.Stroke>) = drawContext.canvas.nativeCanvas.let { c ->
    c.save(); c.translate(r.left, r.top); c.clipRect(0f, 0f, r.width, r.height)
    PhotoOps.drawStrokes(c, strokes, r.width, r.height)
    c.restore()
}

// —— 裁剪 ——

private val RATIOS = listOf("自由" to 0f, "原比例" to -1f, "1:1" to 1f, "3:4" to 0.75f, "4:3" to 4 / 3f, "9:16" to 9 / 16f, "16:9" to 16 / 9f)

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CropTool(
    sm: Bitmap, busy: Boolean, onCancel: () -> Unit, onDone: (Int, Boolean, Boolean, Float, RectF) -> Unit,
) {
    var quarter by remember { mutableIntStateOf(0) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    var angle by remember { mutableFloatStateOf(0f) }
    var ratio by remember { mutableFloatStateOf(0f) }
    var crop by remember { mutableStateOf(RectF(0f, 0f, 1f, 1f)) }
    val oriented by produceState(sm, sm, quarter, flipH, flipV, angle) { value = withContext(Dispatchers.Default) { PhotoOps.orient(sm, quarter, flipH, flipV, angle) } }
    fun applyRatio(r: Float) {
        ratio = r
        val o = oriented
        crop = when { r == 0f -> RectF(0f, 0f, 1f, 1f); r < 0f -> RectF(0f, 0f, 1f, 1f); else -> PhotoOps.fitRatio(o.width, o.height, r) }
    }
    var rect by remember { mutableStateOf(Rect.Zero) }
    // 拖哪儿：四角改大小，框里面整个挪
    var grab by remember { mutableStateOf("") }
    PhotoView(oriented, Modifier.weight(1f), onRect = { rect = it },
        gesture = Modifier.pointerInput(oriented, ratio) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val r = rect
                val cr = Rect(r.left + crop.left * r.width, r.top + crop.top * r.height, r.left + crop.right * r.width, r.top + crop.bottom * r.height)
                val hit = 36.dp.toPx()
                val p = down.position
                grab = when {
                    (p - cr.topLeft).getDistance() < hit -> "tl"; (p - cr.topRight).getDistance() < hit -> "tr"
                    (p - cr.bottomLeft).getDistance() < hit -> "bl"; (p - cr.bottomRight).getDistance() < hit -> "br"
                    cr.contains(p) -> "move"; else -> ""
                }
                if (grab.isEmpty()) return@awaitEachGesture
                val imgRatio = oriented.width.toFloat() / oriented.height
                val want = if (ratio > 0f) ratio / imgRatio else if (ratio < 0f) 1f else 0f // 归一化坐标下的宽高比
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull() ?: break
                    if (!ch.pressed) break
                    val d = ch.position - ch.previousPosition
                    val dx = d.x / r.width; val dy = d.y / r.height
                    val c = RectF(crop)
                    val minS = 0.08f
                    when (grab) {
                        "move" -> { val w = c.width(); val h = c.height(); c.left = (c.left + dx).coerceIn(0f, 1f - w); c.top = (c.top + dy).coerceIn(0f, 1f - h); c.right = c.left + w; c.bottom = c.top + h }
                        else -> {
                            if ("l" in grab) c.left = (c.left + dx).coerceIn(0f, c.right - minS) else c.right = (c.right + dx).coerceIn(c.left + minS, 1f)
                            if ("t" in grab) c.top = (c.top + dy).coerceIn(0f, c.bottom - minS) else c.bottom = (c.bottom + dy).coerceIn(c.top + minS, 1f)
                            if (want > 0f) {
                                // 定了比例：按宽算高，超出就反过来
                                var h = c.width() / want
                                if ("t" in grab) c.top = c.bottom - h else c.bottom = c.top + h
                                if (c.top < 0f || c.bottom > 1f) {
                                    if (c.top < 0f) c.top = 0f; if (c.bottom > 1f) c.bottom = 1f
                                    h = c.height(); val w = h * want
                                    if ("l" in grab) c.left = c.right - w else c.right = c.left + w
                                }
                            }
                        }
                    }
                    crop = c
                    ch.consume()
                }
            }
        },
        overlay = { r ->
            val cr = Rect(r.left + crop.left * r.width, r.top + crop.top * r.height, r.left + crop.right * r.width, r.top + crop.bottom * r.height)
            val dim = Color.Black.copy(alpha = 0.5f)
            drawRect(dim, r.topLeft, Size(r.width, cr.top - r.top))
            drawRect(dim, Offset(r.left, cr.bottom), Size(r.width, r.bottom - cr.bottom))
            drawRect(dim, Offset(r.left, cr.top), Size(cr.left - r.left, cr.height))
            drawRect(dim, Offset(cr.right, cr.top), Size(r.right - cr.right, cr.height))
            drawRect(Color.White, cr.topLeft, cr.size, style = Stroke(2.dp.toPx()))
            for (i in 1..2) {
                drawLine(Color.White.copy(alpha = 0.5f), Offset(cr.left + cr.width * i / 3, cr.top), Offset(cr.left + cr.width * i / 3, cr.bottom), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.5f), Offset(cr.left, cr.top + cr.height * i / 3), Offset(cr.right, cr.top + cr.height * i / 3), 1.dp.toPx())
            }
            val l = 22.dp.toPx(); val t = 4.dp.toPx()
            listOf(cr.topLeft to (1 to 1), cr.topRight to (-1 to 1), cr.bottomLeft to (1 to -1), cr.bottomRight to (-1 to -1)).forEach { (p, s) ->
                drawLine(Color.White, p, Offset(p.x + l * s.first, p.y), t); drawLine(Color.White, p, Offset(p.x, p.y + l * s.second), t)
            }
        })
    PanelHead("裁剪", busy, onCancel) { onDone(quarter, flipH, flipV, angle, crop) }
    Text("拉直 ${"%.1f".format(angle)}°", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
    Slider(angle, { angle = (it * 2).toInt() / 2f }, valueRange = -45f..45f, modifier = Modifier.padding(horizontal = 12.dp))
    Chips {
        Chip("向左转", false) { quarter -= 1; applyRatio(ratio) }
        Chip("左右翻转", flipH) { flipH = !flipH }
        Chip("上下翻转", flipV) { flipV = !flipV }
        Chip("还原", false) { quarter = 0; flipH = false; flipV = false; angle = 0f; applyRatio(0f) }
    }
    Spacer(Modifier.height(6.dp))
    Chips { RATIOS.forEach { (name, r) -> Chip(name, ratio == r) { applyRatio(r) } } }
    Spacer(Modifier.navigationBarsPadding().height(10.dp))
}

// —— 滤镜 ——

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.FilterTool(sm: Bitmap, busy: Boolean, onCancel: () -> Unit, onDone: (android.graphics.ColorMatrix?) -> Unit) {
    var pick by remember { mutableIntStateOf(0) }
    var strength by remember { mutableFloatStateOf(1f) }
    val matrix = PhotoOps.filterMatrix(PhotoOps.FILTERS[pick].second, strength)
    val preview by produceState(sm, sm, pick, strength) { value = withContext(Dispatchers.Default) { PhotoOps.applyMatrix(sm, PhotoOps.filterMatrix(PhotoOps.FILTERS[pick].second, strength)) } }
    val thumbs by produceState<List<Bitmap>>(emptyList(), sm) {
        value = withContext(Dispatchers.Default) { val t = PhotoOps.scaleDown(sm, 180); PhotoOps.FILTERS.map { PhotoOps.applyMatrix(t, PhotoOps.filterMatrix(it.second, 1f)) } }
    }
    PhotoView(preview, Modifier.weight(1f))
    PanelHead("滤镜", busy, onCancel) { onDone(matrix) }
    if (pick > 0) Slider(strength, { strength = it }, modifier = Modifier.padding(horizontal = 16.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhotoOps.FILTERS.forEachIndexed { i, (name, _) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { pick = i; strength = 1f }) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).border(if (i == pick) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))) {
                    thumbs.getOrNull(i)?.let { Image(remember(it) { it.asImageBitmap() }, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Text(name, style = MaterialTheme.typography.labelSmall, fontWeight = if (i == pick) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
    Spacer(Modifier.navigationBarsPadding().height(10.dp))
}

// —— 调节 ——

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AdjustTool(sm: Bitmap, busy: Boolean, onCancel: () -> Unit, onDone: (Map<String, Float>) -> Unit) {
    val values = remember { mutableStateMapOf<String, Float>() }
    var key by remember { mutableStateOf(PhotoOps.ADJUST.first().first) }
    val snapshot = values.toMap()
    // 拖滑块时别每一帧都算：停 60ms 再算
    val preview by produceState(sm, sm, snapshot) { delay(60); value = withContext(Dispatchers.Default) { PhotoOps.adjust(sm, snapshot) } }
    PhotoView(preview, Modifier.weight(1f))
    PanelHead("调节", busy, onCancel) { onDone(snapshot) }
    val v = values[key] ?: 0f
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Slider(v, { values[key] = it.toInt().toFloat() }, valueRange = -100f..100f, modifier = Modifier.weight(1f))
        Text(v.toInt().toString(), Modifier.width(40.dp), textAlign = TextAlign.End)
    }
    Chips {
        PhotoOps.ADJUST.forEach { (k, name) ->
            val changed = abs(values[k] ?: 0f) >= 1f
            Chip(if (changed) "$name·${(values[k] ?: 0f).toInt()}" else name, k == key) { key = k }
        }
        Chip("全部还原", false) { values.clear() }
    }
    Spacer(Modifier.navigationBarsPadding().height(10.dp))
}

// —— 边框 ——

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.FrameTool(sm: Bitmap, busy: Boolean, onCancel: () -> Unit, onDone: (String, Int) -> Unit) {
    var style by remember { mutableStateOf("thin") }
    var color by remember { mutableIntStateOf(android.graphics.Color.WHITE) }
    val preview by produceState(sm, sm, style, color) { value = withContext(Dispatchers.Default) { PhotoOps.frame(sm, style, color) } }
    PhotoView(preview, Modifier.weight(1f))
    PanelHead("边框", busy, onCancel) { onDone(style, color) }
    Chips { listOf("none" to "无", "thin" to "细边", "wide" to "宽边", "polaroid" to "拍立得", "round" to "圆角").forEach { (k, n) -> Chip(n, style == k) { style = k } } }
    if (style in setOf("thin", "wide", "polaroid")) ColorRow(color) { color = it }
    Spacer(Modifier.navigationBarsPadding().height(10.dp))
}

// —— 画笔 ——

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.DoodleTool(sm: Bitmap, busy: Boolean, onCancel: () -> Unit, onDone: (List<PhotoOps.Stroke>) -> Unit) {
    val strokes = remember { mutableStateListOf<PhotoOps.Stroke>() }
    var current by remember { mutableStateOf<PhotoOps.Stroke?>(null) }
    var kind by remember { mutableStateOf("pen") }
    var color by remember { mutableIntStateOf(0xFFE23B2E.toInt()) }
    var level by remember { mutableIntStateOf(2) }
    var rect by remember { mutableStateOf(Rect.Zero) }
    PhotoView(sm, Modifier.weight(1f), onRect = { rect = it },
        gesture = Modifier.pointerInput(kind, color, level) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val pts = mutableListOf(norm(down.position, rect))
                val w = WIDTHS[level] * (if (kind == "eraser") 2f else 1f)
                current = PhotoOps.Stroke(kind, pts.toList(), color, w)
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull() ?: break
                    if (!ch.pressed) break
                    if (ch.positionChanged()) {
                        val p = norm(ch.position, rect)
                        if (kind in setOf("pen", "eraser")) pts += p else if (pts.size == 1) pts += p else pts[1] = p
                        current = PhotoOps.Stroke(kind, pts.toList(), color, w)
                        ch.consume()
                    }
                }
                current?.let { strokes += it }
                current = null
            }
        },
        overlay = { r -> drawStrokesOn(r, strokes + listOfNotNull(current)) })
    PanelHead("画笔", busy, onCancel) { onDone(strokes.toList()) }
    Chips {
        listOf("pen" to "画笔", "arrow" to "箭头", "line" to "直线", "rect" to "方框", "oval" to "圆圈", "eraser" to "橡皮擦").forEach { (k, n) -> Chip(n, kind == k) { kind = k } }
        Chip("撤销一笔", false) { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }
    }
    if (kind != "eraser") ColorRow(color) { color = it }
    SizeRow(level) { level = it }
    Spacer(Modifier.navigationBarsPadding())
}

// —— 马赛克 ——

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.MosaicTool(sm: Bitmap, busy: Boolean, onCancel: () -> Unit, onDone: (List<PhotoOps.Stroke>) -> Unit) {
    val strokes = remember { mutableStateListOf<PhotoOps.Stroke>() }
    var current by remember { mutableStateOf<PhotoOps.Stroke?>(null) }
    var kind by remember { mutableStateOf("pixel") }
    var level by remember { mutableIntStateOf(3) }
    var rect by remember { mutableStateOf(Rect.Zero) }
    val layers by produceState<Pair<Bitmap, Bitmap>?>(null, sm) { value = withContext(Dispatchers.Default) { PhotoOps.pixelate(sm, 0.035f) to PhotoOps.blur(sm, 0.025f) } }
    val done = strokes.toList()
    val preview by produceState(sm, sm, done, layers) { val l = layers; value = withContext(Dispatchers.Default) { PhotoOps.mosaic(sm, done, l?.first, l?.second) } }
    PhotoView(preview, Modifier.weight(1f), onRect = { rect = it },
        gesture = Modifier.pointerInput(kind, level) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val pts = mutableListOf(norm(down.position, rect))
                val w = WIDTHS[level] * 2.4f
                current = PhotoOps.Stroke(kind, pts.toList(), 0, w)
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull() ?: break
                    if (!ch.pressed) break
                    if (ch.positionChanged()) { pts += norm(ch.position, rect); current = PhotoOps.Stroke(kind, pts.toList(), 0, w); ch.consume() }
                }
                current?.let { strokes += it }
                current = null
            }
        },
        // 正在刷的这一笔先用半透明白色标出来，松手后才真打上马赛克
        overlay = { r -> current?.let { c -> drawStrokesOn(r, listOf(c.copy(kind = "pen", color = 0x66FFFFFF))) } })
    PanelHead("马赛克", busy, onCancel) { onDone(strokes.toList()) }
    Chips {
        listOf("pixel" to "马赛克", "blur" to "毛玻璃", "eraser" to "橡皮擦").forEach { (k, n) -> Chip(n, kind == k) { kind = k } }
        Chip("撤销一笔", false) { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }
    }
    SizeRow(level) { level = it }
    Spacer(Modifier.navigationBarsPadding())
}

// —— 文字、贴纸 ——

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ItemsTool(sm: Bitmap, busy: Boolean, sticker: Boolean, onCancel: () -> Unit, onDone: (List<PhotoOps.Item>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<PhotoOps.Item>() }
    var selected by remember { mutableStateOf<Long?>(null) }
    var nextId by remember { mutableStateOf(1L) }
    var rect by remember { mutableStateOf(Rect.Zero) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var captions by remember { mutableStateOf<List<String>>(emptyList()) }
    var thinking by remember { mutableStateOf(false) }
    fun sel() = items.indexOfFirst { it.id == selected }
    fun update(f: (PhotoOps.Item) -> PhotoOps.Item) { val i = sel(); if (i >= 0) items[i] = f(items[i]) }
    fun add(text: String, isSticker: Boolean) {
        val it = PhotoOps.Item(nextId++, text, sticker = isSticker, cy = 0.5f + (items.size % 4) * 0.06f, size = if (isSticker) 0.16f else 0.08f,
            style = if (isSticker) "plain" else "stroke")
        items += it; selected = it.id
    }
    LaunchedEffect(Unit) { if (!sticker) add(PLACEHOLDER, false) }
    PhotoView(sm, Modifier.weight(1f), onRect = { rect = it },
        gesture = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val r = rect
                val (x, y) = norm(down.position, r)
                val hit = PhotoOps.hit(items, x, y, r.width, r.height)
                selected = hit?.id
                if (hit == null) return@awaitEachGesture
                var moved = false
                while (true) {
                    val e = awaitPointerEvent()
                    if (e.changes.none { it.pressed }) break
                    val pan = e.calculatePan(); val zoom = e.calculateZoom(); val rot = e.calculateRotation()
                    if (pan != Offset.Zero || zoom != 1f || rot != 0f) {
                        moved = true
                        update { it.copy(cx = (it.cx + pan.x / r.width).coerceIn(0f, 1f), cy = (it.cy + pan.y / r.height).coerceIn(0f, 1f),
                            size = (it.size * zoom).coerceIn(0.02f, 0.6f), rotation = it.rotation + rot) }
                        e.changes.forEach { it.consume() }
                    }
                }
                if (!moved && !sticker) runCatching { focus.requestFocus() }
            }
        },
        overlay = { r ->
            val c = drawContext.canvas.nativeCanvas
            c.save(); c.translate(r.left, r.top)
            items.forEach { PhotoOps.drawItem(c, it, r.width, r.height) }
            c.restore()
            items.firstOrNull { it.id == selected }?.let { it ->
                val (iw, ih) = PhotoOps.itemSize(it, r.width)
                val center = Offset(r.left + it.cx * r.width, r.top + it.cy * r.height)
                rotate(it.rotation, center) {
                    drawRect(Color.White, Offset(center.x - iw / 2, center.y - ih / 2), Size(iw, ih),
                        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                }
            }
        })
    PanelHead(if (sticker) "贴纸" else "文字", busy, onCancel) { onDone(items.toList()) }
    val cur = items.firstOrNull { it.id == selected }
    if (sticker) {
        Text("点一个加上去；两指缩放、旋转", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(48.dp), Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 8.dp)) {
            items(EMOJI.size) { i -> Box(Modifier.size(48.dp).clickable { add(EMOJI[i], true) }, contentAlignment = Alignment.Center) { Text(EMOJI[i], fontSize = 28.sp) } }
        }
    } else {
        if (cur != null) OutlinedTextField(
            if (cur.text == PLACEHOLDER) "" else cur.text, { t -> update { it.copy(text = t.ifEmpty { PLACEHOLDER }) } },
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(focus), maxLines = 3, placeholder = { Text("输入文字") },
        )
        Chips {
            Chip("加一段", false) { add(PLACEHOLDER, false) }
            Chip(if (thinking) "Kavi 在想…" else "Kavi 配文", false) {
                if (!thinking) scope.launch {
                    thinking = true
                    captions = runCatching { PhotoCaption.suggest(ctx, sm) }.getOrElse { e -> Toast.makeText(ctx, e.message ?: "没想出来", Toast.LENGTH_LONG).show(); emptyList() }
                    thinking = false
                }
            }
            if (cur != null) Chip("删掉", false) { items.removeAll { it.id == selected }; selected = null }
        }
        if (captions.isNotEmpty()) Chips { captions.forEach { c -> Chip(c, false) { if (cur != null) update { it.copy(text = c) } else add(c, false) } } }
        if (cur != null) {
            Chips { listOf("stroke" to "描边", "block" to "底色", "shadow" to "阴影", "plain" to "普通").forEach { (k, n) -> Chip(n, cur.style == k) { update { it.copy(style = k) } } } }
            Chips { listOf("sans" to "黑体", "black" to "粗黑", "serif" to "宋体", "light" to "细体", "mono" to "等宽").forEach { (k, n) -> Chip(n, cur.font == k) { update { it.copy(font = k) } } } }
            ColorRow(cur.color) { c -> update { it.copy(color = c) } }
        }
    }
    Spacer(Modifier.navigationBarsPadding().height(10.dp))
}

private const val PLACEHOLDER = "点这里改字"

// —— 虚化 ——

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BlurTool(sm: Bitmap, busy: Boolean, onCancel: () -> Unit, onDone: (PhotoOps.Focus) -> Unit) {
    var focus by remember { mutableStateOf(PhotoOps.Focus()) }
    var touching by remember { mutableStateOf(false) }
    var rect by remember { mutableStateOf(Rect.Zero) }
    val f = focus
    val preview by produceState(sm, sm, f) { delay(40); value = withContext(Dispatchers.Default) { PhotoOps.focusBlur(sm, f) } }
    PhotoView(preview, Modifier.weight(1f), onRect = { rect = it },
        gesture = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                touching = true
                while (true) {
                    val e = awaitPointerEvent()
                    if (e.changes.none { it.pressed }) break
                    val r = rect
                    val pan = e.calculatePan(); val zoom = e.calculateZoom(); val rot = e.calculateRotation()
                    focus = focus.copy(cx = (focus.cx + pan.x / r.width).coerceIn(0f, 1f), cy = (focus.cy + pan.y / r.height).coerceIn(0f, 1f),
                        radius = (focus.radius * zoom).coerceIn(0.05f, 0.9f), angle = focus.angle + rot)
                    e.changes.forEach { it.consume() }
                }
                touching = false
            }
        },
        overlay = { r ->
            if (focus.mode == "none") return@PhotoView
            val c = Offset(r.left + focus.cx * r.width, r.top + focus.cy * r.height)
            val rad = focus.radius * min(r.width, r.height)
            val col = Color.White.copy(alpha = if (touching) 0.9f else 0.5f)
            if (focus.mode == "radial") drawCircle(col, rad, c, style = Stroke(2.dp.toPx()))
            else withTransform({ rotate(focus.angle, c) }) {
                drawLine(col, Offset(c.x - r.width * 2, c.y - rad), Offset(c.x + r.width * 2, c.y - rad), 2.dp.toPx())
                drawLine(col, Offset(c.x - r.width * 2, c.y + rad), Offset(c.x + r.width * 2, c.y + rad), 2.dp.toPx())
            }
        })
    PanelHead("虚化", busy, onCancel) { onDone(focus) }
    Text("拖动挪焦点，两指缩放改范围" + if (focus.mode == "linear") "、转动改方向" else "", Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Chips { listOf("none" to "无", "radial" to "圆形", "linear" to "线性").forEach { (k, n) -> Chip(n, focus.mode == k) { focus = focus.copy(mode = k) } } }
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("强度", style = MaterialTheme.typography.labelMedium)
        Slider(focus.strength, { focus = focus.copy(strength = it) }, modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
    Spacer(Modifier.navigationBarsPadding().height(10.dp))
}

// —— 抠图 ——

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CutoutTool(b: Bitmap, onCancel: () -> Unit, onDone: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    val ms = com.jack.ai.data.LocalModels
    val modelState by ms.state.collectAsState()
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf("") }
    val ready = ms.cutoutReady(ctx)
    LaunchedEffect(ready) {
        val m = ready ?: return@LaunchedEffect
        val r = runCatching {
            withContext(Dispatchers.IO) {
                val tmp = File(ctx.cacheDir, "photo-cut-in.png"); val out = File(ctx.cacheDir, "photo-cut-out.png")
                tmp.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                com.jack.ai.api.LocalCutout.cut(File(ms.dir(ctx, m), m.files.first().path), tmp, out)
                android.graphics.BitmapFactory.decodeFile(out.path)?.copy(Bitmap.Config.ARGB_8888, false).also { tmp.delete(); out.delete() }
            }
        }
        r.onSuccess { result = it }.onFailure { error = it.message ?: "没抠成" }
    }
    PhotoView(result ?: b, Modifier.weight(1f))
    PanelHead("抠图", busy = result == null && error.isEmpty() && ready != null, onCancel = onCancel) { result?.let(onDone) ?: onCancel() }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding()) {
        when {
            error.isNotEmpty() -> Text("抠图失败：$error", color = MaterialTheme.colorScheme.error)
            result != null -> Text("抠好了：背景变透明，保存成 PNG。点 ✓ 用这张。", style = MaterialTheme.typography.bodyMedium)
            ready != null -> Text("正在抠图，十几秒…", style = MaterialTheme.typography.bodyMedium)
            else -> {
                val running = ms.CUTOUTS.firstOrNull { modelState[it.id]?.running == true }
                if (running != null) {
                    val st = modelState[running.id]
                    Text("正在下载${running.name}：${ms.mb(st?.done ?: 0)} / ${ms.mb(running.bytes)}，下好自动开始。")
                } else {
                    Text("抠图在手机上做，不联网、不花钱，只要下载一次模型。选一个：", style = MaterialTheme.typography.bodyMedium)
                    listOf(ms.CUTOUT_SMALL, ms.CUTOUT_HD).forEach { m ->
                        Column(Modifier.fillMaxWidth().clickable { ms.start(ctx, m) }.padding(vertical = 8.dp)) {
                            Text(m.name, fontWeight = FontWeight.Bold)
                            Text(m.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
