package com.jack.ai.tools

import com.jack.ai.data.PptStyle
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.StyleTemplates
import com.jack.ai.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * my_templates：存、列、删、设默认「我的模板」。
 * 存的时候可以从一份文件来（你生成过的 Word / PPT / Excel 取它的设置；用户发来的 .pptx 取它的配色和字体），也可以直接写设置。
 */
internal suspend fun ToolSession.myTemplates(args: JsonObject): String {
    val action = args["action"].text().ifBlank { "list" }
    val name = args["name"].text().trim()
    return runTool(when (action) { "save" -> "存模板：$name"; "delete" -> "删模板：$name"; "set_default" -> "设默认模板：$name"; else -> "查看模板" }) {
        val list = withContext(Dispatchers.IO) { StyleTemplates.load(app) }
        val result = when (action) {
            "list" -> StyleTemplates.describe(list)
            "save" -> {
                if (name.isEmpty()) throw IllegalArgumentException("要给模板起个名字（name）")
                var kind = args["kind"].text().takeIf { it in StyleTemplates.KEYS }
                val settings = mutableMapOf<String, JsonElement>()
                var colors: StyleTemplates.Colors? = null
                var source = ""
                args["from_file"].text().trim().takeIf { it.isNotEmpty() }?.let { fname ->
                    val f = wsFile(fname)
                    source = f.name
                    val src = withContext(Dispatchers.IO) { Workspace.source(f) }
                    if (src != null) {
                        val (tool, a) = src
                        val k = StyleTemplates.kindOf(tool) ?: throw IllegalArgumentException("「${f.name}」不是 Word、PPT、Excel，存不了模板")
                        kind = k
                        // 原来用了模板的，先把模板的设置补上，再取这份自己的
                        val (full, tc, _) = StyleTemplates.apply(list, tool, a)
                        full.forEach { (key, v) -> if (key in StyleTemplates.KEYS.getValue(k)) settings[key] = v }
                        colors = tc
                        // 照着别的 PPT 做的：把那份的颜色也存下来
                        full["style_from"].text().takeIf { it.isNotBlank() }?.let { s ->
                            runCatching { wsFile(s) }.getOrNull()?.let { pf -> colors = withContext(Dispatchers.IO) { StyleTemplates.Colors.of(PptStyle.extract(pf).theme) } }
                        }
                    } else if (f.name.endsWith(".pptx", true)) {
                        kind = "ppt"
                        colors = withContext(Dispatchers.IO) { StyleTemplates.Colors.of(PptStyle.extract(f).theme) }
                    } else throw IllegalArgumentException("「${f.name}」不是你生成的文件，也不是 .pptx：只能从你生成过的 Word / PPT / Excel，或者用户发来的 PPT 存模板")
                }
                val k = kind ?: throw IllegalArgumentException("要写 kind（ppt、word、excel），或者用 from_file 从一份文件存")
                (args["settings"] as? JsonObject)?.forEach { (key, v) ->
                    if (key !in StyleTemplates.KEYS.getValue(k)) throw IllegalArgumentException("「$key」存不进 ${k} 模板。能存的：" + StyleTemplates.KEYS.getValue(k).joinToString("、"))
                    if (v is JsonNull) settings.remove(key) else settings[key] = v
                }
                // 用户自己写的配色优先：写了 theme 就不用读出来的整套颜色
                if ((args["settings"] as? JsonObject)?.containsKey("theme") == true) colors = null
                if (settings.isEmpty() && colors == null) throw IllegalArgumentException("没有可存的：写 settings，或者用 from_file")
                val old = StyleTemplates.find(list, k, name)?.takeIf { it.name == name }
                val t = StyleTemplates.Template(name, k, settings, colors, isDefault = args["default"].text() == "true" || (old?.isDefault == true && args["default"].text() != "false"), source = source)
                withContext(Dispatchers.IO) { StyleTemplates.save(app, StyleTemplates.put(list, t)) }
                (if (old != null) "已更新" else "已存好") + "模板：\n" + StyleTemplates.describe(listOf(t)) +
                    "\n以后 create 时写 template=「$name」就会照它来" + (if (t.isDefault) "；它是默认的，不写也会用（写 template=none 不用）。" else "。")
            }
            "delete" -> {
                val t = list.firstOrNull { it.name == name } ?: throw IllegalArgumentException("没有叫「$name」的模板。现有的：\n" + StyleTemplates.describe(list))
                withContext(Dispatchers.IO) { StyleTemplates.save(app, list - t) }
                "已删掉模板「$name」。"
            }
            "set_default" -> {
                val t = list.firstOrNull { it.name == name } ?: throw IllegalArgumentException("没有叫「$name」的模板。现有的：\n" + StyleTemplates.describe(list))
                val on = args["default"].text() != "false"
                withContext(Dispatchers.IO) { StyleTemplates.save(app, StyleTemplates.put(list, t.copy(isDefault = on))) }
                if (on) "「$name」设成默认的${t.kind.uppercase()}模板了，以后不说也照它来。" else "「$name」不再是默认模板。"
            }
            else -> throw IllegalArgumentException("action 用 list、save、delete、set_default")
        }
        result to SearchRecord(if (action == "list") "查看模板" else "模板：$name")
    }
}
