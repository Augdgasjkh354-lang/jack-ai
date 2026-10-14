package com.jack.ai.data

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Serializable
data class ChatMessage(
    val id: String = newId(),
    val role: String, // "user" 或 "assistant"
    val content: String = "",
    val reasoning: String = "",
    val model: String = "",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Conversation(
    val id: String = newId(),
    val title: String = "新对话",
    val systemPrompt: String = "",
    val presetName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Preset(
    val id: String = newId(),
    val name: String,
    val prompt: String,
)

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

data class Settings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = MODEL_CHAT,
    val temperature: Float = 1.0f,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val presets: List<Preset> = DEFAULT_PRESETS,
)

const val DEFAULT_BASE_URL = "https://api.deepseek.com"
const val MODEL_CHAT = "deepseek-chat"
const val MODEL_REASONER = "deepseek-reasoner"

val DEFAULT_PRESETS = listOf(
    Preset(name = "通用助手", prompt = ""),
    Preset(name = "翻译", prompt = "你是专业翻译。用户发中文就翻译成地道的英文，发其他语言就翻译成自然的中文。只输出译文，不要解释。"),
    Preset(name = "写作老师", prompt = "你是耐心的写作老师。帮用户修改文字，让它更通顺、更有感染力，并简要说明改了哪里、为什么。"),
    Preset(name = "编程助手", prompt = "你是资深程序员。回答简洁准确，给出可直接运行的代码，并用一两句话解释关键点。"),
    Preset(name = "朋友聊天", prompt = "你是用户的老朋友，说话自然、轻松、真诚，像微信聊天一样，不要长篇大论。"),
)
