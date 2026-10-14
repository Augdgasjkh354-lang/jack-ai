package com.jack.ai.api

import com.jack.ai.data.SearchSource
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

data class SearchResult(val title: String, val url: String, val content: String)

class TavilyClient {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(apiKey: String, query: String, maxResults: Int = 5): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("query", query)
                put("max_results", maxResults)
                put("search_depth", "basic")
                put("include_answer", false)
            }.toString()
            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            sharedHttp.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val hint = when (resp.code) {
                        401 -> "Tavily Key 不对，请到设置里检查"
                        429, 432, 433 -> "Tavily 额度用完或请求太频繁"
                        else -> "搜索失败（${resp.code}）"
                    }
                    throw ApiException(hint, resp.code)
                }
                val results = json.parseToJsonElement(text).jsonObject["results"] as? JsonArray
                    ?: return@use emptyList()
                results.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    SearchResult(o["title"].str(), o["url"].str(), o["content"].str())
                }
            }
        }
}

/** 把搜索结果整理成给模型看的文字，编号从 startIndex 开始，方便引用。 */
fun formatResultsForModel(results: List<SearchResult>, startIndex: Int): String {
    if (results.isEmpty()) return "没有搜到相关结果。"
    return results.mapIndexed { i, r ->
        "[${startIndex + i}] ${r.title}\n链接：${r.url}\n${r.content.take(1500)}"
    }.joinToString("\n\n")
}

fun List<SearchResult>.toSources() = map { SearchSource(it.title.ifBlank { it.url }, it.url) }
