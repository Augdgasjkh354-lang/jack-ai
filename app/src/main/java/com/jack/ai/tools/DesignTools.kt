package com.jack.ai.tools

import com.jack.ai.data.design.DesignFonts
import com.jack.ai.data.design.DesignStyles
import com.jack.ai.data.design.DesignTemplates
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Workspace
import com.jack.ai.ui.DesignRender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * design：做设计图（海报、小红书配图、邀请函、名片、公众号封面、长图），单张或一组。
 * - templates：列出模板（可按分类、风格、套组筛）；
 * - styles：风格库（不填 style 列出全部风格和版式，填了看这个风格的字体、配色、版式、禁忌）；
 * - fonts：能用的字体；
 * - make：套模板（填文字、列表、图片、换配色），或者用 html 从头设计；出 PNG（可再出 PDF），旁边存一份底稿 .html；
 * - series：一组图（小红书一篇拆成几张），每页一个模板或 html，配色统一，文件名按 01、02… 编号，页码自动填；
 * - render：用 edit_file 改过底稿以后重新出图（同名覆盖，聊天里的图跟着变）；
 * - resize：一键改尺寸，按新尺寸重新排版（不是拉伸），另存一份；
 * - cutout：抠图（去背景，出透明 PNG），用手机上的抠图模型，没下载时让 Kavi 先问用户下哪个。
 * 每次都检查文字有没有装不下、审美上有没有常见毛病，能看图的模型还会收到小图自己看一眼。
 */
internal suspend fun ToolSession.design(args: JsonObject): String = when (args["action"].text().ifBlank { "make" }) {
    "templates" -> runTool("看设计模板") {
        val list = withContext(Dispatchers.IO) { DesignTemplates.list(app) }
        val cat = args["category"].text().trim().ifBlank { null }
        val look = args["style"].text().trim().ifBlank { null }
        val shown = if (look == null) list else list.filter { it.meta.str("look") == look || it.meta.str("set").startsWith(look) }
        val text = if (look != null && shown.isEmpty()) "「$look」风格还没有模板：按 design action=styles style=$look 的做法用 html 从头设计。"
            else DesignTemplates.catalog(shown, cat)
        (text + "\n\n配色变量：" + DesignTemplates.VARS_NOTE) to SearchRecord("看设计模板" + (cat?.let { "：${DesignTemplates.CATEGORIES[it] ?: it}" } ?: ""))
    }
    "styles" -> runTool("看设计风格") {
        val lib = withContext(Dispatchers.IO) { DesignStyles.load(app) }
        val id = args["style"].text().trim().ifBlank { null }
        DesignStyles.catalog(lib, id) to SearchRecord("看设计风格" + (id?.let { "：$it" } ?: ""))
    }
    "elements" -> runTool("看设计贴纸") {
        val lib = withContext(Dispatchers.IO) { com.jack.ai.data.design.DesignElements.library(app) }
        val q = args["query"].text().trim()
        com.jack.ai.data.design.DesignElements.catalog(lib, q, args["category"].text().trim().ifBlank { null }?.takeIf { c -> lib.categories.any { it.id == c } }) to
            SearchRecord("看设计贴纸" + (q.takeIf { it.isNotEmpty() }?.let { "：$it" } ?: ""))
    }
    "fonts" -> runTool("看设计字体") { DesignFonts.catalog(withContext(Dispatchers.IO) { DesignFonts.all(app) }) to SearchRecord("看设计字体") }
    "render" -> renderDesign(args)
    "make" -> makeDesign(args)
    "series" -> makeSeries(args)
    "resize" -> resizeDesign(args)
    "cutout" -> cutout(args)
    else -> "action 只能是 templates、styles、fonts、elements、make、series、render、resize、cutout"
}

private suspend fun ToolSession.makeDesign(args: JsonObject): String {
    val list = withContext(Dispatchers.IO) { DesignTemplates.list(app) }
    val page = resolvePage(args, list) ?: return pageError(args, list)
    val base = safeFileName(args["filename"].text()).ifBlank { page.tpl?.name?.substringBefore('·') ?: "设计" }
    return runTool("做设计：$base" + (page.tpl?.let { "（${it.name}）" } ?: "")) {
        val d = draw(base, page, args, args, fresh = true, previewWidth = 720)
        d.text to SearchRecord("做设计：${d.png.name}" + if (d.problems > 0) "（${d.problems} 处要改）" else "")
    }
}

/** 一页要用的东西：模板（或 Kavi 写的 html）和画布大小。 */
private class Page(val tpl: DesignTemplates.Template?, val html: String, val w: Int, val h: Int)

private suspend fun ToolSession.resolvePage(o: JsonObject, list: List<DesignTemplates.Template>): Page? {
    val templateId = o["template"].text().trim()
    if (templateId.isNotEmpty()) {
        val tpl = list.firstOrNull { it.id == templateId } ?: return null
        val html = withContext(Dispatchers.IO) { DesignTemplates.html(app, tpl.id) } ?: return null
        return Page(tpl, html, tpl.w, tpl.h)
    }
    val html = o["html"].text().takeIf { it.isNotBlank() } ?: return null
    val (w, h) = DesignTemplates.size(o["size"].text().ifBlank { "xhs" }) ?: return null
    return Page(null, html, w, h)
}

private fun pageError(o: JsonObject, list: List<DesignTemplates.Template>): String {
    val templateId = o["template"].text().trim()
    return when {
        templateId.isNotEmpty() && list.none { it.id == templateId } -> "没有「$templateId」这个模板。先用 action=templates 看有哪些；或者不填 template、用 html 从头设计。"
        templateId.isNotEmpty() -> "模板「$templateId」的文件读不出来"
        o["html"].text().isBlank() -> "要么填 template（模板 id），要么写 html 从头设计"
        else -> "size 认不出：用 ${DesignTemplates.SIZES.keys.joinToString("、")}，或写「宽x高」像素，比如 1080x1350"
    }
}

private suspend fun ToolSession.makeSeries(args: JsonObject): String {
    val pages = (args["pages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    if (pages.isEmpty()) return "series 要写 pages：每页一个 {template 或 html, slots, lists}，按顺序排"
    if (pages.size > 12) return "一组最多 12 张，现在有 ${pages.size} 张：拆成两组，或者精简内容"
    val list = withContext(Dispatchers.IO) { DesignTemplates.list(app) }
    val resolved = pages.mapIndexed { i, p -> resolvePage(p, list) ?: return "第 ${i + 1} 页：" + pageError(p, list) }
    val name = safeFileName(args["filename"].text()).ifBlank { "小红书" }
    val n = pages.size
    // 整组一个名字：已经有同名的一组就换成「名字2」，不和上一组混在一起
    val base = withContext(Dispatchers.IO) { generateSequence(1) { it + 1 }.map { if (it == 1) name else "$name$it" }.first { !File(wsDir, "$it-01.png").exists() } }
    return runTool("做一组设计：$base（$n 张）") {
        val texts = mutableListOf<String>()
        var problems = 0
        resolved.forEachIndexed { i, page ->
            val num = (i + 1).toString().padStart(2, '0')
            // 页码：模板里有 page 位置又没自己填的，自动填「01/06」
            val p = pages[i]
            val slots = (p["slots"] as? JsonObject).orEmpty().toMutableMap()
            if ("page" !in slots) slots["page"] = JsonPrimitive("$num/${n.toString().padStart(2, '0')}")
            val own = JsonObject(p + ("slots" to JsonObject(slots)))
            val d = draw("$base-$num", page, own, args, fresh = false, previewWidth = 400, quietUnknown = setOf("page"), thumb = i == 0)
            problems += d.problems
            texts += "第 ${i + 1} 张 " + d.text
        }
        val head = "一组 $n 张都出好了，按顺序显示在聊天里，文件名 $base-01 … $base-${n.toString().padStart(2, '0')}。" +
            "检查每张的结果；要改哪张就 edit_file 改那张的底稿（改完自动重新出图），其他张不用动。整组风格要统一：改了一张的配色、字体，别的张也要跟着改。\n\n"
        (head + texts.joinToString("\n\n")) to SearchRecord("做一组设计：$base（$n 张）" + if (problems > 0) "，$problems 处要改" else "")
    }
}

internal suspend fun ToolSession.renderDesign(args: JsonObject): String {
    val path = args["path"].text().trim()
    if (path.isEmpty()) return "要写 path：底稿 .html 的文件名"
    val file = withContext(Dispatchers.IO) { Workspace.find(wsDir, path) }
        ?: return "对话文件夹里没有「$path」"
    if (file.extension.lowercase() !in setOf("html", "htm")) return "render 要的是底稿 .html，不是「${file.name}」"
    val html = withContext(Dispatchers.IO) { file.readText() }
    val (w, h) = DesignTemplates.savedSize(html) ?: DesignTemplates.size(args["size"].text()) ?: (1080 to 1440)
    val label = "重新出图：${file.nameWithoutExtension}"
    return runTool(label) {
        val d = draw(file.nameWithoutExtension, Page(null, html, w, h), args, args, fresh = false, previewWidth = 720)
        // 聊天里的图跟着刷新；编辑器开着这页的话载入新版
        com.jack.ai.ui.DesignEditorActivity.bump(d.png)
        com.jack.ai.data.design.DesignEvents.changed(file)
        d.text to SearchRecord("$label：${d.png.name}" + if (d.problems > 0) "（${d.problems} 处要改）" else "")
    }
}

/** 改尺寸：path 是设计图（.png 或底稿 .html），size 是新尺寸。原图不动，新图叫「原名-方图」这样。 */
private suspend fun ToolSession.resizeDesign(args: JsonObject): String {
    val path = args["path"].text().trim()
    if (path.isEmpty()) return "要写 path：要改尺寸的设计图文件名"
    val found = withContext(Dispatchers.IO) { Workspace.find(wsDir, path) } ?: return "对话文件夹里没有「$path」"
    val src = File(found.parentFile, found.nameWithoutExtension + ".html")
    if (!src.isFile || withContext(Dispatchers.IO) { DesignTemplates.savedSize(src.readText()) } == null)
        return "「${found.name}」没有设计底稿，改不了尺寸（只有 design 做出来的图能改）"
    val raw = args["size"].text().trim().lowercase()
    val (w, h) = DesignTemplates.size(raw)?.takeIf { it.second > 0 } ?: return "size 要写新尺寸：${com.jack.ai.ui.DesignResize.TARGETS.joinToString("、")}，或「宽x高」像素"
    val label = com.jack.ai.ui.DesignResize.LABELS[raw] ?: "${w}x$h"
    return runTool("改尺寸：${found.nameWithoutExtension} → $label") {
        val out = com.jack.ai.ui.DesignResize.resizeAll(app, wsDir, listOf(src), w, h, label).first()
        ui.attach(out.png)
        val text = buildString {
            append("改好了：「${out.png.name}」（${w}×$h），底稿「${out.html.name}」，原图没动。")
            if (out.note.isNotBlank()) append(out.note).append("。")
            if (out.problems.isEmpty()) append("检查：文字都装得下，没有跑出画布。")
            else append("\n检查还有这些毛病（用 edit_file 改新底稿，改完自动重新出图）：\n").append(out.problems.joinToString("\n") { "- $it" })
        }
        text to SearchRecord("改尺寸：${out.png.name}")
    }
}

private class Drawn(val png: File, val text: String, val problems: Int)

/**
 * 出一张图：o 是这一页自己的内容（slots、lists），shared 是整组共用的（colors、format）。
 * 在 runTool 里面调；返回给模型的说明文字。
 */
private suspend fun ToolSession.draw(
    base: String, page: Page, o: JsonObject, shared: JsonObject, fresh: Boolean, previewWidth: Int, quietUnknown: Set<String> = emptySet(), thumb: Boolean = true,
): Drawn {
    // 图片位：填的是对话文件夹里的文件名，换成页面里的地址；找不到的先说清楚
    val pics = mutableMapOf<String, String>()
    val missing = mutableListOf<String>()
    val slots = (o["slots"] as? JsonObject)?.let { all ->
        JsonObject(all.filter { (k, v) ->
            val name = (v as? JsonPrimitive)?.content.orEmpty()
            if (looksLikeImage(name)) {
                val f = withContext(Dispatchers.IO) { Workspace.find(wsDir, name) }
                if (f == null) missing += "$k=$name"
                else pics[k] = DesignRender.fileUrl(f.canonicalFile.relativeTo(wsDir.canonicalFile).path)
                false
            } else true
        })
    }
    if (missing.isNotEmpty()) error("对话文件夹里没有这些图片：${missing.joinToString("、")}")
    val lists = o["lists"] as? JsonObject
    val vars = DesignTemplates.colors((o["colors"] ?: shared["colors"]) as? JsonObject)
    val pdfWanted = (o["format"].text().ifBlank { shared["format"].text() }).lowercase() == "pdf"
    val png = withContext(Dispatchers.IO) { if (fresh) Workspace.uniqueFile(wsDir, "$base.png") else File(wsDir, "$base.png") }
    val src = File(png.parentFile, png.nameWithoutExtension + ".html")
    val pdf = if (pdfWanted) File(png.parentFile, png.nameWithoutExtension + ".pdf") else null
    val data = DesignTemplates.data(vars, slots, lists, pics, DesignTemplates.icons(page.html), com.jack.ai.data.design.DesignElements.stickers(app, page.html))
    val r = DesignRender.render(app, wsDir, page.html, page.w, page.h, data, png, pdf, previewWidth)
    if (r.source.isNotBlank()) withContext(Dispatchers.IO) { src.writeText(r.source) }
    ui.attach(png)
    pdf?.takeIf { it.exists() }?.let { ui.attach(it) }
    val unknown = r.unknown - quietUnknown
    val text = buildString {
        append("「${png.name}」（${r.width}×${r.height}）")
        if (pdf != null) append("，PDF「${pdf.name}」")
        append("。底稿是「${src.name}」：要改就用 edit_file 改它（文字、颜色、位置、大小都在里面），改完会自动重新出图，不用再 render，不要从头再做。\n")
        if (unknown.isNotEmpty()) append("这些位置模板里没有，没填进去：${unknown.joinToString("、")}。对照模板目录里的名字再填。\n")
        if (r.problems.isEmpty()) append("检查：文字都装得下，没有跑出画布。\n")
        else append("检查发现（必须改）：\n").append(r.problems.joinToString("\n") { "- $it" }).append("\n先改掉这些（缩短文字、拆成两行、调小字号或换个模板），再交给用户。\n")
        if (r.advice.isNotEmpty()) append("审美检查（按「审美」技能判断，大多数该改）：\n").append(r.advice.joinToString("\n") { "- $it" }).append("\n")
        when {
            r.blank -> append("截出来是一片空白：可能是 App 在后台没出画面，请用户回到 App 再让你重新出图。")
            canSee && r.preview != null -> {
                images += r.preview
                val small = r.thumb?.takeIf { thumb }
                small?.let { images += it }
                append(if (small != null) "出的图在下一条消息里：先看大图（排版、对齐、留白、颜色、字号层次），再看最后那张缩略图——小红书信息流里就这么大，标题还认得出、还想点吗？有毛病就改了再出。"
                    else "出的图（缩小了）在下一条消息里：看一眼排版、对齐、留白、颜色和字号层次，有毛病就改了再出。")
            }
        }
    }
    return Drawn(png, "图已生成并显示在聊天里：$text", r.problems.size)
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()

private fun looksLikeImage(v: String) = Regex("""\.(png|jpe?g|webp|gif|bmp|heic)$""", RegexOption.IGNORE_CASE).containsMatchIn(v.trim()) && '\n' !in v

/**
 * 抠图：去掉照片背景，存成「原名-抠图.png」（透明背景，裁到主体）。
 * 抠图模型不随 App 带：没下载、也没说下哪个时，告诉 Kavi 用 ask_user 问用户（小巧版 82MB / 高清版 92MB）；
 * 用户选了再带 model 调一次，这次先下载（通知栏显示进度）再抠。
 */
private suspend fun ToolSession.cutout(args: JsonObject): String {
    val path = args["path"].text().trim()
    if (path.isEmpty()) return "要写 path：对话文件夹里的图片文件名"
    val src = withContext(Dispatchers.IO) { Workspace.find(wsDir, path) } ?: return "对话文件夹里没有「$path」"
    if (!looksLikeImage(src.name)) return "「${src.name}」不是图片"
    var model = com.jack.ai.data.LocalModels.cutoutReady(app)
    if (model == null) {
        val want = when (args["model"].text().trim().lowercase()) {
            "small", "int4", "小巧版" -> com.jack.ai.data.LocalModels.CUTOUT_SMALL
            "hd", "full", "int8", "高清版" -> com.jack.ai.data.LocalModels.CUTOUT_HD
            else -> return "抠图要用手机上的抠图模型，还没下载。先用 ask_user 问用户要不要下载、下哪个，选项写：" +
                "「小巧版（82MB，抠得快）」「高清版（92MB，头发丝更准，慢一点）」「先不用」。只下一次，以后抠图不用再问，不联网、不花钱；手机上抠一张要十几秒。" +
                "用户选了再调 design action=cutout path=${src.name} model=small（小巧版）或 model=hd（高清版）；选「先不用」就别再提。"
        }
        val ok = runTool("下载抠图模型：${want.name}") {
            val ms = com.jack.ai.data.LocalModels
            ms.start(app, want)
            try {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val st = ms.state.value[want.id] ?: break
                    if (st.total > 0) ui.progress("下载抠图模型 ${st.done * 100 / st.total}%（${ms.mb(st.done)} / ${ms.mb(st.total)}）")
                    if (!st.running) break
                }
            } catch (e: kotlinx.coroutines.CancellationException) { ms.pause(want); throw e }
            val err = ms.state.value[want.id]?.error.orEmpty()
            if (!ms.ready(app, want)) error(err.ifBlank { "没下完" })
            "下好了" to SearchRecord("下载抠图模型：${want.name}（${ms.mb(want.bytes)}）")
        }
        if (!com.jack.ai.data.LocalModels.ready(app, want)) return "$ok\n抠图模型没下载成功，告诉用户原因；网络好了可以再试（会接着下）。"
        model = want
    }
    val m = model
    return runTool("抠图：${src.name}") {
        val out = withContext(Dispatchers.IO) { Workspace.uniqueFile(wsDir, src.nameWithoutExtension + "-抠图.png") }
        val t0 = System.currentTimeMillis()
        val r = kotlinx.coroutines.runInterruptible(Dispatchers.Default) {
            com.jack.ai.api.LocalCutout.cut(java.io.File(com.jack.ai.data.LocalModels.dir(app, m), m.files.first().path), src, out)
        }
        ui.attach(out)
        val secs = (System.currentTimeMillis() - t0) / 100 / 10.0
        ("抠好了：「${out.name}」（${r.width}×${r.height}，透明背景，已裁到主体，用了 $secs 秒）。已经显示在聊天里。" +
            "要放进设计图就把它填进图片位，或者从头设计时用 <img> 摆在画布上（透明背景适合压在色块上、当贴纸）。") to
            SearchRecord("抠图：${out.name}")
    }
}
