package com.jack.ai.api

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Kavi 浏览器里点下载链接：用网页的 Cookie（登录过的网站照样能下）和浏览器同一条线路（自动 / 走代理 / 直连）去下。
 * 这里只管网络和起名字；存到哪（手机的「下载」文件夹或对话文件夹）由调用方决定。
 */
object BrowserDownload {
    /** 超过这个大小先问；超过 HARD_LIMIT 不下（和 GitHub 下载一样）。 */
    const val ASK_ABOVE = 500L * 1024 * 1024
    const val HARD_LIMIT = 2L * 1024 * 1024 * 1024

    class DownloadException(message: String) : Exception(message)

    /** 网页要下载的一个文件（还没开始下）。size < 0 表示网站没说多大。byUser：用户开着浏览器时点的（弹窗问用户），否则等 Kavi 确认。 */
    /** tab：哪个标签页里点的（网页自己生成的 blob: 文件只能在那一页里读出来），-1 = 不知道。 */
    data class Pending(val url: String, val name: String, val mime: String, val size: Long, val userAgent: String, val referer: String,
                       val byUser: Boolean = false, val tab: Int = -1)

    /** 起文件名：先看 Content-Disposition（filename* 优先），再看网址最后一段，都没有就按类型起一个。 */
    fun nameFor(url: String, disposition: String?, mime: String?): String {
        val d = disposition.orEmpty()
        val star = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(d)?.let {
            runCatching { URLDecoder.decode(it.groupValues[2].trim().trim('"'), it.groupValues[1].ifBlank { "UTF-8" }) }.getOrNull()
        }
        val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(d)?.groupValues?.get(1)?.trim()
        val fromUrl = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .takeIf { it.isNotBlank() && '.' in it }
        var name = (star ?: plain ?: fromUrl ?: "下载").replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().trimStart('.').take(120)
        if (name.isBlank()) name = "下载"
        if ('.' !in name) EXT[mime?.substringBefore(';')?.trim()?.lowercase()]?.let { name += ".$it" }
        return name
    }

    private val EXT = mapOf(
        "application/pdf" to "pdf", "application/zip" to "zip", "application/vnd.android.package-archive" to "apk",
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp", "text/plain" to "txt",
        "text/csv" to "csv", "application/json" to "json", "video/mp4" to "mp4", "audio/mpeg" to "mp3",
        "application/msword" to "doc", "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/x-7z-compressed" to "7z", "application/x-rar-compressed" to "rar", "application/gzip" to "gz",
        "image/svg+xml" to "svg", "image/avif" to "avif", "image/bmp" to "bmp", "video/webm" to "webm", "video/quicktime" to "mov",
        "video/x-flv" to "flv", "audio/mp4" to "m4a", "audio/aac" to "aac", "audio/ogg" to "ogg", "audio/wav" to "wav", "audio/flac" to "flac",
        "application/vnd.ms-excel" to "xls", "application/vnd.ms-powerpoint" to "ppt", "application/epub+zip" to "epub",
        "application/vnd.apple.mpegurl" to "m3u8", "application/x-mpegurl" to "m3u8",
    )

    /** 线路照浏览器的设置；走代理时代理起不来就失败，不偷偷直连（Net.selectorFor 负责）。 */
    private fun client(route: String): OkHttpClient = sharedHttp.newBuilder()
        .proxySelector(Net.selectorFor(route))
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /**
     * 下载到 out。cookie 是这个网址在浏览器里的 Cookie。progress(已下字节, 总字节或 -1)。
     * 超过 limit 停下抛错；断了重试一次（重试前 reset() 让调用方清空已写的）。返回字节数。
     */
    fun fetch(req: Pending, cookie: String?, route: String, limit: Long, open: () -> OutputStream, reset: () -> Unit,
              progress: (Long, Long) -> Unit = { _, _ -> }): Long {
        val scheme = req.url.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") throw DownloadException(
            if (scheme == "blob" || scheme == "data") "这是网页自己生成的文件（$scheme:），暂时存不下来" else "不支持这种下载地址（$scheme:）")
        var last: IOException? = null
        repeat(2) { attempt ->
            if (attempt > 0) reset()
            try {
                val r = Request.Builder().url(req.url)
                    .header("User-Agent", req.userAgent.ifBlank { "Mozilla/5.0" })
                    .apply {
                        if (!cookie.isNullOrBlank()) header("Cookie", cookie)
                        if (req.referer.startsWith("http")) header("Referer", req.referer)
                    }.build()
                client(route).newCall(r).execute().use { resp ->
                    if (!resp.isSuccessful) throw DownloadException("网站拒绝了下载（${resp.code}）" + if (resp.code == 403 || resp.code == 401) "，可能要先在浏览器里登录" else "")
                    val body = resp.body ?: throw IOException("没有内容")
                    val total = body.contentLength()
                    if (total > limit) throw DownloadException("文件有 ${sizeText(total)}，超过了 ${sizeText(limit)} 的上限")
                    var got = 0L
                    var lastReport = 0L
                    body.byteStream().use { input ->
                        open().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                got += n
                                if (got > limit) throw DownloadException("下载超过了 ${sizeText(limit)} 的上限，已停下")
                                o.write(buf, 0, n)
                                if (got - lastReport > 256 * 1024) { lastReport = got; progress(got, total) }
                            }
                        }
                    }
                    progress(got, total)
                    return got
                }
            } catch (e: DownloadException) {
                throw e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("下载失败")
    }

    /** 下载的去处：知道了文件名和类型才建（对话文件夹里的文件，或手机「下载」里的一项）。 */
    interface Sink {
        /** append=true：断点续传，接着已经写的往后写；false 从头写。 */
        fun open(append: Boolean): OutputStream
        /** 没下成：删掉写了一半的。 */
        fun discard()
    }

    data class Result(val name: String, val mime: String, val bytes: Long)

    /** 网址是网页（HTML）不是文件时抛这个，调用方据此告诉模型换办法。 */
    class PageException(val url: String) : Exception("这个网址打开是网页，不是文件")

    /**
     * 下载一个文件，断了从断的地方接着下（网站支持的话，最多接 3 次；不支持就从头再来一次）。
     * name 不为空就用它，否则按网站给的名字起。rejectPage：拿到的是网页就停下（抛 PageException）。
     * sink(文件名, 类型) 在拿到第一个回应后才调，只调一次。
     */
    fun download(req: Pending, cookie: String?, route: String, limit: Long, sink: (String, String) -> Sink,
                 rejectPage: Boolean = false, progress: (Long, Long) -> Unit = { _, _ -> }): Result {
        if (req.url.startsWith("data:", true)) {
            val d = decodeData(req.url) ?: throw DownloadException("这个 data: 地址解不开")
            if (d.second.size > limit) throw DownloadException("文件有 ${sizeText(d.second.size.toLong())}，超过了 ${sizeText(limit)} 的上限")
            val name = req.name.ifBlank { nameFor("", null, d.first) }
            val s = sink(name, d.first)
            try { s.open(false).use { it.write(d.second) } } catch (e: Exception) { s.discard(); throw e }
            progress(d.second.size.toLong(), d.second.size.toLong())
            return Result(name, d.first, d.second.size.toLong())
        }
        val scheme = req.url.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") throw DownloadException(
            if (scheme == "blob") "这是网页自己生成的文件（blob:），要在 Kavi 浏览器里存" else "不支持这种下载地址（$scheme:）")
        var out: Sink? = null
        var name = ""
        var mime = ""
        var got = 0L
        var total = -1L
        // 续传时用来确认文件没变（网站给了才有）
        var validator: String? = null
        var resumes = 0
        var restarted = false
        while (true) {
            val r = Request.Builder().url(req.url)
                .header("User-Agent", req.userAgent.ifBlank { "Mozilla/5.0" })
                .apply {
                    if (!cookie.isNullOrBlank()) header("Cookie", cookie)
                    if (req.referer.startsWith("http")) header("Referer", req.referer)
                    if (got > 0) { header("Range", "bytes=$got-"); validator?.let { header("If-Range", it) } }
                }.build()
            try {
                client(route).newCall(r).execute().use { resp ->
                    if (!resp.isSuccessful) throw DownloadException("网站拒绝了下载（${resp.code}）" + if (resp.code == 403 || resp.code == 401) "，可能要先在浏览器里登录" else "")
                    val body = resp.body ?: throw IOException("没有内容")
                    val partial = got > 0 && resp.code == 206
                    if (out == null) {
                        val type = resp.header("Content-Type").orEmpty()
                        if (rejectPage && isPage(type)) throw PageException(resp.request.url.toString())
                        mime = type.substringBefore(';').trim().lowercase().ifBlank { req.mime }.ifBlank { "application/octet-stream" }
                        name = req.name.ifBlank { nameFor(resp.request.url.toString(), resp.header("Content-Disposition"), mime) }
                            .let { n -> if ('.' !in n) EXT[mime]?.let { "$n.$it" } ?: n else n }
                        out = sink(name, mime)
                    }
                    validator = resp.header("ETag")?.takeUnless { it.startsWith("W/") } ?: resp.header("Last-Modified")
                    if (!partial) {
                        got = 0
                        total = body.contentLength()
                    } else if (total < 0) {
                        total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
                    }
                    if (total > limit) throw DownloadException("文件有 ${sizeText(total)}，超过了 ${sizeText(limit)} 的上限")
                    var lastReport = got
                    body.byteStream().use { input ->
                        out!!.open(partial).use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                got += n
                                if (got > limit) throw DownloadException("下载超过了 ${sizeText(limit)} 的上限，已停下")
                                o.write(buf, 0, n)
                                if (got - lastReport > 256 * 1024) { lastReport = got; progress(got, total) }
                            }
                        }
                    }
                    // 网站说了多大却没给够：当成断了
                    if (total > 0 && got < total) throw IOException("下载到一半断了（${sizeText(got)} / ${sizeText(total)}）")
                    progress(got, total)
                    return Result(name, mime, got)
                }
            } catch (e: IOException) {
                // 还没开始写就断了，或者已经接过 3 次：从头再来一次，再不行就算了
                if (got > 0 && resumes < 3) { resumes++; continue }
                if (!restarted) { restarted = true; got = 0; continue }
                out?.discard()
                throw e
            } catch (e: Exception) {
                out?.discard()
                throw e
            }
        }
    }

    /** 先问一声多大、是什么（HEAD）：(字节数或 -1, 类型)。网站不支持 HEAD 就返回 (-1, "")，照样能下。 */
    fun head(req: Pending, cookie: String?, route: String): Pair<Long, String> = runCatching {
        val r = Request.Builder().url(req.url).head()
            .header("User-Agent", req.userAgent.ifBlank { "Mozilla/5.0" })
            .apply {
                if (!cookie.isNullOrBlank()) header("Cookie", cookie)
                if (req.referer.startsWith("http")) header("Referer", req.referer)
            }.build()
        client(route).newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) -1L to "" else (resp.header("Content-Length")?.toLongOrNull() ?: -1L) to resp.header("Content-Type").orEmpty()
        }
    }.getOrDefault(-1L to "")

    /** 网页（不是文件）的类型。 */
    fun isPage(contentType: String): Boolean = contentType.substringBefore(';').trim().lowercase().let { it == "text/html" || it == "application/xhtml+xml" }

    /** data: 地址解成 (类型, 内容)；解不开返回 null。 */
    fun decodeData(url: String): Pair<String, ByteArray>? {
        if (!url.startsWith("data:", true)) return null
        val comma = url.indexOf(',').takeIf { it > 0 } ?: return null
        val meta = url.substring(5, comma)
        val mime = meta.substringBefore(';').trim().lowercase().ifBlank { "text/plain" }
        val raw = url.substring(comma + 1)
        return runCatching {
            mime to if (meta.endsWith(";base64", true)) java.util.Base64.getMimeDecoder().decode(raw)
            else URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").toByteArray()
        }.getOrNull()
    }

    /** 文件名按类型补扩展名用。 */
    fun extFor(mime: String?): String? = EXT[mime?.substringBefore(';')?.trim()?.lowercase()]

    fun sizeText(bytes: Long): String = com.jack.ai.data.ToolSizes.text(bytes)
}
