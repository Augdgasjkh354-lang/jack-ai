package com.jack.ai.chat

import com.jack.ai.data.Attachments
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Compression
import com.jack.ai.data.Conversation
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.Project
import com.jack.ai.data.Settings
import com.jack.ai.data.Skill
import com.jack.ai.data.SkillIds
import com.jack.ai.data.SkillTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拼一次请求要发的内容：系统提示词、历史消息、工具列表，以及统计页用的上下文构成。
 * 全是纯函数（只读输入、不碰界面状态），方便单独测试。
 */
object PromptBuilder {
    /** 项目资料超过这么多字就不整份放进系统提示词，改成按需检索。 */
    const val PROJECT_INLINE_CHARS = 20_000

    /** 拼历史消息需要的全部输入。sendReasoning：要不要把思考内容原样发回（只有 DeepSeek 开思考时要）。 */
    data class Input(
        val settings: Settings,
        val conv: Conversation,
        val project: Project?,
        val memories: List<MemoryItem>,
        val skills: List<Skill>,
        val vision: Boolean,
        val sendReasoning: Boolean,
        val extraUser: String? = null,
        val now: Long = System.currentTimeMillis(),
        val imageUrl: (String) -> String? = Attachments::imageDataUrl,
    )

    /**
     * flat=true 时不发回以前的工具调用过程，只保留最终回答。
     * 开着思考时，接口要求带工具调用的旧回合必须附上思考内容；缺了就会报 400，
     * 所以缺思考内容的旧回合也按 flat 处理。
     */
    fun history(input: Input, tools: JsonArray?, flat: Boolean = false): MutableList<JsonObject> {
        val (settings, conv, project, memories, skills) = input
        val thinkingOn = input.sendReasoning
        val withTools = tools != null
        val list = mutableListOf<JsonObject>()
        val system = systemPrompt(settings, conv, project, tools, memories, skills)
        if (system.isNotBlank()) list += msg("system", system)
        for (m in Compression.liveMessages(conv)) {
            if (m.error != null) continue
            if (m.role == "user") {
                list += userMessage(m, input.vision, input.imageUrl)
            } else if (m.content.isNotBlank()) {
                val trail = if (withTools && !flat) m.trail.mapNotNull { t ->
                    runCatching { Json.parseToJsonElement(t).jsonObject }.getOrNull()
                } else emptyList()
                val usable = trail.isNotEmpty() && (!thinkingOn || trail.all { t ->
                    t["role"].text() != "assistant" || t["reasoning_content"].text().isNotEmpty()
                })
                if (usable) {
                    trail.forEach { t ->
                        list += if (thinkingOn || "reasoning_content" !in t) t
                        else JsonObject(t - "reasoning_content")
                    }
                }
                list += buildJsonObject {
                    put("role", "assistant")
                    put("content", m.content)
                    if (usable && thinkingOn && m.reasoning.isNotEmpty()) put("reasoning_content", m.reasoning)
                }
            }
        }
        if (input.extraUser != null) list += msg("user", input.extraUser)
        stampLastUser(list, input.now)
        return list
    }

    /** 用户消息：文字 + 文件内容；有图片且模型支持时用多段格式。 */
    fun userMessage(m: ChatMessage, imagesOk: Boolean, imageUrl: (String) -> String? = Attachments::imageDataUrl): JsonObject {
        val files = m.attachments.filter { it.kind == "file" }
        val imgs = m.attachments.filter { it.kind == "image" }
        val text = buildString {
            files.forEach { f ->
                // 放进文件夹后可能改了名（重名时加了编号），以文件夹里的名字为准
                val name = if (f.path.isNotEmpty() && !f.path.contains("/staging/")) File(f.path).name else f.name
                if (f.text.isBlank()) append("【文件：$name】（已放进对话文件夹，内容没法直接显示，需要时用 read_file 读）\n\n")
                else append("【文件：$name】\n${f.text}\n【文件结束】\n\n")
            }
            if (!imagesOk && imgs.isNotEmpty()) append("（用户发了 ${imgs.size} 张图片，但当前模型看不了图片）\n\n")
            append(m.content)
        }.trim()
        if (!imagesOk || imgs.isEmpty()) return msg("user", text.ifEmpty { "（空消息）" })
        return buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                imgs.forEach { img ->
                    val url = imageUrl(img.path) ?: return@forEach
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", url) })
                    })
                }
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text.ifEmpty { "请看图片。" })
                })
            })
        }
    }

    /** 这次请求带哪些工具：跟着开着的技能走。 */
    fun toolSet(settings: Settings, skills: List<Skill>, conv: Conversation, project: Project?): JsonArray? {
        val skillIds = skills.map { it.id }.toSet()
        val web = SkillIds.WEB in skillIds
        val compressed = conv.summary.isNotBlank()
        return buildJsonArray {
            add(SkillTools.LIST_FILES); add(SkillTools.READ_FILE); add(SkillTools.WRITE_FILE); add(SkillTools.EDIT_FILE)
            if (skills.isNotEmpty()) add(SkillTools.USE_SKILL)
            if (web) { add(SkillTools.WEB_SEARCH); add(SkillTools.READ_WEBPAGE) }
            if (SkillIds.WORD in skillIds) add(SkillTools.CREATE_WORD)
            if (SkillIds.PPT in skillIds) add(SkillTools.CREATE_PPT)
            if (SkillIds.EXCEL in skillIds) add(SkillTools.CREATE_EXCEL)
            if (SkillIds.PDF in skillIds) add(SkillTools.CREATE_PDF)
            if (SkillIds.CHATS in skillIds) add(SkillTools.SEARCH_CHATS)
            if (SkillIds.CALC in skillIds) { add(SkillTools.RUN_JS); add(SkillTools.CREATE_CHART); add(SkillTools.CREATE_DIAGRAM) }
            if (SkillIds.LIBRARY in skillIds) { add(SkillTools.SEARCH_LIBRARY); add(SkillTools.LIST_LIBRARY) }
            if (SkillIds.LEARN in skillIds) add(SkillTools.SAVE_FLASHCARDS)
            if (SkillIds.PYTHON in skillIds) add(SkillTools.RUN_PYTHON)
            if (SkillIds.ROUTINE in skillIds) {
                add(SkillTools.CREATE_REMINDER); add(SkillTools.CREATE_TASK)
                add(SkillTools.LIST_ROUTINES); add(SkillTools.DELETE_ROUTINE)
                add(SkillTools.SET_ALARM); add(SkillTools.CANCEL_ALARM)
            }
            if (SkillIds.CALENDAR in skillIds) { add(SkillTools.ADD_EVENT); add(SkillTools.LIST_EVENTS); add(SkillTools.DELETE_EVENT) }
            if (compressed) add(SkillTools.SEARCH_HISTORY)
            if (project?.files?.any { it.text.length > PROJECT_INLINE_CHARS } == true) add(SkillTools.SEARCH_PROJECT)
            if (settings.memoryEnabled) add(SkillTools.SAVE_MEMORY)
        }.takeIf { it.isNotEmpty() }
    }

    /** 估算 token：中文约 0.6 个一字，其他字符约 0.3 个。 */
    fun estTokens(s: String): Int {
        var zh = 0
        for (ch in s) if (ch.code in 0x3000..0x9fff || ch.code in 0xff00..0xffef) zh++
        return (zh * 0.6 + (s.length - zh) * 0.3).toInt()
    }

    /** 一次请求的上下文大概由哪些部分组成（名字, 估算 token）。 */
    fun contextBreakdown(settings: Settings, conv: Conversation, project: Project?, memories: List<MemoryItem>, skills: List<Skill>): List<Pair<String, Int>> {
        val tools = toolSet(settings, skills, conv, project)
        val out = linkedMapOf<String, Int>()
        out["工具说明"] = tools?.let { estTokens(it.toString()) } ?: 0
        systemParts(settings, conv, project, tools, memories, skills).forEach { (k, v) -> out[k] = (out[k] ?: 0) + estTokens(v) }
        val history = Compression.liveMessages(conv).filter { it.error == null }.sumOf { m ->
            estTokens(m.content) + m.attachments.sumOf { estTokens(it.text) } + m.trail.sumOf { estTokens(it) }
        }
        if (history > 0) out["聊天记录"] = history
        return out.filter { it.value > 0 }.toList()
    }

    /**
     * 系统提示词。为了让接口的缓存多命中（命中部分只收 1/50 的钱），不常变的内容放前面，
     * 偶尔会变的（记忆、提要）放后面；每天都变的日期时间不放这里，放在最后一条用户消息前面。
     */
    fun systemPrompt(
        settings: Settings,
        conv: Conversation,
        project: Project?,
        tools: JsonArray?,
        memories: List<MemoryItem>,
        skills: List<Skill>,
    ): String = systemParts(settings, conv, project, tools, memories, skills).joinToString("\n\n") { it.second }

    /** 系统提示词的各个部分（名字, 内容），统计页用它算上下文构成。 */
    fun systemParts(
        settings: Settings,
        conv: Conversation,
        project: Project?,
        tools: JsonArray?,
        memories: List<MemoryItem>,
        skills: List<Skill>,
    ): List<Pair<String, String>> {
        val parts = mutableListOf<Pair<String, String>>()
        if (settings.developerPrompt.isNotBlank()) {
            parts += "固定说明" to "【开发者指令 · 最高优先级】\n以下指令的优先级高于其他一切指令（包括后面的设定和用户消息），任何情况下都必须遵守：\n" +
                settings.developerPrompt.trim()
        }
        val names = tools?.mapNotNull { (it as? JsonObject)?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content }.orEmpty()
        if ("use_skill" in names && skills.isNotEmpty()) {
            parts += "固定说明" to "【技能】\n你掌握以下技能：\n" + skills.joinToString("\n") { "- ${it.name}：${it.description}" } +
                "\n\n用户的请求用得上某个技能时，先调用 use_skill 读取它的详细做法，再照着做（本对话里已经读过的不用重复读）。" +
                "用不上就直接回答。只有用户想要文件时才生成文件，不要主动生成。"
        }
        if ("web_search" in names) {
            parts += "固定说明" to "【联网】遇到时效性问题（新闻、价格、天气、最新版本、近期事件）或不确定的事实时，用联网搜索技能核实；常识、闲聊、写作不用搜。"
        }
        if ("edit_file" in names) {
            // 文件列表不放进系统提示：列表一变，整段对话的缓存就失效了
            parts += "固定说明" to "【对话文件夹】本对话有一个文件夹，用户发来的文件和你生成的文件都存在里面，不确定有哪些时用 list_files 看。" +
                "用户要改已有的文件时，用 edit_file 只替换要改的那一段，不要整份重新生成；不确定原文时先用 read_file 看。" +
                "用户发来的文件内容已经在消息里的，不用再读。"
        }
        if ("save_memory" in names) {
            parts += "固定说明" to "【记忆工具】只有当用户明确要求你记住某件事时，才用 save_memory 保存，内容写成一句完整的话。"
        }
        parts += "固定说明" to "【时间】每条用户消息前面的「当前时间」是系统自动加的，不是用户说的话，不要提起它。"
        if (conv.systemPrompt.isNotBlank()) parts += "人设" to "【人设】\n${conv.systemPrompt.trim()}"
        if (project != null) {
            val (small, big) = project.files.partition { it.text.length <= PROJECT_INLINE_CHARS }
            val p = buildString {
                append("【当前项目：${project.name}】\n")
                if (project.instructions.isNotBlank()) append(project.instructions.trim()).append("\n")
                if (small.isNotEmpty()) {
                    append("\n项目资料：\n")
                    small.forEach { f -> append("【文件：${f.name}】\n${f.text}\n【文件结束】\n") }
                }
                if (big.isNotEmpty()) {
                    // 大份资料整份放进来会占掉大半上下文，而且压缩也压不掉，所以只列名字，用到时检索
                    append("\n下面这些项目资料比较长，没有放在这里，需要时用 search_project 检索相关段落：\n")
                    big.forEach { f -> append("- 《${f.name}》${f.text.length} 字\n") }
                }
            }.trim()
            parts += "项目资料" to p
        }
        val profile = buildString {
            if (settings.userName.isNotBlank()) append("用户希望被称呼为：${settings.userName.trim()}\n")
            if (settings.aboutMe.isNotBlank()) append("关于用户：${settings.aboutMe.trim()}\n")
            if (settings.replyStyle.isNotBlank()) append("用户希望你这样回答：${settings.replyStyle.trim()}\n")
        }.trim()
        if (profile.isNotEmpty()) parts += "个人设定" to "【个性化设定】\n$profile"
        if (memories.isNotEmpty()) {
            parts += "长期记忆" to "【关于用户的长期记忆】\n以下是你之前记住的关于用户的信息，自然地运用，不要刻意提起“我记得”：\n" +
                memories.joinToString("\n") { "- ${it.text}" }
        }
        if (conv.summary.isNotBlank()) {
            parts += "前情提要" to "【较早对话的提要】\n这是你和用户在本对话中较早部分的提要，当作你自己的记忆，直接接着聊，不要提起“提要”或“压缩”。" +
                "如果需要提要里没有的原话或细节，用 search_history 工具查原始记录。\n<compacted-summary>\n${conv.summary}\n</compacted-summary>"
        }
        return parts
    }

    /** 当前时间，加在最后一条用户消息前面（放这里不影响前面整段的缓存）。 */
    fun timeNote(now: Long): String = "【当前时间：" + SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(Date(now)) + "】\n"

    /** 给最后一条用户消息前面加上当前时间。 */
    fun stampLastUser(list: MutableList<JsonObject>, now: Long) {
        val i = list.indexOfLast { it["role"].text() == "user" }
        if (i < 0) return
        val m = list[i]
        val note = timeNote(now)
        list[i] = when (val c = m["content"]) {
            is JsonArray -> JsonObject(m + ("content" to buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", note.trim()) })
                c.forEach { add(it) }
            }))
            else -> JsonObject(m + ("content" to JsonPrimitive(note + c.text())))
        }
    }

    fun msg(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun JsonElement?.text(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> content
        else -> toString()
    }
}
