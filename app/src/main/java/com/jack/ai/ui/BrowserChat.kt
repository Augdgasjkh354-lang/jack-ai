package com.jack.ai.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.chat.Agent
import com.jack.ai.data.Conversation
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.SkillIds
import com.jack.ai.data.SkillTools
import com.jack.ai.data.Store
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.activeSkills
import com.jack.ai.data.allSkills
import com.jack.ai.data.backgroundEndpoint
import com.jack.ai.data.endpoint
import com.jack.ai.tools.BackgroundToolHost
import com.jack.ai.tools.ToolSession
import com.jack.ai.tools.ToolUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 浏览器里的 Kavi：在浏览器底部拉出来的小聊天框，问的就是正在看的这一页，也能顺手操作这一页。
 * 聊天不进对话列表（点「转到聊天」才带过去）；关掉 App 就没了。
 */
object BrowserChat {
    /** 一条消息：user 是用户说的，kavi 是回答；steps 是这次回答里做过的操作。 */
    data class Line(val role: String, val text: String, val steps: List<String> = emptyList(), val error: String? = null)

    /** 要用户确认的操作（下单、提交……）：界面弹窗，点了把结果交回来。 */
    class Ask(val title: String, val body: String, val action: String, val answer: CompletableDeferred<Boolean>)

    val lines = mutableStateListOf<Line>()
    /** 聊天框开着没有（放这里，离开浏览器再回来还开着）。 */
    var open by mutableStateOf(false)
    /** 不跟着浏览器页走：离开浏览器（切到别的 App）它接着做，做完发通知。 */
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    var busy by mutableStateOf(false)
    var progress by mutableStateOf<String?>(null)
    var asking by mutableStateOf<Ask?>(null)

    /** 发给模型的历史（不含系统提示）。 */
    private val history = mutableListOf<JsonObject>()
    /** 上次把哪一页的正文给了模型：换了页才再给。 */
    private var pageSent = ""
    private var job: Job? = null

    /** 历史最多留几条（太长了贵，也慢）。 */
    private const val MAX_HISTORY = 40

    /** App 不在眼前时发通知，点开回到浏览器、聊天框开着。 */
    private fun notifyUser(ctx: Context, title: String, text: String) {
        val i = android.content.Intent(ctx, BrowserActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        open = true
        com.jack.ai.data.Routines.notify(ctx, "browser-chat", title, text.take(300), open = i)
    }

    fun clear() {
        stop()
        lines.clear(); history.clear(); pageSent = ""
    }

    fun stop() {
        job?.cancel(); job = null
        asking?.answer?.complete(false); asking = null
        busy = false; progress = null
    }

    /** 整段聊天转成文字（转到主聊天用）。 */
    fun transcript(): String = lines.joinToString("\n\n") { (if (it.role == "user") "我：" else "Kavi：") + it.text }

    fun send(ctx: Context, text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        lines += Line("user", q)
        lines += Line("kavi", "")
        busy = true
        job = scope.launch {
            try {
                answer(ctx.applicationContext, q)
                if (!JackBrowser.userViewing) notifyUser(ctx.applicationContext, "浏览器里的 Kavi 做完了", lines.lastOrNull()?.text.orEmpty().ifBlank { "回来看看结果" })
            }
            catch (e: CancellationException) { patchLast { it.copy(text = it.text.ifBlank { "（停下了）" }) }; throw e }
            catch (e: Exception) { patchLast { it.copy(error = ToolSession.errorText(e)) } }
            finally { busy = false; progress = null; job = null }
        }
    }

    private fun patchLast(f: (Line) -> Line) {
        val i = lines.lastIndex
        if (i >= 0 && lines[i].role == "kavi") lines[i] = f(lines[i])
    }

    private suspend fun answer(ctx: Context, q: String) {
        val store = Store(ctx)
        val s = withContext(Dispatchers.IO) { store.loadSettings() }
        val ep = s.endpoint(s.chatModel.ifBlank { s.model })?.takeIf { it.apiKey.isNotBlank() } ?: s.backgroundEndpoint()
            ?: run { patchLast { it.copy(error = "还没有填 API Key，到设置里填好再来。") }; return }
        val active = s.activeSkills()
        val skills = active.filter { it.id == SkillIds.BROWSER || it.id == SkillIds.FLOWS || it.id == SkillIds.WEB }
            .ifEmpty { s.allSkills().filter { it.id == SkillIds.BROWSER } }
        val browserRules = s.allSkills().firstOrNull { it.id == SkillIds.BROWSER }?.instructions.orEmpty()
        val web = skills.any { it.id == SkillIds.WEB }
        val tools = JsonArray(listOfNotNull(SkillTools.BROWSER, SkillTools.WEB_SEARCH.takeIf { web }, SkillTools.READ_WEBPAGE.takeIf { web }))

        // 换了一页：把这一页的正文带上（只带一次，同一页接着问不重复发）
        val b = JackBrowser
        val url = b.address
        val page = if (url.isNotEmpty() && url != pageSent) {
            pageSent = url
            val body = runCatching { b.pageText(ctx) }.getOrNull().orEmpty().substringAfter("\n\n").take(8000)
            "【用户正在看的网页】${b.title}\n$url\n" + (if (body.isBlank()) "（读不到文字）" else "正文开头：\n$body") + "\n\n"
        } else ""
        history += buildJsonObject { put("role", "user"); put("content", page + q) }

        val system = "你是 Kavi，用户（Jack）的 AI 助手。用户正在 Jack 浏览器里看网页，在浏览器底部的小窗口里和你说话，窗口很小：" +
            "回答用中文，简短直接，不要开场白。问的是这一页的内容，就根据给你的网页正文回答，不够再用 browser 的 extract、read 读；" +
            "用户让你在这一页上做事（点、填、找、比价、存流程），就用 browser 工具做，用户看得到你在操作。" +
            (if (web) "要网页以外的信息再用 web_search。" else "") + "\n\n浏览器的用法：\n" + browserRules
        val messages = mutableListOf(buildJsonObject { put("role", "system"); put("content", system) }).apply { addAll(history) }

        val steps = mutableListOf<String>()
        val conv = Conversation(id = "browser-chat", title = "浏览器里的 Kavi")
        val session = ToolSession(BackgroundToolHost(ctx), s, conv, null, skills, object : ToolUi {
            override fun started(label: String) { progress = label }
            override fun finished(label: String, record: SearchRecord) {
                steps += label + (record.error?.let { "（出错）" } ?: "")
                patchLast { it.copy(steps = steps.toList()) }
            }
            override fun attach(file: File) { steps += "存了文件「${file.name}」"; patchLast { it.copy(steps = steps.toList()) } }
            override suspend fun confirm(title: String, body: String, action: String): Boolean {
                val d = CompletableDeferred<Boolean>()
                if (!JackBrowser.userViewing) notifyUser(ctx, "Kavi 需要你确认", title)
                withContext(Dispatchers.Main) { asking = Ask(title, body, action, d) }
                return try { d.await() } finally { withContext(Dispatchers.Main) { asking = null } }
            }
        })
        session.canSee = ep.vision
        val client = DeepSeekClient()
        val agent = Agent({ msgs, t -> client.stream(ep, msgs, ThinkLevel.OFF, s.temperature, t) })
        var last = 0L
        agent.run(
            messages = messages,
            tools = tools,
            maxRounds = { session.maxRounds },
            execute = { name, args -> session.execute(name, args) },
            onText = {
                val now = System.currentTimeMillis()
                if (now - last > 80) { last = now; val c = agent.content.toString(); patchLast { it.copy(text = c) } }
            },
            onUsage = { ev -> runCatching { store.addUsage(ep, ev.promptTokens, ev.cacheHit, ev.completionTokens) } },
            images = { session.takeImages() },
        )
        val reply = agent.lastRound.trim().ifBlank { agent.content.toString().trim() }
        patchLast { it.copy(text = agent.content.toString().trim().ifBlank { reply.ifBlank { "（没有回答）" } }) }
        // 留下这次的往来，太长就从头裁掉（只在用户消息那里裁，工具调用和结果不拆开）
        history.clear()
        history += messages.drop(1)
        while (history.size > MAX_HISTORY) {
            val next = history.drop(1).indexOfFirst { (it["role"] as? JsonPrimitive)?.content == "user" }
            if (next < 0) break
            repeat(next + 1) { history.removeAt(0) }
        }
    }
}
