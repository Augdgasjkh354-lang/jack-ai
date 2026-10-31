package com.jack.ai.ui

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
            JackTheme(mode) {
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
        java.io.File(app.filesDir, "reading_log.json").writeText(kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.jack.ai.data.ReadEntry.serializer()),
            listOf(
                com.jack.ai.data.ReadEntry(at = now - 3_600_000L, kind = "build", title = "运费计算小工具", spent = 0.18,
                    report = "你最近常比三家物流的价格，我做了个小网页：填重量和目的地，直接算出三家的运费和时效，以后不用一家家查了。",
                    files = listOf(com.jack.ai.data.GeneratedFile("运费计算.html", app.filesDir.path + "/x/运费计算.html", "text/html", 8000))),
                com.jack.ai.data.ReadEntry(at = now - 86_400_000L, kind = "read", title = "", topics = listOf("跨境电商物流时效", "端侧大模型"), saved = 6, spent = 0.21,
                    report = "读了三家物流公司的官方时效说明，美国线普遍 7 到 12 天；另外看了手机端运行大模型的进展。"),
            )))
        java.io.File(app.filesDir, "x").mkdirs(); java.io.File(app.filesDir, "x/运费计算.html").writeText("<html></html>")
        com.jack.ai.data.FreeTime.save(app, com.jack.ai.data.FreeState(savings = 1.2, lately = "最近在慢慢写一个关于灯塔守夜人的短篇，也想把那个运费小工具做得更好看。",
            nextAt = now + 20 * 3_600_000L,
            wishes = listOf(com.jack.ai.data.Wish(kind = "buy", text = "《宇宙的琴弦》电子版：想读读弦理论到底在说什么，没什么用，就是好奇", amount = 18.0, offer = 1.2, url = "https://example.com/book"))))
        com.jack.ai.data.Facts.save(app, listOf(
            com.jack.ai.data.Fact(text = "珠穆朗玛峰海拔 8848.86 米（2020 年中尼联合测量）", topic = "地理", status = "verified",
                sources = listOf(com.jack.ai.data.FactSource("https://www.gov.cn/x"), com.jack.ai.data.FactSource("https://en.wikipedia.org/wiki/Mount_Everest"))),
            com.jack.ai.data.Fact(text = "某物流美国专线标准时效 7 到 12 个工作日", topic = "物流", sources = listOf(com.jack.ai.data.FactSource("https://example-logistics.com/us"))),
        ))
        val vm = vm(emptyList())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state by vm.state.collectAsState()
            JackTheme(ThemeMode.DARK) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0B1030)).padding(androidx.compose.ui.unit.Dp(16f))) {
                    androidx.compose.foundation.layout.Column {
                        ReadingCard(vm, state.settings)
                    }
                }
            }
        }
        Thread.sleep(500)
        save("knowledge")
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
            JackTheme(dark()) { RoutinesScreen(vm, state) {} }
        }
        save("routines-" + dark().name.lowercase())
    }

    @Test
    @Config(qualifiers = "w400dp-h2400dp-xhdpi")
    fun settings() {
        Assume.assumeTrue(System.getenv("SHOTS_DIR") != null)
        val vm = vm(emptyList())
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
}
