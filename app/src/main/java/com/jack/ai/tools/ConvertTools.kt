package com.jack.ai.tools

import com.jack.ai.api.Transcriber
import com.jack.ai.data.AudioPcm
import com.jack.ai.data.MediaMux
import com.jack.ai.data.PyRunner
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.ToolSizes
import com.jack.ai.data.VideoEdit
import com.jack.ai.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 文件工具箱（convert_file）：图片转 PDF、合并拆分 PDF、PDF 转图片、压缩图片、换图片格式、
 * 剪视频、压视频、取出声音、录音和视频转文字。都在对话文件夹里做，原文件不动，结果另存一份挂在聊天里。
 */

private val ACTIONS = listOf("images_to_pdf", "merge_pdf", "split_pdf", "pdf_to_images", "compress_image", "convert_image",
    "cut_video", "compress_video", "extract_audio", "transcribe")

/** 转文字最长多久（再长要几十分钟，也贵）。 */
private const val MAX_TRANSCRIBE_SEC = 3L * 3600
/** 转文字一段多长（5 分钟的 16 kHz WAV 约 9.6 MB，各家接口都收）。 */
private const val CHUNK_SEC = 300

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
        "extract_audio" -> "取出声音"
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
            "extract_audio" -> {
                val f = files.first()
                val dst = outFile(out, f, "声音", "m4a")
                withContext(Dispatchers.IO) { MediaMux.mux(listOf(f), dst, audioOnly = true) }
                made = listOf(dst)
            }
            else -> return@runTool transcribe(files.first(), out)
        }
        made.take(10).forEach { ui.attach(it) }
        val list = made.joinToString("、") { "「${it.name}」（${ToolSizes.text(it.length())}）" }
        ("做好了：$list" + (if (note.isNotBlank()) "（$note）" else "") + "，存在对话文件夹里" +
            (if (made.size > 10) "，聊天里只挂了前 10 个" else "") + "。原文件没动。") to SearchRecord(label)
    }
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

/** 录音、视频转文字：解出声音 → 5 分钟一段 → 交给语音识别 → 存成 txt。文字也交给模型（太长的截前面）。 */
private suspend fun ToolSession.transcribe(f: File, out: String): Pair<String, SearchRecord> {
    val engine = Transcriber.engine(settings.providers) ?: return Transcriber.NO_ENGINE to SearchRecord("转文字：没有可用的服务")
    val work = File(wsCache, "asr-${System.currentTimeMillis()}")
    try {
        val (parts, secs) = withContext(Dispatchers.IO) {
            MediaMux.toWav(f, work, CHUNK_SEC, MAX_TRANSCRIBE_SEC) { at, total ->
                if (total > 0) ui.progress("转文字：解出声音 ${at * 100 / total}%")
            }
        }
        val text = StringBuilder()
        parts.forEachIndexed { i, p ->
            ui.progress("转文字：第 ${i + 1}/${parts.size} 段")
            val t = withContext(Dispatchers.IO) { Transcriber.transcribe(engine, p) }
            if (parts.size > 1) text.append("【${AudioPcm.stamp(i.toLong() * CHUNK_SEC)}】\n")
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
