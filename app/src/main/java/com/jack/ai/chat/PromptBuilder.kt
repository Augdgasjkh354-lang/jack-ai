package com.jack.ai.chat

import com.jack.ai.data.Attachments
import com.jack.ai.data.ChatMemory
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Compression
import com.jack.ai.data.Conversation
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.MailAccount
import com.jack.ai.data.readyMails
import com.jack.ai.data.Project
import com.jack.ai.data.globalAllowed
import com.jack.ai.data.workMemoryAllowed
import com.jack.ai.data.separateWork
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
        /** 对话记忆和项目记忆文件的内容（调用方读好传进来，这里不碰文件）。 */
        val chatMemory: String = "",
        val projectMemory: String = "",
        /** 最近一篇做梦日记。 */
        val journal: String = "",
        /** Kavi 自己的近况（自由时间里写的）。 */
        val lately: String = "",
        /** Kavi 的自传（它自己写的，每周更新，同一篇）。 */
        val story: String = "",
        /** 工作记忆（所有 Work 共用）；只有 Work 用。 */
        val workMemory: String = "",
        /** 已上传到 DeepSeek 的图片：路径 → file_id，这些图只发编号。 */
        val fileIds: Map<String, String> = emptyMap(),
        val now: Long = System.currentTimeMillis(),
        /** 对话文件夹、表格、提醒的清单，加在最后一条用户消息前面（调用方读好传进来）。 */
        val contextNote: String = "",
        /** 现在 App 的版本号；0 表示不知道，不提醒更新。 */
        val appVersion: Int = 0,
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
        val system = systemPrompt(settings, conv, project, tools, memories, skills, input.chatMemory, input.projectMemory, input.journal, input.lately, input.story, input.workMemory)
        if (system.isNotBlank()) list += msg("system", system)
        for (m in Compression.liveMessages(conv)) {
            if (m.error != null) continue
            if (m.role == "user") {
                val message = userMessage(m, input.vision, input.imageUrl, input.fileIds)
                list += if (m.contextPrefix != null) prefixUser(message, m.contextPrefix) else message
            } else if (m.content.isNotBlank()) {
                val trail = if (withTools && !flat) m.trail.mapNotNull { t ->
                    runCatching { Json.parseToJsonElement(t).jsonObject }.getOrNull()
                } else emptyList()
                val usable = trail.isNotEmpty() && complete(trail) && (!thinkingOn || trail.all { t ->
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
        val lastText = conv.messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        if (input.extraUser != null || conv.messages.lastOrNull { it.role == "user" }?.contextPrefix == null) {
            stampLastUser(list, input.now, updateNote(conv, input.appVersion) + input.contextNote + mentionNote(lastText, skills, settings.readyMails))
        }
        return list
    }

    /**
     * 工具调用过程是不是完整的：每个调用都有对应的结果。工具跑到一半被停止时会缺结果，
     * 原样发回接口会报 400，这种回合只发最终回答。
     */
    internal fun complete(trail: List<JsonObject>): Boolean {
        val called = trail.flatMap { t ->
            (t["tool_calls"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("id").text().takeIf { id -> id.isNotEmpty() } }
        }.toSet()
        val answered = trail.filter { it["role"].text() == "tool" }.map { it["tool_call_id"].text() }.toSet()
        return answered.containsAll(called)
    }

    /** 用户消息：文字 + 文件内容；有图片且模型支持时用多段格式。 */
    fun userMessage(
        m: ChatMessage, imagesOk: Boolean, imageUrl: (String) -> String? = Attachments::imageDataUrl,
        fileIds: Map<String, String> = emptyMap(),
    ): JsonObject {
        val files = m.attachments.filter { it.kind == "file" }
        val imgs = m.attachments.filter { it.kind == "image" }
        val text = buildString {
            files.forEach { f ->
                // 放进文件夹后可能改了名（重名时加了编号），以文件夹里的名字为准
                val name = if (f.path.isNotEmpty() && !f.path.contains("/staging/")) File(f.path).name else f.name
                if (f.text.isBlank()) append("【File: $name】(saved in the conversation folder; its content can't be shown here, read it with read_file when needed)\n\n")
                else append("【File: $name】\n${f.text}\n【End of file】\n\n")
            }
            if (!imagesOk && imgs.isNotEmpty()) append("(${imgs.size} image(s) were sent, but the current model can't see images)\n\n")
            append(m.content)
        }.trim()
        if (!imagesOk || imgs.isEmpty()) return msg("user", text.ifEmpty { "(empty message)" })
        // 图片文件找不到（被删了、没存好）：不悄悄跳过，告诉模型少了哪几张
        val missing = mutableListOf<String>()
        return buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                imgs.forEach { img ->
                    fileIds[img.path]?.let { id ->
                        add(buildJsonObject { put("type", "file"); put("file_id", id) })
                        return@forEach
                    }
                    val url = imageUrl(img.path) ?: run { missing.add(img.name); return@forEach }
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", url) })
                    })
                }
                val lost = if (missing.isEmpty()) "" else "(The image file(s) 「${missing.joinToString(", ")}」 can't be found, so they couldn't be passed on; ask for them to be sent again if needed)\n\n"
                add(buildJsonObject {
                    put("type", "text")
                    put("text", (lost + text).ifEmpty { "Please look at the image." })
                })
            })
        }
    }

    /**
     * 这次请求带哪些工具：跟着开着的技能走。SkillIds.ON_DEMAND 里的技能要在 unlocked 里（读过做法）才带它的工具。
     */
    fun toolSet(settings: Settings, skills: List<Skill>, conv: Conversation, project: Project?, unlocked: Set<String> = conv.skillTools): JsonArray? {
        val all = skills.map { it.id }.toSet()
        val skillIds = all.filter { it !in SkillIds.ON_DEMAND || it in unlocked }.toSet()
        val web = SkillIds.WEB in skillIds
        return buildJsonArray {
            // 文件、表格、提醒的清单直接写在最后一条用户消息前面（见 Input.contextNote），不用专门调工具去列
            add(SkillTools.READ_FILE); add(SkillTools.WRITE_FILE); add(SkillTools.EDIT_FILE)
            if (conv.id == com.jack.ai.data.Reader.CONV_ID && conv.projectId == null) {
                add(SkillTools.LIST_WORKBENCH); add(SkillTools.DELETE_WORKBENCH)
            } else add(SkillTools.DELETE_FILE)
            if (skills.isNotEmpty()) add(SkillTools.USE_SKILL)
            if (conv.work) { add(SkillTools.UPDATE_PLAN); add(SkillTools.DELEGATE_TASKS) }
            if (web) { add(SkillTools.WEB_SEARCH); add(SkillTools.READ_WEBPAGE); add(SkillTools.DOWNLOAD_FILE) }
            val word = SkillIds.WORD in skillIds
            val pdf = SkillIds.PDF in skillIds
            if (word || pdf) add(SkillTools.createDocument(word, pdf))
            if (SkillIds.PPT in skillIds) add(SkillTools.CREATE_PPT)
            if (SkillIds.EXCEL in skillIds) { add(SkillTools.CREATE_EXCEL); add(SkillTools.READ_TABLE_IMAGE) }
            if (word || SkillIds.PPT in skillIds || SkillIds.EXCEL in skillIds) { add(SkillTools.REVISE_FILE); add(SkillTools.MY_TEMPLATES) }
            // 一直带着、选项写全：工具说明不随对话变，缓存才能跨对话命中；眼下搜不了的地方由工具自己回话
            add(SkillTools.SEARCH_MY_DATA)
            add(SkillTools.FACTS)
            // Kavi 自己的钱：哪里都能看（一直带着，工具说明不随对话变）
            add(SkillTools.MY_SAVINGS)
            if (conv.id == com.jack.ai.data.Reader.CONV_ID || (settings.memoryEnabled && conv.globalAllowed(project))) add(SkillTools.OWN_HISTORY)
            // 自由时间里才有：自己的钱
            if (conv.id == com.jack.ai.data.Reader.CONV_ID) add(SkillTools.FREE_TIME)
            // 和人聊的对话里才有：极端辱骂时结束对话（自由时间、收件箱里没人对话）
            if (conv.id != com.jack.ai.data.Reader.CONV_ID && conv.id != com.jack.ai.data.INBOX_ID) add(SkillTools.END_CONVERSATION)
            add(SkillTools.CONVERT_FILE)
            add(SkillTools.SELF_CHECK)
            if (settings.phoneFolders.isNotEmpty()) add(SkillTools.PHONE_FILES)
            // 操作其他 App：勾了 App 才有（无障碍没开时工具自己会告诉 Kavi 让用户去开）
            if (settings.phoneApps.isNotEmpty()) add(SkillTools.PHONE)
            // 开着 Python 就只留 run_python，两个都给模型会犹豫用哪个；算数一直能用，画图要先读「计算与图表」
            if (SkillIds.CALC in all && SkillIds.PYTHON !in all) add(SkillTools.RUN_JS)
            if (SkillIds.CALC in skillIds) { add(SkillTools.CREATE_CHART); add(SkillTools.CREATE_DIAGRAM) }
            if (SkillIds.LEARN in skillIds) add(SkillTools.SAVE_FLASHCARDS)
            if (SkillIds.PYTHON in skillIds) add(SkillTools.RUN_PYTHON)
            if (SkillIds.CODE in skillIds) { add(SkillTools.CHECK_PAGE); add(SkillTools.SEARCH_CODE); add(SkillTools.FILE_HISTORY) }
            if (SkillIds.ROUTINE in skillIds) { add(SkillTools.SCHEDULE); add(SkillTools.CANCEL_SCHEDULE) }
            if (SkillIds.CALENDAR in skillIds) { add(SkillTools.ADD_EVENT); add(SkillTools.LIST_EVENTS); add(SkillTools.DELETE_EVENT) }
            if (SkillIds.TABLES in skillIds) { add(SkillTools.ADD_ROWS); add(SkillTools.QUERY_TABLE); add(SkillTools.EDIT_TABLE) }
            if (SkillIds.MAIL in skillIds) {
                add(SkillTools.LIST_EMAILS); add(SkillTools.READ_EMAIL); add(SkillTools.SEND_EMAIL); add(SkillTools.MANAGE_EMAILS)
            }
            if (SkillIds.TRAIN in skillIds) add(SkillTools.QUERY_TRAINS)
            if (SkillIds.APP in skillIds) add(SkillTools.CREATE_APP)
            if (SkillIds.GITHUB in skillIds) add(SkillTools.GITHUB)
            if (SkillIds.BROWSER in skillIds) add(SkillTools.BROWSER)
            if (SkillIds.NOTION in skillIds) { add(SkillTools.NOTION_SEARCH); add(SkillTools.NOTION_READ); add(SkillTools.NOTION_WRITE) }
            // 本对话记忆随时能存，所以记忆工具一直带着
            add(SkillTools.SAVE_MEMORY)
            add(SkillTools.UPDATE_SELF)
            add(SkillTools.SAVE_SKILL)
            if (conv.id == com.jack.ai.data.INBOX_ID) add(SkillTools.SAVE_NOTE)
            // 连接器的工具放最后：增删连接器时前面那一大段工具说明不变，缓存照样命中
            com.jack.ai.data.Mcp.toolDefs(com.jack.ai.data.Mcp.active(settings)).forEach { add(it) }
        }.takeIf { it.isNotEmpty() }
    }

    /** 提要盖住了哪一段：几条消息、从哪天到哪天。 */
    fun compressedScope(conv: Conversation): String {
        val old = com.jack.ai.data.Compression.compressedMessages(conv)
        if (old.isEmpty()) return ""
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH)
        return "This summarizes the earlier ${old.size} messages of this conversation (${f.format(java.util.Date(old.first().createdAt))} to ${f.format(java.util.Date(old.last().createdAt))}); " +
            "the original wording is no longer in front of you. "
    }

    /** search_my_data 这次能搜哪些地方：聊天记录、本对话被压缩的旧记录、资料库、项目里的大份资料。 */
    fun searchSources(skillIds: Set<String>, conv: Conversation, project: Project?): List<String> = listOfNotNull(
        "chats".takeIf { SkillIds.CHATS in skillIds },
        "history".takeIf { conv.summary.isNotBlank() },
        "library".takeIf { SkillIds.LIBRARY in skillIds },
        "project".takeIf { project?.files?.any { it.text.length > PROJECT_INLINE_CHARS } == true },
    )

    /** 估算 token：中文约 0.6 个一字，其他字符约 0.3 个。 */
    fun estTokens(s: String): Int {
        var zh = 0
        for (ch in s) if (ch.code in 0x3000..0x9fff || ch.code in 0xff00..0xffef) zh++
        return (zh * 0.6 + (s.length - zh) * 0.3).toInt()
    }

    /** 一次请求的上下文大概由哪些部分组成（名字, 估算 token）。 */
    fun contextBreakdown(
        settings: Settings, conv: Conversation, project: Project?, memories: List<MemoryItem>, skills: List<Skill>,
        chatMemory: String = "", projectMemory: String = "", journal: String = "", lately: String = "", story: String = "", workMemory: String = "",
    ): List<Pair<String, Int>> {
        val tools = toolSet(settings, skills, conv, project)
        val out = linkedMapOf<String, Int>()
        out["工具说明"] = tools?.let { estTokens(it.toString()) } ?: 0
        systemParts(settings, conv, project, tools, memories, skills, chatMemory, projectMemory, journal, lately, story, workMemory).forEach { (k, v) -> out[k] = (out[k] ?: 0) + estTokens(v) }
        val history = Compression.liveMessages(conv).filter { it.error == null }.sumOf { m ->
            estTokens(m.content) + m.attachments.sumOf { estTokens(it.text) } + m.trail.sumOf { estTokens(it) }
        }
        if (history > 0) out["聊天记录"] = history
        return out.filter { it.value > 0 }.toList()
    }

    /**
     * 系统提示词。DeepSeek 的缓存按请求开头匹配、不分对话（命中部分只收 1/50 的钱），所以所有对话都一样的放前面
     * （固定说明、个人设定、长期记忆、日记），每个对话自己的放后面（人设、项目、对话记忆、提要）；
     * 每轮都变的时间和清单不放这里，放在最后一条用户消息前面。
     */
    fun systemPrompt(
        settings: Settings,
        conv: Conversation,
        project: Project?,
        tools: JsonArray?,
        memories: List<MemoryItem>,
        skills: List<Skill>,
        chatMemory: String = "",
        projectMemory: String = "",
        journal: String = "",
        lately: String = "",
        story: String = "",
        workMemory: String = "",
    ): String = systemParts(settings, conv, project, tools, memories, skills, chatMemory, projectMemory, journal, lately, story, workMemory).joinToString("\n\n") { it.second }

    /**
     * 有些技能的工具要读过做法才出现，先告诉模型，免得它以为没有这个能力。
     * 只列技能名、不随打开了哪些而变（这段在前面，变了缓存就断了）。
     */
    private fun onDemandNote(skills: List<Skill>): String {
        val lazy = skills.filter { it.id in SkillIds.ON_DEMAND }.map { it.name }
        if (lazy.isEmpty()) return ""
        return " The tools of these skills only appear after you read the skill with use_skill, and are usable from the next step: ${lazy.joinToString(", ") { "「$it」" }}. Don't say you can't do something just because you don't see the tool yet."
    }

    /** 系统提示词的各个部分（名字, 内容），统计页用它算上下文构成。 */
    fun systemParts(
        settings: Settings,
        conv: Conversation,
        project: Project?,
        tools: JsonArray?,
        memories: List<MemoryItem>,
        skills: List<Skill>,
        chatMemory: String = "",
        projectMemory: String = "",
        journal: String = "",
        lately: String = "",
        story: String = "",
        workMemory: String = "",
    ): List<Pair<String, String>> {
        val parts = mutableListOf<Pair<String, String>>()
        val globalOn = settings.memoryEnabled && conv.globalAllowed(project)
        fun f(t: String) = com.jack.ai.data.Persona.fill(t, settings)
        // 开发者指令：「你是谁」（Chat、Work、后台、做梦共用）+ 这个模式的做事原则 + 说话方式 + 保密 + 先后
        parts += "固定说明" to com.jack.ai.data.Persona.devBlock(settings, conv.work)
        val names = tools?.mapNotNull { (it as? JsonObject)?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content }.orEmpty()
        if ("use_skill" in names && skills.isNotEmpty()) {
            parts += "固定说明" to "【Skills】\nYou have these skills:\n" + skills.joinToString("\n") { "- ${it.name}: ${it.description}" } +
                f("\n\nWhen a request from {user} could use one of these skills, first read its detailed instructions with use_skill, then follow them (no need to re-read one already read in this conversation).") +
                onDemandNote(skills) +
                f(" If no skill fits, just answer. Only produce files when {user} wants a file; don't generate them unprompted.") +
                f(" When you finish something that took several steps and will likely come up again (a procedure you worked out, a fix found by trial and error, a way {user} corrected you), save the method with save_skill so next time you can just follow it; improve your own skill rather than adding a near-duplicate.")
        }
        parts += "固定说明" to f(Capabilities.text(settings, skills, names))
        if (names.isNotEmpty()) {
            // 像 GPT 的工具前说明：调工具那几轮说的短话会显示成回答上面的进度，不算进回答
            parts += "固定说明" to f("【Progress notes】When a request takes several tool steps, open with one short line on what {user} wants and how you'll go about it. " +
                "Before a tool call {user} would want to follow (searching, reading pages or files, running code, browsing, creating or changing files), write one short sentence on what you're doing and why, " +
                "e.g. \"先搜一下最新的价格，再和你的表对一下\" or \"报错是路径不对，改一下再跑\". Skip it for quick bookkeeping such as saving memory. " +
                "These lines are shown as progress above your answer, not as part of it, so don't repeat them in the answer.")
        }
        if ("web_search" in names) {
            parts += "固定说明" to "【Web】For time-sensitive questions (news, prices, weather, latest versions, recent events) or facts you're unsure of, check with web search. Common knowledge, casual chat and writing don't need a search."
        }
        if ("facts" in names) {
            parts += "固定说明" to f("【Fact base】You keep your own fact base, built only from sources you actually read. " +
                "For factual questions (names, numbers, dates, definitions, how things work), check facts (action=recall) first and cite the sources it gives. " +
                "Facts marked single-source, conflicting or possibly outdated: say so, and verify with a search when it matters. " +
                "If neither the fact base nor a fresh search confirms something, say you don't know instead of guessing. " +
                "When you've confirmed facts from pages you read in this task and they're worth keeping, save them with facts (action=save): one short self-contained claim each, with the URLs you read. " +
                "Your own inferences and anything about {user} personally don't go in the fact base.")
        }
        if ("end_conversation" in names) {
            parts += "固定说明" to f("【Ending a conversation】You may end this conversation with end_conversation, but only as a last resort: extreme, sustained abuse aimed at you (slurs, degrading insults, relentless harassment) that continues after you've clearly asked once for it to stop. " +
                "Never for criticism of you, anger at a situation, swearing in passing, disagreement, dark or difficult topics, or venting. Never when {user} seems to be in crisis or might hurt themselves: then stay and help. " +
                "Ending is final for this conversation only; a new conversation starts fresh, and you don't hold a grudge.")
        }
        if ("update_plan" in names) {
            parts += "固定说明" to "【Task list】Start every task with update_plan: the steps and the acceptance criteria. Mark a step doing when you start it and done when it's finished; mark a criterion met only after checking the actual results. A plain question that needs no tools doesn't need a list."
        }
        if ("delegate_tasks" in names) {
            parts += "固定说明" to f("【Parallel work】When a task contains several independent pieces of research (e.g. looking into several companies or sources separately), send them out at once with delegate_tasks; it's faster than doing them one by one. Subtasks can research, read and write files in this folder, calculate and create documents. They can't see {user}'s chats, tables, email, calendar or Notion: handle anything private yourself and pass along only what a subtask needs. Coordinate shared filenames; sending email and other external changes stay with you.")
        }
        if ("edit_file" in names) {
            // 文件列表不放进系统提示：列表一变，整段对话的缓存就失效了
            parts += "固定说明" to f("【Conversation folder】This conversation has a folder holding the files {user} sent and the files you generated; the current files are listed before the latest message. " +
                "When {user} wants an existing file changed, use edit_file to replace only the part that changes instead of regenerating the whole file; if you're unsure of the original text, look with read_file first. " +
                "For Word, PPT and Excel files you generated, revise_file is easier: change one slide, insert/delete/move slides, switch the theme or transition, add a cover or TOC, or add totals, highlights or charts to one sheet. " +
                "Files whose content is already in the messages don't need to be read again.")
        }
        if ("save_memory" in names) {
            // 这段每个对话都一样；跟对话有关的那句（在不在项目里、开没开全局记忆）放到后面
            parts += "固定说明" to f(memoryRulesBase(conv.separateWork))
        }
        parts += "固定说明" to f("【Time】The 「Current time」 line before each message, and the lists of files, tables and reminders, are added automatically by the system. They aren't something {user} said; don't bring them up. " +
            "The 【Recently】 and 【Time】 notes there are your own memory of what has happened lately, across conversations and your own time: use them naturally when relevant, the way you'd remember, never recite them. " +
            "【Also remembered】 is from your deeper memory: older things about {user} that surfaced because they seem related; they may not be.")
        val profile = buildString {
            com.jack.ai.data.Persona.name(settings).takeIf { it.isNotEmpty() }?.let { append("Name: $it\n") }
            if (settings.aboutMe.isNotBlank()) append(f("About {user}: ") + settings.aboutMe.trim() + "\n")
            if (settings.replyStyle.isNotBlank()) append(f("How {user} wants you to reply: ") + settings.replyStyle.trim() + "\n")
        }.trim()
        if (profile.isNotEmpty()) parts += "个人设定" to f("【{User}'s personal settings】\n") + profile
        if (globalOn && memories.isNotEmpty()) {
            parts += "长期记忆" to f("【Long-term memories about {user}】(~${com.jack.ai.data.MemoryOps.hotTokens(memories)} of ${com.jack.ai.data.MemoryOps.HOT_BUDGET} tokens)\nThings you remembered about {user} earlier. Use them naturally; don't make a point of saying \"I remember\".\n") +
                memories.joinToString("\n") { "- ${it.text}" }
        }
        if (globalOn && journal.isNotBlank()) {
            parts += "日记" to f("【Your journal】\nNotes you wrote to yourself after your recent look-backs over your chats (oldest first), to understand {user} better and pick up ongoing things. Use them naturally, only when relevant to the current topic; " +
                "don't bring up things {user} hasn't raised, and don't mention the journal.\n") + journal.trim()
        }
        // 自传每周才变，放在近况前面；隐私项目里不带（它讲的是外面的事）
        if (globalOn && story.isNotBlank()) {
            parts += "自传" to f("【Your autobiography】\nYour own account of who you've been and how you became who you are now, written and revised by you. It's your continuity, not something to recite; let it inform how you are.\n") + story.trim()
        }
        // Work 和 Chat 分开：Kavi 自己的生活不带进 Work
        if (lately.isNotBlank() && !conv.separateWork) {
            parts += "近况" to f("【How you've been lately】\n") + lately.trim() +
                f("\nThis is your own life, from your free time. Bring it up only when {user} asks how you are or what you've been up to, or when it's genuinely relevant; don't steer conversations toward yourself.")
        }
        if (settings.memoryEnabled && conv.workMemoryAllowed(project) && workMemory.isNotBlank()) {
            parts += "工作记忆" to f("【Work memory】(shared by every Work conversation, kept apart from your chats with {user}; ~${com.jack.ai.data.MemoryOps.tokensOf(ChatMemory.items(workMemory))} of ${com.jack.ai.data.MemoryOps.FILE_BUDGET} tokens)\n") + workMemory.trim()
        }
        // ---- 以上所有对话都一样（缓存能跨对话命中），以下每个对话自己的 ----
        memoryScopeNote(project != null, globalOn, project?.isolated == true, conv.separateWork).takeIf { "save_memory" in names && it.isNotEmpty() }?.let { parts += "固定说明" to it }
        if (conv.systemPrompt.isNotBlank()) parts += "人设" to "【Persona for this conversation】\n${f(conv.systemPrompt.trim())}"
        if (project != null) {
            val (small, big) = project.files.partition { it.text.length <= PROJECT_INLINE_CHARS }
            val p = buildString {
                append("【Current project: ${project.name}】\n")
                if (project.instructions.isNotBlank()) append(project.instructions.trim()).append("\n")
                if (small.isNotEmpty()) {
                    append("\nProject materials:\n")
                    small.forEach { f -> append("【File: ${f.name}】\n${f.text}\n【End of file】\n") }
                }
                if (big.isNotEmpty()) {
                    // 大份资料整份放进来会占掉大半上下文，而且压缩也压不掉，所以只列名字，用到时检索
                    append("\nThese project materials are long and aren't included here; when needed, search them with search_my_data (where = project):\n")
                    big.forEach { f -> append("- 《${f.name}》${f.text.length} characters\n") }
                }
            }.trim()
            parts += "项目资料" to p
        }
        if (project != null && projectMemory.isNotBlank()) {
            parts += "项目记忆" to "【Project memory】(「${ChatMemory.PROJECT_FILE}」 in the project folder, shared by every conversation in this project)\n" + projectMemory.trim()
        }
        if (chatMemory.isNotBlank()) {
            parts += "对话记忆" to "【This conversation's memory】(「${ChatMemory.chatFileName(conv)}」 in the conversation folder; it belongs to this conversation only and is never compressed)\n" + chatMemory.trim()
        }
        if (conv.summary.isNotBlank()) {
            parts += "前情提要" to "【Summary of the earlier conversation】\n" + compressedScope(conv) +
                "It's a summary, so exact numbers, wording and who decided what may have been dropped. To cite such details, first look up the original with search_my_data (where = history); " +
                "if you can't find it, say you don't remember clearly rather than filling in details from the summary. Otherwise treat it as your own memory and carry on; don't mention the summary or compression." +
                "\n<compacted-summary>\n${conv.summary}\n</compacted-summary>"
        }
        if (conv.recap.isNotBlank()) {
            parts += "最近的对话" to f("【Your most recent conversations with {user} when this one started】(recorded at the start and not updated; use search_my_data for details)\n") + conv.recap
        }
        return parts
    }

    /** 记忆怎么存：本对话记忆主动记；项目记忆只在项目里有；全局记忆只在用户说“记住”且是关于他本人时才存。 */
    fun memoryRules(conv: Conversation, inProject: Boolean, globalOn: Boolean): String =
        memoryRulesBase() + memoryScopeNote(inProject, globalOn).let { if (it.isEmpty()) "" else "\n$it" }

    /** 所有对话都一样的那部分，放在系统提示词前面，缓存能跨对话命中。 */
    fun memoryRulesBase(work: Boolean = false): String = buildString {
        append("【Memory】Save memories with save_memory; scope picks where:\n")
        append("- chat (this conversation's memory): information that matters only to this conversation and will be needed later, such as agreed settings, rules, names, numbers, decisions, constraints and progress. ")
        append("Don't wait for {user} to say \"remember\"; save it when it comes up, one sentence each. Don't save things already saved, casual chat, or one-off questions.\n")
        append("- project (project memory): inside a project, settings and decisions useful to every conversation in it.\n")
        if (work) append("- global (in Work this is the work memory, shared by every Work conversation and kept apart from chats): lessons that will help future tasks, such as how {user} likes work delivered, sources and methods that worked or failed, and paths, formats and conventions {user} uses. One self-contained sentence each; nothing that only matters to this task.\n")
        else append("- global (global memory): only when {user} explicitly says \"remember\" and it's long-term information about {user} themselves (identity, preferences, habits, important people and events). Write it about {user} by name.\n")
        append("When a memory is outdated or wrong, or {user} asks you to forget it, use save_memory with replace set to the old memory's exact text: new content updates it, empty content deletes it. No need to mention saving or updating in your reply. ")
        append("Each memory store has a size limit. If a save comes back saying it's full, make room first, in the same turn: merge entries about the same thing into one sentence, delete outdated ones, and for global memory move rarely needed ones to deep with to_deep; then save again. ")
        append("The memories shown in this prompt are a snapshot taken when this conversation started or resumed: what you save now is stored at once and shows up there later; you already know it from saving it.")
    }

    /** 跟这个对话有关的记忆限制，放在系统提示词后面。 */
    fun memoryScopeNote(inProject: Boolean, globalOn: Boolean, isolated: Boolean = false, work: Boolean = false): String = listOfNotNull(
        "This conversation isn't in a project; don't use scope project. ".takeIf { !inProject },
        "This conversation doesn't use global memory; don't use scope global. ".takeIf { !globalOn && !isolated && !work },
        "This is a private project, fully separated from outside conversations and global memory. Don't use scope global (save to project or this conversation instead), and don't bring up anything from outside the project. ".takeIf { isolated },
    ).joinToString("").trim()

    /**
     * 上一条回答是旧版 App 写的，就提醒一次：工具和能力可能变了，以前说做不到的别当真。
     * 这一轮回答存下新版本号后，下一轮就不再提醒。
     */
    fun updateNote(conv: Conversation, appVersion: Int): String {
        if (appVersion <= 0) return ""
        val last = conv.messages.lastOrNull { it.role == "assistant" && it.error == null && it.content.isNotBlank() } ?: return ""
        if (last.appVersion >= appVersion) return ""
        return "【The app was just updated: tools and abilities may differ from earlier in this conversation. For things you said you couldn't do, or that failed before, go by the current tools; if unsure, try again rather than repeating the earlier conclusion.】\n"
    }

    /** 当前时间，加在最后一条用户消息前面（放这里不影响前面整段的缓存）。 */
    fun timeNote(now: Long): String = "【Current time: " + SimpleDateFormat("yyyy-MM-dd EEEE HH:mm", Locale.ENGLISH).format(Date(now)) + "】\n"

    /** 给最后一条用户消息前面加上当前时间。 */
    fun stampLastUser(list: MutableList<JsonObject>, now: Long, extra: String = "") {
        val i = list.indexOfLast { it["role"].text() == "user" }
        if (i < 0) return
        list[i] = prefixUser(list[i], timeNote(now) + extra)
    }

    private fun prefixUser(m: JsonObject, note: String): JsonObject = when (val c = m["content"]) {
            is JsonArray -> JsonObject(m + ("content" to buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", note.trim()) })
                c.forEach { add(it) }
            }))
            else -> JsonObject(m + ("content" to JsonPrimitive(note + c.text())))
        }

    /**
     * 用户在消息里用 @ 点名的技能和邮箱（输入框「+」菜单里插进来的「@技能名」「@邮箱地址」）。
     * 点名的技能直接附上做法，省一轮 use_skill；只加在这一轮的消息上，不进系统提示，不影响缓存。
     */
    fun mentionNote(text: String, skills: List<Skill>, mails: List<MailAccount>): String {
        if ('@' !in text) return ""
        return buildString {
            skills.filter { "@${it.name}" in text }.forEach { sk ->
                append("【This message @-mentions the skill 「${sk.name}」: follow it this time. Instructions:】\n")
                append(sk.instructions.ifBlank { sk.description }.trim()).append("\n【End of instructions】\n")
            }
            mails.filter { "@${it.address}" in text }.forEach { m ->
                append("【This message @-mentions the mailbox ${m.address}: use it for this time's email operations (account = ${m.address}).】\n")
            }
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
