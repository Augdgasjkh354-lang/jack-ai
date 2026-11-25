package com.jack.ai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay

/*
 * 动效的公共零件。参照：
 * - Material 3 Expressive 的动效物理：位移用弹簧（快的带一点回弹），颜色、透明度这类「效果」用不回弹的硬弹簧；
 * - NN/g 的时长建议：小东西 100–200ms，大的界面变化 200–300ms，进场比退场稍慢；
 * - 系统设置里关了动画（开发者选项「动画时长缩放」为 0，或无障碍里的「移除动画」）就不做装饰性的动效，只留状态变化。
 */

/** 弹簧参数。spatial 管位置、大小（可以有一点回弹）；effects 管透明度、颜色（不回弹）。 */
object Springs {
    /** 小东西的位移、缩放：按钮、图标、勾。带一点回弹，显得「活」。 */
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)
    /** 一般大小的位移：卡片、面板。 */
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    /** 透明度、颜色：快、不回弹。 */
    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3800f)
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)
}

/** 系统关了动画（动画时长缩放为 0）：装饰性的动效都不做。 */
@Composable
@ReadOnlyComposable
fun reduceMotion(): Boolean = animationsOff(LocalContext.current)

fun animationsOff(ctx: android.content.Context): Boolean = runCatching {
    android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/**
 * 第一次出现时从下面浮上来一点、淡入。[order] 是第几个，一个接一个晚 40ms（最多晚 8 个），列表看着是「排着队」进来的。
 * 只在第一次组合时播；系统关了动画就直接显示。别挂在 LazyColumn 的项上（滑出去再滑回来会重播）。
 */
fun Modifier.appear(order: Int = 0, rise: Int = 12): Modifier = composed {
    val off = reduceMotion()
    val p = remember { Animatable(if (off) 1f else 0f) }
    val risePx = with(LocalDensity.current) { rise.dp.toPx() }
    LaunchedEffect(Unit) {
        if (p.value < 1f) {
            delay(40L * order.coerceIn(0, 8))
            p.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 420f))
        }
    }
    graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationY = (1f - p.value) * risePx
    }
}

/**
 * [trigger] 变了（且不是 null / 0）就左右晃两下：出错、不让做的时候用，和「摇头」一个意思。
 * 晃的幅度不大，衰减得快，不会晃得人眼晕；系统关了动画就不晃。
 */
fun Modifier.shake(trigger: Any?): Modifier = composed {
    val off = reduceMotion()
    val x = remember { Animatable(0f) }
    val px = with(LocalDensity.current) { 6.dp.toPx() }
    LaunchedEffect(trigger) {
        if (off || trigger == null || trigger == 0 || trigger == false) return@LaunchedEffect
        for (k in listOf(1f, -0.8f, 0.55f, -0.3f, 0.12f, 0f)) x.animateTo(k * px, tween(55))
    }
    graphicsLayer { translationX = x.value }
}

/**
 * 数字变化时上下滚一下：变大从下面顶上来，变小从上面落下去。计数、进度、版本号「2 / 3」这类用。
 */
@Composable
fun RollingText(value: Int, style: TextStyle, modifier: Modifier = Modifier,
                color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
                format: (Int) -> String = { it.toString() }) {
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(Springs.defaultSpatial()) { h -> if (up) h / 2 else -h / 2 } + fadeIn(Springs.fastEffects())) togetherWith
                (slideOutVertically(Springs.defaultSpatial()) { h -> if (up) -h / 2 else h / 2 } + fadeOut(Springs.fastEffects()))
        },
        label = "rolling",
    ) { v -> Text(format(v), style = style, color = color) }
}

/** 图标换一个（复制 → 对勾、空心 → 实心）：旧的缩小淡出，新的弹一下出来。 */
@Composable
fun <T> PopSwap(target: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            (scaleIn(Springs.fastSpatial(), initialScale = 0.4f) + fadeIn(Springs.fastEffects())) togetherWith
                (scaleOut(tween(90), targetScale = 0.4f) + fadeOut(tween(90)))
        },
        contentAlignment = androidx.compose.ui.Alignment.Center,
        label = "pop",
    ) { content(it) }
}

/** 点下去那一刻弹一下（比按住缩小更短促）：点赞、勾选、置顶这类「一下就完成」的动作。 */
fun Modifier.popOn(trigger: Any?): Modifier = composed {
    val off = reduceMotion()
    val s = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (off) return@LaunchedEffect
        s.snapTo(0.7f)
        s.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 600f))
    }
    graphicsLayer { scaleX = s.value; scaleY = s.value }
}


/**
 * 空状态：一个圆底图标、一行标题、一两行说明，居中。比一段灰字醒目，也不像出错。
 * [action] 给了就在下面放一个按钮。
 */
@Composable
fun EmptyNote(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, modifier: Modifier = Modifier,
              action: (@Composable () -> Unit)? = null) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxWidth().appear().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors.onSurface.copy(alpha = 0.06f)),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { androidx.compose.material3.Icon(icon, null, Modifier.size(26.dp), tint = colors.onSurfaceVariant) }
        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
        Text(body, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp)); action() }
    }
}


/** 不可见的「不许在这里断行」（WORD JOINER），显示时没有宽度。 */
private const val WJ = '⁠'

/**
 * 排版用：句尾最后 [tail] 个字粘在一起（不会只剩一两个字掉到下一行），「11月28日 08:01」这种日期时间整体不拆开。
 * 只用在显示上，别拿处理过的字去存或发给模型。
 */
fun tidyBreaks(s: String, tail: Int = 3): String {
    if (s.isEmpty()) return s
    val dated = Regex("""\d{1,2}月\d{1,2}日(\s\d{1,2}:\d{2})?|\d{1,2}:\d{2}""").replace(s) { m ->
        m.value.replace(' ', ' ').toList().joinToString(WJ.toString())
    }
    // 句尾：从最后往前数 tail 个可见字，中间插连接符
    val end = dated.trimEnd()
    var count = 0
    var i = end.length
    while (i > 0 && count < tail) {
        val c = end[i - 1]
        if (c.isWhitespace()) break
        if (c != WJ) count++
        i--
    }
    if (count < 2) return dated
    val glued = end.substring(i).filter { it != WJ }.toList().joinToString(WJ.toString())
    return end.substring(0, i) + glued + dated.substring(end.length)
}

/**
 * 「删除了…  撤销」这类提示条：和 App 一样的圆角胶囊，深色底白字，「撤销」用主题色；从下面弹上来。
 * 用法和 SnackbarHost 一样。
 */
@Composable
fun KaviSnackbarHost(state: androidx.compose.material3.SnackbarHostState, modifier: Modifier = Modifier) {
    androidx.compose.material3.SnackbarHost(state, modifier) { data ->
        val colors = androidx.compose.material3.MaterialTheme.colorScheme
        val dark = isDarkSurface
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = if (dark) colors.surfaceContainerHighest else androidx.compose.ui.graphics.Color(0xF2202124),
            shadowElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).appear(rise = 16),
        ) {
            androidx.compose.foundation.layout.Row(
                Modifier.padding(start = 20.dp, end = 6.dp).height(48.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(data.visuals.message, Modifier.weight(1f, fill = false).padding(end = 12.dp), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = if (dark) colors.onSurface else androidx.compose.ui.graphics.Color.White)
                data.visuals.actionLabel?.let { label ->
                    androidx.compose.material3.TextButton(onClick = { data.performAction() }) {
                        Text(label, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = if (dark) colors.primary else androidx.compose.ui.graphics.Color(0xFF8AB4F8))
                    }
                }
            }
        }
    }
}
