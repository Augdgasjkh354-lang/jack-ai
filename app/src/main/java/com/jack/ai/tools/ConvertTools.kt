package com.jack.ai.tools

import com.jack.ai.api.Transcriber
import com.jack.ai.data.media.AudioPcm
import com.jack.ai.data.media.MediaMux
import com.jack.ai.data.phone.PyRunner
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.media.Subtitles
import com.jack.ai.data.integrations.ToolSizes
import com.jack.ai.data.media.VideoEdit
import com.jack.ai.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 文件工具箱（convert_file）：图片转 PDF、合并拆分 PDF、PDF 转图片、压缩图片、换图片格式、
 * 剪视频、压视频、剪辑（拼片段、烧字幕、配音乐）、取出声音、录音和视频转文字（可以带时间，出字幕）。都在对话文件夹里做，原文件不动，结果另存一份挂在聊天里。
 */

private val ACTIONS = listOf("images_to_pdf", "merge_pdf", "split_pdf", "pdf_to_images", "compress_image", "convert_image",
    "cut_video", "compress_video", "edit_video", "extract_audio", "transcribe", "ocr", "watch_video", "make_video", "beats", "voiceover", "grab_frame", "to_gallery")

/** ocr 收哪些文件（DeepSeek OCR 认文件内容，不看扩展名，这里先挡掉明显不对的）。 */
private val OCR_EXT = setOf("pdf", "doc", "docx", "ppt", "pptx", "png", "jpg", "jpeg", "webp", "gif", "bmp", "tif", "tiff")

/** 转文字最长多久（再长要几十分钟，也贵）。 */
private const val MAX_TRANSCRIBE_SEC = 3L * 3600

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

/** 对话文件夹里的文件：认「文件名」，也认「子文件夹/文件名」（批量下载存的）；是文件夹就展开成里面的文件（按名字排）。 */
private fun ToolSession.inputs(names: List<String>): List<File> = names.flatMap { n ->
    val direct = File(wsDir, n.trim().trimStart('/')).takeIf { it.exists() && it.canonicalPath.startsWith(wsDir.canonicalPath + File.separator) }
    when {
        direct?.isDirectory == true -> direct.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.name }
        direct != null -> listOf(direct)
        else -> listOf(wsFile(n))
    }
}

/** 结果文件名：给了 out 就用它（补上扩展名），没给就「原名-后缀.扩展名」。 */
private fun ToolSession.outFile(out: String, from: File, suffix: String, ext: String): File {
    val name = out.trim().takeIf { it.isNotEmpty() }?.let { if (it.substringAfterLast('.', "").equals(ext, true)) it else "$it.$ext" }
        ?: "${from.nameWithoutExtension}-$suffix.$ext"
    return Workspace.uniqueFile(wsDir, name)
}

internal suspend fun ToolSession.convertFile(args: JsonObject): String {
    val action = args["action"].text().trim()
    if (action !in ACTIONS) return "action 要填 ${ACTIONS.joinToString("、")} 之一"
    if (action == "make_video") return makeVideo(args)
    if (action == "voiceover") return voiceover(args)
    val names = (args["files"] as? JsonArray)?.map { it.text() }?.filter { it.isNotBlank() }
        ?: listOfNotNull(args["file"].text().takeIf { it.isNotBlank() })
    if (names.isEmpty()) return "$action 要填 file（对话文件夹里的文件名）" + if (action in setOf("images_to_pdf", "merge_pdf")) "，或者 files（几个文件，按顺序；也能填一个文件夹）" else ""
    val label = when (action) {
        "images_to_pdf" -> "图片转 PDF"
        "merge_pdf" -> "合并 PDF"
        "split_pdf" -> "拆分 PDF"
        "pdf_to_images" -> "PDF 转图片"
        "compress_image" -> "压缩图片"
        "convert_image" -> "图片转成 ${args["format"].text().ifBlank { "jpg" }}"
        "cut_video" -> "剪视频"
        "compress_video" -> "压缩视频"
        "edit_video" -> "剪辑视频"
        "extract_audio" -> "取出声音"
        "ocr" -> "认字"
        "watch_video" -> "看视频"
        "beats" -> "找节拍"
        "grab_frame" -> "截图"
        "to_gallery" -> "存进相册"
        else -> "转文字"
    } + "：" + names.first().take(40) + if (names.size > 1) " 等 ${names.size} 个" else ""
    return runTool(label) {
        val files = withContext(Dispatchers.IO) { inputs(names) }
        val out = args["out"].text()
        val made: List<File>
        var note = ""
        when (action) {
            "images_to_pdf", "merge_pdf" -> {
                val want = if (action == "images_to_pdf") IMAGE_EXT else setOf("pdf")
                val use = files.filter { it.extension.lowercase() in want }
                if (use.isEmpty()) throw IllegalStateException(if (action == "images_to_pdf") "这些文件里没有图片" else "这些文件里没有 PDF")
                val dst = outFile(out, use.first(), if (action == "images_to_pdf") "合成" else "合并", "pdf")
                val r = py(if (action == "images_to_pdf") "images_to_pdf" else "merge_pdf", Json.encodeToString(JsonArray.serializer(), JsonArray(use.map { JsonPrimitive(it.absolutePath) })), dst.absolutePath)
                made = r.first; note = "${use.size} 个文件，" + r.second
            }
            "split_pdf" -> {
                val f = files.first()
                val r = py("split_pdf", f.absolutePath, args["pages"].text(), File(wsDir, Workspace.safeName(out.trim().removeSuffix(".pdf").ifBlank { f.nameWithoutExtension })).absolutePath)
                made = r.first; note = r.second
            }
            "pdf_to_images" -> {
                made = withContext(Dispatchers.IO) { pdfToImages(files.first(), args["pages"].text()) }
            }
            "compress_image", "convert_image" -> {
                val imgs = files.filter { it.extension.lowercase() in IMAGE_EXT }.ifEmpty { throw IllegalStateException("这些文件里没有图片") }
                if (imgs.size > 100) throw IllegalStateException("一次最多 100 张")
                val list = mutableListOf<File>()
                val notes = mutableListOf<String>()
                for (img in imgs) {
                    val r = if (action == "compress_image") {
                        val side = args["max_side"].text().toIntOrNull() ?: 1920
                        val q = (args["quality"].text().toIntOrNull() ?: 80).coerceIn(10, 95)
                        py("compress_image", img.absolutePath, outFile(if (imgs.size == 1) out else "", img, "压缩", "jpg").absolutePath, side, q)
                    } else {
                        val fmt = args["format"].text().trim().lowercase().removePrefix(".").ifBlank { "jpg" }
                        if (fmt !in setOf("jpg", "jpeg", "png", "webp", "pdf", "bmp", "gif")) throw IllegalStateException("format 要填 jpg、png、webp、pdf、bmp 或 gif")
                        py("convert_image", img.absolutePath, outFile(if (imgs.size == 1) out else "", img, "转换", if (fmt == "jpeg") "jpg" else fmt).absolutePath, fmt)
                    }
                    list += r.first; notes += r.second
                }
                made = list; note = if (imgs.size == 1) notes.first() else "${imgs.size} 张"
            }
            "cut_video", "compress_video" -> {
                val f = files.first()
                val (dur, w, h) = withContext(Dispatchers.IO) { VideoEdit.info(f) }
                if (dur <= 0 && w == 0) throw IllegalStateException("「${f.name}」读不出来，可能不是视频")
                val spec = if (action == "cut_video") {
                    val s = args["start"].text().takeIf { it.isNotBlank() }?.let { VideoEdit.parseTime(it) ?: throw IllegalStateException("start 写成 1:23 或秒数") }
                    val e = args["end"].text().takeIf { it.isNotBlank() }?.let { VideoEdit.parseTime(it) ?: throw IllegalStateException("end 写成 1:23 或秒数") }
                    if (s == null && e == null) throw IllegalStateException("cut_video 要填 start 或 end（从哪到哪，比如 start=0:30 end=1:45）")
                    if (s != null && e != null && e <= s) throw IllegalStateException("end 要比 start 晚")
                    if (dur > 0 && s != null && s >= dur) throw IllegalStateException("视频只有 ${AudioPcm.stamp(dur / 1000)} 长")
                    VideoEdit.Spec(startMs = s, endMs = e)
                } else {
                    val short = (args["height"].text().toIntOrNull() ?: 720).coerceIn(240, 2160)
                    val mb = args["size_mb"].text().toDoubleOrNull()
                    // 要压到多大：按时长算码率（留 128 kbps 给声音）
                    val bitrate = if (mb != null && dur > 0) ((mb * 8 * 1024 * 1024 / (dur / 1000.0)) - 128_000).toInt().coerceAtLeast(150_000)
                        else when { short <= 480 -> 1_200_000; short <= 720 -> 2_500_000; short <= 1080 -> 5_000_000; else -> 12_000_000 }
                    val shortNow = minOf(w, h).takeIf { it > 0 } ?: short
                    VideoEdit.Spec(height = if (short < shortNow) VideoEdit.heightFor(short, w, h) else null, bitrate = bitrate, mute = args["mute"].text() == "true")
                }
                val dst = outFile(out, f, if (action == "cut_video") "片段" else "压缩", "mp4")
                VideoEdit.run(app, f, dst, spec) { ui.progress("$label $it%") }
                made = listOf(dst)
                note = "${ToolSizes.text(f.length())} → ${ToolSizes.text(dst.length())}"
            }
            "edit_video" -> {
                val f = files.first()
                val (dur, w, _) = withContext(Dispatchers.IO) { VideoEdit.info(f) }
                if (dur <= 0 && w == 0) throw IllegalStateException("「${f.name}」读不出来，可能不是视频")
                val segSpec = (args["segments"] as? JsonArray)?.joinToString(",") { it.text() } ?: args["segments"].text()
                val segs = if (segSpec.isBlank()) null else {
                    val (list, err) = Subtitles.parseSegments(segSpec, VideoEdit::parseTime)
                    list ?: throw IllegalStateException(err)
                    if (dur > 0) list.firstOrNull { it.startMs >= dur }?.let { throw IllegalStateException("视频只有 ${Subtitles.stamp(dur)} 长，「${Subtitles.stamp(it.startMs)}」开始的那段超出了") }
                    // 结尾写过了头（模型按字幕估的）：截到视频结尾
                    if (dur > 0) list.map { it.copy(endMs = minOf(it.endMs, dur)) } else list
                }
                val srtName = args["subtitles"].text().trim()
                val cues = if (srtName.isEmpty()) emptyList() else withContext(Dispatchers.IO) { Subtitles.parseSrt(wsFile(srtName).readText()) }
                    .ifEmpty { throw IllegalStateException("「$srtName」里没读出字幕，要 .srt 格式（transcribe 加 timestamps=true 会做一份）") }
                val music = args["music"].text().trim().takeIf { it.isNotEmpty() }?.let { withContext(Dispatchers.IO) { inputs(listOf(it)).first() } }
                if (segs == null && cues.isEmpty() && music == null) throw IllegalStateException("edit_video 要填 segments（剪哪几段）、subtitles（字幕文件）、music（背景音乐）至少一个")
                val vol = (args["music_volume"].text().toFloatOrNull() ?: 0.3f).coerceIn(0f, 1f)
                val dst = outFile(out, f, "成片", "mp4")
                var tmp: File? = null
                try {
                    var src = f
                    if (segs != null) {
                        // 只剪拼的话一步到位；还要字幕、音乐就先拼到临时文件，再在拼好的上面加（时间都按成片算，最稳）
                        val target = if (cues.isEmpty() && music == null) dst else File(wsCache.apply { mkdirs() }, "edit-${System.currentTimeMillis()}.mp4").also { tmp = it }
                        VideoEdit.concat(app, f, segs, target) { ui.progress("$label 拼片段 $it%") }
                        src = target
                    }
                    if (cues.isNotEmpty() || music != null) {
                        val onScreen = if (segs != null) Subtitles.remap(cues, segs) else cues
                        val step = listOfNotNull("烧字幕".takeIf { cues.isNotEmpty() }, "配音乐".takeIf { music != null }).joinToString("、")
                        VideoEdit.decorate(app, src, dst, onScreen, music, vol) { ui.progress("$label $step $it%") }
                    }
                } finally {
                    tmp?.delete()
                }
                made = listOf(dst)
                val length = segs?.sumOf { it.endMs - it.startMs } ?: dur
                note = listOfNotNull(
                    segs?.let { "${it.size} 段拼成 ${Subtitles.stamp(length)}" },
                    "带字幕".takeIf { cues.isNotEmpty() }, music?.let { "配乐「${it.name}」" },
                ).joinToString("，") + "，${ToolSizes.text(dst.length())}"
            }
            "extract_audio" -> {
                val f = files.first()
                val dst = outFile(out, f, "声音", "m4a")
                withContext(Dispatchers.IO) { MediaMux.mux(listOf(f), dst, audioOnly = true) }
                made = listOf(dst)
            }
            "ocr" -> return@runTool ocr(files, out, args["layout"].text() == "true")
            "watch_video" -> return@runTool watchVideo(files.first(), args)
            "beats" -> return@runTool beats(files.first())
            "grab_frame" -> return@runTool grabFrames(files.first(), args)
            "to_gallery" -> return@runTool toGallery(files) to SearchRecord(label)
            else -> return@runTool if (args["timestamps"].text() == "true") transcribeTimed(files.first(), out) else transcribe(files.first(), out)
        }
        made.take(10).forEach { ui.attach(it) }
        val list = made.joinToString("、") { "「${it.name}」（${ToolSizes.text(it.length())}）" }
        ("做好了：$list" + (if (note.isNotBlank()) "（$note）" else "") + "，存在对话文件夹里" +
            (if (made.size > 10) "，聊天里只挂了前 10 个" else "") + "。原文件没动。") to SearchRecord(label)
    }
}

/** 视频、图片存进手机相册（剪映、抖音这类 App 选素材时从相册里挑）。 */
private suspend fun ToolSession.toGallery(files: List<File>): String {
    val use = files.filter { com.jack.ai.data.Gallery.kindOf(it) != null }
    if (use.isEmpty()) throw IllegalStateException("只能存视频和图片")
    if (use.size > 30) throw IllegalStateException("一次最多 30 个")
    val saved = withContext(Dispatchers.IO) { use.map { it to com.jack.ai.data.Gallery.save(app, it) } }
    val ok = saved.filter { it.second != null }
    if (ok.isEmpty()) throw IllegalStateException("没存进去（相册写不进去，可能存储满了）")
    val bad = saved.filter { it.second == null }.map { it.first.name }
    return "存进相册了：" + ok.joinToString("、") { "「${it.second}」" } + "（视频在「Movies/${com.jack.ai.data.Gallery.FOLDER}」，图片在「Pictures/${com.jack.ai.data.Gallery.FOLDER}」）。" +
        "别的 App 选素材时按时间排在最前面（存的顺序就是这次给的顺序，越后存的越靠前）。" +
        (if (bad.isNotEmpty()) "这几个没存进去：" + bad.joinToString("、") + "。" else "")
}

/** 调 jack_files.py 里的函数：(做出来的文件, 说明)。 */
private suspend fun ToolSession.py(fn: String, vararg args: Any): Pair<List<File>, String> {
    val raw = PyRunner.call(app, "jack_files", fn, *args)
    val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: throw IllegalStateException("处理出错：${raw.take(200)}")
    if ((o["ok"] as? JsonPrimitive)?.content != "true") throw IllegalStateException("处理出错：" + o["error"].text())
    return (o["files"] as? JsonArray).orEmpty().map { File(it.text()) } to o["note"].text()
}

/** PDF 每页存成一张 PNG（手机自带的 PdfRenderer，宽 1600 像素，最多 50 页）。pages 同拆分的写法，空 = 全部。 */
private fun ToolSession.pdfToImages(f: File, pages: String): List<File> {
    val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    val r = try { android.graphics.pdf.PdfRenderer(pfd) } catch (e: Exception) { pfd.close(); throw IllegalStateException("「${f.name}」打不开（可能加了密码或不是 PDF）") }
    return r.use { renderer ->
        val n = renderer.pageCount
        val want = pageList(pages, n).take(50)
        want.map { i ->
            renderer.openPage(i).use { page ->
                val w = 1600
                val h = (w.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, 6000)
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = Workspace.uniqueFile(wsDir, "${f.nameWithoutExtension}-第${i + 1}页.png")
                out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                out
            }
        }
    }
}

/** 「1-3,5,8-」→ 从 0 起的页号（超出范围的去掉）；空的就全部。 */
internal fun pageList(spec: String, n: Int): List<Int> {
    if (spec.isBlank()) return (0 until n).toList()
    return spec.replace('，', ',').split(',').flatMap { part ->
        val p = part.trim()
        if (p.isEmpty()) return@flatMap emptyList()
        val (a, b) = if ('-' in p) (p.substringBefore('-').trim().toIntOrNull() ?: 1) to (p.substringAfter('-').trim().toIntOrNull() ?: n)
            else (p.toIntOrNull() ?: return@flatMap emptyList()).let { it to it }
        (maxOf(1, a)..minOf(n, b)).map { it - 1 }
    }.distinct()
}

/** 找音乐的节拍（卡点）：BPM、每一拍和重拍的时间。 */
private suspend fun ToolSession.beats(f: File): Pair<String, SearchRecord> {
    val on = com.jack.ai.data.media.Beats.Onsets()
    withContext(Dispatchers.IO) {
        MediaMux.decode16k(f, 20 * 60L, { at, total -> if (total > 0) ui.progress("找节拍：${at * 100 / total}%") }) { on.push(it) }
    }
    val r = withContext(Dispatchers.Default) { com.jack.ai.data.media.Beats.detect(on.flux(), on.samples) }
        ?: return "「${f.name}」太短或者听不出节奏（要 4 秒以上、有鼓点的音乐）。" to SearchRecord("找节拍：${f.name}")
    return com.jack.ai.data.media.Beats.describe(r, f.name) + "\n卡点：让每段的长度等于两个重拍（或拍子）之间的间隔，段落切换就落在拍子上。" to
        SearchRecord("找节拍：${f.name}（${r.bpm.toInt()} BPM）")
}

/**
 * 配音：几句文案用 DeepSeek 语音读出来，接成一条 wav（句子之间停一下），同时出一份按这条配音时间的 .srt。
 * 每句单独合成，所以每句从几秒到几秒是准的，Kavi 照着安排画面。
 * 给了 srt（原视频带时间的文字稿）就是翻译配音：每句放回原句开始的时间，长了加速，放不下的标出来让 Kavi 缩短（[com.jack.ai.data.media.Dub]）。
 * 每句的声音按「声音 + 文字」存在缓存里，改几句再配只重配改了的。配完默认回听核对一遍（[com.jack.ai.data.media.SpeechCheck]）。
 */
private suspend fun ToolSession.voiceover(args: JsonObject): String {
    val srtName = args["srt"].text().trim()
    val timing = if (srtName.isEmpty()) null else {
        val f = runCatching { withContext(Dispatchers.IO) { inputs(listOf(srtName)).first() } }.getOrElse { return it.message ?: "对话文件夹里没有「$srtName」" }
        withContext(Dispatchers.IO) { Subtitles.parseSrt(f.readText()) }.ifEmpty { return "「$srtName」里没读出字幕：要 .srt（transcribe 加 timestamps=true 做的那份）" }
    }
    val given = ((args["lines"] as? JsonArray)?.map { it.text() } ?: args["text"].text().takeIf { it.isNotBlank() }?.split('\n').orEmpty()).map { it.trim() }
    val lines = when {
        timing == null -> given.filter { it.isNotEmpty() }
        given.isEmpty() -> timing.map { it.text.replace('\n', ' ').trim() }
        given.size != timing.size -> return "lines 要和「$srtName」一句对一句：它有 ${timing.size} 句，你给了 ${given.size} 句（某句不配就写空字符串）"
        else -> given
    }
    if (lines.none { it.isNotEmpty() }) return "voiceover 要写 lines：配音文案，一句一项（一句就是一屏字幕，10～20 个字）；翻译配音再给 srt=原视频的字幕"
    val maxLines = if (timing != null) 400 else 80
    if (lines.size > maxLines) return "一次最多 $maxLines 句，现在 ${lines.size} 句：分两段配"
    val chars = lines.sumOf { com.jack.ai.api.DeepSeekTools.ttsChars(it) }
    val maxChars = if (timing != null) 30_000 else 6000
    if (chars > maxChars) return "文案太长（$chars 计费字符，上限 $maxChars）" + if (timing == null) "：短视频配音一般几百字，精简一下" else "：视频分段配"
    val key = settings.apiKey.takeIf { it.isNotBlank() } ?: return "配音要用 DeepSeek Key（设置里填的那个），现在没填"
    val voice = args["voice"].text().ifBlank { settings.dsVoiceId }.let { if (it == "tide") "tide" else "stella" }
    val pauseMs = ((args["pause"].text().toDoubleOrNull() ?: 0.25) * 1000).toLong().coerceIn(0, 2000)
    val name = args["out"].text().trim().substringBeforeLast('.').ifBlank { if (timing != null) "翻译配音" else "配音" }
    val check = args["check"]?.text() != "false"
    val videoName = args["file"].text().trim()
    return runTool((if (timing != null) "翻译配音" else "配音") + "：${lines.count { it.isNotEmpty() }} 句") {
        val rate = com.jack.ai.api.DeepSeekTools.TTS_RATE
        val cache = File(wsCache, "tts").apply { mkdirs() }
        fun keyOf(s: String) = java.security.MessageDigest.getInstance("SHA-1").digest("$voice|$s".toByteArray()).joinToString("") { "%02x".format(it) }
        val pcm = arrayOfNulls<ByteArray>(lines.size)
        var done = 0; var fresh = 0
        coroutineScope {
            val gate = Semaphore(3)
            lines.mapIndexed { i, line ->
                async(Dispatchers.IO) {
                    if (line.isEmpty()) { pcm[i] = ByteArray(0); return@async }
                    val hit = File(cache, keyOf(line) + ".pcm")
                    if (hit.isFile) { pcm[i] = hit.readBytes(); return@async }
                    gate.withPermit {
                        val raw = com.jack.ai.api.DeepSeekTools.ttsStream(key, line, voice).use { it.readBytes() }
                        host.store.addPaidCall(com.jack.ai.api.DeepSeekTools.TTS_KEY, com.jack.ai.api.DeepSeekTools.ttsChars(line) * com.jack.ai.api.DeepSeekTools.TTS_PRICE_PER_CHAR)
                        val t = com.jack.ai.data.media.VoiceTrack.trim(raw, rate)
                        pcm[i] = t
                        runCatching { hit.writeBytes(t) }
                        ui.progress("配音：${synchronized(pcm) { fresh++; ++done }}/${lines.count { it.isNotEmpty() }} 句")
                    }
                }
            }.awaitAll()
        }
        val parts = pcm.map { it!! }
        val cues: List<Subtitles.Cue>
        val audio: ByteArray
        val heardParts: List<ByteArray>
        var report = ""
        if (timing != null) {
            val dur = parts.map { it.size * 1000L / (rate * 2) }
            val places = com.jack.ai.data.media.Dub.plan(timing, dur)
            val sped = parts.mapIndexed { i, p -> places[i]?.let { com.jack.ai.data.media.Dub.stretch(p, rate, it.speed) } ?: ByteArray(0) }
            val videoMs = videoName.takeIf { it.isNotEmpty() }?.let { n ->
                runCatching { withContext(Dispatchers.IO) { VideoEdit.info(inputs(listOf(n)).first()).first } }.getOrNull()
            } ?: 0L
            audio = com.jack.ai.data.media.Dub.lay(places, sped, rate, videoMs)
            cues = places.mapIndexedNotNull { i, p -> p?.let { Subtitles.Cue(it.startMs, it.endMs, lines[i]) } }
            heardParts = sped
            val long = places.withIndex().filter { it.value?.fit == com.jack.ai.data.media.Dub.Fit.LONG }
            val fast = places.count { it?.fit == com.jack.ai.data.media.Dub.Fit.FAST }
            report = buildString {
                append("对时间：${cues.size} 句，${cues.size - long.size - fast} 句原速放得下，$fast 句稍微加速（最多 ${com.jack.ai.data.media.Dub.MAX_SPEED}×）")
                if (long.isEmpty()) append("，都在原句的位置上。")
                else {
                    append("，${long.size} 句太长、往后挤了，要缩短译文（意思保留，删修饰、换短的说法）：\n")
                    long.forEach { (i, p) ->
                        p!!
                        append("${i + 1}. [${Subtitles.stamp(timing[i].startMs)}] 原句 ${"%.1f".format(p.slotMs / 1000.0)} 秒，配音要 ${"%.1f".format(p.needMs / 1000.0)} 秒" +
                            (if (p.lateMs > 0) "，晚了 ${"%.1f".format(p.lateMs / 1000.0)} 秒" else "") + "：「${lines[i]}」→ 缩到 ${com.jack.ai.data.media.Dub.shorter(lines[i], p)} 个字左右\n")
                    }
                    append("改好这几句，lines 其他句子照旧、srt 不变再配一次（没改的句子用缓存，不重复花钱）。")
                }
            }
        } else {
            val joined = com.jack.ai.data.media.VoiceTrack.join(lines, parts, rate, pauseMs)
            audio = joined.first; cues = joined.second
            heardParts = parts
        }
        val wav = Workspace.uniqueFile(wsDir, "$name.wav")
        val srt = File(wsDir, wav.nameWithoutExtension + ".srt")
        withContext(Dispatchers.IO) {
            com.jack.ai.data.media.VideoCompose.Wav.write(wav, audio, rate)
            srt.writeText(Subtitles.toSrt(cues))
        }
        ui.attach(wav)
        val qc = if (!check) "" else listenBack(lines, heardParts, rate, cache) { keyOf(it) }
        val total = audio.size * 1000L / (rate * 2)
        val head = "配好了：「${wav.name}」（${Subtitles.stamp(total)}，${if (voice == "tide") "男声" else "女声"}" +
            (if (fresh < lines.count { it.isNotEmpty() }) "，新配 $fresh 句，其余用上次配好的" else "") + "），字幕「${srt.name}」"
        val text = if (timing != null) {
            "$head，按原视频的时间排好了。\n$report" + (if (qc.isNotEmpty()) "\n\n$qc" else "") +
                "\n\n出片：make_video，clips=[{file=原视频, volume=0.1（留一点环境声；要完全换掉写 0）}]，voiceover=「${wav.name}」，subtitles=「${srt.name}」，subtitles_source=\"output\"；别加 cover（会把配音往后挪、对不上嘴）。"
        } else {
            "$head，每句的时间：\n" + Subtitles.timed(cues) + (if (qc.isNotEmpty()) "\n\n$qc" else "") +
                "\n\n出片时 plan 里写 voiceover=「${wav.name}」、subtitles=「${srt.name}」、subtitles_source=\"output\"；按每句的时间安排画面（这句在说什么就放什么镜头），画面总长不短于 ${Subtitles.stamp(total)}；" +
                "素材原声一般关掉（volume 0）或压低（0.2），背景音乐 music_volume 0.15～0.25。"
        }
        text to SearchRecord("配音：${wav.name}")
    }
}

/** 回听：每句配音再转成文字和文案比（转过的按「声音 + 文字 + 长度」缓存）。没有能转文字的服务就跳过。 */
private suspend fun ToolSession.listenBack(lines: List<String>, parts: List<ByteArray>, rate: Int, cache: File, keyOf: (String) -> String): String {
    val engine = Transcriber.engine(settings) ?: return "（没做回听核对：没有能转文字的服务）"
    val heard = arrayOfNulls<String>(lines.size)
    var done = 0
    val todo = lines.indices.filter { lines[it].isNotEmpty() && parts[it].isNotEmpty() }
    try {
        coroutineScope {
            val gate = Semaphore(3)
            todo.map { i ->
                async(Dispatchers.IO) {
                    val hit = File(cache, keyOf(lines[i]) + "-" + parts[i].size + ".heard")
                    if (hit.isFile) { heard[i] = hit.readText(); return@async }
                    gate.withPermit {
                        val w = File(cache, "listen-$i-${System.nanoTime()}.wav")
                        try {
                            com.jack.ai.data.media.VideoCompose.Wav.write(w, parts[i], rate)
                            val t = transcribeRetrying(engine, w)
                            heard[i] = t
                            runCatching { hit.writeText(t) }
                        } finally { w.delete() }
                        ui.progress("回听核对：${synchronized(heard) { ++done }}/${todo.size} 句")
                    }
                }
            }.awaitAll()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        return "（回听核对没做完：${e.message?.take(80)}）"
    }
    return com.jack.ai.data.media.SpeechCheck.format(com.jack.ai.data.media.SpeechCheck.check(lines, heard.toList()), todo.size)
}

/** 从视频里截几张图（做封面、填设计的图片位用），存成 PNG。 */
private suspend fun ToolSession.grabFrames(f: File, args: JsonObject): Pair<String, SearchRecord> {
    val times = (args["times"] as? JsonArray)?.map { it.text() } ?: args["times"].text().ifBlank { args["start"].text() }.split(',', '，', ' ').filter { it.isNotBlank() }
    if (times.isEmpty()) return "grab_frame 要写 times：截哪几个时间点，比如 \"1.5, 8, 0:12\"（先 watch_video 看哪一帧好看）" to SearchRecord("截图")
    val ms = times.take(12).map { VideoEdit.parseTime(it) ?: return "「$it」认不出，写成 1:23 或秒数" to SearchRecord("截图") }
    val made = withContext(Dispatchers.IO) {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            ms.mapNotNull { t ->
                val b = r.getFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST) ?: return@mapNotNull null
                val out = Workspace.uniqueFile(wsDir, "${f.nameWithoutExtension}-${Subtitles.stamp(t).replace(':', '-').replace('.', '_')}.png")
                out.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
                out
            }
        } finally { r.release() }
    }
    if (made.isEmpty()) return "一张都没截出来（时间超出了视频长度，或者这个格式手机解不了）" to SearchRecord("截图：${f.name}")
    made.forEach { ui.attach(it) }
    return ("截好了：" + made.joinToString("、") { "「${it.name}」" } + "。做封面：design 套一个模板（或从头设计），图片位填这张，标题写钩子；出片时 plan 里写 cover=封面 PNG。") to SearchRecord("截图：${f.name}")
}

/** 照剪辑方案出片：素材都在对话文件夹里，方案的写法见 VideoPlan。 */
private suspend fun ToolSession.makeVideo(args: JsonObject): String {
    val raw = args["plan"]
    val o = (raw as? JsonObject) ?: runCatching { Json.parseToJsonElement(raw.text()).jsonObject }.getOrNull()
        ?: return "make_video 要写 plan：剪辑方案（clips、texts、subtitles、music……），格式见工具说明"
    val (plan, err) = com.jack.ai.data.media.VideoPlan.parse(o) { it.substringAfterLast('.', "").lowercase() in IMAGE_EXT }
    if (plan == null) return "剪辑方案有问题：$err。改好再调。"
    val names = (plan.clips.map { it.file } + listOfNotNull(plan.subtitles, plan.music, plan.voiceover)).distinct()
    val files = mutableMapOf<String, File>()
    for (n in names) files[n] = runCatching { withContext(Dispatchers.IO) { inputs(listOf(n)).first() } }.getOrElse { return it.message ?: "对话文件夹里没有「$n」" }
    val first = files.getValue(plan.clips.first().file)
    return runTool("剪辑出片：${plan.clips.size} 段" + (plan.music?.let { "，配乐「$it」" } ?: "")) {
        val subs = plan.subtitles?.let { name ->
            val cues = withContext(Dispatchers.IO) { Subtitles.parseSrt(files.getValue(name).readText()) }
            if (cues.isEmpty()) throw IllegalStateException("「$name」里没读出字幕，要 .srt（transcribe 加 timestamps=true 会做一份）")
            val source = plan.subtitleSource ?: plan.clips.firstOrNull { !it.image }?.file ?: com.jack.ai.data.media.VideoPlan.OUTPUT
            cues to source
        }
        val dst = outFile(args["out"].text(), first, "成片", "mp4")
        val work = File(wsCache, "compose-${System.currentTimeMillis()}")
        try {
            val d = com.jack.ai.data.media.VideoCompose.make(app, plan, { files.getValue(it) }, subs, plan.music?.let { files.getValue(it) }, plan.voiceover?.let { files.getValue(it) }, work, dst) { ui.progress(it) }
            ui.attach(dst)
            val text = buildString {
                append("出好了：「${dst.name}」（${Subtitles.stamp(d.durationMs).substringBefore('.')}，${d.width}×${d.height}，${ToolSizes.text(dst.length())}），已经显示在聊天里。")
                if (d.subtitleCount > 0) append("字幕 ${d.subtitleCount} 句。")
                d.notes.forEach { append(it).append("。") }
                append("\n交给用户前可以用 watch_video 看一眼成片（字幕、花字的位置，卡点准不准）；要改就改方案再出一次，原素材都没动。")
            }
            text to SearchRecord("剪辑出片：${dst.name}")
        } finally {
            work.deleteRecursively()
        }
    }
}

/** 自动转文字只做这么长以内的视频（再长的花得多，让 Kavi 先问）。 */
private const val WATCH_LISTEN_MAX_MS = 30 * 60_000L

/**
 * 看视频：抽帧拼成带时间的宫格图交给能看图的模型；有声音的附上带时间的文字稿（对话文件夹里有就直接用，没有就先转，30 分钟以内）。
 * start、end 只看那一段，帧抽得更密。
 */
private suspend fun ToolSession.watchVideo(f: File, args: JsonObject): Pair<String, SearchRecord> {
    val info = withContext(Dispatchers.IO) { com.jack.ai.data.media.VideoFrames.info(f) }
        ?: throw IllegalStateException("「${f.name}」读不出来：可能不是视频，或者是手机不支持的格式")
    val stampOf = com.jack.ai.data.media.VideoFrames::stamp
    if (!info.hasVideo && info.width == 0) return ("「${f.name}」没有画面（是纯音频），用 transcribe 转文字。") to SearchRecord("看视频：${f.name}（没有画面）")
    val dur = info.durationMs
    val start = args["start"].text().takeIf { it.isNotBlank() }?.let { VideoEdit.parseTime(it) ?: throw IllegalStateException("start 看不懂：写成 1:23 或秒数") } ?: 0L
    val end = args["end"].text().takeIf { it.isNotBlank() }?.let { VideoEdit.parseTime(it) ?: throw IllegalStateException("end 看不懂：写成 1:23 或秒数") }?.coerceAtMost(dur) ?: dur
    if (start >= end) throw IllegalStateException("视频只有 ${stampOf(dur)} 长，${stampOf(start)} 到 ${stampOf(end)} 这段不对")
    val part = start > 0 || end < dur
    val text = StringBuilder("「${f.name}」${stampOf(dur)}，${info.width}×${info.height}" + (if (!info.hasAudio) "，没有声音" else "") +
        (if (part) "。这次只看 ${stampOf(start)}–${stampOf(end)}" else "") + "。\n")
    if (canSee) {
        val keep = com.jack.ai.data.media.VideoFrames.keepFor(end - start)
        val frames = withContext(Dispatchers.IO) {
            com.jack.ai.data.media.VideoFrames.extract(f, start, end, keep) { i, n -> ui.progress("看视频：抽帧 $i/$n") }
        }
        if (frames.isEmpty()) text.append("画面抽不出来（这个格式手机解不了），只能听声音。\n")
        else {
            val grids = withContext(Dispatchers.Default) {
                frames.chunked(com.jack.ai.data.media.VideoFrames.PER_GRID).map { g ->
                    val b = com.jack.ai.data.media.VideoFrames.grid(g)
                    com.jack.ai.data.media.VideoFrames.dataUrl(b).also { b.recycle() }
                }
            }
            frames.forEach { it.bmp.recycle() }
            images += grids
            text.append("抽了 ${frames.size} 帧（均匀取一半，画面变化大的地方取一半），拼成 ${grids.size} 张宫格图，在下一条消息里：" +
                "每格左上角是时间，从左到右、从上到下按时间排。\n")
        }
    } else text.append("现在用的模型不能看图，只能听声音。要看画面，请用户把模型换成能看图的（比如 V4.1 Flash）。\n")
    if (info.hasAudio && args["listen"].text() != "false") {
        fun findTimed() = wsDir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(f.nameWithoutExtension + "-文字稿") && it.extension == "txt" }
            .filter { runCatching { it.bufferedReader().use { r -> r.readLine().orEmpty() } }.getOrDefault("").contains("带时间") }
            .maxByOrNull { it.lastModified() }
        var timed = withContext(Dispatchers.IO) { findTimed() }
        var note = ""
        if (timed == null) when {
            dur > WATCH_LISTEN_MAX_MS -> note = "视频比较长，没有自动转文字。要听内容，先问用户，再用 transcribe timestamps=true。"
            Transcriber.engine(settings) == null -> note = "没有能转文字的服务，只看了画面。" + Transcriber.NO_ENGINE
            else -> {
                val (msg, _) = transcribeTimed(f, "")
                timed = withContext(Dispatchers.IO) { findTimed() }
                if (timed == null) note = msg.substringBefore("。") + "。"
            }
        }
        val body = timed?.let { t ->
            val all = withContext(Dispatchers.IO) { t.readText() }.substringAfter("\n\n").trim()
            if (part) com.jack.ai.data.media.VideoFrames.transcriptBetween(all, start, end) else all
        }.orEmpty()
        when {
            body.isNotBlank() -> text.append("\n声音的文字稿（「${timed!!.name}」，机器转写可能有错字、同音字）：\n")
                .append(if (body.length > 15_000) body.take(15_000) + "\n…（后面还有，完整的用 read_file 读「${timed.name}」）" else body).append("\n")
            timed != null -> text.append("\n这段里没听出有人说话。\n")
            note.isNotEmpty() -> text.append("\n").append(note).append("\n")
        }
    }
    text.append("\n要细看某一段（动作、画面里的字、细节），再调 watch_video 填 start、end（比如 1:20 到 1:40），那段会抽得更密。" +
        "帧是抽出来的，两帧之间发生的事看不到：没看到的别编，说清楚是按画面推测的。")
    return text.toString() to SearchRecord("看视频：${f.name}" + if (part) "（${stampOf(start)}–${stampOf(end)}）" else "")
}

/** 录音、视频转文字：解出声音 → 5 分钟一段 → 交给语音识别 → 存成 txt。文字也交给模型（太长的截前面）。 */
private suspend fun ToolSession.transcribe(f: File, out: String): Pair<String, SearchRecord> {
    val engine = Transcriber.engine(settings) ?: return Transcriber.NO_ENGINE to SearchRecord("转文字：没有可用的服务")
    val work = File(wsCache, "asr-${System.currentTimeMillis()}")
    try {
        val (parts, secs) = withContext(Dispatchers.IO) {
            MediaMux.toWav(f, work, engine.chunkSec, MAX_TRANSCRIBE_SEC) { at, total ->
                if (total > 0) ui.progress("转文字：解出声音 ${at * 100 / total}%")
            }
        }
        val text = StringBuilder()
        parts.forEachIndexed { i, p ->
            ui.progress("转文字：第 ${i + 1}/${parts.size} 段")
            val t = withContext(Dispatchers.IO) { transcribeRetrying(engine, p) }
            if (parts.size > 1) text.append("【${AudioPcm.stamp(i.toLong() * engine.chunkSec)}】\n")
            text.append(t.ifBlank { "（这一段没听出字）" }).append("\n\n")
            p.delete()
        }
        val body = text.toString().trim()
        val dst = outFile(out, f, "文字稿", "txt")
        withContext(Dispatchers.IO) { dst.writeText("「${f.name}」的文字稿（${AudioPcm.stamp(secs)}，${engine.name} ${engine.model.substringAfterLast('/')} 转写）\n\n$body\n") }
        ui.attach(dst)
        val shown = if (body.length > 30_000) body.take(30_000) + "\n…（后面还有，完整的在「${dst.name}」里，用 read_file 读）" else body
        return ("转好了，${AudioPcm.stamp(secs)}，存成「${dst.name}」。机器转写可能有错字、同音字，引用时留意。文字稿：\n\n$shown") to SearchRecord("转文字：${f.name}")
    } finally {
        work.deleteRecursively()
    }
}

/**
 * 带时间的转文字（剪辑用，思路来自 modelscope/FunClip）：按说话的停顿切成一句句，每句单独识别（同时 4 句），
 * 得到「[0:12.3-0:18.9] 文字」的文字稿和一份 .srt 字幕。模型照着时间挑片段，交给 edit_video 剪。
 */
private suspend fun ToolSession.transcribeTimed(f: File, out: String): Pair<String, SearchRecord> {
    val engine = Transcriber.engine(settings) ?: return Transcriber.NO_ENGINE to SearchRecord("转文字：没有可用的服务")
    val work = File(wsCache, "asr-${System.currentTimeMillis()}").apply { mkdirs() }
    try {
        class Piece(val file: File, val startMs: Long, val endMs: Long)
        val pieces = mutableListOf<Piece>()
        val splitter = AudioPcm.SpeechSplitter({ start, samples, n ->
            val pf = File(work, "s-%05d.wav".format(pieces.size))
            AudioPcm.writeWav(pf, samples, n)
            pieces += Piece(pf, start * 1000 / AudioPcm.RATE, (start + n) * 1000 / AudioPcm.RATE)
        })
        val samples = withContext(Dispatchers.IO) {
            MediaMux.decode16k(f, MAX_TRANSCRIBE_SEC, { at, total -> if (total > 0) ui.progress("转文字：解出声音 ${at * 100 / total}%") }) { splitter.push(it) }
                .also { splitter.finish() }
        }
        val secs = samples / AudioPcm.RATE
        if (pieces.isEmpty()) return "「${f.name}」（${AudioPcm.stamp(secs)}）里没听出有人说话。" to SearchRecord("转文字：${f.name}")
        val texts = arrayOfNulls<String>(pieces.size)
        var done = 0
        val lock = Any()
        coroutineScope {
            val gate = Semaphore(4)
            pieces.mapIndexed { i, p ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        texts[i] = transcribeRetrying(engine, p.file)
                        p.file.delete()
                        val n = synchronized(lock) { ++done }
                        ui.progress("转文字：$n/${pieces.size} 句")
                    }
                }
            }.awaitAll()
        }
        val cues = pieces.indices.mapNotNull { i -> texts[i]?.trim()?.takeIf { it.isNotEmpty() }?.let { Subtitles.Cue(pieces[i].startMs, pieces[i].endMs, it) } }
        if (cues.isEmpty()) return "「${f.name}」（${AudioPcm.stamp(secs)}）没听出字。" to SearchRecord("转文字：${f.name}")
        val timed = Subtitles.timed(cues)
        val srt = outFile(if (out.isBlank()) "" else out.substringBeforeLast('.'), f, "字幕", "srt")
        val txt = outFile(if (out.isBlank()) "" else out.substringBeforeLast('.'), f, "文字稿", "txt")
        withContext(Dispatchers.IO) {
            srt.writeText(Subtitles.toSrt(cues))
            txt.writeText("「${f.name}」的文字稿（${AudioPcm.stamp(secs)}，${engine.name} ${engine.model.substringAfterLast('/')} 转写，带时间）\n\n$timed\n")
        }
        ui.attach(txt); ui.attach(srt)
        val shown = if (timed.length > 30_000) timed.take(30_000) + "\n…（后面还有，完整的在「${txt.name}」里，用 read_file 读）" else timed
        return ("转好了，${AudioPcm.stamp(secs)}，${cues.size} 句，文字稿「${txt.name}」，字幕「${srt.name}」。机器转写可能有错字、同音字；" +
            "要烧进视频的字幕有错字可以先改「${srt.name}」。剪片段：挑好时间后用 edit_video（segments 填 开始-结束，几段用逗号隔开，可以加 subtitles=「${srt.name}」）。" +
            "文字稿：\n\n$shown") to SearchRecord("转文字：${f.name}")
    } finally {
        work.deleteRecursively()
    }
}

/**
 * 认字（DeepSeek OCR）：扫描版 PDF、拍的文件、老版 Word/PPT、图片里的字，逐页认出来存成 txt，文字也交给模型（太长的截前面）。
 * 几个文件就各存一份。1.5 元/1000 页。
 */
private suspend fun ToolSession.ocr(files: List<File>, out: String, layout: Boolean): Pair<String, SearchRecord> {
    val key = settings.apiKey
    if (key.isBlank()) return "认字要用 DeepSeek Key：请用户在 设置 → DeepSeek API Key 里填好再试。" to SearchRecord("认字：没有 DeepSeek Key")
    val bad = files.filter { it.extension.lowercase() !in OCR_EXT }
    if (bad.isNotEmpty()) return "ocr 只收 PDF、Word、PPT 和图片，「${bad.first().name}」不行" to SearchRecord("认字")
    val big = files.firstOrNull { it.length() > 100L * 1024 * 1024 }
    if (big != null) return "「${big.name}」超过 100 MB，DeepSeek 认字收不了；可以先 split_pdf 拆小" to SearchRecord("认字")
    val results = StringBuilder()
    var totalPages = 0
    for ((i, f) in files.withIndex()) {
        ui.progress("认字：${f.name}" + if (files.size > 1) "（${i + 1}/${files.size}）" else "")
        val pages = withContext(Dispatchers.IO) {
            var wait = 3000L
            var r: List<com.jack.ai.api.DeepSeekTools.OcrPage>? = null
            for (attempt in 0 until 3) {
                try { r = com.jack.ai.api.DeepSeekTools.ocr(key, f, layout); break }
                catch (e: com.jack.ai.api.ApiException) {
                    if ((e.code != 429 && e.code != 503) || attempt == 2) throw e
                    delay(wait); wait *= 2
                }
            }
            r!!
        }
        totalPages += pages.size
        host.store.addPaidCall(com.jack.ai.api.DeepSeekTools.OCR_KEY, pages.size * com.jack.ai.api.DeepSeekTools.OCR_PRICE_PER_PAGE)
        val body = pages.mapIndexed { n, p ->
            (if (pages.size > 1) "【第 ${n + 1} 页】\n" else "") + p.text.ifBlank { "（这一页没认出字）" } + if (p.truncated) "\n（这一页太长，没认完）" else ""
        }.joinToString("\n\n")
        val dst = outFile(if (files.size == 1) out else "", f, "文字", "txt")
        withContext(Dispatchers.IO) { dst.writeText("「${f.name}」认出的文字（${pages.size} 页，DeepSeek OCR）\n\n$body\n") }
        ui.attach(dst)
        results.append("「${f.name}」${pages.size} 页 → 「${dst.name}」\n\n").append(body).append("\n\n")
    }
    val all = results.toString().trim()
    val shown = if (all.length > 30_000) all.take(30_000) + "\n…（后面还有，完整的在 txt 里，用 read_file 读）" else all
    return "认好了，共 $totalPages 页。机器认字可能有错字，表格和公式可能走样，引用时留意。\n\n$shown" to SearchRecord("认字：${files.first().name}")
}

/** 一句一句识别时请求多，容易被限流（429）：等一会儿重试，最多 5 次。用 DeepSeek 时把花费记进用量统计。 */
private suspend fun ToolSession.transcribeRetrying(engine: Transcriber.Engine, wav: File): String {
    val spent: (Double) -> Unit = { host.store.addPaidCall(com.jack.ai.api.DeepSeekTools.ASR_KEY, it) }
    var wait = 2000L
    repeat(4) {
        try {
            return Transcriber.transcribe(engine, wav, spent)
        } catch (e: com.jack.ai.api.ApiException) {
            if (e.code != 429 && e.code < 500) throw e
            delay(wait); wait *= 2
        }
    }
    return Transcriber.transcribe(engine, wav, spent)
}
