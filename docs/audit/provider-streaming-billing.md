# 服务商流式、思考参数、计费与免费池审计

调查范围：

- `api/DeepSeekClient.kt`
- `api/Net.kt`
- `data/Models.kt`（Provider 相关）
- `data/Pricing.kt`
- `data/FreePool.kt`

重点核对了流式中断、429/5xx/超时、异常返回格式、服务商参数差异、用量/缓存计费、免费额度切换。调用链只用于确认真实后果，没有把范围外文件当成额外审计对象。

## 高

### 1. 百炼“免费池探测”会对模型列表逐个发真实请求，可能直接花钱，而且成功的百炼模型仍不会进入免费池

- 位置：`data/FreePool.kt:132-166`
- 严重程度：高（花冤枉钱）

会怎样出错：

配置百炼/DashScope 后，每天探测免费池时会先拿完整聊天模型列表，然后对每个聊天模型真的发一次 `/chat/completions`，即使这个模型是按量付费模型也会请求。百炼没有像硅基流动那样先从价格页筛出“明确 0 元”的白名单，所以探测本身可能扣余额。

更糟的是，百炼这里 `freePrices == null`：请求成功只会得到 `probeResult == 1`，代码随后因为 `freePrices != null` 不成立而丢掉结果。也就是说，用户可能为“探测”付了钱，模型却仍不会成为免费候选。之前已经标记 exhausted 的模型，成功探测后还会保留旧 exhausted 状态。

证据：

```kotlin
val freePrices = if (isSiliconFlow(p.baseUrl)) {
    siliconFreeModels()
} else null

for ((model, _) in listed.second.filter {
    isChatModel(it.first) && (freePrices == null || it.first in freePrices)
}) {
    val body = buildJsonObject {
        put("model", model)
        put("max_tokens", 1)
        put("stream", false)
        put("enable_thinking", false)
        ...
    }.toString()

    ...
    when (probeResult(resp.isSuccessful, text)) {
        2 -> FreeModel(p.id, model, now, listed.first)
        1 -> if (freePrices != null) FreeModel(p.id, model, baseUrl = listed.first) else null
        else -> null
    }
}
```

这里还是直接用 `sharedHttp` 请求，没有用量回调，所以探测造成的费用也不会进入 App 的用量统计。

建议的修法：

百炼没有可靠的“明确免费”元数据前，不要对完整模型列表逐个做付费 completion 探测。只探测能由官方额度/价格接口确认免费的模型；确认不了就不要把该服务纳入免费池。

### 2. 自定义服务商的“思考开/关”不是按服务商能力发送，Qwen 新模型和 Kimi 会出现设置失效，Qwen 还可能在“关闭思考”时继续计费思考 token

- 位置：`data/Models.kt:412-419, 481-492`；`api/DeepSeekClient.kt:91-103`
- 严重程度：高（可能花冤枉钱）

会怎样出错：

Provider 只记录一个 `reasoning: Boolean`，含义被写死成“支持 reasoning_effort”。请求侧也只有两种方言：DeepSeek 自己的格式，或通用 `reasoning_effort`。

当前百炼的 Qwen3.5/3.6/3.7 等混合思考模型默认开启思考；Kimi K2.5/K2.6 是可切换思考、默认关闭。它们都没有被 `guessReasoning()` 识别。于是：

- Qwen3.5 等模型选“思考关闭”时，客户端不发关闭思考的参数，模型仍按默认开启思考，用户会多等并多付思考输出 token。
- Kimi K2.5/K2.6 选“思考高”时，因为被猜成 `reasoning=false`，客户端同样不会发开启参数，用户以为开了思考，实际仍按默认关闭。
- 即使用户手工把 `reasoning` 打开，也只会发 `reasoning_effort`；不同百炼模型支持的开关/档位并不完全相同，代码没有表达这种差异。

证据：

```kotlin
data class ProviderModel(
    val id: String,
    val vision: Boolean = false,
    val reasoning: Boolean = false,
    ...
)

fun guessReasoning(id: String): Boolean {
    ...
    return ... || listOf(
        "reason", "r1", "think", "qwq", "gpt-5",
        "deepseek-r", "grok-3-mini", "grok-4"
    ).any { it in s }
}
```

```kotlin
} else if (ep.reasoning && thinking.effort != null) {
    put("reasoning_effort", if (thinking.effort == "max") "high" else thinking.effort)
} else if (!ep.reasoning) {
    put("temperature", temperature.toDouble())
}
```

已加两个 `@Ignore("audit: 复现")` 单测，固定复现 Qwen3.5 和 Kimi K2.5 都没有被识别为可切换思考模型：

- `app/src/test/java/com/jack/ai/data/ProviderReasoningAuditTest.kt`

官方依据：

- Alibaba Cloud Model Studio：<https://www.alibabacloud.com/help/en/model-studio/deep-thinking>
- Alibaba Cloud OpenAI-compatible Chat：<https://docs.modelstudio.console.alibabacloud.com/en/model-studio/qwen-api-via-openai-chat-completions>

建议的修法：

ProviderModel/Endpoint 需要表达“思考方言/可关闭/支持档位”，而不只是一个布尔值；按服务商和模型发送 `enable_thinking`、`reasoning_effort` 或其他对应参数。模型列表导入时也要识别当前 Qwen/Kimi 混合思考模型。

### 3. `complete(maxTokens)` 对自定义服务商既不发送 `max_tokens`，也没有真正执行“不开思考”，后台任务可能比预期更慢、更贵

- 位置：`api/DeepSeekClient.kt:178-198`；`data/Models.kt:456-458`
- 严重程度：高（花冤枉钱）

会怎样出错：

`complete()` 的注释明确说“不开思考”，参数也提供 `maxTokens`。但 `max_tokens` 和 `thinking=disabled` 只在 `ep.deepseek` 时发送。没有 DeepSeek Key 时，`backgroundEndpoint()` 会使用当前自定义模型；此时传进来的 `maxTokens` 完全不起作用。

如果当前模型是默认开启思考的 Qwen3.5/3.6/3.7 一类模型，压缩、做梦、复核等后台请求还会默认进入思考，既增加延迟和输出 token，又没有调用方要求的输出上限。

证据：

```kotlin
suspend fun complete(
    ep: Endpoint,
    messages: List<JsonObject>,
    maxTokens: Int = 16384,
    ...
) {
    val body = buildJsonObject {
        put("model", ep.model)
        put("stream", false)
        if (ep.deepseek) {
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("max_tokens", maxTokens)
        }
        ...
    }
}
```

```kotlin
fun Settings.backgroundEndpoint(): Endpoint? =
    if (apiKey.isNotBlank()) endpoint(MODEL_FLASH)
    else endpoint(model)?.takeIf { it.apiKey.isNotBlank() }
```

建议的修法：

`maxTokens` 应按兼容接口实际支持的输出上限字段发给所有可用 Provider；“后台不开思考”也要走与上一条相同的服务商能力适配，不能只对 DeepSeek 生效。

## 中

### 4. 流式连接在没有 `[DONE]` / finish_reason 的情况下正常 EOF，会被当成成功结束，半截回答可能直接交给用户

- 位置：`api/DeepSeekClient.kt:132-168`
- 严重程度：中（结果错）

会怎样出错：

服务器、反向代理或中间网络如果在已经发出一部分 SSE 后“正常关闭”响应体，`readUtf8Line()` 会返回 null。当前循环直接 `break`，随后 `close()`，上层收到的是“正常完成”而不是异常。

用户会看到半截回答却没有错误提示；如果中断发生在工具调用参数分片中间，上层还可能拿着不完整的工具调用继续处理。

证据：

```kotlin
while (true) {
    val line = source.readUtf8Line() ?: break
    ...
    if (data == "[DONE]") break
    ...
}
...
close()
```

这里没有记录是否见过 `[DONE]` 或合法的终止 `finish_reason`。

建议的修法：

记录流是否收到明确终止标记；已经收到正文/工具分片后却直接 EOF，应抛出可重试的流中断错误。只有明确终止，或服务商文档定义的合法终止形式，才按成功结束。

### 5. OpenRouter 可把“生成到一半失败”放在 `choices[0].error`，当前代码只检查顶层 error，会把部分失败当成正常回复

- 位置：`api/DeepSeekClient.kt:138-155`
- 严重程度：中（结果错）

会怎样出错：

OpenRouter 当前文档说明：模型已经开始生成后失败时，HTTP 仍可能是 200，错误可出现在顶层 `error`，也可出现在 `choices[0].error`，同时 `finish_reason` 为 `error`。

代码只检查顶层 `obj["error"]`。choice 里的 `error` 不读取，只把 `finish_reason="error"` 发成普通 Finish 事件。上层只把 `length` 当截断，因此用户可能拿到一段不完整正文，却看不到真正错误，也不会触发本轮的故障重试。

证据：

```kotlin
(obj["error"] as? JsonObject)?.let { err ->
    throw ApiException(...)
}

val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
choice["finish_reason"].str().takeIf { it.isNotEmpty() }?.let { reason ->
    trySendBlocking(StreamEvent.Finish(reason))
}
```

官方依据：

- OpenRouter 当前错误/回退示例：<https://openrouter.ai/blog/tutorials/model-routing-for-support-bots-cheap-first-faq-handling/>

建议的修法：

在处理 choice 时显式检查 `choice["error"]` 和 `finish_reason=="error"`，转换成 ApiException；已有部分输出时也要标记回答失败/中断，不能静默成功。

### 6. SSE 的坏 JSON 和“200 但返回非 SSE JSON”都会被静默吞掉，真实服务端错误可能只变成“没有收到回复”

- 位置：`api/DeepSeekClient.kt:133-148`
- 严重程度：中（结果错）

会怎样出错：

任意 `data:` 行只要 JSON 解析失败，就直接 `continue`。如果网关把错误页/错误 JSON 以 HTTP 200 返回，且不是 `data:` 开头，整段内容也会全部跳过。

结果有两种：

- 前面已有正文：坏掉的结尾被忽略，半截正文按正常结果结束。
- 完全没有合法事件：上层只能得到“没有收到回复，请重试”，服务端原始错误被吞掉，无法判断是参数问题、网关问题还是格式不兼容。

证据：

```kotlin
if (!line.startsWith("data:")) continue
...
val obj = runCatching {
    json.parseToJsonElement(data).jsonObject
}.getOrNull() ?: continue
```

建议的修法：

允许已知的 SSE 注释/心跳，但对 `data:` 的非空坏 JSON 应报格式错误；HTTP 200 且整条响应没有任何合法 SSE 事件时，把响应类型/首段内容转成明确 ApiException。

### 7. 流式请求的费用统计依赖最后的 usage 块；中途断开时本次请求可能已经产生费用，却完全没有记录

- 位置：`api/DeepSeekClient.kt:119-145, 168-170`
- 严重程度：中（花费统计错误）

会怎样出错：

用量事件只在服务返回 `usage` 对象且 `prompt_tokens > 0` 时发出。多数兼容服务把 usage 放在流尾。如果模型已经生成了一部分 token 后网络断开，客户端会报 IOException，但这次请求没有 Usage 事件。

对于服务端已经执行并计费的请求，App 的“最近请求/当天花费”会少这一笔；之后上层重试成功，只会记录重试后的那一笔，因此统计可能低于服务商账单。

证据：

```kotlin
override fun onFailure(call: Call, e: IOException) {
    close(e)
}
...
(obj["usage"] as? JsonObject)?.let { u ->
    val p = ...
    val c = ...
    if (p > 0) trySendBlocking(StreamEvent.Usage(p, c, cacheHitOf(u)))
}
```

建议的修法：

失败但已开始生成的请求至少记成“费用未知/待核对”，不要当 0；有 provider generation/usage 查询接口的服务可按 request/generation id 补账。重试要作为独立请求统计。

### 8. OpenRouter 花费用本地三档 token 单价重算，忽略 provider 返回的 exact cost 和 cache-write 溢价，缓存开启时会系统性算错

- 位置：`api/DeepSeekClient.kt:302-308, 341-344`；`data/Models.kt:413-419, 422-433`；`data/Pricing.kt:68-79`
- 严重程度：中（结果错）

会怎样出错：

本地价格模型只有 `hit / miss / out` 三项。OpenRouter 模型列表解析也只保存 prompt、completion、input_cache_read；流里只取 cached_tokens，然后用 `Pricing.cost()` 自己重算。

OpenRouter 当前会直接返回该请求实际扣费的 `usage.cost`。同时缓存写入并不总是按普通输入价计费：例如当前 Anthropic 5 分钟 cache write 是普通输入的 1.25 倍，Alibaba Qwen cache write 也是 1.25 倍；响应里还有 `cache_write_tokens`。当前代码把这些 token 当普通 miss 计价，因此会低估写缓存的请求。ProviderModel 里保存的价格还是抓模型列表当时的快照，服务商价格变化后也会继续按旧价估，直到重新刷新模型。

证据：

```kotlin
return Price(
    hit = usd("input_cache_read") ?: miss,
    miss = miss,
    out = out
)
```

```kotlin
private fun cacheHitOf(u: JsonObject): Int? =
    (u["prompt_cache_hit_tokens"] as? JsonPrimitive)?.intOrNull
        ?: ((u["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens")
            as? JsonPrimitive)?.intOrNull
```

```kotlin
fun cost(p: Price, prompt: Int, hit: Int, completion: Int): Double =
    (hit * p.hit +
        (prompt - hit).coerceAtLeast(0) * p.miss +
        completion * p.out) / 1_000_000.0
```

官方依据：

- OpenRouter usage.cost 建议：<https://openrouter.ai/blog/insights/cost-vs-quality-tradeoff-framework-for-agent-models/>
- OpenRouter 缓存读写计费：<https://openrouter.ai/blog/tutorials/prompt-caching-sticky-routing/>

建议的修法：

OpenRouter 优先记录服务端最终返回的 `usage.cost`（并按美元转人民币），不要用本地 token 价格覆盖 exact cost；没有 exact cost 的服务再走估算。若继续估算，至少把 cache-write token 和对应费率纳入价格模型，并明确显示“估算”。

## 已核对但未发现问题

- 429/5xx：`DeepSeekClient` 会抛 ApiException，不会在 HTTP 层死循环；上层 Agent 只在“这一轮一个字/思考/工具分片都没收到”时对 429、500、502、503、504、IOException 最多重试两次（3 秒、8 秒），没有无限重试。
- 超时：共享 OkHttp 的 connect timeout 30 秒、read timeout 5 分钟、write timeout 30 秒。read timeout 很长，但有上限；没有在 `Net.kt` 发现额外无限重试。
- 工具调用：本次核对的 OpenRouter、百炼 OpenAI-compatible Chat 都支持标准 `tools/tool_calls` 形状；没有找到“切换到这些服务后工具格式必然不兼容”的证据。
- DeepSeek 计价：本次核对的 Flash/Pro 高峰价、缓存命中价、输出价和当前代码一致；工作日高峰/其余时段折扣逻辑没有发现确定错误。
- 免费额度切换：正常 Work 的 delegate_tasks 在精确收到 `AllocationQuota.FreeTierOnly` 后会把免费候选标 exhausted 并换下一个，只有非自由时间才允许回落到付费后台模型；自由时间明确禁止付费 fallback。这里没有发现无限切换。

## 存疑

无。
