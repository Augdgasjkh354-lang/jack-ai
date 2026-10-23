package com.jack.ai.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Exa 搜索（https://exa.ai）：按意思找，英文资料、技术文档、论文、GitHub 这类比较准。
 * 每月 1 号免费额度补满 10 美元（普通搜索约 7 美元一千次，正文每页再加约 1 美元一千页）。
 */
class ExaClient {
    private val json = Json { ignoreUnknownKeys = true }

    private fun call(apiKey: String, path: String, body: String): String {
        val request = Request.Builder()
            .url("https://api.exa.ai/$path")
            .header("x-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return sharedHttp.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val hint = when (resp.code) {
                    401, 403 -> "Exa Key 不对或没权限"
                    402 -> "Exa 这个 Key 的额度用完了"
                    429 -> "Exa 请求太频繁"
                    else -> "Exa 出错（${resp.code}）"
                }
                throw ApiException(hint, resp.code)
            }
            text
        }
    }

    /** 搜索，每条带一段正文开头当摘要。 */
    suspend fun search(apiKey: String, query: String, maxResults: Int = 5): List<SearchResult> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("query", query)
            put("numResults", maxResults)
            put("type", "auto")
            putJsonObject("contents") { putJsonObject("text") { put("maxCharacters", 1200) } }
        }.toString()
        val results = json.parseToJsonElement(call(apiKey, "search", body)).jsonObject["results"] as? JsonArray
        results.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            SearchResult(o["title"].str().ifBlank { o["url"].str() }, o["url"].str(), o["text"].str().trim())
        }
    }

    /** 读一个网页的正文。没读到返回 [WebClient.NO_CONTENT]。 */
    suspend fun contents(apiKey: String, url: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("urls", JsonArray(listOf(JsonPrimitive(url))))
            putJsonObject("text") { put("maxCharacters", 30_000) }
        }.toString()
        val first = (json.parseToJsonElement(call(apiKey, "contents", body)).jsonObject["results"] as? JsonArray)?.firstOrNull() as? JsonObject
        first?.get("text").str().trim().ifBlank { WebClient.NO_CONTENT }
    }
}
