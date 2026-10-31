package com.jack.ai.chat

import android.content.Context
import com.jack.ai.api.EmbedUse
import com.jack.ai.api.Embedder
import com.jack.ai.api.LocalEmbed
import com.jack.ai.data.DeepRecall
import com.jack.ai.data.Facts
import com.jack.ai.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 手机上的本地向量模型平时做的事：
 * - 预热：加载模型，后台把深处记忆、事实库还没算的向量补上，免得聊天时现算；
 * - 判断问题难不难：「思考·自动」的规则没认出来的，和几组例句比一比，像难题就用高档思考。只用本地模型，不联网不花钱。
 */
object LocalBrain {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 难题和简单问题的例句：新问题和哪组最像的三句更近，就归哪组。 */
    internal val HARD = listOf(
        "帮我写一个 Python 爬虫抓取商品价格", "这段代码报错 NullPointerException 怎么修", "证明根号二是无理数",
        "分析一下这家公司的财报和风险", "给我设计一个三个月的增长方案", "比较一下这三款手机的优缺点",
        "解这个方程组 x+y=3, 2x-y=0", "帮我规划一下从深圳到大阪的七天行程，预算一万", "优化这个 SQL 查询的性能",
        "写一份完整的商业计划书", "这个合同条款有什么坑", "帮我把这篇文章改得更有说服力",
    )
    internal val EASY = listOf(
        "你好", "今天星期几", "谢谢你", "给我讲个笑话", "早上好呀", "晚安", "你叫什么名字", "我有点累",
        "哈哈哈好的", "推荐一首歌", "在吗", "今天天气不错",
    )

    private class Protos(val engine: String, val hard: List<FloatArray>, val easy: List<FloatArray>)
    @Volatile private var protos: Protos? = null
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
                if (settings.localClassify) runCatching { protos(e) }
                runCatching { DeepRecall.prefill(app, settings) }
                runCatching { Facts.backfill(app, e, limit = 500) }
            } finally {
                warming.set(false)
            }
        }
    }

    private fun protos(e: Embedder.Local): Protos =
        protos?.takeIf { it.engine == e.id } ?: Protos(
            e.id,
            Embedder.embed(e, HARD, use = EmbedUse.CLASSIFY),
            Embedder.embed(e, EASY, use = EmbedUse.CLASSIFY),
        ).also { protos = it }

    /**
     * 这个问题难不难：true 难、false 不难、null 判断不了（没开、没选本地模型、模型还没加载好、超时）。
     * 模型没在内存里就不等（加载要几秒），这次按规则来，顺手在后台加载好。
     */
    suspend fun isHard(ctx: Context, settings: Settings, text: String): Boolean? {
        if (!settings.localClassify || text.isBlank()) return null
        val e = Embedder.local(ctx, settings) ?: return null
        if (!LocalEmbed.isLoaded(e.dir)) { warmUp(ctx, settings); return null }
        // 算到一半超时就不等了，算的那份留在后台接着算完（例句的向量算好会存着）
        val job = scope.async(Dispatchers.IO) {
            val p = protos(e)
            decide(Embedder.embed(e, listOf(text.take(1000)), use = EmbedUse.CLASSIFY).first(), p.hard, p.easy)
        }
        return withTimeoutOrNull(1500) { runCatching { job.await() }.getOrNull() }
    }

    /** 和难题例句里最像的三句的平均相似度，比简单例句的高就算难。 */
    internal fun decide(v: FloatArray, hard: List<FloatArray>, easy: List<FloatArray>): Boolean {
        fun top3(group: List<FloatArray>) = group.map { dot(v, it) }.sortedDescending().take(3).average()
        return top3(hard) > top3(easy)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }
}
