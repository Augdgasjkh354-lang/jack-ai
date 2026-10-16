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

/** 长对话压缩成前情提要。 */
class CompressFeature(core: AppCore) : Feature(core) {
    internal fun maybeAutoCompress(convId: String) {
        val conv = state.value.conversations.firstOrNull { it.id == convId } ?: return
        if (conv.lastPromptTokens >= state.value.settings.compressTokens) compress(convId, manual = false)
    }

    /** 压缩当前对话。manual=true 时，即使没到阈值也压缩。 */
    fun compress(convId: String? = state.value.currentId, manual: Boolean = true) {
        val id = convId ?: return
        val s = state.value
        if (s.streaming || s.compressingId != null) return
        val conv = s.conversations.firstOrNull { it.id == id } ?: return
        val settings = s.settings
        val ep = settings.backgroundEndpoint() ?: return
        // 保留约 16% 阈值的最近原文（手动压缩时保留少一些，至少 8k）
        val retain = (settings.compressTokens * 0.16).toInt().let { if (manual) (it / 2).coerceAtLeast(8_000) else it }
        val msgs = Compression.toCompress(conv, retain)
        if (msgs.isEmpty()) {
            if (manual) state.update { it.copy(toast = "对话还比较短，暂时不需要压缩") }
            return
        }
        state.update { it.copy(compressingId = id) }
        scope.launch {
            try {
                // 压缩的花费也算进这个对话
                val counted = DeepSeekClient { e, p, h, c -> core.recordSpend(id, e, p, h, c) }
                val summary = counted.complete(
                    ep,
                    Compression.buildRequest(conv.summary, msgs, imagesOk = ep.vision),
                )
                updateConvQuiet(id) {
                    it.copy(summary = summary.trim(), summaryUntilId = msgs.last().id, lastPromptTokens = 0)
                }
                persist()
                if (manual) state.update { it.copy(toast = "已压缩 ${msgs.size} 条较早的消息") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(toast = "压缩失败：${e.message ?: "网络问题"}") }
            } finally {
                state.update { it.copy(compressingId = null) }
            }
        }
    }
}
