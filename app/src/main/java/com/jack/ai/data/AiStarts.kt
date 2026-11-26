package com.jack.ai.data

import android.content.Context
import androidx.core.util.AtomicFile
import com.jack.ai.api.DeepSeekClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 空白页弹幕里 Kavi 自己想的 10 条建议：后台模型按记忆和最近聊的话题出，混在固定的那些里随机飘，不另外标出来。
 * 开 App 时看一眼，超过 12 小时没换过就重新想一次；没配好模型、关了空白页建议就不想。
 */
object AiStarts {
    const val COUNT = 10
    private const val TTL_MS = 12L * 60 * 60 * 1000

    @Serializable
    private data class Saved(val at: Long = 0, val items: List<Item> = emptyList())

    @Serializable
    private data class Item(val title: String, val prompt: String)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<BizStarts.Start>>(emptyList())

    /** 现在有的那几条（还没想过是空的）。 */
    val items: StateFlow<List<BizStarts.Start>> = _items

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "ai_starts.json"))

    private fun read(ctx: Context): Saved =
        runCatching { json.decodeFromString(Saved.serializer(), String(file(ctx).readFully())) }.getOrDefault(Saved())

    private fun toStarts(s: Saved) = s.items.mapIndexed { i, it ->
        // 颜色现在不显示，随便给一个；key 带前缀，不和固定的那些撞
        BizStarts.Start("ai$i", it.title, it.prompt, 0xFF6B7280)
    }

    /** 读上次存下的；过期了（或从没想过）就让后台模型再想一次。 */
    suspend fun refresh(ctx: Context, now: Long = System.currentTimeMillis()) = mutex.withLock {
        val saved = read(ctx)
        _items.value = toStarts(saved)
        if (now - saved.at < TTL_MS && saved.items.isNotEmpty()) return@withLock
        val store = Store(ctx)
        val s = store.loadSettings()
        if (!s.bizStarts) return@withLock
        val ep = s.backgroundEndpoint() ?: return@withLock
        val raw = runCatching {
            DeepSeekClient(store.usage("空白页建议")).complete(
                ep,
                listOf(
                    buildJsonObject { put("role", "system"); put("content", Persona.fill(RULES, s)) },
                    buildJsonObject { put("role", "user"); put("content", material(store, s)) },
                ),
                maxTokens = 2048,
                jsonMode = true,
            )
        }.getOrNull() ?: return@withLock
        val items = parseItems(raw).takeIf { it.isNotEmpty() } ?: return@withLock
        val next = Saved(now, items)
        val f = file(ctx)
        val out = f.startWrite()
        try { out.write(json.encodeToString(Saved.serializer(), next).toByteArray()); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
        _items.value = toStarts(next)
    }

    /** 给模型看的：记得的事、最近聊过什么（隐私项目、工作对话不给）。 */
    private fun material(store: Store, s: Settings): String = buildString {
        val projects = store.loadProjects()
        val mems = store.loadMemories().sortedByDescending { it.lastUsedAt.coerceAtLeast(it.createdAt) }.take(40)
        append("【What you remember about {user}】\n")
        append(mems.joinToString("\n") { "- " + it.text.take(120) }.ifBlank { "(nothing yet)" })
        val titles = store.loadConversations().asSequence()
            .filter { !it.work && !it.isolatedIn(projects) && it.messages.isNotEmpty() }
            .sortedByDescending { it.updatedAt }.map { it.title }.filter { it.isNotBlank() && it != "新对话" }.take(20).toList()
        append("\n\n【Recent chat topics】\n").append(titles.joinToString("\n") { "- $it" }.ifBlank { "(none yet)" })
    }.let { Persona.fill(it, s) }

    private const val RULES = """You are Kavi, {user}'s assistant. Suggest things {user} might want to ask you to do next; they drift across the empty chat screen like bullet comments, and tapping one sends its prompt as {user}'s own message.
Write exactly 10. Base most on what you know about {user} and what they've been talking about lately (follow-ups, next steps, related things they'd enjoy); a few can be fresh ideas just for fun. Each must be something you can actually do in a chat: write, look up, plan, make a file, set a reminder, explain, recommend.
- "title": what shows on screen, 4 to 10 Chinese characters, plain and specific, no punctuation at the end, no emoji.
- "prompt": the message sent when tapped, one short sentence written as {user} speaking to you in first person (帮我…/给我…/…怎么样), in Chinese.
Never mention private details in a way that would embarrass {user} if someone glanced at the screen.
Output only one JSON object: {"items": [{"title": "…", "prompt": "…"}]}"""

    /** 把模型回的 JSON 拆成建议：缺字段的、太长的、重复的扔掉，最多 [COUNT] 条。 */
    internal fun parse(raw: String): List<Pair<String, String>> = parseItems(raw).map { it.title to it.prompt }

    private fun parseItems(raw: String): List<Item> {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val arr = o["items"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val t = (m["title"] as? JsonPrimitive)?.content?.trim().orEmpty().trimEnd('。', '，', '！', '？', '.', '!', '?')
            val p = (m["prompt"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (t.isEmpty() || p.isEmpty() || t.length > 16) null else Item(t, p.take(200))
        }.distinctBy { it.title }.take(COUNT)
    }
}
