package com.jack.ai.data.workflow

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jack.ai.data.Conversation
import com.jack.ai.data.GeneratedFile
import com.jack.ai.data.InboxItem
import com.jack.ai.data.Routines
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import com.jack.ai.data.Workspace
import com.jack.ai.data.allSkills
import com.jack.ai.data.backgroundEndpoint
import com.jack.ai.data.packs.PackHost
import com.jack.ai.data.packs.PackManifest
import com.jack.ai.data.packs.PackRuntime
import com.jack.ai.data.packs.PackToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 工作流的存取、定时、在后台跑。
 * 存在 files/workflows.json；每个工作流最近 30 次的运行记录在 files/workflow_runs/<id>.json。
 * 定时用和定时任务一样的闹钟（到点由 AlarmReceiver 叫醒），真正跑在 WorkManager 里，App 被杀了也能起来。
 */
object Workflows {
    const val ACTION = "com.jack.ai.WORKFLOW"
    private const val KEEP_RUNS = 30
    private val LOCK = Any()

    private val _all = MutableStateFlow<List<Workflow>?>(null)
    /** 界面看的：工作流列表（第一次读时从文件加载）。 */
    fun flow(ctx: Context): StateFlow<List<Workflow>?> { if (_all.value == null) _all.value = load(ctx); return _all.asStateFlow() }

    /** 正在跑的工作流 id（界面上显示转圈）。 */
    val running = MutableStateFlow<Set<String>>(emptySet())

    private fun file(ctx: Context) = File(ctx.filesDir, "workflows.json")
    private fun runsFile(ctx: Context, id: String) = File(ctx.filesDir, "workflow_runs/$id.json")
    fun filesDir(ctx: Context, id: String) = Workspace.dir(ctx, convId(id))
    fun convId(id: String) = "workflow_$id"

    fun load(ctx: Context): List<Workflow> = synchronized(LOCK) {
        runCatching { WorkflowFormat.json.decodeFromString(ListSerializer(Workflow.serializer()), file(ctx).readText()) }.getOrDefault(emptyList())
    }

    private fun saveAll(ctx: Context, list: List<Workflow>) = synchronized(LOCK) {
        val f = file(ctx); val tmp = File(f.path + ".tmp")
        tmp.writeText(WorkflowFormat.json.encodeToString(ListSerializer(Workflow.serializer()), list))
        tmp.renameTo(f)
        _all.value = list
        shareEntry(ctx, list)
    }

    // ---------- 分享、通知触发 ----------

    /** 分享菜单里的「交给 Kavi 工作流」：有开着的「分享时跑」的工作流才出现，免得平时多一项。 */
    private fun shareEntry(ctx: Context, list: List<Workflow>) = runCatching {
        val on = list.any { it.enabled && it.trigger.type == "share" }
        ctx.packageManager.setComponentEnabledSetting(android.content.ComponentName(ctx, ctx.packageName + ".WorkflowShare"),
            if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP)
    }

    fun shareTargets(ctx: Context) = load(ctx).filter { it.enabled && it.trigger.type == "share" }

    /** 通知里写的 App 名字或包名、关键字对不对得上（不分大小写）。 */
    fun matches(t: WfTrigger, pkg: String, app: String, title: String, text: String): Boolean {
        if (t.type != "notify") return false
        val okApp = t.app.isBlank() || listOf(app, pkg).any { it.contains(t.app.trim(), ignoreCase = true) }
        val okWord = t.keyword.isBlank() || t.keyword.split(Regex("[,，|]")).map { it.trim() }.filter { it.isNotEmpty() }
            .any { k -> (title + "\n" + text).contains(k, ignoreCase = true) }
        return okApp && okWord
    }

    /** 读通知那里每来一条就问一下：有对得上的「收到通知时跑」的工作流就放进后台跑（同一个工作流一分钟最多一次）。 */
    fun onNotification(ctx: Context, pkg: String, app: String, title: String, text: String, now: Long = System.currentTimeMillis()) {
        if (pkg == ctx.packageName) return
        load(ctx).filter { it.enabled && matches(it.trigger, pkg, app, title, text) && now - it.lastFiredAt > 60_000 }.forEach { w ->
            val input = kotlinx.serialization.json.buildJsonObject { put("app", app); put("title", title); put("text", text.take(2000)) }
            enqueue(ctx, w.id, "notify", input.toString())
            update(ctx, w.id) { it.copy(lastFiredAt = now) }
        }
    }

    fun get(ctx: Context, id: String) = load(ctx).firstOrNull { it.id == id }

    /** 按 id 或名字找（聊天里说名字）。 */
    fun find(ctx: Context, key: String): Workflow? {
        val all = load(ctx); val k = key.trim()
        return all.firstOrNull { it.id == k } ?: all.firstOrNull { it.name == k } ?: all.firstOrNull { it.name.contains(k, true) || k.contains(it.name, true) }
    }

    /** 新建或替换（同 id），排好下一次。 */
    fun save(ctx: Context, w: Workflow, now: Long = System.currentTimeMillis()): Workflow {
        val old = get(ctx, w.id)
        val saved = w.copy(createdAt = old?.createdAt ?: w.createdAt.takeIf { it > 0 } ?: now, updatedAt = now,
            // 改了定时就从现在重新算
            lastFiredAt = if (old != null && old.trigger == w.trigger) old.lastFiredAt else 0)
        saveAll(ctx, load(ctx).filterNot { it.id == w.id } + saved)
        schedule(ctx, saved)
        return saved
    }

    fun update(ctx: Context, id: String, f: (Workflow) -> Workflow) {
        val w = get(ctx, id) ?: return
        val n = f(w)
        saveAll(ctx, load(ctx).map { if (it.id == id) n else it })
        schedule(ctx, n)
    }

    fun delete(ctx: Context, id: String) {
        cancel(ctx, id)
        saveAll(ctx, load(ctx).filterNot { it.id == id })
        runsFile(ctx, id).delete()
    }

    // ---------- 运行记录 ----------

    fun runs(ctx: Context, id: String): List<WfRun> = synchronized(LOCK) {
        runCatching { WorkflowFormat.json.decodeFromString(ListSerializer(WfRun.serializer()), runsFile(ctx, id).readText()) }.getOrDefault(emptyList())
    }

    private fun addRun(ctx: Context, r: WfRun) = synchronized(LOCK) {
        val list = (listOf(r) + runs(ctx, r.workflowId)).take(KEEP_RUNS)
        runsFile(ctx, r.workflowId).apply { parentFile?.mkdirs() }.writeText(WorkflowFormat.json.encodeToString(ListSerializer(WfRun.serializer()), list))
    }

    // ---------- 定时 ----------

    fun scheduleAll(ctx: Context) = load(ctx).forEach { runCatching { schedule(ctx, it) } }

    fun schedule(ctx: Context, w: Workflow) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarm(ctx, w.id)
        am.cancel(pi)
        val t = WorkflowFormat.nextAt(w, System.currentTimeMillis()) ?: return
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(alarm(ctx, id))
        WorkManager.getInstance(ctx).cancelUniqueWork("workflow:$id")
    }

    private fun alarm(ctx: Context, id: String) = PendingIntent.getBroadcast(
        ctx, ("wf:$id").hashCode(),
        Intent(ctx, com.jack.ai.data.AlarmReceiver::class.java).setAction(ACTION).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 到点了：交给后台跑，记下这次到点，排下一次。 */
    fun fire(ctx: Context, id: String) {
        val w = get(ctx, id) ?: return
        if (!w.enabled) return
        enqueue(ctx, id, "schedule")
        update(ctx, id) { it.copy(lastFiredAt = System.currentTimeMillis(), enabled = it.trigger.repeat != "once" && it.enabled) }
    }

    /** 放到后台跑（手动点「跑一下」也走这里，切出 App 也不停）。 */
    fun enqueue(ctx: Context, id: String, trigger: String, input: String = "") {
        val req = OneTimeWorkRequestBuilder<WorkflowWorker>()
            .setInputData(workDataOf("id" to id, "trigger" to trigger, "input" to input))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .build()
        // 同一个工作流只排一份：上一次还没跑完，这次就不再排
        WorkManager.getInstance(ctx).enqueueUniqueWork("workflow:$id", ExistingWorkPolicy.KEEP, req)
    }

    /**
     * 真的跑一次：记运行记录，结果按 deliver 送到收件箱或通知；出错了发通知说哪一步错了。
     * [callTool] 在聊天里跑时用当前对话的工具（同一套确认）；后台跑时为空，用后台的工具，要确认的一律不做。
     */
    suspend fun run(
        ctx: Context, w: Workflow, trigger: String, input: JsonElement? = null,
        callTool: (suspend (String, JsonObject) -> String)? = null, onStep: (WfStepLog) -> Unit = {},
    ): WfRun {
        running.update { it + w.id }
        try {
            val store = Store(ctx)
            val settings = withContext(Dispatchers.IO) { store.loadSettings() }
            val files = mutableListOf<GeneratedFile>()
            val host = AppWfHost(ctx, settings, w, callTool, files)
            val r = WorkflowEngine(host).run(w, trigger, input, onStep)
            // 编辑器里试跑：不记运行记录，结果不另外送（步骤里自己发的通知、收件箱照常发）
            if (trigger != "test") withContext(Dispatchers.IO) {
                addRun(ctx, r)
                deliver(ctx, store, w, r, files)
                // 监控：这次没「停下」就是满足了，满足一次就关掉，不再查
                if (trigger == "schedule" && w.trigger.type == "schedule" && w.trigger.repeat == "watch" && r.status == "ok") {
                    update(ctx, w.id) { it.copy(enabled = false) }
                    Routines.notify(ctx, "wf_${w.id}_hit", "⚙ 「${w.name}」等到了", r.output.take(200).ifBlank { "监控的条件满足了，已经停止监控" }, open = openIntent(ctx, w))
                }
            }
            return r
        } finally {
            running.update { it - w.id }
        }
    }

    /** 点了工作流的通知：工作流页打开后直接展开这一个。 */
    val openRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    private fun openIntent(ctx: Context, w: Workflow) = android.content.Intent(ctx, com.jack.ai.MainActivity::class.java)
        .setAction(com.jack.ai.MainActivity.ACTION_OPEN_WORKFLOW).putExtra("wfId", w.id)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun deliver(ctx: Context, store: Store, w: Workflow, r: WfRun, files: List<GeneratedFile>) {
        val bad = r.status == "failed" || r.status == "limit"
        if (bad) {
            Routines.notify(ctx, "wf_${w.id}", "⚙ 工作流「${w.name}」没跑完", r.error.take(200) + "\n点开看是哪一步", open = openIntent(ctx, w))
            return
        }
        // 自己写了「发到收件箱」「发通知」的步骤，就不再额外送一份
        val sent = r.steps.any { it.type == "message" || it.type == "notify" }
        if (sent && files.isEmpty()) return
        val text = (if (r.status == "stopped") "停下了：${r.error}\n\n" else "") + r.output.ifBlank { "跑完了，没有要说的结果。" }
        when (w.deliver) {
            "inbox" -> store.addInbox(InboxItem(w.id, convId(w.id), "⚙ ${w.name}", "工作流 · ${triggerLabel(r.trigger)}", text, files = files, work = true))
            "notify" -> Routines.notify(ctx, "wf_${w.id}", "⚙ ${w.name}", text.take(300), open = openIntent(ctx, w))
        }
    }

    fun triggerLabel(t: String) = when (t) {
        "schedule" -> "到点自动跑"; "chat" -> "在聊天里跑"; "test" -> "试跑"; "notify" -> "收到通知"; "share" -> "分享过来"; else -> "手动跑"
    }
}

/**
 * App 里的工作流本事。工具：聊天里跑时用对话的那套；后台跑时开一个后台工具会话，要确认的事一律不做（授权在第 6 步做）。
 * 模型用后台模型，花的钱记在统计页「后台 · 工作流 · 名字」，同时算进这次的花钱上限。
 */
private class AppWfHost(
    private val ctx: Context,
    private val settings: Settings,
    private val w: Workflow,
    private val callTool: (suspend (String, JsonObject) -> String)?,
    private val files: MutableList<GeneratedFile>,
) : WfHost {
    private val store = Store(ctx)
    private val conv by lazy { Conversation(id = Workflows.convId(w.id), title = "⚙ ${w.name}") }
    /** 正在跑的这一步保存时授权过：它要问用户的，照授权放行。 */
    @Volatile private var granted = false
    /** 这一步问过、没放行的那句（报错时告诉用户）。 */
    @Volatile private var refused: String? = null
    private val session by lazy {
        val ui = object : com.jack.ai.tools.ToolUi {
            override fun started(label: String) {}
            override fun finished(label: String, record: SearchRecord) {}
            override fun attach(file: File) { files += GeneratedFile(file.name, file.path, mime(file.name), file.length()) }
            // 后台没人看着：保存时授权过的放行（付款、密码这类除外），别的一律不做
            override suspend fun confirm(title: String, body: String, action: String): Boolean {
                val ok = granted && !WfGrants.NEVER.containsMatchIn(title + body)
                if (!ok) refused = title
                return ok
            }
        }
        com.jack.ai.tools.ToolSession(com.jack.ai.tools.BackgroundToolHost(ctx), settings, conv, null, settings.allSkills().filter { it.enabled }, ui)
    }

    override suspend fun tool(name: String, args: JsonObject, step: WfStep): String {
        callTool?.let { return it(name, args) }
        granted = WfGrants.allowed(w, step); refused = null
        val out = try { session.perform(name, args.toString()) } finally { granted = false }
        if (out.status != com.jack.ai.tools.ToolStatus.OK) {
            val why = out.text.lineSequence().firstOrNull().orEmpty().take(300)
            throw IllegalStateException(when {
                out.status != com.jack.ai.tools.ToolStatus.REJECTED -> why
                WfGrants.allowed(w, step) -> "这一步碰到了付款、密码这类，授权了也不做：${refused ?: why}"
                else -> "这一步要你确认（${refused ?: why}），后台跑时不做。在工作流页编辑后保存时同意授权，以后就不用问"
            })
        }
        return out.text
    }

    override suspend fun ai(prompt: String, system: String, json: Boolean): Pair<String, Double> {
        val ep = settings.backgroundEndpoint() ?: throw IllegalStateException("还没有设置能用的模型")
        var cost = 0.0
        val client = com.jack.ai.api.DeepSeekClient { e, p, h, c -> cost += store.addUsage(e, p, h, c, label = "后台 · 工作流 · ${w.name}") ?: 0.0 }
        val msgs = buildList {
            add(buildJsonObject { put("role", "system"); put("content", system.ifBlank { "你在帮用户跑一个固定的工作流「${w.name}」里的一步，没有人在线，不要提问，直接按要求给出结果。" }) })
            add(buildJsonObject { put("role", "user"); put("content", prompt.take(60_000)) })
        }
        val text = withContext(Dispatchers.IO) { client.complete(ep, msgs, maxTokens = 4000, jsonMode = json) }
        return text to cost
    }

    override suspend fun notify(title: String, body: String) = Routines.notify(ctx, "wf_${w.id}_${System.nanoTime() % 1000}", title, body)

    override suspend fun message(title: String, text: String) = withContext(Dispatchers.IO) {
        store.addInbox(InboxItem(w.id, Workflows.convId(w.id), "⚙ $title", "工作流", text, work = true))
    }

    override suspend fun file(name: String, text: String): String = withContext(Dispatchers.IO) {
        val safe = name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().ifBlank { "结果.txt" }.take(80)
        val f = File(Workflows.filesDir(ctx, w.id).apply { mkdirs() }, safe)
        f.writeText(text)
        files += GeneratedFile(f.name, f.path, mime(f.name), f.length())
        f.path
    }

    override suspend fun code(code: String, vars: JsonObject): String {
        val m = PackManifest("workflow-code", "工作流代码", tools = listOf(PackToolSpec("main", "代码")))
        val r = PackRuntime.run(m, "export async function main(vars, kavi) {\n$code\n}", "main", vars, emptyMap(), NoPowers, 30_000)
        if (!r.ok) throw IllegalStateException(r.text)
        return r.text
    }

    private fun mime(n: String) = java.net.URLConnection.guessContentTypeFromName(n) ?: "text/plain"
}

/** 工作流里的代码只能算东西，联网、文件这些用别的步骤。 */
private object NoPowers : PackHost {
    override fun call(op: String, arg: JsonElement): JsonElement = throw IllegalStateException("工作流的「跑代码」只能算东西；联网、存文件用别的步骤")
    override suspend fun callAsync(op: String, arg: JsonElement): JsonElement =
        if (op == "sleep") JsonNull else throw IllegalStateException("工作流的「跑代码」只能算东西；联网、调模型用别的步骤")
}

class WorkflowWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val w = Workflows.get(applicationContext, id) ?: return Result.success()
        // 通知触发的是 JSON（app、title、text），分享的是文字
        val input = inputData.getString("input").orEmpty().takeIf { it.isNotBlank() }?.let { s ->
            if (s.startsWith("{")) WfTemplate.parseLoose(s) else JsonPrimitive(s)
        }
        runCatching { setForeground(foreground(w.name)) }
        Workflows.run(applicationContext, w, inputData.getString("trigger") ?: "manual", input)
        return Result.success()
    }

    private fun foreground(name: String): androidx.work.ForegroundInfo {
        val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        if (nm.getNotificationChannel("work") == null) nm.createNotificationChannel(android.app.NotificationChannel("work", "回答和任务进度", android.app.NotificationManager.IMPORTANCE_LOW))
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, "work").setSmallIcon(com.jack.ai.R.drawable.ic_widget_chat)
            .setContentTitle("正在跑工作流：$name").setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true).build()
        return if (Build.VERSION.SDK_INT >= 29) androidx.work.ForegroundInfo(7311, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else androidx.work.ForegroundInfo(7311, n)
    }
}
