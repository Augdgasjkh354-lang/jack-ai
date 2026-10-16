package com.jack.ai.features

import android.app.Application
import com.jack.ai.AskWidget
import com.jack.ai.UiState
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.Endpoint
import com.jack.ai.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 各功能块共用的东西：界面状态、存储、协程，以及改对话、保存的几个基本操作。
 * 界面只看一个 UiState，所有功能块都改的是同一个。
 */
class AppCore(val app: Application, val store: Store, val scope: CoroutineScope) {
    val state = MutableStateFlow(UiState())
    /** 写文件按顺序来，免得两次保存互相覆盖。 */
    val ioSerial = Dispatchers.IO.limitedParallelism(1)
    /** 停下正在进行的回答（由 ChatViewModel 设置）。 */
    var stop: () -> Unit = {}

    fun toast(msg: String) = state.update { it.copy(toast = msg) }

    private val waiting = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

    /**
     * 弹窗问用户，等他点了再继续。用户停止回答（协程被取消）时弹窗自动收起。
     */
    suspend fun confirm(title: String, body: String, action: String): Boolean {
        val req = com.jack.ai.ConfirmRequest(com.jack.ai.data.newId(), title, body, action)
        val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        waiting[req.id] = answer
        state.update { it.copy(confirm = req) }
        return try {
            answer.await()
        } finally {
            waiting.remove(req.id)
            state.update { if (it.confirm?.id == req.id) it.copy(confirm = null) else it }
        }
    }

    fun answerConfirm(id: String, ok: Boolean) {
        waiting[id]?.complete(ok)
    }

    fun updateConv(id: String, transform: (Conversation) -> Conversation) {
        state.update { s ->
            s.copy(conversations = s.conversations.map {
                if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()) else it
            }.sortedByDescending { it.updatedAt })
        }
    }

    fun patchMessage(convId: String, msgId: String, transform: (ChatMessage) -> ChatMessage) {
        state.update { s ->
            s.copy(conversations = s.conversations.map { c ->
                if (c.id != convId) c
                else c.copy(messages = c.messages.map { if (it.id == msgId) transform(it) else it })
            })
        }
    }

    /** 修改对话但不改变排序时间。 */
    fun updateConvQuiet(id: String, transform: (Conversation) -> Conversation) {
        state.update { s -> s.copy(conversations = s.conversations.map { if (it.id == id) transform(it) else it }) }
    }

    fun persist() {
        // 空对话不保存
        val snapshot = state.value.conversations.filter { it.messages.isNotEmpty() }
        scope.launch(ioSerial) { store.saveConversations(snapshot) }
    }

    fun persistProjects() {
        val snapshot = state.value.projects
        scope.launch(ioSerial) { store.saveProjects(snapshot) }
    }

    fun refreshWidget() {
        scope.launch(Dispatchers.IO) { AskWidget.refresh(app) }
    }

    /** 记一笔用量：写进每日统计（当场算钱），再累加到对话上。 */
    fun recordSpend(convId: String, ep: Endpoint, prompt: Int, hit: Int?, completion: Int) {
        scope.launch(Dispatchers.IO) {
            val cost = store.addUsage(ep, prompt, hit, completion)
            updateConvQuiet(convId) {
                it.copy(
                    spentTokens = it.spentTokens + prompt + completion,
                    requests = it.requests + 1,
                    spentPrompt = it.spentPrompt + prompt,
                    spentHit = it.spentHit + (hit ?: 0),
                    spentCompletion = it.spentCompletion + completion,
                    spentCost = it.spentCost + (cost ?: 0.0),
                    unpriced = it.unpriced || cost == null,
                )
            }
            persist()
        }
    }
}

/** 功能块的基类：把 AppCore 的常用东西直接拿来用。 */
abstract class Feature(protected val core: AppCore) {
    protected val state get() = core.state
    protected val store get() = core.store
    protected val scope get() = core.scope
    protected val ioSerial get() = core.ioSerial
    protected val app: Application get() = core.app
    protected fun updateConv(id: String, transform: (Conversation) -> Conversation) = core.updateConv(id, transform)
    protected fun updateConvQuiet(id: String, transform: (Conversation) -> Conversation) = core.updateConvQuiet(id, transform)
    protected fun persist() = core.persist()
    protected fun persistProjects() = core.persistProjects()
    protected fun refreshWidget() = core.refreshWidget()
}
