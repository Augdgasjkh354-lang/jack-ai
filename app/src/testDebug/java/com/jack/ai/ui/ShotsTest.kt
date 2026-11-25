package com.jack.ai.ui


import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.jack.ai.ChatViewModel
import com.jack.ai.data.ChatMessage
import com.jack.ai.data.Conversation
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Store
import com.jack.ai.data.ThemeMode
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 本地看界面用：SHOTS_DIR 设了才跑，把几个页面画成 PNG。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShotsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    init { GlassConfig.blur = false }

    private fun save(name: String) {
        val dir = File(System.getenv("SHOTS_DIR") ?: return)
        dir.mkdirs()
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun vm(convs: List<Conversation>): ChatViewModel {
        val app = RuntimeEnvironment.getApplication() as Application
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        Store(app).saveConversations(convs)
        return ChatViewModel(app)
    }

    private fun show(vm: ChatViewModel, mode: ThemeMode) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            // SHOTS_FONT=serif 这类：看换了字体以后的样子
            JackTheme(mode, font = System.getenv("SHOTS_FONT").orEmpty()) {
                ChatScreen(vm, state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
    }

    @Test
    fun chat() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val mode = if (!System.getenv("SHOTS_DARK").isNullOrEmpty()) ThemeMode.DARK else ThemeMode.LIGHT
        val answer = """## 长期保持高效和专注，可以从三个方面入手

1. **设计适合自己的工作节奏**：找到精力最充沛的时间段，专注处理最重要的任务；用固定流程减少决策成本。
2. **管理注意力，而不是时间**：减少不必要的干扰，一次只做一件事，借助番茄工作法训练专注。
3. **保持身心的稳定状态**：充足的睡眠、规律的运动和健康的饮食，是高效工作的底层支持。

---

**高效不是一时的冲刺，而是一种可持续的生活方式。** 当你建立起适合自己的节奏，专注就会成为习惯。"""
        val c = Conversation(title = "保持高效", messages = listOf(
            ChatMessage(role = "user", content = "如何在工作中保持长期的高效和专注？"),
            ChatMessage(role = "assistant", content = answer, model = "deepseek-v4-flash",
                searches = listOf(SearchRecord("高效 专注 方法"), SearchRecord("番茄工作法")),
                reasoning = "用户想知道怎么长期保持高效……",
                steps = listOf(
                    com.jack.ai.data.ThinkStep("summary", "理清高效和专注的区别", 1),
                    com.jack.ai.data.ThinkStep("note", "我先查一下有研究支持的方法，再挑最实用的几条。", 2),
                    com.jack.ai.data.ThinkStep("summary", "对比番茄工作法和时间块", 3),
                    com.jack.ai.data.ThinkStep("summary", "按精力、注意力、身体三方面组织", 4),
                )),
        ))
        val vm = vm(listOf(c))
        vm.select(c.id)
        show(vm, mode)
        save("chat-" + mode.name.lowercase())
    }

    @Test
    fun empty() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val mode = if (!System.getenv("SHOTS_DARK").isNullOrEmpty()) ThemeMode.DARK else ThemeMode.LIGHT
        val vm = vm(emptyList())
        show(vm, mode)
        save("empty-" + mode.name.lowercase())
    }

    private fun dark() = if (!System.getenv("SHOTS_DARK").isNullOrEmpty()) ThemeMode.DARK else ThemeMode.LIGHT

    @Test
    fun work() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val app = RuntimeEnvironment.getApplication() as Application
        val xlsx = File(app.filesDir, "3家供应商报价对比.xlsx").apply { writeBytes(ByteArray(56 * 1024)) }
        val t0 = 1_761_530_040_000L
        val steps = listOf("浏览器打开 1688，搜索「工业风扇 报价」", "读取 供应商A.pdf", "浏览器打开 淘宝，搜索「工业风扇 批发」",
            "查「供应商报价」", "生成 Excel：3家供应商报价对比.xlsx", "浏览器截图")
        val c = Conversation(title = "整理 3 家供应商报价", work = true, messages = listOf(
            ChatMessage(role = "user", content = "整理 3 家供应商的工业风扇报价，做成对比表"),
            ChatMessage(role = "assistant", content = "", cost = 0.38,
                plan = listOf(com.jack.ai.data.PlanItem("收集 3 家供应商的最新报价", "done"), com.jack.ai.data.PlanItem("整理关键参数并对比", "done"),
                    com.jack.ai.data.PlanItem("生成汇总表格（Excel）", "doing"), com.jack.ai.data.PlanItem("检查数据并优化格式"), com.jack.ai.data.PlanItem("输出结果")),
                criteria = listOf(com.jack.ai.data.PlanItem("找满 3 家供应商", "met"), com.jack.ai.data.PlanItem("每家都有价格和来源链接", "met"),
                    com.jack.ai.data.PlanItem("规格一致、可以直接比较"), com.jack.ai.data.PlanItem("Excel 能打开、没有空行")),
                searches = steps.mapIndexed { i, q -> SearchRecord(q, at = t0 + i * 60_000L, pending = i == steps.lastIndex) },
                files = listOf(com.jack.ai.data.GeneratedFile(xlsx.name, xlsx.absolutePath, "application/vnd.ms-excel", xlsx.length()))),
        ))
        val vm = vm(listOf(c))
        vm.select(c.id)
        show(vm, dark())
        save("work-" + dark().name.lowercase())
    }

    @Test
    fun drawer() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val now = System.currentTimeMillis()
        val convs = listOf(
            Conversation(title = "如何建立一个更好的早晨习惯？", pinned = true, updatedAt = now, messages = listOf(ChatMessage(role = "user", content = "我想要一个科学且可执行的早晨习惯方案"))),
            Conversation(title = "帮我优化这段产品文案", updatedAt = now - 1000, messages = listOf(ChatMessage(role = "user", content = "这是一段面向海外用户的产品介绍，请帮我润色"))),
            Conversation(title = "解释一下量子计算的基本原理", updatedAt = now - 2000, messages = listOf(ChatMessage(role = "assistant", content = "量子计算用**量子比特**来表示信息"))),
            Conversation(title = "帮我制定健身计划", updatedAt = now - 86_400_000L * 1, messages = listOf(ChatMessage(role = "user", content = "我每周有 3 天可以去健身房，目标是增肌"))),
            Conversation(title = "比较几款笔记软件", updatedAt = now - 86_400_000L * 3, messages = listOf(ChatMessage(role = "user", content = "从功能、价格、同步方面对比 Notion 和 Obsidian"))),
        )
        val vm = vm(convs)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(dark()) {
                HistoryDrawer(state.copy(streamingIds = setOf(convs[1].id), projects = listOf(
                    com.jack.ai.data.Project(name = "个人成长"), com.jack.ai.data.Project(name = "工作学习"))),
                    {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        save("drawer-" + dark().name.lowercase())
    }

    @Test
    fun knowledge() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val app = RuntimeEnvironment.getApplication() as Application
        val now = System.currentTimeMillis()
        com.jack.ai.data.memory.Facts.save(app, listOf(
            com.jack.ai.data.memory.Fact(text = "珠穆朗玛峰海拔 8848.86 米（2020 年中尼联合测量）", topic = "地理", status = "verified",
                sources = listOf(com.jack.ai.data.memory.FactSource("https://www.gov.cn/x"), com.jack.ai.data.memory.FactSource("https://en.wikipedia.org/wiki/Mount_Everest"))),
            com.jack.ai.data.memory.Fact(text = "某物流美国专线标准时效 7 到 12 个工作日", topic = "物流", sources = listOf(com.jack.ai.data.memory.FactSource("https://example-logistics.com/us"))),
        ))
        val vm = vm(emptyList())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(ThemeMode.DARK) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0B1030)).padding(androidx.compose.ui.unit.Dp(16f))) {
                    androidx.compose.foundation.layout.Column {
                        FactsCard(vm)
                    }
                }
            }
        }
        Thread.sleep(500)
        save("knowledge")
    }

    @Test
    fun projectFiles() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val app = RuntimeEnvironment.getApplication() as Application
        val p = com.jack.ai.data.Project(id = "shot1", name = "小工具")
        val root = com.jack.ai.data.Workspace.dir(app, "p_shot1")
        com.jack.ai.data.ProjectTree.Template.WEB.apply(root)
        root.resolve("src").mkdirs(); root.resolve("src/app.py").writeText("print(1)\n".repeat(200))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            JackTheme(dark()) { androidx.compose.material3.Surface { ProjectFiles(p, androidx.compose.foundation.layout.PaddingValues(), {}) } }
        }
        save("project-files-" + dark().name.lowercase())
    }

    /** 工具包：技能页上的卡片、装之前的确认页、详情页。 */
    @Test
    fun packs() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val app = RuntimeEnvironment.getApplication() as Application
        val s = com.jack.ai.data.packs.Packs.stageAsset(app, "weather")
        val kuaidi = com.jack.ai.data.packs.PackManifest("kuaidi", "快递查询", "1.2.0", "按单号查快递走到哪了，支持顺丰、中通、圆通等 30 多家", "Jack",
            permissions = com.jack.ai.data.packs.PackPermissions(net = listOf("api.kuaidi100.com"), files = "pack", notify = true),
            config = listOf(com.jack.ai.data.packs.PackConfigField("apiKey", "快递 100 的 Key", secret = true, required = true)),
            tools = listOf(com.jack.ai.data.packs.PackToolSpec("track", "查一个快递单号的物流轨迹")))
        val notes = com.jack.ai.data.packs.PackManifest("notes", "读书笔记", "0.3.0", "把对话文件夹里的读书笔记整理成卡片，用模型提炼要点",
            permissions = com.jack.ai.data.packs.PackPermissions(files = "workspace", ai = true),
            tools = listOf(com.jack.ai.data.packs.PackToolSpec("cards", "整理成卡片"), com.jack.ai.data.packs.PackToolSpec("quiz", "出几道题")))
        val list = listOf(
            com.jack.ai.data.packs.InstalledPack(s.manifest, source = "sample"),
            com.jack.ai.data.packs.InstalledPack(kuaidi, source = "github:jack/kavi-packs@main:kuaidi"),
            com.jack.ai.data.packs.InstalledPack(notes, enabled = false, source = "kavi"),
        )
        packPagesInline = true
        val which = System.getenv("SHOTS_PACK") ?: "list"
        val op = if (which == "operit") kotlinx.coroutines.runBlocking {
            com.jack.ai.data.packs.OperitImport.stage(app, "/* METADATA\n{ name: bili, display_name: { zh: \"B站助手\" }, description: { zh: \"搜视频、看字幕、自动点赞\" }, author: \"某位作者\", env: [\"BILIBILI_SESSDATA\"],\n" +
                "tools: [ { name: search, description: 搜视频, parameters: [ { name: keyword, type: string, required: true } ] }, { name: like, description: 给视频点赞, parameters: [] } ] }\n*/\n" +
                "const c = OkHttp.newClient(); exports.search = async (p) => (await c.get('https://api.bilibili.com/x/web-interface/search?keyword=' + p.keyword)).json();\n" +
                "exports.like = async () => { await Tools.UI.tap(1, 2); await Tools.System.startApp('tv.danmaku.bili'); return { success: true }; };", "bili.js")
        } else null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            JackTheme(dark()) {
                androidx.compose.material3.Surface {
                    when (which) {
                        "operit" -> PackConfirm(op!!, null, {}, {})
                        "confirm" -> PackConfirm(s, null, {}, {})
                        "kavi" -> androidx.compose.foundation.layout.Box {
                            PackConfirm(s.copy(source = "kavi"), null, {}, {}, byKavi = true)
                        }
                        "empty" -> androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) { PacksEmptyCard({}, {}) }
                        else -> androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                            list.forEach { PackCard(it, {}, {}) }
                        }
                    }
                }
            }
        }
        save("packs-$which-" + dark().name.lowercase())
    }

    /** 工作流：列表，和一个工作流的步骤树。 */
    @Test
    fun workflows() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val app = RuntimeEnvironment.getApplication() as Application
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        val stocks = com.jack.ai.data.workflow.WorkflowFormat.parse("""{"id":"w1","name":"股票早报","description":"看三只股票，跌超 3% 单独提醒并点评","by":"kavi",
            "trigger":{"type":"schedule","repeat":"weekdays","time":"08:00"},"steps":[
            {"id":"s1","type":"tool","name":"行情","tool":"pack_stocks__quote","args":{"codes":"600519,000858,300750"}},
            {"id":"s2","type":"forEach","name":"这一只","list":"{{行情}}","steps":[
              {"id":"s3","type":"if","cond":{"left":"{{这一只.涨跌幅}}","op":"<","right":"-3"},"then":[
                {"id":"s4","type":"ai","name":"点评","prompt":"用一句话点评 {{这一只.名字}} 今天跌了 {{这一只.涨跌幅}}%"},
                {"id":"s5","type":"notify","title":"{{这一只.名字}} 跌了 {{这一只.涨跌幅}}%","body":"{{点评}}"}]}]},
            {"id":"s6","type":"message","text":"今天看了 {{行情.length}} 只"}]}""").getOrThrow()
        com.jack.ai.data.workflow.Workflows.save(app, stocks)
        packPagesInline = true
        com.jack.ai.data.workflow.Workflows.save(app, com.jack.ai.data.workflow.Workflow(id = "w2", name = "每日一句", description = "让 Kavi 给今天写一句提神的话，发成通知。",
            steps = listOf(com.jack.ai.data.workflow.WfStep(id = "a", type = "ai", prompt = "x"), com.jack.ai.data.workflow.WfStep(id = "b", type = "notify", title = "x"))))
        val which = System.getenv("SHOTS_PACK") ?: "list"
        rule.mainClock.autoAdvance = false
        rule.setContent {
            JackTheme(dark()) {
                if (which == "trigger") {
                    WorkflowEditor(com.jack.ai.data.workflow.Workflow(name = "", trigger = com.jack.ai.data.workflow.WfTrigger("notify", app = "淘宝", keyword = "快递，取件"),
                        steps = listOf(com.jack.ai.data.workflow.WfStep(id = "s1", type = "notify", title = "📦 {{输入.title}}", body = "{{输入.text}}")))) {}
                } else if (which == "editor") {
                    fun l(id: String, out: String, ok: Boolean = true, at: String = "") = com.jack.ai.data.workflow.WfStepLog(id, "", "", at, 0, 420, ok, input = "", output = if (ok) out else "", error = if (ok) "" else out)
                    WorkflowEditor(stocks, preview = mapOf(
                        "s1" to listOf(l("s1", "[{\"名字\":\"茅台\",\"涨跌幅\":-3.6},{\"名字\":\"五粮液\",\"涨跌幅\":1.2}]")),
                        "s3" to listOf(l("s3", "是", at = "第 1 项"), l("s3", "否", at = "第 2 项")),
                        "s4" to listOf(l("s4", "茅台回调明显，短线情绪偏弱，关注 1600 支撑。", at = "第 1 项")),
                    ), open = "s3") {}
                } else if (which == "tree") androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(20.dp)) { StepTree(stocks.steps) }
                } else WorkflowsScreen {}
            }
        }
        save("workflows-$which-" + dark().name.lowercase())
    }

    @Test
    fun memoryGraph() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        fun m(t: String, vararg a: String) = com.jack.ai.data.MemoryItem(text = t, about = a.toList())
        val all = listOf(
            m("小王是同事，做设计", "小王", "公司"), m("小王的生日在 12 月 3 日", "小王"), m("国庆和小王去了杭州", "小王", "杭州"),
            m("觉得杭州的龙井虾仁一般", "杭州"), m("养了一只橘猫叫团子", "团子"), m("团子每年 3 月打疫苗", "团子", "宠物医院"),
            m("公司在做跨境电商", "公司", "跨境电商"), m("店铺在 1688 和淘宝", "跨境电商", "淘宝"), m("妈妈住在成都", "妈妈", "成都"),
            m("春节回成都看妈妈", "妈妈", "成都", "春节"), m("体检约在下周三", "体检"),
        )
        rule.mainClock.autoAdvance = false
        rule.setContent {
            JackTheme(dark()) {
                androidx.compose.material3.Surface {
                    MemoryGraphView(all, "小王", {})
                }
            }
        }
        save("memory-graph-" + dark().name.lowercase())
    }

    @Test
    fun memory() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val app = RuntimeEnvironment.getApplication() as Application
        val store = Store(app)
        store.saveMemories(listOf("用户喜欢简洁直接的回答", "用户在做跨境电商，店铺在 1688 和淘宝", "用户常常熬夜，但在努力调整作息").map { com.jack.ai.data.MemoryItem(text = it) })
        val now = System.currentTimeMillis()
        store.saveDreams(listOf(
            com.jack.ai.data.DreamEntry(createdAt = now, seen = true, journal = "今天聊了很多。他一边处理店铺的事情，一边在想以后的方向，有时候会焦虑，但没有停下。\n\n复盘：上午那份报价表我漏了运费一栏，被他指出来了，以后做对比表先列全成本。\n\n下次：他大概会接着问物流的事，可以提前把三家的发货时效查好。", changes = "新记住了店铺平台"),
            com.jack.ai.data.DreamEntry(createdAt = now - 7 * 86_400_000L, seen = true, journal = "那几天他在处理物流和广告的事情，感觉有点累……"),
            com.jack.ai.data.DreamEntry(createdAt = now - 17 * 86_400_000L, from = now - 26 * 86_400_000L, merged = 10, seen = true, journal = "这段时间聊了很多关于产品、团队和未来的想法……"),
        ))
        val vm = vm(emptyList())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(dark()) { MemoryScreen(vm, state) {} }
        }
        save("memory-" + dark().name.lowercase())
    }

    @Test
    fun routines() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val app = RuntimeEnvironment.getApplication() as Application
        val now = System.currentTimeMillis()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now + 86_400_000L))
        val task = com.jack.ai.data.Routine(kind = "task", title = "汇总昨天的邮件", prompt = "x", hour = 9, minute = 0, repeat = "weekdays", date = day)
        val watch = com.jack.ai.data.Routine(kind = "watch", title = "G1234 次二等座有票就通知我", prompt = "x", hour = 0, minute = 0, everyMin = 30, until = now + 3 * 86_400_000L)
        Store(app).saveRoutines(listOf(
            com.jack.ai.data.Routine(kind = "reminder", title = "喝水", hour = 8, minute = 30, repeat = "daily", date = day), task, watch))
        com.jack.ai.data.RunLog.once(app, task, "ok", "完成")
        com.jack.ai.data.RunLog.once(app, watch, "not_met", "")
        val vm = vm(emptyList())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(dark()) { RoutinesScreen(vm, state, onBack = {}) }
        }
        save("routines-" + dark().name.lowercase())
    }

    /** 一轮出错的对话：用户气泡（改过、有两个版本）、带图片的消息、报错卡片。 */
    @Test
    fun errorChat() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val u = ChatMessage(role = "user", content = "帮我把这张图里的表格整理成 Excel，再写一段两百字的分析")
        val c = Conversation(title = "整理表格", messages = listOf(
            ChatMessage(role = "user", content = "你好"),
            ChatMessage(role = "assistant", content = "你好！有什么可以帮你？", model = "deepseek-v4-flash"),
            u.copy(alts = listOf(listOf(u.copy(content = "旧的问法")))),
            ChatMessage(role = "assistant", content = "", model = "deepseek-v4-flash",
                error = "ChatGPT 登录服务返回错误（403）：unsupported_country_region_territory"),
        ))
        val vm = vm(listOf(c))
        vm.select(c.id)
        show(vm, dark())
        save("error-" + dark().name.lowercase())
    }

    /** 小细节：长消息折叠、代码块的语言和复制、隔了一阵的时间分隔。 */
    @Test
    fun details() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val now = System.currentTimeMillis()
        val long = (1..30).joinToString("\n") { "第 $it 行：这是从别处粘贴过来的一大段日志内容" }
        val c = Conversation(title = "看日志", messages = listOf(
            ChatMessage(role = "user", content = "帮我看看这段代码", createdAt = now - 86_400_000L),
            ChatMessage(role = "assistant", content = "可以这样写：\n\n```python\nfor i in range(3):\n    print(i)\n```", model = "deepseek-v4-flash", createdAt = now - 86_400_000L + 5000),
            ChatMessage(role = "user", content = long, createdAt = now - 60_000L),
        ))
        val vm = vm(listOf(c))
        vm.select(c.id)
        show(vm, dark())
        save("details-" + dark().name.lowercase())
    }

    @Test
    fun project() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val now = System.currentTimeMillis()
        val p = com.jack.ai.data.Project(id = "pj", name = "小说《林舟》", instructions = "风格冷峻")
        val convs = listOf(
            Conversation(title = "第一章大纲", projectId = "pj", updatedAt = now, messages = listOf(ChatMessage(role = "user", content = "写大纲"))),
            Conversation(title = "人物小传：林舟", projectId = "pj", pinned = true, updatedAt = now - 5000, messages = listOf(ChatMessage(role = "user", content = "x"))),
        )
        val vm = vm(convs)
        vm.saveProject(p)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(dark()) { ProjectScreen(vm, state, "pj", {}, {}) }
        }
        save("project-" + dark().name.lowercase())
    }

    @Test
    fun usage() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val vm = vm(emptyList())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(dark()) { UsageScreen(vm, state) {} }
        }
        Thread.sleep(300)
        save("usage-" + dark().name.lowercase())
    }

    @Test
    fun library() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val vm = vm(emptyList())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(dark()) { LibraryScreen(vm, state) {} }
        }
        save("library-" + dark().name.lowercase())
    }

    @Test
    fun skills() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val vm = vm(emptyList())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(dark()) { SkillsScreen(vm, state.settings) {} }
        }
        save("skills-" + dark().name.lowercase())
    }

    @Test
    fun files() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val vm = vm(emptyList())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(dark()) { FileLibraryScreen(state, {}, {}) }
        }
        Thread.sleep(300)
        save("files-" + dark().name.lowercase())
    }

    /** 底栏：五格的、两格的小胶囊，还有侧边栏里「底栏 / 其他」两组。 */
    @Test
    fun tabs() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val vm = vm(listOf(Conversation(title = "保持高效", messages = listOf(ChatMessage(role = "user", content = "你好")))))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(dark()) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(vertical = 12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                        fun items(ts: List<KaviTab>) = ts.map { TabItem(it.icon, it.label) {} }
                        LiquidTabBar(items(KaviTab.bar(null)), 2, androidx.compose.ui.Modifier.padding(horizontal = 12.dp))
                        LiquidTabBar(items(KaviTab.bar(listOf("library"))), 0, androidx.compose.ui.Modifier.padding(horizontal = 12.dp))
                        TabArranger(listOf("memory", "routines"), onOpen = {}, onSave = {})
                    }
                }
            }
        }
        save("tabs-" + dark().name.lowercase())
    }

    @Test
    @Config(qualifiers = "w400dp-h2400dp-xhdpi")
    fun settings() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val vm = vm(emptyList())
        // 填了 Key 才是首页（分组列表）；没填会直接打开 Key 那页
        vm.updateSettings { it.copy(apiKey = "sk-test", userName = "Jack", monthlyBudget = 200.0, quotaWeek = 20_000_000L) }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(dark()) { SettingsScreen(vm, state.settings, {}, {}, {}, { true }) }
        }
        save("settings-" + dark().name.lowercase())
    }

    /** 小组件：用 RemoteViews 真实地展开、画出来，背后垫一张深色「壁纸」。 */
    @Test
    @Config(qualifiers = "w400dp-h860dp-night-xxhdpi")
    fun widgetsNight() = widgets("night")

    @Test
    fun widgetsDay() = widgets("day")

    private fun widgets(name: String) {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val ctx = RuntimeEnvironment.getApplication()
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF3A4A66.toInt(), 0xFFB98A7A.toInt(), 0xFF2E3550.toInt()))
        }
        val now = System.currentTimeMillis()
        val pi = com.jack.ai.Widgets.openApp(ctx, 1)
        val today = com.jack.ai.Widgets.renderList(ctx, "今天", listOf("⏰ 今天 11:00  和产品团队周会" to null, "📅 15:00  写周报" to null,
            "💰 本月 AI 花了 ¥48 / 预算 ¥200" to null), "今天没有安排", pi, meta = "10月27日 周二")
        val recent = com.jack.ai.Widgets.renderList(ctx, "最近对话", listOf("帮我优化这段产品文案  ·  今天 10:18" to null,
            "这张图的风格是什么？  ·  昨天 21:04" to null, "帮我列一个去京都的 5 天行程  ·  昨天" to null), "还没有对话", pi,
            icon = com.jack.ai.R.drawable.ic_wt_bubble, tag = com.jack.ai.R.drawable.widget_tag_slate)
        val ledger = android.widget.RemoteViews(ctx.packageName, com.jack.ai.R.layout.widget_ledger).apply {
            setTextViewText(com.jack.ai.R.id.g_today, "今天 ¥86"); setTextViewText(com.jack.ai.R.id.g_month, "本月 ¥2140 · 记账")
        }
        listOf(today to 520, recent to 480, ledger to 220).forEach { (rv, h) ->
            val v = rv.apply(ctx, root)
            root.addView(v, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, h).apply { bottomMargin = 40 })
        }
        // 桌面图标四种
        val icons = android.widget.LinearLayout(ctx)
        listOf(com.jack.ai.R.mipmap.ic_launcher_k, com.jack.ai.R.mipmap.ic_launcher_bubble, com.jack.ai.R.mipmap.ic_launcher_moon, com.jack.ai.R.mipmap.ic_launcher).forEach { id ->
            icons.addView(android.widget.ImageView(ctx).apply { setImageResource(id) }, android.widget.LinearLayout.LayoutParams(200, 200).apply { marginEnd = 40 })
        }
        root.addView(icons)
        val w = 1200; val h = 2000
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(System.getenv("SHOTS_DIR")!!, "widgets-$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun look() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val vm = vm(emptyList())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(dark(), "pine") { SettingsScreen(vm, state.settings, {}, {}, {}, { false }, startLook = true) }
        }
        save("look-" + dark().name.lowercase())
    }
}
