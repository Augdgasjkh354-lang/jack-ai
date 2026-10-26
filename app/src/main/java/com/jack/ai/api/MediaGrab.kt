package com.jack.ai.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网页里的视频、音频：从浏览器发出去的请求里认出媒体地址（直链文件、HLS 的 m3u8、DASH 分开的音视频 m4s），
 * 挑出最好的那个，下载下来（HLS 一段段下了拼起来，加密的 AES-128 顺手解开）。拼成 mp4 交给 data/MediaMux。
 * 这里只管网络和解析，不碰安卓的东西，能在电脑上测。DRM 加密的（Netflix、YouTube 的大部分）存不下来。
 */
object MediaGrab {
    /** 一条媒体请求。kind：file 直链文件、hls 播放列表、dash 分开的音视频片段。 */
    data class Hit(val url: String, val kind: String, val at: Long)

    private val FILE_EXT = setOf("mp4", "m4v", "webm", "mov", "flv", "mkv", "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val NOT_MEDIA_EXT = setOf("js", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2", "ttf", "json", "html", "htm", "txt", "xml", "ts", "m4s", "avif")

    /** 认一条请求是不是媒体，是的话返回 kind。range 是请求头里的 Range（播放器拉视频常带）。 */
    fun kindOf(url: String, range: String? = null): String? {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return null
        val path = lower.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return when {
            ext == "m3u8" -> "hls"
            ext == "m4s" -> "dash"
            ext in FILE_EXT -> "file"
            // 抖音这类：地址里没有扩展名，参数里写着类型
            Regex("""mime_type=(video|audio)_""").containsMatchIn(lower) -> "file"
            // 播放器带 Range 拉的、又不像图片脚本的，多半是视频
            range?.startsWith("bytes=") == true && ext !in NOT_MEDIA_EXT && ext.length <= 4 -> "file"
            else -> null
        }
    }

    /** 同一个东西换着参数（签名、分段）请求多次，按「网站 + 路径」算一个。 */
    fun keyOf(url: String): String = url.substringBefore('?').substringBefore('#')

    // ---------- HLS（m3u8） ----------

    data class Variant(val url: String, val bandwidth: Long, val height: Int, val audioGroup: String?)
    data class Segment(val url: String, val keyUrl: String?, val iv: ByteArray?, val seq: Long)
    sealed class Playlist {
        /** 总表：几种清晰度，还有单独的音轨（组名 → 地址）。 */
        data class Master(val variants: List<Variant>, val audios: Map<String, String>) : Playlist()
        /** 一种清晰度的分段。init：fMP4 的开头那段；ended=false 是直播。unsupported：用了解不开的加密。 */
        data class Media(val segments: List<Segment>, val init: String?, val ended: Boolean, val unsupported: String?, val seconds: Double) : Playlist()
    }

    private fun attrs(line: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(line.substringAfter(':')).forEach { m ->
            out[m.groupValues[1]] = m.groupValues[2].trim('"')
        }
        return out
    }

    private fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref.trim())

    fun parseM3u8(text: String, base: String): Playlist {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val variants = mutableListOf<Variant>()
            val audios = mutableMapOf<String, String>()
            lines.forEachIndexed { i, l ->
                if (l.startsWith("#EXT-X-MEDIA:")) {
                    val a = attrs(l)
                    if (a["TYPE"] == "AUDIO" && a["URI"] != null) {
                        val g = a["GROUP-ID"].orEmpty()
                        // 一组里有好几种语言时用默认的那个
                        if (g !in audios || a["DEFAULT"] == "YES") audios[g] = resolve(base, a["URI"]!!)
                    }
                }
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val a = attrs(l)
                    val uri = lines.drop(i + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
                    variants += Variant(resolve(base, uri), a["BANDWIDTH"]?.toLongOrNull() ?: 0,
                        a["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0, a["AUDIO"])
                }
            }
            return Playlist.Master(variants, audios)
        }
        val segs = mutableListOf<Segment>()
        var seq = 0L
        var keyUrl: String? = null
        var iv: ByteArray? = null
        var init: String? = null
        var unsupported: String? = null
        var seconds = 0.0
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0
                l.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(l)
                    when (a["METHOD"]) {
                        "NONE", null -> { keyUrl = null; iv = null }
                        "AES-128" -> { keyUrl = a["URI"]?.let { resolve(base, it) }; iv = a["IV"]?.let(::hex) }
                        else -> unsupported = "用了 ${a["METHOD"]} 加密（版权保护），存不下来"
                    }
                }
                l.startsWith("#EXT-X-MAP:") -> init = attrs(l)["URI"]?.let { resolve(base, it) }
                l.startsWith("#EXT-X-BYTERANGE") -> unsupported = unsupported ?: "分段方式（BYTERANGE）暂不支持"
                l.startsWith("#EXTINF:") -> seconds += l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                !l.startsWith("#") -> segs += Segment(resolve(base, l), keyUrl, iv, seq++)
            }
        }
        return Playlist.Media(segs, init, lines.any { it.startsWith("#EXT-X-ENDLIST") }, unsupported, seconds)
    }

    private fun hex(s: String): ByteArray? {
        val h = s.removePrefix("0x").removePrefix("0X")
        if (h.length != 32) return null
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** 挑清晰度：不超过 maxHeight（0 = 不限）里最高的；都超过就要最低的那个。 */
    fun pick(variants: List<Variant>, maxHeight: Int = 0): Variant? {
        val ok = if (maxHeight > 0) variants.filter { it.height in 1..maxHeight } else variants
        return (ok.ifEmpty { if (maxHeight > 0) listOfNotNull(variants.minByOrNull { it.height }) else variants })
            .maxWithOrNull(compareBy<Variant>({ it.height }, { it.bandwidth }))
    }

    // ---------- B 站：页面里的 __playinfo__ ----------

    /** B 站页面自带的播放信息：DASH 的音视频地址（选清晰度最高的、兼容最好的 H.264），或者整个的直链（durl）。 */
    data class PlayInfo(val video: String?, val audio: String?, val direct: String?, val quality: String)

    fun bilibili(playinfo: String, maxHeight: Int = 0): PlayInfo? {
        val root = runCatching { Json.parseToJsonElement(playinfo) as? JsonObject }.getOrNull() ?: return null
        val data = (root["data"] as? JsonObject) ?: root
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content
        fun JsonObject.n(k: String) = s(k)?.toLongOrNull() ?: 0
        fun JsonObject.link() = s("baseUrl") ?: s("base_url") ?: (this["backupUrl"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
        val dash = data["dash"] as? JsonObject
        if (dash != null) {
            val videos = (dash["video"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            // 7 = H.264（手机都能放）；HEVC、AV1 有的手机放不了，只在没有 H.264 时用
            val fit = if (maxHeight > 0) videos.filter { it.n("height") in 1..maxHeight.toLong() }.ifEmpty { listOfNotNull(videos.minByOrNull { it.n("height") }) } else videos
            val v = fit.filter { it.n("codecid") == 7L }.ifEmpty { fit }.maxWithOrNull(compareBy({ it.n("id") }, { it.n("bandwidth") }))
            val audios = (dash["audio"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject } +
                listOfNotNull(((dash["flac"] as? JsonObject)?.get("audio") as? JsonObject))
            val a = audios.filter { it.s("codecs")?.startsWith("mp4a") != false }.maxByOrNull { it.n("bandwidth") }
            if (v != null || a != null) return PlayInfo(v?.link(), a?.link(), null, v?.let { "${it.n("height")}p" }.orEmpty())
        }
        val durl = (data["durl"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // 分成好几段的老视频（flv）不拼，只要一整段的
        if (durl.size == 1) return PlayInfo(null, null, durl[0].s("url"), "")
        return null
    }

    // ---------- 分开的音视频（DASH）：看文件头里是视频还是音频 ----------

    /** mp4 / m4s 文件头里的 hdlr 写着 vide 或 soun。认不出返回 null。 */
    fun trackType(head: ByteArray): String? {
        val tag = "hdlr".toByteArray()
        var i = 0
        while (i + 16 <= head.size) {
            if (head[i] == tag[0] && head[i + 1] == tag[1] && head[i + 2] == tag[2] && head[i + 3] == tag[3]) {
                return when (String(head, i + 12, 4, Charsets.ISO_8859_1)) {
                    "vide" -> "video"
                    "soun" -> "audio"
                    else -> null
                }
            }
            i++
        }
        return null
    }

    // ---------- 网络 ----------

    class Req(val userAgent: String, val referer: String, val cookieOf: (String) -> String?, val route: String)

    private fun client(route: String) = sharedHttp.newBuilder()
        .proxySelector(Net.selectorFor(route))
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private fun request(r: Req, url: String, range: String? = null) = Request.Builder().url(url)
        .header("User-Agent", r.userAgent.ifBlank { "Mozilla/5.0" })
        .apply {
            r.cookieOf(url)?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
            if (r.referer.startsWith("http")) header("Referer", r.referer)
            range?.let { header("Range", it) }
        }.build()

    fun text(r: Req, url: String): String = client(r.route).newCall(request(r, url)).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("网站拒绝了（${resp.code}）")
        val body = resp.body ?: throw IOException("没有内容")
        if (body.contentLength() > 5L * 1024 * 1024) throw IOException("播放列表太大")
        body.string()
    }

    fun bytes(r: Req, url: String, limit: Long = 64L * 1024 * 1024, range: String? = null): ByteArray {
        var last: IOException? = null
        repeat(3) {
            try {
                return client(r.route).newCall(request(r, url, range)).execute().use { resp ->
                    if (!resp.isSuccessful) throw BrowserDownload.DownloadException("网站拒绝了（${resp.code}）")
                    val body = resp.body ?: throw IOException("没有内容")
                    if (body.contentLength() > limit) throw BrowserDownload.DownloadException("一段就超过了 ${BrowserDownload.sizeText(limit)}")
                    body.bytes()
                }
            } catch (e: IOException) { last = e }
        }
        throw last ?: IOException("下载失败")
    }

    /** 读文件头（前 64 KB）看是视频还是音频，顺便拿到总大小（网站给了的话）。 */
    fun probe(r: Req, url: String): Pair<String?, Long> = client(r.route).newCall(request(r, url, "bytes=0-65535")).execute().use { resp ->
        if (!resp.isSuccessful) return null to -1
        val total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: resp.body?.contentLength() ?: -1
        val head = resp.body?.byteStream()?.use { s -> val b = ByteArray(65536); var n = 0; while (n < b.size) { val k = s.read(b, n, b.size - n); if (k < 0) break; n += k }; b.copyOf(n) }
        (head?.let(::trackType)) to total
    }

    /**
     * 下 HLS：url 是总表就挑清晰度（不超过 maxHeight），有单独的音轨也一起下。
     * 视频写进 videoOut（.ts 或 fMP4），单独的音轨写进 audioOut；返回 (有没有单独音轨, 多少秒)。
     * progress(下了几段, 共几段, 已下字节)。
     */
    fun hls(r: Req, url: String, videoOut: File, audioOut: File, limit: Long, maxHeight: Int = 0,
            progress: (Int, Int, Long) -> Unit = { _, _, _ -> }): Pair<Boolean, Double> {
        var media = parseM3u8(text(r, url), url)
        var mediaUrl = url
        var audioUrl: String? = null
        val master = media as? Playlist.Master
        if (master != null) {
            val v = pick(master.variants, maxHeight) ?: throw BrowserDownload.DownloadException("播放列表里没有能下的清晰度")
            audioUrl = v.audioGroup?.let { master.audios[it] }?.takeIf { it != v.url }
            mediaUrl = v.url
            media = parseM3u8(text(r, v.url), v.url)
        }
        val video = media as? Playlist.Media ?: throw BrowserDownload.DownloadException("播放列表套了好几层，认不出来")
        val audio = audioUrl?.let { parseM3u8(text(r, it), it) as? Playlist.Media }
        listOfNotNull(video, audio).forEach { m ->
            m.unsupported?.let { throw BrowserDownload.DownloadException("这个视频$it") }
            if (!m.ended) throw BrowserDownload.DownloadException("这是直播，没有结尾，存不下来")
            if (m.segments.isEmpty()) throw BrowserDownload.DownloadException("播放列表是空的")
        }
        val total = video.segments.size + (audio?.segments?.size ?: 0)
        var done = 0
        var got = 0L
        val keys = mutableMapOf<String, ByteArray>()
        fun write(m: Playlist.Media, out: File, base: String) {
            out.outputStream().buffered(256 * 1024).use { o ->
                m.init?.let { o.write(bytes(r, it)) }
                for (s in m.segments) {
                    var b = bytes(r, s.url)
                    if (s.keyUrl != null) {
                        val key = keys.getOrPut(s.keyUrl) { bytes(r, s.keyUrl, 1024) }
                        b = decrypt(b, key, s.iv ?: ivOf(s.seq))
                    }
                    got += b.size
                    if (got > limit) throw BrowserDownload.DownloadException("视频超过了 ${BrowserDownload.sizeText(limit)} 的上限，已停下")
                    o.write(b)
                    progress(++done, total, got)
                }
            }
            if (out.length() == 0L) throw IOException("没下到内容（$base）")
        }
        write(video, videoOut, mediaUrl)
        audio?.let { write(it, audioOut, audioUrl!!) }
        return (audio != null) to video.seconds
    }

    /** HLS 没写 IV 时用分段序号（16 字节，大端）。 */
    fun ivOf(seq: Long): ByteArray = ByteArray(16).also { b -> for (i in 0 until 8) b[15 - i] = (seq ushr (8 * i)).toByte() }

    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        if (key.size != 16) throw BrowserDownload.DownloadException("视频的解密钥匙不对（${key.size} 字节），可能要登录")
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }
}
