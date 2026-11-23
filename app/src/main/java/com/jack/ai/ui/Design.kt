package com.jack.ai.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jack.ai.R
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * 全 App 共用的那套样子：圆角、分类色、代码字体、动效、触感。
 * 新写界面时从这里拿，不再各处写死数字和色值。
 */

/** 圆角只有这五档：小标签 8、小方块和按钮 12、提示条和图片 16、卡片和气泡 22、输入框、弹出菜单和底部面板 30。 */
object Radii {
    val xs = RoundedCornerShape(8.dp)
    val s = RoundedCornerShape(12.dp)
    val m = RoundedCornerShape(16.dp)
    val l = RoundedCornerShape(22.dp)
    val xl = RoundedCornerShape(30.dp)

    /** 聊天气泡：自己说的右上角收小，对方说的左上角收小。 */
    val bubbleMine = RoundedCornerShape(22.dp, 8.dp, 22.dp, 22.dp)
    val bubbleTheirs = RoundedCornerShape(8.dp, 22.dp, 22.dp, 22.dp)

    /** 从底下升起来的面板：只有上面两个角是圆的。 */
    val sheetTop = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
}

/** 现在是不是深色的底（记忆页那种自带夜色的也算）。 */
val isDarkSurface: Boolean
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * 分类用的颜色（工具图标、设置分组、状态点）：浅色模式用深一点的，深色模式用亮一点的，
 * 在两种底上都看得清。和强调色无关，换主题色时它们不变。
 */
enum class Hue(private val light: Long, private val dark: Long) {
    BLUE(0xFF2F6FEB, 0xFF6AA5FF),
    VIOLET(0xFF7C4DEB, 0xFFAB94FF),
    GREEN(0xFF1E9461, 0xFF4CC98A),
    ORANGE(0xFFE0561A, 0xFFFF9257),
    AMBER(0xFFD98200, 0xFFF2B544),
    RED(0xFFE5484D, 0xFFFF7A7F),
    SKY(0xFF0C8FD6, 0xFF4FC3F7),
    GRAY(0xFF6B7280, 0xFFA3A8B4);

    val color: Color
        @Composable @ReadOnlyComposable get() = Color(if (isDarkSurface) dark else light)

    /** 图标后面那块浅色底。 */
    val wash: Color
        @Composable @ReadOnlyComposable get() = color.copy(alpha = if (isDarkSurface) 0.2f else 0.12f)

    companion object {
        /** 老数据里存的是色值（比如常用开场的 tint）：按最接近的那一类取色。 */
        fun of(argb: Long): Hue = entries.minBy { h ->
            val a = Color(argb); val b = Color(h.light)
            val dr = a.red - b.red; val dg = a.green - b.green; val db = a.blue - b.blue
            dr * dr + dg * dg + db * db
        }
    }
}

/** 文件类型的标签色：白字压在上面，两种模式下都一样。 */
object FileColors {
    val word = Color(0xFF2B579A)
    val slides = Color(0xFFD24726)
    val sheet = Color(0xFF217346)
    val pdf = Color(0xFFB3261E)
    val image = Color(0xFF3F9499)
    val text = Color(0xFF737373)
    val code = Color(0xFF7B5BA6)
}

/** 代码用的等宽字体（JetBrains Mono，只带了英文和符号；中文自动落回系统字体）。 */
val CodeFont = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** 等宽数字：金额、时间、计数一位一位对齐，数字变的时候不左右晃。 */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

/** 动效的时长：小的状态变化、展开收起、整页切换。 */
object Motion {
    const val QUICK = 140
    const val NORMAL = 220
    const val PAGE = 280
}

/** 整页切换：进一页时新页从右边滑进来一小段、淡入，旧页稍稍退后；返回时反过来。 */
fun pageTransition(forward: Boolean): ContentTransform {
    val move = tween<IntOffset>(Motion.PAGE, easing = FastOutSlowInEasing)
    val fade = tween<Float>(Motion.PAGE)
    val quick = tween<Float>(Motion.QUICK)
    return if (forward) {
        (slideInHorizontally(move) { it / 6 } + fadeIn(fade)) togetherWith (slideOutHorizontally(move) { -it / 14 } + fadeOut(quick))
    } else {
        (slideInHorizontally(move) { -it / 14 } + fadeIn(fade)) togetherWith (slideOutHorizontally(move) { it / 6 } + fadeOut(quick))
    }
}

/** 按下去微微缩一下，松手弹回来。和 clickable 用同一个 interactionSource。 */
fun Modifier.pressScale(source: InteractionSource, to: Float = 0.96f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) to else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** 深色模式下给卡片描一道很淡的亮边，一层一层分得更清楚；浅色模式不画。 */
fun Modifier.hairline(shape: Shape): Modifier = composed {
    if (isDarkSurface) border(1.dp, Color.White.copy(alpha = 0.07f), shape) else this
}

/** 展开 / 收起的小箭头：转过去，不是换一个图标。 */
@Composable
fun Chevron(expanded: Boolean, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(Motion.NORMAL), label = "chevron")
    Icon(Icons.Default.KeyboardArrowDown, if (expanded) "收起" else "展开", modifier.graphicsLayer { rotationZ = turn }, tint = tint)
}

/**
 * Kavi 在忙的那颗「星」：一个带光晕的小点，慢慢地一明一暗。
 * 等回答、正在写的时候用它，不用转圈。
 */
@Composable
fun BreathingStar(modifier: Modifier = Modifier, size: Dp = 18.dp, color: Color = MaterialTheme.colorScheme.onSurface) {
    val t = rememberInfiniteTransition(label = "star")
    val beat by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "beat")
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.10f + 0.22f * beat), Color.Transparent), c, r), r, c)
        drawCircle(color.copy(alpha = 0.55f + 0.45f * beat), r * (0.26f + 0.10f * beat), c)
    }
}

/** 触感：跟着系统的「触摸振动」开关走，系统里关了就不震。 */
class Haptics(private val view: View) {
    /** 轻轻一下：发送、切换开关。 */
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }

    /** 做完了：回答写完。老系统没有这一种，用轻的那下代替。 */
    fun done() {
        view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/**
 * 全 App 的开关都走这一个：和系统的一样，只是拨动时轻轻震一下。
 * （同一个包里的这个 Switch 会盖过 material3 的，界面代码照常写 Switch(...) 就行。）
 */
@Composable
fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptics = rememberHaptics()
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange?.let { change -> { on -> haptics.tick(); change(on) } },
        modifier = modifier,
        enabled = enabled,
    )
}

/**
 * 毛玻璃。整屏会滚动的内容挂 [glassSource]，浮在它上面的东西（顶栏、输入框、圆按钮、标题胶囊）用 [glass]：
 * 后面的消息真的被模糊，再压一层半透明的灰（Android 12 起；更老的系统退成一层更不透明的灰）。
 * 没有可模糊的底时（设置页、侧边栏，或者自己就在可滚动内容里面），[glass] 退成一层很淡的半透明。
 */
val LocalGlass = androidx.compose.runtime.staticCompositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/** 玻璃片的颜色：浅色是带一点灰的白，深色是比纯黑亮几档的灰；再撒一点颗粒，像磨砂而不是糊开。 */
@Composable
fun glassStyle(): dev.chrisbanes.haze.HazeStyle {
    val dark = isDarkSurface
    val tint = if (dark) Color(0xFF2A2A2D) else Color(0xFFF2F2F4)
    return dev.chrisbanes.haze.HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.background,
        tint = dev.chrisbanes.haze.HazeTint(tint.copy(alpha = if (dark) 0.55f else 0.68f)),
        blurRadius = 20.dp,
        noiseFactor = 0.05f,
        fallbackTint = dev.chrisbanes.haze.HazeTint(tint.copy(alpha = 0.92f)),
    )
}

/** 顶栏、输入框后面那一整条：颜色和底色一样，只是半透明加模糊，消息滚进去是渐渐糊掉，不是一刀切掉。 */
@Composable
fun barStyle(): dev.chrisbanes.haze.HazeStyle {
    val bg = MaterialTheme.colorScheme.background
    return dev.chrisbanes.haze.HazeStyle(
        backgroundColor = bg,
        tint = dev.chrisbanes.haze.HazeTint(bg.copy(alpha = 0.72f)),
        blurRadius = 24.dp,
        noiseFactor = 0f,
        fallbackTint = dev.chrisbanes.haze.HazeTint(bg.copy(alpha = 0.94f)),
    )
}

/** 没有可模糊的底时，玻璃退成这层淡淡的半透明。 */
val glassFill: Color
    @Composable @ReadOnlyComposable get() = if (isDarkSurface) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.045f)

/** 玻璃的边：上沿亮、下沿淡，像光从上面打下来。 */
@Composable
@ReadOnlyComposable
fun glassEdge(): Brush = if (isDarkSurface) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f)))
    else Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.04f), Color.Black.copy(alpha = 0.09f)))

/** 会被模糊的那层内容。里面的东西自己不再取模糊（自己模糊自己会套娃），退成半透明。 */
@Composable
fun GlassSource(state: dev.chrisbanes.haze.HazeState, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(modifier.hazeSource(state)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalGlass provides null) { content() }
    }
}

/** 一片玻璃：裁成 [shape]、模糊后面的内容、描一道上亮下淡的边。 */
fun Modifier.glass(shape: Shape): Modifier = composed {
    val haze = LocalGlass.current
    val clipped = clip(shape)
    (if (haze != null) clipped.hazeEffect(haze, glassStyle()) else clipped.background(glassFill))
        .border(0.8.dp, glassEdge(), shape)
}

/**
 * 顶栏 / 底栏后面那条渐变的玻璃：靠屏幕边的一头最糊，往内容那头渐渐变清楚。
 * [top] 为 true 时是顶栏（上糊下清），否则是底栏（下糊上清）。
 */
fun Modifier.glassBar(top: Boolean): Modifier = composed {
    val haze = LocalGlass.current ?: return@composed this
    hazeEffect(haze, barStyle()) {
        progressive = dev.chrisbanes.haze.HazeProgressive.verticalGradient(
            startIntensity = if (top) 1f else 0f, endIntensity = if (top) 0f else 1f,
            easing = androidx.compose.animation.core.LinearEasing,
        )
    }
}

/** 浮在底色上面的那一层（圆按钮、胶囊、输入框、弹出菜单）用的颜色：深色是比纯黑亮一档的灰，浅色是很浅的灰。 */
val raisedColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.let { if (isDarkSurface) it.surfaceContainerHigh else it.surfaceContainer }

/**
 * 圆形的图标按钮：一片圆的毛玻璃；按下去缩一下、轻轻震一下。
 * 顶栏的菜单、新对话这类都用它。
 */
@Composable
fun CircleIconButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, size: Dp = 42.dp, iconSize: Dp = 22.dp,
                     enabled: Boolean = true, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(
        modifier.pressScale(press, 0.9f).size(size).glass(CircleShape)
            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled) { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

/** 弹出菜单：大圆角、比底色亮一档、深色模式带一道淡边。里面放 [MenuRow]。 */
@Composable
fun RoundMenu(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier.widthIn(min = 220.dp),
        shape = Radii.xl,
        containerColor = raisedColor,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = if (isDarkSurface) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)) else null,
        content = content,
    )
}

/**
 * 弹出菜单里的一行：左边一个圆底的图标，右边是字；选中的用强调色、末尾打勾。
 * 图标没有现成的就给一个字（比如 @）。
 */
@Composable
fun MenuRow(label: String, icon: ImageVector? = null, glyph: String? = null, sub: String? = null, checked: Boolean = false,
            enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val press = remember { MutableInteractionSource() }
    val ink = (if (danger) colors.error else if (checked) colors.primary else colors.onSurface).copy(alpha = if (enabled) 1f else 0.4f)
    Row(
        Modifier.fillMaxWidth().pressScale(press, 0.97f).heightIn(min = 54.dp)
            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(colors.onSurface.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = ink)
            else Text(glyph.orEmpty(), color = ink, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        if (checked) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Default.Check, "已选", Modifier.size(20.dp), tint = colors.primary)
        }
    }
}
