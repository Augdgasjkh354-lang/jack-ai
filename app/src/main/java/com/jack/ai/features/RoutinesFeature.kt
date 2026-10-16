package com.jack.ai.features

import android.app.Application
import android.net.Uri
import com.jack.ai.AskWidget
import com.jack.ai.UiState
import com.jack.ai.api.ApiException
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.chat.PromptBuilder
import com.jack.ai.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 提醒和定时任务，以及后台任务结果并回对话。 */
class RoutinesFeature(core: AppCore) : Feature(core) {
    fun saveRoutine(r: Routine) {
        val list = store.loadRoutines().let { l -> if (l.any { it.id == r.id }) l.map { if (it.id == r.id) r else it } else l + r }
        store.saveRoutines(list)
        Routines.schedule(app, r)
        state.update { it.copy(routines = list) }
        refreshWidget()
    }

    fun deleteRoutine(id: String) {
        Routines.cancel(app, id)
        val list = store.loadRoutines().filterNot { it.id == id }
        store.saveRoutines(list)
        state.update { it.copy(routines = list) }
        refreshWidget()
    }

    fun reloadRoutines() = state.update { it.copy(routines = store.loadRoutines()) }

    /** 把后台任务的结果并进对话。openId 不为空时，合并后打开那个对话。 */
    fun mergeInbox(openId: String? = null) {
        scope.launch(ioSerial) {
            val items = store.takeInbox()
            if (items.isNotEmpty()) {
                state.update { s ->
                    var convs = s.conversations
                    items.forEach { item ->
                        val msgs = listOf(
                            ChatMessage(role = "user", content = "⏰ " + item.prompt.ifBlank { item.title }, createdAt = item.at),
                            ChatMessage(role = "assistant", content = item.result, model = MODEL_FLASH, createdAt = item.at + 1),
                        )
                        convs = if (convs.any { it.id == item.convId }) {
                            convs.map { c -> if (c.id == item.convId) c.copy(messages = c.messages + msgs, updatedAt = item.at) else c }
                        } else {
                            listOf(Conversation(id = item.convId, title = "⏰ " + item.title, messages = msgs, updatedAt = item.at)) + convs
                        }
                    }
                    s.copy(conversations = convs.sortedByDescending { it.updatedAt })
                }
                persist()
            }
            if (openId != null && state.value.conversations.any { it.id == openId }) {
                state.update { it.copy(currentId = openId, pending = emptyList()) }
            }
        }
    }
}
