package com.jack.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.ai.data.ThemeMode

// 暖白底 + 陶土色点缀，安静耐看
private val LightColors = lightColorScheme(
    primary = Color(0xFFC4673F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E2D7),
    onPrimaryContainer = Color(0xFF5A2610),
    secondary = Color(0xFF7A6E62),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFE9E1),
    onSecondaryContainer = Color(0xFF2B2722),
    tertiary = Color(0xFF5F7A6B),
    background = Color(0xFFFAF8F5),
    onBackground = Color(0xFF2B2722),
    surface = Color(0xFFFAF8F5),
    onSurface = Color(0xFF2B2722),
    surfaceVariant = Color(0xFFF0EBE4),
    onSurfaceVariant = Color(0xFF6E665D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F3EF),
    surfaceContainer = Color(0xFFF3EFEA),
    surfaceContainerHigh = Color(0xFFF0EBE5),
    surfaceContainerHighest = Color(0xFFEDE8E1),
    outline = Color(0xFFA69E94),
    outlineVariant = Color(0xFFE5DFD7),
    inverseSurface = Color(0xFF34302B),
    inverseOnSurface = Color(0xFFF5F0EA),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE59069),
    onPrimary = Color(0xFF3A1606),
    primaryContainer = Color(0xFF55301F),
    onPrimaryContainer = Color(0xFFFADBCB),
    secondary = Color(0xFFBFB4A8),
    onSecondary = Color(0xFF2B2722),
    secondaryContainer = Color(0xFF35322E),
    onSecondaryContainer = Color(0xFFECE7E1),
    tertiary = Color(0xFF9DBBA9),
    background = Color(0xFF1C1B19),
    onBackground = Color(0xFFECE7E1),
    surface = Color(0xFF1C1B19),
    onSurface = Color(0xFFECE7E1),
    surfaceVariant = Color(0xFF2C2A27),
    onSurfaceVariant = Color(0xFFB3ABA1),
    surfaceContainerLowest = Color(0xFF171614),
    surfaceContainerLow = Color(0xFF222120),
    surfaceContainer = Color(0xFF262523),
    surfaceContainerHigh = Color(0xFF2B2927),
    surfaceContainerHighest = Color(0xFF302E2B),
    outline = Color(0xFF7D766D),
    outlineVariant = Color(0xFF3A3734),
    inverseSurface = Color(0xFFECE7E1),
    inverseOnSurface = Color(0xFF2B2722),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = t.bodyMedium.copy(lineHeight = 22.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** 可选主题色：名字、浅色模式 (主色, 浅底, 深字)、深色模式 (主色, 深底, 浅字)。 */
data class Accent(val id: String, val label: String, val light: Triple<Long, Long, Long>, val dark: Triple<Long, Long, Long>)

val ACCENTS = listOf(
    Accent("clay", "陶土", Triple(0xFFC4673F, 0xFFF6E2D7, 0xFF5A2610), Triple(0xFFE59069, 0xFF55301F, 0xFFFADBCB)),
    Accent("ocean", "海蓝", Triple(0xFF2F6FB0, 0xFFDCE8F5, 0xFF0F3157), Triple(0xFF8DB8E8, 0xFF1F3B5C, 0xFFD6E6F7)),
    Accent("pine", "松绿", Triple(0xFF3E7D5E, 0xFFDCEDE3, 0xFF143826), Triple(0xFF8CC4A5, 0xFF1F4231, 0xFFD3EDDF)),
    Accent("plum", "紫藤", Triple(0xFF7B5BA6, 0xFFEAE1F4, 0xFF331F4D), Triple(0xFFC0A6E3, 0xFF3D2E55, 0xFFEDE3F8)),
    Accent("rose", "玫瑰", Triple(0xFFB5506A, 0xFFF6DEE4, 0xFF4F1424), Triple(0xFFE893A8, 0xFF5A2734, 0xFFF9DCE3)),
    Accent("ink", "墨黑", Triple(0xFF3A3632, 0xFFE8E3DC, 0xFF1C1A17), Triple(0xFFD9D2C8, 0xFF3A3632, 0xFFF2EEE8)),
)

@Composable
fun JackTheme(mode: ThemeMode, accent: String = "clay", fontScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val a = ACCENTS.firstOrNull { it.id == accent } ?: ACCENTS.first()
    val (p, c, on) = if (dark) a.dark else a.light
    val base = if (dark) DarkColors else LightColors
    val colors = base.copy(
        primary = Color(p),
        onPrimary = if (dark) Color(0xFF1C1B19) else Color.White,
        primaryContainer = Color(c),
        onPrimaryContainer = Color(on),
    )
    val density = LocalDensity.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val links = androidx.compose.runtime.remember(ctx) { JackUriHandler(ctx) }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
        androidx.compose.ui.platform.LocalUriHandler provides links,
    ) {
        MaterialTheme(
            colorScheme = colors,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
