package com.jack.ai.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jack.ai.UiState

/**
 * 新手任务：分几个阶段教「能拿 Kavi 做什么」，每一关一句说明、一个「试一试」（把示范的话填进输入框）。
 * 真的做到了就自动打勾（看对话里有没有发过图、做出过 PPT、设过提醒……），看不出来的点「我会了」。
 * 阶段全部能看，按推荐顺序排，还没做完的第一个阶段展开。界面上的按钮在哪，由各页面第一次打开时的指亮引导教（[Tour]）。
 */
object Guide {
    /** 点了一关以后要做的事。 */
    sealed interface Action {
        /** 开个新对话，把这句话填进输入框。 */
        data class Try(val prompt: String) : Action
        /** 聊天页的指亮引导再走一遍。 */
        data object ChatTour : Action
        data object Memory : Action
        data object NewProject : Action
        data object Routines : Action
        /** 打开设置的某一页（SettingsPage 的名字）。 */
        data class Settings(val page: String) : Action
    }

    class Lesson(val id: String, val title: String, val text: String, val action: Action? = null, val done: (UiState) -> Boolean = { false })
    class Stage(val id: String, val title: String, val lead: String, val lessons: List<Lesson>)

    private fun UiState.messages() = conversations.asSequence().flatMap { it.messages.asSequence() }
    private fun UiState.files() = messages().flatMap { it.files.asSequence() }.map { it.name.lowercase() }
    private fun UiState.usedTool(name: String) = messages().any { m -> m.trail.any { "\"name\":\"$name\"" in it } }

    fun stages(browser: Boolean = com.jack.ai.Edition.browser, phone: Boolean = com.jack.ai.Edition.phoneControl): List<Stage> = listOfNotNull(
        Stage("basics", "认识 Kavi", "先会问、会看回答、会换模型。", listOf(
            Lesson("chat-tour", "看一遍界面在哪", "输入框、＋、换模型、侧边栏、底栏各在哪，一分钟带你看完。", Action.ChatTour),
            Lesson("first-ask", "问它一个问题", "想做什么直接说，不用想格式。不满意就接着说「再短一点」「换个说法」。",
                Action.Try("用三句话告诉我，你能帮我做哪些事？")) { s -> s.messages().any { it.role == "user" } },
            Lesson("voice", "用说的", "点输入框右边的话筒说话；长按话筒是语音对话，说完自动发、自动读回答。"),
            Lesson("think", "难题让它多想一会儿", "点顶上的标题换模型和思考深度：日常用快的省钱，算账、写方案、改代码用深度思考。"),
        )),
        Stage("inputs", "给它看东西", "图片、文件、网页，Kavi 都能读。", listOf(
            Lesson("image", "发一张图", "拍张菜单让它推荐、截个报错让它看、发张表格照片让它整理成表。点「＋」选照片或拍照。") { s ->
                s.messages().any { m -> m.role == "user" && m.attachments.any { it.kind == "image" } }
            },
            Lesson("file", "发一个文件让它读", "PDF、Word、Excel 都行：「总结要点」「找出合同里对我不利的条款」「把这份表按月份汇总」。") { s ->
                s.messages().any { m -> m.role == "user" && m.attachments.any { it.kind == "file" } }
            },
            Lesson("search", "让它上网查最新的", "新闻、价格、天气、最近的事，它会自己去搜，回答里标出来源。",
                Action.Try("今天有什么值得关注的科技新闻？挑三条讲讲。")) { s -> s.messages().any { it.role == "assistant" && it.searches.isNotEmpty() } },
        )),
        Stage("make", "让它做出东西", "不只是回答，还能直接交给你文件。", listOf(
            Lesson("doc", "做一份文档或 PPT", "说清楚给谁看、讲什么、几页，做好的 Word、PPT 在对话里点开就能看、能分享。",
                Action.Try("帮我做一份 6 页的 PPT，主题是「新员工入职第一周」，风格简洁。")) { s ->
                s.files().any { it.endsWith(".pptx") || it.endsWith(".docx") || it.endsWith(".xlsx") || it.endsWith(".pdf") }
            },
            Lesson("design", "做一张图", "海报、小红书封面、朋友圈配图，说用途和要写的字。",
                Action.Try("做一张小红书封面：标题「一个人的周末食谱」，清新一点。")) { s ->
                s.files().any { it.endsWith(".png") || it.endsWith(".jpg") }
            },
            Lesson("app", "做一个小工具", "计算器、记账本、打卡表……做好能直接用，还能放到桌面。",
                Action.Try("做一个能调参数的房贷计算器，等额本息和等额本金都要。")) { s -> s.files().any { it.endsWith(".html") } },
        )),
        Stage("know", "让它记住你", "越用越懂你：你是谁、你的习惯、你正在做的事。", listOf(
            Lesson("remember", "告诉它你是谁", "说一句「记住：……」，以后每个对话都会带上。不想让它记的随时能删。",
                Action.Try("记住：我叫……，平时做……，喜欢简短直接的回答。")) { s -> s.memories.isNotEmpty() || s.settings.aboutMe.isNotBlank() },
            Lesson("memory-page", "看看它记了什么", "在记忆页能看、能改、能删每一条，也能关掉记忆。", Action.Memory),
            Lesson("project", "给长期的事建个项目", "一本书、一个客户、一门课：项目里的对话共用资料、指令和记忆，不和别的混在一起。",
                Action.NewProject) { s -> s.projects.isNotEmpty() },
        )),
        Stage("time", "让它按时做事", "到点提醒你，或者到点自己把事做了。", listOf(
            Lesson("reminder", "设一个提醒", "用平常说话的方式说时间就行。", Action.Try("10 分钟后提醒我站起来活动一下")) { s ->
                s.routines.any { it.kind == "reminder" }
            },
            Lesson("task", "设一个定时任务", "到点 Kavi 在后台去做，做完把结果发给你。", Action.Try("每天早上 8 点给我发一份今天的天气和三条新闻")) { s ->
                s.routines.any { it.kind == "task" }
            },
            Lesson("watch", "让它帮你盯着", "隔一会儿查一次，等到了才告诉你；降价、有票、有新消息都能盯。",
                Action.Try("帮我盯着下周五北京到上海的高铁票，有二等座了告诉我")) { s -> s.routines.any { it.kind == "watch" } },
            Lesson("routines-page", "看看都设了什么", "提醒和任务页里能看到每一个、每次跑得怎么样，能开关、能改。", Action.Routines),
        )),
        if (!browser && !phone) null else Stage("hands", "让它替你动手", "打开网站、点 App，像你自己在操作。要发送、付款这类会先问你。", listOfNotNull(
            if (!browser) null else Lesson("browser", "让它上网站帮你看", "它会在自己的浏览器里打开网页、翻页、读内容，你随时能看它在干什么。",
                Action.Try("打开知乎热榜，告诉我今天前五条是什么")) { s -> s.usedTool("browser") },
            if (!phone) null else Lesson("phone-setup", "允许它操作手机上的 App", "在设置里勾上它能碰的 App（涉及钱的勾不了），再开一下无障碍。",
                Action.Settings("PHONE")) { s -> s.settings.phoneApps.isNotEmpty() },
            if (!phone) null else Lesson("phone", "让它在 App 里做一件事", "比如看看某个群最近在聊什么、在某个 App 里搜个东西。",
                Action.Try("打开微信，看看最近一条消息是谁发的、说了什么，只看不回")) { s -> s.usedTool("phone") },
        )),
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("guide", Context.MODE_PRIVATE)

    /** 自己点了「我会了」的几关。 */
    fun marked(ctx: Context): Set<String> = prefs(ctx).getStringSet("marked", emptySet()).orEmpty()
    fun mark(ctx: Context, id: String, on: Boolean = true) {
        val now = marked(ctx).toMutableSet().apply { if (on) add(id) else remove(id) }
        prefs(ctx).edit().putStringSet("marked", now).apply()
    }

    /** 空白聊天页上那张小卡点了「不再显示」。 */
    fun hidden(ctx: Context) = prefs(ctx).getBoolean("hidden", false)
    fun hide(ctx: Context) = prefs(ctx).edit().putBoolean("hidden", true).apply()

    /** 这一关做没做到：自动看出来的、自己点过的、聊天引导看过的。 */
    fun isDone(l: Lesson, state: UiState, marked: Set<String>, tourSeen: Boolean): Boolean =
        l.id in marked || (l.id == "chat-tour" && tourSeen) || runCatching { l.done(state) }.getOrDefault(false)

    data class Progress(val doneStages: Int, val stages: Int, val next: Lesson?, val nextStage: Int)

    fun progress(stages: List<Stage>, done: (Lesson) -> Boolean): Progress {
        val firstOpen = stages.indexOfFirst { st -> st.lessons.any { !done(it) } }
        return Progress(
            doneStages = stages.count { st -> st.lessons.all(done) },
            stages = stages.size,
            next = stages.getOrNull(firstOpen)?.lessons?.firstOrNull { !done(it) },
            nextStage = firstOpen,
        )
    }
}

/** 空白聊天页上的那张小卡：「新手任务 2/6 · 下一步：发一张图 ›」，做完了或点了叉就不再显示。 */
@Composable
internal fun GuideCard(state: UiState, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var hidden by remember { mutableStateOf(Guide.hidden(ctx)) }
    if (hidden) return
    val marked = remember { Guide.marked(ctx) }
    val tourSeen = remember { Tour.seen(ctx, Tour.CHAT) }
    val stages = remember { Guide.stages() }
    val p = Guide.progress(stages) { Guide.isDone(it, state, marked, tourSeen) }
    val next = p.next ?: return
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(Radii.l).background(raisedColor).clickable(onClickLabel = "打开新手任务", onClick = onOpen)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("新手任务 · ${p.doneStages}/${p.stages} 个阶段", style = MaterialTheme.typography.labelMedium, color = colors.primary)
            Text("下一步：${next.title} ›", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
        IconButton(onClick = { Guide.hide(ctx); hidden = true }) { Icon(Icons.Default.Close, "不再显示", Modifier.size(18.dp), tint = colors.outline) }
    }
}

/** 新手任务页。 */
@Composable
fun GuideScreen(state: UiState, onBack: () -> Unit, onAction: (Guide.Action) -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var version by remember { mutableIntStateOf(0) }
    val marked = remember(version) { Guide.marked(ctx) }
    val tourSeen = remember(version) { Tour.seen(ctx, Tour.CHAT) }
    val stages = remember { Guide.stages() }
    fun done(l: Guide.Lesson) = Guide.isDone(l, state, marked, tourSeen)
    val p = Guide.progress(stages, ::done)
    var open by rememberSaveable { mutableIntStateOf(p.nextStage.coerceAtLeast(0)) }
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack) }
            item {
                Column(Modifier.padding(start = 4.dp, top = 4.dp)) {
                    Text("新手任务", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(if (p.next == null) "全部做完了。剩下的边用边发现，不知道 Kavi 能不能做，就直接问它。"
                        else "按顺序做一遍，大概十分钟。做到了会自动打勾，哪关都能先跳着看。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    val all = stages.sumOf { it.lessons.size }
                    val finished = stages.sumOf { st -> st.lessons.count(::done) }
                    LinearProgressIndicator(progress = { if (all == 0) 1f else finished / all.toFloat() }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
                    Text("$finished / $all", style = MaterialTheme.typography.labelSmall.tabular, color = colors.outline, modifier = Modifier.padding(top = 4.dp))
                }
            }
            itemsIndexed(stages, key = { _, st -> st.id }) { i, st ->
                val count = st.lessons.count(::done)
                val complete = count == st.lessons.size
                Column(Modifier.fillMaxWidth().clip(Radii.l).background(raisedColor)) {
                    Row(Modifier.fillMaxWidth().clickable { open = if (open == i) -1 else i }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(if (complete) colors.primary else colors.onSurface.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                            if (complete) Icon(Icons.Default.Check, "做完了", Modifier.size(18.dp), tint = colors.onPrimary)
                            else Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(st.title, style = MaterialTheme.typography.titleSmall)
                            Text(st.lead, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Text("$count/${st.lessons.size}", style = MaterialTheme.typography.labelMedium.tabular, color = colors.outline)
                        Chevron(open == i, Modifier.padding(start = 4.dp))
                    }
                    if (open == i) Column(Modifier.padding(start = 14.dp, end = 8.dp, bottom = 8.dp)) {
                        st.lessons.forEach { l -> LessonRow(l, done(l), onAction = { a -> onAction(a); if (a == Guide.Action.Memory || a == Guide.Action.Routines) { Guide.mark(ctx, l.id); version++ } },
                            onMark = { on -> Guide.mark(ctx, l.id, on); version++ }) }
                    }
                }
            }
            item {
                // 各页面第一次打开时的指亮引导：看过就不再带，这里能重新带一遍
                TextButton(onClick = {
                    listOf(Tour.DRAWER, Tour.ROUTINES, Tour.PROJECT, Tour.SETTINGS).forEach { t ->
                        ctx.getSharedPreferences("tour", Context.MODE_PRIVATE).edit().remove(t).apply()
                    }
                    android.widget.Toast.makeText(ctx, "好了，再打开侧边栏、提醒、项目、设置时会重新带一遍", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("各页面的指引再带一遍") }
            }
        }
    }
}

@Composable
private fun LessonRow(l: Guide.Lesson, done: Boolean, onAction: (Guide.Action) -> Unit, onMark: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape)
            .background(if (done) colors.primary else colors.onSurface.copy(alpha = 0.08f)).clickable(onClickLabel = if (done) "取消打勾" else "我会了") { onMark(!done) },
            contentAlignment = Alignment.Center) {
            if (done) Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = colors.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (done) colors.onSurfaceVariant else colors.onSurface)
            Text(l.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            (l.action as? Guide.Action.Try)?.let { t ->
                Text("「${t.prompt}」", style = MaterialTheme.typography.bodySmall, color = colors.outline, modifier = Modifier.padding(top = 2.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                l.action?.let { a ->
                    TextButton(onClick = { onAction(a) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                        Text(when (a) {
                            is Guide.Action.Try -> "试一试"
                            Guide.Action.ChatTour -> "带我看"
                            Guide.Action.NewProject -> "建一个"
                            else -> "去看看"
                        })
                    }
                }
                if (!done && l.action !is Guide.Action.ChatTour) TextButton(onClick = { onMark(true) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("我会了", color = colors.outline)
                }
            }
        }
    }
}
