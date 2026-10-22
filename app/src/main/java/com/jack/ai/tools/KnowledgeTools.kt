package com.jack.ai.tools

import android.content.Intent
import android.provider.AlarmClock
import com.jack.ai.api.formatResultsForModel
import com.jack.ai.api.toSources
import com.jack.ai.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 知识类工具：旧记录、聊天记录、资料库、项目资料、记忆、复习卡片、技能说明。 */

internal suspend fun ToolSession.searchOld(query: String) = runTool("翻阅旧记录：$query") {
    findOld(query) to SearchRecord("翻阅旧记录：$query")
}

private fun ToolSession.findOld(query: String) = Compression.searchHistory(Compression.compressedMessages(conv), query)

private suspend fun ToolSession.findChats(query: String): String {
    val all = host.conversations()
    return withContext(Dispatchers.Default) { Compression.searchAllChats(all, query, conv.id) }
}

private suspend fun ToolSession.findLibrary(query: String): String {
    val hits = withContext(Dispatchers.IO) { host.libraryIndex().search(query) }
    return if (hits.isEmpty()) "资料库里没有找到和“$query”相关的内容。" else hits.joinToString("\n\n") {
        "【《${it.doc}》第 ${it.part} 段】\n${it.text}"
    }
}

private suspend fun ToolSession.findProject(query: String): String {
    val p = project ?: throw IllegalStateException("不在项目里")
    val hits = withContext(Dispatchers.Default) { host.projectIndex(p).search(query) }
    return if (hits.isEmpty()) "项目资料里没有找到和“$query”相关的内容。" else hits.joinToString("\n\n") {
        "【《${it.doc}》第 ${it.part} 段】\n${it.text}"
    }
}

private fun ToolSession.libraryDocs(): String {
    val docs = host.store.loadLibrary()
    return if (docs.isEmpty()) "资料库是空的。用户可以在左侧菜单「资料库」里导入文档。"
    else docs.joinToString("\n") { "《${it.name}》${it.chars} 字" }
}

private val SOURCE_NAMES = mapOf("chats" to "以前的聊天", "history" to "本对话较早的原话", "library" to "资料库", "project" to "项目资料")

/**
 * search_my_data：在用户自己的东西里找，合并了以前的 search_chats、search_history、search_library、list_library、search_project。
 * where 不填就把能搜的地方都搜一遍；query 不填就列出资料库或项目里有哪些文档。
 */
internal suspend fun ToolSession.searchMyData(args: JsonObject): String {
    val query = args["query"].text().trim()
    val available = com.jack.ai.chat.PromptBuilder.searchSources(skillIds, conv, project)
    val want = args["where"].text().ifBlank { "all" }
    val where = if (want == "all") available else listOf(want).filter { it in available }
    if (available.isEmpty()) return "现在没有能找的地方：聊天记录搜索和资料库技能都没开，本对话也还没被压缩过。"
    if (where.isEmpty()) return "现在不能在「$want」里找。能找的地方：" + available.joinToString("、") { "$it（${SOURCE_NAMES[it]}）" }
    val label = if (query.isEmpty()) "查看资料" else "翻找：$query"
    return runTool(label) {
        val parts = where.mapNotNull { w ->
            val text = when {
                query.isEmpty() && w == "library" -> libraryDocs()
                query.isEmpty() && w == "project" -> project!!.files.joinToString("\n") { "《${it.name}》${it.text.length} 字" }
                query.isEmpty() -> null
                w == "chats" -> findChats(query)
                w == "history" -> findOld(query)
                w == "library" -> findLibrary(query)
                else -> findProject(query)
            } ?: return@mapNotNull null
            if (where.size == 1) text else "【${SOURCE_NAMES[w]}】\n$text"
        }
        (parts.joinToString("\n\n").ifBlank { "要给出搜索关键词 query。" }) to SearchRecord(label)
    }
}

/**
 * 存记忆：本对话、项目、全局三种。不该存全局的（这个对话关了全局记忆）就存进本对话。
 * 填了 replace 就是改旧的那条，content 留空就是删掉它。
 */
internal suspend fun ToolSession.remember(args: JsonObject): String {
    val text = args["content"].text().trim()
    val old = args["replace"].text().trim()
    val globalOn = settings.memoryEnabled && conv.useGlobalMemory
    val scope = when (val s = args["scope"].text()) {
        "global" -> if (globalOn) s else "chat"
        "project" -> if (project != null) s else "chat"
        else -> "chat"
    }
    val where = when (scope) { "global" -> "全局"; "project" -> "项目"; else -> "本对话" }
    val label = when {
        old.isEmpty() -> "记住（$where）：$text"
        text.isEmpty() -> "忘掉（$where）：$old"
        else -> "改记忆（$where）：$text"
    }
    return runTool(label) {
        if (text.isEmpty() && old.isEmpty()) throw IllegalArgumentException("内容是空的")
        when (scope) {
            "global" -> {
                val list = host.store.loadMemories()
                val updated = if (old.isEmpty()) list + MemoryItem(text = text) else {
                    // 按文字改，没动的条目保留原来的编号和时间
                    val texts = ChatMemory.replace(list.map { it.text }, old, text)
                    val byText = list.associateBy { it.text }
                    texts.map { byText[it] ?: MemoryItem(text = it) }
                }
                host.store.saveMemories(updated)
                host.setMemories(updated)
            }
            else -> withContext(Dispatchers.IO) {
                val file = if (scope == "project") ChatMemory.projectFile(app, project!!.id) else ChatMemory.chatFile(app, conv)
                if (old.isEmpty()) ChatMemory.add(file, text)
                else ChatMemory.write(file, ChatMemory.replace(ChatMemory.items(ChatMemory.read(file)), old, text))
            }
        }
        val done = when {
            old.isEmpty() -> "已存进${where}记忆。"
            text.isEmpty() -> "已从${where}记忆里删掉。"
            else -> "已改好${where}记忆。"
        }
        // 存完重新读一遍：新内容在不在；删除的话，原来那条还在不在
        val now = withContext(Dispatchers.IO) {
            when (scope) {
                "global" -> host.store.loadMemories().map { it.text }
                "project" -> ChatMemory.items(ChatMemory.read(ChatMemory.projectFile(app, project!!.id)))
                else -> ChatMemory.items(ChatMemory.read(ChatMemory.chatFile(app, conv)))
            }
        }
        val problem = when {
            text.isNotEmpty() && text !in now -> "存完后在${where}记忆里查不到这条"
            text.isEmpty() && now.any { it == old.removePrefix("- ").trim() } -> "删完后${where}记忆里还有这条"
            else -> null
        }
        done + Verify.note(listOfNotNull(problem), "已重新读过${where}记忆，确认生效（现在共 ${now.size} 条）") to SearchRecord(label)
    }
}

internal suspend fun ToolSession.searchChats(query: String) = runTool("翻阅聊天记录：$query") {
    findChats(query) to SearchRecord("翻阅聊天记录：$query")
}

internal suspend fun ToolSession.searchLibrary(query: String) = runTool("翻阅资料库：$query") {
    findLibrary(query) to SearchRecord("翻阅资料库：$query")
}

internal suspend fun ToolSession.searchProject(query: String) = runTool("翻阅项目资料：$query") {
    findProject(query) to SearchRecord("翻阅项目资料：$query")
}

internal suspend fun ToolSession.listLibrary() = runTool("查看资料库") {
    libraryDocs() to SearchRecord("查看资料库")
}

internal suspend fun ToolSession.saveCards(args: JsonObject) = runTool("生成复习卡片") {
    val deck = args["deck"].text().ifBlank { "未分类" }
    val cards = (args["cards"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val f = o["front"].text().trim()
        val b = o["back"].text().trim()
        if (f.isEmpty() || b.isEmpty()) null else Flashcard(deck = deck, front = f, back = b)
    }
    if (cards.isEmpty()) throw IllegalArgumentException("没有卡片内容")
    host.editCards { it + cards }
    "已保存 ${cards.size} 张卡片到卡组「$deck」。" to SearchRecord("生成复习卡片：$deck（${cards.size} 张）")
}

internal suspend fun ToolSession.useSkill(name: String) = runTool("使用技能：$name") {
    val skill = findSkill(skills, name)
    if (skill?.id == SkillIds.RESEARCH) maxRounds = maxOf(maxRounds, 25)
    val text = skill?.let { "【${it.name}】\n${it.instructions}" }
        ?: ("没有这个技能。可用的技能：" + skills.joinToString("、") { it.name })
    text to SearchRecord("使用技能：${skill?.name ?: name}")
}

/** 收件箱用：把整理好的笔记存进项目或资料库，存完重新读一遍核实。 */
internal suspend fun ToolSession.saveNote(args: JsonObject): String {
    val title = args["title"].text().trim().ifBlank { "笔记" }.take(60)
    val content = args["content"].text().trim()
    if (content.isEmpty()) return "笔记内容是空的"
    val want = args["project"].text().trim()
    val projects = withContext(Dispatchers.IO) { host.store.loadProjects() }
    val p = if (want.isEmpty()) null else projects.firstOrNull { it.name == want }
        ?: projects.firstOrNull { it.name.contains(want, true) || want.contains(it.name, true) }
        ?: return "没有「$want」这个项目。现有项目：" + projects.joinToString("、") { it.name }.ifEmpty { "（还没有）" } + "。不确定就不填 project，存进资料库。"
    val where = p?.let { "项目「${it.name}」" } ?: "资料库"
    return runTool("存进$where：$title") {
        withContext(Dispatchers.Main) { host.saveNote(p?.id, title, content) }
        val ok = withContext(Dispatchers.IO) {
            if (p != null) host.store.loadProjects().firstOrNull { it.id == p.id }?.files?.any { it.name == "$title.md" } == true
            else host.store.loadLibrary().any { it.name == "$title.md" }
        }
        "已存进$where：「$title」。" + Verify.note(listOfNotNull("存完后在$where 里查不到这份笔记".takeIf { !ok }), "已在${where}里查到") to
            SearchRecord("存进$where：$title")
    }
}
