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
    Compression.searchHistory(Compression.compressedMessages(conv), query) to
        SearchRecord("翻阅旧记录：$query")
}

/** 存记忆：本对话、项目、全局三种。不该存全局的（这个对话关了全局记忆）就存进本对话。 */
internal suspend fun ToolSession.remember(args: JsonObject): String {
    val text = args["content"].text().trim()
    val globalOn = settings.memoryEnabled && conv.useGlobalMemory
    val scope = when (val s = args["scope"].text()) {
        "global" -> if (globalOn) s else "chat"
        "project" -> if (project != null) s else "chat"
        else -> "chat"
    }
    val where = when (scope) { "global" -> "全局"; "project" -> "项目"; else -> "本对话" }
    return runTool("记住（$where）：$text") {
        if (text.isEmpty()) throw IllegalArgumentException("内容是空的")
        when (scope) {
            "global" -> {
                val list = host.store.loadMemories() + MemoryItem(text = text)
                host.store.saveMemories(list)
                host.setMemories(list)
            }
            "project" -> withContext(Dispatchers.IO) { ChatMemory.add(ChatMemory.projectFile(app, project!!.id), text) }
            else -> withContext(Dispatchers.IO) { ChatMemory.add(ChatMemory.chatFile(app, conv), text) }
        }
        "已存进${where}记忆。" to SearchRecord("记住（$where）：$text")
    }
}

internal suspend fun ToolSession.searchChats(query: String) = runTool("翻阅聊天记录：$query") {
    val all = host.conversations()
    withContext(Dispatchers.Default) { Compression.searchAllChats(all, query, conv.id) } to
        SearchRecord("翻阅聊天记录：$query")
}

internal suspend fun ToolSession.searchLibrary(query: String) = runTool("翻阅资料库：$query") {
    val hits = withContext(Dispatchers.IO) { host.libraryIndex().search(query) }
    (if (hits.isEmpty()) "资料库里没有找到和“$query”相关的内容。" else hits.joinToString("\n\n") {
        "【《${it.doc}》第 ${it.part} 段】\n${it.text}"
    }) to SearchRecord("翻阅资料库：$query")
}

internal suspend fun ToolSession.searchProject(query: String) = runTool("翻阅项目资料：$query") {
    val p = project ?: throw IllegalStateException("不在项目里")
    val hits = withContext(Dispatchers.Default) { host.projectIndex(p).search(query) }
    (if (hits.isEmpty()) "项目资料里没有找到和“$query”相关的内容。" else hits.joinToString("\n\n") {
        "【《${it.doc}》第 ${it.part} 段】\n${it.text}"
    }) to SearchRecord("翻阅项目资料：$query")
}

internal suspend fun ToolSession.listLibrary() = runTool("查看资料库") {
    val docs = host.store.loadLibrary()
    (if (docs.isEmpty()) "资料库是空的。用户可以在左侧菜单「资料库」里导入文档。"
    else docs.joinToString("\n") { "《${it.name}》${it.chars} 字" }) to SearchRecord("查看资料库")
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
