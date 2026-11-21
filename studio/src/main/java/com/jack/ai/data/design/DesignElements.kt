package com.jack.ai.data.design

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 设计的元素库：贴纸（assets/design/stickers/，Muse 画的）、形状、图标（Lucide + Tabler）、纹理。
 * 编辑器「加元素」面板从这里拿；Kavi 从头设计时写 <div data-sticker="贴纸id">，出图时 fill.js 把 SVG 填进去（和 data-icon 一样，底稿里不存图形）。
 * 颜色都走六个配色变量或 currentColor，整张换配色时跟着变。
 */
object DesignElements {
    @Serializable
    data class Category(val id: String, val name: String)

    @Serializable
    data class Sticker(
        val id: String, val category: String, val name: String, val tags: List<String> = emptyList(),
        val style: String = "", val recolor: String = "fill", val ratio: String = "square",
        val viewBox: List<Double> = listOf(0.0, 0.0, 100.0, 100.0), val textArea: List<Double>? = null, val tile: Boolean = false,
    ) {
        /** 加到 1080 宽的画布上默认多大（宽、高，画布像素）。 */
        fun size(): Pair<Int, Int> {
            val w0 = viewBox.getOrElse(2) { 100.0 }.coerceAtLeast(1.0)
            val h0 = viewBox.getOrElse(3) { 100.0 }.coerceAtLeast(1.0)
            val w = when { tile -> 1080.0; ratio == "wide" -> 380.0; ratio == "tall" -> 320.0 * w0 / h0; else -> 240.0 }
            return w.toInt() to (w * h0 / w0).toInt()
        }
    }

    @Serializable
    data class Library(val version: Int = 1, val categories: List<Category> = emptyList(), val stickers: List<Sticker> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: Library? = null

    fun library(ctx: Context): Library = cache ?: runCatching {
        json.decodeFromString(Library.serializer(), ctx.assets.open("design/stickers.json").use { it.readBytes().toString(Charsets.UTF_8) })
    }.getOrDefault(Library()).also { cache = it }

    /** 贴纸的 SVG：去掉元数据，撑满外面的框。没有这张时返回 null。 */
    fun stickerSvg(ctx: Context, id: String): String? {
        if (!Regex("[a-z0-9-]+").matches(id)) return null
        val raw = runCatching { ctx.assets.open("design/stickers/$id.svg").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: return null
        return fit(raw.replace(Regex("<metadata>[\\s\\S]*?</metadata>"), "").replace(Regex("<\\?xml[^>]*>"), "").trim())
    }

    /** 根 <svg> 撑满外面的框（贴纸文件里没写宽高）。 */
    fun fit(svg: String): String =
        svg.replaceFirst(Regex("<svg\\b"), "<svg width=\"100%\" height=\"100%\" style=\"display:block;overflow:visible\"")

    /** 页面里 data-sticker 用到的贴纸 → SVG（出图、打开编辑器时填进去）。 */
    fun stickers(ctx: Context, html: String): Map<String, String> =
        Regex("""data-sticker\s*=\s*["']([a-z0-9-]+)["']""").findAll(html).map { it.groupValues[1] }.distinct()
            .mapNotNull { id -> stickerSvg(ctx, id)?.let { id to it } }.toMap()

    /** 按名字、标签、类别找贴纸；空的就是全部。 */
    fun search(lib: Library, query: String, category: String? = null): List<Sticker> {
        val q = query.trim()
        val cats = lib.categories.associate { it.id to it.name }
        return lib.stickers.filter { s ->
            (category == null || s.category == category) &&
                (q.isEmpty() || q in s.name || s.tags.any { q in it || it in q } || q in (cats[s.category] ?: "") || q in s.id)
        }
    }

    // ---------- 形状 ----------

    class Shape(val id: String, val name: String, val svg: String, val w: Int, val h: Int, val stretch: Boolean = false) {
        /** 线条类（直线、箭头、波浪）：「颜色」改线条色，「粗细」改线宽。 */
        val line get() = "fill:none" in svg
    }

    /** 填充用 --kv-fill（默认主色），描边用 --kv-stroke 和 --kv-sw（默认没有描边），编辑器里「颜色」「描边」改的就是这几个。 */
    private const val FILL = "fill:var(--kv-fill,var(--primary));stroke:var(--kv-stroke,var(--fg));stroke-width:var(--kv-sw,0);vector-effect:non-scaling-stroke"
    private const val LINE = "fill:none;stroke:var(--kv-fill,var(--primary));stroke-width:var(--kv-sw,8);stroke-linecap:round;stroke-linejoin:round;vector-effect:non-scaling-stroke"

    private fun svg(vb: String, body: String, stretch: Boolean = false) =
        """<svg viewBox="$vb" width="100%" height="100%"${if (stretch) " preserveAspectRatio=\"none\"" else ""} style="display:block;overflow:visible">$body</svg>"""

    val SHAPES: List<Shape> = listOf(
        Shape("rect", "矩形", svg("0 0 100 100", """<rect width="100" height="100" style="$FILL"/>""", true), 360, 240, stretch = true),
        Shape("round-rect", "圆角矩形", svg("0 0 100 100", """<rect width="100" height="100" rx="14" style="$FILL"/>""", true), 360, 240, stretch = true),
        Shape("circle", "圆", svg("0 0 100 100", """<circle cx="50" cy="50" r="50" style="$FILL"/>"""), 240, 240),
        Shape("pill", "胶囊", svg("0 0 200 80", """<rect width="200" height="80" rx="40" style="$FILL"/>"""), 320, 128),
        Shape("line", "直线", svg("0 0 100 10", """<line x1="2" y1="5" x2="98" y2="5" style="$LINE"/>""", true), 480, 24, stretch = true),
        Shape("arrow", "箭头", svg("0 0 120 40", """<path d="M4 20H112M92 4l20 16-20 16" style="$LINE"/>"""), 300, 100),
        Shape("star", "星形", svg("0 0 100 100", """<path d="M50 3l13.8 30.6 33.4 3.6-25 22.6 7 32.8L50 76 20.8 92.6l7-32.8-25-22.6 33.4-3.6z" style="$FILL"/>"""), 220, 220),
        Shape("bubble", "对话气泡", svg("0 0 120 100", """<path d="M14 4h92a10 10 0 0 1 10 10v52a10 10 0 0 1-10 10H46L24 96V76H14A10 10 0 0 1 4 66V14A10 10 0 0 1 14 4z" style="$FILL"/>"""), 320, 266),
        Shape("wave", "波浪线", svg("0 0 200 30", """<path d="M4 15c16-14 32-14 48 0s32 14 48 0 32-14 48 0 32 14 48 0" style="$LINE"/>""", true), 420, 64, stretch = true),
        Shape("triangle", "三角形", svg("0 0 100 90", """<path d="M50 2l48 86H2z" style="$FILL"/>"""), 220, 198),
        Shape("half-circle", "半圆", svg("0 0 100 50", """<path d="M0 50a50 50 0 0 1 100 0z" style="$FILL"/>"""), 300, 150),
        Shape("arch", "拱形", svg("0 0 100 130", """<path d="M0 130V50a50 50 0 0 1 100 0v80z" style="$FILL"/>"""), 240, 312),
    )

    // ---------- 纹理 ----------

    class Texture(val id: String, val name: String)

    val TEXTURES = listOf(Texture("paper", "纸纹"), Texture("grain", "颗粒"), Texture("grid", "网格"), Texture("dots", "点阵"), Texture("lines", "斜线"))

    /** 纹理层的 CSS 背景：强度 1～3。颜色跟着正文色（--fg）淡淡地铺。 */
    fun textureCss(id: String, level: Int): String {
        val a = when (level) { 1 -> 6; 3 -> 18; else -> 11 }
        val ink = "color-mix(in srgb, var(--fg) $a%, transparent)"
        fun noise(freq: String, op: String) = "url(\"data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='240' height='240'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='$freq' numOctaves='3' stitchTiles='stitch'/><feColorMatrix values='0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 $op 0'/></filter><rect width='100%' height='100%' filter='url(%23n)'/></svg>\")"
        val op = when (level) { 1 -> "0.18"; 3 -> "0.5"; else -> "0.32" }
        return when (id) {
            "paper" -> "background-image:${noise("0.04", op)},${noise("0.9", (op.toDouble() / 2).toString())};background-size:480px 480px,240px 240px"
            "grain" -> "background-image:${noise("0.85", op)};background-size:240px 240px"
            "grid" -> "background-image:linear-gradient($ink 2px,transparent 2px),linear-gradient(90deg,$ink 2px,transparent 2px);background-size:54px 54px"
            "dots" -> "background-image:radial-gradient($ink 3px,transparent 3.5px);background-size:36px 36px"
            else -> "background-image:repeating-linear-gradient(45deg,$ink 0 2px,transparent 2px 22px)"
        }
    }

    // ---------- 图标 ----------

    /** 中文关键词 → 图标名里的英文词（面板里用中文搜图标）。 */
    val ICON_WORDS: Map<String, List<String>> = mapOf(
        "电话" to listOf("phone"), "手机" to listOf("device-mobile", "smartphone"), "邮件" to listOf("mail"), "邮箱" to listOf("mail"),
        "地址" to listOf("map-pin"), "位置" to listOf("map-pin", "location"), "地图" to listOf("map"), "导航" to listOf("navigation"),
        "时间" to listOf("clock"), "钟" to listOf("clock"), "日历" to listOf("calendar"), "日期" to listOf("calendar"),
        "心" to listOf("heart"), "爱心" to listOf("heart"), "喜欢" to listOf("heart", "thumb-up"), "点赞" to listOf("thumb-up", "thumbs-up"),
        "星" to listOf("star"), "星星" to listOf("star", "sparkles"), "闪光" to listOf("sparkles"), "礼物" to listOf("gift"),
        "购物" to listOf("shopping", "cart"), "购物车" to listOf("shopping-cart"), "袋" to listOf("shopping-bag"), "店" to listOf("building-store", "store"),
        "钱" to listOf("coin", "currency-yen", "wallet"), "价格" to listOf("tag", "currency-yen"), "标签" to listOf("tag"), "优惠" to listOf("discount", "ticket"),
        "券" to listOf("ticket"), "票" to listOf("ticket"), "钱包" to listOf("wallet"), "卡" to listOf("credit-card"),
        "人" to listOf("user"), "用户" to listOf("user"), "朋友" to listOf("users"), "团队" to listOf("users"),
        "家" to listOf("home", "house"), "房子" to listOf("home", "house"), "建筑" to listOf("building"),
        "车" to listOf("car"), "汽车" to listOf("car"), "飞机" to listOf("plane"), "火车" to listOf("train"), "自行车" to listOf("bike", "bicycle"),
        "咖啡" to listOf("coffee", "cup"), "茶" to listOf("cup", "tea"), "杯" to listOf("cup", "glass"), "酒" to listOf("glass", "wine", "beer"),
        "吃" to listOf("tools-kitchen", "utensils"), "餐" to listOf("tools-kitchen", "utensils"), "蛋糕" to listOf("cake"), "披萨" to listOf("pizza"),
        "苹果" to listOf("apple"), "水果" to listOf("apple", "cherry", "lemon"), "叶子" to listOf("leaf"), "植物" to listOf("plant", "leaf"),
        "花" to listOf("flower"), "树" to listOf("tree"), "太阳" to listOf("sun"), "月亮" to listOf("moon"), "云" to listOf("cloud"),
        "雨" to listOf("cloud-rain", "umbrella"), "雪" to listOf("snowflake"), "火" to listOf("flame"), "闪电" to listOf("bolt", "zap"),
        "水" to listOf("droplet"), "相机" to listOf("camera"), "照片" to listOf("photo", "image"), "图片" to listOf("photo", "image"),
        "视频" to listOf("video"), "音乐" to listOf("music"), "麦克风" to listOf("microphone", "mic"), "耳机" to listOf("headphones"),
        "书" to listOf("book"), "学习" to listOf("school", "book"), "学校" to listOf("school"), "笔" to listOf("pencil", "pen"),
        "写" to listOf("pencil", "writing"), "文件" to listOf("file"), "文档" to listOf("file-text"), "剪贴板" to listOf("clipboard"),
        "勾" to listOf("check"), "对" to listOf("check"), "完成" to listOf("circle-check", "check"), "叉" to listOf("x"), "错" to listOf("x"),
        "加" to listOf("plus"), "减" to listOf("minus"), "箭头" to listOf("arrow"), "右" to listOf("arrow-right", "chevron-right"),
        "左" to listOf("arrow-left", "chevron-left"), "上" to listOf("arrow-up"), "下" to listOf("arrow-down"),
        "搜索" to listOf("search"), "放大镜" to listOf("search", "zoom"), "设置" to listOf("settings"), "工具" to listOf("tool"),
        "锁" to listOf("lock"), "钥匙" to listOf("key"), "安全" to listOf("shield"), "盾" to listOf("shield"),
        "铃" to listOf("bell"), "通知" to listOf("bell"), "消息" to listOf("message"), "聊天" to listOf("message", "messages"),
        "分享" to listOf("share"), "链接" to listOf("link"), "下载" to listOf("download"), "上传" to listOf("upload"),
        "网" to listOf("world", "globe"), "地球" to listOf("world", "globe"), "无线" to listOf("wifi"), "电脑" to listOf("device-laptop", "laptop"),
        "图表" to listOf("chart"), "增长" to listOf("trending-up", "chart"), "目标" to listOf("target"), "奖杯" to listOf("trophy"),
        "奖" to listOf("award", "medal", "trophy"), "皇冠" to listOf("crown"), "钻石" to listOf("diamond"), "火箭" to listOf("rocket"),
        "灯泡" to listOf("bulb", "lightbulb"), "想法" to listOf("bulb", "lightbulb"), "旗" to listOf("flag"), "定位" to listOf("current-location", "crosshair"),
        "宠物" to listOf("paw", "dog", "cat"), "猫" to listOf("cat"), "狗" to listOf("dog"), "宝宝" to listOf("baby"),
        "运动" to listOf("run", "ball", "barbell"), "健身" to listOf("barbell", "dumbbell"), "跑步" to listOf("run"), "球" to listOf("ball"),
        "药" to listOf("pill"), "医院" to listOf("hospital", "stethoscope"), "健康" to listOf("heartbeat", "activity"),
        "笑" to listOf("mood-smile", "smile"), "表情" to listOf("mood", "smile"), "眼睛" to listOf("eye"), "手" to listOf("hand"),
        "衣服" to listOf("shirt"), "口红" to listOf("lipstick"), "美妆" to listOf("brush", "sparkles"), "剪刀" to listOf("scissors"),
        "播放" to listOf("player-play", "play"), "暂停" to listOf("player-pause", "pause"), "直播" to listOf("broadcast", "live"),
    )

    /** 不搜时先给的常用图标。 */
    val COMMON_ICONS = listOf(
        "phone", "mail", "map-pin", "clock", "calendar", "heart", "star", "sparkles", "gift", "shopping-bag", "shopping-cart", "tag",
        "ticket", "wallet", "user", "users", "home", "car", "plane", "coffee", "cake", "leaf", "flower", "sun", "moon", "cloud",
        "camera", "music", "book", "pencil", "check", "circle-check", "x", "plus", "arrow-right", "search", "bell", "message-circle",
        "share", "link", "globe", "trophy", "crown", "diamond", "rocket", "lightbulb", "flag", "thumbs-up", "smile", "eye", "flame", "zap",
    )

    /** 按中文或英文搜图标名；返回能用的名字（最多 [limit] 个）。 */
    fun searchIcons(all: Collection<String>, query: String, limit: Int = 120): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return COMMON_ICONS.filter { it in all }
        val words = ICON_WORDS.filterKeys { it in q || q in it }.values.flatten().distinct().ifEmpty { listOf(q.replace(' ', '-')) }
        val exact = words.filter { it in all }
        val starts = all.filter { n -> words.any { w -> n.startsWith(w) } }.sortedBy { it.length }
        val inside = all.filter { n -> words.any { w -> w in n } }.sortedBy { it.length }
        return (exact + starts + inside).distinct().filterNot { it.endsWith("-off") }.take(limit)
    }

    /** 一个图标的 SVG（和出图时 data-icon 填进去的一样）。 */
    fun iconSvg(name: String): String? = DesignTemplates.icons("""data-icon="$name"""")[name]

    // ---------- 给 Kavi 看的目录 ----------

    fun catalog(lib: Library, query: String = "", category: String? = null): String = buildString {
        val list = search(lib, query, category)
        appendLine("贴纸（${list.size} 个）：写 <div data-sticker=\"贴纸id\" style=\"position:absolute;left:..px;top:..px;width:..px;height:..px\"></div>，出图时自动画上；线条类（stroke）颜色跟着这个 div 的 color，色块类（fill）跟着配色变量，要单独换主色就在 div 上写 --primary。")
        val cats = lib.categories.associate { it.id to it.name }
        list.groupBy { it.category }.forEach { (cat, items) ->
            append("· ").append(cats[cat] ?: cat).append("：")
            appendLine(items.joinToString("；") { s ->
                val (w, h) = s.size()
                "${s.id} ${s.name}（${if (s.style == "hand") "手绘" else "干净"}，${s.recolor}，约 ${w}×$h" + (if (s.textArea != null) "，中间能放字" else "") + "）"
            })
        }
        if (query.isBlank() && category == null) {
            appendLine("形状、纹理用内联 SVG 或 CSS 自己画（做法见「审美」第 4 步）；编辑器「加元素」里也有现成的形状、图标和纹理，用户能自己加。")
        }
    }
}
