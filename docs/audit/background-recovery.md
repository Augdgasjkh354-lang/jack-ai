# 后台任务恢复、去重、锁与预算审计

调查范围：

- `tools/BackgroundWork.kt`
- `tools/TaskCheckpoint.kt`
- `data/Goal.kt`
- `data/Goals.kt`
- `data/GoalWorker.kt`
- `data/Routines.kt`
- `data/RunLog.kt`

重点检查 App 被杀/重启后的续做、重复副作用、同任务并发、浏览器/手机操作锁、后台多轮预算。

## 结论

确认 10 个真实可触发的问题：7 个高、3 个中。最危险的是“外部副作用已经成功但断点还没记下来”和“Work 已做完但 WorkManager 还没确认成功”这两个窗口；都可能让发邮件、提交、下单、删除之类动作在恢复后再做一次。

为可纯逻辑复现的行为加了 3 个忽略测试：

- `app/src/test/java/com/jack/ai/tools/TaskCheckpointTest.kt`：断点过期后成功步骤消失；半轮工具会被补成“这一步没做”。
- `app/src/test/java/com/jack/ai/data/RoutineRecoveryAuditTest.kt`：一次性任务错过时间后 `nextAt()` 没有补跑时间。

## 高

### 1. 外部副作用成功后、断点落盘前被杀，恢复会把它当成没做并可能再执行

**位置：** `tools/BackgroundWork.kt:239-253`、`tools/TaskCheckpoint.kt:67-74`。辅助证据：`chat/Agent.kt:197-211`。

**会怎样出错：** 一轮里模型发出多个工具调用。第二个工具已经把邮件发出、订单提交或远端内容删除成功，进程恰好在工具返回之后、把 tool message 加进 messages / 调 `onTools()` 保存断点之前被系统杀掉。恢复时这个调用没有“成功结果”；同轮已有断点时 `repair()` 还会明确补成“这一步没做”。模型收到恢复提示“已经成功的步骤别重做”，但这一步被记录成没做，于是会再执行一次。用户可能收到两封邮件、两个提交或两笔订单。

**证据：**

```kotlin
// BackgroundWork.kt
else session.perform(name, args)...
onTools = { ... TaskCheckpoint.save(checkpoint, cpKey, messages, spent) }

// TaskCheckpoint.kt
val missing = ids.filter { it !in done }
return msgs + missing.map { id ->
    buildJsonObject { ... put("content", "（任务在这里被打断了，这一步没做。）") }
}
```

Agent 的真实顺序是 `execute()` -> 构造并追加 tool message -> `onTools()`。外部副作用与 durable checkpoint 之间存在明确窗口。

复现：`TaskCheckpointTest.interruptedToolWithoutRecordedResultIsMarkedNotDone`，已标 `@Ignore("audit: 复现")`。

**严重程度：高。**

**建议的修法：** 对发送、提交、下单、删除等危险副作用做 write-ahead 状态和幂等键；恢复时“没有结果”应视为“结果未知，需要先查远端状态”，不能直接写成“没做”。

### 2. 普通 Work 结束时先删断点，WorkManager 还没确认成功；这时被杀会从头重跑

**位置：** `tools/BackgroundWork.kt:296-324`、`data/Routines.kt:440-476`。

**会怎样出错：** `runBackgroundWork()` 已完成全部工具和最终回答，随后在返回 `TaskResult` 前就把普通定时 Work 的断点删掉。RoutineWorker 接下来还要写收件箱、发通知、写 RunLog，最后才 `Result.success()`。如果进程在断点已清掉、WorkManager 尚未持久确认成功的窗口被杀，WorkManager 会恢复这个仍未完成的 Worker；Worker 再次调用 `runBackgroundWork()` 时已经没有断点，会从头执行。一次性任务也会重跑，因为它即使 `enabled=false`，`repeat=="none"` 仍被允许进入执行。

**证据：**

```kotlin
// BackgroundWork.kt
if (!free && (goalId == null || Goals.current(ctx, conv.id)?.status == "complete"))
    TaskCheckpoint.clear(checkpoint)
return TaskResult(...)

// Routines.kt
val result = ... runBackgroundWork(...)
...
store.addInbox(...)
Routines.notify(...)
RunLog.finish(...)
Result.success()
```

**严重程度：高。**

**建议的修法：** 断点/执行租约要到 Worker 的 durable completion 一起提交；至少不要在 `runBackgroundWork()` 内提前清普通任务断点。用持久的 run-id + completed 标记让同一个 WorkRequest 重启时只做收尾，不重放业务工具。

### 3. 断点固定 6 小时过期；长时间关机、无网或省电后会丢掉已完成步骤并从头做

**位置：** `tools/TaskCheckpoint.kt:17-18,43-53`、`tools/BackgroundWork.kt:162-175`。

**会怎样出错：** 任务已经完成过发送/提交等步骤并成功写入断点，之后手机关机、断网或 WorkManager 因系统限制超过 6 小时才重跑。此时 `load()` 直接返回 null；后台任务看不到任何历史，重新从原提示开始。用户会看到同一步再次执行。

**证据：**

```kotlin
const val MAX_AGE_MS = 6 * 3600_000L
...
if (now - at > MAX_AGE_MS || at > now) return null
```

复现：`TaskCheckpointTest.staleCheckpointDropsSuccessfulSideEffectHistory`，已标 `@Ignore("audit: 复现")`。

**严重程度：高。**

**建议的修法：** 用“具体 WorkRequest/run-id 是否还是同一次执行”判断能否恢复，不用纯时间 TTL 丢弃成功历史；旧周期的断点可以按 occurrence-id 分文件隔离。

### 4. 重复定时任务在长时间无网时会积压多个独立 WorkRequest，恢复网络后可同时跑同一个任务

**位置：** `data/Routines.kt:223-250,440-476`。

**会怎样出错：** 每天任务到点时都会创建新的 `OneTimeWorkRequest`，并带 `CONNECTED` 约束，但使用普通 `enqueue(req)`，没有 unique work。手机连续几天无网时，每天都会积压一个同 routine id 的待执行请求；网络恢复后这些请求都是独立 Work，可并发进入同一个 `RoutineWorker`。它们会执行同一份当前任务，可能重复发邮件/提交；而且都用同一个 checkpoint 文件，保存时还会互相覆盖。

**证据：**

```kotlin
val req = OneTimeWorkRequestBuilder<RoutineWorker>()
    .addTag("routine:${r.id}")
    ...
    .setConstraints(...CONNECTED...)
    .build()
WorkManager.getInstance(ctx).enqueue(req)
```

这里没有 `enqueueUniqueWork`，RoutineWorker 也没有按 routine id 的持久/进程内执行租约。

**严重程度：高。**

**建议的修法：** 同一 routine 用 unique work 或持久串行队列；如果要保留“每次都执行”，给每个 occurrence 单独 id，并保证同 routine 的副作用阶段串行且有幂等键。

### 5. Work 单次花费上限只在调用工具前检查，模型请求本身可以把预算继续花穿

**位置：** `tools/BackgroundWork.kt:239-243,266-270,305-310`。辅助证据：`chat/Agent.kt:92-107,199-243`。

**会怎样出错：** `spent` 是收到模型 usage 后才增加；`workCostLimit` 只在 `execute` 工具前检查。一次模型请求可以先把余额从“略低于上限”直接推到上限以上；如果这轮没有工具调用，完全不会经过 limit 判断。即使工具调用被挡住并把 `maxRounds=0`，Agent 还会再发一轮无工具请求来写最终回答，这一轮继续计费。连续多轮 Work 因此能超过用户设置的单次上限。

**证据：**

```kotlin
execute = { ... ->
    val limit = budget?.limit ?: s.workCostLimit
    if (limit > 0 && spent >= limit) {
        session.maxRounds = 0
        ...
    }
},
...
onUsage = { ev ->
    spent += Pricing.priceAt(...)?.let { Pricing.cost(...) } ?: 0.0
}
```

`beforeRequest` 只检查 goal 状态/月度预算，没有检查 `workCostLimit`。

**严重程度：高。**

**建议的修法：** 在每次模型请求前也做单次预算 admission，并预留下一轮最大/估算成本；达到硬上限后不要再发付费“收尾请求”，直接用已有内容结束或给收尾单独的小额保留额度。

### 6. 目标因单次预算暂停后，点“继续”仍恢复旧 spent，容易立即再次暂停并多花一轮模型费

**位置：** `tools/BackgroundWork.kt:163-173,296-322`、`data/GoalWorker.kt:76-80`。辅助证据：`ChatViewModel.kt:503-514`。

**会怎样出错：** Goal 到 `workCostLimit` 后，GoalWorker 把状态改成 paused，但此时 checkpoint 不会清，因为 `runBackgroundWork()` 返回时 goal 还是 active；checkpoint 里保存了已经达到上限的 `spent`。用户点“继续”只把 goal 改回 active、重置 `autoRounds`，不清断点。新 Worker 按相同 goal id 载入断点并恢复旧 spent；它仍会先发一次模型请求，然后工具阶段发现已超上限，再次暂停。用户看到“继续”几乎没继续做事，却又产生模型费用。

**证据：**

```kotlin
TaskCheckpoint.load(... )?.let { saved ->
    ...
    spent = saved.spent
}
...
if (!free && (goalId == null || Goals.current(ctx, conv.id)?.status == "complete"))
    TaskCheckpoint.clear(checkpoint)

// GoalWorker
if (Goal.budgetReached(limit, result.spent) ...) {
    Goals.change(ctx, id) { it?.update("paused", "本轮花费已达任务上限，请查看进展后继续。") }
}
```

**严重程度：高。**

**建议的修法：** 把“恢复执行上下文”和“本轮预算累计”分开存；用户明确点继续时保留历史消息/成功步骤，但为新一轮重置本轮 spend 基线。

### 7. 月度预算没有原子预留；多个不同目标并发时都可能在余额不足前通过检查并一起超支

**位置：** `data/Goals.kt:21-23,55-61`、`tools/BackgroundWork.kt:266-270,305-310`。

**会怎样出错：** 不同 conversation 的 GoalWorker 可以并发运行。每个 Worker 在模型请求前都调用 `pauseForBudget()`，它只是读取当前 usage 总额；两个 Worker 可同时看到“还没到月度预算”，随后各自发一个付费模型请求。usage 只有请求返回后才落账，因此月度预算可能一次被多个并发请求同时冲过。

**证据：**

```kotlin
fun pauseForBudget(...) {
    val limit = store.loadSettings().monthlyBudget
    if (limit <= 0 || !Goal.budgetReached(limit, monthlySpent(store))) return false
    ...
}
```

`Goals.workers` 只按 conversation id 去重，没有全局预算 reservation。

**严重程度：高。**

**建议的修法：** 月度预算 admission 做全局原子预留/结算，或至少把后台付费请求通过一个全局预算闸门串行放行。

## 中

### 8. 一次性任务在手机关机期间错过触发时间，开机后不会补跑

**位置：** `data/Routines.kt:117-129,149-165,411-417`。

**会怎样出错：** 一次性任务设为 10:00，手机 09:00 关机，11:00 开机。BOOT_COMPLETED 会调用 `scheduleAll()`，但 `nextAt(11:00)` 对 10:00 的一次性任务返回 null，因此不再排 Alarm，也不会创建 WorkRequest。用户看到任务一直没结果，实际上已经永久跳过。

**证据：**

```kotlin
if (repeat == "none") {
    ...
    return at(c).timeInMillis.takeIf { it > now }
}
...
fun scheduleAll(ctx: Context) = Store(ctx).loadRoutines().forEach { schedule(ctx, it) }
```

复现：`RoutineRecoveryAuditTest.oneShotTaskMissedWhilePhoneOffHasNoCatchUpTime`，已标 `@Ignore("audit: 复现")`。

**严重程度：中。**

**建议的修法：** 持久化“应执行 occurrence”和“是否已完成”；开机恢复时对一次性任务做有限宽限期补跑，而不是只计算未来时间。

### 9. 监控条件满足后先永久禁用监控，再执行后续 Work；中途被杀后后续步骤不会自动续上

**位置：** `data/Routines.kt:545-569`。

**会怎样出错：** 监控判断条件已满足后，先把 routine 保存成 `enabled=false`，再调用 `runBackgroundWork()` 做 `then`。如果 App/进程在后续 Work 中途被杀，checkpoint 可能还在，但重启后的 PeriodicWorker 先看到 routine 已禁用，直接取消并返回；没有入口再载入这个 checkpoint。用户既不会继续收到监控，也不会自动完成后续动作，只能在运行记录里看到中途被打断。

**证据：**

```kotlin
update(cancel = false) { it.copy(enabled = false, lastRunAt = now) }
val follow = ... runBackgroundWork(...)
...
WorkManager.getInstance(ctx).cancelUniqueWork("watch:${r.id}")
```

**严重程度：中。**

**建议的修法：** 把“条件已满足”和“后续动作已完成”拆成两个持久状态；恢复时如果是 matched-but-follow-pending，要继续后续 Work，完成后再彻底停监控。

### 10. Goal 的 autoRounds 在真正开始本轮前就持久 +1；进程被杀重试会重复消耗轮数并提前暂停

**位置：** `data/GoalWorker.kt:21-40`、`data/Goal.kt:16`。

**会怎样出错：** Worker 先把 `autoRounds` 加一并落盘，再开始 `runBackgroundWork()`。如果刚加完就被系统杀掉，WorkManager 重跑时又加一；即使同一逻辑轮最终只是恢复断点，也会占两个自动轮次。连续几次系统回收后，用户会看到“连续自动续做已达 5 轮，请查看进展后点继续”，实际完成的轮数可能远少于 5。

**证据：**

```kotlin
val admitted = Goals.change(ctx, id) { g ->
    if (g?.id == goal.id && g.canContinue) g.copy(autoRounds = g.autoRounds + 1) else g
}
...
runBackgroundWork(...)
```

**严重程度：中。**

**建议的修法：** 给逻辑 round 持久化 round-id / in-progress 状态；进程恢复同一个 in-progress round 时不再 +1，只在新一轮真正 admission 时计数。

## 核查通过

- **Goal 重启恢复：** `KaviApp.onCreate()` 会调用 `Goals.recover()`；`Goals.schedule()` 使用 `enqueueUniqueWork(... KEEP)`，已有持久 Work 时不会再排第二份。
- **同一 Goal 的前后台抢占：** `GoalWorker` 有 `Goals.workers.putIfAbsent`，前台接管时先加 `Goals.foreground` 并取消/等待后台 worker；未发现稳定可复现的“双启动同一 Goal”路径。
- **范围内的正常异常收尾：** `BackgroundWork.kt:317-320` 在 `finally` 调 `session.releaseBrowser()`；硬进程死亡后浏览器/手机 mutex 都是进程内对象，会随进程消失，不会跨重启永久锁死。

## 存疑

### ToolSession 的浏览器关闭异常可能跳过手机锁释放（范围外依赖，未列为正式问题）

辅助位置：`tools/ToolSession.kt:277-284`。

`releaseBrowser()` 先 `BrowserLock.unlock()` 并关闭后台标签页，然后才进入 `NonCancellable` 块关闭 phone control / unlock phone mutex。如果 `BrowserLock.closeQuietly()` 内部关闭 WebView 时抛异常，后面的手机锁释放不会执行。浏览器锁已经放掉，但 phone mutex 可能留在当前进程，后续手机任务会等 120 秒后失败。

目前没有在调查范围内找到一个确定会让 `closeQuietly()` 抛错的输入，所以只列存疑。修法上应把 browser cleanup 和 phone cleanup 分成两个独立 `try/finally`，保证任一清理失败都不影响另一把锁。
