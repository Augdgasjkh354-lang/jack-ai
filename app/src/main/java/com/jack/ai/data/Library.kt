package com.jack.ai.data

import kotlinx.serialization.Serializable
import kotlin.math.ln

/** 资料库里的一份文档。正文存在 files/library/<id>.txt。 */
@Serializable
data class LibraryDoc(
    val id: String = newId(),
    val name: String,
    val chars: Int,
    val addedAt: Long = System.currentTimeMillis(),
)

/** 复习卡片（间隔重复）。interval 单位是天。 */
@Serializable
data class Flashcard(
    val id: String = newId(),
    val deck: String,
    val front: String,
    val back: String,
    val due: Long = System.currentTimeMillis(),
    val interval: Float = 0f,
    val ease: Float = 2.5f,
    val reps: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

object Recall {
    private const val DAY = 24 * 3600 * 1000L

    /** grade：0 忘了、1 模糊、2 记得。 */
    fun review(c: Flashcard, grade: Int, now: Long = System.currentTimeMillis()): Flashcard = when (grade) {
        0 -> c.copy(interval = 0f, ease = (c.ease - 0.2f).coerceAtLeast(1.3f), reps = 0, due = now + 10 * 60 * 1000L)
        1 -> {
            val iv = (c.interval * 1.2f).coerceAtLeast(1f)
            c.copy(interval = iv, ease = (c.ease - 0.15f).coerceAtLeast(1.3f), reps = c.reps + 1, due = now + (iv * DAY).toLong())
        }
        else -> {
            val iv = when (c.reps) {
                0 -> 1f
                1 -> 3f
                else -> (c.interval * c.ease).coerceAtLeast(c.interval + 1)
            }
            c.copy(interval = iv, reps = c.reps + 1, due = now + (iv * DAY).toLong())
        }
    }
}

/**
 * 资料库的关键词检索：中文按两个字一组切词，英文按单词，用 BM25 给段落打分。
 * 不需要联网，也不需要向量模型。
 */
class LibraryIndex(docs: List<Pair<String, String>>) {
    data class Chunk(val doc: String, val part: Int, val text: String)

    private val chunks = docs.flatMap { (name, text) -> split(text).mapIndexed { i, t -> Chunk(name, i + 1, t) } }
    private val termFreqs = chunks.map { c -> tokens(c.text).groupingBy { it }.eachCount() }
    private val lengths = termFreqs.map { it.values.sum().coerceAtLeast(1) }
    private val avgLen = if (lengths.isEmpty()) 1.0 else lengths.average()
    private val docFreq = HashMap<String, Int>().also { df -> termFreqs.forEach { tf -> tf.keys.forEach { df[it] = (df[it] ?: 0) + 1 } } }

    val size get() = chunks.size

    fun search(query: String, top: Int = 6): List<Chunk> {
        val q = tokens(query).distinct()
        if (q.isEmpty() || chunks.isEmpty()) return emptyList()
        val n = chunks.size.toDouble()
        val scores = termFreqs.mapIndexed { i, tf ->
            var s = 0.0
            for (t in q) {
                val f = tf[t] ?: continue
                val df = docFreq[t] ?: continue
                val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
                s += idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * lengths[i] / avgLen))
            }
            i to s
        }
        return scores.filter { it.second > 0 }.sortedByDescending { it.second }.take(top).map { chunks[it.first] }
    }

    companion object {
        private fun isCjk(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿'

        fun tokens(text: String): List<String> {
            val out = mutableListOf<String>()
            val s = text.lowercase()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    isCjk(c) -> {
                        var j = i
                        while (j < s.length && isCjk(s[j])) j++
                        if (j - i == 1) out += s.substring(i, j)
                        else for (k in i until j - 1) out += s.substring(k, k + 2)
                        i = j
                    }
                    c.isLetterOrDigit() -> {
                        var j = i
                        while (j < s.length && s[j].isLetterOrDigit() && !isCjk(s[j])) j++
                        if (j - i >= 2 || c.isDigit()) out += s.substring(i, j)
                        i = j
                    }
                    else -> i++
                }
            }
            return out
        }

        /** 切成约 700 字的段落，相邻段落重叠一点，尽量在换行或句号处断开。 */
        fun split(text: String, size: Int = 700, overlap: Int = 120): List<String> {
            val t = text.replace("\r", "").trim()
            if (t.length <= size) return if (t.isBlank()) emptyList() else listOf(t)
            val out = mutableListOf<String>()
            var start = 0
            while (start < t.length) {
                var end = (start + size).coerceAtMost(t.length)
                if (end < t.length) {
                    val cut = t.lastIndexOfAny(charArrayOf('\n', '。', '！', '？', '.', '!', '?'), end)
                    if (cut > start + size / 2) end = cut + 1
                }
                out += t.substring(start, end).trim()
                if (end >= t.length) break
                start = (end - overlap).coerceAtLeast(start + 1)
            }
            return out.filter { it.isNotBlank() }
        }
    }
}
