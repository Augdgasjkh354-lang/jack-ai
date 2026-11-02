package com.jack.ai.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Kavi 在操作浏览器时，聊天页右上角的小窗：按整屏大小排版、缩小显示，能实时看到它在干什么，点一下进完整浏览器接手。
 * 网页窗口放在这里也让截图能画出来（没放进任何界面的网页窗口截不出图）。
 */
@Composable
fun BrowserPeek(modifier: Modifier = Modifier, working: Boolean = true, convId: String = "") {
    val ctx = LocalContext.current
    val b = JackBrowser
    // 这个对话的回答在自己的后台标签页里做事：小窗放那一页
    val agentTab by androidx.compose.runtime.produceState<JackBrowser.Tab?>(null, convId) {
        while (true) { value = com.jack.ai.tools.BrowserLock.tabForConv(convId)?.takeIf { it.web != null }; delay(500) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(b.agentAt) {
        while (true) { now = System.currentTimeMillis(); delay(5_000) }
    }
    if (!working || !b.agentActive(now) || b.inlineHosts > 0) return
    val dm = ctx.resources.displayMetrics
    val density = LocalDensity.current
    val fullW = with(density) { dm.widthPixels.toDp() }
    val fullH = with(density) { dm.heightPixels.toDp() }
    val scale = 0.28f
    Surface(
        modifier.size(fullW * scale, fullH * scale),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.clipToBounds()) {
            val own = agentTab
            key(b.hostGen, own) {
                AndroidView(
                    factory = { own?.web?.let { w -> (w.parent as? android.view.ViewGroup)?.removeView(w); w } ?: b.claim(ctx) },
                    modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(fullW, fullH)
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) },
                )
                DisposableEffect(Unit) { onDispose { b.release(ctx, handBack = false) } }
            }
            // 盖一层：小窗里不让点网页，点了就进完整浏览器
            Box(Modifier.matchParentSize().clickable {
                own?.let { t -> b.tabs.indexOf(t).takeIf { it >= 0 }?.let { b.switchTo(it) } }
                ctx.startActivity(Intent(ctx, BrowserActivity::class.java))
            })
            Text(
                "Kavi 在用浏览器",
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)).padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
