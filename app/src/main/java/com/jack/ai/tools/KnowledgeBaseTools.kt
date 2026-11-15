package com.jack.ai.tools

import com.jack.ai.data.GitHub
import com.jack.ai.data.KbRepo
import com.jack.ai.data.KnowledgeBases
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * knowledge_base：Kavi 管知识库——列出（list）、自己建（create）、往自己建的里写文档（write，content 空就是删那份）、
 * 看 GitHub 项目里有哪些（catalog）、装（install）、同步更新（sync）、开关（enable / disable）、删整个知识库（remove）。
 * 知识库是资料不是指令，装、写、更新不用用户逐条过目；删整个知识库要确认。
 */
internal suspend fun ToolSession.knowledgeBase(args: JsonObject): String {
    val action = args["action"].text().ifBlank { "list" }
    val token = settings.githubToken
    val input = args["repo"].text().trim()
    val repo = GitHub.repoOf(input)
    if (action in setOf("catalog", "install") && repo == null) return "$action 要填 repo：「作者/项目名」或 GitHub 项目网址"
    val ref = args["ref"].text().trim().ifEmpty { Regex("""github\.com/[\w.-]+/[\w.-]+/tree/([^/?#]+)""").find(input)?.groupValues?.get(1).orEmpty() }
    val names = (args["names"] as? JsonArray).orEmpty().map { it.text().trim() }.filter { it.isNotEmpty() }
    if (project?.isolated == true && action !in setOf("list", "catalog")) return "这是隐私项目，知识库在所有对话里共用，这里不装也不改。"
    val store = host.store
    val kbName = args["kb"].text().trim()
    val title = args["title"].text().trim()
    val content = args["content"].text()
    val label = when (action) {
        "create" -> "建知识库：$kbName"
        "write" -> (if (content.isBlank()) "从知识库删文档：" else "写进知识库：") + "$kbName / $title"
        "catalog" -> "看 $repo 里的知识库"
        "install" -> "装知识库：$repo"
        "sync" -> "同步知识库"
        "remove" -> "删知识库：" + names.joinToString("、")
        "enable", "disable" -> (if (action == "enable") "打开" else "关掉") + "知识库：" + names.joinToString("、")
        else -> "查看知识库"
    }
    fun pick(): List<com.jack.ai.data.KnowledgeBase> {
        val all = store.loadKbs()
        return all.filter { kb -> names.any { it == kb.name || it == kb.id } }.ifEmpty { all.filter { kb -> names.any { kb.name.contains(it, true) } } }
    }
    return runTool(label) {
        val text = withContext(Dispatchers.IO) {
            when (action) {
                "create" -> {
                    if (kbName.isEmpty()) return@withContext "create 要填 kb（知识库名字）"
                    store.loadKbs().firstOrNull { it.name == kbName }?.let { return@withContext "已经有叫「$kbName」的知识库了，直接用 write 往里写。" }
                    val kb = KnowledgeBases.create(store, kbName, args["description"].text())
                    host.libraryChanged()
                    "已建好知识库「${kb.name}」。用 write（kb 填这个名字，title、content 填文档）往里放东西。" +
                        Verify.note(listOfNotNull("读回来没找到".takeIf { store.loadKbs().none { it.id == kb.id } }), "已读回核对")
                }
                "write" -> {
                    if (kbName.isEmpty() || title.isEmpty()) return@withContext "write 要填 kb（知识库名字）和 title（文档标题）"
                    val all = store.loadKbs()
                    val kb = all.firstOrNull { it.name == kbName } ?: all.firstOrNull { it.name.contains(kbName, true) }
                        ?: return@withContext "没有叫「$kbName」的知识库。先用 create 建，或者 list 看有哪些。"
                    val msg = KnowledgeBases.write(store, kb.id, title, content)
                    host.libraryChanged()
                    val back = store.loadLibrary().filter { it.kb == kb.id }
                    val ok = if (content.isBlank()) back.none { it.name.removeSuffix(".md") == title.removeSuffix(".md") }
                        else back.any { it.name.removeSuffix(".md") == title.removeSuffix(".md") && store.libraryText(it.id) == content.trim() }
                    msg + "。" + Verify.note(listOfNotNull("读回来对不上".takeIf { !ok }), "已读回核对")
                }
                "catalog" -> {
                    val (l, entries) = KnowledgeBases.catalog(store, token, repo!!, ref)
                    if (entries.isEmpty() || entries.all { it.found.files.isEmpty() }) "「$repo」(${l.branch}) 里没有能当资料的文件。"
                    else "「$repo」(${l.branch}) 里有 ${entries.size} 个知识库：\n" + entries.take(60).joinToString("\n") { e ->
                        "- ${e.name}（文件夹 ${e.found.dir.ifEmpty { "整个仓库" }}，${e.found.files.size} 份文档，${e.state}）：${e.description.take(120)}"
                    } + (if (l.truncated) "\n（仓库太大，GitHub 只给了一部分目录）" else "") + "\n\n要装用 action=install（names 填名字或文件夹，不填就是全部）。"
                }
                "install" -> {
                    val (l, entries) = KnowledgeBases.catalog(store, token, repo!!, ref)
                    val want = if (names.isEmpty() || names.any { it == "all" || it == "全部" }) entries
                        else entries.filter { e -> names.any { it == e.name || it == e.found.dir || it == e.found.dir.substringAfterLast('/') } }
                    when {
                        entries.isEmpty() -> "「$repo」里没找到知识库。"
                        want.isEmpty() -> "「$repo」里没有叫 ${names.joinToString("、")} 的知识库。先用 action=catalog 看有哪些。"
                        want.size > 20 -> "一次最多装 20 个（这次要装 ${want.size} 个），用 names 挑几个。"
                        else -> {
                            val lines = want.map { e ->
                                runCatching { KnowledgeBases.install(host.app, store, token, l, e.found, e.name, e.description).line() }
                                    .getOrElse { "「${e.name}」没装成：${it.message?.take(100)}" }
                            }
                            host.libraryChanged()
                            val back = store.loadKbs()
                            val missing = want.filter { e -> back.none { it.source == KbRepo.source(l.repo, l.branch, e.found.dir) } }
                            "装好了：\n" + lines.joinToString("\n") +
                                "\n以后用 search_my_data（where=library）就能搜到；资料库里能开关、删除，每天会自动查一次更新。" +
                                Verify.note(missing.map { "「${it.name}」读回来没找到" }, "已读回核对")
                        }
                    }
                }
                "sync" -> {
                    val only = if (names.isEmpty()) emptySet() else pick().map { it.id }.toSet()
                    val (results, failed) = KnowledgeBases.sync(host.app, store, token, only)
                    host.libraryChanged()
                    if (results.isEmpty() && failed.isEmpty()) "还没有从 GitHub 装过知识库。用 action=install 装。"
                    else (results.map { it.line() } + failed).joinToString("\n")
                }
                "enable", "disable" -> {
                    val hit = pick()
                    if (hit.isEmpty()) "没找到知识库：${names.joinToString("、")}。装了的：" + store.loadKbs().joinToString("、") { it.name }.ifEmpty { "（没有）" }
                    else {
                        hit.forEach { KnowledgeBases.setEnabled(store, it.id, action == "enable") }
                        host.libraryChanged()
                        "已${if (action == "enable") "打开" else "关掉"}：" + hit.joinToString("、") { it.name } +
                            if (action == "disable") "（关掉的不参与检索，文档还在）" else ""
                    }
                }
                "remove" -> null
                else -> {
                    val kbs = store.loadKbs()
                    val docs = store.loadLibrary()
                    val mine = docs.count { it.kb.isEmpty() }
                    "资料库：我的资料 $mine 份" + if (kbs.isEmpty()) "；还没有知识库（可以用 create 自己建）。" else "；知识库 ${kbs.size} 个：\n" +
                        kbs.joinToString("\n") { kb ->
                            val n = docs.filter { it.kb == kb.id }
                            "- ${kb.name}（${n.size} 份，${n.sumOf { it.chars }} 字，${if (kb.enabled) "开着" else "关着"}，" +
                                (if (kb.source.isEmpty()) "自己建的" else "来自 ${KbRepo.repoOf(kb.source)}") + "）：${kb.description.take(100)}"
                        }
                }
            }
        } ?: run {
            // 删：要确认
            val hit = withContext(Dispatchers.IO) { pick() }
            if (hit.isEmpty()) return@runTool "没找到知识库：${names.joinToString("、")}。" to SearchRecord(label)
            if (!approve("删掉 ${hit.size} 个知识库？", hit.joinToString("\n") { "「${it.name}」和里面的全部文档" }, "删"))
                return@runTool "用户没有同意，没有删。" to SearchRecord(label)
            withContext(Dispatchers.IO) { hit.forEach { KnowledgeBases.remove(store, it.id) } }
            host.libraryChanged()
            val left = withContext(Dispatchers.IO) { store.loadKbs() }
            "已删掉：" + hit.joinToString("、") { it.name } + "。" +
                Verify.note(listOfNotNull("删完读回来还在".takeIf { left.any { k -> hit.any { it.id == k.id } } }), "已读回核对")
        }
        text to SearchRecord(label)
    }
}
