package com.jack.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

@Composable
fun JackTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark(mode)) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
