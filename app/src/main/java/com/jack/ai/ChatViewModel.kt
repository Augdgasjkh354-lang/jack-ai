package com.jack.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jack.ai.api.ApiException
import com.jack.ai.api.StreamEvent
import com.jack.ai.api.TavilyClient
import com.jack.ai.api.formatResultsForModel
import com.jack.ai.api.toSources
import com.jack.ai.data.MODEL_REASONER
import com.jack.ai.data.SearchRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.Preset
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class UiState(
    val conversations: List<Conversation> = emptyList(),
    val currentId: String? = null,
    val streaming: Boolean = false,
    val settings: Settings = Settings(),
) {
    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val client = DeepSeekClient()
    private val tavily = TavilyClient()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null
    private val ioSerial = Dispatchers.IO.limitedParallelism(1)

    init {
        val convs = store.loadConversations().sortedByDescending { it.updatedAt }
        _state.value = UiState(conversations = convs, settings = store.loadSettings())
    }

    // ---------- 对话管理 ----------

    fun newChat() {
        stop()
        _state.update { it.copy(currentId = null) }
    }

    fun select(id: String) {
        stop()
        _state.update { it.copy(currentId = id) }
    }

    fun delete(id: String) {
        if (id == _state.value.currentId) stop()
        _state.update { s ->
            s.copy(
                conversations = s.conversations.filterNot { it.id == id },
                currentId = if (s.currentId == id) null else s.currentId,
            )
        }
        persist()
    }

    fun rename(id: String, title: String) {
        updateConv(id) { it.copy(title = title.ifBlank { it.title }) }
        persist()
    }

    fun clearAll() {
        stop()
        _state.update { it.copy(conversations = emptyList(), currentId = null) }
        persist()
    }

    /** 在新对话开始前选择人设。 */
    fun choosePreset(preset: Preset) {
        val cur = _state.value.current
        if (cur == null) {
            val conv = Conversation(systemPrompt = preset.prompt, presetName = preset.name)
            _state.update { it.copy(conversations = listOf(conv) + it.conversations, currentId = conv.id) }
        } else if (cur.messages.isEmpty()) {
            updateConv(cur.id) { it.copy(systemPrompt = preset.prompt, presetName = preset.name) }
        }
    }

    // ---------- 设置 ----------

    fun updateSettings(transform: (Settings) -> Settings) {
        _state.update { it.copy(settings = transform(it.settings)) }
        store.saveSettings(_state.value.settings)
    }

    suspend fun checkBalance(): String {
        val s = _state.value.settings
        if (s.apiKey.isBlank()) return "请先填写 API Key"
        return try {
            "余额：" + client.balance(s.baseUrl, s.apiKey)
        } catch (e: ApiException) {
            e.message ?: "查询失败"
        } catch (e: IOException) {
            "网络连接失败"
        }
    }

    // ---------- 发消息 ----------

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.streaming) return
        var conv = _state.value.current
        if (conv == null) {
            conv = Conversation()
            val c = conv
            _state.update { it.copy(conversations = listOf(c) + it.conversations, currentId = c.id) }
        }
        val id = conv.id
        updateConv(id) { c ->
            c.copy(
                title = if (c.messages.isEmpty()) trimmed.take(20).replace('\n', ' ') else c.title,
                messages = c.messages + ChatMessage(role = "user", content = trimmed),
            )
        }
        startReply(id)
    }

    /** 重新生成最后一条回答。 */
    fun regenerate() {
        val conv = _state.value.current ?: return
        if (_state.value.streaming) return
        val msgs = conv.messages.dropLastWhile { it.role == "assistant" }
        if (msgs.isEmpty()) return
        updateConv(conv.id) { it.copy(messages = msgs) }
        startReply(conv.id)
    }

    /** 删除某条消息（以及它之后的所有消息）。 */
    fun deleteFrom(messageId: String) {
        val conv = _state.value.current ?: return
        stop()
        val idx = conv.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        updateConv(conv.id) { it.copy(messages = it.messages.take(idx)) }
        persist()
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun startReply(convId: String) {
        val settings = _state.value.settings
        val conv = _state.value.conversations.first { it.id == convId }
        val reply = ChatMessage(role = "assistant", model = settings.model)
        updateConv(convId) { it.copy(messages = it.messages + reply) }

        if (settings.apiKey.isBlank()) {
            patchMessage(convId, reply.id) { it.copy(error = "还没有填写 API Key，请点左上角菜单 → 设置") }
            persist()
            return
        }
        val web = settings.webSearch && settings.tavilyKey.isNotBlank()
        val reasoner = settings.model == MODEL_REASONER

        val messages = mutableListOf<JsonObject>()
        val system = buildString {
            append(conv.systemPrompt)
            if (web) {
                if (isNotEmpty()) append("\n\n")
                append(webSystemPrompt())
            }
        }
        if (system.isNotBlank()) messages += msg("system", system)
        conv.messages
            .filter { it.error == null && it.content.isNotBlank() }
            .forEach { messages += msg(it.role, it.content) }

        _state.update { it.copy(streaming = true) }
        job = viewModelScope.launch {
            val reasoning = StringBuilder()
            val content = StringBuilder()
            var lastFlush = 0L
            fun flush() {
                val r = reasoning.toString()
                val c = content.toString()
                patchMessage(convId, reply.id) { it.copy(reasoning = r, content = c) }
            }
            var sourceCount = 0
            suspend fun runSearch(query: String): String {
                patchMessage(convId, reply.id) { it.copy(searches = it.searches + SearchRecord(query, pending = true)) }
                fun finish(rec: SearchRecord) = patchMessage(convId, reply.id) { m ->
                    m.copy(searches = m.searches.map { if (it.query == query && it.pending) rec else it })
                }
                return try {
                    val results = tavily.search(settings.tavilyKey, query)
                    finish(SearchRecord(query, results.toSources()))
                    val text = formatResultsForModel(results, sourceCount + 1)
                    sourceCount += results.size
                    text
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val err = (e as? ApiException)?.message ?: "网络连接失败"
                    finish(SearchRecord(query, error = err))
                    "搜索失败：$err"
                }
            }

            try {
                var round = 0
                var tools: JsonArray? = if (web) WEB_TOOLS else null
                while (true) {
                    val calls = sortedMapOf<Int, ToolAcc>()
                    val roundReasoning = StringBuilder()
                    val roundContent = StringBuilder()
                    if (content.isNotEmpty() && !content.endsWith("\n")) content.append("\n\n")
                    val flow = client.stream(settings.baseUrl, settings.apiKey, settings.model, messages, settings.temperature, tools)
                    try {
                        flow.collect { ev ->
                            when (ev) {
                                is StreamEvent.Text -> {
                                    reasoning.append(ev.reasoning); roundReasoning.append(ev.reasoning)
                                    content.append(ev.content); roundContent.append(ev.content)
                                    val now = System.currentTimeMillis()
                                    if (now - lastFlush > 60) { lastFlush = now; flush() }
                                }
                                is StreamEvent.ToolCall -> {
                                    val acc = calls.getOrPut(ev.index) { ToolAcc() }
                                    if (ev.id.isNotEmpty()) acc.id = ev.id
                                    if (ev.name.isNotEmpty()) acc.name = ev.name
                                    acc.args.append(ev.arguments)
                                }
                            }
                        }
                    } catch (e: ApiException) {
                        // 深度思考模型如果不支持工具调用，退回到“先搜再答”
                        if (tools != null && round == 0 && e.code == 400 && roundContent.isEmpty()) {
                            tools = null
                            val q = conv.messages.last { it.role == "user" }.content.take(300)
                            val results = runSearch(q)
                            messages.add(0, msg("system", "以下是联网搜索结果，回答时参考并用 [编号](链接) 标注来源：\n\n$results"))
                            continue
                        }
                        throw e
                    }
                    flush()
                    if (calls.isEmpty()) break

                    round++
                    messages += buildJsonObject {
                        put("role", "assistant")
                        if (roundContent.isEmpty()) put("content", JsonNull) else put("content", roundContent.toString())
                        if (reasoner) put("reasoning_content", roundReasoning.toString())
                        put("tool_calls", buildJsonArray {
                            calls.values.forEach { tc ->
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.name)
                                        put("arguments", tc.args.toString())
                                    })
                                })
                            }
                        })
                    }
                    for (tc in calls.values) {
                        val query = parseQuery(tc.args.toString())
                        val result = if (tc.name == "web_search" && query.isNotBlank()) runSearch(query) else "未知工具"
                        messages += buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tc.id)
                            put("content", result)
                        }
                    }
                    // 最多搜 3 轮，之后必须直接回答
                    if (round >= 3) tools = null
                }
                if (content.isBlank() && reasoning.isEmpty()) {
                    patchMessage(convId, reply.id) { it.copy(error = "没有收到回复，请重试") }
                }
            } catch (e: CancellationException) {
                flush()
                throw e
            } catch (e: ApiException) {
                flush()
                patchMessage(convId, reply.id) { it.copy(error = e.message) }
            } catch (e: IOException) {
                flush()
                patchMessage(convId, reply.id) { it.copy(error = "网络连接失败：${e.message ?: "请检查网络"}") }
            } catch (e: Exception) {
                flush()
                patchMessage(convId, reply.id) { it.copy(error = "出错了：${e.message}") }
            } finally {
                // 停止或出错时，把还在“搜索中”的标记清掉
                patchMessage(convId, reply.id) { m ->
                    m.copy(content = m.content.trimEnd(), searches = m.searches.map { if (it.pending) it.copy(pending = false) else it })
                }
                _state.update { it.copy(streaming = false) }
                persist()
            }
        }
    }

    private class ToolAcc(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private fun msg(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun parseQuery(args: String): String = runCatching {
        Json.parseToJsonElement(args).jsonObject["query"]?.jsonPrimitive?.content
    }.getOrNull().orEmpty()

    private fun webSystemPrompt(): String {
        val today = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(Date())
        return "今天是$today。你可以使用 web_search 工具联网搜索。" +
            "遇到时效性问题（新闻、价格、天气、最新版本、近期事件等）或你不确定的事实时，先搜索再回答；" +
            "常识、闲聊、写作、编程之类不需要搜索的问题直接回答。" +
            "搜索词要简洁精准，必要时可以分几次搜不同角度。" +
            "回答时引用搜索结果，用 [编号](链接) 的格式标注来源。"
    }

    private companion object {
        val WEB_TOOLS: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", "web_search")
                    put("description", "在互联网上搜索最新信息，返回若干网页的标题、链接和摘要。")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "搜索关键词，简洁明确")
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("query")) })
                    })
                })
            })
        }
    }

    // ---------- 工具函数 ----------

    private fun updateConv(id: String, transform: (Conversation) -> Conversation) {
        _state.update { s ->
            s.copy(conversations = s.conversations.map {
                if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()) else it
            }.sortedByDescending { it.updatedAt })
        }
    }

    private fun patchMessage(convId: String, msgId: String, transform: (ChatMessage) -> ChatMessage) {
        _state.update { s ->
            s.copy(conversations = s.conversations.map { c ->
                if (c.id != convId) c
                else c.copy(messages = c.messages.map { if (it.id == msgId) transform(it) else it })
            })
        }
    }

    private fun persist() {
        // 空对话不保存
        val snapshot = _state.value.conversations.filter { it.messages.isNotEmpty() }
        viewModelScope.launch(ioSerial) { store.saveConversations(snapshot) }
    }
}
