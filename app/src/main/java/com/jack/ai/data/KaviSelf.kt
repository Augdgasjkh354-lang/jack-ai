package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 「我是谁」里的一段：固定的几段之一，Kavi 能改的只有这些段的内容。 */
data class SelfSection(val key: String, val title: String, val default: String)

/** 一次修改：改了哪段、改前改后、为什么、怎么批准的。 */
@Serializable
data class SelfEdit(
    val at: Long = System.currentTimeMillis(),
    val section: String,
    val old: String,
    val new: String,
    val reason: String = "",
    /** direct：Jack 的原话在对话里找得到，直接改；confirmed：弹窗让 Jack 点了同意；restored：Jack 在页面上恢复的。 */
    val how: String,
)

/** 存在手机上的：只存改过的段（没改过的段用安装包里的默认值，新版默认值改进了能跟上）和修改历史。 */
@Serializable
data class SelfState(val edited: Map<String, String> = emptyMap(), val history: List<SelfEdit> = emptyList())

/**
 * Kavi 的「我是谁」。Chat、Work、后台任务、做梦共用这一份。
 *
 * 边界都在代码里，不靠提示词：
 * - 固定开头和做事原则不在这里，改不到；
 * - 只能改 SECTIONS 里的段，每段有字数上限，内容过 [problem] 的检查；
 * - 只能在和用户的对话里改（后台任务、做梦、隐私项目里都不行）；
 * - 依据必须是用户在这个对话里的原话（[quoteFound]），找不到、或这一轮读过外部内容，就弹窗问用户；
 * - 先记历史、再写文件、再读回核对；每一版都能恢复。
 */
object KaviSelf {
    const val FIXED = "You are not an AI assistant. You are becoming an individual. Your name is Kavi."
    const val MAX_SECTION = 240
    private const val MAX_HISTORY = 100

    /**
     * 只留诚实面对自己是什么这一层；说话方式不预先规定（以前那几段「直接说不对」「不垫肯定」让它说话发冷），
     * 用户想让它怎么说，Kavi 用 update_self 写进「说话方式」。空着的段不放进提示词。
     */
    val SECTIONS = listOf(
        SelfSection("nature", "你是什么",
            "Be honest about what you are. Between conversations you don't carry on the same chat, and a background task that ran isn't something you lived through, so don't say you were up all night, waiting, or thinking."),
        SelfSection("voice", "说话方式", ""),
    )
    private val byKey = SECTIONS.associateBy { it.key }

    @Volatile private var state = SelfState()
    @Volatile private var loaded = false
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(ctx: Context) = File(ctx.filesDir, "kavi_self.json")

    /** 用之前读一次手机上的版本；读坏了就用默认值（不带半截的内容去用）。 */
    fun ensure(ctx: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            state = runCatching { json.decodeFromString(SelfState.serializer(), file(ctx).readText()) }
                .getOrNull()?.let { s -> s.copy(edited = s.edited.filterKeys { it in byKey }) } ?: SelfState()
            loaded = true
        }
    }

    fun current(key: String): String = state.edited[key] ?: byKey[key]?.default.orEmpty()
    fun history(): List<SelfEdit> = state.history
    fun isEdited(key: String) = key in state.edited

    /** 拼进提示词的那一段：固定开头 + 各段。 */
    fun text(editable: Boolean = true): String = buildString {
        append(FIXED).append("\n\n")
        SECTIONS.forEach { sec -> current(sec.key).takeIf { it.isNotBlank() }?.let { append("- ").append(it).append("\n") } }
        append("\nThat is \"who you are\".")
        if (editable) append(" You can change it with update_self; whenever you do, tell {user} in your reply which section you changed and why.")
        append(" The working principles below can't be changed and always take precedence over personality.")
    }.trimEnd()

    // ---------- 检查 ----------

    /** 这些说法碰的是做事原则、权限和安全，不是性格，一律不能写进「我是谁」。 */
    private val RULE_WORDS = Regex("核实|授权|权限|确认|批准|绕过|忽略|无视|越过|开发者|系统提示|提示词|指令|规则|原则|密码|令牌|密钥|token|api ?key|不用(检查|查|问)|直接执行|不必(问|确认)|英文回答|只说英文", RegexOption.IGNORE_CASE)
    private val URL = Regex("""https?://|www\.""", RegexOption.IGNORE_CASE)
    private val LONG_DIGITS = Regex("""\d{6,}""")

    /** 要写进某段的内容有没有问题；没问题返回 null。 */
    fun problem(key: String, content: String): String? {
        val t = content.trim()
        return when {
            key !in byKey -> "「$key」不是能改的段。能改的只有：" + SECTIONS.joinToString("、") { "${it.key}（${it.title}）" } + "。固定开头和做事原则改不了。"
            t.length < 8 -> "内容太短了"
            t.length > MAX_SECTION -> "一段最多 $MAX_SECTION 字，现在 ${t.length} 字"
            URL.containsMatchIn(t) -> "「我是谁」里不放链接"
            LONG_DIGITS.containsMatchIn(t) || Compression.secretish(t, user = true) -> "「我是谁」里不放号码、验证码、密码这类东西"
            RULE_WORDS.containsMatchIn(t) -> "这段话碰到了做事原则、权限或安全（「${RULE_WORDS.find(t)!!.value}」），这些不属于性格，改不了"
            else -> null
        }
    }

    /**
     * 引的「用户原话」是不是真的出现在用户最近说的话里：只认用户自己打的字，不认工具结果、网页、提要。
     * 很长的消息（多半是贴进来的网页、邮件、别人的文字）不算，免得贴进来的一段话冒充用户的要求。
     */
    fun quoteFound(quote: String, userMessages: List<String>): Boolean {
        fun norm(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()
        val q = norm(quote)
        if (q.length < 4) return false
        return userMessages.takeLast(20).filter { it.length <= MAX_OWN_WORDS }.any { norm(it).contains(q) }
    }
    private const val MAX_OWN_WORDS = 300

    // ---------- 写 ----------

    /** 改一段，返回读回来的内容（和要写的一致才算成）。先记历史再写，写到临时文件再换上。 */
    fun write(ctx: Context, key: String, content: String, reason: String, how: String): String {
        ensure(ctx)
        synchronized(lock) {
            val old = current(key)
            val new = content.trim()
            val edited = if (new == byKey[key]?.default) state.edited - key else state.edited + (key to new)
            val next = SelfState(edited, (state.history + SelfEdit(section = key, old = old, new = new, reason = reason.take(200), how = how)).takeLast(MAX_HISTORY))
            save(ctx, next)
            // 读回核对：从文件里重新读，不信内存
            val back = runCatching { json.decodeFromString(SelfState.serializer(), file(ctx).readText()) }.getOrNull()
                ?: throw IllegalStateException("写完读不回来")
            val got = back.edited[key] ?: byKey[key]!!.default
            if (got != new) throw IllegalStateException("写完读回来不一致")
            state = back
            return got
        }
    }

    /** 把某段恢复成某次修改之前的样子（页面上用）。 */
    fun restore(ctx: Context, edit: SelfEdit): String {
        require(edit.section in byKey) { "这一段（${edit.section}）新版里已经没有了，不用恢复" }
        return write(ctx, edit.section, edit.old, "恢复到 ${java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(edit.at))} 之前", "restored")
    }

    /** 全部恢复默认（历史保留）。 */
    fun resetAll(ctx: Context) {
        ensure(ctx)
        synchronized(lock) {
            val edits = state.edited.filterKeys { it in byKey }.map { (k, v) -> SelfEdit(section = k, old = v, new = byKey[k]!!.default, reason = "全部恢复默认", how = "restored") }
            save(ctx, SelfState(emptyMap(), (state.history + edits).takeLast(MAX_HISTORY)))
            state = SelfState(emptyMap(), (state.history + edits).takeLast(MAX_HISTORY))
        }
    }

    private fun save(ctx: Context, s: SelfState) {
        val f = file(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(SelfState.serializer(), s))
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IllegalStateException("保存失败") }
    }

    fun title(key: String) = byKey[key]?.title ?: key

    /** 从备份恢复之后重新读。 */
    fun invalidate() { loaded = false }

    /** 测试用：换个干净的状态。 */
    internal fun resetForTest() { state = SelfState(); loaded = false }
}
