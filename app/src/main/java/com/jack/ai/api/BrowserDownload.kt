package com.jack.ai.api

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Jack 浏览器里点下载链接：用网页的 Cookie（登录过的网站照样能下）和浏览器同一条线路（自动 / 走代理 / 直连）去下。
 * 这里只管网络和起名字；存到哪（手机的「下载」文件夹或对话文件夹）由调用方决定。
 */
object BrowserDownload {
    /** 超过这个大小先问；超过 HARD_LIMIT 不下（和 GitHub 下载一样）。 */
    const val ASK_ABOVE = 500L * 1024 * 1024
    const val HARD_LIMIT = 2L * 1024 * 1024 * 1024

    class DownloadException(message: String) : Exception(message)

    /** 网页要下载的一个文件（还没开始下）。size < 0 表示网站没说多大。byUser：用户开着浏览器时点的（弹窗问用户），否则等 Jack 确认。 */
    data class Pending(val url: String, val name: String, val mime: String, val size: Long, val userAgent: String, val referer: String,
                       val byUser: Boolean = false)

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

    fun sizeText(bytes: Long): String = com.jack.ai.data.ToolSizes.text(bytes)
}
