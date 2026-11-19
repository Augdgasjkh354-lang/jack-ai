package com.jack.ai.data

import android.content.Context
import com.jack.ai.api.sharedHttp
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 设计图用的字体。手机自带的只有黑体（有的手机还有宋体），常规、粗两档，做出来的图很「素」。
 * - 得意黑打进安装包（1MB，标题用得最多）；
 * - 其余的按需下载：用的是 jsDelivr 上的 Fontsource / 霞鹜文楷网页字体包，一个字体切成上百个小块（按 unicode-range），
 *   页面用到哪些字才下哪几块，下过的存在 files/design-fonts 里，下次不再联网。
 * 页面里写 font-family: "思源宋体对应的英文名" 就行：出图前看页面提到了哪些字体，把它们的 @font-face 临时加进去（不进底稿）。
 * 全部是可以免费商用的开源字体（SIL OFL 1.1）。清单在 assets/design/fonts.json。
 */
object DesignFonts {
    class Font(
        val id: String,
        /** CSS 里写的名字。 */
        val family: String,
        val label: String,
        /** 适合做什么（给模型挑字体看）。 */
        val use: String,
        /** 有哪几档字重。 */
        val weights: String,
        /** npm 包名@版本（jsDelivr 上）；打包进安装包的为空。 */
        val pkg: String = "",
        /** 包里的 CSS 文件（相对包的根目录）。 */
        val css: List<String> = emptyList(),
        /** 打进安装包的字体文件（assets 里的路径）。 */
        val bundled: String = "",
        val cjk: Boolean = true,
    )

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: List<Font>? = null

    /** 字体清单在 assets/design/fonts.json（模板校验脚本也读它，两边对得上）。 */
    fun all(ctx: Context): List<Font> = cache ?: runCatching {
        parse(ctx.assets.open("design/fonts.json").use { it.readBytes().toString(Charsets.UTF_8) })
    }.getOrDefault(emptyList()).also { cache = it }

    internal fun parse(text: String): List<Font> = (json.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
        val pkg = s("pkg")
        if (pkg.isNotEmpty() && !PKG_OK.matches(pkg)) return@mapNotNull null
        Font(s("id"), s("family"), s("label"), s("use"), s("weights"), pkg,
            (o["css"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }, s("bundled"), s("cjk") != "false")
    }

    private const val CDN = "https://cdn.jsdelivr.net/npm/"
    private val PKG_OK = Regex("""^(@fontsource/[a-z0-9-]+|lxgw-wenkai-webfont)@\d+\.\d+\.\d+$""")
    /** 页面里的字体文件地址：/fonts/npm/<包>@<版本>/<路径>.woff2，或 /fonts/app/<文件名>。 */
    private val FILE_OK = Regex("""^/fonts/npm/((?:@fontsource/[a-z0-9-]+|lxgw-wenkai-webfont)@\d+\.\d+\.\d+)/([A-Za-z0-9_./-]+\.woff2)$""")

    /** 给模型看的字体清单。 */
    fun catalog(fonts: List<Font>): String = buildString {
        append("可用字体（font-family 写英文名，后面再跟一个系统字体兜底，比如 \"Smiley Sans\",\"Noto Sans CJK SC\",sans-serif）：\n")
        fonts.forEach { f -> append("- \"${f.family}\"：${f.label}，字重 ${f.weights}。${f.use}\n") }
        append("手机自带的 \"Noto Sans CJK SC\"（黑体，400/700）和 \"Noto Serif CJK SC\"（宋体，有的手机没有）不用下载。")
        append("一张图最多用两种中文字体（标题一种、正文一种），英文数字字体再加一种。除得意黑外第一次用要联网下载，没网时会退回系统黑体。")
    }

    /** 页面里用到了哪些字体（看 CSS 里有没有写到它的名字）。 */
    fun used(fonts: List<Font>, html: String): List<Font> {
        val lower = html.lowercase()
        return fonts.filter { f -> Regex("""["']?""" + Regex.escape(f.family.lowercase()) + """["']?\s*[,;}!"']""").containsMatchIn(lower) }
    }

    /** 下载过的 CSS 放在内存里一份，同一次运行里不用每次读文件。 */
    private val cssCache = ConcurrentHashMap<String, String>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "design-fonts")

    /**
     * 这些字体的 @font-face（地址改成页面里的 /fonts/…）。下不下来的字体放在第二个返回值里（会退回系统字体）。
     * 要联网，放在 IO 线程调。
     */
    fun css(ctx: Context, fonts: List<Font>): Pair<String, List<String>> {
        val failed = mutableListOf<String>()
        val out = StringBuilder()
        fonts.forEach { f ->
            if (f.bundled.isNotEmpty()) {
                out.append("@font-face{font-family:'${f.family}';font-style:normal;font-weight:100 900;font-display:block;src:url(/fonts/app/${f.bundled.substringAfterLast('/')}) format('woff2')}\n")
                return@forEach
            }
            val parts = f.css.map { name -> runCatching { cssFile(ctx, f.pkg, name) }.getOrNull() }
            if (parts.any { it == null }) failed += f.label
            parts.filterNotNull().forEach { out.append(it).append('\n') }
        }
        return out.toString() to failed
    }

    private fun cssFile(ctx: Context, pkg: String, name: String): String {
        require(PKG_OK.matches(pkg))
        val key = "$pkg/$name"
        cssCache[key]?.let { return it }
        val file = File(dir(ctx), "css/" + key.replace('@', '_').replace('/', '_'))
        val raw = if (file.isFile && file.length() > 0) file.readText() else {
            val text = fetch(CDN + key)?.toString(Charsets.UTF_8)?.takeIf { "@font-face" in it } ?: error("下载失败")
            file.parentFile?.mkdirs(); file.writeText(text); text
        }
        val base = name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val css = rewrite(raw, pkg, base)
        cssCache[key] = css
        return css
    }

    /** 把 CSS 里的相对地址换成页面里的 /fonts/npm/…，只留 woff2，显示方式改成等字体（出图要等字体到了再截）。 */
    internal fun rewrite(css: String, pkg: String, base: String): String =
        css.replace(Regex(""",\s*url\([^)]*\.woff\)\s*format\(['"]woff['"]\)"""), "")
            .replace(Regex("""url\(\s*['"]?\./([^'")]+)['"]?\s*\)""")) { m -> "url(/fonts/npm/$pkg/$base${m.groupValues[1]})" }
            .replace(Regex("""font-display:\s*swap"""), "font-display: block")

    /**
     * 页面要的字体文件（/fonts/… 开头的路径）：安装包里的直接读；下载过的读本地；没下过的现在下（在 WebView 的后台线程里，不卡界面）。
     * 不认识的地址返回 null。
     */
    fun open(ctx: Context, path: String): InputStream? {
        if (path.startsWith("/fonts/app/")) {
            val name = path.removePrefix("/fonts/app/")
            val f = all(ctx).firstOrNull { it.bundled.substringAfterLast('/') == name } ?: return null
            return runCatching { ctx.assets.open(f.bundled) }.getOrNull()
        }
        val m = FILE_OK.matchEntire(path) ?: return null
        if (".." in path) return null
        val pkg = m.groupValues[1]; val rest = m.groupValues[2]
        val file = File(dir(ctx), "files/" + pkg.replace('@', '_').replace('/', '_') + "/" + rest)
        if (file.isFile && file.length() > 0) return file.inputStream()
        val bytes = fetch("$CDN$pkg/$rest") ?: return null
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }
        return bytes.inputStream()
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        sharedHttp.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()

    /** 下载过的字体占多大（字节）。 */
    fun cachedBytes(ctx: Context): Long = dir(ctx).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clearCache(ctx: Context) { dir(ctx).deleteRecursively(); cssCache.clear() }
}
