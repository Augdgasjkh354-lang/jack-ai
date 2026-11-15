package com.jack.ai.api

import com.jack.ai.data.Endpoint
import com.jack.ai.data.ThinkLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 模型服务的接口格式。Kavi 内部的对话一律是 OpenAI 格式（role / content / tool_calls / tool），
 * 发给 Anthropic、Gemini 原生接口前在这里转换，回来的流再转回 StreamEvent。按接口地址自动认。
 */
enum class ApiFormat {
    OPENAI, ANTHROPIC, GEMINI;

    companion object {
        fun of(ep: Endpoint): ApiFormat = if (ep.deepseek) OPENAI else of(ep.baseUrl)

        fun of(baseUrl: String): ApiFormat {
            val uri = runCatching { java.net.URI(baseUrl.trim()) }.getOrNull() ?: return OPENAI
            val host = uri.host.orEmpty().lowercase()
            val path = uri.path.orEmpty().lowercase().trimEnd('/')
            return when {
                host == "generativelanguage.googleapis.com" -> GEMINI
                host == "api.anthropic.com" -> ANTHROPIC
                // 国内不少服务另给一个 Anthropic 格式的地址：…/anthropic
                path.endsWith("/anthropic") || "/anthropic/" in "$path/" -> ANTHROPIC
                else -> OPENAI
            }
        }
    }
}

/** 一次请求：地址、请求头、请求体。 */
internal data class NativeRequest(val url: String, val headers: Map<String, String>, val body: String)

/** 把一行一行的 SSE 转成 StreamEvent；出错直接抛 ApiException。 */
internal interface SseParser {
    fun feed(line: String): List<StreamEvent>
    /** 收到了正常的结束标记。 */
    val finished: Boolean
    /** 收到过能认的数据。 */
    val sawData: Boolean
}

private val json = Json { ignoreUnknownKeys = true }

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.int(): Int = (this as? JsonPrimitive)?.intOrNull ?: 0

/** OpenAI 格式的 content（字符串、分段数组或 null）拆成文字和图片。 */
private sealed interface Part {
    data class Text(val text: String) : Part
    /** 图片：data: 开头的带 mime 和 base64，否则是网址。 */
    data class Image(val url: String) : Part {
        val dataUri get() = url.startsWith("data:")
        val mime get() = url.removePrefix("data:").substringBefore(';').ifBlank { "image/png" }
        val base64 get() = url.substringAfter("base64,", "")
    }
}

private fun partsOf(content: JsonElement?): List<Part> = when (content) {
    null, is kotlinx.serialization.json.JsonNull -> emptyList()
    is JsonPrimitive -> listOf(Part.Text(content.content))
    is JsonArray -> content.mapNotNull { el ->
        val o = el.obj() ?: return@mapNotNull null
        when (o["type"].str()) {
            "text" -> Part.Text(o["text"].str())
            "image_url" -> (o["image_url"].obj()?.get("url") ?: o["image_url"]).str().takeIf { it.isNotBlank() }?.let { Part.Image(it) }
            else -> null
        }
    }
    else -> emptyList()
}

private fun textOf(content: JsonElement?): String =
    partsOf(content).joinToString("\n") { if (it is Part.Text) it.text else "[图片]" }

private fun argsObject(raw: String): JsonObject =
    runCatching { json.parseToJsonElement(raw.ifBlank { "{}" }) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

/** 同一角色连着的几条并成一条（两家都要求一问一答交替）。 */
private fun merge(list: List<Pair<String, List<JsonObject>>>): List<Pair<String, MutableList<JsonObject>>> {
    val out = mutableListOf<Pair<String, MutableList<JsonObject>>>()
    list.filter { it.second.isNotEmpty() }.forEach { (role, blocks) ->
        val last = out.lastOrNull()
        if (last != null && last.first == role) last.second += blocks else out += role to blocks.toMutableList()
    }
    return out
}

private fun isAnthropicCloud(ep: Endpoint) = runCatching { java.net.URI(ep.baseUrl).host.equals("api.anthropic.com", true) }.getOrDefault(false)

// ------------------------------------------------------------------ Anthropic

internal object AnthropicApi {
    /** Kavi 的思考档位 → Anthropic 的 effort。 */
    private fun effort(level: ThinkLevel): String = when (level.effort) {
        null -> "low"
        "max" -> "max"
        "high" -> "high"
        "low" -> "low"
        else -> "medium"
    }

    fun url(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (Regex("""/v1$""").containsMatchIn(base)) "$base/$path" else "$base/v1/$path"
    }

    fun headers(ep: Endpoint): Map<String, String> = buildMap {
        put("x-api-key", ep.apiKey)
        if (isAnthropicCloud(ep)) {
            put("anthropic-version", "2026-10-21")
            if (refusalFallback(ep.model)) put("anthropic-beta", "server-side-fallback-2026-07-01")
        } else {
            // 别家的 Anthropic 兼容地址多半只认老版本号，有的认 Bearer
            put("anthropic-version", "2023-06-01")
            put("Authorization", "Bearer ${ep.apiKey}")
        }
    }

    /** 这几个模型被安全分类器拒答时，由服务端换到合适的模型接着答（官方建议默认打开）。 */
    private fun refusalFallback(model: String) =
        model.startsWith("claude-fable-5") || model.startsWith("claude-opus-5") || model == "claude-sonnet-5-5"

    fun request(ep: Endpoint, messages: List<JsonObject>, thinking: ThinkLevel, tools: JsonArray?, stream: Boolean, maxTokens: Int? = null): NativeRequest {
        val cloud = isAnthropicCloud(ep)
        val (system, turns) = convert(messages)
        val body = buildJsonObject {
            put("model", ep.model)
            put("max_tokens", maxTokens ?: if (cloud) 64_000 else 16_384)
            if (stream) put("stream", true)
            if (system.isNotBlank()) put("system", system)
            put("messages", JsonArray(turns))
            if (!tools.isNullOrEmpty()) put("tools", buildJsonArray {
                tools.forEach { t ->
                    val fn = t.obj()?.get("function").obj() ?: return@forEach
                    add(buildJsonObject {
                        put("name", fn["name"].str())
                        fn["description"].str().takeIf { it.isNotBlank() }?.let { put("description", it) }
                        put("input_schema", fn["parameters"].obj() ?: buildJsonObject { put("type", "object") })
                        // 大段参数（写文件）边生成边传；Kavi 本来就会处理拼坏了的参数
                        if (cloud && stream) put("eager_input_streaming", true)
                    })
                }
            })
            // 用户标了「能深度思考」才调档位；新模型思考关不掉，不想思考就把 effort 压到最低
            if (ep.reasoning) {
                if (thinking.effort != null) putJsonObject("thinking") { put("type", "adaptive"); put("display", "summarized") }
                putJsonObject("output_config") { put("effort", effort(thinking)) }
            }
            if (cloud) {
                // 自动缓存：同一对话的前缀下次直接命中，便宜不少
                putJsonObject("cache_control") { put("type", "ephemeral") }
                if (refusalFallback(ep.model)) put("fallbacks", "default")
            }
        }
        return NativeRequest(url(ep.baseUrl, "messages"), headers(ep), body.toString())
    }

    /**
     * OpenAI 格式的对话 → (system, messages)。开头的 system 合成顶层 system，中途的 system 当成用户那边的提示；
     * 工具结果放进下一条用户消息的 tool_result。不回传签名思考块：Kavi 会压缩、改写历史，带回去对不上会被拒，
     * 全部不带是官方允许的做法。
     */
    fun convert(messages: List<JsonObject>): Pair<String, List<JsonObject>> {
        val system = StringBuilder()
        var leading = true
        val turns = mutableListOf<Pair<String, List<JsonObject>>>()
        fun textBlock(t: String) = buildJsonObject { put("type", "text"); put("text", t) }
        fun blocksOf(content: JsonElement?): List<JsonObject> = partsOf(content).mapNotNull { p ->
            when (p) {
                is Part.Text -> p.text.takeIf { it.isNotBlank() }?.let { textBlock(it) }
                is Part.Image -> buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        if (p.dataUri) { put("type", "base64"); put("media_type", p.mime); put("data", p.base64) }
                        else { put("type", "url"); put("url", p.url) }
                    }
                }
            }
        }
        messages.forEach { m ->
            when (m["role"].str()) {
                "system" -> if (leading) {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(textOf(m["content"]))
                } else textOf(m["content"]).takeIf { it.isNotBlank() }?.let { turns += "user" to listOf(textBlock("【系统提示】$it")) }
                "user" -> { leading = false; turns += "user" to blocksOf(m["content"]) }
                "assistant" -> {
                    leading = false
                    val blocks = blocksOf(m["content"]).filter { it["type"].str() == "text" }.toMutableList()
                    m["tool_calls"].arr()?.forEach { el ->
                        val tc = el.obj() ?: return@forEach
                        val fn = tc["function"].obj()
                        blocks += buildJsonObject {
                            put("type", "tool_use")
                            put("id", toolId(tc["id"].str()))
                            put("name", fn?.get("name").str())
                            put("input", argsObject(fn?.get("arguments").str()))
                        }
                    }
                    turns += "assistant" to blocks
                }
                "tool" -> {
                    leading = false
                    val parts = blocksOf(m["content"]).ifEmpty { listOf(textBlock("（空）")) }
                    turns += "user" to listOf(buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", toolId(m["tool_call_id"].str()))
                        put("content", JsonArray(parts))
                    })
                }
            }
        }
        val merged = merge(turns).toMutableList()
        if (merged.firstOrNull()?.first != "user") merged.add(0, "user" to mutableListOf(textBlock("（继续）")))
        // 新模型不接受以助手结尾的请求（预填），补一句让它接着说
        if (merged.last().first == "assistant") merged += "user" to mutableListOf(textBlock("接着说。"))
        return system.toString() to merged.map { (role, blocks) ->
            buildJsonObject { put("role", role); put("content", JsonArray(blocks)) }
        }
    }

    /** Anthropic 的工具 id 只认字母、数字、_ 和 -（别家的 id 换到这里当备用时要规整一下，前后对得上就行）。 */
    fun toolId(id: String): String =
        if (Regex("^[A-Za-z0-9_-]{1,64}$").matches(id)) id
        else "t_" + java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    class Parser : SseParser {
        override var finished = false
        override var sawData = false
        private var input = 0
        private var cacheRead = 0
        private var cacheWrite = 0
        private var output = 0
        private var sawToolUse = false
        /** 工具块：有没有收到过参数分片（一个都没有的要补个 {}）。 */
        private val toolArgs = mutableMapOf<Int, Boolean>()

        override fun feed(line: String): List<StreamEvent> {
            if (!line.startsWith("data:")) return emptyList()
            val o = runCatching { json.parseToJsonElement(line.removePrefix("data:").trim()).obj() }.getOrNull() ?: return emptyList()
            sawData = true
            val index = o["index"].int()
            return when (o["type"].str()) {
                "message_start" -> { usage(o["message"].obj()?.get("usage").obj()); emptyList() }
                "content_block_start" -> {
                    val b = o["content_block"].obj()
                    when (b?.get("type").str()) {
                        "tool_use" -> {
                            sawToolUse = true
                            toolArgs[index] = false
                            listOf(StreamEvent.ToolCall(index, b?.get("id").str(), b?.get("name").str(), ""))
                        }
                        "text" -> b?.get("text").str().takeIf { it.isNotEmpty() }?.let { listOf(StreamEvent.Text("", it)) }.orEmpty()
                        else -> emptyList()
                    }
                }
                "content_block_delta" -> {
                    val d = o["delta"].obj()
                    when (d?.get("type").str()) {
                        "text_delta" -> listOf(StreamEvent.Text("", d?.get("text").str()))
                        "thinking_delta" -> d?.get("thinking").str().takeIf { it.isNotEmpty() }?.let { listOf(StreamEvent.Text(it, "")) }.orEmpty()
                        "input_json_delta" -> {
                            toolArgs[index] = true
                            listOf(StreamEvent.ToolCall(index, "", "", d?.get("partial_json").str()))
                        }
                        else -> emptyList()
                    }
                }
                "content_block_stop" -> if (toolArgs[index] == false) {
                    toolArgs[index] = true
                    listOf(StreamEvent.ToolCall(index, "", "", "{}"))
                } else emptyList()
                "message_delta" -> {
                    usage(o["usage"].obj())
                    val reason = o["delta"].obj()?.get("stop_reason").str()
                    val events = mutableListOf<StreamEvent>()
                    if (input + cacheRead + cacheWrite > 0) events += StreamEvent.Usage(input + cacheRead + cacheWrite, output, cacheRead)
                    if (reason.isNotEmpty()) events += StreamEvent.Finish(when (reason) {
                        "end_turn", "stop_sequence" -> "stop"
                        "max_tokens", "model_context_window_exceeded" -> "length"
                        "tool_use" -> "tool_calls"
                        else -> if (sawToolUse) "tool_calls" else reason
                    })
                    events
                }
                "message_stop" -> { finished = true; emptyList() }
                "error" -> throw errorOf(o["error"].obj())
                else -> emptyList()
            }
        }

        private fun usage(u: JsonObject?) {
            if (u == null) return
            u["input_tokens"].int().takeIf { it > 0 }?.let { input = it }
            u["cache_read_input_tokens"].int().takeIf { it > 0 }?.let { cacheRead = it }
            u["cache_creation_input_tokens"].int().takeIf { it > 0 }?.let { cacheWrite = it }
            u["output_tokens"].int().takeIf { it > 0 }?.let { output = it }
        }
    }

    /** 流里的 error 事件：过载、限流按对应的状态码报，外面会重试或换备用。 */
    fun errorOf(err: JsonObject?): ApiException {
        val type = err?.get("type").str()
        val code = when (type) {
            "overloaded_error" -> 503
            "rate_limit_error" -> 429
            "api_error" -> 500
            "authentication_error" -> 401
            "permission_error" -> 403
            "invalid_request_error" -> 400
            else -> 0
        }
        return ApiException("服务返回错误：" + err?.get("message").str().ifBlank { type.ifBlank { "未知错误" } }, code, type)
    }

    fun parseModels(body: String?): List<String> =
        (runCatching { json.parseToJsonElement(body.orEmpty()).obj() }.getOrNull()?.get("data").arr())
            ?.mapNotNull { it.obj()?.get("id").str().takeIf(String::isNotBlank) }?.sorted()
            ?: throw ApiException("这个地址返回的不是模型列表，检查一下地址对不对")
}

// ------------------------------------------------------------------ Gemini

internal object GeminiApi {
    /** 工具调用的 id 里带上 Gemini 的 thoughtSignature（下一轮必须原样带回去），用 ~ 隔开。 */
    private const val SIG = "~"

    fun base(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (Regex("""/v\d+(beta|alpha)?\d*$""").containsMatchIn(b)) b else "$b/v1beta"
    }

    fun headers(ep: Endpoint) = mapOf("x-goog-api-key" to ep.apiKey)

    fun request(ep: Endpoint, messages: List<JsonObject>, thinking: ThinkLevel, temperature: Float, tools: JsonArray?, stream: Boolean, jsonMode: Boolean = false): NativeRequest {
        val (system, contents) = convert(messages)
        val model = ep.model.removePrefix("models/")
        val gen3 = "gemini-3" in model
        val body = buildJsonObject {
            if (system.isNotBlank()) putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            put("contents", JsonArray(contents))
            if (!tools.isNullOrEmpty()) putJsonArray("tools") {
                add(buildJsonObject {
                    putJsonArray("functionDeclarations") {
                        tools.forEach { t ->
                            val fn = t.obj()?.get("function").obj() ?: return@forEach
                            add(buildJsonObject {
                                put("name", fn["name"].str())
                                fn["description"].str().takeIf { it.isNotBlank() }?.let { put("description", it) }
                                // 完整的 JSON Schema（parameters 字段只认一个子集）
                                fn["parameters"].obj()?.let { put("parametersJsonSchema", it) }
                            })
                        }
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature.toDouble())
                if (jsonMode) put("responseMimeType", "application/json")
                if (ep.reasoning) putJsonObject("thinkingConfig") {
                    put("includeThoughts", thinking.effort != null)
                    if (gen3) put("thinkingLevel", if (thinking.effort == null || thinking.effort == "low") "low" else "high")
                    else if (thinking.effort == null && "flash" in model) put("thinkingBudget", 0)
                }
            }
        }
        val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return NativeRequest("${base(ep.baseUrl)}/models/$model:$action", headers(ep), body.toString())
    }

    fun convert(messages: List<JsonObject>): Pair<String, List<JsonObject>> {
        val system = StringBuilder()
        var leading = true
        val names = mutableMapOf<String, String>()
        val turns = mutableListOf<Pair<String, List<JsonObject>>>()
        fun text(t: String) = buildJsonObject { put("text", t) }
        fun partsFor(content: JsonElement?): List<JsonObject> = partsOf(content).mapNotNull { p ->
            when (p) {
                is Part.Text -> p.text.takeIf { it.isNotBlank() }?.let { text(it) }
                is Part.Image -> if (p.dataUri) buildJsonObject {
                    putJsonObject("inlineData") { put("mimeType", p.mime); put("data", p.base64) }
                } else text("[图片：${p.url}]")
            }
        }
        messages.forEach { m ->
            when (m["role"].str()) {
                "system" -> if (leading) {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(textOf(m["content"]))
                } else textOf(m["content"]).takeIf { it.isNotBlank() }?.let { turns += "user" to listOf(text("【系统提示】$it")) }
                "user" -> { leading = false; turns += "user" to partsFor(m["content"]) }
                "assistant" -> {
                    leading = false
                    val parts = partsFor(m["content"]).filter { "text" in it }.toMutableList()
                    m["tool_calls"].arr()?.forEach { el ->
                        val tc = el.obj() ?: return@forEach
                        val fn = tc["function"].obj()
                        val id = tc["id"].str()
                        val name = fn?.get("name").str()
                        names[id] = name
                        parts += buildJsonObject {
                            putJsonObject("functionCall") { put("name", name); put("args", argsObject(fn?.get("arguments").str())) }
                            id.substringAfter(SIG, "").takeIf { it.isNotEmpty() }?.let { put("thoughtSignature", it) }
                        }
                    }
                    turns += "model" to parts
                }
                "tool" -> {
                    leading = false
                    val id = m["tool_call_id"].str()
                    val images = partsFor(m["content"]).filter { "inlineData" in it }
                    turns += "user" to listOf(buildJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", names[id] ?: "tool")
                            putJsonObject("response") { put("content", textOf(m["content"]).ifBlank { "（空）" }) }
                        }
                    }) + images
                }
            }
        }
        val merged = merge(turns).toMutableList()
        if (merged.firstOrNull()?.first != "user") merged.add(0, "user" to mutableListOf(text("（继续）")))
        return system.toString() to merged.map { (role, parts) ->
            buildJsonObject { put("role", role); put("parts", JsonArray(parts)) }
        }
    }

    class Parser : SseParser {
        override var finished = false
        override var sawData = false
        private var calls = 0
        private var usage: StreamEvent.Usage? = null

        override fun feed(line: String): List<StreamEvent> {
            if (!line.startsWith("data:")) return emptyList()
            val o = runCatching { json.parseToJsonElement(line.removePrefix("data:").trim()).obj() }.getOrNull() ?: return emptyList()
            sawData = true
            o["error"].obj()?.let { err ->
                throw ApiException("服务返回错误：" + err["message"].str(), err["code"].int(), err["status"].str())
            }
            o["promptFeedback"].obj()?.get("blockReason").str().takeIf { it.isNotEmpty() }?.let {
                throw ApiException("Gemini 拒绝回答这次请求（$it）", serviceCode = it)
            }
            o["usageMetadata"].obj()?.let { u ->
                usage = StreamEvent.Usage(u["promptTokenCount"].int(), u["candidatesTokenCount"].int() + u["thoughtsTokenCount"].int(),
                    u["cachedContentTokenCount"].int())
            }
            val events = mutableListOf<StreamEvent>()
            val cand = o["candidates"].arr()?.firstOrNull().obj() ?: return events
            cand["content"].obj()?.get("parts").arr()?.forEach { el ->
                val p = el.obj() ?: return@forEach
                val fc = p["functionCall"].obj()
                when {
                    fc != null -> {
                        val sig = p["thoughtSignature"].str()
                        val id = "gm" + (calls + 1) + "-" + java.util.UUID.randomUUID().toString().take(8) + (if (sig.isNotEmpty()) SIG + sig else "")
                        events += StreamEvent.ToolCall(calls++, id, fc["name"].str(), (fc["args"].obj() ?: JsonObject(emptyMap())).toString())
                    }
                    p["thought"] == JsonPrimitive(true) -> p["text"].str().takeIf { it.isNotEmpty() }?.let { events += StreamEvent.Text(it, "") }
                    else -> p["text"].str().takeIf { it.isNotEmpty() }?.let { events += StreamEvent.Text("", it) }
                }
            }
            val reason = cand["finishReason"].str()
            if (reason.isNotEmpty()) {
                finished = true
                usage?.takeIf { it.promptTokens > 0 }?.let { events += it }
                events += StreamEvent.Finish(when {
                    calls > 0 -> "tool_calls"
                    reason == "STOP" -> "stop"
                    reason == "MAX_TOKENS" -> "length"
                    else -> reason.lowercase()
                })
            }
            return events
        }
    }

    fun parseModels(body: String?): List<String> =
        (runCatching { json.parseToJsonElement(body.orEmpty()).obj() }.getOrNull()?.get("models").arr())
            ?.mapNotNull { el ->
                val m = el.obj() ?: return@mapNotNull null
                val methods = m["supportedGenerationMethods"].arr()?.map { it.str() }.orEmpty()
                m["name"].str().removePrefix("models/").takeIf { it.isNotBlank() && (methods.isEmpty() || "generateContent" in methods) }
            }?.sorted()
            ?: throw ApiException("这个地址返回的不是模型列表，检查一下地址对不对")
}
