package com.jack.ai.tools

import com.jack.ai.data.GitHub
import com.jack.ai.data.InjectionGuard
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Skill
import com.jack.ai.data.SkillRepo
import com.jack.ai.data.activeSkills
import com.jack.ai.data.Verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal val SKILL_ACTIONS = setOf("skills", "skill_install", "skill_sync", "skill_remove")

/** 从 GitHub 仓库装来的技能：拉下来的全文、带的资料和指纹，装之前给用户过目。 */
private class Incoming(val skill: Skill, val texts: Map<String, String>, val replaces: Skill?)

/**
 * 从 GitHub 仓库装技能、同步、删除（github 工具的 skills / skill_install / skill_sync / skill_remove）。
 * 技能是写给 Kavi 的指令：装和更新之前都把内容给用户看（疑似注入的句子标出来），用户点头才写进去。
 */
internal suspend fun ToolSession.githubSkills(action: String, args: JsonObject): String {
    val token = settings.githubToken
    val repo = GitHub.repoOf(args["repo"].text())
    val ref = args["ref"].text().trim()
    val wanted = (args["skills"] as? JsonArray).orEmpty().map { it.text().trim() }.filter { it.isNotEmpty() }
    val label = when (action) {
        "skills" -> "看 $repo 里的技能"
        "skill_install" -> "从 $repo 装技能"
        "skill_sync" -> "同步 GitHub 技能"
        else -> "删掉 GitHub 技能"
    }
    if ((action == "skills" || action == "skill_install") && repo == null) return "$action 要填 repo（「作者/项目名」或项目网址）"
    if (project?.isolated == true && action != "skills") return "这是隐私项目，技能在所有对话里共用，这里不装也不改技能。"
    return runTool(label) {
        val installed = withContext(Dispatchers.IO) { host.store.loadSettings().customSkills }
        val text = when (action) {
            "skills" -> listSkills(token, repo!!, ref, installed)
            "skill_install" -> install(token, repo!!, ref, wanted, installed)
            "skill_sync" -> sync(token, repo, installed)
            else -> remove(wanted, installed)
        }
        text to SearchRecord(label)
    }
}

private fun ToolSession.parsedOf(token: String, l: SkillRepo.Listing, f: SkillRepo.Found) =
    SkillRepo.parse(SkillRepo.fetch(token, l.repo, l.branch, f.skillPath), f.dir.substringAfterLast('/').ifEmpty { l.repo.substringAfter('/') })

private suspend fun ToolSession.listSkills(token: String, repo: String, ref: String, installed: List<Skill>): String = withContext(Dispatchers.IO) {
    val l = SkillRepo.list(token, repo, ref)
    if (l.skills.isEmpty()) return@withContext "「$repo」(${l.branch}) 里没找到 SKILL.md。技能要放在一个文件夹里、里面有 SKILL.md（开头 --- 里写 name 和 description）。"
    val lines = l.skills.take(40).map { f ->
        val p = runCatching { parsedOf(token, l, f) }.getOrNull()
        val mine = installed.firstOrNull { it.source == SkillRepo.source(repo, l.branch, f.dir) }
        val state = when { mine == null -> "没装"; mine.sourceHash == f.hash -> "已装（最新）"; else -> "已装，有更新" }
        "- ${p?.name ?: f.dir}（文件夹 ${f.dir.ifEmpty { "根目录" }}，带 ${f.files.size} 份资料，$state）：${p?.description.orEmpty().take(120)}"
    }
    "「$repo」(${l.branch}) 里有 ${l.skills.size} 个技能：\n" + lines.joinToString("\n") +
        (if (l.skills.size > 40) "\n……还有 ${l.skills.size - 40} 个没列出" else "") +
        (if (l.truncated) "\n（仓库太大，GitHub 只给了一部分目录）" else "") +
        "\n\n要装就用 skill_install（skills 填名字或文件夹，不填就是全部），装之前会把内容给用户过目。"
}

/** 拉下一个技能的全文和资料，做成要装的样子（同名的另起名字，更新时沿用原来的 id 和开关）。 */
private fun ToolSession.prepare(token: String, l: SkillRepo.Listing, f: SkillRepo.Found, old: Skill?, taken: Set<String>): Incoming {
    val p = parsedOf(token, l, f)
    val texts = f.files.mapValues { (_, full) -> SkillRepo.fetch(token, l.repo, l.branch, full) }
    var name = p.name
    if (old == null && name in taken) name = "$name（${l.repo.substringAfter('/')}）".take(40)
    val body = if (p.body.length > SkillRepo.BODY_MAX) p.body.take(SkillRepo.BODY_MAX) + "\n…（太长，后面截断了）" else p.body
    val desc = p.description.ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120) }
    val skill = (old ?: Skill(name = name, description = desc, instructions = body)).copy(
        name = old?.name ?: name, description = desc, instructions = body,
        source = SkillRepo.source(l.repo, l.branch, f.dir), sourceHash = f.hash, files = texts.keys.sorted(),
    )
    return Incoming(skill, texts, old)
}

/** 给用户过目的样子：每个技能的名字、说明、带的资料、开头一段；疑似对 AI 下指令的句子单独列出来。 */
private fun preview(list: List<Incoming>): String = buildString {
    list.forEach { inc ->
        val s = inc.skill
        val all = s.instructions + "\n" + inc.texts.values.joinToString("\n")
        val scan = InjectionGuard.scan(all)
        append("「${s.name}」${if (inc.replaces != null) "（更新）" else ""}：${s.description}\n")
        if (s.files.isNotEmpty()) append("带 ${s.files.size} 份资料：${s.files.take(6).joinToString("、")}${if (s.files.size > 6) "…" else ""}\n")
        if (scan.findings.isNotEmpty() || scan.hidden > 0) {
            append("⚠️ 里面有疑似对 AI 下指令的话，装之前请看清：\n")
            scan.findings.take(3).forEach { append("  · ${it.line}\n") }
            if (scan.hidden > 0) append("  · 藏着 ${scan.hidden} 个看不见的字符\n")
        }
        append(s.instructions.take(400).trim()).append(if (s.instructions.length > 400) "…\n\n" else "\n\n")
    }
    append("技能是写给 Kavi 的做事说明，装上以后每个对话都能用。装好后在「技能」里能看全文、能关掉。")
}

private suspend fun ToolSession.save(list: List<Incoming>, removed: List<Skill> = emptyList()): Boolean {
    val ctx = host.app
    withContext(Dispatchers.IO) {
        list.forEach { inc ->
            val dir = SkillRepo.dir(ctx, inc.skill.id)
            dir.deleteRecursively()
            inc.texts.forEach { (rel, t) ->
                val f = java.io.File(dir, rel).canonicalFile
                if (f.path.startsWith(dir.canonicalPath + java.io.File.separator)) { f.parentFile?.mkdirs(); f.writeText(t) }
            }
        }
        removed.forEach { SkillRepo.dir(ctx, it.id).deleteRecursively() }
    }
    val ids = list.map { it.skill.id }.toSet() + removed.map { it.id }
    withContext(Dispatchers.Main) {
        host.updateSettings { s -> s.copy(customSkills = s.customSkills.filter { it.id !in ids } + list.map { it.skill }) }
    }
    val back = withContext(Dispatchers.IO) { host.store.loadSettings().customSkills }
    return list.all { inc -> back.any { it.id == inc.skill.id && it.sourceHash == inc.skill.sourceHash } } && removed.none { r -> back.any { it.id == r.id } }
}

private suspend fun ToolSession.install(token: String, repo: String, ref: String, wanted: List<String>, installed: List<Skill>): String {
    val l = withContext(Dispatchers.IO) { SkillRepo.list(token, repo, ref) }
    if (l.skills.isEmpty()) return "「$repo」里没找到 SKILL.md，没装。"
    val pick = if (wanted.isEmpty() || wanted.any { it == "all" || it == "全部" }) l.skills else l.skills.filter { f ->
        wanted.any { w -> f.dir == w || f.dir.substringAfterLast('/') == w } ||
            wanted.any { w -> runCatching { withContext(Dispatchers.IO) { parsedOf(token, l, f) }.name }.getOrNull() == w }
    }
    if (pick.isEmpty()) return "「$repo」里没有叫 ${wanted.joinToString("、")} 的技能。先用 action=skills 看有哪些。"
    if (pick.size > 30) return "一次最多装 30 个（这个仓库有 ${pick.size} 个），用 skills 挑几个名字。"
    val taken = (settings.activeSkills().map { it.name } + installed.map { it.name }).toMutableSet()
    val incoming = withContext(Dispatchers.IO) {
        pick.map { f ->
            val old = installed.firstOrNull { it.source == SkillRepo.source(repo, l.branch, f.dir) }
            prepare(token, l, f, old, taken).also { taken += it.skill.name }
        }
    }
    if (!approve("从 GitHub 装 ${incoming.size} 个技能？", "来自 $repo（${l.branch}）\n\n" + preview(incoming), "装", strict = true))
        return "用户没有同意，没有装。"
    val ok = save(incoming)
    ui.notice("从 GitHub 装好了技能：" + incoming.joinToString("、") { it.skill.name })
    return "已装好 ${incoming.size} 个技能：" + incoming.joinToString("、") { it.skill.name } +
        "。下一个对话开始就能在技能清单里看到、用 use_skill 读（这个对话的清单不变）。以后说「同步 GitHub 技能」就能拉更新。" +
        Verify.note(listOfNotNull("存完读回来对不上".takeIf { !ok }), "已读回核对")
}

private suspend fun ToolSession.sync(token: String, only: String?, installed: List<Skill>): String {
    val mine = installed.filter { it.source.isNotEmpty() && (only == null || SkillRepo.repoOf(it.source) == only) }
    if (mine.isEmpty()) return "还没有从 GitHub 装过技能" + (only?.let { "（$it）" } ?: "") + "。用 skill_install 装。"
    val updates = ArrayList<Incoming>()
    val gone = ArrayList<Skill>()
    val failed = ArrayList<String>()
    withContext(Dispatchers.IO) {
        mine.groupBy { SkillRepo.repoOf(it.source) + "@" + SkillRepo.branchOf(it.source) }.forEach { (key, group) ->
            val l = runCatching { SkillRepo.list(token, key.substringBefore('@'), key.substringAfter('@')) }
                .getOrElse { failed += "${key.substringBefore('@')}：${it.message?.take(80)}"; return@forEach }
            group.forEach { s ->
                val f = l.skills.firstOrNull { it.dir == SkillRepo.dirOf(s.source) }
                when {
                    f == null -> gone += s
                    // 资料不在了（换手机用备份恢复时不带这些文件）也重新拉一遍
                    f.hash != s.sourceHash || s.files.any { !java.io.File(SkillRepo.dir(host.app, s.id), it).exists() } -> runCatching { updates += prepare(token, l, f, s, emptySet()) }.onFailure { failed += "${s.name}：${it.message?.take(80)}" }
                }
            }
        }
    }
    val tail = (if (gone.isNotEmpty()) "\n仓库里已经没有的（没动，要删用 skill_remove）：" + gone.joinToString("、") { it.name } else "") +
        (if (failed.isNotEmpty()) "\n没查成的：" + failed.joinToString("；") else "")
    if (updates.isEmpty()) return "查了 ${mine.size} 个 GitHub 技能，都是最新的。$tail"
    if (!approve("更新 ${updates.size} 个 GitHub 技能？", preview(updates), "更新", strict = true))
        return "用户没有同意，没有更新。$tail"
    val ok = save(updates)
    return "已更新：" + updates.joinToString("、") { it.skill.name } + "（下一个对话起用新版）。$tail" +
        Verify.note(listOfNotNull("存完读回来对不上".takeIf { !ok }), "已读回核对")
}

private suspend fun ToolSession.remove(wanted: List<String>, installed: List<Skill>): String {
    if (wanted.isEmpty()) return "skill_remove 要在 skills 里填要删的技能名。"
    val hit = installed.filter { s -> s.source.isNotEmpty() && wanted.any { it == s.name } }
    if (hit.isEmpty()) return "没找到这些从 GitHub 装的技能：${wanted.joinToString("、")}（只能删从 GitHub 装的；自己加的在「技能」里删）。"
    if (!approve("删掉 ${hit.size} 个从 GitHub 装的技能？", hit.joinToString("\n") { "「${it.name}」来自 ${SkillRepo.repoOf(it.source)}" }, "删", strict = true))
        return "用户没有同意，没有删。"
    val ok = save(emptyList(), hit)
    return "已删掉：" + hit.joinToString("、") { it.name } + "。" + Verify.note(listOfNotNull("删完读回来还在".takeIf { !ok }), "已读回核对")
}
