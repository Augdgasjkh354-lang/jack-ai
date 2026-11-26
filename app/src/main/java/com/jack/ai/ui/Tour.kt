package com.jack.ai.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 新手教学的「指哪」部分：一步一步把常用的地方「挖亮」，旁边一个气泡说明。分几套：
 * 聊天页（[CHAT]，填过 Key 以后问一次要不要看）；侧边栏、提醒和任务、项目页、设置各一套，第一次打开那里时自动带一遍（看过就不再带）。
 * 教「能拿 Kavi 做什么」的是新手任务页（[GuideScreen]）。
 * 页面上的按钮用 [tourTarget] 报自己的位置，没报上来的那一步直接跳过（比如底栏只剩聊天一格时）。
 */
object Tour {
    /** 各个目标在屏幕上的位置（根坐标）。控件离开界面时自己去掉，免得指到别的页面上留下的位置。 */
    val bounds = mutableStateMapOf<String, Rect>()
    /** 正在第几步；-1 是没在教。 */
    var step by mutableStateOf(-1)
    /** 正在教哪一套。 */
    var name by mutableStateOf(CHAT)
    /** 设置里点了「重新看」：回到聊天页就开始。 */
    var replay by mutableStateOf(false)

    const val CHAT = "chat"
    const val DRAWER = "drawer"
    const val ROUTINES = "routines"
    const val PROJECT = "project"
    const val SETTINGS = "settings"

    class Step(val target: String?, val title: String, val text: String)

    fun steps(tour: String): List<Step> = when (tour) {
        DRAWER -> DRAWER_STEPS
        ROUTINES -> ROUTINES_STEPS
        PROJECT -> PROJECT_STEPS
        SETTINGS -> SETTINGS_STEPS
        else -> STEPS
    }

    fun start(tour: String) { name = tour; step = 0 }

    /** 看过（走完、跳过都算）的那几套记在这里：页面上的不再自动带，新手任务页按它打勾。 */
    private fun prefs(ctx: android.content.Context) = ctx.applicationContext.getSharedPreferences("tour", android.content.Context.MODE_PRIVATE)
    fun seen(ctx: android.content.Context, tour: String) = prefs(ctx).getBoolean(tour, false)
    fun markSeen(ctx: android.content.Context, tour: String) { prefs(ctx).edit().putBoolean(tour, true).apply() }

    /**
     * 第一次打开某个页面时带一遍（[tour] 不是聊天页那套）：用户回答过「要不要看新手教学」才带（刚装好、还在填 Key 的不打扰），
     * 看过的、正在教别的的不带。等一下再开始，让页面上的控件先报好位置。
     */
    suspend fun autoStart(ctx: android.content.Context, tour: String, asked: Boolean) {
        if (!asked || seen(ctx, tour) || step >= 0) return
        kotlinx.coroutines.delay(700)
        if (step >= 0 || seen(ctx, tour)) return
        if (steps(tour).any { it.target != null && bounds[it.target] != null }) start(tour)
    }

    val DRAWER_STEPS = listOf(
        Step("d-pages", "常用的页面", "资料库、记忆、提醒和任务、文件这些页面都在这里。长按拖动能排顺序，拖到上面那组就放进底栏。"),
        Step("d-projects", "项目", "一件长期的事（一本书、一个客户、一门课）建一个项目：里面的对话共用资料、指令和记忆。"),
        Step("d-search", "搜对话", "按字搜所有对话的内容，点结果直接跳到那句话。"),
        Step("d-settings", "设置", "模型和 Key、外观、记忆、要连的服务都在这里。设置首页能搜，找某个开关不用一页页翻。"),
    )
    val ROUTINES_STEPS = listOf(
        Step("r-filter", "三种", "提醒到点弹通知；定时任务到点让 Kavi 在后台做事，结果发给你；监控隔一会儿查一次，满足条件才告诉你。"),
        Step("r-add", "自己加", "点这里加提醒或定时任务。更省事的是直接在聊天里说：「每天早上 8 点给我发天气」「降价了告诉我」。"),
        Step("r-quiet", "免打扰", "睡觉时段里，定时任务和监控的结果静音送到，你自己设的提醒照常响。"),
        Step("r-workflows", "工作流", "要好几步连着做的（查数据 → 判断 → 发通知），做成工作流，到点自己跑完。"),
    )
    val PROJECT_STEPS = listOf(
        Step("p-tabs", "项目里有什么", "聊天是这个项目的所有对话；资料是每个对话都能用的背景材料；记忆是 Kavi 在这个项目里记下的事。"),
        Step("p-menu", "项目指令", "在菜单里「编辑指令」：写下这个项目一直要遵守的要求（风格、字数、主角叫什么），每个对话都会带上。"),
        Step("p-input", "在项目里开新对话", "在这里直接说，开出来的对话就在这个项目里，资料和指令都带着。"),
    )
    val SETTINGS_STEPS = listOf(
        Step("s-search", "搜设置", "想改什么直接搜：字体、深色、备份、余额……"),
        Step("s-model", "模型和 Key", "在这里换默认模型、加别家的模型服务。用 ChatGPT 账号登录的也在这里。"),
    )

    val STEPS = listOf(
        Step("input", "在这里问 Kavi", "想做什么直接说。点右边的话筒是语音输入，长按话筒是语音对话：说完自动发、读回答、接着听。"),
        Step("plus", "加图片和文件", "点「＋」发照片、文件、拍照，或者点名用某个技能（写周报、做 PPT……）。"),
        Step("model", "换模型", "点标题这块换模型和思考深度。难的问题用深度思考，日常聊天用快的省钱。"),
        Step("newchat", "开新对话", "换个话题就开一个新的，旧的都在侧边栏里。"),
        Step("menu", "侧边栏", "所有对话、项目、搜索都在这里，从左边往右滑也能打开。顶上的页面长按拖动，能决定底栏放哪几个。"),
        Step("tabs", "底栏", "常用的页面一点就到。按住底栏左右滑，能快速换页。"),
        Step(null, "就这些", "剩下的边用边发现。不知道 Kavi 能干什么，就直接问它。"),
    )
}

/** 让新手教学能指到这个控件。控件离开界面时把位置去掉（列表滚出去、换了页面）。 */
fun Modifier.tourTarget(key: String): Modifier = composed {
    androidx.compose.runtime.DisposableEffect(key) { onDispose { Tour.bounds.remove(key) } }
    onGloballyPositioned { Tour.bounds[key] = it.boundsInRoot() }
}

/** 填过 DeepSeek Key 以后问一次：要不要看新手教学。 */
@Composable
fun TourAsk(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        icon = { LogoMark(40.dp) },
        title = { Text("认识一下 Kavi？") },
        text = {
            Text("花一分钟看看几个常用的地方：输入框、换模型、侧边栏、底栏。之前用过也可以看看，有些是新加的。\n\n以后想看，在 设置 → 高级 里也能打开。",
                style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("好，带我看看") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("不用了") } },
    )
}

/**
 * 盖在聊天页上的教学层：一层暗色，要介绍的那块挖空（带一圈慢慢呼吸的亮边），说明气泡放在它上面或下面。
 * 挖空的框在两步之间弹着滑过去。点挖空的地方也算「下一步」，点暗处不动。
 */
@Composable
fun TourOverlay(tour: String = Tour.CHAT, onTry: () -> Unit = {}) {
    val i = Tour.step
    if (i < 0 || Tour.name != tour) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 找得到位置的步骤才走；最后那句总结没有目标
    val steps = Tour.steps(tour).filter { it.target == null || Tour.bounds[it.target] != null }
    if (steps.isEmpty()) { Tour.step = -1; return }
    androidx.compose.runtime.DisposableEffect(tour) { onDispose { if (Tour.name == tour) Tour.markSeen(ctx, tour) } }
    val at = i.coerceIn(0, steps.lastIndex)
    val s = steps[at]
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val pad = with(density) { 8.dp.toPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    fun end() { Tour.markSeen(ctx, tour); Tour.step = -1 }
    fun go(to: Int) {
        haptics.select()
        if (to > steps.lastIndex) end() else Tour.step = to.coerceAtLeast(0)
    }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val target = s.target?.let { Tour.bounds[it] }?.translate(-origin)?.inflate(pad)
        // 没有目标（总结那步）：挖空缩成屏幕中间一个点
        val goal = target ?: Rect(Offset(w / 2, h / 2), Size.Zero)
        val box = remember { Animatable(goal, Rect.VectorConverter) }
        LaunchedEffect(goal) { box.animateTo(goal, Springs.defaultSpatial()) }
        val glow by rememberInfiniteTransition(label = "tour").animateFloat(0.35f, 0.85f,
            infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "glow")
        val still = reduceMotion()
        // 暗层 + 挖空：画一个「整屏减去那个圆角框」的形状（奇偶填充），不用擦除，哪种绘制方式下面的按钮都透得出来
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(goal) {
                    detectTapGestures { p -> if (target != null && target.contains(p)) go(at + 1) }
                },
        ) {
            val r = box.value
            val corner = CornerRadius(minOf(r.height, r.width) / 2f, minOf(r.height, r.width) / 2f)
            val shade = androidx.compose.ui.graphics.Path().apply {
                fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                if (r.width > 1f) addRoundRect(androidx.compose.ui.geometry.RoundRect(r, corner))
            }
            drawPath(shade, Color.Black.copy(alpha = 0.62f))
            if (r.width > 1f) drawRoundRect(Color.White.copy(alpha = if (still) 0.6f else glow), r.topLeft, r.size, corner, style = Stroke(2.dp.toPx()))
        }
        // 说明气泡：目标在下半屏就放在它上面，在上半屏就放在下面；没有目标就放中间
        val bubbleW = minOf(maxWidth - 32.dp, 380.dp)
        var bubbleH by remember { mutableIntStateOf(0) }
        val gap = with(density) { 14.dp.toPx() }
        val y = when {
            target == null -> (h - bubbleH) / 2f
            target.center.y > h / 2 -> target.top - gap - bubbleH
            else -> target.bottom + gap
        }.coerceIn(with(density) { 24.dp.toPx() }, (h - bubbleH - with(density) { 24.dp.toPx() }).coerceAtLeast(0f))
        val x = (w - with(density) { bubbleW.toPx() }) / 2f
        androidx.compose.runtime.key(at) {
            Surface(
                // 深色模式下暗层和纯黑的面板分不开：气泡用亮一档的底、描一道细边
                shape = Radii.xl, color = if (isDarkSurface) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp, border = if (isDarkSurface) androidx.compose.foundation.BorderStroke(0.8.dp, glassEdge()) else null,
                modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(bubbleW)
                    .onGloballyPositioned { bubbleH = it.size.height }.appear(rise = 10),
            ) {
                Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${at + 1}/${steps.size}", style = MaterialTheme.typography.labelMedium.tabular,
                            color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(tidyBreaks(s.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp))
                    // 进度：一排小点，走过的是实心
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        steps.indices.forEach { k ->
                            Box(Modifier.size(if (k == at) 14.dp else 6.dp, 6.dp).clip(CircleShape)
                                .background(if (k <= at) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.target != null) TextButton(onClick = { end() }) { Text("跳过", color = MaterialTheme.colorScheme.outline) }
                        Spacer(Modifier.weight(1f))
                        if (at > 0) TextButton(onClick = { go(at - 1) }) { Text("上一步") }
                        if (s.target == null) {
                            TextButton(onClick = { end() }) { Text("完成") }
                            TextButton(onClick = { end(); onTry() }) { Text("试着问一句", fontWeight = FontWeight.SemiBold) }
                        } else if (at == steps.lastIndex) TextButton(onClick = { end() }) { Text("知道了", fontWeight = FontWeight.SemiBold) }
                        else TextButton(onClick = { go(at + 1) }) { Text("下一步", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

