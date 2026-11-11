package com.jack.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Kavi 在操作浏览器、而回答里的实时画面（[BrowserLive]）不在屏幕上时（滑走了，或这条回答还没用到浏览器），
 * 网页窗口放在这里：没放进任何界面的网页窗口截不出图、网页也不好好跑。
 *
 * 以前这里是聊天页右上角的一个悬浮小窗，会盖住回答；现在只留一个看不见的落点（整屏大小排版，只露出 1dp），
 * 要看 Kavi 在干什么就看回答里的实时画面，滑上去就有。
 */
@Composable
fun BrowserHiddenHost(modifier: Modifier = Modifier, working: Boolean = true, convId: String = "") {
    val ctx = LocalContext.current
    val b = JackBrowser
    // 这个对话的回答在自己的后台标签页里做事：放那一页
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
    Box(modifier.size(1.dp).clipToBounds()) {
        val own = agentTab
        key(b.hostGen, own) {
            AndroidView(
                factory = { own?.web?.let { w -> (w.parent as? android.view.ViewGroup)?.removeView(w); w } ?: b.claim(ctx) },
                modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(fullW, fullH),
            )
            DisposableEffect(Unit) { onDispose { b.release(ctx, handBack = false) } }
        }
    }
}
