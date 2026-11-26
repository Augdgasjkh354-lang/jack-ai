package com.jack.ai.ui

import com.jack.ai.api.webReady
import androidx.compose.foundation.border
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.collectAsState
import com.jack.ai.data.activeSkills
import com.jack.ai.data.readyMails
import com.jack.ai.data.ChatExport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.ai.ChatViewModel
import com.jack.ai.UiState
import com.jack.ai.data.Attachment
import com.jack.ai.data.Conversation
import com.jack.ai.data.Settings
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.endpoint
import com.jack.ai.data.modelChoices
import com.jack.ai.data.modelLabel
import com.jack.ai.data.nextAt
import kotlinx.coroutines.launch
import java.util.Calendar

// 新对话的空白页：问候、今日概览、建议入口（从 ChatScreen.kt 拆出来）。

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "早上好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

/** 问候下面那一行：今天还有几个提醒、任务，几个监控在盯着；什么都没有就是 null。 */
internal fun todayLine(routines: List<com.jack.ai.data.Routine>, now: Long = System.currentTimeMillis()): String? {
    val end = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
    }.timeInMillis
    val today = routines.filter { it.kind != "watch" }.count { r -> r.nextAt(now)?.let { it <= end } == true }
    val watching = routines.count { it.kind == "watch" && it.enabled && it.until > now }
    val parts = listOfNotNull("$today 个提醒和任务".takeIf { today > 0 }, "$watching 个监控在盯着".takeIf { watching > 0 })
    return if (parts.isEmpty()) null else "今天还有 " + parts.joinToString("，")
}

@Composable
internal fun EmptyState(
    vm: ChatViewModel,
    state: UiState,
    conv: Conversation?,
    onOpenMemory: () -> Unit,
    onOpenRoutines: () -> Unit = {},
) {
    val project = state.project(state.currentProjectId)
    val colors = MaterialTheme.colorScheme
    // 新对话的空白页轻轻浮上来，不是一下子蹦出来
    val still = reduceMotion()
    val enter = remember(conv?.id) { androidx.compose.animation.core.Animatable(if (still) 1f else 0f) }
    LaunchedEffect(conv?.id) { enter.animateTo(1f, androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 24.dp.toPx() }
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // 靠左的一句大问候，下面一行小字；入口是不带框的几行，沉在底部、离输入框近，单手好点
        Spacer(Modifier.height(56.dp))
        val name = state.settings.userName.trim()
        Text(
            greeting() + if (name.isNotEmpty()) "，$name" else "",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        // 「今天还有 2 个提醒」点一下就去看是哪两个
        val today = if (project == null) todayLine(state.routines) else null
        Text(
            if (project != null) "在项目「${project.name}」里，想做点什么？"
            else today?.let { "$it ›" } ?: "今天想聊点什么？",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = (if (today != null) Modifier.clip(Radii.s).clickable(onClickLabel = "看提醒和任务", onClick = onOpenRoutines) else Modifier)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        // 一大堆建议像弹幕一样从右往左慢慢飘，点哪条就把哪条发出去；Kavi 用问题卡片把要知道的问清楚
        if (state.settings.bizStarts) {
            androidx.compose.runtime.key(conv?.id) {
                Box(Modifier.appear(order = 2).layout { m, c ->
                    // 弹幕铺满屏幕宽，不受外面左右留白的限制
                    val extra = 40.dp.roundToPx()
                    val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
                    layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
                }) {
                    // 每次打开空白页都重新随机：固定的那些加上 Kavi 自己想的，打乱了分到几行
                    val ai by com.jack.ai.data.AiStarts.items.collectAsState()
                    val seed = remember(conv?.id) { kotlin.random.Random.nextInt() }
                    Danmaku(remember(seed, ai) { danmakuLanes(com.jack.ai.data.BizStarts.DRIFT + ai, seed) }) { b -> vm.send(b.prompt) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 把建议打乱、轮流分到几条弹幕轨道上；[seed] 一样排法就一样（换个新对话换一种）。 */
internal fun danmakuLanes(
    pool: List<com.jack.ai.data.BizStarts.Start>,
    seed: Int,
    lanes: Int = 3,
): List<List<com.jack.ai.data.BizStarts.Start>> {
    val shuffled = pool.shuffled(kotlin.random.Random(seed))
    return List(lanes) { lane -> shuffled.filterIndexed { i, _ -> i % lanes == lane } }.filter { it.isNotEmpty() }
}

/**
 * 弹幕：几条轨道各自从右往左匀速慢飘，速度、起点错开；手指按在上面时全部停住，好点准。
 * 点一下发出去；按住左右拖，那一行跟着手走，松手顺着甩的劲儿滑一段再接着飘。
 * 系统关了动画就不飘，每条轨道改成能左右划的一行。两头淡出，不是硬生生切断。
 */
@Composable
private fun Danmaku(lanes: List<List<com.jack.ai.data.BizStarts.Start>>, onPick: (com.jack.ai.data.BizStarts.Start) -> Unit) {
    val still = reduceMotion()
    var held by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    held = true
                    do { val e = awaitPointerEvent(PointerEventPass.Initial) } while (e.changes.any { it.pressed })
                    held = false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        lanes.forEachIndexed { i, lane ->
            // 每条轨道速度不一样（每秒 12～18dp），起点也错开，看着不像一排排齐步走
            DanmakuLane(lane, speed = 12f + (i * 5) % 7, start = 0.37f * i, still = still, held = { held }, onPick = onPick)
        }
    }
}

@Composable
private fun DanmakuLane(
    items: List<com.jack.ai.data.BizStarts.Start>,
    speed: Float,
    start: Float,
    still: Boolean,
    held: () -> Boolean,
    onPick: (com.jack.ai.data.BizStarts.Start) -> Unit,
) {
    if (still) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            items.forEach { DanmakuChip(it, 12.dp, onPick) }
        }
        return
    }
    // 同一串排两遍，飘过半程就无缝接回开头
    var half by remember { mutableFloatStateOf(0f) }
    var shift by remember { mutableFloatStateOf(0f) }
    var fling by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val speedPx = with(LocalDensity.current) { speed.dp.toPx() }
    LaunchedEffect(half) {
        if (half <= 0f) return@LaunchedEffect
        shift = (start % 1f) * half
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L && !dragging) {
                    val dt = (t - last) / 1e9f
                    // 松手后甩出去的速度慢慢衰减（大约半秒停下），再回到平常的飘速
                    val drift = if (held()) 0f else speedPx
                    shift = wrapShift(shift + (drift + fling) * dt, half)
                    fling *= kotlin.math.exp(-dt * 5f)
                    if (kotlin.math.abs(fling) < 1f) fling = 0f
                }
                last = t
            }
        }
    }
    var laneW by remember { mutableFloatStateOf(0f) }
    // 弹幕之间的空隙每次随机（12～36dp）；两遍用同一组，接缝处才对得上
    val gaps = remember(items) { items.map { (12 + kotlin.random.Random.nextInt(4) * 8).dp } }
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    Box(Modifier.fillMaxWidth().clipToBounds().onSizeChanged { laneW = it.width.toFloat() }
        .pointerInput(Unit) {
            // 左右拖这一行：超过手指抖动的距离才算拖，没超过还是点击
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { tracker.resetTracking(); dragging = true; fling = 0f },
                onDragEnd = { fling = -tracker.calculateVelocity().x.coerceIn(-4000f, 4000f); dragging = false },
                onDragCancel = { dragging = false },
            ) { change, dx ->
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                if (half > 0f) shift = wrapShift(shift - dx, half)
            }
        }) {
        Row(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                .onSizeChanged { half = it.width / 2f }
                .offset { IntOffset(-shift.roundToInt(), 0) },
        ) {
            repeat(2) { items.forEachIndexed { k, b -> EdgeFaded({ shift }, { laneW }, edge) { DanmakuChip(b, gaps[k], onPick) } } }
        }
    }
}

/** 让偏移量落在 [0, half) 里：往哪边拖都能无缝接上。 */
internal fun wrapShift(x: Float, half: Float): Float = if (half <= 0f) 0f else ((x % half) + half) % half

/** 弹幕飘到两头时慢慢变淡：按它中心离轨道两边多远算透明度，只重画不重排。 */
@Composable
private fun EdgeFaded(shift: () -> Float, laneW: () -> Float, edge: Float, content: @Composable () -> Unit) {
    var x by remember { mutableFloatStateOf(0f) }
    var w by remember { mutableFloatStateOf(0f) }
    Box(Modifier.onPlaced { x = it.positionInParent().x; w = it.size.width.toFloat() }
        .graphicsLayer { alpha = edgeAlpha(x - shift() + w / 2, laneW(), edge) }) { content() }
}

/** 离两边 [edge] 以内线性变淡，出了轨道就全透明。 */
internal fun edgeAlpha(center: Float, laneW: Float, edge: Float): Float =
    if (laneW <= 0f) 1f else (minOf(center, laneW - center) / edge).coerceIn(0f, 1f)

/**
 * 一条弹幕：统一的浅灰胶囊加一圈细边，不用一堆颜色抢眼；后面空多少由 [gap] 定（随机的），
 * 几行之间不会排成整齐的竖列。按下去微微缩一下。
 */
@Composable
private fun DanmakuChip(b: com.jack.ai.data.BizStarts.Start, gap: androidx.compose.ui.unit.Dp, onPick: (com.jack.ai.data.BizStarts.Start) -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val haptics = rememberHaptics()
    val colors = MaterialTheme.colorScheme
    Text(
        b.title,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurface.copy(alpha = 0.78f),
        maxLines = 1, softWrap = false,
        modifier = Modifier.padding(end = gap).pressScale(press, 0.94f).clip(CircleShape)
            .background(colors.onSurface.copy(alpha = if (isDarkSurface) 0.08f else 0.045f))
            .border(0.5.dp, colors.onSurface.copy(alpha = 0.08f), CircleShape)
            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current) { haptics.tick(); onPick(b) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
