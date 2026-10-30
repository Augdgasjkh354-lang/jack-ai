package com.jack.ai.tools

import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * revise_file：按页、按表、按设置改你生成过的 Word / PPT / Excel，只动要改的那部分，改完自动重新生成。
 * 比 edit_file 换整段底稿省事：「把第 3 页换成卡片版式」「删掉第 5 页」「整份换成红色」「Word 加上目录」一句就够。
 */
private val PPT_SET = setOf("theme", "transition", "animation", "style_from")
private val WORD_SET = setOf("theme", "cover", "toc", "header", "page_numbers", "heading_numbers", "indent", "font_style")
private val EXCEL_SET = setOf("theme")

/** 改好的参数和做了哪几件事（说给模型听）。纯 JSON 操作，单独拿出来好测。 */
internal fun revise(tool: String, old: JsonObject, args: JsonObject): Pair<JsonObject, List<String>> {
    val done = mutableListOf<String>()
    val out = old.toMutableMap()
    // 整份的设置：值写 null 就是去掉（回到默认）
    (args["set"] as? JsonObject)?.let { set ->
        val allowed = when (tool) { "create_ppt" -> PPT_SET; "create_word" -> WORD_SET; "create_excel" -> EXCEL_SET; else -> emptySet() }
        set.forEach { (k, v) ->
            if (k !in allowed) throw IllegalArgumentException("「$k」改不了。这种文件能改的设置：" + allowed.joinToString("、").ifEmpty { "没有" })
            if (v is JsonNull) out.remove(k) else out[k] = v
            done += "设置 $k" + if (v is JsonNull) " 去掉" else " = " + (if (v is JsonPrimitive) v.content else v.toString()).take(60)
        }
    }
    (args["slides"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { ops ->
        if (tool != "create_ppt") throw IllegalArgumentException("slides 只能用在 PPT 上")
        val slides = (out["slides"] as? JsonArray).orEmpty().toMutableList<JsonElement>()
        ops.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val op = o["op"].text()
            val page = o["page"].text().toIntOrNull() ?: throw IllegalArgumentException("每个操作都要写 page（第几页，从 1 数）")
            fun check(n: Int, max: Int) { if (n < 1 || n > max) throw IllegalArgumentException("第 $n 页不存在：一共 ${slides.size} 页") }
            when (op) {
                "update" -> {
                    check(page, slides.size)
                    val patch = o["slide"] as? JsonObject ?: throw IllegalArgumentException("update 要写 slide：这页要改的字段")
                    // 只改写到的字段，没写的保持原样；写 null 去掉这个字段
                    val cur = (slides[page - 1] as? JsonObject).orEmpty().toMutableMap()
                    patch.forEach { (k, v) -> if (v is JsonNull) cur.remove(k) else cur[k] = v }
                    slides[page - 1] = JsonObject(cur)
                    done += "第 $page 页改了 " + patch.keys.joinToString("、")
                }
                "replace" -> {
                    check(page, slides.size)
                    slides[page - 1] = o["slide"] as? JsonObject ?: throw IllegalArgumentException("replace 要写 slide：新的整页")
                    done += "第 $page 页整页换掉"
                }
                "insert" -> {
                    check(page, slides.size + 1)
                    slides.add(page - 1, o["slide"] as? JsonObject ?: throw IllegalArgumentException("insert 要写 slide：新的一页"))
                    done += "在第 $page 页插入一页"
                }
                "delete" -> { check(page, slides.size); slides.removeAt(page - 1); done += "删掉第 $page 页" }
                "move" -> {
                    check(page, slides.size)
                    val to = o["to"].text().toIntOrNull() ?: throw IllegalArgumentException("move 要写 to：挪到第几页")
                    check(to, slides.size)
                    slides.add(to - 1, slides.removeAt(page - 1))
                    done += "第 $page 页挪到第 $to 页"
                }
                else -> throw IllegalArgumentException("不认识的操作「$op」：能用 update、replace、insert、delete、move")
            }
        }
        // 操作按顺序做：前面的插入、删除会让后面的页码跟着变
        out["slides"] = JsonArray(slides)
    }
    (args["sheets"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { ops ->
        if (tool != "create_excel") throw IllegalArgumentException("sheets 只能用在 Excel 上")
        val sheets = (out["sheets"] as? JsonArray).orEmpty().toMutableList<JsonElement>()
        ops.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val name = o["name"].text()
            val i = sheets.indexOfFirst { (it as? JsonObject)?.get("name").text() == name }
            if (o["delete"].text() == "true") {
                if (i < 0) throw IllegalArgumentException("没有叫「$name」的工作表")
                sheets.removeAt(i); done += "删掉工作表「$name」"; return@forEach
            }
            val patch = o.filterKeys { it != "delete" }
            if (i < 0) { sheets += JsonObject(patch); done += "加了工作表「$name」"; return@forEach }
            val cur = (sheets[i] as JsonObject).toMutableMap()
            patch.forEach { (k, v) -> if (v is JsonNull) cur.remove(k) else cur[k] = v }
            sheets[i] = JsonObject(cur)
            done += "工作表「$name」改了 " + patch.keys.filter { it != "name" }.joinToString("、")
        }
        out["sheets"] = JsonArray(sheets)
    }
    if (done.isEmpty()) throw IllegalArgumentException("没有要改的：写 set（整份的设置）、slides（PPT 按页改）或 sheets（Excel 按表改）")
    return JsonObject(out) to done
}

private fun JsonObject?.orEmpty() = this ?: JsonObject(emptyMap())

internal suspend fun ToolSession.reviseFile(args: JsonObject): String {
    val name = args["name"].text()
    return runTool("修改 $name") {
        val file = wsFile(name)
        val (tool, old) = withContext(Dispatchers.IO) { Workspace.source(file) }
            ?: throw IllegalArgumentException("「${file.name}」不是你生成的（没有底稿），用 edit_file 改文字，或者重新生成")
        if (tool !in setOf("create_ppt", "create_word", "create_excel")) throw IllegalArgumentException("revise_file 只改 Word、PPT、Excel；「${file.name}」用 edit_file")
        val (newArgs, done) = revise(tool, old, args)
        val warnings = renderFile(tool, newArgs, file, wsCache)
        withContext(Dispatchers.IO) { Workspace.saveSource(file, tool, newArgs) }
        ui.attach(file)
        val pages = (newArgs["slides"] as? JsonArray)?.size?.let { "，现在一共 $it 页" }.orEmpty()
        "已修改「${file.name}」：" + done.joinToString("；") + pages + "。" + verifyGenerated(tool, newArgs, file) + layoutNote(warnings) to
            SearchRecord("修改 ${file.name}")
    }
}
