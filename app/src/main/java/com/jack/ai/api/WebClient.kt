package com.jack.ai.api

import com.jack.ai.data.Settings
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

/** 能不能联网：Tavily 和 DeepSeek 有一个 Key 就行。 */
fun Settings.webReady(): Boolean = tavilyKey.isNotBlank() || apiKey.isNotBlank()

/**
 * 搜索和读网页。先用 Tavily（有免费额度）；没填 Tavily、Tavily 报错或没结果时，
 * 有 DeepSeek Key 就换 DeepSeek 的 Search / Fetch（每次成功 0.005 元，记进统计）。
 */
class WebClient(
    val tavily: TavilyClient = TavilyClient(),
    private val onPaid: () -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(s: Settings, query: String): List<SearchResult> =
        withTavily(s, emptyList(), { tavily.search(s.tavilyKey, query).takeIf { it.isNotEmpty() } }) { deepseekSearch(s.apiKey, query) }

    suspend fun read(s: Settings, url: String): String =
        withTavily(s, NO_CONTENT, { tavily.extract(s.tavilyKey, url).takeUnless { it == NO_CONTENT } }) { deepseekFetch(s.apiKey, url) }

    /** tavilyCall 返回 null 表示没拿到东西，该换 DeepSeek；两个都用不了时返回 empty 或抛 Tavily 的错。 */
    private suspend fun <T> withTavily(s: Settings, empty: T, tavilyCall: suspend () -> T?, deepseek: suspend () -> T): T {
        var error: Exception? = null
        if (s.tavilyKey.isNotBlank()) {
            try {
                tavilyCall()?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            }
        }
        if (s.apiKey.isBlank()) {
            error?.let { throw it }
            if (s.tavilyKey.isBlank()) throw ApiException("没有可用的联网服务", 0)
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
        const val PRICE = 0.005
    }
}
