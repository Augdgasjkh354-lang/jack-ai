package com.jack.ai.ui

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 全 App 的按钮：和 [Switch]、[OutlinedButton] 一样，同一个包里的这几个会盖过 material3 的
 * （ui 包里的文件别再单独 import material3 的 Button / TextButton / IconButton）。
 *
 * 比系统的多几样：
 *  - 按下去缩一下、轻轻震一下（跟着系统「触摸振动」开关走）；
 *  - busy = true：前面转个小圈，点了不重复触发；
 *  - whyDisabled：灰着的时候点一下，晃一晃并说缺什么（以前点了没反应，不知道为什么不能按）；
 *  - 实心的主按钮从灰变成能按的那一刻，扫过一道光，提示「好了，可以点了」。
 */

/** 实心的主按钮。 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    busy: Boolean = false,
    whyDisabled: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    DisabledReason(modifier, enabled && !busy, whyDisabled) { inner ->
        androidx.compose.material3.Button(
            onClick = { if (!busy) { haptics.tick(); onClick() } },
            modifier = inner.pressScale(press, 0.95f).clip(shape).readySheen(enabled && !busy),
            enabled = enabled,
            shape = shape,
            colors = colors,
            contentPadding = contentPadding,
            interactionSource = press,
        ) { BusyMark(busy); content() }
    }
}

/** 文字按钮（对话框的确定 / 取消、行尾的小操作）。 */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    busy: Boolean = false,
    whyDisabled: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    DisabledReason(modifier, enabled && !busy, whyDisabled) { inner ->
        androidx.compose.material3.TextButton(
            onClick = { if (!busy) { haptics.tick(); onClick() } },
            modifier = inner.pressScale(press, 0.94f),
            enabled = enabled,
            shape = shape,
            colors = colors,
            contentPadding = contentPadding,
            interactionSource = press,
        ) { BusyMark(busy); content() }
    }
}

/** 图标按钮：按下去缩得多一点（图标小，缩少了看不出来）；busy 时图标换成小圈。 */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    busy: Boolean = false,
    whyDisabled: String? = null,
    content: @Composable () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    DisabledReason(modifier, enabled && !busy, whyDisabled) { inner ->
        androidx.compose.material3.IconButton(
            onClick = { if (!busy) { haptics.tick(); onClick() } },
            modifier = inner.pressScale(press, 0.86f),
            enabled = enabled,
            colors = colors,
            interactionSource = press,
        ) {
            PopSwap(busy) { b -> if (b) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current) else content() }
        }
    }
}

/** busy 时在字前面转的小圈（跟着按钮的字色）。 */
@Composable
internal fun RowScope.BusyMark(busy: Boolean) {
    if (!busy) return
    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
    Spacer(Modifier.width(8.dp))
}

/**
 * 灰着的按钮上盖一层透明的点击区：点了晃一下、震一下、说为什么不能按。
 * 没给理由、或者能按的时候什么都不盖。
 */
@Composable
internal fun DisabledReason(modifier: Modifier, active: Boolean, why: String?, content: @Composable (inner: Modifier) -> Unit) {
    var shake by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    // 总是包一层 Box：能按 / 不能按来回变时按钮本身不重建（「好了」那道光要记得之前是灰的）。
    // 调用方的 modifier（weight、fillMaxWidth、padding……）给外面这层，propagateMinConstraints 让按钮跟着撑满
    Box(modifier, propagateMinConstraints = true) {
        content(Modifier.shake(shake))
        if (!active && why != null) Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            shake++
            haptics.reject()
            Toast.makeText(ctx, why, Toast.LENGTH_SHORT).show()
        })
    }
}

/**
 * 「可以点了」的一道光：从不能按变成能按（填好了、加载完了）时，斜着扫过一次。
 * 一直亮着的按钮不扫，免得满屏闪；系统关了动画也不扫。
 */
internal fun Modifier.readySheen(ready: Boolean): Modifier = composed {
    val off = reduceMotion()
    val t = remember { Animatable(-1f) }
    val was = remember { booleanArrayOf(ready) }
    LaunchedEffect(ready) {
        val becameReady = ready && !was[0]
        was[0] = ready
        if (!becameReady || off) return@LaunchedEffect
        t.snapTo(0f)
        t.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        t.snapTo(-1f)
    }
    drawWithContent {
        drawContent()
        val p = t.value
        if (p < 0f) return@drawWithContent
        val band = size.width * 0.45f
        val x = -band + (size.width + band * 2) * p
        drawRect(Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
            start = Offset(x - band / 2, 0f), end = Offset(x + band / 2, size.height),
        ))
    }
}

/** 一次性动作的进度：没在做 → 在做 → 好了（对勾停一会儿）/ 没成（变成「重试」）。 */
enum class ActPhase { IDLE, BUSY, DONE, FAILED }

/**
 * 点了要等结果的按钮（测试连接、读取、检查更新……）：自己管「在做 → 好了 / 重试」，不用每处再写一遍。
 * [run] 返回 null 算成功；返回一句话算失败，这句话弹出来、按钮变「重试」。
 */
@Composable
fun ActionButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    whyDisabled: String? = null,
    doneLabel: String = "好了",
    outlined: Boolean = true,
    run: suspend () -> String?,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    var phase by remember { mutableStateOf(ActPhase.IDLE) }
    var nudge by remember { mutableIntStateOf(0) }
    LaunchedEffect(phase) { if (phase == ActPhase.DONE) { delay(1800); phase = ActPhase.IDLE } }
    val go: () -> Unit = {
        phase = ActPhase.BUSY
        scope.launch {
            val err = runCatching { run() }.getOrElse { it.message ?: "出错了" }
            if (err == null) { phase = ActPhase.DONE; haptics.done() }
            else { phase = ActPhase.FAILED; nudge++; haptics.reject(); Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show() }
        }
    }
    val text: @Composable RowScope.() -> Unit = {
        PopSwap(phase) { p ->
            androidx.compose.material3.Text(when (p) {
                ActPhase.IDLE -> label
                ActPhase.BUSY -> "$label…"
                ActPhase.DONE -> "✓ $doneLabel"
                ActPhase.FAILED -> "重试"
            })
        }
    }
    val m = modifier.shake(nudge)
    if (outlined) OutlinedButton(onClick = go, modifier = m, enabled = enabled, busy = phase == ActPhase.BUSY, whyDisabled = whyDisabled, content = text)
    else Button(onClick = go, modifier = m, enabled = enabled, busy = phase == ActPhase.BUSY, whyDisabled = whyDisabled, content = text)
}
