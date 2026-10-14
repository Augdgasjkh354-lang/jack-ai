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
    val messages: List<ChatMessage> = emptyList(),
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
    val developerPrompt: String = "",
    val userName: String = "",
    val aboutMe: String = "",
    val replyStyle: String = "",
)

const val DEFAULT_BASE_URL = "https://api.deepseek.com"
const val MODEL_FLASH = "deepseek-flash"
const val MODEL_PRO = "deepseek-v4-pro"

fun modelLabel(model: String): String = when (model) {
    MODEL_FLASH -> "V4.1 Flash"
    MODEL_PRO -> "V4 Pro"
    else -> model
}

fun modelSupportsImages(model: String) = model != MODEL_PRO

val DEFAULT_PRESETS = listOf(
    Preset(name = "通用助手", prompt = ""),
    Preset(name = "翻译", prompt = "你是专业翻译。用户发中文就翻译成地道的英文，发其他语言就翻译成自然的中文。只输出译文，不要解释。"),
    Preset(name = "写作老师", prompt = "你是耐心的写作老师。帮用户修改文字，让它更通顺、更有感染力，并简要说明改了哪里、为什么。"),
    Preset(name = "编程助手", prompt = "你是资深程序员。回答简洁准确，给出可直接运行的代码，并用一两句话解释关键点。"),
    Preset(name = "朋友聊天", prompt = "你是用户的老朋友，说话自然、轻松、真诚，像微信聊天一样，不要长篇大论。"),
)
