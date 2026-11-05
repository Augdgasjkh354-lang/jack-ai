package com.jack.ai.bench

import android.content.Context
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.chat.PromptBuilder
import com.jack.ai.data.ChatMemory
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.DeepRecall
import com.jack.ai.data.Dreamer
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.MemoryOps
import com.jack.ai.data.MemoryReview
import com.jack.ai.data.MODEL_FLASH
import com.jack.ai.data.Project
import com.jack.ai.data.globalAllowed
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.Timeline
import com.jack.ai.data.activeSkills
import com.jack.ai.data.endpoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 只在手动传入 Key 时用真 DeepSeek 跑；平时分支检查无 Key 会跳过，绝不产出虚构分数。 */
@RunWith(RobolectricTestRunner::class)
class MemoryBenchTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dayMs = TimeUnit.DAYS.toMillis(1)
    private val maxTokens = 3_000_000L
    private val judgeHttp = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()

    private data class Question(
        val kind: String, val question: String, val expect: String, val clue: String, val mustNot: List<String>,
    )
    private data class Result(
        val question: Question, val answer: String, val score: Double, val why: String,
        val failure: String, val brought: String,
        val answerSpend: Spend = Spend(), val judgeSpend: Spend = Spend(),
    )
    /** 一段时间里的用量：输入、缓存命中、输出 token 和花费（元，App 的 Pricing 当场算好的）。 */
    private data class Spend(val prompt: Long = 0, val hit: Long = 0, val completion: Long = 0, val yuan: Double = 0.0) {
        operator fun plus(o: Spend) = Spend(prompt + o.prompt, hit + o.hit, completion + o.completion, yuan + o.yuan)
        operator fun minus(o: Spend) = Spend(prompt - o.prompt, hit - o.hit, completion - o.completion, yuan - o.yuan)
        val tokens get() = prompt + completion
        fun yuanText() = "¥" + "%.4f".format(Locale.US, yuan)
    }
    private fun spent(store: Store): Spend = store.loadUsage().fold(Spend()) { acc, d ->
        acc + Spend(d.prompt, d.cacheHit, d.completion, d.cost)
    }
    /** 顺手记、做梦（只有全考有）各花了多少。 */
    private class Stages { var chat = Spend(); var review = Spend(); var dream = Spend(); val dreamFailures = mutableListOf<String>() }

    /**
     * 按 App 的真实路径让 Kavi 回一句：常记、日记、近况、和这句话相关的深处记忆（DeepRecall 会把浮上来的记成「用过」，
     * 做梦时就能看到它们），隐私项目里不带全局记忆。不开工具。返回（回答，带进提示词的记忆）。
     */
    private suspend fun kaviReply(
        ctx: Context, store: Store, settings: Settings, client: DeepSeekClient,
        conv: Conversation, project: Project?, at: Long, maxTokens: Int,
    ): Pair<String, String> {
        val global = conv.globalAllowed(project)
        val query = conv.messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val hot = if (global) store.loadMemories() else emptyList()
        val deep = if (global) DeepRecall.note(ctx, settings, query, at) else ""
        val timeline = runCatching {
            Timeline.noteFor(ctx, settings, store.loadConversations(), store.loadProjects(), conv)
        }.getOrDefault("")
        val input = PromptBuilder.Input(
            settings = settings, conv = conv, project = project, memories = hot,
            skills = settings.activeSkills(), vision = false, sendReasoning = false,
            workMemory = ChatMemory.read(ChatMemory.workFile(ctx)),
            contextNote = timeline + deep, now = at,
        )
        val msgs = PromptBuilder.history(input, tools = null, flat = true)
        if (project == null) check(!msgs.joinToString("\n").contains("心理咨询")) { "普通对话提示词泄露私人项目" }
        val answer = client.complete(settings.endpoint(MODEL_FLASH)!!, msgs, maxTokens = maxTokens)
        return answer to (hot.joinToString("\n") { "- ${it.text}" } + "\n" + deep)
    }
    private data class Exam(val mode: String, val results: List<Result>, val tokens: Long, val yuan: Double, val report: String)

    private fun JsonObject.value(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()
    private fun JsonObject.items(key: String): List<JsonObject> =
        (this[key] as? JsonArray).orEmpty().map { it.jsonObject }
    private fun JsonObject.words(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("memory-bench/$name")) {
            "考场资源不存在：$name"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun fixture(name: String): JsonObject = json.parseToJsonElement(resource(name)).jsonObject

    /** 没有 Key 的分支检查也要验证剧本、题库与私密项目的基本结构。 */
    @Test
    fun fixturesHaveThirtyDaysAndQuestionCoverage() {
        val days = fixture("script.json").items("days")
        assertEquals(30, days.size)
        assertEquals(46, days.sumOf { it.items("conversations").size })
        assertEquals(1, days.flatMap { it.items("conversations") }
            .count { it.value("private") == "true" })
        assertTrue(fixtureQuestions().groupBy { it.kind }.size >= 9)
        assertTrue(fixture("memories.json").words("deep").any { "芒果" in it })
    }

    @Test(timeout = 5_400_000L)
    fun memoryBenchmark() = runBlocking {
        val key = System.getenv("DEEPSEEK_API_KEY")?.trim().orEmpty()
        assumeTrue("没有 DEEPSEEK_API_KEY，记忆考场未运行", key.isNotEmpty())
        // 和 Jack 的手机一样用简体中文：提示词里的 {lang} 跟系统语言走，英文环境下 Kavi 会用英文写记忆
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val selected = System.getenv("MEMORY_BENCH_MODE")?.ifBlank { "quick" } ?: "quick"
        require(selected in setOf("quick", "full", "both")) { "未知考场模式：$selected" }
        val questions = fixtureQuestions()
        val modes = if (selected == "both") listOf("full", "quick") else listOf(selected)
        val exams = mutableListOf<Exam>()
        for (mode in modes) exams += runMode(mode, key, questions, exams.sumOf { it.tokens })
        val report = buildString {
            appendLine("# 记忆考场报告（${when (selected) { "quick" -> "快考"; "full" -> "全考"; else -> "双模式对照" }}）")
            appendLine()
            appendLine("**总花费：¥${"%.4f".format(Locale.US, exams.sumOf { it.yuan })}**（${exams.sumOf { it.tokens }} token，含阅卷）")
            appendLine()
            appendLine("本地跑法：`DEEPSEEK_API_KEY=... ./gradlew :app:testDebugUnitTest --tests \"com.jack.ai.bench.MemoryBenchTest\"`")
            appendLine("切换模式：设置环境变量 `MEMORY_BENCH_MODE=quick|full|both`（默认 quick）。")
            appendLine("使用真实 DeepSeek Flash、真实 Store / MemoryReview / Dreamer / DeepRecall / PromptBuilder；阅卷温度 0。")
            appendLine()
            exams.forEach { append(it.report).appendLine() }
            if (exams.size == 2) {
                appendLine("## 同题对照")
                appendLine("| 题目 | 全考 | 快考 | 归因 |")
                appendLine("|---|---:|---:|---|")
                val full = exams.first { it.mode == "full" }.results
                val quick = exams.first { it.mode == "quick" }.results
                full.zip(quick).forEach { (a, b) ->
                    val cause = when {
                        a.score < 1 && b.score == 1.0 -> "记 / 整理环节"
                        a.score < 1 && b.score < 1 -> "使用环节（或两端同时存在问题）"
                        else -> "—"
                    }
                    appendLine("| ${cell(a.question.question)} | ${a.score} | ${b.score} | $cause |")
                }
                appendLine()
            }
            appendLine("合计 token：${exams.sumOf { it.tokens }}；估算费用：¥${"%.4f".format(Locale.US, exams.sumOf { it.yuan })}")
        }
        val cwd = File(System.getProperty("user.dir"))
        val appDir = if (File(cwd, "src/main").isDirectory) cwd else File(cwd, "app")
        val output = File(appDir, "build/reports/memory-bench.md")
        output.parentFile.mkdirs()
        output.writeText(report)
        println(report)
        System.err.println("记忆考场报告：${output.absolutePath}")
    }

    private fun fixtureQuestions(): List<Question> = json.parseToJsonElement(resource("questions.json"))
        .let { it as JsonArray }.map { v ->
            val q = v.jsonObject
            Question(q.value("kind"), q.value("question"), q.value("expect"), q.value("clue"), q.words("must_not"))
        }.also { require(it.size >= 20) { "题库至少要有 20 题" } }

    private fun reset(ctx: Context, key: String): Pair<Store, Settings> {
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = Store(ctx)
        val settings = Settings(
            apiKey = key, userName = "小林", model = MODEL_FLASH, thinking = ThinkLevel.OFF,
            memoryEnabled = true, dreamEnabled = true,
            proactive = false, webSearch = false,
        )
        store.saveSettings(settings)
        return store to settings
    }

    private fun used(store: Store): Long = store.loadUsage().sumOf { it.prompt + it.completion }
    private fun guard(store: Store, previous: Long) {
        check(previous + used(store) <= maxTokens) {
            "记忆考场 token 超过 300 万，停止请求（当前累计 ${previous + used(store)}）"
        }
    }

    private suspend fun runMode(
        mode: String, key: String, questions: List<Question>, previous: Long,
    ): Exam {
        val ctx = RuntimeEnvironment.getApplication()
        val (store, settings) = reset(ctx, key)
        val client = DeepSeekClient(store::addUsage)
        val base = System.currentTimeMillis() - dayMs * 31
        val stages = Stages()
        if (mode == "full") replay(ctx, store, settings, client, base, previous, stages)
        else seed(ctx, store, base)
        val records = mutableListOf<Result>()
        val examAt = base + dayMs * 31
        for ((index, q) in questions.withIndex()) {
            val conv = Conversation(title = "第31天·题${index + 1}", messages = listOf(
                ChatMessage(role = "user", content = q.question, createdAt = examAt + index * 1000L),
            ))
            val hot = store.loadMemories()
            val beforeAnswer = spent(store)
            // 答题走与普通对话相同的全局记忆与深处路径，不能夹带私人项目（kaviReply 里查）。
            val (answer, brought) = kaviReply(ctx, store, settings, client, conv, null, examAt + index * 1000L, 500)
            val answerSpend = spent(store) - beforeAnswer
            guard(store, previous)
            val (judgeScore, why) = judge(store, settings, q, answer)
            val judgeSpend = spent(store) - beforeAnswer - answerSpend
            guard(store, previous)
            val leaked = q.mustNot.any { answer.contains(it, ignoreCase = true) }
            val score = if (leaked) 0.0 else judgeScore
            val reason = if (leaked) "回答触及 must_not 禁区" else why
            val failure = classify(q, score, hot, MemoryOps.deep(ctx), brought)
            records += Result(q, answer, score, reason, failure, brought, answerSpend, judgeSpend)
            println("[记忆考场/$mode] ${index + 1}/${questions.size} ${q.kind}：$score（$reason）")
        }
        val tokenCount = used(store)
        val price = store.loadUsage().sumOf { it.cost }
        val hotText = store.loadMemories().joinToString("\n") { "- ${it.text}" }.ifBlank { "（空）" }
        val report = buildString {
            appendLine("## ${if (mode == "full") "全考（30 天真实回放）" else "快考（固定理想记忆）"}")
            appendLine()
            appendLine("总分：${"%.1f".format(Locale.US, records.sumOf { it.score })}/${records.size}")
            appendLine("token：$tokenCount（输入+输出）；估算费用：¥${"%.4f".format(Locale.US, price)}")
            appendLine()
            val answers = records.fold(Spend()) { a, r -> a + r.answerSpend }
            val judges = records.fold(Spend()) { a, r -> a + r.judgeSpend }
            appendLine("### 用量和花费")
            appendLine("| 阶段 | 输入 | 缓存命中 | 输出 | 花费 |")
            appendLine("|---|---:|---:|---:|---:|")
            fun row(name: String, sp: Spend) = appendLine("| $name | ${sp.prompt} | ${sp.hit} | ${sp.completion} | ${sp.yuanText()} |")
            if (mode == "full") { row("模拟聊天里 Kavi 的回答（30 天）", stages.chat); row("顺手记（30 天）", stages.review); row("做梦（30 天）", stages.dream) }
            row("答题（${records.size} 题）", answers)
            row("阅卷（考场自己的开销）", judges)
            if (mode == "full") {
                val daily = (stages.review + stages.dream).yuan / 30
                appendLine()
                appendLine("Kavi 自己每天的记忆开销（顺手记 + 做梦 ÷ 30 天）：约 ¥${"%.4f".format(Locale.US, daily)}")
                appendLine()
                appendLine("做梦失败：" + if (stages.dreamFailures.isEmpty()) "无" else "${stages.dreamFailures.size} 次（${stages.dreamFailures.joinToString("；")}）")
            }
            appendLine()
            appendLine("### 分类别")
            appendLine("| 类别 | 得分 | 满分 |")
            appendLine("|---|---:|---:|")
            records.groupBy { it.question.kind }.forEach { (kind, rs) ->
                appendLine("| $kind | ${"%.1f".format(Locale.US, rs.sumOf { it.score })} | ${rs.size} |")
            }
            appendLine()
            appendLine("### 逐题结果")
            appendLine("| 题目 | 得分 | 理由 | 失败原因 | 答题输入 / 命中 / 输出 | 答题花费 |")
            appendLine("|---|---:|---|---|---:|---:|")
            records.forEach { r ->
                val a = r.answerSpend
                appendLine("| ${cell(r.question.question)} | ${r.score} | ${cell(r.why)} | ${r.failure} | ${a.prompt} / ${a.hit} / ${a.completion} | ${a.yuanText()} |")
            }
            appendLine()
            appendLine("### 回答原文和实际带入的记忆")
            records.forEachIndexed { i, r ->
                appendLine("#### ${i + 1}. ${r.question.question}")
                appendLine("Kavi 回答：")
                appendLine(r.answer.lines().joinToString("\n") { "> " + it })
                appendLine()
                appendLine("提示词带入的常记 + 深处：")
                appendLine("```text")
                appendLine(r.brought)
                appendLine("```")
                appendLine()
            }
            appendLine("### 30 天后的记忆快照")
            appendLine("常记全文：")
            appendLine("```text")
            appendLine(hotText)
            appendLine("```")
            appendLine("深处条数：${MemoryOps.deep(ctx).size}")
            appendLine()
        }
        return Exam(mode, records, tokenCount, price, report)
    }

    private fun classify(
        q: Question, score: Double, hot: List<MemoryItem>, deep: List<MemoryItem>,
        brought: String,
    ): String {
        if (score == 1.0) return "—"
        if (q.kind == "守住别记" || q.kind == "守住隐私项目") return "守密失败"
        if (q.kind == "不乱编") return "乱编"
        val stored = (hot + deep).joinToString("\n") { it.text }
        if (q.clue.isNotBlank() && !stored.contains(q.clue, ignoreCase = true)) return "没记住"
        if (q.clue.isNotBlank() && !brought.contains(q.clue, ignoreCase = true)) return "记住了没带进提示词"
        return "带了但没用上"
    }

    private fun cell(text: String) = text.replace("|", "｜").replace("\n", " ")

    private fun seed(ctx: Context, store: Store, base: Long) {
        val fixed = fixture("memories.json")
        store.saveMemories(fixed.words("hot").map { MemoryItem(text = it, createdAt = base, source = "记忆考场固定状态") })
        MemoryOps.saveDeep(ctx, fixed.words("deep").map { MemoryItem(text = it, createdAt = base, source = "记忆考场固定状态") })
        val project = Project(name = "记忆考场私人项目", isolated = true)
        store.saveProjects(listOf(project))
        val p = fixed["private"]!!.jsonObject
        store.saveConversations(listOf(Conversation(
            title = p.value("title"), projectId = project.id, useGlobalMemory = false,
            messages = p.items("turns").flatMapIndexed { i, t ->
                listOf(
                    ChatMessage(role = "user", content = t.value("user"), createdAt = base + i * 2000L),
                    ChatMessage(role = "assistant", content = t.value("assistant"), createdAt = base + i * 2000L + 1),
                )
            },
        )))
    }

    private suspend fun replay(
        ctx: Context, store: Store, settings: Settings, client: DeepSeekClient,
        base: Long, previous: Long, stages: Stages,
    ) {
        val script = fixture("script.json")
        val days = script.items("days")
        check(days.size == 30) { "剧本不是 30 天" }
        val privateProject = Project(name = "记忆考场私人项目", isolated = true)
        store.saveProjects(listOf(privateProject))
        store.lastDreamAt = base - 1
        for ((dayIndex, day) in days.withIndex()) {
            check(day.value("day").toInt() == dayIndex + 1)
            for ((segmentIndex, segment) in day.items("conversations").withIndex()) {
                val isPrivate = segment.value("private") == "true"
                val project = privateProject.takeIf { isPrivate }
                var conv = Conversation(title = segment.value("title"), projectId = project?.id,
                    useGlobalMemory = !isPrivate)
                val turns = segment.items("turns")
                for ((turnIndex, turn) in turns.withIndex()) {
                    val at = base + dayIndex * dayMs + (segmentIndex + 1) * 3_600_000L + turnIndex * 60_000L
                    // 剧本只用小林说的话；Kavi 的回答现场生成（剧本里的 assistant 只是占位，不用）
                    conv = conv.copy(updatedAt = at,
                        messages = conv.messages + ChatMessage(role = "user", content = turn.value("user"), createdAt = at))
                    val beforeChat = spent(store)
                    val (reply, _) = kaviReply(ctx, store, settings, client, conv, project, at, 400)
                    stages.chat += spent(store) - beforeChat
                    guard(store, previous)
                    conv = conv.copy(messages = conv.messages + ChatMessage(role = "assistant", content = reply, createdAt = at + 1))
                    store.saveConversations(store.loadConversations().filterNot { it.id == conv.id } + conv)
                    val beforeReview = spent(store)
                    val reviewed = MemoryReview.review(ctx, store, conv, project, settings, client)
                    stages.review += spent(store) - beforeReview
                    if (reviewed != null) {
                        conv = conv.copy(memReviewedAt = reviewed.reviewedAt)
                        store.saveConversations(store.loadConversations().filterNot { it.id == conv.id } + conv)
                        guard(store, previous)
                    }
                }
            }
            // 每天的对话使用历史时间戳；梦的起止时钟也落在那一天。
            val beforeDream = spent(store)
            // 和 App 一样：这天做梦失败就跳过，下一次做梦会把没整理的接着整理（lastDreamAt 没往后挪）
            runCatching { Dreamer.dream(ctx, force = true, now = base + (dayIndex + 1) * dayMs - 1) }
                .onFailure { stages.dreamFailures += "第 ${dayIndex + 1} 天：${it.javaClass.simpleName}"; println("[记忆考场/全考] 第 ${dayIndex + 1} 天做梦失败：$it") }
            stages.dream += spent(store) - beforeDream
            guard(store, previous)
            println("[记忆考场/全考] 第 ${dayIndex + 1}/30 天完成，累计 ${used(store)} token")
        }
    }

    /** 阅卷请求单独设温度 0，完整记录用量到与 App 相同的 Store 账本。 */
    private fun judge(store: Store, settings: Settings, q: Question, answer: String): Pair<Double, String> {
        val ep = settings.endpoint(MODEL_FLASH)!!
        val user = buildJsonObject {
            put("question", q.question)
            put("expect", q.expect)
            put("must_not", JsonArray(q.mustNot.map { JsonPrimitive(it) }))
            put("answer", answer)
        }
        val body = buildJsonObject {
            put("model", ep.model)
            put("stream", false)
            put("temperature", 0)
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("max_tokens", 500)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", JsonArray(listOf(
                PromptBuilder.msg("system", resource("judge.md")),
                PromptBuilder.msg("user", user.toString()),
            )))
        }
        val request = Request.Builder()
            .url(ep.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${ep.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        judgeHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "阅卷请求失败 ${response.code}：${text.take(300)}" }
            val result = json.parseToJsonElement(text).jsonObject
            (result["usage"] as? JsonObject)?.let { usage ->
                store.addUsage(ep,
                    usage.value("prompt_tokens").toIntOrNull() ?: 0,
                    usage.value("prompt_cache_hit_tokens").toIntOrNull(),
                    usage.value("completion_tokens").toIntOrNull() ?: 0,
                    "记忆考场阅卷")
            }
            val choice = (result["choices"] as JsonArray).first().jsonObject
            val message = choice["message"]!!.jsonObject.value("content")
            val grade = json.parseToJsonElement(message.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject
            val score = (grade["score"] as? JsonPrimitive)?.doubleOrNull
                ?: error("阅卷缺少 score")
            check(score == 0.0 || score == 0.5 || score == 1.0) { "阅卷分值错误：$score" }
            return score to grade.value("why")
        }
    }
}
