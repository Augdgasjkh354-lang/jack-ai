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
    if (action != "set") return "action 只能是 list 或 set"
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
