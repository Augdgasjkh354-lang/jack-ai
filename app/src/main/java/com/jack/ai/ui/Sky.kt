package com.jack.ai.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import java.util.Calendar

/**
 * Kavi 的那片天：记忆页是整页的夜空，新对话的空白页顶上也露一角——
 * 右上角一轮光（白天是太阳的暖光，夜里是月光），深色模式下再撒几颗星。
 */

/** 星星的位置是固定的（每次打开一样）：x、y 在 0..1 之间，第三个数是大小。 */
private val STARS: List<Triple<Float, Float, Float>> = run {
    val r = java.util.Random(27)
    List(46) { Triple(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.6f) }
}

/** 稀稀落落的星星：越靠上越亮、越密。 */
internal fun DrawScope.drawStars(color: Color = Color.White, strength: Float = 1f) {
    STARS.forEach { (x, y, rad) ->
        drawCircle(color.copy(alpha = (0.25f + (1 - y) * 0.45f) * strength), rad.dp.toPx() / 2 + 0.5f, Offset(x * size.width, y * y * size.height))
    }
}

/** 右上角的一轮光。 */
internal fun DrawScope.drawHalo(color: Color, alpha: Float) {
    val c = Offset(size.width * 0.82f, 70.dp.toPx())
    val r = 120.dp.toPx()
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = c, radius = r), radius = r, center = c)
}

/** 记忆页的星空和月光。 */
@Composable
fun NightStars(modifier: Modifier) {
    Canvas(modifier) {
        drawStars()
        drawHalo(Color(0xFFF2D38A), 0.2f)
    }
}

/** 这个钟点天上那轮光的颜色：清晨偏暖、白天偏金、傍晚偏紫、夜里是月亮的淡金。 */
internal fun skyGlow(hour: Int): Long = when (hour) {
    in 5..7 -> 0xFFFF9E6B
    in 8..16 -> 0xFFFFC857
    in 17..19 -> 0xFFB58CFF
    else -> 0xFFF2D38A
}

/**
 * 空白页顶上的那一角天：跟着钟点换颜色的一轮光；深色模式下有星星。
 * 进来时慢慢亮起来，之后是静止的（不一直动，省电）。
 */
@Composable
fun SkyBackdrop(modifier: Modifier, key: Any? = Unit) {
    val dark = isDarkSurface
    val ink = MaterialTheme.colorScheme.onSurface
    val glow = remember(key) { Color(skyGlow(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) }
    val appear = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { appear.animateTo(1f, tween(900)) }
    Canvas(modifier) {
        if (dark) drawStars(ink, strength = 0.7f * appear.value)
        drawHalo(glow, (if (dark) 0.16f else 0.22f) * appear.value)
    }
}
