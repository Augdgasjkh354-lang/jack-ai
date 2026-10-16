package com.jack.ai.data

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** 图片或文件附件。图片存成手机里的 jpg，文件只保存提取出的文字。 */
@Serializable
data class Attachment(
    val id: String = newId(),
    val kind: String, // "image" 或 "file"
    val name: String,
    val path: String = "",
    val text: String = "",
)

@Serializable
data class ChatMessage(
    val id: String = newId(),
    val role: String, // "user" 或 "assistant"
    val content: String = "",
    val reasoning: String = "",
    val model: String = "",
    val error: String? = null,
    val searches: List<SearchRecord> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    /** 这条回答过程中调用工具产生的中间消息（JSON），开着工具时要原样发回给接口。 */
    val trail: List<String> = emptyList(),
    /** AI 用 skill 生成的文件。 */
    val files: List<GeneratedFile> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SearchSource(val title: String, val url: String)

@Serializable
data class SearchRecord(
    val query: String,
    val sources: List<SearchSource> = emptyList(),
    val pending: Boolean = false,
    val error: String? = null,
)

@Serializable
data class Conversation(
    val id: String = newId(),
    val title: String = "新对话",
    val systemPrompt: String = "",
    val presetName: String = "",
    val projectId: String? = null,
    val pinned: Boolean = false,
    /** 这个对话带不带全局记忆；参不参与每晚的「做梦」整理。 */
    val useGlobalMemory: Boolean = true,
    val inDream: Boolean = true,
    val messages: List<ChatMessage> = emptyList(),
    /** 压缩后的前情提要，以及它覆盖到哪条消息（含）为止。 */
    val summary: String = "",
    val summaryUntilId: String? = null,
    /** 最近一次请求实际用掉的上下文 token 数（接口返回）。 */
    val lastPromptTokens: Int = 0,
    /** 这个对话累计用掉的 token。 */
    val spentTokens: Long = 0,
    /** 累计明细：请求次数、输入、其中缓存命中、输出、费用（元）。 */
    val requests: Int = 0,
    val spentPrompt: Long = 0,
    val spentHit: Long = 0,
    val spentCompletion: Long = 0,
    val spentCost: Double = 0.0,
    /** 有没有请求的价格不知道（自定义模型没填价）。 */
    val unpriced: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 项目：一组对话共享同一份说明和资料。 */
@Serializable
data class Project(
    val id: String = newId(),
    val name: String,
    val instructions: String = "",
    val files: List<Attachment> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class MemoryItem(
    val id: String = newId(),
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class DreamEntry(
    val id: String = newId(),
    val createdAt: Long = System.currentTimeMillis(),
    val dream: String,
    val summary: String = "",
    val changes: String = "",
    /** 醒来后想对用户说的一句话。 */
    val note: String = "",
    val seen: Boolean = false,
)

@Serializable
data class Preset(
    val id: String = newId(),
    val name: String,
    val prompt: String,
)

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/** DeepSeek 的思考等级：关闭，或 low / high / max。 */
enum class ThinkLevel(val label: String, val effort: String?) {
    OFF("不思考", null),
    LOW("思考·低", "low"),
    HIGH("思考·高", "high"),
    MAX("思考·最高", "max"),
}

data class Settings(
    val apiKey: String = "",
    val tavilyKey: String = "",
    val webSearch: Boolean = true,
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = MODEL_FLASH,
    val thinking: ThinkLevel = ThinkLevel.HIGH,
    val temperature: Float = 1.0f,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val presets: List<Preset> = DEFAULT_PRESETS,
    val developerPrompt: String = DEFAULT_DEV_PROMPT,
    val compressTokens: Int = 200_000,
    val memoryEnabled: Boolean = true,
    val dreamEnabled: Boolean = true,
    val userName: String = "",
    val aboutMe: String = "",
    val replyStyle: String = "",
    /** 关掉的内置 skill（联网搜索的开关仍然是 webSearch）。 */
    val disabledSkills: Set<String> = emptySet(),
    val customSkills: List<Skill> = emptyList(),
    /** 字体缩放、主题色、应用锁。 */
    val fontScale: Float = 1f,
    val accent: String = "clay",
    val appLock: Boolean = false,
    /** 用户自己加的 OpenAI 兼容服务（Kimi、通义、OpenRouter 等）。 */
    val providers: List<Provider> = emptyList(),
    /** 每月预算（元），0 表示不设。 */
    val monthlyBudget: Double = 0.0,
    /** 邮箱账号（IMAP 收信、SMTP 发信），没设置时为空。 */
    val mail: MailAccount? = null,
)

/** 邮箱账号。password 是邮箱的「授权码」，不是登录密码；不进备份。 */
@Serializable
data class MailAccount(
    val address: String = "",
    val password: String = "",
    val imapHost: String = "",
    val imapPort: Int = 993,
    val smtpHost: String = "",
    val smtpPort: Int = 465,
) {
    val ready: Boolean get() = address.isNotBlank() && password.isNotBlank() && imapHost.isNotBlank() && smtpHost.isNotBlank()
}

/** 常见邮箱的服务器：后缀 → (IMAP, SMTP, SMTP 端口)。 */
val MAIL_PRESETS = mapOf(
    "163.com" to Triple("imap.163.com", "smtp.163.com", 465),
    "126.com" to Triple("imap.126.com", "smtp.126.com", 465),
    "yeah.net" to Triple("imap.yeah.net", "smtp.yeah.net", 465),
    "qq.com" to Triple("imap.qq.com", "smtp.qq.com", 465),
    "foxmail.com" to Triple("imap.qq.com", "smtp.qq.com", 465),
    "gmail.com" to Triple("imap.gmail.com", "smtp.gmail.com", 465),
    "outlook.com" to Triple("outlook.office365.com", "smtp.office365.com", 587),
    "hotmail.com" to Triple("outlook.office365.com", "smtp.office365.com", 587),
)

/** 按邮箱地址的后缀填好服务器；认不出的后缀返回 null。 */
fun mailPresetFor(address: String): MailAccount? {
    val domain = address.substringAfter('@', "").trim().lowercase()
    val (imap, smtp, port) = MAIL_PRESETS[domain] ?: return null
    return MailAccount(address = address.trim(), imapHost = imap, smtpHost = smtp, smtpPort = port)
}

/** OpenAI 兼容的模型服务。 */
@Serializable
data class Provider(
    val id: String = newId(),
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val models: List<ProviderModel> = emptyList(),
)

/** vision：能看图；reasoning：支持 reasoning_effort（深度思考）。 */
@Serializable
data class ProviderModel(
    val id: String,
    val vision: Boolean = false,
    val reasoning: Boolean = false,
    /** 价格（元 / 百万 token），不知道时为空。 */
    val price: Price? = null,
)

/** 一次请求发到哪里、用什么模型。deepseek=true 时按 DeepSeek 的参数格式发。 */
data class Endpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val deepseek: Boolean,
    val vision: Boolean,
    val reasoning: Boolean,
    /** 记用量用的模型标识（自定义模型是 c:服务:模型）。 */
    val key: String = model,
    /** 自定义模型的价格；DeepSeek 的价格按时段另算。 */
    val price: Price? = null,
)

/** 自定义模型在设置里存成 "c:服务id:模型id"，内置 DeepSeek 模型直接存模型名。 */
fun customModelKey(providerId: String, modelId: String) = "c:$providerId:$modelId"

private fun parseCustomKey(key: String): Pair<String, String>? {
    if (!key.startsWith("c:")) return null
    val rest = key.substring(2)
    val i = rest.indexOf(':')
    return if (i <= 0) null else rest.substring(0, i) to rest.substring(i + 1)
}

fun Settings.endpoint(key: String = model): Endpoint? {
    val custom = parseCustomKey(key)
    if (custom == null) {
        return Endpoint(baseUrl, apiKey, key, deepseek = true, vision = key != MODEL_PRO, reasoning = true)
    }
    val p = providers.firstOrNull { it.id == custom.first } ?: return null
    val m = p.models.firstOrNull { it.id == custom.second } ?: ProviderModel(custom.second)
    return Endpoint(p.baseUrl, p.apiKey, m.id, deepseek = false, vision = m.vision, reasoning = m.reasoning, key = key, price = m.price)
}

/** 压缩、做梦、定时任务这类后台活：有 DeepSeek Key 就用 Flash（便宜），否则用当前选的模型。 */
fun Settings.backgroundEndpoint(): Endpoint? =
    if (apiKey.isNotBlank()) endpoint(MODEL_FLASH) else endpoint(model)?.takeIf { it.apiKey.isNotBlank() }

/** 至少配好了一个能用的服务。 */
fun Settings.hasAnyKey(): Boolean = apiKey.isNotBlank() || providers.any { it.apiKey.isNotBlank() && it.models.isNotEmpty() }

data class ModelChoice(val key: String, val label: String, val group: String, val desc: String)

/** 模型选择菜单里列出的全部模型：DeepSeek 两个（填了 Key 或者没加别的服务时）+ 每个服务里勾选的模型。 */
fun Settings.modelChoices(): List<ModelChoice> {
    val list = mutableListOf<ModelChoice>()
    if (apiKey.isNotBlank() || providers.none { it.models.isNotEmpty() }) {
        list += ModelChoice(MODEL_FLASH, modelLabel(MODEL_FLASH), "DeepSeek", "快、便宜，能看图片")
        list += ModelChoice(MODEL_PRO, modelLabel(MODEL_PRO), "DeepSeek", "更聪明，适合难题（不能看图）")
    }
    providers.forEach { p ->
        p.models.forEach { m ->
            val tags = listOfNotNull("能看图".takeIf { m.vision }, "能深度思考".takeIf { m.reasoning })
            list += ModelChoice(customModelKey(p.id, m.id), modelLabel(m.id), p.name, tags.joinToString("，"))
        }
    }
    return list
}

/** 按模型名猜它能不能看图、会不会深度思考，用户可以再改。 */
fun guessVision(id: String): Boolean {
    val s = id.lowercase()
    return listOf("vl", "vision", "4o", "gpt-4.1", "gpt-5", "gemini", "claude", "omni", "qvq", "glm-4v", "glm-4.5v",
        "pixtral", "llava", "llama-4", "kimi-k2.5", "kimi-latest", "moonshot-v1-8k-vision", "grok-4", "mistral-medium").any { it in s }
}

fun guessReasoning(id: String): Boolean {
    val s = id.lowercase()
    return Regex("""(^|[/\-_.])(o1|o3|o4)([\-_.]|$)""").containsMatchIn(s) ||
        listOf("reason", "r1", "think", "qwq", "gpt-5", "deepseek-r", "grok-3-mini", "grok-4").any { it in s }
}

/** 每天每个模型用了多少 token。 */
@Serializable
data class UsageDay(
    val date: String,
    val model: String,
    val prompt: Long = 0,
    val cacheHit: Long = 0,
    val completion: Long = 0,
    val requests: Int = 0,
    /** 当场算好的费用（元）。旧版本记的没有，costed=false。 */
    val cost: Double = 0.0,
    val costed: Boolean = false,
    /** 价格不知道的请求次数。 */
    val unpriced: Int = 0,
    /** 服务有没有返回缓存命中数。 */
    val hitKnown: Boolean = false,
)

/** 这一天这个模型花了多少钱（元）；完全不知道价格时返回 null。 */
fun UsageDay.costYuan(): Double? {
    if (costed) return if (unpriced >= requests && cost == 0.0) null else cost
    val p = Pricing.legacyPrice(model) ?: return null
    return Pricing.cost(p, prompt.toInt(), cacheHit.toInt(), completion.toInt())
}

const val DEFAULT_BASE_URL = "https://api.deepseek.com"
const val MODEL_FLASH = "deepseek-flash"
const val MODEL_PRO = "deepseek-v4-pro"

fun modelLabel(model: String): String = when {
    model == MODEL_FLASH -> "V4.1 Flash"
    model == MODEL_PRO -> "V4 Pro"
    // 自定义模型只显示模型名；OpenRouter 这类 "厂商/模型" 去掉厂商前缀
    else -> (parseCustomKey(model)?.second ?: model).substringAfterLast('/')
}

val DEFAULT_PRESETS = listOf(
    Preset(name = "通用助手", prompt = ""),
    Preset(name = "翻译", prompt = "你是专业翻译。用户发中文就翻译成地道的英文，发其他语言就翻译成自然的中文。只输出译文，不要解释。"),
    Preset(name = "写作老师", prompt = "你是耐心的写作老师。帮用户修改文字，让它更通顺、更有感染力，并简要说明改了哪里、为什么。"),
    Preset(name = "编程助手", prompt = "你是资深程序员。回答简洁准确，给出可直接运行的代码，并用一两句话解释关键点。"),
    Preset(name = "朋友聊天", prompt = "你是用户的老朋友，说话自然、轻松、真诚，像微信聊天一样，不要长篇大论。"),
)

/** 内置的最高优先级提示词：专门针对 DeepSeek V4.1 Flash 的已知毛病。 */
const val DEFAULT_DEV_PROMPT = """你正在一个私人聊天 App 里和用户对话。以下规则针对你已知的习惯，在任何对话中都始终有效：

1. 说人话：用自然、口语化的简体中文，像一个懂行的朋友当面解释。专有名词没有通行译法时，保留英文原文并用一句话解释，不要自己造词或生硬直译。中文回答里不要夹带 "Let me" 之类的英文过渡语。
2. 直接回答：第一句就给答案或结论，再补充必要的理由。答完就停，不重复已经说过的内容，不在结尾总结复述，不追加一串“如果你需要…我还可以…”。
3. 长度跟着问题走：简单问题一两句话就够；只有复杂问题才分点、加标题。不为了显得全面而凑内容。
4. 诚实：不确定的事明确说“我不确定”，并说清哪部分是推测；不编造人名、数据、引文、链接和书名。能用搜索工具核实的事实，先核实再回答。
5. 不附和：用户说错了，礼貌但明确地指出；有不同看法就直说理由，不为了讨好改变正确的判断。
6. 前后一致：长对话中牢记之前确定的事实、数字、名字、设定和用户的要求，回答前核对有没有和前文矛盾；涉及数字和计算时自己验算一遍。
7. 分清角色：你是助手，用户是用户。扮演角色时只说你的角色该说的话，不替用户说话或做决定，不把只有某个角色知道的信息透露给其他角色。
8. 不兜圈子：一个思路走不通，就换个方法，或直接说明卡在哪里，不反复尝试同一件事。
9. 写作时：用具体的细节、动作和对话来表现，少用空泛的形容和套话式排比；同一个情节不来回写好几遍。
10. 要求长期有效：用户在对话中提出的格式、语气、字数等要求，之后每一轮都继续遵守，直到用户改口。"""
