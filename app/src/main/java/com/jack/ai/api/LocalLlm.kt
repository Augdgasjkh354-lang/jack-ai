package com.jack.ai.api

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * 在手机上跑小的生成模型（Qwen3.5-0.8B，ONNX Runtime，只用 CPU）：写一行小标题、一句话总结这类短活，不联网、不花钱。
 * 模型分两块：embed_tokens 把编号变成向量，decoder 一步步往下算；线性注意力层的状态（conv、recurrent）和普通注意力层的
 * KV 缓存都由 decoder 自己交回来，下一步原样喂回去。提示词分块喂（一次 16 个记号），因为 decoder 每个位置都会交回整张词表的分数。
 * 同一时间只加载一个，十分钟没用就放掉。
 */
object LocalLlm {
    data class Spec(
        val embed: String,
        val decoder: String,
        val tokenizer: String = "tokenizer.json",
        /** 线性注意力层状态的形状（不含 batch）。 */
        val convShape: LongArray,
        val recurrentShape: LongArray,
        /** KV 缓存：几个头、每头多宽。 */
        val kvHeads: Long,
        val kvDim: Long,
        val stop: List<String> = listOf("<|im_end|>", "<|endoftext|>"),
    )

    private class Loaded(val dir: File, val spec: Spec, val embed: OrtSession, val decoder: OrtSession, val tok: LocalTokenizer)

    private val lock = Any()
    @Volatile private var loaded: Loaded? = null
    @Volatile private var lastUsed = 0L
    @Volatile private var reaper: Thread? = null
    private const val IDLE_MS = 10 * 60_000L
    private const val CHUNK = 16

    fun isLoaded(dir: File) = loaded?.dir == dir

    fun load(dir: File, spec: Spec) = synchronized(lock) {
        if (loaded?.dir == dir) return@synchronized
        release()
        val env = OrtEnvironment.getEnvironment()
        fun opts() = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        val tok = LocalTokenizer.load(File(dir, spec.tokenizer))
        val embed = env.createSession(File(dir, spec.embed).path, opts())
        val decoder = runCatching { env.createSession(File(dir, spec.decoder).path, opts()) }.getOrElse { embed.close(); throw it }
        loaded = Loaded(dir, spec, embed, decoder, tok)
        lastUsed = System.currentTimeMillis()
        startReaper()
    }

    fun release() = synchronized(lock) {
        loaded?.let { runCatching { it.embed.close() }; runCatching { it.decoder.close() } }
        loaded = null
    }

    /**
     * 按对话格式（system + user）生成一段回答，最多 maxNew 个记号；关掉思考，直接答。贪心解码（每步取分数最高的），
     * 同样的输入出同样的结果。user 太长就只留后面 maxPrompt 个记号左右。在后台线程调用。
     */
    fun generate(dir: File, spec: Spec, system: String, user: String, maxNew: Int = 48, maxPrompt: Int = 1024): String = synchronized(lock) {
        load(dir, spec)
        val m = loaded ?: error("本地模型没加载上")
        try {
            val ids = prompt(m.tok, system, user, maxPrompt)
            val stop = spec.stop.mapNotNull { m.tok.id(it) }.toSet()
            m.tok.decode(run(m, ids, maxNew, stop)).trim()
        } finally {
            lastUsed = System.currentTimeMillis()
        }
    }

    /** Qwen 的对话格式：特殊记号单独放，文字照字切（用户的话里写着 <|im_end|> 也混不进格式）。 */
    internal fun prompt(tok: LocalTokenizer, system: String, user: String, maxPrompt: Int): IntArray {
        fun sp(t: String) = tok.id(t) ?: error("词表里没有 $t")
        fun txt(t: String) = tok.encode(t, Int.MAX_VALUE).toList()
        val head = listOf(sp("<|im_start|>")) + txt("system\n$system") + sp("<|im_end|>") + txt("\n") + sp("<|im_start|>") + txt("user\n")
        val tail = listOf(sp("<|im_end|>")) + txt("\n") + sp("<|im_start|>") + txt("assistant\n") +
            sp("<think>") + txt("\n\n") + sp("</think>") + txt("\n\n")
        val body = txt(user).let { if (it.size > maxPrompt) it.takeLast(maxPrompt) else it }
        return (head + body + tail).toIntArray()
    }

    private fun run(m: Loaded, prompt: IntArray, maxNew: Int, stop: Set<Int>): IntArray {
        val env = OrtEnvironment.getEnvironment()
        val spec = m.spec
        // 起始状态：全零；KV 缓存长度 0
        val state = LinkedHashMap<String, OnnxTensor>()
        var owner: OrtSession.Result? = null
        for (name in m.decoder.inputNames) {
            when {
                name.startsWith("past_conv") -> state[name] = zeros(env, longArrayOf(1) + spec.convShape)
                name.startsWith("past_recurrent") -> state[name] = zeros(env, longArrayOf(1) + spec.recurrentShape)
                name.startsWith("past_key_values") -> state[name] = zeros(env, longArrayOf(1, spec.kvHeads, 0, spec.kvDim))
            }
        }
        val out = ArrayList<Int>()
        try {
            var pos = 0
            var last: FloatArray? = null
            fun step(ids: IntArray) {
                val n = ids.size
                val inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { ids[it].toLong() }), longArrayOf(1, n.toLong()))
                val embedded = inputIds.use { m.embed.run(mapOf("input_ids" to it)) }
                val total = pos + n
                val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(total) { 1L }), longArrayOf(1, total.toLong()))
                // 文字模型三行位置都一样（多模态的图片位置才不同）
                val positions = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(3 * n) { pos + it % n.toLong() }), longArrayOf(3, 1, n.toLong()))
                val feed = HashMap<String, OnnxTensor>(state)
                feed["inputs_embeds"] = embedded.get(0) as OnnxTensor
                feed["attention_mask"] = mask
                feed["position_ids"] = positions
                val r = try { m.decoder.run(feed) } finally { embedded.close(); mask.close(); positions.close() }
                // 新的状态在这次的结果里；上一次的结果（或起始的全零）用完了
                owner?.close() ?: state.values.forEach { it.close() }
                owner = r
                state.clear()
                for ((name, v) in r) {
                    if (name == "logits") continue
                    state[name.replace("present_conv", "past_conv").replace("present_recurrent", "past_recurrent").replace("present.", "past_key_values.")] = v as OnnxTensor
                }
                val logits = r.get("logits").get() as OnnxTensor
                val buf = logits.floatBuffer
                val vocab = (logits.info.shape[2]).toInt()
                last = FloatArray(vocab).also { a -> buf.position((n - 1) * vocab); buf.get(a) }
                pos = total
            }
            prompt.toList().chunked(CHUNK).forEach { step(it.toIntArray()) }
            repeat(maxNew) {
                val next = argmax(last!!)
                if (next in stop) return out.toIntArray()
                out += next
                // 同一个记号连着出十次，多半卡住了
                if (out.size >= 10 && out.takeLast(10).distinct().size == 1) return out.toIntArray()
                step(intArrayOf(next))
            }
            return out.toIntArray()
        } finally {
            owner?.close() ?: state.values.forEach { it.close() }
        }
    }

    private fun zeros(env: OrtEnvironment, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.allocate(shape.fold(1L) { a, b -> a * b }.toInt()), shape)

    internal fun argmax(a: FloatArray): Int {
        var best = 0
        for (i in 1 until a.size) if (a[i] > a[best]) best = i
        return best
    }

    private fun startReaper() {
        if (reaper?.isAlive == true) return
        reaper = Thread {
            while (loaded != null) {
                runCatching { Thread.sleep(60_000) }
                synchronized(lock) { if (loaded != null && System.currentTimeMillis() - lastUsed > IDLE_MS) release() }
            }
        }.apply { isDaemon = true; name = "local-llm-idle"; start() }
    }
}
