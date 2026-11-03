# 长任务上下文、打转与中断恢复审计

## 范围与结论

审计范围：

- `chat/Agent.kt`
- `chat/ContextSlim.kt`
- `chat/ImageOffload.kt`
- `data/Compression.kt`
- `tools/LoopGuard.kt`
- `tools/ToolTimeout.kt`

为确认真实触发链，只额外看了 `ChatViewModel.kt`、`PromptBuilder.kt`、`ToolSession.kt`、`CompressFeature.kt` 的调用点；它们不作为本报告的审计对象，也没有改业务代码。

确认 7 个问题：高 2 个，中 5 个。最值得先修的是“首轮请求没有先瘦身”和“ContextSlim 超预算后仍可能一字不压”，两条都会让几百步任务的上下文控制失效。

复现测试集中在 `app/src/test/java/com/jack/ai/chat/LongRunAuditTest.kt`，全部标了 `@Ignore("audit: 复现")`，不会进入正常测试执行。

---

## 高：长任务结束后的下一次请求会先发送完整旧 trail，瘦身来不及生效

**位置**

- `app/src/main/java/com/jack/ai/chat/ContextSlim.kt:8-12`
- `app/src/main/java/com/jack/ai/chat/Agent.kt:92-100`
- `app/src/main/java/com/jack/ai/chat/Agent.kt:240`
- `app/src/main/java/com/jack/ai/data/Compression.kt:45-58`

**会怎样出错**

一次回答跑了几百步时，`ContextSlim` 只改当前 `messages`，明确不改持久化的 `trail`。任务结束后，完整工具调用和结果仍会存在回答记录里。

用户马上追问时，`Agent.run` 会先调用 `stream(...)`，到工具执行完一轮后才调用 `slim`。同时，普通对话压缩要求至少有 3 条用户消息；第一次大任务后马上追问时只有 2 条用户消息，`Compression.toCompress` 会直接返回空。

触发条件：前一条回答包含大量工具结果，用户在前两轮内继续追问。

后果：追问的第一轮请求会把完整旧工具记录发给模型，可能直接碰到上下文/请求体上限；即使没超限，也会多付一轮本来可以避免的大上下文费用。用户会看到请求很慢、接口报“上下文过长/请求过大”，或者任务刚追问就失败。

**证据**

`ContextSlim.kt` 明确只改临时消息：

```kotlin
// 只改这次请求用的 messages，不动 trail（存档里还是原文）。
```

`Agent.kt` 的顺序是先请求，后瘦身：

```kotlin
while (true) {
    beforeRequest(messages, currentTools)
    ...
    stream(messages, currentTools).collect { ev -> ... }
    ...
    slim?.invoke(messages)
}
```

`Compression.kt` 在用户轮数少于 3 时拒绝压缩：

```kotlin
val userIdx = live.indices.filter { live[it].role == "user" }
if (userIdx.size < 3) return emptyList()
```

复现测试：`LongRunAuditTest.slimShouldRunBeforeTheFirstRequest`。它要求第一次 `stream` 之前已经执行 `slim`；当前实现会失败。

**严重程度：高**

原因：长任务产生的大上下文会被重复发送，存在实际的额外 token 花费，并可能直接让后续请求失败。

**建议的修法**

每次 `stream` 前都先执行一次 `slim`，包括第一次请求。另给 `Compression.toCompress` 增加“硬超限”路径：即使不足 3 个用户轮次，只要旧内容已经超过硬阈值，也允许压掉最旧的大任务记录，同时保留最近一轮原文。

---

## 高：ContextSlim 的 BUDGET 只是触发阈值，不是瘦身后的硬上限

**位置**

- `app/src/main/java/com/jack/ai/chat/ContextSlim.kt:23-31`
- `app/src/main/java/com/jack/ai/chat/ContextSlim.kt:37-41`

**会怎样出错**

`BUDGET = 60_000` 只决定“要不要尝试瘦身”。真正处理时，长度不超过 1500 的结果完全跳过，已经带 `MARK` 的结果也永远跳过，最近 6 条则按条数保留而不看总大小。

触发条件包括：

1. 100 条各 1000 字的工具结果，总计约 10 万字；
2. 最近 6 条工具结果本身就超过 6 万字；
3. 几百条旧结果都已经被缩到约千字，累计后再次超过 6 万字。

这些情况里 `slim` 返回后，工具结果总量仍可能远高于 60,000 字。后续每轮继续带着超大的上下文请求，用户会看到越来越慢、花费上升，最终可能因上下文过长而卡住。

**证据**

```kotlin
if (tools.sumOf { text(messages[it]).length } <= BUDGET) return 0
...
val c = text(messages[i])
if (c.length <= MIN_LEN || c.startsWith(MARK)) return@forEach
```

这里没有“处理完成后再次检查总量”的步骤。

复现测试：`LongRunAuditTest.contextSlimShouldEnforceItsBudgetForManySmallResults`。100 条各 1000 字的结果会触发超预算，但当前实现一条都不会压。

**严重程度：高**

原因：几百步任务中该问题会持续重复发送本应被裁掉的上下文，造成额外费用，并可能把任务顶到模型上下文上限。

**建议的修法**

把 `BUDGET` 变成真正的后置条件：第一轮策略压完后若仍超限，继续把更早的小结果或已压过结果降成短占位符/Spill 引用，直到总量回到预算内。最近结果也应按总字符/token 预算保留，而不只按固定 6 条。

---

## 中：中断或自动续做时，没有跨回答的副作用去重，可能重复发送/创建

**位置**

- `app/src/main/java/com/jack/ai/chat/Agent.kt:197-211`
- `app/src/main/java/com/jack/ai/tools/LoopGuard.kt:7-20`

**会怎样出错**

`Agent` 先把整轮 `assistant.tool_calls` 写进 trail，再逐个执行；只有工具返回后才写对应的 `tool` 结果。与此同时，`LoopGuard` 的所有去重状态都只存在当前实例内。

真实触发有两条：

- 副作用工具已经在外部系统完成，紧接着下一轮模型请求遇到网络/5xx，整条回答被标为错误并自动续做；
- 用户在副作用已经提交到外部系统、但 `execute` 还没返回的窗口按停止，当前调用没有完成结果可记录。

当前续做会创建新的 Agent/ToolSession，新的 `LoopGuard` 没见过上一次调用。调用链核对还确认：错误回答不会作为正常历史发回模型，因此“上次已经发成功”的工具记录不能稳定充当去重凭据。

如果清单状态还没来得及更新，模型可能再次调用同一个 `send_email`、新增日历事件等工具。用户会看到重复邮件、重复事件或其他重复外部操作。

**证据**

`Agent.kt`：

```kotlin
messages += assistantCall
trail += assistantCall.toString()
for (c in calls.values) {
    ...
    val result = execute(c.name, c.args.toString())
    ...
    messages += toolMsg
    trail += toolMsg.toString()
}
```

`LoopGuard.kt`：

```kotlin
private var lastCall = ""
private var streak = 0
private val counts = HashMap<String, Int>()
```

这些状态都没有跨回答持久化。调用链佐证：自动续做在 `ChatViewModel.kt:909-923` 新开回答；历史构造在 `PromptBuilder.kt:87` 跳过带 error 的回答。

没有加单测：完整复现需要跨 `ChatViewModel`、`ToolSession` 和外部副作用工具生命周期，单测只测一个类会把关键触发条件简化掉。

**严重程度：中**

原因：会产生实际错误结果，典型表现是重复发送或重复创建。

**建议的修法**

对有副作用的工具维护跨续做的执行账本/幂等键，至少记录“已成功”和“结果未知”两种状态。续做前先查账本；结果未知时优先查询外部状态或要求确认，不要直接重放同一操作。

---

## 中：网络抖动的两次重试额度是整次回答共用，成功后不会恢复

**位置**

- `app/src/main/java/com/jack/ai/chat/Agent.kt:89-90`
- `app/src/main/java/com/jack/ai/chat/Agent.kt:120-123`

**会怎样出错**

`hiccups` 在 `run` 开始时初始化一次，之后只递增，从未在某一轮成功后清零。

触发条件：一个几百步任务早期出现过两次可恢复的空响应网络错误/429/5xx，之后正常跑了很多轮；后面再发生一次同类瞬时故障。

后果：第三次故障不会走“最多重试两次”的逻辑，而是立即抛出。用户会看到长任务在一次普通网络抖动后突然中断，再依赖更外层的自动续做。

**证据**

```kotlin
var hiccups = 0
...
if (isHiccup(e) && hiccups < RETRY_DELAYS.size &&
    roundContent.isEmpty() && roundReasoning.isEmpty() && calls.isEmpty()) {
    kotlinx.coroutines.delay(RETRY_DELAYS[hiccups++])
    continue
}
```

成功完成一轮后没有 `hiccups = 0`。

复现测试：`LongRunAuditTest.transientRetryBudgetShouldResetAfterSuccessfulRounds`。

**严重程度：中**

原因：长任务会被本来可自动恢复的网络抖动打断。

**建议的修法**

把计数改成“连续失败次数”或“当前请求的重试次数”，任意一次请求成功后清零，不要让几小时前的一次抖动消耗后续轮次的重试额度。

---

## 中：LoopGuard 用原始 JSON 字符串做签名，语义相同的调用换个格式就能绕过

**位置**

- `app/src/main/java/com/jack/ai/tools/LoopGuard.kt:14-21`

**会怎样出错**

签名是 `name + "|" + argsText.trim()`，只去掉最外层空白。JSON 内部空格、字段顺序等任何文本差异都会被当成不同调用。

触发条件：模型原地打转时反复调用同一工具、同一参数，但生成的 JSON 格式略有变化，例如：

- `{"query":"x"}`
- `{"query" : "x"}`
- 字段顺序从 `{"query":"x","limit":10}` 变成 `{"limit":10,"query":"x"}`

后果：连续 3 次/累计 5 次的保护都可能一直达不到，模型继续重复搜索、读页面或其他工具调用，用户看到任务长时间不往前走并继续消耗调用次数和费用。

**证据**

```kotlin
val sig = name + "|" + argsText.trim()
streak = if (sig == lastCall) streak + 1 else 1
...
val total = (counts[sig] ?: 0) + 1
```

复现测试：`LongRunAuditTest.loopGuardShouldTreatEquivalentJsonAsTheSameCall`。

**严重程度：中**

原因：原地打转保护可被模型自然产生的 JSON 格式差异绕过，导致任务卡住。

**建议的修法**

先解析参数 JSON，再按字段名排序、统一数字/空白表示后生成规范签名；对少数工具再补工具级语义签名，忽略不影响结果的默认参数。

---

## 中：Compression 自己的压缩请求没有附件总预算，压缩动作本身可能先超限

**位置**

- `app/src/main/java/com/jack/ai/data/Compression.kt:198-247`
- 重点：`app/src/main/java/com/jack/ai/data/Compression.kt:209-223`

**会怎样出错**

压缩请求对单个文件只截到 20,000 字，但没有限制文件总数或总字符数；如果压缩模型支持图片，则这批消息里的每张图片都会转成 data URL 塞进同一个压缩请求，也没有总张数/总字节预算。

触发条件：要压缩的旧消息里累计了很多文件或图片。

后果：本来是为了缩小上下文的请求，自己先触发上下文/请求体/图片数量限制而失败。压缩摘要不会写入，原始长历史继续保留；后续请求仍然很大。调用链核对确认压缩异常只会结束本次压缩，不会分批继续。

**证据**

```kotlin
m.attachments.filter { it.kind == "file" }.forEach { f ->
    sb.append("\n[文件：${f.name}]\n${f.text.take(20_000)}\n[文件结束]\n")
}
val imgs = m.attachments.filter { it.kind == "image" }
if (imgs.isNotEmpty()) {
    if (imagesOk) {
        ...
        imgs.forEach { img ->
            val url = Attachments.imageDataUrl(img.path) ?: return@forEach
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", url); put("detail", "low") })
            })
        }
    }
}
```

没有全局累计预算，也没有分批逻辑。

没有加复现测试：图片路径会进入 Android 图片/base64 读取，单纯构造 JSON 的测试不能覆盖真实请求体大小和服务限制。

**严重程度：中**

原因：压缩会失败，长对话无法降下来，后续回答可能继续报错或卡住。

**建议的修法**

给压缩请求单独设总字符、文件和图片预算；超过时分批总结，或把多余图片先替换成占位描述。压缩请求也应复用图片卸载/请求体预检，而不是一次塞完。

---

## 中：ImageOffload 把所有 HTTP 413 都当成图片预算错误

**位置**

- `app/src/main/java/com/jack/ai/chat/ImageOffload.kt:8-13`

**会怎样出错**

`rejected` 对 413 不检查错误文本，直接返回 true。

触发条件：请求里恰好有多张图片，但真正导致 413 的是超长文本、工具结果或整个请求体，而不是图片数量/大小。

后果：Agent 会先删旧图片再重试一次。文本超限没有被解决，第二次请求仍失败；同时这次重试已经丢掉旧图上下文。用户会看到任务仍然报错，并且模型在那次错误重试中少了图片信息。

**证据**

```kotlin
fun rejected(e: ApiException): Boolean {
    val text = (e.message.orEmpty() + " " + e.serviceCode).lowercase()
    if (e.code == 413) return true
    return (e.code in setOf(0, 400, 422)) &&
        Regex("image|图片|图像").containsMatchIn(text) &&
        Regex("too.?many|too.?large|exceed|maximum|limit|size|budget|过多|太多|过大|太大|超出|超过|上限").containsMatchIn(text)
}
```

复现测试：`LongRunAuditTest.generic413ShouldNotBeAssumedToBeAnImageBudgetError`。

**严重程度：中**

原因：恢复策略选错，结果仍失败，并在重试时丢掉有用图片上下文。

**建议的修法**

泛 413 先按“整个请求过大”处理：先做文本/工具结果瘦身，再根据错误信息或请求中的图片占比决定是否卸载图片。只有明确的图片限制错误才直接走图片专用恢复。

---

## 已核实没有形成问题的重点

### 旧网页截图会主动省略

`Agent.kt:213-225` 每产生一批新截图，会把本次回答里之前记录的截图消息替换成“之前的网页截图已省略”，再只保留最新截图。现有 `AgentTest.screenshotsGoAfterToolResultsAndOldOnesAreDropped` 已覆盖这个行为。

因此，“当前回答里浏览器不断截图、旧截图一直原样累积”这个问题没有发现。

### 停止后，残缺 tool_call / tool result 不会直接导致下一次请求 400

`Agent` 确实可能在停止时留下不完整 trail；调用链核对发现 `PromptBuilder.complete` 会检查每个 tool call 是否有对应结果，不完整的 trail 不会原样发回接口。

因此，“停止一次后下一次请求必然因为 tool/result 对不上而 400”没有发现。真正留下的风险是上面第 3 条：副作用是否已经完成的信息可能丢掉，续做时可能重复执行。

### ToolTimeout 会保留用户/上游取消

`ToolTimeout.kt:50-54` 只把自己产生的 `Expired` 转成超时文字，并在 catch 中先调用 `ensureActive()`；现有 `ToolTimeoutTest.confirmationWaitIsExcludedAndParentCancellationStillWorks` 也覆盖了上游取消。

因此，常规协程取消被误吞、用户按停止后还被当成普通“工具超时结果”继续喂给模型的问题没有发现。

---

## 存疑

### ToolTimeout 对完全不响应协程取消的阻塞调用能否按时停住

位置：`app/src/main/java/com/jack/ai/tools/ToolTimeout.kt:40-54`。

这里通过 `task.cancel(Expired())` 取消子协程。若某个具体工具内部长期卡在不响应协程取消的阻塞式 Java/native 调用，实际资源是否立即停止取决于那个工具实现。此次范围没有逐个追所有工具底层 I/O，因此不把它列成确认问题。

建议后续单独审计最长时限的几个工具（browser、download_file、phone、delegate_tasks）的底层调用是否支持取消。

### Compression 的图片固定按 1024 token 估算是否足够保守

位置：`app/src/main/java/com/jack/ai/data/Compression.kt:22-25`。

实际视觉 token 取决于模型和图片处理方式，固定 1024 可能与某些服务差距较大。当前自动压缩主要由接口返回的 `lastPromptTokens` 触发，减轻了这个风险；此次没有拿各服务真实计费/视觉 token 规则逐一验证，所以单列存疑。
