package com.jack.ai.api

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** 一段文字拿来干什么：检索时问题和资料要分开（模型给的前缀不一样），分类另有前缀。 */
enum class EmbedUse { QUERY, DOC, CLASSIFY }

/**
 * 在手机上跑向量模型（ONNX Runtime，只用 CPU）：不联网、不花钱。同一时间只加载一个模型，十分钟没用就放掉，省内存。
 * 模型文件由 data/LocalModels 下载；这里只管加载和算。
 */
object LocalEmbed {
    /** 一个模型怎么用：哪个文件、怎么取句向量、各种用途的前缀。 */
    data class Spec(
        val onnx: String,
        val tokenizer: String = "tokenizer.json",
        val pooling: Pooling,
        /** 模型要不要 token_type_ids（BERT 类要）。 */
        val tokenTypes: Boolean = false,
        /** 模型还要的空输入（多模态模型的图片、视频、声音特征，只算文字时给空的）：输入名 → 每行多宽。 */
        val emptyInputs: Map<String, Long> = emptyMap(),
        val prefix: Map<EmbedUse, String> = emptyMap(),
        val maxTokens: Int = 512,
    )

    /** CLS：取第一个位置（bge）；OUTPUT：模型直接给 sentence_embedding（EmbeddingGemma）。 */
    enum class Pooling { CLS, OUTPUT }

    private class Loaded(val dir: File, val spec: Spec, val session: OrtSession, val tok: LocalTokenizer)

    private val lock = Any()
    @Volatile private var loaded: Loaded? = null
    @Volatile private var lastUsed = 0L
    @Volatile private var reaper: Thread? = null
    private const val IDLE_MS = 10 * 60_000L

    /** 这个模型已经在内存里了（加载要几秒；分类这种等不起的，没加载就先不用）。 */
    fun isLoaded(dir: File) = loaded?.dir == dir

    /** 加载（已经加载的直接返回）。换了模型会先放掉旧的。 */
    fun load(dir: File, spec: Spec) {
        synchronized(lock) {
            if (loaded?.dir == dir) return
            release()
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            }
            val tok = LocalTokenizer.load(File(dir, spec.tokenizer))
            val session = env.createSession(File(dir, spec.onnx).path, opts)
            loaded = Loaded(dir, spec, session, tok)
            lastUsed = System.currentTimeMillis()
            startReaper()
        }
    }

    fun release() = synchronized(lock) {
        loaded?.let { runCatching { it.session.close() } }
        loaded = null
    }

    /** 算向量（已归一化，点积就是余弦）。在后台线程调用；第一次要先加载几秒。 */
    fun embed(dir: File, spec: Spec, texts: List<String>, use: EmbedUse): List<FloatArray> = synchronized(lock) {
        // 整个过程拿着锁：一次只算一批（反正都在抢 CPU），也不会算到一半被放掉
        load(dir, spec)
        val m = loaded ?: error("本地模型没加载上")
        // 一条一条算：不用补齐，短句子快；手机上批量也快不了多少
        texts.map { t -> one(m, (spec.prefix[use].orEmpty()) + t) }.also { lastUsed = System.currentTimeMillis() }
    }

    private fun one(m: Loaded, text: String): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val ids = m.tok.encode(text, m.spec.maxTokens)
        val n = ids.size.toLong()
        val shape = longArrayOf(1, n)
        val tensors = LinkedHashMap<String, OnnxTensor>()
        try {
            tensors["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), shape)
            tensors["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape)
            if (m.spec.tokenTypes) tensors["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape)
            m.spec.emptyInputs.forEach { (name, width) -> tensors[name] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(0, width)) }
            val want = if (m.spec.pooling == Pooling.OUTPUT) "sentence_embedding" else "last_hidden_state"
            m.session.run(tensors, setOf(want)).use { r ->
                val v = when (m.spec.pooling) {
                    Pooling.OUTPUT -> (r.get(0).value as Array<*>)[0] as FloatArray
                    // [1, 长度, 维度]：取第一个位置
                    Pooling.CLS -> ((r.get(0).value as Array<*>)[0] as Array<*>)[0] as FloatArray
                }
                return normalize(v)
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    internal fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = kotlin.math.sqrt(s).toFloat().takeIf { it > 0f } ?: return v
        return FloatArray(v.size) { v[it] / n }
    }

    /** 十分钟没用就放掉（Gemma 加载后占两三百 MB 内存）。 */
    private fun startReaper() {
        if (reaper?.isAlive == true) return
        reaper = Thread {
            while (loaded != null) {
                runCatching { Thread.sleep(60_000) }
                synchronized(lock) { if (loaded != null && System.currentTimeMillis() - lastUsed > IDLE_MS) release() }
            }
        }.apply { isDaemon = true; name = "local-embed-idle"; start() }
    }
}
