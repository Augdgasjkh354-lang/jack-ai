package com.jack.ai.tools

import com.jack.ai.a11y.Element
import com.jack.ai.a11y.KaviAccessibility
import com.jack.ai.a11y.PhoneGuard
import com.jack.ai.a11y.Screen
import com.jack.ai.a11y.ScreenText
import com.jack.ai.data.SearchRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * 操作其他 App（phone 工具，无障碍）：apps 列出能操作的 App、open 打开、read 读屏幕、tap / long_press 点、type 填字、
 * enter 按输入法的回车（发送）、scroll / swipe 滑、back 返回、home 回桌面、wait 等、screenshot 截图。
 * 规矩（见 PhoneGuard）：只碰用户勾过的 App；涉及钱的 App 和付款页面不碰；发送、删除这类按钮先问；密码框不读不填；
 * 用户在悬浮条上按了「停止」，这一轮就不再操作。每次操作完自动读一遍新的屏幕，省一次调用。
 */
internal suspend fun ToolSession.phone(args: JsonObject): String {
    val action = args["action"].text().trim()
    val allowed = settings.phoneApps
    if (allowed.isEmpty()) return "用户还没勾 Kavi 能操作的 App。请用户到 设置 → 操作其他 App 里勾上要用的 App（涉及钱的勾不了）。"
    val svc = KaviAccessibility.instance
        ?: return if (KaviAccessibility.enabled(app)) "无障碍服务开着但还没连上（可能刚被系统关掉又重开），过几秒再试一次；还不行请用户到 设置 → 操作其他 App 关掉再打开。"
        else "无障碍服务没开，Kavi 碰不到别的 App。请用户到 设置 → 操作其他 App 点「去打开」（安卓 13 以上要先在应用信息里「允许受限制的设置」）。"
    // 用户按了停止：这一轮不再操作
    val stopsNow = KaviAccessibility.stops.get()
    if (phoneStops < 0) phoneStops = stopsNow
    else if (stopsNow != phoneStops) return "用户在悬浮条上按了「停止」。这一轮不要再操作手机，告诉用户停在了哪一步、还差什么。"

    fun label(pkg: String) = svc.appLabel(pkg)
    fun refuse(pkg: String): String? {
        val name = label(pkg)
        PhoneGuard.blocked(pkg, name, app.packageName)?.let { return "现在前台是「$name」：$it。停下，告诉用户这一步要自己来。" }
        if (pkg !in allowed) return "现在前台是「$name」，不在用户勾的 App 里，Kavi 不能看也不能点。要用的话请用户在 设置 → 操作其他 App 里勾上它。"
        return null
    }

    /** 读当前屏幕：查能不能碰、是不是付款页面，记下来给后面按编号操作。 */
    suspend fun read(): Pair<Screen?, String> {
        val (pkg, root) = svc.snapshot() ?: return null to "读不到屏幕（可能在锁屏或系统界面上）。"
        refuse(pkg)?.let { phoneSeen = null; return null to it }
        val screen = Screen(pkg, label(pkg), ScreenText.elements(root))
        PhoneGuard.payPage(screen.texts())?.let { phoneSeen = null; return null to "到了付款或输密码的页面（看到「$it」），Kavi 不碰。停下，告诉用户这一步要自己来。" }
        phoneSeen = screen
        val note = StringBuilder(ScreenText.format(screen))
        // 读不到几个字的（自己画的界面、游戏）：能看图就自动截一张
        if (screen.elements.size < 4 && canSee) svc.screenshot(screen.elements)?.let { (img, k) ->
            images += img; phoneScale = k
            note.append("这一屏读到的字很少，自动截了图（在下一条消息里），红框上的数字是清单编号；要点图里别的地方用 tap 的 x、y（截图上的像素）。\n")
        }
        return screen to note.toString()
    }

    /** 要操作的那一项：n 编号，或 text 按文字找。 */
    fun target(s: Screen): Element? = args["n"].text().toIntOrNull()?.let { s.byNumber(it) } ?: args["text"].text().takeIf { it.isNotBlank() }?.let { s.byText(it) }

    /** 点一个按钮前的关：花钱的不点；发送、删除这类先问（问的时候带上输入框里的字，用户才知道要发什么）。 */
    suspend fun check(s: Screen, what: String, verb: String): String? = when (PhoneGuard.risk(what)) {
        PhoneGuard.Risk.MONEY -> "「$what」会花钱，Kavi 不点。停下，告诉用户这一步要自己来。"
        PhoneGuard.Risk.ASK -> {
            val typed = s.elements.filter { it.editable && !it.password && it.value.isNotBlank() }.joinToString("\n") { "输入框里：${it.value.take(200)}" }
            if (approve("让 Kavi 在「${s.app}」$verb「$what」？", (if (typed.isNotEmpty()) "$typed\n\n" else "") + "点了可能就发出去、删掉或提交了。", verb)) null
            else "用户没同意$verb「$what」，没有点。不要换个办法再点它，问用户想怎么做。"
        }
        PhoneGuard.Risk.OK -> null
    }

    /** 做完一步：等界面停下来，再读一遍。 */
    suspend fun after(done: String): String {
        svc.settle()
        val (_, text) = read()
        return "$done\n\n$text"
    }

    fun needScreen(): Screen? = phoneSeen?.takeIf { it.pkg == svc.foreground() }

    val bar = when (action) {
        "open" -> "打开 " + args["app"].text()
        "tap", "long_press" -> (if (action == "tap") "点 " else "长按 ") + (args["text"].text().ifBlank { args["n"].text().let { n -> if (n.isNotBlank()) "第 $n 项" else "屏幕" } })
        "type" -> "输入文字"
        "read", "screenshot", "wait", "apps" -> "看屏幕"
        else -> action
    }
    val toolLabel = "操作手机：$bar"
    return runTool(toolLabel) {
        if (action != "apps") svc.showBar(bar)
        val text: String = when (action) {
            "apps" -> withContext(Dispatchers.IO) {
                "用户勾了这些 App 给你操作：\n" + allowed.joinToString("\n") { p -> "- ${label(p)}（$p）" + if (app.packageManager.getLaunchIntentForPackage(p) == null) "：没装或打不开" else "" }
            }
            "open" -> {
                val want = args["app"].text().trim()
                if (want.isBlank()) return@runTool "open 要填 app（App 名字或包名）。" to SearchRecord(toolLabel)
                val pkg = allowed.firstOrNull { it == want } ?: allowed.firstOrNull { label(it) == want } ?: allowed.firstOrNull { want in label(it) || label(it) in want }
                    ?: return@runTool ("「$want」不在用户勾的 App 里（勾了：${allowed.joinToString("、") { label(it) }}）。要用的话请用户在 设置 → 操作其他 App 里勾上。") to SearchRecord(toolLabel)
                PhoneGuard.blocked(pkg, label(pkg), app.packageName)?.let { return@runTool "「${label(pkg)}」$it。" to SearchRecord(toolLabel) }
                if (!svc.open(pkg)) return@runTool "打不开「${label(pkg)}」（可能没装）。" to SearchRecord(toolLabel)
                // 冷启动慢：等它到前台
                repeat(20) { if (svc.foreground() != pkg) delay(250) }
                svc.settle(4000)
                read().second
            }
            "read" -> read().second
            "screenshot" -> {
                if (!canSee) return@runTool "当前模型看不了图，用 read 读屏幕上的字和按钮。" to SearchRecord(toolLabel)
                val (s, t) = read()
                if (s == null) return@runTool t to SearchRecord(toolLabel)
                val (img, k) = svc.screenshot(s.elements) ?: return@runTool "截不了图（要安卓 11 以上；或者截得太频繁，等一秒再试）。下面是读到的清单：\n$t" to SearchRecord(toolLabel)
                images += img; phoneScale = k
                "截图在下一条消息里，红框上的数字就是下面清单的编号；按图上的位置点用 tap 的 x、y（截图上的像素）。\n$t"
            }
            "tap", "long_press" -> {
                val s = needScreen() ?: return@runTool "先用 read 看一下现在的屏幕（界面变了或还没读过）。" to SearchRecord(toolLabel)
                val long = action == "long_press"
                val verb = if (long) "长按" else "点"
                val x = args["x"].text().toFloatOrNull(); val y = args["y"].text().toFloatOrNull()
                val e = target(s)
                if (e == null && (x == null || y == null)) return@runTool "要填 n（清单编号）、text（按钮上的字）或 x、y（截图上的坐标）。清单里没有对得上的。" to SearchRecord(toolLabel)
                if (e != null) {
                    check(s, e.label, verb)?.let { return@runTool it to SearchRecord(toolLabel) }
                    if (!e.enabled) return@runTool "「${e.label}」现在点不了（灰的）。" to SearchRecord(toolLabel)
                    if (!svc.click(e, long)) return@runTool "没点成「${e.label}」。可以 read 重看一下，或者截图按坐标点。" to SearchRecord(toolLabel)
                    after("已${verb}「${e.label.ifBlank { "第 ${e.n} 项" }}」。")
                } else {
                    val px = (x!! * phoneScale).toInt(); val py = (y!! * phoneScale).toInt()
                    s.at(px, py)?.let { under -> check(s, under.label, verb)?.let { return@runTool it to SearchRecord(toolLabel) } }
                    if (!svc.tap(px, py, long)) return@runTool "没点成。" to SearchRecord(toolLabel)
                    after("已${verb}屏幕上 ($px, $py)。")
                }
            }
            "type" -> {
                val s = needScreen() ?: return@runTool "先用 read 看一下现在的屏幕。" to SearchRecord(toolLabel)
                val content = args["content"].text().ifEmpty { args["value"].text() }
                val e = (args["n"].text().toIntOrNull()?.let { s.byNumber(it) } ?: s.elements.firstOrNull { it.editable })
                    ?: return@runTool "这一屏没有输入框。先点开要输入的地方。" to SearchRecord(toolLabel)
                if (e.password) return@runTool "这是密码框，Kavi 不填。请用户自己输入。" to SearchRecord(toolLabel)
                if (!e.editable) return@runTool "第 ${e.n} 项不是输入框。" to SearchRecord(toolLabel)
                if (!svc.type(e, content)) return@runTool "没填进去。可以先 tap 点一下输入框再试。" to SearchRecord(toolLabel)
                after("已在输入框里填好（还没发出去；要发就点「发送」或用 enter）。")
            }
            "enter" -> {
                val s = needScreen() ?: return@runTool "先用 read 看一下现在的屏幕。" to SearchRecord(toolLabel)
                val typed = s.elements.firstOrNull { it.editable && !it.password && it.value.isNotBlank() }?.value.orEmpty()
                // 回车常常就是「发送」「搜索」：搜索不问，别的按发送算
                val search = s.elements.any { it.editable && "搜索" in (it.label + it.value) }
                if (!search && !approve("让 Kavi 在「${s.app}」按回车（可能直接发出去）？", if (typed.isNotEmpty()) "输入框里：${typed.take(200)}" else "输入框是空的。", "按")) {
                    return@runTool "用户没同意按回车，没有按。" to SearchRecord(toolLabel)
                }
                if (!svc.imeEnter()) return@runTool "按不了回车（要安卓 11 以上，或者输入框不支持）。找一下页面上的「发送」「搜索」按钮。" to SearchRecord(toolLabel)
                after("已按回车。")
            }
            "scroll", "swipe" -> {
                val s = needScreen()
                if (action == "swipe") {
                    val (x1, y1, x2, y2) = listOf("x", "y", "x2", "y2").map { args[it].text().toFloatOrNull() }
                    if (x1 == null || y1 == null || x2 == null || y2 == null) return@runTool "swipe 要填 x、y、x2、y2（截图上的像素）。翻列表用 scroll 更简单。" to SearchRecord(toolLabel)
                    if (s == null) return@runTool "先用 read 看一下现在的屏幕。" to SearchRecord(toolLabel)
                    val k = phoneScale
                    svc.swipe((x1 * k).toInt(), (y1 * k).toInt(), (x2 * k).toInt(), (y2 * k).toInt())
                    after("已划过。")
                } else {
                    if (s == null) return@runTool "先用 read 看一下现在的屏幕。" to SearchRecord(toolLabel)
                    val dir = args["direction"].text().ifBlank { "down" }
                    val list = args["n"].text().toIntOrNull()?.let { s.byNumber(it) } ?: s.elements.filter { it.scrollable }.maxByOrNull { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) }
                    svc.scroll(list, dir)
                    after("已往${mapOf("down" to "下", "up" to "上", "left" to "左", "right" to "右")[dir] ?: dir}翻。")
                }
            }
            "back" -> {
                val pkg = svc.foreground() ?: ""
                refuse(pkg)?.let { return@runTool it to SearchRecord(toolLabel) }
                svc.back()
                after("已返回。")
            }
            "home" -> { svc.home(); phoneSeen = null; delay(500); "已回到桌面。" }
            "wait" -> {
                val want = args["text"].text().trim()
                val secs = args["seconds"].text().toFloatOrNull()?.coerceIn(0.5f, 15f) ?: if (want.isNotEmpty()) 10f else 2f
                val end = System.currentTimeMillis() + (secs * 1000).toLong()
                if (want.isEmpty()) { delay((secs * 1000).toLong()); read().second }
                else {
                    var last = ""
                    while (true) {
                        val (s, t) = read()
                        last = t
                        if (s == null || s.texts().any { want in it } || System.currentTimeMillis() > end) break
                        delay(700)
                    }
                    (if (phoneSeen?.texts()?.any { want in it } == true) "等到了「$want」。\n\n" else "等了 ${secs.toInt()} 秒还没出现「$want」。\n\n") + last
                }
            }
            else -> return@runTool "action 要填 apps、open、read、tap、long_press、type、enter、scroll、swipe、back、home、wait 或 screenshot。" to SearchRecord(toolLabel)
        }
        text to SearchRecord(toolLabel)
    }
}
