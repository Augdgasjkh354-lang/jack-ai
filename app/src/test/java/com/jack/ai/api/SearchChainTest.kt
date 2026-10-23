package com.jack.ai.api

import com.jack.ai.data.Settings
import com.jack.ai.data.exaKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** 搜索按 Exa → Tavily → DeepSeek 的顺序；同一家好几个 Key 轮着用，额度用完的歇到下个月。 */
class SearchChainTest {
    private val tried = mutableListOf<String>()
    private val free = mutableListOf<String>()
    private val web = WebClient(onFree = { free += it })

    @Before fun clean() = KeyRest.clear()

    private fun go(s: Settings, exa: (String) -> String?, tavily: (String) -> String? = { null }): String = runBlocking {
        web.chain(s, "空", exa = { k -> tried += k; exa(k) }, tavily = { k -> tried += k; tavily(k) }) { tried += "deepseek"; "DeepSeek 的结果" }
    }

    @Test
    fun exaFirstThenTavilyThenDeepSeek() {
        val s = Settings(apiKey = "ds", exaKey = "exa1", tavilyKey = "tv1")
        assertEquals("Exa 的结果", go(s, exa = { "Exa 的结果" }))
        assertEquals(listOf("exa-web"), free)
        tried.clear()
        // Exa 没结果：换 Tavily（不再试 Exa 的别的 Key）
        assertEquals("Tavily 的结果", go(s.copy(exaKey = "exa1\nexa2"), exa = { null }, tavily = { "Tavily 的结果" }))
        assertEquals(listOf("exa1", "tv1"), tried)
        tried.clear()
        // 都没结果：DeepSeek
        assertEquals("DeepSeek 的结果", go(s, exa = { null }))
        assertEquals(listOf("exa1", "tv1", "deepseek"), tried)
    }

    @Test
    fun aKeyOutOfQuotaRestsAndTheNextOneTakesOver() {
        val s = Settings(exaKey = "exa1\nexa2\nexa3")
        val r = go(s, exa = { k -> if (k == "exa1") throw ApiException("Exa 这个 Key 的额度用完了", 402) else "用 $k 搜到的" })
        assertEquals("用 exa2 搜到的", r)
        assertTrue(KeyRest.resting("exa1"))
        tried.clear()
        // 下一次直接跳过歇着的 exa1
        go(s, exa = { k -> "用 $k 搜到的" })
        assertEquals(listOf("exa2"), tried)
    }

    @Test
    fun networkTroubleSkipsToTheNextProviderNotTheNextKey() {
        val s = Settings(exaKey = "exa1\nexa2", tavilyKey = "tv1")
        val r = go(s, exa = { throw IOException("连不上") }, tavily = { "Tavily 的结果" })
        assertEquals("Tavily 的结果", r)
        assertEquals(listOf("exa1", "tv1"), tried)
        assertTrue(!KeyRest.resting("exa1"))
    }

    @Test
    fun everythingFailsWithoutDeepSeekGivesTheLastError() {
        val s = Settings(exaKey = "exa1", tavilyKey = "tv1")
        val e = runCatching { go(s, exa = { throw ApiException("Exa Key 不对或没权限", 401) }, tavily = { throw ApiException("Tavily 额度用完或请求太频繁", 432) }) }.exceptionOrNull()
        assertEquals("Tavily 额度用完或请求太频繁", e?.message)
        // 两个 Key 都歇着了，Key 不对的歇一天，额度用完的歇到下个月
        assertTrue(KeyRest.resting("exa1")); assertTrue(KeyRest.resting("tv1"))
        assertTrue(KeyRest.resting("tv1", System.currentTimeMillis() + 25 * 3_600_000L))
        assertTrue(!KeyRest.resting("exa1", System.currentTimeMillis() + 25 * 3_600_000L))
    }

    @Test
    fun severalKeysAreOnePerLine() {
        val s = Settings(exaKey = " a1 \n\nb2\na1", tavilyKey = "t1")
        assertEquals(listOf("a1", "b2"), s.exaKeys)
        assertTrue(s.webReady())
        assertTrue(!Settings().webReady())
    }
}
