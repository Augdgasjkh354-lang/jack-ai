package com.jack.ai.chat

import android.content.Context
import com.jack.ai.api.Embedder
import com.jack.ai.api.LocalEmbed
import com.jack.ai.data.DeepRecall
import com.jack.ai.data.Facts
import com.jack.ai.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手机上的本地向量模型平时做的事。
 * 预热：加载模型，后台把深处记忆、事实库还没算的向量补上，免得聊天时现算。
 */
object LocalBrain {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 选了本地模型时在后台预热（开 App、换模型时调一次）。 */
    fun warmUp(ctx: Context, settings: Settings, delayMs: Long = 0) {
        val app = ctx.applicationContext
        val e = Embedder.local(app, settings) ?: return
        // 已经在预热了就不再开一份
        if (!warming.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                delay(delayMs)
                runCatching { LocalEmbed.load(e.dir, e.model.spec!!) }.onFailure { return@launch }
                runCatching { DeepRecall.prefill(app, settings) }
                runCatching { Facts.backfill(app, e, limit = 500) }
            } finally {
                warming.set(false)
            }
        }
    }
}
