package com.jack.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jack.ai.api.ApiException
import com.jack.ai.api.ApiMessage
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

        val history = buildList {
            if (conv.systemPrompt.isNotBlank()) add(ApiMessage("system", conv.systemPrompt))
            conv.messages
                .filter { it.error == null && it.content.isNotBlank() }
                .forEach { add(ApiMessage(it.role, it.content)) }
        }

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
            try {
                client.stream(settings.baseUrl, settings.apiKey, settings.model, history, settings.temperature)
                    .collect { d ->
                        reasoning.append(d.reasoning)
                        content.append(d.content)
                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 60) {
                            lastFlush = now
                            flush()
                        }
                    }
                flush()
                if (content.isEmpty() && reasoning.isEmpty()) {
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
                _state.update { it.copy(streaming = false) }
                persist()
            }
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
