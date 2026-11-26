package com.jack.ai.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 底部标签栏能放的页面。chat 一直在第一个、拿不掉；其余的在侧边栏里长按拖进「底栏」或拖出去。
 * id 存在设置里（Settings.tabBar），所以别改名。
 */
enum class KaviTab(val id: String, val label: String, val icon: ImageVector) {
    CHAT("chat", "聊天", AppIcons.Chat),
    LIBRARY("library", "资料库", AppIcons.Folder),
    MEMORY("memory", "记忆", AppIcons.Moon),
    BROWSER("browser", "浏览器", AppIcons.Globe),
    ROUTINES("routines", "提醒和任务", Icons.Default.Notifications),
    FILES("files", "文件库", AppIcons.File),
    SKILLS("skills", "技能", Icons.Default.Build),
    APPS("apps", "小工具", AppIcons.Code),
    GAMES("games", "游戏", AppIcons.Game),
    REVIEW("review", "复习卡片", Icons.Default.Star),
    TABLES("tables", "表格", Icons.AutoMirrored.Filled.List),
    DOWNLOADS("downloads", "下载", AppIcons.Download);

    companion object {
        /** 底栏最多几格（含聊天）：再多手机上就挤了。 */
        const val MAX = 5
        val DEFAULT = listOf("library", "memory", "browser", "routines")

        fun of(id: String) = entries.firstOrNull { it.id == id }

        /** 这个版本有的（正式版没有浏览器）。 */
        fun available(t: KaviTab) = t != BROWSER || com.jack.ai.Edition.browser

        /** 底栏上的：聊天在前，后面是设置里存的（空的就用默认），去重、去掉这个版本没有的，最多 [MAX] 格。 */
        fun bar(saved: List<String>?): List<KaviTab> {
            val ids = saved ?: DEFAULT
            return (listOf(CHAT) + ids.mapNotNull { of(it) }.filter { it != CHAT && available(it) }.distinct()).take(MAX)
        }

        /** 没放进底栏的。 */
        fun others(saved: List<String>?): List<KaviTab> {
            val on = bar(saved).toSet()
            return entries.filter { it !in on && available(it) }
        }
    }
}

/** 记住上一次底栏选中的是第几格：换页时新页面的底栏从那里「流」过来，而不是凭空出现在新位置。 */
internal object TabMemory { var last = 0 }

/**
 * 底部标签栏：一条玻璃胶囊，格子不多时缩成小胶囊居中（只有两格就不撑满整行）。
 * 选中的那块像一滴水：换格时弹着滑过去，滑的途中横向拉长、纵向压扁一点，到了再回弹成圆的。
 */
@Composable
fun LiquidTabBar(items: List<TabItem>, selected: Int, modifier: Modifier = Modifier, tourKey: String? = null) {
    if (items.size < 2) return
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // 一格最宽 76dp：五格差不多铺满，两三格就是中间一个小胶囊
        val cell: Dp = minOf((maxWidth - 8.dp) / items.size, 76.dp)
        val cellPx = with(density) { cell.toPx() }
        val target = selected.coerceIn(0, items.lastIndex) * cellPx
        val start = TabMemory.last.coerceIn(0, items.lastIndex) * cellPx
        val still = reduceMotion()
        val x = remember { Animatable(if (still) target else start) }
        LaunchedEffect(target) {
            TabMemory.last = selected
            x.animateTo(target, spring(dampingRatio = 0.62f, stiffness = 420f))
        }
        // 长按底栏再左右滑：那块玻璃跟着手指走（放大一点，像放大镜），滑过一格震一下，松手就换到手指下那一页
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var scrubbing by remember { mutableStateOf(false) }
        var hover by remember { androidx.compose.runtime.mutableIntStateOf(selected) }
        var finger by remember { mutableFloatStateOf(0f) }
        val lift by animateFloatAsState(if (scrubbing) 1f else 0f, Springs.fastSpatial(), label = "lens")
        fun cellAt(px: Float) = (px / cellPx).toInt().coerceIn(0, items.lastIndex)
        fun endScrub() {
            if (!scrubbing) return
            scrubbing = false
            val i = hover
            TabMemory.last = i
            scope.launch { x.animateTo(i * cellPx, spring(dampingRatio = 0.62f, stiffness = 420f)) }
            if (i != selected) items[i].onClick()
        }
        fun follow(px: Float) { scope.launch { x.snapTo((px - cellPx / 2f).coerceIn(0f, items.lastIndex * cellPx)) } }
        // 离目标越远拉得越长：最多宽出 45%、矮 14%（手指拖着时不拉，跟着手指走）
        val stretch = if (scrubbing) 0f else (abs(x.value - target) / cellPx).coerceIn(0f, 1f)
        val lensW = cell * (1f + 0.45f * stretch)
        Box(Modifier.width(cell * items.size + 8.dp).then(if (tourKey != null) Modifier.tourTarget(tourKey) else Modifier).glass(CircleShape).padding(4.dp)
            .pointerInput(items.size, cellPx, selected) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o ->
                        haptics.longPress(); scrubbing = true
                        finger = o.x; hover = cellAt(finger)
                        scope.launch { x.animateTo((finger - cellPx / 2f).coerceIn(0f, items.lastIndex * cellPx), Springs.fastSpatial()) }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        finger += amount.x
                        follow(finger)
                        val h = cellAt(finger)
                        if (h != hover) { hover = h; haptics.select() }
                    },
                    onDragEnd = { endScrub() },
                    // 长按不动就松手时，松手那一下被格子自己的「点」先收走了，这边收到的是取消：照样按松手处理
                    onDragCancel = { endScrub() },
                )
            }) {
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((x.value - (lensW.toPx() - cellPx) / 2f).roundToInt(), 0) }
                    .width(lensW).height(46.dp)
                    .graphicsLayer {
                        scaleY = (1f - 0.14f * stretch) * (1f + 0.14f * lift)
                        scaleX = 1f + 0.14f * lift
                        alpha = 1f - 0.25f * lift
                    }
                    .shadow(if (isDarkSurface) 0.dp else 3.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(if (isDarkSurface) Color.White.copy(alpha = 0.16f) else Color.White)
                    .hairline(CircleShape),
            )
            Row {
                items.forEachIndexed { i, t ->
                    val press = remember { MutableInteractionSource() }
                    val on = if (scrubbing) i == hover else i == selected
                    val ink by androidx.compose.animation.animateColorAsState(if (on) colors.onSurface else colors.onSurfaceVariant,
                        Springs.defaultEffects(), label = "tabInk")
                    Box(
                        Modifier.width(cell).height(46.dp).pressScale(press, 0.86f).clip(CircleShape)
                            .clickable(interactionSource = press, indication = null, onClickLabel = t.label) {
                                // 长按滑动松手时这里也会收到一次「点」：那次交给滑动处理，不重复打开
                                if (scrubbing) return@clickable
                                if (i == selected) t.onClick() else { haptics.select(); t.onClick() }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(t.icon, t.label, Modifier.size(22.dp), tint = ink) }
                }
            }
        }
    }
}

/**
 * 侧边栏顶上那块：「底栏」和「其他」两组。长按一行拖动，可以排顺序，也能拖过分界线放进或拿出底栏；
 * 底栏满了（[KaviTab.MAX] 格）再放进来，最后一个被挤到「其他」。点一下照常打开。
 */
@Composable
internal fun TabArranger(
    saved: List<String>?,
    badge: (KaviTab) -> String? = { null },
    dot: (KaviTab) -> Boolean = { false },
    onOpen: (KaviTab) -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val rowH = 48.dp
    val rowPx = with(density) { rowH.toPx() }
    // 一个格子一个位置：底栏的（不含聊天）、分界线、其他的。分界线也占一格，拖过它就换组
    val bar = KaviTab.bar(saved).drop(1)
    val rest = KaviTab.others(saved)
    val slots: List<KaviTab?> = bar + listOf(null) + rest
    var dragging by remember { mutableStateOf<KaviTab?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    val from = slots.indexOf(dragging)
    val to = if (dragging == null) -1 else (from + (dy / rowPx).roundToInt()).coerceIn(0, slots.lastIndex)
    // 拖到哪一格换了：轻轻震一下
    LaunchedEffect(to) { if (dragging != null && to >= 0) haptics.select() }

    // 拖手势在 pointerInput 里，闭包是旧的：位置都从状态现算，不用组合时算好的 from / to
    fun commit() {
        val d = dragging ?: return
        val from = slots.indexOf(d)
        val to = (from + (dy / rowPx).roundToInt()).coerceIn(0, slots.lastIndex)
        if (from < 0 || to == from) return
        val list = slots.toMutableList()
        list.removeAt(from)
        list.add(to, d)
        val div = list.indexOf(null)
        var newBar = list.subList(0, div).filterNotNull()
        var newRest = list.subList(div + 1, list.size).filterNotNull()
        if (newBar.size > KaviTab.MAX - 1) {
            newRest = newBar.drop(KaviTab.MAX - 1) + newRest
            newBar = newBar.take(KaviTab.MAX - 1)
        }
        onSave(newBar.map { it.id })
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 28.dp, end = 16.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("底栏", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Text("长按拖动", style = MaterialTheme.typography.labelSmall, color = colors.outline)
        }
        // 聊天固定在第一个
        TabRow(KaviTab.CHAT, null, false, Modifier.height(rowH), onClick = { onOpen(KaviTab.CHAT) })
        Box(Modifier.fillMaxWidth()) {
            slots.forEachIndexed { i, t ->
                // 别的格子给拖着的那个让位：在 from 和 to 之间的整体挪一格
                val shift = when {
                    dragging == null || i == from -> 0f
                    from < to && i in (from + 1)..to -> -rowPx
                    from > to && i in to until from -> rowPx
                    else -> 0f
                }
                val moved by animateFloatAsState(shift, Springs.defaultSpatial(), label = "slot")
                val isDragged = t != null && t == dragging
                val lift by animateFloatAsState(if (isDragged) 1f else 0f, Springs.fastSpatial(), label = "lift")
                val rowMod = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(0, (i * rowPx + if (isDragged) dy else moved).roundToInt()) }
                    .zIndex(if (isDragged) 1f else 0f)
                    .height(rowH)
                    .graphicsLayer { scaleX = 1f + 0.03f * lift; scaleY = 1f + 0.03f * lift }
                if (t == null) {
                    Row(rowMod.fillMaxWidth().padding(start = 28.dp, end = 16.dp), verticalAlignment = Alignment.Bottom) {
                        Text("其他", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                } else {
                    TabRow(t, badge(t), dot(t), rowMod
                        .shadow((6f * lift).dp, Radii.m, clip = false)
                        .pointerInput(t, slots) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { haptics.longPress(); dragging = t; dy = 0f },
                                onDrag = { change, amount -> change.consume(); dy += amount.y },
                                onDragEnd = { commit(); dragging = null; dy = 0f },
                                onDragCancel = { dragging = null; dy = 0f },
                            )
                        }, lifted = lift > 0.01f, onClick = { onOpen(t) })
                }
            }
            // 撑起高度（子项都是按偏移摆的）
            Spacer(Modifier.height(rowH * slots.size))
        }
    }
}

/** 侧边栏里的一行：图标、名字，有待办的右边标数字或小点。 */
@Composable
private fun TabRow(t: KaviTab, badge: String?, dot: Boolean, modifier: Modifier, lifted: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(Radii.m)
            .background(if (lifted) colors.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Icon(t.icon, null, Modifier.size(24.dp), tint = colors.onSurface)
        Spacer(Modifier.size(14.dp))
        Text(t.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        when {
            badge != null -> Text(badge, style = MaterialTheme.typography.labelMedium, color = colors.onPrimary, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(CircleShape).background(colors.primary).padding(horizontal = 8.dp, vertical = 1.dp))
            dot -> Box(Modifier.size(8.dp).clip(CircleShape).background(colors.primary))
        }
        if (lifted) Icon(Icons.Default.Menu, null, Modifier.padding(start = 8.dp).size(18.dp), tint = colors.onSurfaceVariant)
    }
}
