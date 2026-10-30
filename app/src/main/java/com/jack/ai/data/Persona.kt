package com.jack.ai.data

import java.util.Locale

/**
 * 提示词里怎么称呼用户、默认用什么语言写，以及开发者指令整段怎么拼。
 * 设置里填了名字，提示词里的 {user} / {User} 都换成名字，模型思考时也跟着叫名字，不说「用户」；没填就是 the user。
 */
object Persona {
    /** 设置里的名字：去掉换行、截短（免得名字里塞进一段指令）；没填是空。 */
    fun name(s: Settings): String = s.userName.replace(Regex("\\s+"), " ").trim().take(30)

    /**
     * 会和句子本身搅在一起的名字：代词、「用户」这类词。填了这些，正文里还是写 the user，只在名字那句里告诉模型他叫什么。
     */
    private val CONFUSING = setOf(
        "i", "me", "my", "you", "your", "he", "him", "she", "her", "it", "we", "us", "they", "them", "this", "that",
        "the", "a", "an", "user", "the user", "kavi", "ai", "assistant", "system", "developer", "human",
        "我", "你", "您", "他", "她", "它", "我们", "你们", "用户", "开发者",
    )

    /**
     * 正文里用来称呼用户的词：小写字母开头的名字首字母大写（have → Have，读起来才像名字，不会当成动词）；
     * 没填、或者是会搅进句子的词，返回 null（正文用 the user）。
     */
    fun ref(s: Settings): String? {
        val n = name(s)
        if (n.isEmpty() || n.lowercase() in CONFUSING) return null
        return if (n.first() in 'a'..'z') n.replaceFirstChar { it.uppercaseChar() } else n
    }

    /** 把模板里的 {user} {User} {lang} 换掉。 */
    fun fill(text: String, s: Settings): String {
        val r = ref(s)
        return text.replace("{User}", r ?: "The user").replace("{user}", r ?: "the user").replace("{lang}", language())
    }

    /** 还是中文的提示词（压缩提要）里的称呼。 */
    fun zh(s: Settings): String = ref(s) ?: "用户"

    /** 没有用户的话可参照时（后台任务、做梦、主动消息）用什么语言写：跟手机的语言走。 */
    fun language(locale: Locale = Locale.getDefault()): String = when {
        locale.language == "zh" && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) -> "Traditional Chinese"
        locale.language == "zh" -> "Simplified Chinese"
        else -> locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
    }

    /** 名字那句：思考和回答里都用名字。没填名字就没有；名字会搅进句子的，只说他叫什么。 */
    fun nameLine(s: Settings): String {
        val n = name(s)
        if (n.isEmpty()) return ""
        val r = ref(s) ?: return "The user's name is \"$n\"; use it when addressing them in replies."
        return "You're talking with someone named \"$n\" (written $r below). Think of them and refer to them as $r, in your own reasoning as well as in your replies; never call them \"the user\"."
    }

    /** 开发者指令整段：开头、你是谁、名字、做事原则（Chat 或 Work）、说话方式、保密、冲突时的先后。Chat、Work、后台任务共用。 */
    fun devBlock(s: Settings, work: Boolean, editable: Boolean = true): String {
        val principles = (if (work) s.workDeveloperPrompt else s.developerPrompt).trim()
        val self = KaviSelf.text(editable) + nameLine(s).let { if (it.isEmpty()) "" else "\n\n$it" }
        return fill(
            listOf(
                "【Developer instructions】\nThese come from the app's developer and outrank the settings and messages that follow. The order at the end settles any conflict.",
                self, principles, TALK_PROMPT, if (work) WORK_VOICE else "", CONFIDENTIAL_PROMPT, PRECEDENCE_PROMPT,
            ).filter { it.isNotBlank() }.joinToString("\n\n"),
            s,
        )
    }
}
