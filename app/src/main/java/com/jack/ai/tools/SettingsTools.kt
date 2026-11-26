package com.jack.ai.tools

import com.jack.ai.data.SearchRecord
import com.jack.ai.data.SettingsKnobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * settings：Kavi 帮用户改 App 的设置。list 看能改哪些、现在是什么；set 改（一次可以改几项）。
 * 只能改 [SettingsKnobs] 白名单里的；会多花钱的先问用户。改完在回答里挂一张小卡片说清改了什么。
 */
internal suspend fun ToolSession.settingsTool(args: JsonObject): String {
    val action = args["action"].text().ifBlank { "list" }
    if (action == "list") return runTool("查看设置") {
        val s = withContext(Dispatchers.IO) { host.store.loadSettings() }
        ("能改的设置（key｜名字｜现在｜能填什么）：\n" + SettingsKnobs.list(s) +
            "\n要改用 action=set、changes 写 {key: 值}。Key、令牌、代理、权限、应用锁不在这里，要用户自己去设置里改。") to SearchRecord("查看设置")
    }
    if (action == "pin_page" || action == "unpin_page") return pinPage(args["name"].text(), action == "pin_page")
    if (action != "set") return "action 只能是 list、set、pin_page 或 unpin_page"
    val changes = (args["changes"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
    if (changes.isEmpty()) return "changes 里写要改的：{key: 值}。先用 action=list 看有哪些"
    return runTool("改设置") {
        val before = withContext(Dispatchers.IO) { host.store.loadSettings() }
        var next = before
        val errors = mutableListOf<String>()
        val knobs = mutableListOf<SettingsKnobs.Knob>()
        for ((key, value) in changes) {
            val k = SettingsKnobs.find(key)
            if (k == null) { errors += "「$key」不能改（不在能改的设置里）"; continue }
            try { next = k.set(next, value); knobs += k } catch (e: IllegalArgumentException) { errors += "${k.label}：${e.message}" }
        }
        val changed = knobs.filter { it.get(before) != it.get(next) }.distinctBy { it.key }
        fun line(k: SettingsKnobs.Knob) = "${k.label}：${SettingsKnobs.show(before, k)} → ${SettingsKnobs.show(next, k)}"
        if (changed.isEmpty()) return@runTool ((if (errors.isEmpty()) "都已经是这样了，没改。" else "没改成：" + errors.joinToString("；"))) to SearchRecord("改设置：没改")
        // 会多花钱、影响大的：先问一声
        val risky = changed.filter { it.ask }
        if (risky.isNotEmpty() && !approve("改设置？", risky.joinToString("\n") { line(it) }, "改")) {
            return@runTool "用户没同意，设置没改。" to SearchRecord("改设置：已取消")
        }
        val final = next
        withContext(Dispatchers.Main.immediate) { host.updateSettings { cur -> changed.fold(cur) { acc, k -> k.set(acc, k.get(final)) } } }
        val summary = changed.joinToString("\n") { line(it) }
        ui.notice("Kavi 改了设置\n$summary")
        ("已改：\n$summary" + if (errors.isEmpty()) "" else "\n没改成：" + errors.joinToString("；")) to SearchRecord("改设置")
    }
}

/** 小工具网页放进 / 移出侧边栏「我的页面」。name 可以是这个对话里的文件，也可以是「@文件夹/文件名」「游戏/名字.html」。 */
private suspend fun ToolSession.pinPage(name: String, pin: Boolean): String {
    if (name.isBlank()) return "要写 name：小工具的文件名"
    return runTool(if (pin) "放进侧边栏" else "移出侧边栏") {
        val file = wsFile(name)
        if (!file.extension.equals("html", true)) return@runTool "「${file.name}」不是小工具网页，放不进侧边栏" to SearchRecord("放进侧边栏：不是网页")
        val rel = com.jack.ai.data.PinnedPages.rel(app, file)
        val cur = withContext(Dispatchers.IO) { host.store.loadSettings() }.pinnedPages
        val next = if (pin) com.jack.ai.data.PinnedPages.add(cur, rel)
            ?: return@runTool "侧边栏最多放 ${com.jack.ai.data.PinnedPages.MAX} 个页面，满了。先问用户移出哪个" to SearchRecord("放进侧边栏：满了")
            else cur - rel
        if (next == cur) return@runTool (if (pin) "「${file.nameWithoutExtension}」已经在侧边栏里了" else "「${file.nameWithoutExtension}」本来就不在侧边栏里") to SearchRecord("侧边栏：没变")
        withContext(Dispatchers.Main.immediate) { host.updateSettings { it.copy(pinnedPages = next) } }
        val title = file.nameWithoutExtension
        ui.notice(if (pin) "「$title」放进了侧边栏的「我的页面」" else "「$title」移出了侧边栏")
        (if (pin) "已放进侧边栏「我的页面」，用户打开侧边栏点它就能全屏用" else "已移出侧边栏") to SearchRecord(if (pin) "放进侧边栏：$title" else "移出侧边栏：$title")
    }
}
