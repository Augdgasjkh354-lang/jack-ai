package com.jack.ai.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 成组的设计图（design action=series 出的「名字-01、名字-02……」一组）：编辑器里翻页改、同步风格、整组导出。
 * 这里放和界面无关的部分：认出同一组、改页码、把配色和字体变量写进别的页、存进相册。
 */
object DesignSeries {
    private val NUMBERED = Regex("""^(.+)-(\d{2})$""")

    /** 底稿 a-03.html → 同一组所有页的底稿（按页码排）。不是成组的、或者一组只剩一张，返回只有它自己。 */
    fun pagesOf(src: File): List<File> {
        val m = NUMBERED.matchEntire(src.nameWithoutExtension) ?: return listOf(src)
        val base = m.groupValues[1]
        val pages = src.parentFile?.listFiles()?.filter { f ->
            f.isFile && f.extension.lowercase() == "html" && NUMBERED.matchEntire(f.nameWithoutExtension)?.groupValues?.get(1) == base
        }?.sortedBy { it.nameWithoutExtension.takeLast(2) }.orEmpty()
        return pages.ifEmpty { listOf(src) }
    }

    /** 一组的名字（不带 -01）；不是成组的返回 null。 */
    fun baseOf(src: File): String? = NUMBERED.matchEntire(src.nameWithoutExtension)?.groupValues?.get(1)

    fun pageName(base: String, i: Int) = base + "-" + (i + 1).toString().padStart(2, '0')

    /** 页码位置（data-slot="page"）里原来是「01/06」这种，就改成新的页码；用户自己写了别的不动。 */
    fun withPageNumber(html: String, index: Int, total: Int): String {
        val re = Regex("""(<[^>]*\bdata-slot\s*=\s*["']page["'][^>]*>)\s*(\d{1,2})\s*/\s*(\d{1,2})\s*(</)""")
        val text = (index + 1).toString().padStart(2, '0') + "/" + total.toString().padStart(2, '0')
        return re.replace(html) { m -> m.groupValues[1] + text + m.groupValues[4] }
    }

    /**
     * 把配色、字体变量（--bg、--font-title……）写进页面的画布（class 里有 canvas 的第一个元素）的 style，
     * 原来 style 里同名的变量换掉，别的保留。用来把一页的风格同步到整组。
     */
    fun withCanvasVars(html: String, vars: Map<String, String>): String {
        // 字体名里的双引号换成单引号（style 属性本身用双引号括着）
        val safe = vars.mapValues { it.value.replace('"', '\'') }.filter { (k, v) -> Regex("^[a-z][a-z0-9-]*$").matches(k) && ';' !in v && '<' !in v && '>' !in v }
        if (safe.isEmpty()) return html
        val tag = Regex("""<[a-zA-Z][^>]*\bclass\s*=\s*["'][^"']*\bcanvas\b[^"']*["'][^>]*>""").find(html) ?: return html
        val open = tag.value
        val styleRe = Regex("""\sstyle\s*=\s*"([^"]*)"""")
        val old = styleRe.find(open)?.groupValues?.get(1).orEmpty()
        val kept = old.split(';').map { it.trim() }.filter { d -> d.isNotEmpty() && safe.keys.none { k -> d.startsWith("--$k:") || d.startsWith("--$k ") } }
        val style = (kept + safe.map { (k, v) -> "--$k: $v" }).joinToString("; ")
        val newOpen = if (styleRe.containsMatchIn(open)) open.replace(styleRe, " style=\"${style.replace("$", "\\$")}\"")
            else open.replaceFirst(Regex("""\s*/?>$"""), " style=\"$style\">")
        return html.substring(0, tag.range.first) + newOpen + html.substring(tag.range.last + 1)
    }

    /** 按顺序把这些图存进相册（Pictures/Kavi），返回存了几张。 */
    fun exportToGallery(ctx: Context, pngs: List<File>, base: String): Int {
        var n = 0
        val stamp = System.currentTimeMillis()
        pngs.forEachIndexed { i, f ->
            if (!f.isFile) return@forEachIndexed
            val ext = f.extension.lowercase().ifEmpty { "png" }
            val name = "${base}_${stamp}_${(i + 1).toString().padStart(2, '0')}.$ext"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/png")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kavi")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Kavi").apply { mkdirs() }
                    put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
                }
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@forEachIndexed
            try {
                resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: error("打不开相册图片")
                if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                n++
            } catch (e: Exception) { resolver.delete(uri, null, null) }
        }
        return n
    }
}
