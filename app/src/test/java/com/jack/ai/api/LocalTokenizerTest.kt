package com.jack.ai.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

/**
 * 分词和 Python 的 tokenizers 一致：golden.json 是用 tokenizers 0.23 算出来的（小词表的两个 tokenizer 也是它存的）。
 * 真模型的分词（bge、Gemma 的词表太大不进仓库）：设了 LOCAL_MODEL_DIR（下面有 bge/、gemma/ 两个目录，各放 tokenizer.json）才测。
 */
class LocalTokenizerTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource("localtok/$name")!!.readText()
    private val golden = Json.parseToJsonElement(res("golden.json")).jsonObject
    private val texts = golden["texts"]!!.jsonArray.map { it.jsonPrimitive.content }
    private fun expected(key: String) = golden[key]!!.jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.int }.toIntArray() }

    private fun check(tok: LocalTokenizer, key: String, skip: (String) -> Boolean = { false }) {
        expected(key).forEachIndexed { i, ids ->
            if (!skip(texts[i])) assertArrayEquals("$key：「${texts[i].take(30)}」", ids, tok.encode(texts[i], 100_000))
        }
    }

    @Test
    fun wordPieceMatchesHuggingFace() = check(LocalTokenizer.parse(StringReader(res("wordpiece_tiny.json"))), "wordpiece_tiny")

    @Test
    fun bpeWithByteFallbackMatchesHuggingFace() = check(LocalTokenizer.parse(StringReader(res("bpe_tiny.json"))), "bpe_tiny")

    @Test
    fun truncatesButKeepsSpecialTokens() {
        val wp = LocalTokenizer.parse(StringReader(res("wordpiece_tiny.json")))
        val ids = wp.encode("你好你好你好你好", 5)
        assertArrayEquals(intArrayOf(2, 4, 5, 4, 3), ids)
        val bpe = LocalTokenizer.parse(StringReader(res("bpe_tiny.json")))
        val b = bpe.encode("abc abc abc", 4)
        assertEquals(4, b.size)
        assertEquals(2, b.first()); assertEquals(1, b.last())
    }

    @Test
    fun jsonPullHandlesEscapes() {
        val p = JsonPull(StringReader("""{"a": "x\"y\\z你\n", "b": [1, 2.0, true, null, {"c": []}], "d": -3}"""))
        val got = HashMap<String, Any>()
        p.obj { k ->
            when (k) {
                "a" -> got[k] = p.str()
                "d" -> got[k] = p.int()
                else -> p.skip()
            }
        }
        assertEquals("x\"y\\z你\n", got["a"])
        assertEquals(-3, got["d"])
    }

    @Test
    fun realTokenizersMatchHuggingFace() {
        val dir = System.getenv("LOCAL_MODEL_DIR")?.let(::File)
        assumeTrue("没设 LOCAL_MODEL_DIR，跳过真模型的分词", dir?.isDirectory == true)
        check(LocalTokenizer.load(File(dir, "bge/tokenizer.json")), "bge")
        val gemma = LocalTokenizer.load(File(dir, "gemma/tokenizer.json"))
        // 唯一故意不一样的：文字里写着的「<bos>」Python 当特殊记号，这里当普通文字切（不让用户的文字混进控制符）
        check(gemma, "gemma") { "<bos>" in it }
        // 只有开头自己加的那个 <bos>（编号 2）
        assertEquals(1, gemma.encode("<bos>", 100).count { it == 2 })
    }
}
