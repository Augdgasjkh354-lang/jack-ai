package com.jack.ai.data

import android.content.Context
import com.jack.ai.api.EmbedUse
import com.jack.ai.api.LocalEmbed
import com.jack.ai.api.sharedHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** 模型的一个文件：在仓库里的路径、大小、SHA-256（下完核对，对不上就不用）。 */
data class ModelFile(val path: String, val size: Long, val sha256: String)

/**
 * 能下载到手机上的本地模型。recall / same / similar / floor 是这个模型上「算相关」「是同一件事」「很像」「值得排进来」的相似度门槛，
 * 各模型的分数分布不一样（Gemma 整体偏高），是拿一组中文记忆和换了说法的问题实测定的。
 */
data class LocalModel(
    val id: String,
    val name: String,
    val blurb: String,
    /** 魔搭（国内快）和 Hugging Face 上的仓库名；hfRevision 固定到某个版本，免得上游改了文件对不上。 */
    val repo: String,
    val hfRevision: String,
    val files: List<ModelFile>,
    /** 向量模型的用法；生成模型是 null，用 llm。 */
    val spec: LocalEmbed.Spec? = null,
    val llm: com.jack.ai.api.LocalLlm.Spec? = null,
    /** 加载后大约占多少内存（MB）。 */
    val ramMb: Int,
    val recall: Float = 1f,
    val same: Float = 1f,
    val similar: Float = 1f,
    val floor: Float = 1f,
) {
    val bytes: Long get() = files.sumOf { it.size }
}

/** 下载状态：done 已下好的字节数，total 总共，running 正在下，error 上次失败的原因。 */
data class ModelDownload(val done: Long = 0, val total: Long = 0, val running: Boolean = false, val error: String = "")

/**
 * 本地模型：列表、下载（断点续传、核对 SHA-256、魔搭 → hf-mirror → Hugging Face 依次试）、删除。
 * 文件放在 noBackupFilesDir/local_models/<id>/，不进备份（几百 MB，换手机重新下）。
 */
object LocalModels {
    val GEMMA = LocalModel(
        id = "embeddinggemma2",
        name = "EmbeddingGemma 2（推荐）",
        blurb = "Google 10 月开源的端侧向量模型，只装文字部分。中文找回准，每条几十毫秒",
        repo = "onnx-community/embeddinggemma-2-ONNX",
        hfRevision = "daa72c51243991dfcaf9f9137d2c573d8f7790c0",
        files = listOf(
            ModelFile("tokenizer.json", 32_170_510, "4d777ef5bdc1aa36227abdfb77c3e49e7b9c892d16e1b6bda41c393504828be4"),
            ModelFile("onnx/model_q4.onnx", 490_742, "f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9"),
            ModelFile("onnx/model_q4.onnx_data", 174_028_800, "c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49"),
        ),
        spec = LocalEmbed.Spec(
            onnx = "onnx/model_q4.onnx",
            pooling = LocalEmbed.Pooling.OUTPUT,
            emptyInputs = mapOf("image_features" to 512L, "video_features" to 512L, "audio_features" to 512L),
            prefix = mapOf(
                EmbedUse.QUERY to "task: search result | query: ",
                EmbedUse.DOC to "title: none | text: ",
                EmbedUse.CLASSIFY to "task: classification | query: ",
            ),
        ),
        ramMb = 300,
        recall = 0.70f, same = 0.95f, similar = 0.88f, floor = 0.62f,
    )

    val BGE_SMALL = LocalModel(
        id = "bge-small-zh",
        name = "bge-small-zh（轻量）",
        blurb = "智源的中文小模型，只有 24MB，几毫秒一条。找回不如 Gemma 准，适合内存小的手机",
        repo = "Xenova/bge-small-zh-v1.5",
        hfRevision = "75c43b069aac4d136ba6bc1122f995fedcfd2781",
        files = listOf(
            ModelFile("tokenizer.json", 439_125, "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26"),
            ModelFile("onnx/model_quantized.onnx", 24_010_842, "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc"),
        ),
        spec = LocalEmbed.Spec(
            onnx = "onnx/model_quantized.onnx",
            pooling = LocalEmbed.Pooling.CLS,
            tokenTypes = true,
            prefix = mapOf(EmbedUse.QUERY to "为这个句子生成表示以用于检索相关文章："),
        ),
        ramMb = 60,
        recall = 0.45f, same = 0.88f, similar = 0.72f, floor = 0.30f,
    )

    /** 生成模型：写思考摘要这类短活。 */
    val QWEN = LocalModel(
        id = "qwen3.5-0.8b",
        name = "Qwen3.5-0.8B",
        blurb = "阿里的小生成模型（4 位量化）。用来写思考摘要的小标题，不花钱；手机上每秒几个到十几个字",
        repo = "onnx-community/Qwen3.5-0.8B-ONNX",
        hfRevision = "8fe9dbe3fb39fe902e18df9be84b6a0ac77ae0f6",
        files = listOf(
            ModelFile("tokenizer.json", 19_226_111, "89da80cc6689bef4d90cc1028249436975ffb0814618f1d93c65310e05801a9b"),
            ModelFile("onnx/embed_tokens_q4.onnx", 857, "8773dcf4858f855bfce13def4356ca17e0bc516a477154496b6ffb6dd6d084dc"),
            ModelFile("onnx/embed_tokens_q4.onnx_data", 162_897_920, "9210b1d26eb14136d3522584d17da14cc5f4b82b6f5607e6d80d59424a8cbe99"),
            ModelFile("onnx/decoder_model_merged_q4.onnx", 876_359, "cfb338c5112e11e2a123d6d03d8870cd800883ee333ec0a25289482b609a16f8"),
            ModelFile("onnx/decoder_model_merged_q4.onnx_data", 485_425_152, "fb38200610a2206aa4c10f4c5f41c08107e2b0b44968166943b3937a1eb11f84"),
        ),
        llm = com.jack.ai.api.LocalLlm.Spec(
            embed = "onnx/embed_tokens_q4.onnx",
            decoder = "onnx/decoder_model_merged_q4.onnx",
            convShape = longArrayOf(6144, 4),
            recurrentShape = longArrayOf(16, 128, 128),
            kvHeads = 2, kvDim = 256,
        ),
        ramMb = 900,
    )

    /** 向量模型（设置里选一个）。 */
    val ALL = listOf(GEMMA, BGE_SMALL)
    /** 生成模型。 */
    val LLMS = listOf(QWEN)

    /** 下好了、设置里打开了「思考摘要用本地模型」的生成模型。 */
    fun activeLlm(ctx: Context, s: Settings): LocalModel? = QWEN.takeIf { s.localLlm && ready(ctx, it) }

    fun byId(id: String): LocalModel? = ALL.firstOrNull { it.id == id }?.takeIf { it.spec != null }

    fun dir(ctx: Context, m: LocalModel) = File(ctx.noBackupFilesDir, "local_models/${m.id}")
    private fun okFile(ctx: Context, m: LocalModel) = File(dir(ctx, m), ".ok")

    /** 下完、核对过了。 */
    fun ready(ctx: Context, m: LocalModel): Boolean = okFile(ctx, m).exists() &&
        m.files.all { File(dir(ctx, m), it.path).length() == it.size }

    /** 设置里选的、而且下好了的那个；没有就是 null。 */
    fun active(ctx: Context, s: Settings): LocalModel? = byId(s.localModel)?.takeIf { ready(ctx, it) }

    /** 本地模型一共占了多少空间（含没下完的）。 */
    fun usedBytes(ctx: Context): Long = File(ctx.noBackupFilesDir, "local_models").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---------- 下载 ----------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val _state = MutableStateFlow<Map<String, ModelDownload>>(emptyMap())
    val state: StateFlow<Map<String, ModelDownload>> = _state

    private val client by lazy { sharedHttp.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build() }

    /** 依次试的下载地址：魔搭（国内直连快）、hf-mirror、Hugging Face（国外，开着 Kavi 专用代理时走代理）。 */
    internal fun urls(m: LocalModel, f: ModelFile) = listOf(
        "https://modelscope.cn/models/${m.repo}/resolve/master/${f.path}",
        "https://hf-mirror.com/${m.repo}/resolve/${m.hfRevision}/${f.path}",
        "https://huggingface.co/${m.repo}/resolve/${m.hfRevision}/${f.path}",
    )

    fun start(ctx: Context, m: LocalModel) = synchronized(jobs) {
        if (jobs[m.id]?.isActive == true) return
        val app = ctx.applicationContext
        set(m.id) { it.copy(running = true, error = "", total = m.bytes, done = partBytes(app, m)) }
        jobs[m.id] = scope.launch {
            val err = runCatching { download(app, m) }.exceptionOrNull()
            set(m.id) { it.copy(running = false, error = when (err) {
                null -> ""
                is kotlinx.coroutines.CancellationException -> ""
                else -> err.message ?: "下载失败"
            }) }
        }
    }

    fun pause(m: LocalModel) = synchronized(jobs) { jobs.remove(m.id)?.cancel() }

    fun delete(ctx: Context, m: LocalModel) {
        pause(m)
        if (LocalEmbed.isLoaded(dir(ctx, m))) LocalEmbed.release()
        if (com.jack.ai.api.LocalLlm.isLoaded(dir(ctx, m))) com.jack.ai.api.LocalLlm.release()
        dir(ctx, m).deleteRecursively()
        _state.update { it - m.id }
    }

    private fun set(id: String, f: (ModelDownload) -> ModelDownload) = _state.update { it + (id to f(it[id] ?: ModelDownload())) }

    /** 已经下了多少（下完的文件 + 没下完的 .part）。 */
    fun partBytes(ctx: Context, m: LocalModel): Long = m.files.sumOf { f ->
        val done = File(dir(ctx, m), f.path)
        if (done.length() == f.size) f.size else File(done.path + ".part").length()
    }

    private suspend fun download(ctx: Context, m: LocalModel) {
        val d = dir(ctx, m).apply { mkdirs() }
        okFile(ctx, m).delete()
        val free = d.usableSpace
        val need = m.bytes - partBytes(ctx, m)
        if (need > 0 && free < need + 50L * 1024 * 1024) throw IOException("手机空间不够：还要 ${mb(need)}，只剩 ${mb(free)}")
        for (f in m.files) {
            val target = File(d, f.path).apply { parentFile?.mkdirs() }
            if (target.length() == f.size) continue
            var last: Exception? = null
            for (url in urls(m, f)) {
                coroutineContext.ensureActive()
                try { fetch(m, url, target, f); last = null; break } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { last = e }
            }
            last?.let { throw IOException("下载 ${f.path.substringAfterLast('/')} 失败：${it.message}", it) }
        }
        okFile(ctx, m).writeText(System.currentTimeMillis().toString())
        set(m.id) { it.copy(done = m.bytes) }
    }

    /** 下一个文件：从 .part 接着下，下完核对大小和 SHA-256，对了才改名。核对不上把 .part 删掉，换下一个地址重下。 */
    private suspend fun fetch(m: LocalModel, url: String, target: File, f: ModelFile) {
        val part = File(target.path + ".part")
        if (part.length() > f.size) part.delete()
        val start = part.length()
        if (start < f.size) {
            val req = Request.Builder().url(url).apply { if (start > 0) header("Range", "bytes=$start-") }.build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("服务器返回 ${r.code}")
                val append = start > 0 && r.code == 206
                val body = r.body ?: throw IOException("没有内容")
                body.byteStream().use { input ->
                    FileOutputStream(part, append).use { out ->
                        var done = if (append) start else 0L
                        val buf = ByteArray(256 * 1024)
                        var lastShown = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done > f.size) throw IOException("文件比预期的大，可能下错了")
                            if (done - lastShown > 1024 * 1024) { lastShown = done; refresh(m, target) }
                        }
                    }
                }
            }
        }
        if (part.length() != f.size) throw IOException("没下完（${mb(part.length())} / ${mb(f.size)}）")
        if (sha256(part) != f.sha256) { part.delete(); throw IOException("文件校验不对，已删掉重下") }
        if (!part.renameTo(target)) throw IOException("存不进去")
    }

    private fun refresh(m: LocalModel, target: File) {
        val d = target.parentFile?.let { generateSequence(it) { p -> p.parentFile }.firstOrNull { p -> p.name == m.id } } ?: return
        val done = m.files.sumOf { f -> val t = File(d, f.path); if (t.length() == f.size) f.size else File(t.path + ".part").length() }
        set(m.id) { it.copy(done = done, total = m.bytes) }
    }

    internal fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun mb(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes in 1 until 1024 * 1024 -> "不到 1 MB"
        else -> "%.0f MB".format(bytes / 1024.0 / 1024)
    }

    // ---------- 测速 ----------

    /** 在这台手机上试一下：加载要多久、算一条要多久（毫秒）；生成模型是写一行摘要要多久，顺便给出写的那行。 */
    fun benchmark(ctx: Context, m: LocalModel): Triple<Long, Long, String> {
        val d = dir(ctx, m)
        m.llm?.let { spec ->
            val t0 = System.currentTimeMillis()
            com.jack.ai.api.LocalLlm.load(d, spec)
            val t1 = System.currentTimeMillis()
            val line = com.jack.ai.api.LocalLlm.generate(d, spec, "把用户给的一段思考压成一个小标题：不超过 12 个字，只输出标题。",
                "用户问深圳到大阪的机票，我先查最近一周的航班价格，再比较直飞和转机，还要看行李额度。", maxNew = 24)
            return Triple(t1 - t0, System.currentTimeMillis() - t1, line)
        }
        val spec = m.spec!!
        val t0 = System.currentTimeMillis()
        LocalEmbed.load(d, spec)
        val t1 = System.currentTimeMillis()
        val samples = listOf("今天下午三点和王总开会", "帮我推荐几本好看的科幻小说", "Jack 喜欢喝不加糖的冰美式", "深圳到大阪的机票多少钱",
            "这段代码为什么报空指针", "周末去哪里徒步比较好")
        LocalEmbed.embed(d, spec, samples, EmbedUse.DOC)
        val t2 = System.currentTimeMillis()
        return Triple(t1 - t0, (t2 - t1) / samples.size, "")
    }
}
