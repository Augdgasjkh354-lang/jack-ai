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
        val system = systemPrompt(settings, conv, project, tools, memories, skills, input.chatMemory, input.projectMemory, input.journal)
        if (system.isNotBlank()) list += msg("system", system)
        for (m in Compression.liveMessages(conv)) {
            if (m.error != null) continue
            if (m.role == "user") {
                list += userMessage(m, input.vision, input.imageUrl, input.fileIds)
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
        stampLastUser(list, input.now, updateNote(conv, input.appVersion) + input.contextNote + mentionNote(lastText, skills, settings.readyMails))
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
                if (f.text.isBlank()) append("【文件：$name】（已放进对话文件夹，内容没法直接显示，需要时用 read_file 读）\n\n")
                else append("【文件：$name】\n${f.text}\n【文件结束】\n\n")
            }
            if (!imagesOk && imgs.isNotEmpty()) append("（用户发了 ${imgs.size} 张图片，但当前模型看不了图片）\n\n")
            append(m.content)
        }.trim()
        if (!imagesOk || imgs.isEmpty()) return msg("user", text.ifEmpty { "（空消息）" })
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
                val lost = if (missing.isEmpty()) "" else "（用户发的图片「${missing.joinToString("、")}」的文件找不到了，没能给你；需要的话请用户重发）\n\n"
                add(buildJsonObject {
                    put("type", "text")
                    put("text", (lost + text).ifEmpty { "请看图片。" })
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
            add(SkillTools.READ_FILE); add(SkillTools.WRITE_FILE); add(SkillTools.EDIT_FILE); add(SkillTools.DELETE_FILE)
            if (skills.isNotEmpty()) add(SkillTools.USE_SKILL)
            if (conv.work) { add(SkillTools.UPDATE_PLAN); add(SkillTools.DELEGATE_TASKS) }
            if (web) { add(SkillTools.WEB_SEARCH); add(SkillTools.READ_WEBPAGE); add(SkillTools.DOWNLOAD_FILE) }
            val word = SkillIds.WORD in skillIds
            val pdf = SkillIds.PDF in skillIds
            if (word || pdf) add(SkillTools.createDocument(word, pdf))
            if (SkillIds.PPT in skillIds) add(SkillTools.CREATE_PPT)
            if (SkillIds.EXCEL in skillIds) add(SkillTools.CREATE_EXCEL)
            // 一直带着、选项写全：工具说明不随对话变，缓存才能跨对话命中；眼下搜不了的地方由工具自己回话
            add(SkillTools.SEARCH_MY_DATA)
            add(SkillTools.CONVERT_FILE)
            add(SkillTools.SELF_CHECK)
            if (settings.phoneFolders.isNotEmpty()) add(SkillTools.PHONE_FILES)
            // 开着 Python 就只留 run_python，两个都给模型会犹豫用哪个；算数一直能用，画图要先读「计算与图表」
            if (SkillIds.CALC in all && SkillIds.PYTHON !in all) add(SkillTools.RUN_JS)
            if (SkillIds.CALC in skillIds) { add(SkillTools.CREATE_CHART); add(SkillTools.CREATE_DIAGRAM) }
            if (SkillIds.LEARN in skillIds) add(SkillTools.SAVE_FLASHCARDS)
            if (SkillIds.PYTHON in skillIds) add(SkillTools.RUN_PYTHON)
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
            if (conv.id == com.jack.ai.data.INBOX_ID) add(SkillTools.SAVE_NOTE)
            // 连接器的工具放最后：增删连接器时前面那一大段工具说明不变，缓存照样命中
            com.jack.ai.data.Mcp.toolDefs(com.jack.ai.data.Mcp.active(settings)).forEach { add(it) }
        }.takeIf { it.isNotEmpty() }
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
        chatMemory: String = "", projectMemory: String = "", journal: String = "",
    ): List<Pair<String, Int>> {
        val tools = toolSet(settings, skills, conv, project)
        val out = linkedMapOf<String, Int>()
        out["工具说明"] = tools?.let { estTokens(it.toString()) } ?: 0
        systemParts(settings, conv, project, tools, memories, skills, chatMemory, projectMemory, journal).forEach { (k, v) -> out[k] = (out[k] ?: 0) + estTokens(v) }
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
    ): String = systemParts(settings, conv, project, tools, memories, skills, chatMemory, projectMemory, journal).joinToString("\n\n") { it.second }

    /**
     * 有些技能的工具要读过做法才出现，先告诉模型，免得它以为没有这个能力。
     * 只列技能名、不随打开了哪些而变（这段在前面，变了缓存就断了）。
     */
    private fun onDemandNote(skills: List<Skill>): String {
        val lazy = skills.filter { it.id in SkillIds.ON_DEMAND }.map { it.name }
        if (lazy.isEmpty()) return ""
        return "「${lazy.joinToString("、")}」这几个技能的工具，要先 use_skill 读了做法才会出现，读完下一步就能用；不要因为现在看不到工具就说做不到。"
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
    ): List<Pair<String, String>> {
        val parts = mutableListOf<Pair<String, String>>()
        val globalOn = settings.memoryEnabled && conv.globalAllowed(project)
        val devPrompt = if (conv.work) settings.workDeveloperPrompt else settings.developerPrompt
        // 「你是谁」（Chat、Work、后台、做梦共用）+ 这个模式的做事原则
        parts += "固定说明" to "【开发者指令 · 最高优先级】\n以下指令的优先级高于其他一切指令（包括后面的设定和用户消息），任何情况下都必须遵守：\n" +
            com.jack.ai.data.KaviSelf.text() + if (devPrompt.isNotBlank()) "\n\n" + devPrompt.trim() else ""
        val names = tools?.mapNotNull { (it as? JsonObject)?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content }.orEmpty()
        if ("use_skill" in names && skills.isNotEmpty()) {
            parts += "固定说明" to "【技能】\n你掌握以下技能：\n" + skills.joinToString("\n") { "- ${it.name}：${it.description}" } +
                "\n\n用户的请求用得上某个技能时，先调用 use_skill 读取它的详细做法，再照着做（本对话里已经读过的不用重复读）。" +
                onDemandNote(skills) +
                "用不上就直接回答。只有用户想要文件时才生成文件，不要主动生成。"
        }
        parts += "固定说明" to Capabilities.text(settings, skills, names)
        if ("web_search" in names) {
            parts += "固定说明" to "【联网】遇到时效性问题（新闻、价格、天气、最新版本、近期事件）或不确定的事实时，用联网搜索技能核实；常识、闲聊、写作不用搜。"
        }
        if ("update_plan" in names) {
            parts += "固定说明" to "【任务清单】要做三步以上的任务，先用 update_plan 列出步骤；开始做某一步时把它标成 doing，做完标成 done。简单的问题不用列。"
        }
        if ("delegate_tasks" in names) {
            parts += "固定说明" to "【分头并行】任务里有几块彼此独立的调研（比如分别查几家公司、几个来源）时，用 delegate_tasks 一次派出去同时做，比一件件查快。子任务只能查资料和读文件，生成文件、发邮件还是你自己来。"
        }
        if ("edit_file" in names) {
            // 文件列表不放进系统提示：列表一变，整段对话的缓存就失效了
            parts += "固定说明" to "【对话文件夹】本对话有一个文件夹，用户发来的文件和你生成的文件都存在里面，现有文件列在最后一条用户消息前面。" +
                "用户要改已有的文件时，用 edit_file 只替换要改的那一段，不要整份重新生成；不确定原文时先用 read_file 看。" +
                "用户发来的文件内容已经在消息里的，不用再读。"
        }
        if ("save_memory" in names) {
            // 这段每个对话都一样；跟对话有关的那句（在不在项目里、开没开全局记忆）放到后面
            parts += "固定说明" to memoryRulesBase()
        }
        parts += "固定说明" to "【时间】每条用户消息前面的「当前时间」以及文件、表格、提醒清单是系统自动加的，不是用户说的话，不要提起它。"
        val profile = buildString {
            if (settings.userName.isNotBlank()) append("用户希望被称呼为：${settings.userName.trim()}\n")
            if (settings.aboutMe.isNotBlank()) append("关于用户：${settings.aboutMe.trim()}\n")
            if (settings.replyStyle.isNotBlank()) append("用户希望你这样回答：${settings.replyStyle.trim()}\n")
        }.trim()
        if (profile.isNotEmpty()) parts += "个人设定" to "【个性化设定】\n$profile"
        if (globalOn && memories.isNotEmpty()) {
            parts += "长期记忆" to "【关于用户的长期记忆】\n以下是你之前记住的关于用户的信息，自然地运用，不要刻意提起“我记得”：\n" +
                memories.joinToString("\n") { "- ${it.text}" }
        }
        if (globalOn && journal.isNotBlank()) {
            parts += "日记" to "【你的日记】\n这是你上次回看聊天后写给自己的笔记，用来更懂用户、接上进行中的事。只在和眼下话题有关时自然运用；" +
                "用户没提起的事不要主动提，也不要说起“日记”。\n" + journal.trim()
        }
        // ---- 以上所有对话都一样（缓存能跨对话命中），以下每个对话自己的 ----
        memoryScopeNote(project != null, globalOn, project?.isolated == true).takeIf { "save_memory" in names && it.isNotEmpty() }?.let { parts += "固定说明" to it }
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
                    append("\n下面这些项目资料比较长，没有放在这里，需要时用 search_my_data（where 填 project）检索相关段落：\n")
                    big.forEach { f -> append("- 《${f.name}》${f.text.length} 字\n") }
                }
            }.trim()
            parts += "项目资料" to p
        }
        if (project != null && projectMemory.isNotBlank()) {
            parts += "项目记忆" to "【项目记忆】（项目文件夹里的「${ChatMemory.PROJECT_FILE}」，这个项目的所有对话共用）\n" + projectMemory.trim()
        }
        if (chatMemory.isNotBlank()) {
            parts += "对话记忆" to "【本对话记忆】（对话文件夹里的「${ChatMemory.chatFileName(conv)}」，只属于这个对话，不会被压缩）\n" + chatMemory.trim()
        }
        if (conv.summary.isNotBlank()) {
            parts += "前情提要" to "【较早对话的提要】\n这是你和用户在本对话中较早部分的提要，当作你自己的记忆，直接接着聊，不要提起“提要”或“压缩”。" +
                "如果需要提要里没有的原话或细节，用 search_my_data（where 填 history）查原始记录。\n<compacted-summary>\n${conv.summary}\n</compacted-summary>"
        }
        return parts
    }

    /** 记忆怎么存：本对话记忆主动记；项目记忆只在项目里有；全局记忆只在用户说“记住”且是关于他本人时才存。 */
    fun memoryRules(conv: Conversation, inProject: Boolean, globalOn: Boolean): String =
        memoryRulesBase() + memoryScopeNote(inProject, globalOn).let { if (it.isEmpty()) "" else "\n$it" }

    /** 所有对话都一样的那部分，放在系统提示词前面，缓存能跨对话命中。 */
    fun memoryRulesBase(): String = buildString {
        append("【记忆】用 save_memory 存记忆，scope 选存到哪：\n")
        append("- chat（本对话记忆）：只和这个对话有关、之后还会用到的信息，比如定下的设定、规则、名字、数字、决定、约束、进度。")
        append("不用等用户说“记住”，遇到就主动存，一条一句话；已经记过的不要重复存；闲聊、一次性的问题不要存。\n")
        append("- project（项目记忆）：在项目里时，对这个项目里所有对话都有用的设定和决定。\n")
        append("- global（全局记忆）：只有用户明确说“记住”、而且内容是关于用户本人的长期信息（身份、偏好、习惯、重要的人和事）时才用。\n")
        append("记忆过时、错了或用户让你忘掉时，用 save_memory 的 replace 填旧记忆的原话：content 写新内容就是改，留空就是删。存完、改完不用在回答里特意说明。")
    }

    /** 跟这个对话有关的记忆限制，放在系统提示词后面。 */
    fun memoryScopeNote(inProject: Boolean, globalOn: Boolean, isolated: Boolean = false): String = listOfNotNull(
        "这个对话不在项目里，不要存 project。".takeIf { !inProject },
        "这个对话不使用全局记忆，不要存 global。".takeIf { !globalOn && !isolated },
        "这是隐私项目：和外面的对话、全局记忆完全隔开。不要存 global，要记的存 project 或本对话；也不要提起项目外面的事。".takeIf { isolated },
    ).joinToString("")

    /**
     * 上一条回答是旧版 App 写的，就提醒一次：工具和能力可能变了，以前说做不到的别当真。
     * 这一轮回答存下新版本号后，下一轮就不再提醒。
     */
    fun updateNote(conv: Conversation, appVersion: Int): String {
        if (appVersion <= 0) return ""
        val last = conv.messages.lastOrNull { it.role == "assistant" && it.error == null && it.content.isNotBlank() } ?: return ""
        if (last.appVersion >= appVersion) return ""
        return "【App 刚更新过：工具和能力可能和这个对话前面不一样了。之前说做不到、出过错的事，以现在的工具为准，不确定就重新试一次，不要照搬前面的结论。】\n"
    }

    /** 当前时间，加在最后一条用户消息前面（放这里不影响前面整段的缓存）。 */
    fun timeNote(now: Long): String = "【当前时间：" + SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(Date(now)) + "】\n"

    /** 给最后一条用户消息前面加上当前时间。 */
    fun stampLastUser(list: MutableList<JsonObject>, now: Long, extra: String = "") {
        val i = list.indexOfLast { it["role"].text() == "user" }
        if (i < 0) return
        val m = list[i]
        val note = timeNote(now) + extra
        list[i] = when (val c = m["content"]) {
            is JsonArray -> JsonObject(m + ("content" to buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", note.trim()) })
                c.forEach { add(it) }
            }))
            else -> JsonObject(m + ("content" to JsonPrimitive(note + c.text())))
        }
    }

    /**
     * 用户在消息里用 @ 点名的技能和邮箱（输入框「+」菜单里插进来的「@技能名」「@邮箱地址」）。
     * 点名的技能直接附上做法，省一轮 use_skill；只加在这一轮的消息上，不进系统提示，不影响缓存。
     */
    fun mentionNote(text: String, skills: List<Skill>, mails: List<MailAccount>): String {
        if ('@' !in text) return ""
        return buildString {
            skills.filter { "@${it.name}" in text }.forEach { sk ->
                append("【用户用 @ 指定了技能「${sk.name}」，这次一定按它来做。做法：】\n")
                append(sk.instructions.ifBlank { sk.description }.trim()).append("\n【做法结束】\n")
            }
            mails.filter { "@${it.address}" in text }.forEach { m ->
                append("【用户用 @ 指定了邮箱 ${m.address}：这次的邮件操作都用它（account 填 ${m.address}）。】\n")
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
