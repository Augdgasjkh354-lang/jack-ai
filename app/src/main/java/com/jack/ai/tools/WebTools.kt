package com.jack.ai.tools

import android.content.Intent
import android.provider.AlarmClock
import com.jack.ai.api.formatResultsForModel
import com.jack.ai.api.toSources
import com.jack.ai.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 联网：搜索、读网页。 */

internal suspend fun ToolSession.search(query: String, engine: String = "") = runTool(query) {
    val prefer = when (engine.trim().lowercase()) { "exa" -> com.jack.ai.api.Prefer.EXA; "tavily" -> com.jack.ai.api.Prefer.TAVILY; else -> com.jack.ai.api.Prefer.AUTO }
    val results = host.web.search(settings, query, prefer)
    val text = formatResultsForModel(results, sourceCount + 1)
    sourceCount += results.size
    text to SearchRecord(query, results.toSources())
}

internal suspend fun ToolSession.readPage(url: String) = runTool("读取 $url") {
    val text = host.web.read(settings, url)
    text to SearchRecord("读取 $url", listOf(com.jack.ai.data.SearchSource(url, url)))
}
