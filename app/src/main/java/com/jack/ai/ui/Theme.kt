package com.jack.ai.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.jack.ai.data.ThemeMode

private val Blue = Color(0xFF4D6BFE)

private val LightColors = lightColorScheme(
    primary = Blue,
    secondaryContainer = Color(0xFFE3E8FF),
    onSecondaryContainer = Color(0xFF1A2A6C),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB0FF),
    secondaryContainer = Color(0xFF2C3770),
    onSecondaryContainer = Color(0xFFE3E8FF),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun JackTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
