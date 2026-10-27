package com.jack.ai.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jack.ai.R

/**
 * 桌面图标可以换：每种图标是清单里的一个 activity-alias（都指向 MainActivity），换的时候开一个、关其他的。
 * id 存在设置里（Settings.appIcon）。
 */
object AppIcon {
    /** id、名字、清单里的 alias、前景图（classic 是以前的 PNG）。 */
    data class Option(val id: String, val label: String, val alias: String, val foreground: Int)

    val OPTIONS = listOf(
        Option("k", "K", ".LauncherK", R.drawable.ic_logo_k_fg),
        Option("bubble", "对话气泡", ".LauncherBubble", R.drawable.ic_logo_bubble_fg),
        Option("moon", "月亮", ".LauncherMoon", R.drawable.ic_logo_moon_fg),
        Option("classic", "原来的", ".LauncherClassic", R.mipmap.ic_launcher_foreground),
    )

    fun option(id: String) = OPTIONS.firstOrNull { it.id == id } ?: OPTIONS.first()

    private fun component(ctx: Context, o: Option) = ComponentName(ctx.packageName, ctx.packageName + o.alias)

    /** 换成 id 这个图标：先开新的再关旧的，桌面上任何时候都至少有一个入口。 */
    fun apply(ctx: Context, id: String) {
        val pm = ctx.packageManager
        val want = option(id)
        pm.setComponentEnabledSetting(component(ctx, want), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        OPTIONS.filter { it != want }.forEach { o ->
            pm.setComponentEnabledSetting(component(ctx, o), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    /** 桌面上现在开着哪几个图标（没改过的按清单：默认只开 K）。 */
    fun enabled(ctx: Context): List<String> = OPTIONS.filter { o ->
        when (ctx.packageManager.getComponentEnabledSetting(component(ctx, o))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> o.id == "k"
        }
    }.map { it.id }

    /** 现在开着的是不是设置里那个；不是就改过来（没变时不动，免得每次启动都让桌面刷新）。 */
    fun ensure(ctx: Context, id: String) {
        if (enabled(ctx) != listOf(option(id).id)) apply(ctx, id)
    }
}

/** 现在用的图标（JackTheme 里给），Kavi 的标志跟着它变。 */
val LocalAppIcon = staticCompositionLocalOf { "k" }

/** 图标的样子：和桌面上一样的底色加前景。 */
@Composable
fun AppIconTile(id: String, size: Dp, modifier: Modifier = Modifier) {
    val o = AppIcon.option(id)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bg: Brush = when {
        o.id == "classic" -> Brush.linearGradient(listOf(Color(0xFFC7673C), Color(0xFFC7673C)))
        dark -> Brush.linearGradient(listOf(Color(0xFF1C2132), Color(0xFF2A2550)))
        else -> Brush.linearGradient(listOf(Color.White, Color(0xFFEDEAFE)))
    }
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier.size(size).clip(shape).background(bg)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (dark || o.id == "classic") 0f else 1f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(o.foreground), null, Modifier.requiredSize(size * 1.5f))
    }
}
