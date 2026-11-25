package com.jack.ai.a11y

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 「问这一屏」的圈选页：铺满刚截的屏幕，用手指圈出想问的那块（像「圈一圈搜索」），
 * 只把这块的截图和这块里的字带进 Kavi；也可以点「问整屏」照以前那样整屏带过去。
 */
class ScreenPickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = intent.getStringExtra("app").orEmpty().ifBlank { "当前屏幕" }
        val shotFile = intent.getStringExtra("shot")?.let { File(it) }
        val shot = shotFile?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        val lines = intent.getStringExtra("lines")?.let { ScreenAsk.readLines(File(it)) }.orEmpty()
        if (shot == null) { ScreenAsk.toast(this, "截图没了，再叫一次 Kavi"); finish(); return }

        fun send(crop: android.graphics.Rect?) {
            val (img, text) = if (crop == null) shot to ScreenAsk.allText(app, lines) else {
                val part = Bitmap.createBitmap(shot, crop.left, crop.top, crop.width(), crop.height())
                val words = ScreenAsk.inside(lines, crop.left, crop.top, crop.right, crop.bottom)
                part to if (words.isEmpty()) "" else "「$app」屏幕上圈出来的这一块里的字：\n" + words.joinToString("\n") + "\n"
            }
            startActivity(ScreenAsk.intent(this, if (crop == null) app else "$app（圈出的一块）", text, img))
            shotFile.delete()
            finish()
        }

        val settings = com.jack.ai.data.Store(this).loadSettings()
        setContent { com.jack.ai.ui.JackTheme(settings.themeMode, settings.accent, settings.fontScale, settings.appIcon, settings.fontChoice) {
            // 手指画过的点（屏幕上显示的坐标）、圈完的框（截图上的像素）
            val points = remember { mutableStateListOf<Offset>() }
            var box by remember { mutableStateOf<android.graphics.Rect?>(null) }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val vw = with(density) { maxWidth.toPx() }
                    val vh = with(density) { maxHeight.toPx() }
                    // 截图按比例放进屏幕（一般正好铺满）：显示坐标 ↔ 截图像素
                    val scale = minOf(vw / shot.width, vh / shot.height)
                    val ox = (vw - shot.width * scale) / 2
                    val oy = (vh - shot.height * scale) / 2
                    Image(shot.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { p -> points.clear(); box = null; points += p },
                            onDrag = { change, _ -> points += change.position },
                            onDragEnd = {
                                if (points.size < 3) return@detectDragGestures
                                val xs = points.map { ((it.x - ox) / scale).toInt() }
                                val ys = points.map { ((it.y - oy) / scale).toInt() }
                                // 往外留一点边，别把字切掉
                                val pad = (12 / scale).toInt()
                                val l = (xs.min() - pad).coerceIn(0, shot.width - 1)
                                val t = (ys.min() - pad).coerceIn(0, shot.height - 1)
                                val r = (xs.max() + pad).coerceIn(l + 1, shot.width)
                                val b = (ys.max() + pad).coerceIn(t + 1, shot.height)
                                box = if (r - l >= 24 && b - t >= 24) android.graphics.Rect(l, t, r, b) else null
                            },
                        )
                    }) {
                        // 没圈的地方压暗一点，圈中的框亮着
                        val sel = box?.let { Rect(ox + it.left * scale, oy + it.top * scale, ox + it.right * scale, oy + it.bottom * scale) }
                        if (sel == null) drawRect(Color.Black.copy(alpha = 0.18f))
                        else {
                            val dim = Color.Black.copy(alpha = 0.45f)
                            drawRect(dim, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, sel.top))
                            drawRect(dim, Offset(0f, sel.bottom), androidx.compose.ui.geometry.Size(size.width, size.height - sel.bottom))
                            drawRect(dim, Offset(0f, sel.top), androidx.compose.ui.geometry.Size(sel.left, sel.height))
                            drawRect(dim, Offset(sel.right, sel.top), androidx.compose.ui.geometry.Size(size.width - sel.right, sel.height))
                            drawRoundRect(Color.White, sel.topLeft, sel.size, androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx()))
                        }
                        if (points.size > 1) {
                            val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }
                            drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
                Text(if (box == null) "用手指圈出想问的地方" else "圈好了，问这一块？",
                    color = Color.White, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp))
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                    OutlinedButton(onClick = { shotFile.delete(); finish() }, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.55f))) { Text("取消", color = Color.White) }
                    if (box != null) OutlinedButton(onClick = { box = null; points.clear() }, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.55f))) { Text("重圈", color = Color.White) }
                    if (box != null) Button(onClick = { send(box) }) { Text("问这一块") }
                    else Button(onClick = { send(null) }) { Text("问整屏") }
                }
            }
        } }
    }
}
