package com.jack.ai.tools

import android.content.Context
import android.net.Uri
import com.jack.ai.api.DeepSeekClient
import com.jack.ai.chat.Agent
import com.jack.ai.chat.PromptBuilder
import com.jack.ai.data.Attachment
import com.jack.ai.data.Attachments
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.GeneratedFile
import com.jack.ai.data.InboxItem
import com.jack.ai.data.RemoteClient
import com.jack.ai.data.RemoteFile
import com.jack.ai.data.RemoteMessage
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.ThinkStep
import com.jack.ai.data.activeSkills
import com.jack.ai.data.endpoint
import com.jack.ai.data.globalAllowed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 回一条从站点来的消息（用户在 iPhone、电脑上问的）：和手机上的 Chat 一样，记忆、文件、邮件、浏览器、技能都能用。
 * 每个站点对话对应 Kavi 的一个对话（id 是 remote-站点对话 id），存在 remote_convs.json；每轮答完再抄一份进收件箱，App 里能看到「📱」对话。
 * 回答边写边推到站点（一秒一次）；要确认的事在网页上问（十分钟没回就算不同意）；生成的文件传到站点。
 * 「操作其他 App」要用户在设置里单独允许远程用。
 */
object RemoteChat {
    /** 等用户在网页上回答的确认：id → 结果。RemoteService 取到答案时填上。 */
    val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private const val CONFIRM_WAIT_MS = 10 * 60_000L

    fun answered(id: String, ok: Boolean) { pending.remove(id)?.complete(ok) }

    suspend fun reply(ctx: Context, client: RemoteClient, m: RemoteMessage) = coroutineScope {
        val host = BackgroundToolHost(ctx)
        val store = host.store
        val s = store.loadSettings()
        val steps = java.util.concurrent.CopyOnWriteArrayList<String>()
        val notices = java.util.concurrent.CopyOnWriteArrayList<String>()
        val made = java.util.concurrent.CopyOnWriteArrayList<File>()
        // 远程问用 Chat 的模型和思考设置
        val model = if (s.activeWork) s.chatModel.ifBlank { s.model } else s.model
        val level = if (s.activeWork) s.chatThinking ?: s.thinking else s.thinking
        val ep = s.endpoint(model)?.takeIf { it.apiKey.isNotBlank() }
        if (ep == null) {
            client.reply(m.id, "手机上的 Kavi 还没配好模型的 Key，到手机的 设置 里填好再来。", emptyList(), emptyList(), "error")
            return@coroutineScope
        }
        val convId = "remote-" + m.conv
        val stored = store.loadRemoteConvs().firstOrNull { it.id == convId }
        var conv = stored ?: Conversation(id = convId, title = m.text.replace(Regex("\\s+"), " ").take(30).ifBlank { "远程对话" })
        val wsDir = com.jack.ai.data.Workspace.dir(ctx, com.jack.ai.data.Workspace.key(conv))

        // 用户传的文件：取下来，和手机上发图、发文件一样处理
        val atts = m.files.mapNotNull { f ->
            runCatching {
                // 文件名是网页端传来的：先清洗，免得 ../ 把文件写到 remote_in 之外（比如 shared_prefs）
                val local = client.download(f, File(ctx.cacheDir, "remote_in/${System.nanoTime()}/${com.jack.ai.data.Workspace.safeName(f.name)}"))
                val image = f.type.startsWith("image/") || Regex("\\.(png|jpe?g|gif|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(f.name)
                if (image) Attachments.importImage(ctx, Uri.fromFile(local)) else Attachments.importFile(ctx, Uri.fromFile(local), keepOriginal = true)
            }.onFailure { notices += "文件「${f.name}」没取到：${it.message}" }.getOrNull()
        }
        val user = ChatMessage(role = "user", content = m.text, attachments = atts)
        conv = conv.copy(messages = conv.messages + user, updatedAt = System.currentTimeMillis())
        saveConv(store, conv)

        val skills = s.activeSkills()
        val project: com.jack.ai.data.Project? = null
        fun tools(unlocked: Set<String>) = PromptBuilder.toolSet(s, skills, conv, project, unlocked)?.let { all ->
            // 「操作其他 App」远程用要用户单独允许
            if (s.remotePhone) all else JsonArray(all.filter { t -> toolName(t) != "phone" })
        }
        val toolSet = tools(conv.skillTools)
        val thinking = level.resolve(m.text, false, atts.isNotEmpty())
        val thinkingOn = ep.deepseek && thinking.effort != null
        val memories = if (s.memoryEnabled && conv.globalAllowed(project)) store.loadMemories() else emptyList()
        val note = runCatching { ContextNote.build(ctx, store, conv, skills.map { it.id }.toSet()) }.getOrDefault("") +
            (if (s.memoryEnabled) runCatching { com.jack.ai.data.DeepRecall.note(ctx, s, m.text) }.getOrDefault("") else "") +
            "【Remote】{user} is asking from another device (iPhone or computer) through the Kavi remote site, not on this phone. " +
            "Files you create are sent to that device. Anything that needs confirmation is asked on that device." +
            (if (s.remotePhone) "" else " Operating other apps on the phone is turned off for remote use.") + "\n"
        val input = PromptBuilder.Input(s, conv, project, memories, skills, vision = ep.vision, sendReasoning = thinkingOn,
            journal = com.jack.ai.data.Dreamer.recentJournals(store.loadDreams()), lately = com.jack.ai.data.FreeTime.latelyNote(ctx),
            contextNote = com.jack.ai.data.Persona.fill(note, s), appVersion = com.jack.ai.data.appVersion(ctx))
        val history = PromptBuilder.history(input, toolSet)

        val session = ToolSession(host, s, conv, project, skills, object : ToolUi {
            override fun started(label: String) { steps += label }
            override fun finished(label: String, record: SearchRecord) {}
            override fun attach(file: File) { if (made.none { it.absolutePath == file.absolutePath }) made += file }
            override fun notice(text: String) { notices += text }
            override fun progress(text: String) { steps += text }
            override suspend fun confirm(title: String, body: String, action: String): Boolean {
                val id = withContext(Dispatchers.IO) { client.ask(m.id, title, body, action) }
                val wait = CompletableDeferred<Boolean>().also { pending[id] = it }
                steps += "等你在这边确认：$title"
                val ok = withTimeoutOrNull(CONFIRM_WAIT_MS) { wait.await() }
                if (ok == null) { pending.remove(id); runCatching { withContext(Dispatchers.IO) { client.cancelAsk(id) } } }
                return ok == true
            }
        })
        session.remote = true
        session.canSee = ep.vision
        session.modelName = model
        session.unlocked.addAll(conv.skillTools)

        val client2 = DeepSeekClient()
        val agent = Agent({ msgs, t -> client2.stream(ep, msgs, thinking, s.temperature, t) }, sendReasoning = thinkingOn, splitNotes = true)
        fun allSteps() = agent.notes.map { it.text } + steps
        // 一秒推一次（有变化才推）
        val pusher = launch(Dispatchers.IO) {
            var last = ""
            while (isActive) {
                delay(1000)
                val text = agent.content.toString()
                val st = allSteps()
                val sig = text.length.toString() + "|" + st.size
                if (sig != last) { last = sig; runCatching { client.reply(m.id, text, st, emptyList(), "running") } }
            }
        }
        var error: String? = null
        var stopped = false
        try {
            withContext(Dispatchers.IO) {
                agent.run(
                    messages = history,
                    tools = toolSet,
                    maxRounds = { session.maxRounds },
                    maxToolCalls = { session.maxRounds },
                    execute = { name, args -> session.execute(name, args) },
                    images = { session.takeImages() },
                    retool = { tools(session.unlocked.toSet()) },
                    onUsage = { ev -> store.addUsage(ep, ev.promptTokens, ev.cacheHit, ev.completionTokens, label = "远程") },
                )
            }
        } catch (e: CancellationException) {
            stopped = true
        } catch (e: Exception) {
            error = ToolSession.errorText(e)
        } finally {
            pusher.cancel()
            session.releaseBrowser()
        }
        // 收尾：存对话、传文件、写最后一版回答（被停下也要写完）
        withContext(NonCancellable + Dispatchers.IO) {
            val uploaded = made.mapNotNull { f -> runCatching { client.upload(f, ToolSession.mimeOf(f.name)) }.getOrNull() }
            val body = agent.content.toString().ifBlank { agent.lastRound } +
                (if (stopped) "\n\n（已停止）" else "") +
                (error?.let { "\n\n出错了：$it" } ?: "") +
                (if (notices.isNotEmpty()) "\n\n" + notices.joinToString("\n") { "ℹ️ $it" } else "")
            runCatching { client.reply(m.id, body.ifBlank { "（没有生成内容）" }, allSteps(), uploaded, if (error != null && agent.content.isBlank()) "error" else "done") }
            val files = made.map { GeneratedFile(it.name, it.absolutePath, ToolSession.mimeOf(it.name), it.length()) }
            val answer = ChatMessage(role = "assistant", content = body, reasoning = agent.reasoning.toString(), model = model,
                trail = agent.trail.toList(), files = files, error = error, steps = allSteps().map { ThinkStep("note", it) },
                appVersion = com.jack.ai.data.appVersion(ctx))
            val latest = store.loadRemoteConvs().firstOrNull { it.id == convId } ?: conv
            saveConv(store, latest.copy(messages = latest.messages + answer, skillTools = latest.skillTools + session.unlocked, updatedAt = System.currentTimeMillis()))
            // App 里也能看到这一轮
            store.addInbox(InboxItem(routineId = "remote", convId = convId, title = conv.title, prompt = m.text, result = body, files = files, remote = true))
        }
    }

    private fun saveConv(store: com.jack.ai.data.Store, c: Conversation) = synchronized(this) {
        val list = store.loadRemoteConvs()
        store.saveRemoteConvs(if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c else it } else list + c)
    }

    private fun toolName(t: kotlinx.serialization.json.JsonElement): String =
        (((t as? JsonObject)?.get("function") as? JsonObject)?.get("name") as? JsonPrimitive)?.content.orEmpty()
}
