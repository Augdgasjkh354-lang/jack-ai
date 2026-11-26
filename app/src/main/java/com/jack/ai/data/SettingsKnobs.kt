package com.jack.ai.data

/**
 * Kavi 能帮用户改的设置（settings 工具）：只放外观、习惯、开关这类，改坏了也就是用着不顺手，随时能改回来。
 * Key、令牌、代理、权限、应用锁、提示词这些一律不在这里，Kavi 读不到也改不了。
 * [ask] 的会多花钱或影响大（换模型、开付费朗读、改预算、关记忆），改之前先弹窗问用户。
 */
object SettingsKnobs {
    class Knob(
        val key: String,
        val label: String,
        /** 能填什么（给模型看）。 */
        val values: String,
        val ask: Boolean = false,
        val get: (Settings) -> String,
        /** 把字符串值写进设置；值不对抛 IllegalArgumentException，消息给模型看。 */
        val set: (Settings, String) -> Settings,
    )

    private fun bool(v: String): Boolean = when (v.trim().lowercase()) {
        "true", "on", "1", "开", "打开", "是" -> true
        "false", "off", "0", "关", "关闭", "否" -> false
        else -> throw IllegalArgumentException("要填 true 或 false")
    }

    private fun pick(v: String, options: Map<String, String>): String {
        val t = v.trim()
        return options.keys.firstOrNull { it.equals(t, true) } ?: options.entries.firstOrNull { it.value == t }?.key
            ?: throw IllegalArgumentException("只能填：" + options.entries.joinToString("、") { "${it.key}（${it.value}）" })
    }

    private fun num(v: String, range: ClosedFloatingPointRange<Double>): Double {
        val n = v.trim().toDoubleOrNull() ?: throw IllegalArgumentException("要填数字")
        if (n !in range) throw IllegalArgumentException("要在 ${range.start} 到 ${range.endInclusive} 之间")
        return n
    }

    private fun Settings.model(v: String, allowEmpty: Boolean): String {
        val t = v.trim()
        if (t.isEmpty() && allowEmpty) return ""
        val choices = modelChoices()
        return choices.firstOrNull { it.key == t }?.key ?: choices.firstOrNull { it.label.equals(t, true) }?.key
            ?: throw IllegalArgumentException("没有这个模型。能选的：" + (if (allowEmpty) "空（自动）、" else "") + choices.joinToString("、") { "${it.key}（${it.label}）" })
    }

    private fun modelName(s: Settings, key: String) = if (key.isBlank()) "自动" else s.modelChoices().firstOrNull { it.key == key }?.label ?: key

    private val ACCENTS = linkedMapOf("mono" to "黑白", "ocean" to "蓝", "pine" to "绿", "plum" to "紫", "clay" to "橙", "rose" to "红", "pink" to "粉", "cocoa" to "可可")
    private val TABS = linkedMapOf("library" to "资料库", "memory" to "记忆", "browser" to "浏览器", "routines" to "提醒和任务", "files" to "文件库",
        "skills" to "技能", "apps" to "小工具", "games" to "游戏", "review" to "复习卡片", "tables" to "表格", "downloads" to "下载")
    private val THINK = linkedMapOf("OFF" to "不思考", "LOW" to "思考·低", "HIGH" to "思考·高", "MAX" to "思考·最高")

    val ALL: List<Knob> = listOf(
        Knob("theme", "深色模式", "system（跟随系统）、light（浅色）、dark（深色）",
            get = { it.themeMode.name.lowercase() },
            set = { s, v -> s.copy(themeMode = ThemeMode.valueOf(pick(v, mapOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")).uppercase())) }),
        Knob("accent", "主题色", ACCENTS.entries.joinToString("、") { "${it.key}（${it.value}）" },
            get = { it.accent }, set = { s, v -> s.copy(accent = pick(v, ACCENTS)) }),
        Knob("font", "界面字体", "空（跟随系统）、serif（宋体）",
            get = { it.fontChoice }, set = { s, v -> s.copy(fontChoice = if (v.isBlank()) "" else pick(v, mapOf("serif" to "宋体"))) }),
        Knob("font_scale", "字号", "0.85～1.3，1 是默认",
            get = { "%.2f".format(it.fontScale) }, set = { s, v -> s.copy(fontScale = num(v, 0.85..1.3).toFloat()) }),
        Knob("bubble_accent", "自己的消息气泡用主题色", "true / false",
            get = { it.bubbleAccent.toString() }, set = { s, v -> s.copy(bubbleAccent = bool(v)) }),
        Knob("tab_bar", "底栏放哪几页（聊天固定在第一个）", "最多 4 个，逗号隔开：" + TABS.entries.joinToString("、") { "${it.key}（${it.value}）" },
            get = { (it.tabBar ?: listOf("library", "memory", "browser", "routines")).joinToString(",") },
            set = { s, v ->
                val ids = v.split(',', '，', '、', ' ').map { it.trim() }.filter { it.isNotEmpty() }.map { pick(it, TABS) }.distinct()
                if (ids.size > 4) throw IllegalArgumentException("最多 4 个")
                s.copy(tabBar = ids)
            }),
        Knob("model", "主模型（聊天用的）", "模型的 key 或名字", ask = true,
            get = { it.model }, set = { s, v -> s.copy(model = s.model(v, false)) }),
        Knob("thinking", "思考强度", THINK.entries.joinToString("、") { "${it.key}（${it.value}）" },
            get = { it.thinking.name }, set = { s, v -> s.copy(thinking = ThinkLevel.valueOf(pick(v, THINK))) }),
        Knob("background_model", "后台模型（夜间整理、压缩、复核、定时任务等）", "模型的 key 或名字，空是自动", ask = true,
            get = { it.backgroundModel }, set = { s, v -> s.copy(backgroundModel = s.model(v, true)) }),
        Knob("subagent_model", "子 agent 模型（分头做事时）", "模型的 key 或名字，空是自动", ask = true,
            get = { it.subagentModel }, set = { s, v -> s.copy(subagentModel = s.model(v, true)) }),
        Knob("user_name", "怎么称呼用户", "文字，空是不称呼",
            get = { it.userName }, set = { s, v -> s.copy(userName = v.trim().take(20)) }),
        Knob("reply_style", "回复风格（用户希望你怎么回）", "文字，最多 500 字",
            get = { it.replyStyle }, set = { s, v -> s.copy(replyStyle = v.trim().take(500)) }),
        Knob("web_search", "联网搜索", "true / false", get = { it.webSearch.toString() }, set = { s, v -> s.copy(webSearch = bool(v)) }),
        Knob("think_summary", "显示思考摘要", "true / false", get = { it.thinkSummary.toString() }, set = { s, v -> s.copy(thinkSummary = bool(v)) }),
        Knob("summary_collapsed", "回答出来后思考摘要折叠", "true / false", get = { it.summaryCollapsed.toString() }, set = { s, v -> s.copy(summaryCollapsed = bool(v)) }),
        Knob("start_suggestions", "新对话空白页的建议弹幕", "true / false", get = { it.bizStarts.toString() }, set = { s, v -> s.copy(bizStarts = bool(v)) }),
        Knob("memory", "记忆（记住用户的事）", "true / false", ask = true, get = { it.memoryEnabled.toString() }, set = { s, v -> s.copy(memoryEnabled = bool(v)) }),
        Knob("dream", "夜间整理记忆", "true / false", get = { it.dreamEnabled.toString() }, set = { s, v -> s.copy(dreamEnabled = bool(v)) }),
        Knob("chat_review", "聊天时顺手记", "true / false", get = { it.chatReview.toString() }, set = { s, v -> s.copy(chatReview = bool(v)) }),
        Knob("proactive", "Kavi 主动找你（早晚回看，有要紧的才推送）", "true / false", get = { it.proactive.toString() }, set = { s, v -> s.copy(proactive = bool(v)) }),
        Knob("work_review", "交付前复核", "true / false", get = { it.workReview.toString() }, set = { s, v -> s.copy(workReview = bool(v)) }),
        Knob("confirm_plan", "开工前先给用户确认清单", "always（每次）、big（大任务）、never（从不）",
            get = { it.workConfirmPlan }, set = { s, v -> s.copy(workConfirmPlan = pick(v, mapOf("always" to "每次", "big" to "大任务", "never" to "从不"))) }),
        Knob("task_cost_limit", "一次任务花到多少元先停下问（0 不限）", "0～100", ask = true,
            get = { it.workCostLimit.toString() }, set = { s, v -> s.copy(workCostLimit = num(v, 0.0..100.0)) }),
        Knob("monthly_budget", "每月预算（元，0 不设）", "0～10000", ask = true,
            get = { it.monthlyBudget.toString() }, set = { s, v -> s.copy(monthlyBudget = num(v, 0.0..10000.0)) }),
        Knob("quiet_hours", "定时任务免打扰时段", "「22-8」这样几点到几点，off 是不开",
            get = { if (it.quietStart < 0) "off" else "${it.quietStart}-${it.quietEnd}" },
            set = { s, v ->
                val t = v.trim()
                if (t.equals("off", true) || t == "关") s.copy(quietStart = -1) else {
                    val (a, b) = t.split('-', '～', '~', '到').map { it.trim().toIntOrNull() ?: throw IllegalArgumentException("写成「22-8」") }.let { it[0] to it[1] }
                    if (a !in 0..23 || b !in 0..23) throw IllegalArgumentException("几点要在 0～23 之间")
                    s.copy(quietStart = a, quietEnd = b)
                }
            }),
        Knob("voice_input", "话筒的语音输入", "kavi（Kavi 自己录音转文字）、system（手机系统的语音识别）",
            get = { it.voiceInput }, set = { s, v -> s.copy(voiceInput = pick(v, mapOf("kavi" to "Kavi 自己", "system" to "系统"))) }),
        Knob("ds_voice", "朗读用 DeepSeek 的语音（收费）", "true / false", ask = true,
            get = { it.dsVoice.toString() }, set = { s, v -> s.copy(dsVoice = bool(v)) }),
        Knob("ds_voice_id", "DeepSeek 朗读的声音", "stella（女）、tide（男）",
            get = { it.dsVoiceId }, set = { s, v -> s.copy(dsVoiceId = pick(v, mapOf("stella" to "女", "tide" to "男"))) }),
        Knob("links_in_app", "链接在 Kavi 里打开", "true / false", get = { it.linksInApp.toString() }, set = { s, v -> s.copy(linksInApp = bool(v)) }),
        Knob("auto_update", "自动检查新版本", "true / false", get = { it.autoUpdate.toString() }, set = { s, v -> s.copy(autoUpdate = bool(v)) }),
    )

    fun find(key: String): Knob? = ALL.firstOrNull { it.key.equals(key.trim(), true) || it.label == key.trim() }

    /** 给模型看的清单：key｜名字｜现在｜能填什么。 */
    fun list(s: Settings): String = ALL.joinToString("\n") { k ->
        val now = k.get(s).let { v -> if (k.key.endsWith("model")) modelName(s, v) + if (v.isNotBlank()) "（$v）" else "" else v.ifEmpty { "（空）" } }
        "${k.key}｜${k.label}｜现在：$now｜${k.values}" + if (k.ask) "｜改之前会问用户" else ""
    }

    /** 一个值给人看的样子（模型显示名字，不显示 key）。 */
    fun show(s: Settings, k: Knob): String {
        val v = k.get(s)
        return if (k.key.endsWith("model")) modelName(s, v) else v.ifEmpty { "空" }
    }
}
