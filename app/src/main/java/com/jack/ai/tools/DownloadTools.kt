package com.jack.ai.tools

import com.jack.ai.api.BrowserDownload
import com.jack.ai.api.BrowserPages
import com.jack.ai.api.MediaGrab
import com.jack.ai.data.Downloads
import com.jack.ai.data.MediaMux
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.SkillIds
import com.jack.ai.data.ToolSizes
import com.jack.ai.data.Workspace
import com.jack.ai.ui.JackBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 下载：download_file 给网址直接下（文件、m3u8 视频），browser 的 download_all（整页的图片、附件）、
 * download_media（网页里的视频、音乐）。存进对话文件夹，或用户要的话存到手机「下载/JackAI」。
 * 登录过的网站照样能下（带 Jack 浏览器的 Cookie），线路和浏览器一样（国外网站走 Jack 专用代理）。
 */

/** 一次最多给几个网址。 */
private const val MAX_URLS = 50
/** 批量下载一次最多几个。 */
private const val MAX_BATCH = 200
/** 视频默认最高要多清楚（手机上看 1080p 够了，再高文件大好几倍）。 */
private const val MAX_HEIGHT = 1080

private val APK_NOTE = "这是安卓安装包：要不要装由用户自己决定，提醒他只装信得过的来源。"

private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?')

/** 存到哪：phone 是手机「下载/JackAI」，别的都是对话文件夹。 */
private fun JsonObject.toPhone() = this["to"].text().trim() == "phone"

/** 进度写成一句话，显示在通知栏。 */
private fun progressLine(name: String, got: Long, total: Long) =
    "下载「${name.take(30)}」" + if (total > 0) " ${got * 100 / total}%（${ToolSizes.text(got)} / ${ToolSizes.text(total)}）" else " ${ToolSizes.text(got)}"

/** 存好的文件说给模型听；在对话文件夹里的挂成卡片（attach=false 时不挂）。 */
private fun ToolSession.told(s: Downloads.Saved, attach: Boolean = true): String {
    val f = s.file
    if (f != null && attach) ui.attach(f)
    val size = f?.length()?.let { "（${ToolSizes.text(it)}）" }.orEmpty()
    return "「${s.name}」$size" + if (f != null) "" else "，在${s.where}"
}

// ---------- download_file：给网址直接下 ----------

internal suspend fun ToolSession.downloadFile(args: JsonObject): String {
    val urls = ((args["urls"] as? JsonArray)?.map { it.text().trim() } ?: listOf(args["url"].text().trim()))
        .filter { it.isNotBlank() }.distinct()
    if (urls.isEmpty()) return "要填 url（文件的下载地址）"
    if (urls.size > MAX_URLS) return "一次最多下 $MAX_URLS 个，分几次来"
    urls.firstOrNull { !it.startsWith("http://", true) && !it.startsWith("https://", true) && !it.startsWith("data:", true) }?.let {
        return "「${it.take(80)}」不是下载地址：要以 http:// 或 https:// 开头" +
            if (it.startsWith("blob:", true)) "。blob: 是网页自己生成的文件，要在 Jack 浏览器里点下载，再用 browser 的 download 存" else ""
    }
    val phone = args.toPhone()
    val name = args["name"].text().trim().takeIf { urls.size == 1 }.orEmpty()
    val label = if (urls.size == 1) "下载 " + urls[0].take(60) else "下载 ${urls.size} 个文件"
    return runTool(label) {
        val ua = JackBrowser.userAgent()
        val route = JackBrowser.routeFor(app)
        val saved = mutableListOf<Downloads.Saved>()
        val failed = mutableListOf<String>()
        val pages = mutableListOf<String>()
        for (url in urls) {
            try {
                if (MediaGrab.kindOf(url) == "hls") {
                    val cookie = JackBrowser.cookieFor(url)
                    val req = MediaGrab.Req(ua, url, { u -> cookie.takeIf { hostOf(u) == hostOf(url) } }, route)
                    saved += grabMedia(req, Pick.Hls(url), name.ifBlank { "视频" }, audioOnly = false, phone = phone).first
                    continue
                }
                val p = BrowserDownload.Pending(url, Workspace.safeName(name).takeIf { name.isNotBlank() }.orEmpty(), "", -1, ua, "")
                val cookie = if (url.startsWith("http", true)) JackBrowser.cookieFor(url) else null
                // 先问多大：太大先问用户、空间不够不下
                val (size, type) = withContext(Dispatchers.IO) { if (url.startsWith("http", true)) BrowserDownload.head(p, cookie, route) else -1L to "" }
                if (BrowserDownload.isPage(type)) { pages += url; continue }
                val refuse = checkDownload(size, url)
                if (refuse != null) { failed += "${url.take(80)}：$refuse"; continue }
                val target = withContext(Dispatchers.IO) {
                    var t: Downloads.Target? = null
                    BrowserDownload.download(p, cookie, route, BrowserDownload.HARD_LIMIT, { n, mime ->
                        (if (phone) Downloads.toPhone(app, n, mime) else Downloads.toDir(app, wsDir, n, mime)).also { t = it }.sink
                    }, rejectPage = true, progress = { got, total -> ui.progress(progressLine(p.name.ifBlank { url.substringAfterLast('/') }, got, total)) })
                    t!!
                }
                saved += withContext(Dispatchers.IO) { target.finish().also { Downloads.log(app, it, url) } }
            } catch (e: BrowserDownload.PageException) {
                pages += url
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += "${url.take(80)}：" + (e.message ?: ToolSession.errorText(e))
            }
        }
        val text = buildString {
            if (saved.isNotEmpty()) {
                val attach = saved.size <= 5
                append("已下载 ${saved.size} 个文件" + (if (phone) "到${saved[0].where}" else "到对话文件夹") + "：")
                append(saved.joinToString("、") { told(it, attach) })
                append("。")
                if (!phone) append((if (attach) "聊天里能打开或分享，" else "文件多，聊天里没一个个挂出来；") + "需要的话用 read_file 或 run_python 处理。")
                else append("用户在文件管理器的「下载/JackAI」里能找到。")
                if (saved.any { it.name.endsWith(".apk", true) }) append(APK_NOTE)
            }
            if (pages.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("这些网址打开是网页，不是文件：" + pages.joinToString("、") { it.take(80) } + "。")
                append(if (SkillIds.BROWSER in skillIds) "要存网页里的视频、音乐：用 browser 打开它，再 action=download_media；要存网页上的图片、附件：打开后 action=download_all；要点了按钮才开始下的：打开后点下载按钮，再 action=download。（「操作浏览器」技能）"
                    else "只要文字用 read_webpage；要存网页里的视频、图片，请用户打开「操作浏览器」技能。")
            }
            if (failed.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("没下成 ${failed.size} 个：\n" + failed.joinToString("\n") { "- $it" })
            }
        }
        if (saved.isEmpty() && pages.isEmpty()) throw IllegalStateException(text)
        text to SearchRecord(label + if (saved.isEmpty()) "：没下成" else "")
    }
}

/** 超过 500MB 先问用户；超过 2GB 或手机空间不够就不下。返回不下的原因，能下返回 null。 */
private suspend fun ToolSession.checkDownload(size: Long, what: String): String? {
    if (size > BrowserDownload.HARD_LIMIT) return "有 ${ToolSizes.text(size)}，超过了 ${ToolSizes.text(BrowserDownload.HARD_LIMIT)} 的上限"
    val free = withContext(Dispatchers.IO) { wsDir.apply { mkdirs() }.usableSpace }
    if (size > 0 && free < size + 100L * 1024 * 1024) return "手机剩余空间只有 ${ToolSizes.text(free)}，放不下（约 ${ToolSizes.text(size)}）"
    if (size > BrowserDownload.ASK_ABOVE && !approve("下载这个大文件？", "大约 ${ToolSizes.text(size)}，来自 ${what.take(200)}。走代理的话会用掉相应的节点流量。", "下载")) {
        return "用户没有同意下载（约 ${ToolSizes.text(size)}）"
    }
    return null
}

// ---------- 视频、音频 ----------

/** 要下的媒体：直链文件、HLS 播放列表、分开的视频和音频（DASH）。 */
internal sealed class Pick {
    data class File(val url: String) : Pick()
    data class Hls(val url: String) : Pick()
    data class Dash(val video: String?, val audio: String?) : Pick()
}

/**
 * 下好一个视频（或只要声音），拼成 mp4 / m4a，存进对话文件夹或手机。返回 (存好的, 补充说明)。
 * 中间文件放在对话文件夹的 .cache 里，做完删掉。
 */
private suspend fun ToolSession.grabMedia(req: MediaGrab.Req, pick: Pick, title: String, audioOnly: Boolean, phone: Boolean): Pair<Downloads.Saved, String> =
    withContext(Dispatchers.IO) {
        val work = File(wsCache, "media-${System.currentTimeMillis()}").apply { mkdirs() }
        val base = Workspace.safeName(title.replace(Regex("""[_-]?\s*(哔哩哔哩|bilibili|抖音|YouTube)[^\n]*$""", RegexOption.IGNORE_CASE), "").trim().take(50)).ifBlank { "视频" }
        val notes = mutableListOf<String>()
        try {
            fun fetch(url: String, into: String): File {
                var f: File? = null
                BrowserDownload.download(BrowserDownload.Pending(url, into, "", -1, req.userAgent, req.referer), req.cookieOf(url), req.route,
                    BrowserDownload.HARD_LIMIT, { n, _ -> File(work, n).also { f = it }.let { file ->
                        object : BrowserDownload.Sink {
                            override fun open(append: Boolean) = java.io.FileOutputStream(file, append)
                            override fun discard() { file.delete() }
                        }
                    } }, progress = { got, total -> ui.progress(progressLine(base, got, total)) })
                return f!!
            }
            // 先都下到 work 里，最后得到一个成品 out
            val out: File = when (pick) {
                is Pick.File -> {
                    val f = fetch(pick.url, "")
                    if (audioOnly) remux(listOf(f), File(work, "$base.m4a"), true, notes) ?: f
                    else f
                }
                is Pick.Hls -> {
                    val v = File(work, "video.ts")
                    val a = File(work, "audio.ts")
                    val (separate, _) = MediaGrab.hls(req, pick.url, v, a, BrowserDownload.HARD_LIMIT, MAX_HEIGHT) { done, total, got ->
                        ui.progress("下载「${base.take(30)}」第 $done/$total 段（${ToolSizes.text(got)}）")
                    }
                    val inputs = if (separate) listOf(v, a) else listOf(v)
                    remux(inputs, File(work, if (audioOnly) "$base.m4a" else "$base.mp4"), audioOnly, notes)
                        ?: v.also { if (separate) notes += "声音是单独的一条，没能合进去" }
                }
                is Pick.Dash -> {
                    val v = if (!audioOnly) pick.video?.let { fetch(it, "video.m4s") } else null
                    val a = pick.audio?.let { fetch(it, "audio.m4s") }
                    if (a == null && !audioOnly) notes += "没找到单独的音轨，存下来的视频可能没有声音"
                    val inputs = listOfNotNull(v, a)
                    if (inputs.isEmpty()) throw BrowserDownload.DownloadException(if (audioOnly) "没找到音轨" else "没找到视频")
                    remux(inputs, File(work, if (audioOnly || v == null) "$base.m4a" else "$base.mp4"), audioOnly || v == null, notes)
                        ?: throw BrowserDownload.DownloadException("视频和声音下好了，但没能拼在一起（编码手机不支持）")
                }
            }
            // 成品名字：标题 + 扩展名
            val ext = out.extension.ifBlank { "mp4" }.let { if (it == "m4s") "mp4" else it }
            val finalName = "$base.$ext"
            val mime = when (ext.lowercase()) {
                "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "ts" -> "video/mp2t"; "webm" -> "video/webm"; "flv" -> "video/x-flv"; "mov" -> "video/quicktime"
                else -> if (ext in setOf("aac", "ogg", "opus", "wav", "flac")) "audio/$ext" else "video/mp4"
            }
            val saved = if (phone) Downloads.copyToPhone(app, out.let { f -> File(work, finalName).takeIf { f.renameTo(it) } ?: f }, mime)
            else {
                val dst = Workspace.uniqueFile(wsDir, finalName)
                if (!out.renameTo(dst)) { out.copyTo(dst, overwrite = true) }
                Downloads.Saved(dst.name, android.net.Uri.fromFile(dst), mime, "对话文件夹", dst)
            }
            // 网页里的视频重下要重新打开网页，记录里不留网址
            Downloads.log(app, saved, "")
            saved to notes.joinToString("；")
        } finally {
            work.deleteRecursively()
        }
    }

/** 换成 mp4 / m4a；拼不了返回 null（原因记进 notes）。 */
private fun remux(inputs: List<File>, out: File, audioOnly: Boolean, notes: MutableList<String>): File? = try {
    MediaMux.mux(inputs, out, audioOnly)
    out
} catch (e: Exception) {
    notes += (if (audioOnly) "没能单独取出声音" else "没能转成 mp4") + "（${e.message ?: e.javaClass.simpleName}），存的是原来的格式"
    null
}

/** 视频页挑要下哪个：B 站的播放信息 > 网页播放器的地址 > 拦截到的 HLS > 分开的音视频 > 直链。挑不出返回 (null, 为什么)。 */
private fun choose(p: JackBrowser.MediaPage, req: MediaGrab.Req): Pair<Pick?, String> {
    val site = BrowserPages.site(p.url)
    if (site.endsWith("youtube.com") || site == "youtu.be") return null to
        "YouTube 的视频是加密分段传的，Jack 存不下来。要内容的话可以用 extract what=subtitles 取字幕。"
    p.playinfo?.let { MediaGrab.bilibili(it, MAX_HEIGHT) }?.let { pi ->
        if (pi.direct != null) return Pick.File(pi.direct) to "B 站"
        if (pi.video != null || pi.audio != null) return Pick.Dash(pi.video, pi.audio) to "B 站 ${pi.quality}"
    }
    fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
    // 网页里的播放器：正在放的、画面大的优先
    val els = p.elements.sortedWith(compareByDescending<JsonObject> { it.s("playing") == "true" }.thenByDescending { it.s("area").toDoubleOrNull() ?: 0.0 })
    for (e in els) {
        val src = (e["srcs"] as? JsonArray).orEmpty().map { it.text() }.firstOrNull { it.startsWith("http", true) } ?: continue
        return (if (MediaGrab.kindOf(src) == "hls") Pick.Hls(src) else Pick.File(src)) to "网页播放器"
    }
    // 网页播放器用的是 blob:（分段加载）：从拦截到的请求里找
    p.hits.firstOrNull { it.kind == "hls" }?.let { return Pick.Hls(it.url) to "播放列表" }
    val dash = p.hits.filter { it.kind == "dash" }.asReversed().take(6)
    if (dash.isNotEmpty()) {
        var video: String? = null
        var audio: String? = null
        for (h in dash) {
            val (type, _) = runCatching { MediaGrab.probe(req, h.url) }.getOrDefault(null to -1L)
            if (type == "video" && video == null) video = h.url
            if (type == "audio" && audio == null) audio = h.url
            if (video != null && audio != null) break
        }
        if (video != null || audio != null) return Pick.Dash(video, audio) to "分开的音视频"
    }
    p.hits.lastOrNull { it.kind == "file" }?.let { return Pick.File(it.url) to "视频地址" }
    val why = if (p.elements.isNotEmpty()) "网页里有播放器，但还没抓到视频地址：先让视频放起来（click 播放按钮，等几秒），再 download_media。"
        else "这一页没找到视频或音频。视频要是在别的页面（点进详情页），先打开那一页；还没开始放的，先点播放再试。"
    return null to why
}

/** browser action=download_media：存当前网页里的视频（audio_only 只要声音）。 */
internal suspend fun ToolSession.downloadMedia(args: JsonObject): Pair<String, SearchRecord> {
    val audioOnly = args["audio_only"].text() == "true"
    val label = if (audioOnly) "存网页里的音频" else "存网页里的视频"
    val page = JackBrowser.mediaPage(app)
    // 拿 Cookie 要在主线程：先把要用到的网站的都取好，下载线程里直接查
    val urls = buildList {
        page.playinfo?.let { MediaGrab.bilibili(it, MAX_HEIGHT) }?.let { addAll(listOfNotNull(it.video, it.audio, it.direct)) }
        page.elements.forEach { e -> (e["srcs"] as? JsonArray).orEmpty().forEach { add(it.text()) } }
        page.hits.forEach { add(it.url) }
    }.filter { it.startsWith("http", true) }
    val jar = urls.map(::hostOf).distinct().take(10).associateWith { h -> JackBrowser.cookieFor("https://$h/", page.tab) }
    val withCookies = MediaGrab.Req(JackBrowser.userAgent(), page.url, { u -> jar[hostOf(u)] }, JackBrowser.routeFor(app))
    val (pick, from) = withContext(Dispatchers.IO) { choose(page, withCookies) }
    if (pick == null) return from to SearchRecord("$label：没找到")
    val (saved, note) = grabMedia(withCookies, pick, page.title, audioOnly, args.toPhone())
    val where = if (saved.file != null) "对话文件夹" else saved.where
    return ("已存下${if (audioOnly) "音频" else "视频"}${told(saved)}到$where（来源：$from）。" +
        (if (note.isNotBlank()) "注意：$note。" else "") +
        "只供用户自己看、听；别帮着传播有版权的内容。") to SearchRecord(label)
}

// ---------- 整页批量下 ----------

/** browser action=download_all：当前页面上的图片（kind=images）、附件（files）或都要（all），放进一个文件夹。 */
internal suspend fun ToolSession.downloadAll(args: JsonObject): Pair<String, SearchRecord> {
    val kind = args["kind"].text().trim().takeIf { it in setOf("images", "files", "all") } ?: "all"
    val max = (args["count"].text().toIntOrNull() ?: 50).coerceIn(1, MAX_BATCH)
    val filter = args["text"].text().trim()
    val phone = args.toPhone()
    val what = when (kind) { "images" -> "图片"; "files" -> "附件"; else -> "图片和附件" }
    val label = "存网页上的$what"
    val (total, items) = JackBrowser.collectDownloads(app, kind, filter, max)
    if (items.isEmpty()) return ("这一页上没找到$what" + (if (filter.isNotBlank()) "（含「$filter」的）" else "") +
        "。图片是往下划才加载的，先 scroll 几屏再试；附件要点了才出来的，点下载按钮后用 action=download。") to SearchRecord("$label：没找到")
    val title = JackBrowser.title.ifBlank { BrowserPages.site(JackBrowser.address) }
    val folder = Workspace.safeName(title.take(30)).ifBlank { "网页下载" }
    val dir = Workspace.uniqueFile(wsDir, folder).also { it.mkdirs() }
    val referer = JackBrowser.address
    val ua = JackBrowser.userAgent()
    val tabId = JackBrowser.tab.id
    val saved = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, Downloads.Saved>>()
    val failed = java.util.concurrent.ConcurrentLinkedQueue<String>()
    val done = java.util.concurrent.atomic.AtomicInteger()
    val bytes = java.util.concurrent.atomic.AtomicLong()
    val gate = Semaphore(3)
    coroutineScope {
        items.mapIndexed { i, (url, name) ->
            async {
                gate.withPermit {
                    if (bytes.get() > BrowserDownload.HARD_LIMIT) { failed += "${url.take(60)}：一共超过 2 GB，后面的不下了"; return@withPermit }
                    val prefix = "%03d-".format(i + 1)
                    // 附件用链接上写的名字（带扩展名的话），图片按网址起名
                    val given = name.takeIf { kind != "images" && Regex("""\.[A-Za-z0-9]{2,5}$""").containsMatchIn(it) }.orEmpty()
                    val p = BrowserDownload.Pending(url, given, "", -1, ua, referer, tab = tabId)
                    try {
                        val s = JackBrowser.store(p, { n, mime ->
                            if (phone) Downloads.toPhone(app, prefix + n, mime, folder) else Downloads.toDir(app, dir, prefix + n, mime)
                        })
                        saved += i to s
                        bytes.addAndGet(s.file?.length() ?: 0)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += "${url.take(60)}：${e.message ?: e.javaClass.simpleName}"
                    }
                    ui.progress("存网页上的$what ${done.incrementAndGet()}/${items.size}")
                }
            }
        }.awaitAll()
    }
    val list = saved.sortedBy { it.first }.map { it.second }
    if (list.isEmpty()) {
        dir.deleteRecursively()
        throw IllegalStateException("找到 ${items.size} 个$what，一个都没下成：" + failed.take(5).joinToString("；"))
    }
    if (phone) dir.deleteRecursively()
    val text = buildString {
        append("已存下 ${list.size} 个$what")
        if (phone) append("到手机的「下载/${Downloads.PHONE_FOLDER}/$folder」文件夹，用户在文件管理器或相册里能找到。")
        else {
            append("到对话文件夹的 ${dir.name}/ 里（按网页上的顺序编了号）。")
            if (list.size <= 4) list.forEach { told(it) }
            else {
                // 文件多：打成一个压缩包挂出来，用户一下就能整个分享
                val zip = withContext(Dispatchers.IO) { runCatching { zipDir(dir, Workspace.uniqueFile(wsDir, "${dir.name}.zip")) }.getOrNull() }
                if (zip != null) { ui.attach(zip); append("另外打了个压缩包「${zip.name}」（${ToolSizes.text(zip.length())}）挂在聊天里，方便整个分享。") }
            }
            append("要处理（挑图、改名、转格式）可以用 run_python，路径写 \"${dir.name}/…\"。")
        }
        if (total > items.size) append("页面上一共有 $total 个，这次只下了前 ${items.size} 个（count 最多 $MAX_BATCH）。")
        if (failed.isNotEmpty()) append("\n没下成 ${failed.size} 个：" + failed.take(5).joinToString("；") + if (failed.size > 5) "……" else "")
    }
    return text to SearchRecord(label)
}

/** 把文件夹打成 zip（图片本来就压缩过，直接存不再压，快）。 */
private fun zipDir(dir: File, out: File): File {
    java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
        dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { f ->
            z.putNextEntry(java.util.zip.ZipEntry("${dir.name}/${f.name}"))
            f.inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
    }
    return out
}
