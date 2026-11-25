package com.jack.ai.ui

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/**
 * 界面字体。设置里存一个 id：
 * - ""：跟随系统（默认，和以前一样）；
 * - "serif"：宋体（系统自带的衬线字，中文是思源宋体 / Noto Serif CJK）；
 * - "sys:<名字>"：手机里自带的字体（/system/fonts 这些地方的 MiSans、HarmonyOS Sans、OPPO Sans……）；
 * - "file:<文件名>"：自己导入的 .ttf / .otf，存在 App 的 files/fonts 里。
 *
 * 为什么要有「手机里的字体」：有些系统（小米主题、vivo、OPPO、荣耀的换字体）只给老式 TextView 换字，
 * Compose 写的界面拿到的还是原来的字，看着就和系统其他地方不一样；直接读那个字体文件就一样了。
 */
object AppFonts {
    data class Choice(val id: String, val label: String, val files: List<File> = emptyList())

    /** 当前字体：Compose 里那几处自己拼 TextStyle 的地方（Markdown、记忆图）用它，不然会漏掉。 */
    val LocalFamily = staticCompositionLocalOf<FontFamily?> { null }
    /** 同一个字体给老式 TextView（回答正文用 Markwon 画）。null 是系统默认。 */
    val LocalTypeface = staticCompositionLocalOf<Typeface?> { null }

    /** 手机字体可能在的地方。/data/system/theme/fonts 是小米主题换的字体。 */
    private val DIRS = listOf("/system/fonts", "/product/fonts", "/system_ext/fonts", "/data/system/theme/fonts")

    /** 安卓原生带的字体，不列出来（要么就是默认字体，要么是英文花体）。 */
    private val AOSP = Regex("(?i)^(Roboto|Noto|Droid|Cutive|ComingSoon|CarroisGothic|DancingScript|SourceSansPro|AndroidClock|Clock|Google|Lato|Rubik|Arbutus|Arvo|Lustria|Fraunces|Karla|Lora|Montserrat|Nunito|Barlow|BigShoulders|Zilla|SourceSerif|Ahem|NotoColorEmoji|Emoji|SysSans-En|DroidSans)")

    private val WEIGHT_WORDS = listOf(
        "thin" to 100, "hairline" to 100, "extralight" to 200, "ultralight" to 200, "light" to 300,
        "regular" to 400, "normal" to 400, "book" to 400, "medium" to 500,
        "semibold" to 600, "demibold" to 600, "demi" to 600, "extrabold" to 800, "ultrabold" to 800,
        "bold" to 700, "heavy" to 900, "black" to 900,
    )

    /** 文件名里的字重（MiSans-Medium.ttf → 500）；认不出来返回 null。 */
    internal fun weightOf(name: String): Int? {
        val n = name.substringBeforeLast('.').lowercase().replace("_", "-")
        val tail = n.substringAfterLast('-')
        // 先比长的（semibold 别认成 bold）
        return WEIGHT_WORDS.sortedByDescending { it.first.length }.firstOrNull { (w, _) -> tail == w || tail.endsWith(w) }?.second
    }

    /** 同一套字的名字：去掉字重和 VF 这类后缀（MiSans-Medium.ttf、MiSans-Bold.ttf → MiSans）。 */
    internal fun familyOf(name: String): String {
        var n = name.substringBeforeLast('.').replace('_', '-')
        n = n.replace(Regex("(?i)[- ]?(VF|Variable)$"), "")
        val w = weightOf(name)
        if (w != null) {
            val dash = n.lastIndexOf('-')
            n = if (dash > 0) n.substring(0, dash) else n.replace(Regex("(?i)(thin|extralight|ultralight|light|regular|normal|book|medium|semibold|demibold|demi|extrabold|ultrabold|bold|heavy|black)$"), "")
        }
        return n.trim('-', ' ').ifEmpty { name.substringBeforeLast('.') }
    }

    /** 名字好看一点：MiSans → MiSans，HarmonyOS_Sans_SC → HarmonyOS Sans SC。 */
    private fun pretty(family: String) = family.replace('-', ' ').replace('_', ' ')

    /**
     * 手机里能用的中文字体，按「一套」归在一起。中文字体都很大（几 MB 起），小于 1.5MB 的多半只有英文，不列。
     */
    fun scanSystem(): List<Choice> {
        val files = DIRS.flatMap { d -> File(d).listFiles().orEmpty().toList() }
            .filter { f -> f.isFile && f.canRead() && f.extension.lowercase() in setOf("ttf", "otf") && f.length() > 1_500_000 && !AOSP.containsMatchIn(f.name) }
        return files.groupBy { familyOf(it.name) }
            .map { (fam, list) -> Choice("sys:$fam", pretty(fam), list.distinctBy { it.name }.sortedBy { weightOf(it.name) ?: 400 }) }
            .sortedBy { it.label.lowercase() }
    }

    fun importedDir(ctx: Context) = File(ctx.filesDir, "fonts").apply { mkdirs() }

    fun scanImported(ctx: Context): List<Choice> = importedDir(ctx).listFiles().orEmpty()
        .filter { it.extension.lowercase() in setOf("ttf", "otf") }
        .sortedBy { it.name }
        .map { Choice("file:${it.name}", pretty(it.nameWithoutExtension), listOf(it)) }

    /** 导入一个字体文件：先确认安卓认得它，再拷进来。返回新字体的 id，认不出来返回 null。 */
    fun import(ctx: Context, uri: Uri): String? {
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.replace(Regex("""[\\/:*?"<>|]"""), "_") ?: "字体.ttf"
        val ext = name.substringAfterLast('.', "").lowercase()
        val safe = if (ext in setOf("ttf", "otf")) name else "$name.ttf"
        val tmp = File(ctx.cacheDir, "font-import.tmp")
        runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } }.getOrNull() ?: return null
        val ok = runCatching { Typeface.Builder(tmp).build() != null }.getOrDefault(false)
        if (!ok) { tmp.delete(); return null }
        val dest = File(importedDir(ctx), safe)
        tmp.copyTo(dest, overwrite = true); tmp.delete()
        return "file:${dest.name}"
    }

    /** id 对应的字体文件；找不到（换了手机、删了）返回空，界面就退回系统字体。 */
    fun filesOf(ctx: Context, id: String): List<File> = when {
        id.startsWith("sys:") -> scanSystem().firstOrNull { it.id == id }?.files.orEmpty()
        id.startsWith("file:") -> listOfNotNull(File(importedDir(ctx), id.removePrefix("file:")).takeIf { it.isFile })
        else -> emptyList()
    }

    /**
     * 拼成 Compose 的字体。一套里有多个字重文件就按文件名对上字重；只有一个文件的（可变字体，或者只有常规体），
     * 按 300–700 各登记一次，可变字体会真的换粗细，普通字体由系统加粗。
     */
    fun family(ctx: Context, id: String): FontFamily? {
        if (id == "serif") return FontFamily.Serif
        val files = filesOf(ctx, id)
        if (files.isEmpty()) return null
        val fonts = if (files.size == 1) {
            listOf(300, 400, 500, 600, 700).map { w ->
                Font(files[0], FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
            }
        } else {
            val byWeight = files.groupBy { weightOf(it.name) ?: 400 }.mapValues { it.value.first() }
            byWeight.map { (w, f) -> Font(f, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) }
        }
        return runCatching { FontFamily(fonts) }.getOrNull()
    }

    /** 给 TextView 用的常规体。 */
    fun typeface(ctx: Context, id: String): Typeface? {
        if (id == "serif") return Typeface.SERIF
        val files = filesOf(ctx, id)
        val f = files.firstOrNull { (weightOf(it.name) ?: 400) == 400 } ?: files.firstOrNull() ?: return null
        return runCatching { Typeface.Builder(f).setFontVariationSettings("'wght' 400").build() }.getOrNull()
    }
}
