package com.jack.ai.api

import com.jack.ai.data.Settings
import com.jack.ai.data.exaKeys
import com.jack.ai.data.tavilyKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 能不能联网：Exa、Tavily、DeepSeek 有一个 Key 就行。 */
fun Settings.webReady(): Boolean = exaKey.isNotBlank() || tavilyKey.isNotBlank() || apiKey.isNotBlank()

/**
 * 每个搜索 Key 的「歇一会儿」：额度用完的歇到下个月 1 号（Exa、Tavily 都是每月 1 号补额度），
 * Key 不对的歇一天，请求太频繁的歇 10 分钟。歇着的 Key 先跳过，轮到下一个。只记在内存里，重启 App 会重新试一遍。
 */
object KeyRest {
    private val until = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun resting(key: String, now: Long = System.currentTimeMillis()) = (until[key] ?: 0) > now

    /** 这个错误值不值得换下一个 Key：Key 不对、额度用完、太频繁。断网这类换 Key 也没用。 */
    fun rotates(code: Int) = code in setOf(401, 402, 403, 429, 432, 433)

    fun rest(key: String, code: Int, now: Long = System.currentTimeMillis()) {
        until[key] = when (code) {
            402, 432, 433 -> java.util.Calendar.getInstance().apply {
                timeInMillis = now; add(java.util.Calendar.MONTH, 1); set(java.util.Calendar.DAY_OF_MONTH, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 5)
            }.timeInMillis
            401, 403 -> now + 24 * 3_600_000L
            else -> now + 10 * 60_000L
        }
    }

    /** 默认轮着用：每三次搜索，两次 Exa 先上、一次 Tavily 先上；同一家的几个 Key 也一个接一个地轮。 */
    private val turn = java.util.concurrent.atomic.AtomicInteger()
    private val keyTurn = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    internal fun exaFirst(): Boolean = turn.getAndIncrement() % 3 != 2

    /** 这一家这次从第几个 Key 开始，后面的依次排上。 */
    internal fun ordered(provider: String, keys: List<String>): List<String> {
        if (keys.size < 2) return keys
        val start = Math.floorMod(keyTurn.getOrPut(provider) { java.util.concurrent.atomic.AtomicInteger() }.getAndIncrement(), keys.size)
        return keys.drop(start) + keys.take(start)
    }

    internal fun clear() { until.clear(); turn.set(0); keyTurn.clear() }
}

/** 这次搜索想先用哪家：一般 AUTO（轮着来）；英文资料、论文、技术文档、GitHub 先 Exa；最新新闻先 Tavily。 */
enum class Prefer { AUTO, EXA, TAVILY }

/**
 * 搜索和读网页。默认轮着用：每三次里两次 Exa 先上、一次 Tavily 先上，同一家的几个 Key 也轮着用，额度摊开；
 * 有特别需要时可以指定先用哪家（[Prefer]）。
 * 一个 Key 额度用完、Key 不对或太频繁，就换同家的下一个；一家都不行（或没结果）再换另一家。
 * 最后有 DeepSeek Key 就用 DeepSeek 的 Search / Fetch（每次成功 0.005 元，记进统计）。
 */
class WebClient(
    val tavily: TavilyClient = TavilyClient(),
    private val onPaid: () -> Unit = {},
    val exa: ExaClient = ExaClient(),
    /** Exa、Tavily 成功一次（免费额度），记进统计：参数是统计里的名字。 */
    private val onFree: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(s: Settings, query: String, prefer: Prefer = Prefer.AUTO): List<SearchResult> = chain(s, emptyList(), prefer,
        exa = { k -> exa.search(k, query).takeIf { it.isNotEmpty() } },
        tavily = { k -> tavily.search(k, query).takeIf { it.isNotEmpty() } },
    ) { deepseekSearch(s.apiKey, query) }

    suspend fun read(s: Settings, url: String): String = chain(s, NO_CONTENT, Prefer.AUTO,
        exa = { k -> exa.contents(k, url).takeUnless { it == NO_CONTENT } },
        tavily = { k -> tavily.extract(k, url).takeUnless { it == NO_CONTENT } },
    ) { deepseekFetch(s.apiKey, url) }

    /**
     * 一家一家、一个 Key 一个 Key 地试。返回 null 表示这家没拿到东西（换下一家，不换 Key）；
     * 都用不了时：有 DeepSeek 就交给它，否则抛最后一个错，或者返回 empty。
     */
    internal suspend fun <T> chain(
        s: Settings, empty: T, prefer: Prefer,
        exa: suspend (String) -> T?, tavily: suspend (String) -> T?,
        deepseek: suspend () -> T,
    ): T {
        var error: Exception? = null
        val e = Triple(EXA_KEY, s.exaKeys, exa)
        val t = Triple(TAVILY_KEY, s.tavilyKeys, tavily)
        // 两家都有 Key 才谈得上轮换
        val exaFirst = when {
            prefer == Prefer.EXA -> true
            prefer == Prefer.TAVILY -> false
            s.exaKeys.isEmpty() || s.tavilyKeys.isEmpty() -> true
            else -> KeyRest.exaFirst()
        }
        for ((name, keys, call) in if (exaFirst) listOf(e, t) else listOf(t, e)) {
            for (key in KeyRest.ordered(name, keys.filterNot { KeyRest.resting(it) })) {
                try {
                    val got = call(key)
                    if (got != null) { onFree(name); return got }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    error = e
                    if (KeyRest.rotates(e.code)) KeyRest.rest(key, e.code) else break
                } catch (e: Exception) {
                    // 断网、超时：同一家换 Key 也没用，直接换下一家
                    error = e
                    break
                }
            }
        }
        if (s.apiKey.isBlank()) {
            error?.let { throw it }
            if (s.exaKeys.isEmpty() && s.tavilyKeys.isEmpty()) throw ApiException("没有可用的联网服务", 0)
            return empty
        }
        return deepseek()
    }

    private suspend fun post(key: String, path: String, body: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.deepseek.com/v1/tools/$path")
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        sharedHttp.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val hint = when (resp.code) {
                    401 -> "DeepSeek Key 不对"
                    402 -> "DeepSeek 余额不足"
                    429 -> "联网请求太频繁，稍后再试"
                    else -> (if (path == "search") "搜索失败" else "读取网页失败") + "（${resp.code}）"
                }
                throw ApiException(hint, resp.code)
            }
            text
        }
    }

    private suspend fun deepseekSearch(key: String, query: String): List<SearchResult> {
        val body = buildJsonObject {
            put("query", query.take(128))
            put("count", 5)
        }.toString()
        val arr = json.parseToJsonElement(post(key, "search", body)).jsonObject["results"] as? JsonArray
        val results = arr.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            SearchResult(o["title"].str(), o["url"].str(), o["summary"].str().ifBlank { o["snippet"].str() })
        }
        if (results.isNotEmpty()) onPaid()
        return results
    }

    private suspend fun deepseekFetch(key: String, url: String): String {
        val body = buildJsonObject { put("url", url) }.toString()
        val content = json.parseToJsonElement(post(key, "fetch", body)).jsonObject["content"].str()
        if (content.isBlank()) return NO_CONTENT
        onPaid()
        return unescapeFetch(content).take(30_000)
    }

    companion object {
        /**
         * DeepSeek Fetch 返回的 Markdown 会把 # . | 写成 \# \. \|。模型看得懂，但原样抄进回答或文件时会多出反斜杠，
         * 所以先还原（代码块里的不动）。
         */
        fun unescapeFetch(md: String): String {
            val parts = md.split("```")
            return parts.mapIndexed { i, p -> if (i % 2 == 1) p else p.replace(FETCH_ESCAPE, "$1") }.joinToString("```")
        }

        private val FETCH_ESCAPE = Regex("""\\([#.|])""")

        const val NO_CONTENT = "没能读取这个网页的内容。"
        /** 计入统计时用的“模型”名。 */
        const val USAGE_KEY = "deepseek-web"
        const val EXA_KEY = "exa-web"
        const val TAVILY_KEY = "tavily-web"
        const val PRICE = 0.005
    }
}
