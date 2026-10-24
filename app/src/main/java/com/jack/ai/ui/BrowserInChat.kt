package com.jack.ai.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jack.ai.data.Attachments
import com.jack.ai.data.SearchRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Jack 正在操作网页时，回答里显示的实时画面：网页按整屏排版、缩到回答的宽度，能看到它点了哪、输了什么。
 * 点一下进完整浏览器接手（比如自己登录）。它在的时候，聊天页右上角的小窗就不出来。
 */
@Composable
fun BrowserLive() {
    val ctx = LocalContext.current
    val b = JackBrowser
    val dm = ctx.resources.displayMetrics
    val density = LocalDensity.current
    val fullW = with(density) { dm.widthPixels.toDp() }
    val fullH = with(density) { dm.heightPixels.toDp() }
    DisposableEffect(Unit) {
        b.inlineHosts++
        // 离开时让小窗（或浏览器页）重新来要网页窗口
        onDispose { b.inlineHosts--; b.release(ctx) }
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scale = maxWidth / fullW
            // 只露出上面一部分（大约半屏），够看清在干什么，不占满整个对话
            Box(Modifier.fillMaxWidth().height(fullH * scale * 0.55f).clipToBounds()) {
                key(b.hostGen) {
                    AndroidView(
                        factory = { b.claim(ctx) },
                        modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(fullW, fullH)
                            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) },
                    )
                }
                // 盖一层：这里不让点网页，点了进完整浏览器
                Box(Modifier.fillMaxSize().clickable { ctx.startActivity(Intent(ctx, BrowserActivity::class.java)) })
                Text(
                    "Kavi 正在操作网页 · 点这里接手",
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 这次回答里 Jack 操作网页的每一步：小截图 + 做了什么，横着排，点开看大图。 */
@Composable
fun BrowserSteps(steps: List<SearchRecord>) {
    var viewing by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { i, s ->
            Column(Modifier.width(112.dp).clip(RoundedCornerShape(10.dp)).clickable { viewing = s.shot }) {
                StepImage(s.shot)
                Text(
                    "${i + 1}. " + s.query.removePrefix("浏览器"),
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (s.error != null) colors.error else colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

@Composable
private fun StepImage(path: String) {
    val bmp by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { Attachments.thumbnail(path, 360) } }
    Box(Modifier.width(112.dp).height(150.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter) }
        if (bmp == null) Text("截图没了", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall)
    }
}
