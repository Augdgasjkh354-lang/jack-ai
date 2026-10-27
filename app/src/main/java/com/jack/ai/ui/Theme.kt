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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.ai.data.ThemeMode

// 纯白 / 纯黑底，灰阶分层，颜色只留给强调色（默认「黑白」：浅色用黑、深色用白）
private val LightColors = lightColorScheme(
    primary = Color(0xFF0A0A0A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEDED),
    onPrimaryContainer = Color(0xFF0A0A0A),
    secondary = Color(0xFF5C5C5C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F1F1),
    onSecondaryContainer = Color(0xFF0A0A0A),
    tertiary = Color(0xFF5C5C5C),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF3F3F3),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE6E6E6),
    outline = Color(0xFFA3A3A3),
    outlineVariant = Color(0xFFE5E5E5),
    inverseSurface = Color(0xFF1A1A1A),
    inverseOnSurface = Color(0xFFF5F5F5),
    error = Color(0xFFD92D20),
    errorContainer = Color(0xFFFDE7E5),
    onErrorContainer = Color(0xFF5C0F0A),
)

// 深色模式不用纯黑：带一点蓝的深灰，卡片一层层亮一点（参考深色玻璃那套），强调色照旧是白
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF262B36),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFA3A8B4),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1B1F28),
    onSecondaryContainer = Color(0xFFF0F2F5),
    tertiary = Color(0xFFA3A8B4),
    background = Color(0xFF0C0E13),
    onBackground = Color(0xFFF0F2F5),
    surface = Color(0xFF0C0E13),
    onSurface = Color(0xFFF0F2F5),
    surfaceVariant = Color(0xFF1B1F28),
    onSurfaceVariant = Color(0xFF9EA4B0),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF12151C),
    surfaceContainer = Color(0xFF171A22),
    surfaceContainerHigh = Color(0xFF1D212B),
    surfaceContainerHighest = Color(0xFF242936),
    outline = Color(0xFF5E6472),
    outlineVariant = Color(0xFF262B36),
    inverseSurface = Color(0xFFF0F2F5),
    inverseOnSurface = Color(0xFF0C0E13),
    error = Color(0xFFFF6B5E),
    errorContainer = Color(0xFF3D0F0B),
    onErrorContainer = Color(0xFFFFDAD5),
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
    Accent("mono", "黑白", Triple(0xFF0A0A0A, 0xFFEDEDED, 0xFF0A0A0A), Triple(0xFFFFFFFF, 0xFF262B36, 0xFFFFFFFF)),
    Accent("ocean", "蓝", Triple(0xFF0A6CFF, 0xFFE3EEFF, 0xFF002A66), Triple(0xFF5AA0FF, 0xFF0F2547, 0xFFD6E6FF)),
    Accent("pine", "绿", Triple(0xFF1F8F5A, 0xFFDFF3E8, 0xFF0B3A23), Triple(0xFF4CC98A, 0xFF0F2E1F, 0xFFD3F2E1)),
    Accent("plum", "紫", Triple(0xFF6E4BE8, 0xFFECE6FF, 0xFF2A1670), Triple(0xFFA894FF, 0xFF241A4D, 0xFFE8E2FF)),
    Accent("clay", "橙", Triple(0xFFE2552B, 0xFFFDE5DC, 0xFF5A1C08), Triple(0xFFFF8A5C, 0xFF3D1A0E, 0xFFFFDDCF)),
    Accent("rose", "红", Triple(0xFFE0345A, 0xFFFDE2E8, 0xFF5C0A1E), Triple(0xFFFF6B8A, 0xFF3D0F1A, 0xFFFFDDE4)),
)

@Composable
fun JackTheme(mode: ThemeMode, accent: String = "mono", fontScale: Float = 1f, appIcon: String = "k", content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val a = ACCENTS.firstOrNull { it.id == accent } ?: ACCENTS.first()
    val (p, c, on) = if (dark) a.dark else a.light
    val base = if (dark) DarkColors else LightColors
    val colors = base.copy(
        primary = Color(p),
        // 强调色亮就配黑字，暗就配白字（「黑白」在深色模式下是白底黑字的按钮）
        onPrimary = if (Color(p).luminance() > 0.5f) Color.Black else Color.White,
        primaryContainer = Color(c),
        onPrimaryContainer = Color(on),
    )
    val density = LocalDensity.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val links = androidx.compose.runtime.remember(ctx) { JackUriHandler(ctx) }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
        androidx.compose.ui.platform.LocalUriHandler provides links,
        LocalAppIcon provides appIcon,
    ) {
        MaterialTheme(
            colorScheme = colors,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
