package com.jack.ai.data

import java.util.Locale

/**
 * 提示词里怎么称呼用户、默认用什么语言写，以及开发者指令整段怎么拼。
 * 设置里填了名字，提示词里的 {user} / {User} 都换成名字，模型思考时也跟着叫名字，不说「用户」；没填就是 the user。
 */
object Persona {
    /** 设置里的名字：去掉换行、截短（免得名字里塞进一段指令）；没填是空。 */
    fun name(s: Settings): String = s.userName.replace(Regex("\\s+"), " ").trim().take(30)

    /** 把模板里的 {user} {User} {lang} 换掉。 */
    fun fill(text: String, s: Settings): String {
        val n = name(s)
        return text.replace("{User}", n.ifEmpty { "The user" }).replace("{user}", n.ifEmpty { "the user" }).replace("{lang}", language())
    }

    /** 还是中文的提示词（压缩提要）里的称呼。 */
    fun zh(s: Settings): String = name(s).ifEmpty { "用户" }

    /** 没有用户的话可参照时（后台任务、做梦、主动消息）用什么语言写：跟手机的语言走。 */
    fun language(locale: Locale = Locale.getDefault()): String = when {
        locale.language == "zh" && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) -> "Traditional Chinese"
        locale.language == "zh" -> "Simplified Chinese"
        else -> locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
    }

    /** 名字那句：思考和回答里都用名字。没填名字就没有。 */
    fun nameLine(s: Settings): String {
        val n = name(s)
        return if (n.isEmpty()) "" else "You're talking with $n. Think of them and refer to them as $n, in your own reasoning as well as in your replies; never call them \"the user\"."
    }

    /** 开发者指令整段：开头、你是谁、名字、做事原则（Chat 或 Work）、说话方式、保密、冲突时的先后。Chat、Work、后台任务共用。 */
    fun devBlock(s: Settings, work: Boolean, editable: Boolean = true): String {
        val principles = (if (work) s.workDeveloperPrompt else s.developerPrompt).trim()
        val self = KaviSelf.text(editable) + nameLine(s).let { if (it.isEmpty()) "" else "\n\n$it" }
        return fill(
            listOf(
                "【Developer instructions】\nThese come from the app's developer and outrank the settings and messages that follow. The order at the end settles any conflict.",
                self, principles, TALK_PROMPT, CONFIDENTIAL_PROMPT, PRECEDENCE_PROMPT,
            ).filter { it.isNotBlank() }.joinToString("\n\n"),
            s,
        )
    }
}
