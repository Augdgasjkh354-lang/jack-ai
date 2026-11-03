# Store / 持久化与并发写入审计

## 范围与结论

本次只调查，不改业务代码。已读取 `AGENTS.md`、`CLAUDE.md`，并逐文件扫描 `app/src/main` 下 314 个 Kotlin/Java 文件里的直接 `writeText` / `writeBytes` 调用，再沿调用链检查读取、并发和损坏后的行为。

结论：

- `Store.kt` 管理的列表型 JSON（尤其 `conversations.json`、`usage.json`、`tool_stats.json`）本身有进程内全局锁 + `AtomicFile`，写到一半被杀不会留下半截主文件；解析损坏时还会尝试 `.prev`，没有副本才返回空并在界面提示。
- 三个普通对话同时回答时，主对话列表由 `AppCore.ioSerial` 串行保存，`persist()` 延迟 300ms 后取当时最新的 `StateFlow`，没有发现“对话 A 保存把对话 B 的正文覆盖掉”的路径。
- 真正高风险集中在绕开 `Store.atomicWrite` 的持久状态：长期记忆的读改写不是事务、共享“做事经验”没有锁，以及多份记忆/模板/资料文件直接覆盖写。它们要么会并发丢更新，要么会在断电/进程被杀时留下半截文件，随后读取又把损坏当成空数据。

审计复现放在 `app/src/test/java/com/jack/ai/data/PersistenceAuditTest.kt`，全部标了 `@Ignore("audit: 复现")`，不会进入正常测试执行。

## 关键文件总表

| 文件 / 状态 | 谁在写 | 加锁 | 原子写入 | 坏了 / 并发后的后果 |
|---|---|---:|---:|---|
| `conversations.json` | `Store.saveConversations`；主界面经 `AppCore.persist` | 是，`Store.LOCK`；主界面另有 `ioSerial` | 是，`AtomicFile` | 中途被杀时主文件可恢复；解析坏时回 `.prev`，没有副本才清空并提示。未发现三个普通对话互相覆盖正文 |
| `memories.json` | `Store.saveMemories`；聊天后的 `reviewMemory` | 单次 load/save 各自有 `Store.LOCK` | 单次写是 | 文件不会半写；但“读→计算→整表保存”不在同一锁内，多个对话会互相覆盖新增/修改的长期记忆 |
| `usage.json` / `usage_recent.json` | `Store.addUsage` / `addPaidCall` | 是，整段读改写在 `Store.LOCK` | 是 | 三个回答同时记费也会串行累加；未发现丢计数路径 |
| `tool_stats.json` | `Store.addToolStat` | 是，整段读改写在 `Store.LOCK` | 是 | 多工具并发统计会串行累加 |
| SharedPreferences `settings` | `Store.saveSettings` | 无应用层 `LOCK` | 由 Android SharedPreferences 管理，不是本仓库直接文件写 | 本次没有发现三个回答会并发保存整份 Settings；其中 JSON 字段解析失败会退默认值 |
| `own_memories.json` | `OwnMemory` | 是 | 是，`AtomicFile` | 中途被杀可保旧版；本次未发现半写问题 |
| `工作记忆.md` / `项目记忆.md` / `记忆.md` | `ChatMemory.write`、聊天记忆回看、资料页操作 | 否 | 否，直接 `writeText` | 多对话会丢更新；被杀时可留下截断文本，尾部记忆静默消失 |
| `deep_memories.json` | `MemoryOps.saveDeep`、`DeepRecall`、做梦整理、手动升降记忆 | 单次方法有对象锁 | 否 | 半写后解析失败直接当空；并发“读→写”还会丢 `uses/lastUsedAt` 更新 |
| `memories_archive.json` | `MemoryOps.addToArchive` | 是 | 否 | 半写后归档读取为空，合并/删除前的历史记忆无法找回 |
| `memories_versions/YYYY-MM-DD.json` | `MemoryOps.snapshot` | 是 | 否 | 当天快照半写后 `version()` 返回 null，失去当天恢复点 |
| `library/<id>.txt` | `Store.saveLibraryText`；`KnowledgeFeature` 导入/记笔记 | 否（导入路径多数经 `ioSerial`，`addNote` 可直接调） | 否 | 被杀时正文可截断；`library.json` 元数据仍在，AI/用户会读到缺内容的资料 |
| `style_templates.json` | `StyleTemplates.save` | 是 | 否 | 半写后 `load` 直接返回空列表，用户保存的 Word/PPT/Excel 模板全部像“没存过” |
| `threads.json` | `Threads.save` | 是 | 否 | 半写后读取为空，挂着的 promise / unfinished / wondering 全部消失 |
| `autobiography.json` / `checkups.json` | `Autobiography` | 是 | 否 | 半写后分别退回空自传 / 空体检列表，连续性数据丢失 |
| `.appdata/<app>.json` | 小工具 JS 的 `jack.save` → `AppView.save` | 否 | 否 | 保存时被杀会留下半截 JSON；下次 `jack.load` 原样返回半截，小工具可能重置或报 JSON 解析错 |
| `timeline_state.json` | `Timeline.markSummarized` | 是 | 否 | 半写后摘要进度映射变空，旧对话会被当成“还没总结”再次处理 |
| `browser_stats.json` / `phone_stats.json` | `BrowserStats.appendTo` / `PhoneStats` | 是 | 否 | 半写后历史统计读成空，下一条记录会把“空 + 新记录”写回，旧统计永久丢失 |
| `office_stats.json` | `OfficeStats.record` | 是 | 否 | 半写后近 30 天 Office 质量统计归零 |
| `notifications.json` | `PhoneData.add` | 是 | 否 | 半写后最近三天通知历史归零，`phone_data` 查不到旧通知 |
| `site_notes.json` | `SiteNotes.record` | 是 | 否 | 半写后网站失败经验归零，后续 Work 会重新撞登录/封锁/限流 |
| `reading_log.json` | `Reader.add` | 是 | 否 | 半写后自由时间历史归零；本次预算实际由 `FreeTime` 积蓄状态控制，因此没有发现因此重复花预算的路径 |
| `injection_log.json` | `InjectionGuard.record` | 是 | 否 | 半写后注入检测历史归零 |
| 工作区 `.src/<file>.json` | `Workspace.saveSource` | 否 | 否 | 半写后 `source()` 返回 null；生成文件仍在，但后续编辑拿不到原始工具参数/底稿 |
| `facts.json` / Kavi 自我状态 / `runlogs.json` / TaskCheckpoint / `deepseek_files.json` | 各自模块 | 多数有模块锁或单任务串行 | 先写 `.tmp` 再 `renameTo` | 写临时文件时被杀通常保留旧目标；见“存疑”，没有按确认问题计入 |

## 已确认问题

### 1. 多个对话同时“回看记忆”会互相覆盖全局长期记忆

**严重程度：高（丢数据）**

**位置：**
- `app/src/main/java/com/jack/ai/ChatViewModel.kt:957-987`
- `app/src/main/java/com/jack/ai/data/Store.kt:109-110`

**会怎样出错：**

两个或三个对话在相近时间都达到记忆回看条件（`MemoryReview.EVERY = 3`）时，每个对话都会启动自己的 IO coroutine。它们可能先后从同一版 `memories.json` 读出旧列表，各自加/改不同记忆，然后再整表 `saveMemories(next)`。

`Store.saveMemories` 的锁只保护“这一次完整写入”，没有把“读取旧版 → 应用 ops → 保存新版”包成同一个事务。因此最后保存的对话会把先保存的对话新增/修改内容覆盖掉。文件仍是合法 JSON，不会触发损坏恢复，也不会提示用户。

**证据：**

```kotlin
val live = store.loadMemories()
val (next, d) = MemoryReview.applyItems(live, ops, budget, ...)
...
store.saveMemories(next)
```

而 `Store` 只是：

```kotlin
fun loadMemories() = load("memories.json", MemoryItem.serializer())
fun saveMemories(list: List<MemoryItem>) = save("memories.json", MemoryItem.serializer(), list)
```

已加忽略测试：`PersistenceAuditTest.globalMemoryStaleSnapshotsLoseOneConversationUpdate`。

**建议的修法：** 给 `Store` 增加“在最新磁盘列表上做 transform 并原子保存”的事务式更新接口；聊天回看把 ops 在这个锁内重新应用到最新列表，不要在锁外算完整快照再整表覆盖。

---

### 2. 多个 Work 对话会互相覆盖共享“做事经验”

**严重程度：高（丢数据）**

**位置：**
- `app/src/main/java/com/jack/ai/ChatViewModel.kt:961-980`
- `app/src/main/java/com/jack/ai/data/ChatMemory.kt:30, 74-80`

**会怎样出错：**

做任务时所有对话共享 `工作记忆.md`。两个 Work 对话同时回看时，都先 `current()` 读取共享文件，再各自应用 ops，最后 `ChatMemory.write(workFile, next)` 整份覆盖。没有跨对话锁，也没有版本检查。

例如 A 学到“交付前先跑完整测试”，B 同时学到“报告用中文”，两边都从旧版出发；B 后写时会把 A 刚写进去的经验抹掉。三个对话同时回答时同理。

**证据：**

```kotlin
if (work) {
    val (next, d) = MemoryReview.apply(current(), ops, budget)
    ...
    if (done.isNotEmpty()) ChatMemory.write(workFile, next)
}
```

```kotlin
fun write(file: File, items: List<String>) {
    ...
    file.writeText(render(titleOf(file), items))
}
```

已加忽略测试：`PersistenceAuditTest.sharedWorkMemoryStaleSnapshotsLoseOneConversationUpdate`。

**建议的修法：** 对共享工作记忆做按文件串行的读改写事务（Mutex/锁 + 重新读取最新内容）；写盘同时换成 `AtomicFile`。

---

### 3. 对话 / 项目 / 工作记忆直接覆盖写，被杀时会静默截断

**严重程度：高（丢数据）**

**位置：** `app/src/main/java/com/jack/ai/data/ChatMemory.kt:30, 45-51, 74-80`

**会怎样出错：**

`File.writeText` 会先截断旧文件再写新内容。App 在写到一半时被系统杀掉、手机掉电或进程崩溃，就可能留下只有前半段的 Markdown。

这里没有 JSON 校验；`ChatMemory.read` 只要 `readText` 成功就把半截当正常文本，`items()` 继续解析。用户看到的结果是尾部记忆直接消失，后续再保存会把这个截断版本正式覆盖下去。影响 `记忆.md`、`项目记忆.md`、`工作记忆.md`。

**证据：**

```kotlin
fun read(file: File): String =
    runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")

...
file.writeText(render(titleOf(file), items))
```

**建议的修法：** 所有 ChatMemory 文件改用 `AtomicFile`（或同目录临时文件 + fsync + 原子 rename），读改写再配合按文件锁。

---

### 4. `deep_memories.json` 半写后会被当成“没有深层记忆”

**严重程度：高（丢数据）**

**位置：** `app/src/main/java/com/jack/ai/data/MemoryOps.kt:188-195`

**会怎样出错：**

深层记忆保存用直接 `writeText`。中途被杀后 JSON 可能只剩半截。下次 `deep()` 解码失败后直接 `getOrDefault(emptyList())`，没有保留坏文件、没有副本、没有用户提示。

后续聊天的 DeepRecall、做梦整理、手动升降记忆都会把它当成真的空列表；一旦再保存，新空/小列表会覆盖原损坏文件，深层长期记忆永久丢失。

**证据：**

```kotlin
fun deep(ctx: Context): List<MemoryItem> = synchronized(lock) {
    runCatching { json.decodeFromString(..., deepFile(ctx).readText()) }
        .getOrDefault(emptyList())
}

fun saveDeep(...) = synchronized(lock) {
    runCatching { deepFile(ctx).writeText(json.encodeToString(...)) }
}
```

**建议的修法：** 复用 `Store` 的 `AtomicFile + .prev + corrupt 留档`模式；解析失败不要直接把“坏”当“空”。

---

### 5. 资料库正文直接写，断电会留下“元数据还在、正文被截断”的文档

**严重程度：高（丢数据）**

**位置：**
- `app/src/main/java/com/jack/ai/data/Store.kt:116-119`
- `app/src/main/java/com/jack/ai/features/KnowledgeFeature.kt:38, 54`

**会怎样出错：**

资料库索引 `library.json` 走 AtomicFile，但每份正文 `library/<id>.txt` 是直接 `writeText`。导入文件或“存进资料库”时，如果进程在正文覆盖中途死亡，正文会只剩前半截，元数据仍可能保留原来的名称/字符数。

下次资料检索会正常找到这份资料，却只能索引到截断后的内容；用户会看到资料还在列表里，Kavi 却漏掉后半段内容。

**证据：**

```kotlin
fun libraryText(id: String): String =
    runCatching { File(File(dir, "library"), "$id.txt").readText() }.getOrDefault("")

fun saveLibraryText(id: String, text: String) {
    File(dir, "library").mkdirs()
    File(File(dir, "library"), "$id.txt").writeText(text)
}
```

**建议的修法：** 每份资料正文也用 `AtomicFile`；正文成功后再提交对应 `library.json` 元数据，避免两份状态不同步。

---

### 6. `style_templates.json` 半写后所有自定义 Office 模板静默消失

**严重程度：高（丢数据）**

**位置：** `app/src/main/java/com/jack/ai/data/StyleTemplates.kt:55-62`

**会怎样出错：**

保存模板时虽然有对象锁，但仍直接覆盖原文件。写到一半被杀会留下非法 JSON；下次 `load` 用 `getOrDefault(emptyList())` 把损坏解释成“用户没有模板”。

用户保存的 PPT/Word/Excel 风格、默认模板会全部从界面和生成逻辑里消失；下一次保存新模板还会把损坏文件覆盖掉。

**证据：**

```kotlin
fun load(ctx: Context): List<Template> = synchronized(lock) {
    runCatching { json.decodeFromString(..., file(ctx).readText()) }
        .getOrDefault(emptyList())
}

fun save(ctx: Context, list: List<Template>) = synchronized(lock) {
    file(ctx).writeText(json.encodeToString(..., list))
}
```

已加忽略测试：`PersistenceAuditTest.halfWrittenStyleTemplatesSilentlyLoadAsEmpty`。

**建议的修法：** 用 `AtomicFile`，并在解析失败时保留损坏文件 / 尝试最近一份副本，不要静默返回空。

---

### 7. 小工具 `.appdata/*.json` 直接覆盖，用户在小工具里保存的数据可被半写破坏

**严重程度：高（丢数据）**

**位置：** `app/src/main/java/com/jack/ai/ui/AppView.kt:61-78`

**会怎样出错：**

小工具调用 `jack.save(json)` 时直接 `writeText` 到 `.appdata/<app>.json`。如果保存过程中 App 被杀，文件可只剩 JSON 前缀。

`jack.load()` 不解析、不校验，下一次会把半截字符串原样交给网页。使用 `JSON.parse(jack.load())` 的小工具会报错；如果网页自己遇错后写默认值，旧状态会永久被重置。

**证据：**

```kotlin
fun save(json: String) {
    if (json.length > 1_000_000) return
    runCatching { dataFile(app).apply { parentFile?.mkdirs() }.writeText(json) }
}

fun load(): String =
    runCatching { dataFile(app).takeIf { it.exists() }?.readText() }
        .getOrNull().orEmpty()
```

**建议的修法：** `jack.save` 改为 AtomicFile；提交前至少验证传入字符串是完整 JSON，并保留上一版。

---

### 8. `threads.json` 半写后 Kavi 会忘掉所有挂着的承诺 / 未完事项

**严重程度：高（丢数据）**

**位置：** `app/src/main/java/com/jack/ai/data/Threads.kt:43-50`

**会怎样出错：**

`Threads.save` 有对象锁，但直接覆盖 JSON。半写后，`load` 解码失败返回空列表。原来状态为 `open` 的 promise、unfinished、wondering、looking_forward 全部消失，Kavi 后续不再带这些未完事项。

**证据：**

```kotlin
fun load(ctx: Context): List<OpenThread> = synchronized(lock) {
    runCatching { json.decodeFromString(..., file(ctx).readText()) }
        .getOrDefault(emptyList())
}

...
runCatching { file(ctx).writeText(json.encodeToString(..., keep)) }
```

**建议的修法：** 改用 AtomicFile，并保留上一版；“损坏”与“确实为空”要分开处理。

---

### 9. 自传 / 自我体检半写后会被静默重置

**严重程度：高（丢数据）**

**位置：** `app/src/main/java/com/jack/ai/data/Autobiography.kt:51-59, 120-121, 155-156`

**会怎样出错：**

`autobiography.json` 和 `checkups.json` 都在模块锁里直接 `writeText`。进程在覆盖过程中被杀后，读取端分别退回 `Autobiography()` 和 `emptyList()`。

结果是已有自传、共享经历和自我体检历史像从未存在过；没有坏文件留档或用户提示。

**证据：**

```kotlin
runCatching { json.decodeFromString(..., bioFile(ctx).readText()) }
    .getOrDefault(Autobiography())

...
bioFile(ctx).writeText(...)
checkFile(ctx).writeText(...)
```

**建议的修法：** 两个 JSON 都改成 AtomicFile；解析失败时保留损坏原文，并尝试上一版。

---

### 10. 多个对话同时召回深层记忆，会丢 `uses / lastUsedAt` 更新

**严重程度：中（结果错）**

**位置：**
- `app/src/main/java/com/jack/ai/data/DeepRecall.kt:109-111`
- `app/src/main/java/com/jack/ai/data/MemoryOps.kt:190-195`

**会怎样出错：**

两个对话同时命中不同的深层记忆时，各自先 `MemoryOps.deep(ctx)` 取整表，再 `saveDeep(...map {...})` 整表覆盖。虽然 `deep()` 和 `saveDeep()` 各自都有同一个对象锁，但锁在两次调用之间已经释放。

后写的快照会覆盖先写的使用次数/最后使用时间。记忆文字通常还在，但做梦时基于这些计数做升降/整理的判断会不准确。

**证据：**

```kotlin
val ids = userHits.map { it.id }.toSet()
MemoryOps.saveDeep(
    ctx,
    MemoryOps.deep(ctx).map {
        if (it.id in ids) it.copy(lastUsedAt = now, uses = it.uses + 1) else it
    }
)
```

**建议的修法：** 提供 `MemoryOps.updateDeep { latest -> ... }`，把读取最新列表、更新计数、原子写回放在同一个锁内。

---

### 11. 记忆归档和每日版本不是原子写，主记忆还在但恢复链会断

**严重程度：中（结果错 / 恢复能力丢失）**

**位置：** `app/src/main/java/com/jack/ai/data/MemoryOps.kt:202-220, 227-228`

**会怎样出错：**

`memories_archive.json` 保存被合并/删除的旧记忆；`memories_versions/YYYY-MM-DD.json` 保存修改前快照。两者都直接 `writeText`。

归档半写后 `archive()` 返回空，历史条目找不回；当天版本半写后 `version(day)` 返回 null，而且因为文件已经存在，同一天后续 `snapshot` 不会重写它。主 `memories.json` 仍在，所以不会立刻清空当前记忆，但用户失去回退/追溯能力。

**证据：**

```kotlin
archiveFile(ctx).writeText(...)
...
if (!f.exists()) f.writeText(...)
...
fun version(...) =
    runCatching { json.decodeFromString(..., File(...).readText()) }.getOrNull()
```

**建议的修法：** 归档和版本文件同样走 AtomicFile；版本文件写成功后再让“已存在”成为跳过条件。

---

### 12. `timeline_state.json` 半写会让旧对话再次被当成未总结

**严重程度：中（结果错）**

**位置：** `app/src/main/java/com/jack/ai/data/Timeline.kt:68-74, 223`

**会怎样出错：**

该文件记录“每个对话摘要做到哪条消息”。半写后解析失败，`summarizedUpTo` 会返回空映射。之后后台会把已经总结过的消息重新当成 fresh 内容，再次写经历摘要。

用户可能看到重复经历、时间线内容膨胀；不会直接闪退。

**证据：**

```kotlin
val m = summarizedUpTo(ctx) + (convId to at)
runCatching {
    stateFile(ctx).writeText(json.encodeToString(..., m))
}
```

**建议的修法：** 状态文件改 AtomicFile；损坏时保留上一版，而不是直接回到“全部未总结”。

---

### 13. 多份统计 / 通知 / 经验日志半写后会“整份归零”，下一次记录把旧历史永久覆盖

**严重程度：中（结果错）**

**位置：**
- `data/BrowserStats.kt:40-47`（同时供 `phone_stats.json` 使用）
- `data/OfficeStats.kt:31-37`
- `data/PhoneData.kt:63-72`
- `data/SiteNotes.kt:39-59`
- `data/Reader.kt:67-72`
- `data/InjectionGuard.kt:121-128`

**会怎样出错：**

这些模块都是“解析失败 → 空列表”加“直接覆盖写”。如果一次保存被杀，下一次读取会把损坏文件当空；再来一条新记录时，代码会保存“空列表 + 新记录”，于是旧历史从临时不可读变成永久丢失。

用户可见后果包括：浏览器/手机成功率统计归零、Office 质量统计归零、最近通知查不到、网站失败经验忘记、自由时间历史丢失、注入检测记录归零。这里没有发现因此突破自由时间预算的路径：预算由 `FreeTime` 的积蓄状态控制，不靠 `reading_log.json`。

**证据（共同模式）：**

```kotlin
val list = runCatching { json.decodeFromString(..., file.readText()) }
    .getOrDefault(emptyList())
file.writeText(json.encodeToString(..., list + newItem))
```

**建议的修法：** 这些小 JSON 可以统一复用一个轻量 AtomicFile helper；至少不要把“解析失败”和“真的没有记录”合成同一个空列表状态。

---

### 14. 生成文件的 `.src/*.json` 底稿半写后，后续编辑会失去原始生成参数

**严重程度：中（结果错）**

**位置：** `app/src/main/java/com/jack/ai/data/Workspace.kt:74-86`

**会怎样出错：**

Word/PDF/PPT/Excel/小工具生成后会把工具名和参数写进 `.src/<文件名>.json`。这是后续“读底稿再改”的来源。保存时直接 `writeText`；半写后 `source()` 解析失败返回 null。

生成出来的 Office 文件本身还在，但下一次编辑拿不到原始 Markdown/HTML/结构化参数，只能走文件抽取或其他退路，可能丢图表/结构信息，导致修改结果和原稿不一致。

**证据：**

```kotlin
fun saveSource(file: File, tool: String, args: JsonObject) {
    val f = sourceFile(file)
    f.parentFile?.mkdirs()
    f.writeText(buildJsonObject { ... }.toString())
}

fun source(file: File): Pair<String, JsonObject>? =
    runCatching { Json.parseToJsonElement(sourceFile(file).readText())... }.getOrNull()
```

**建议的修法：** 底稿 JSON 用 AtomicFile；生成文件交付前确保底稿提交成功。

## 存疑

### A. 多处“临时文件 + `renameTo`”比直接覆盖安全，但错误处理不一致

`Facts.kt`、`KaviSelf.kt`、`RunLog.kt`、`TaskCheckpoint.kt`、`DeepSeekFiles.kt` 等先写 `.tmp` 再 `renameTo`。进程死在写临时文件阶段时，旧目标仍然在，因此本次没有按“半写损坏”列成问题。

其中部分位置没有检查 `renameTo` 的返回值，另一些在第一次 rename 失败后会先删旧目标再重试。真实设备上同目录 rename 通常能工作；目前没有仓库内证据表明它经常失败，所以只列存疑，不计入问题数。后续若统一持久化层，建议一起换成 `AtomicFile`，避免每个模块自己实现不同版本。

### B. Settings 没发现本次关注的直接文件写风险

`Store.saveSettings` 使用 Android `SharedPreferences.Editor.apply()`，不是仓库里的 `writeText/writeBytes`。本次调用链没有发现“最多三个回答同时各拿一份旧 Settings 再整份保存”的路径，因此不报并发覆盖问题。

部分设置值本身是 JSON 字符串（如 presets/providers/mails/mcpServers）；读取失败会回默认/空列表。这意味着若 SharedPreferences 底层文件因外部原因损坏，体验会像设置丢失，但本次没有找到应用代码直接半写该文件的证据。

## 已验证没有发现问题的重点路径

`Store.atomicWrite`（`Store.kt:63-77`）用 `AtomicFile.startWrite / finishWrite / failWrite`；`loadUnlocked`（`Store.kt:38-57`）通过 `AtomicFile.readFully` 读取，JSON 解码失败会留 `.corrupt-时间` 并尝试 `.prev`。因此 `conversations.json`、`projects.json`、`memories.json` 等通过 Store 正常保存时，不会因“写到一半被杀”留下半截主文件。

`Store.addUsage`（`Store.kt:221+`）和 `Store.addToolStat`（`Store.kt:208-215`）把完整读改写都放在全局 `LOCK` 内，再 AtomicFile 落盘；多个回答/工具同时记账统计时会串行累加。

主界面保存对话的 `AppCore.persist`（`AppCore.kt:84-95`）用单一 `ioSerial`，300ms 内多次请求合并，真正写盘时读取当刻最新 `state.value.conversations`。结合每个对话自己的 reply job 串行、不同对话可并行的设计，本次没有找到三个普通对话同时回答导致 `conversations.json` 正文互相覆盖的真实路径。
