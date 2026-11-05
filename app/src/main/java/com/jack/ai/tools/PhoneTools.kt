package com.jack.ai.tools

import com.jack.ai.a11y.Element
import com.jack.ai.a11y.KaviAccessibility
import com.jack.ai.a11y.PhoneGuard
import com.jack.ai.a11y.Screen
import com.jack.ai.a11y.ScreenText
import com.wangzan101.mika.core.uistate.ActionEffect
import com.wangzan101.mika.core.uistate.EffectElement
import com.wangzan101.mika.core.uistate.EffectKind
import com.wangzan101.mika.core.uistate.EffectScreen
import com.jack.ai.data.AppUsageNotes
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
    val accessibility = KaviAccessibility.instance
        ?: return if (KaviAccessibility.enabled(app)) "无障碍服务开着但还没连上（可能刚被系统关掉又重开），过几秒再试一次；还不行请用户到 设置 → 操作其他 App 关掉再打开。"
        else "无障碍服务没开，Kavi 碰不到别的 App。请用户到 设置 → 操作其他 App 点「去打开」（安卓 13 以上要先在应用信息里「允许受限制的设置」）。"
    // 存流程、读流程不用碰屏幕
    if (action == "flow" || action == "save_flow") return phoneFlow(action, args)
    val openWant = if (action == "open") args["app"].text().trim() else ""
    val openPkg = if (action == "open") {
        if (openWant.isBlank()) return "open 要填 app（App 名字或包名）。"
        allowed.firstOrNull { it == openWant }
            ?: allowed.firstOrNull { accessibility.appLabel(it) == openWant }
            ?: allowed.firstOrNull { openWant in accessibility.appLabel(it) || accessibility.appLabel(it) in openWant }
            ?: return "「$openWant」不在用户勾的 App 里（勾了：${allowed.joinToString("、") { accessibility.appLabel(it) }}）。要用的话请用户在 设置 → 操作其他 App 里勾上。"
    } else null
    val openPolicy = openPkg?.let(AppUsageNotes::policyForPackage)
    // 有时模型第一步就是 read：这时也要从主屏正在显示的 App 判断，不能先建虚拟屏再发现是敏感 App。
    val mainPkg = if (action != "open" && action != "apps" && phoneControl == null) accessibility.foreground() else null
    val mainPolicy = mainPkg?.let(AppUsageNotes::policyForPackage)
    val forceMain = openPolicy?.mainScreenOnly == true || mainPolicy?.mainScreenOnly == true
    val protectedPkg = openPkg?.takeIf { openPolicy?.mainScreenOnly == true } ?: mainPkg?.takeIf { mainPolicy?.mainScreenOnly == true }
    if (forceMain && settings.phoneBackground && !interactive) {
        return "「${accessibility.appLabel(protectedPkg!!)}」为保护登录状态只允许在主屏操作，后台定时任务不打开它。请用户在聊天里前台发起这次操作。"
    }
    // 列 App 不建虚拟屏；标成主屏专用的 App 不进入 Mika 虚拟屏。
    val svc = try {
        (if (action == "apps") com.jack.ai.a11y.PhoneControl(accessibility, null)
        else claimPhone(accessibility, forceMain = forceMain)).also { it.ready() }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { return "手机操作未开始：${ToolSession.errorText(e)}" }
    // 手机锁着（多半是定时任务在后台跑）：推送请用户解锁，等两分钟
    if (locked()) {
        if (interactive) return "手机锁着屏，Kavi 没法操作。请用户解锁后再说一声。"
        com.jack.ai.data.Routines.notify(app, "phone-unlock", "Kavi 要用一下手机", "后台任务要操作手机上的 App，解锁后它会接着做（两分钟内）。")
        val end = System.currentTimeMillis() + 120_000
        while (locked() && System.currentTimeMillis() < end) delay(2000)
        if (locked()) return "手机一直锁着屏，没法操作，已推送通知提醒用户。任务到这里停下，结果里写明「需要解锁手机才能做」。"
    }
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
        AppUsageNotes.policyForPackage(pkg).signedOutMarker(screen.texts())?.let {
            phoneSeen = null
            return null to "检测到「$it」，账号已经回到登录页。为保护账号，这一轮停止操作；请用户在主屏手动登录后再继续。"
        }
        phoneSeen = screen
        val note = StringBuilder(flowHint(pkg)).append(ScreenText.format(screen))
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

    /** 记一笔实测结果（隐私项目里不记）。 */
    suspend fun stat(appName: String, outcome: String) {
        if (project?.isolated == true || appName.isBlank()) return
        withContext(Dispatchers.IO) { runCatching { com.jack.ai.data.PhoneStats.record(app, appName, action, outcome) } }
    }

    /** 现在前台的 App 名（只算用户勾过的）。 */
    fun fgName(): String = svc.foreground()?.takeIf { it in allowed }?.let { label(it) }.orEmpty()

    /** 把 Kavi 的控件清单转成 Mika 效果判断的纯逻辑输入，不暴露密码内容。 */
    fun Screen.effectScreen() = EffectScreen(pkg, elements.map { e ->
        EffectElement(e.role, e.label, if (e.password) "" else e.value, e.bounds.toList(), e.checked, e.password)
    })

    /** 动作后读回：没看见变化就提醒模型先检查，绝不自动补点。 */
    suspend fun after(done: String, before: Screen?, typed: String? = null): String {
        svc.settle()
        val (now, screenText) = read()
        val effect = ActionEffect.classify(before?.effectScreen(), now?.effectScreen(), typed)
        before?.let { stat(it.app, if (effect == EffectKind.NO_EFFECT) "no_change" else "ok") }
        val result = when (effect) {
            EffectKind.CHANGED -> "操作效果：界面明显变了。"
            EffectKind.NO_EFFECT -> when (action) {
                "tap", "long_press" -> "操作效果：点了没反应（控件树前后没有变化）。先检查新清单或截图，不要原样重试，避免重复发送或提交。"
                "type" -> "操作效果：输入后控件树没变化，文字可能没填进去；先检查输入框。"
                "swipe", "scroll" -> "操作效果：划动后控件树没变化，先检查是不是已经到底。"
                else -> "操作效果：控件树没有变化，先检查实际界面。"
            }
            EffectKind.UNCERTAIN -> "操作效果：拿不准（界面变化太小或控件树读不全），先查看新清单再决定下一步。"
        }
        return "$done\n$result\n\n$screenText"
    }

    fun needScreen(): Screen? = phoneSeen?.takeIf { it.pkg == svc.foreground() }

    /** 对标成 careful 的 App 强制放慢动作，并限制一轮里连续操作的次数（翻页、划只放慢，不计数）。 */
    suspend fun beforeAction(s: Screen, counts: Boolean = true): String? {
        val policy = AppUsageNotes.policyForPackage(s.pkg)
        if (policy.actionIntervalMs <= 0) return null
        if (counts) {
            val count = (phoneActionCounts[s.pkg] ?: 0) + 1
            if (count > policy.actionLimit) {
                phoneSeen = null
                return "为了保护「${s.app}」账号，这一轮已经连续操作 ${policy.actionLimit} 次，先停在这里。不要继续刷或反复打开页面，稍后由用户重新发起。"
            }
            phoneActionCounts[s.pkg] = count
        }
        val last = phoneLastActionAt[s.pkg] ?: 0L
        val wait = (policy.actionIntervalMs - (System.currentTimeMillis() - last)).coerceAtLeast(0L)
        if (wait > 0) delay(wait)
        phoneLastActionAt[s.pkg] = System.currentTimeMillis()
        return null
    }

    val bar = when (action) {
        "open" -> "打开 " + args["app"].text()
        "tap", "long_press" -> (if (action == "tap") "点 " else "长按 ") + (args["text"].text().ifBlank { args["n"].text().let { n -> if (n.isNotBlank()) "第 $n 项" else "屏幕" } })
        "type" -> "输入文字"
        "read", "screenshot", "wait", "apps" -> "看屏幕"
        else -> action
    }
    val toolLabel = "操作手机：$bar"
    /** 没做成：记一笔，把话交给模型。 */
    suspend fun fail(appName: String, outcome: String, msg: String): Pair<String, SearchRecord> {
        stat(appName, outcome)
        return msg to SearchRecord(toolLabel)
    }
    return runTool(toolLabel) {
        if (action != "apps") svc.showBar(bar)
        val text: String = when (action) {
            "apps" -> withContext(Dispatchers.IO) {
                "用户勾了这些 App 给你操作：\n" + allowed.joinToString("\n") { p -> "- ${label(p)}（$p）" + if (app.packageManager.getLaunchIntentForPackage(p) == null) "：没装或打不开" else "" }
            }
            "open" -> {
                val pkg = openPkg ?: return@runTool "open 要填 app（App 名字或包名）。" to SearchRecord(toolLabel)
                PhoneGuard.blocked(pkg, label(pkg), app.packageName)?.let { return@runTool "「${label(pkg)}」$it。" to SearchRecord(toolLabel) }
                if (!svc.open(pkg)) return@runTool fail(label(pkg), "failed", "打不开「${label(pkg)}」（可能没装）。")
                // 冷启动慢：等它到前台
                repeat(20) { if (svc.foreground() != pkg) delay(250) }
                svc.settle(4000)
                stat(label(pkg), if (svc.foreground() == pkg) "ok" else "no_change")
                (if (openPolicy?.mainScreenOnly == true) "为保护登录状态，这个 App 只在主屏操作。\n\n" else "") + read().second
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
                val s = needScreen() ?: return@runTool fail(fgName(), "stale", "先用 read 看一下现在的屏幕（界面变了或还没读过）。")
                beforeAction(s)?.let { return@runTool it to SearchRecord(toolLabel) }
                val long = action == "long_press"
                val verb = if (long) "长按" else "点"
                val x = args["x"].text().toFloatOrNull(); val y = args["y"].text().toFloatOrNull()
                val e = target(s)
                if (e == null && (x == null || y == null)) return@runTool fail(s.app, "miss", "要填 n（清单编号）、text（按钮上的字）或 x、y（截图上的坐标）。清单里没有对得上的。")
                if (e != null) {
                    check(s, e.label, verb)?.let { return@runTool it to SearchRecord(toolLabel) }
                    if (!e.enabled) return@runTool "「${e.label}」现在点不了（灰的）。" to SearchRecord(toolLabel)
                    if (!svc.click(e, long)) return@runTool fail(s.app, "failed", "没点成「${e.label}」。可以 read 重看一下，或者截图按坐标点。")
                    after("已${verb}「${e.label.ifBlank { "第 ${e.n} 项" }}」。", s)
                } else {
                    val px = (x!! * phoneScale).toInt(); val py = (y!! * phoneScale).toInt()
                    s.at(px, py)?.let { under -> check(s, under.label, verb)?.let { return@runTool it to SearchRecord(toolLabel) } }
                    if (!svc.tap(px, py, long)) return@runTool fail(s.app, "failed", "没点成。")
                    after("已${verb}屏幕上 ($px, $py)。", s)
                }
            }
            "type" -> {
                val s = needScreen() ?: return@runTool fail(fgName(), "stale", "先用 read 看一下现在的屏幕。")
                beforeAction(s)?.let { return@runTool it to SearchRecord(toolLabel) }
                val content = args["content"].text().ifEmpty { args["value"].text() }
                val e = (args["n"].text().toIntOrNull()?.let { s.byNumber(it) } ?: s.elements.firstOrNull { it.editable })
                    ?: return@runTool fail(s.app, "miss", "这一屏没有输入框。先点开要输入的地方。")
                if (e.password) return@runTool "这是密码框，Kavi 不填。请用户自己输入。" to SearchRecord(toolLabel)
                if (!e.editable) return@runTool fail(s.app, "miss", "第 ${e.n} 项不是输入框。")
                if (!svc.type(e, content, settings.phonePace.typing())) return@runTool fail(s.app, "failed", "没填进去。可以先 tap 点一下输入框再试。")
                after("已在输入框里填好（还没发出去；要发就点「发送」或用 enter）。", s, typed = content)
            }
            "enter" -> {
                val s = needScreen() ?: return@runTool fail(fgName(), "stale", "先用 read 看一下现在的屏幕。")
                beforeAction(s)?.let { return@runTool it to SearchRecord(toolLabel) }
                val typed = s.elements.firstOrNull { it.editable && !it.password && it.value.isNotBlank() }?.value.orEmpty()
                // 回车常常就是「发送」「搜索」：搜索不问，别的按发送算
                val search = s.elements.any { it.editable && "搜索" in (it.label + it.value) }
                if (!search && !approve("让 Kavi 在「${s.app}」按回车（可能直接发出去）？", if (typed.isNotEmpty()) "输入框里：${typed.take(200)}" else "输入框是空的。", "按")) {
                    return@runTool "用户没同意按回车，没有按。" to SearchRecord(toolLabel)
                }
                if (!svc.imeEnter()) return@runTool fail(s.app, "failed", "按不了回车（要安卓 11 以上，或者输入框不支持）。找一下页面上的「发送」「搜索」按钮。")
                after("已按回车。", s)
            }
            "scroll", "swipe" -> {
                val s = needScreen()
                if (s != null) beforeAction(s, counts = false)?.let { return@runTool it to SearchRecord(toolLabel) }
                if (action == "swipe") {
                    val (x1, y1, x2, y2) = listOf("x", "y", "x2", "y2").map { args[it].text().toFloatOrNull() }
                    if (x1 == null || y1 == null || x2 == null || y2 == null) return@runTool "swipe 要填 x、y、x2、y2（截图上的像素）。翻列表用 scroll 更简单。" to SearchRecord(toolLabel)
                    if (s == null) return@runTool fail(fgName(), "stale", "先用 read 看一下现在的屏幕。")
                    val k = phoneScale
                    if (!svc.swipe((x1 * k).toInt(), (y1 * k).toInt(), (x2 * k).toInt(), (y2 * k).toInt()))
                        return@runTool fail(s.app, "failed", "没划成，先用 read 看一下屏幕。")
                    after("已划过。", s)
                } else {
                    if (s == null) return@runTool fail(fgName(), "stale", "先用 read 看一下现在的屏幕。")
                    val dir = args["direction"].text().ifBlank { "down" }
                    val list = args["n"].text().toIntOrNull()?.let { s.byNumber(it) } ?: s.elements.filter { it.scrollable }.maxByOrNull { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) }
                    if (!svc.scroll(list, dir)) return@runTool fail(s.app, "failed", "没翻成，先用 read 看一下屏幕。")
                    after(before = s, done = "已往${mapOf("down" to "下", "up" to "上", "left" to "左", "right" to "右")[dir] ?: dir}翻。")
                }
            }
            "back" -> {
                val pkg = svc.foreground() ?: ""
                refuse(pkg)?.let { return@runTool it to SearchRecord(toolLabel) }
                val before = phoneSeen?.takeIf { it.pkg == pkg }
                val paceStop = before?.let { beforeAction(it) }
                if (paceStop != null) return@runTool paceStop to SearchRecord(toolLabel)
                svc.back()
                after("已返回。", before)
            }
            "home" -> {
                if (svc.background != null) "后台屏幕没有手机桌面。要换 App 请用 open；不要操作主屏。"
                else { svc.home(); phoneSeen = null; delay(500); "已回到桌面。" }
            }
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

/** 手机锁着屏或屏幕灭着。 */
private fun ToolSession.locked(): Boolean {
    val km = app.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
    val pm = app.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    return km.isKeyguardLocked || !pm.isInteractive
}

/** 首次读到这个 App 时，合并内置使用知识、用户备忘和已存流程（每轮每个 App 只给一次）。 */
private suspend fun ToolSession.flowHint(pkg: String): String {
    val site = com.jack.ai.data.BrowserFlows.APP_PREFIX + pkg
    if (site in flowHinted) return ""
    val hint = withContext(Dispatchers.IO) {
        val flows = host.store.loadFlows().filter { it.site == site }
        val notes = com.jack.ai.data.BrowserFlows.notesFor(host.store.loadSiteNotes(), site) +
            com.jack.ai.data.BrowserFlows.builtinNotesFor(site)
        com.jack.ai.data.BrowserFlows.hint(flows, notes, app = true)
    }
    return if (hint.isNotEmpty() && flowHinted.add(site)) "$hint\n" else ""
}

/** 读一个手机流程的步骤（记一次使用），或者存 / 更新一个（存的是文字步骤，照着做时发送、付款照样要用户确认）。 */
private suspend fun ToolSession.phoneFlow(action: String, args: JsonObject): String {
    val store = host.store
    val prefix = com.jack.ai.data.BrowserFlows.APP_PREFIX
    val flows = withContext(Dispatchers.IO) { store.loadFlows() }
    val mine = flows.filter { it.site.startsWith(prefix) }
    val name = args["name"].text().trim()
    if (name.isEmpty()) return "要填 name（流程名）。现有手机流程：\n" + com.jack.ai.data.BrowserFlows.index(mine)
    if (action == "flow") {
        val f = com.jack.ai.data.BrowserFlows.find(mine, name)
            ?: return "没有叫「$name」的手机流程。现有手机流程：\n" + com.jack.ai.data.BrowserFlows.index(mine)
        withContext(Dispatchers.IO) {
            store.saveFlows(store.loadFlows().map { if (it.id == f.id) it.copy(uses = it.uses + 1, usedAt = System.currentTimeMillis()) else it })
        }
        val blanks = com.jack.ai.data.BrowserFlows.blanks(f.steps)
        return "【手机流程「${f.name}」】App：${f.site.removePrefix(prefix)}" + f.whenToUse.takeIf { it.isNotBlank() }?.let { "\n什么时候用：$it" }.orEmpty() +
            (if (blanks.isNotEmpty()) "\n要填的空：" + blanks.joinToString("、") { "{$it}" } + "（换成这次用户给的值；必须有又没给的先问用户）" else "") +
            "\n步骤：\n${f.steps}\n\n先 open 这个 App，再照着做；界面和步骤对不上就按意思灵活处理，做完告诉用户哪一步变了、问要不要更新流程。"
    }
    val appArg = args["app"].text().trim()
    val pkg = settings.phoneApps.firstOrNull { it == appArg }
        ?: settings.phoneApps.firstOrNull { p -> com.jack.ai.a11y.KaviAccessibility.instance?.appLabel(p)?.let { it == appArg || appArg in it } == true }
        ?: return "app 要填用户勾过的 App（名字或包名）。勾了：" + settings.phoneApps.joinToString("、")
    val steps = args["steps"].text().trim().take(6000)
    if (steps.isEmpty()) return "steps 要填编号的文字步骤。"
    val site = prefix + pkg
    val old = flows.firstOrNull { it.name == name }
    val saved = (old ?: com.jack.ai.data.BrowserFlow(name = name, site = site, steps = steps)).copy(name = name, site = site, whenToUse = args["when"].text().trim().take(200), steps = steps)
    withContext(Dispatchers.IO) {
        val now = store.loadFlows()
        store.saveFlows(if (now.any { it.id == saved.id }) now.map { if (it.id == saved.id) saved else it } else now + saved)
    }
    ui.notice((if (old != null) "已更新" else "已存成") + "手机流程「$name」，在 技能 → 浏览器流程 里能改、能删")
    return (if (old != null) "已更新" else "已存下") + "手机流程「$name」。下次打开这个 App 时会提醒，用 action=flow 读出步骤照着做；也能用 schedule 定时让 Kavi 照着跑。"
}
