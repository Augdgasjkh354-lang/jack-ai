package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一次运行里调用的一个工具：成没成（代码判定，不看文字），留下的远端编号或脱敏后的出错原因。
 * 不记正文、网页内容、邮件内容、令牌。
 */
@Serializable
data class RunStep(val tool: String, val ok: Boolean, val note: String = "", val rejected: Boolean = false)

/**
 * 提醒、定时任务、监控的一次运行记录：什么时候跑的、跑到哪一步、结果如何、为什么失败。
 * status：
 * - running 正在跑（进程被杀就一直停在这里，显示成「中途被打断」）
 * - ok 完成 / 监控条件满足
 * - not_met 查到了，条件还没满足
 * - unknown 这次没查成（工具出错、没拿到可靠数据），不能说条件没满足
 * - partial 监控条件满足了，但后续步骤没做完
 * - needs_setup 还没配置好（比如没有 API Key），没跑
 * - failed 失败
 * note 只放代码写的固定短句，不放模型的回答；error 存之前先脱敏。
 */
@Serializable
data class RunRecord(
    val id: String = newId(),
    val routineId: String,
    val title: String,
    val kind: String,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    val status: String = "running",
    val steps: List<RunStep> = emptyList(),
    val note: String = "",
    val error: String = "",
    /** 失败后会不会自动再试。 */
    val retry: Boolean = false,
) {
    /** 「running」超过 30 分钟还没结束：多半是进程被系统杀了。 */
    fun interrupted(now: Long = System.currentTimeMillis()) = status == "running" && now - startedAt > 30 * 60_000L

    fun statusText(): String = when {
        interrupted() -> "中途被打断（App 可能被系统关掉了）"
        status == "running" -> "正在跑"
        status == "ok" -> if (kind == "watch") "条件满足了" else "完成"
        status == "not_met" -> "查过了，条件还没满足"
        status == "unknown" -> "这次没查成，下次再查"
        status == "partial" -> "条件满足了，但后续步骤没做完"
        status == "needs_setup" -> "没跑：还没配置好"
        else -> "失败" + if (retry) "（会自动再试）" else ""
    }
}

object RunLog {
    private const val PER_ROUTINE = 20
    private const val TOTAL = 300
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "runlogs.json")

    fun all(ctx: Context): List<RunRecord> = synchronized(lock) {
        runCatching { json.decodeFromString(ListSerializer(RunRecord.serializer()), file(ctx).readText()) }.getOrDefault(emptyList())
    }

    /** 某个提醒或任务的记录，最新的在前。 */
    fun of(ctx: Context, routineId: String): List<RunRecord> = newestFirst(all(ctx)).filter { it.routineId == routineId }

    /** 最新的在前。文件里按开始的先后存（新的总是加在末尾），倒过来就是，不看时钟（时钟会被调、同一毫秒会撞）。 */
    private fun newestFirst(list: List<RunRecord>): List<RunRecord> = list.asReversed()

    private fun update(ctx: Context, change: (List<RunRecord>) -> List<RunRecord>) = synchronized(lock) {
        val list = change(all(ctx))
        // 每个提醒最多留最新的 20 次；再按全局先后只留最新的 300 次。list 本身按先后存（新的在末尾）
        val perRoutine = newestFirst(list).groupBy { it.routineId }.values.flatMap { it.take(PER_ROUTINE) }.map { it.id }.toSet()
        val kept = list.filter { it.id in perRoutine }.takeLast(TOTAL)
        val f = file(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(RunRecord.serializer()), kept))
        tmp.renameTo(f)
    }

    fun start(ctx: Context, r: Routine): String {
        val rec = RunRecord(routineId = r.id, title = r.title, kind = r.kind)
        update(ctx) { it + rec }
        return rec.id
    }

    fun step(ctx: Context, runId: String, step: RunStep) =
        update(ctx) { l -> l.map { if (it.id == runId) it.copy(steps = (it.steps + step).takeLast(40)) else it } }

    /** note 只传代码里写死的短句；error 会先脱敏。 */
    fun finish(ctx: Context, runId: String, status: String, note: String = "", error: String = "", retry: Boolean = false) =
        update(ctx) { l ->
            l.map {
                if (it.id == runId) it.copy(status = status, finishedAt = System.currentTimeMillis(), note = note.take(80),
                    error = Redact.text(error).take(300), retry = retry) else it
            }
        }

    /** 一次就结束的记录（提醒弹出、监控到期这类）。 */
    fun once(ctx: Context, r: Routine, status: String, note: String = "", error: String = "") {
        val id = start(ctx, r)
        finish(ctx, id, status, note, error)
    }

    /** 成功的工具结果里只认这几种远端编号，别的一概不留。 */
    private val IDS = listOf(
        Regex("""编号(?:还是)?\s*([A-Za-z0-9-]{1,40})""") to "编号 ",
        Regex("""提交\s*([0-9a-f]{7})""") to "提交 ",
        Regex("""PR\s*#(\d{1,7})""") to "PR #",
        Regex("""issue\s*#(\d{1,7})""", RegexOption.IGNORE_CASE) to "issue #",
        Regex("""\bid[=：:]\s*([0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12})""") to "id ",
    )

    /** 从工具结果里抽远端编号（提醒/日历/邮件编号、提交号、PR/issue 号、Notion 页面 id），最多 3 个。 */
    fun idsOf(result: String): List<String> =
        IDS.flatMap { (re, label) -> re.findAll(result).map { label + it.groupValues[1] }.toList() }
            .filterNot { Redact.hasSecretPrefix(it.substringAfter(' ').substringAfter('#')) }
            .distinct().take(3)

    /** 把一次工具调用变成一步记录：成没成看 status；成功只留编号，失败留脱敏后的第一行原因。 */
    fun stepOf(tool: String, status: com.jack.ai.tools.ToolStatus, result: String): RunStep = when (status) {
        com.jack.ai.tools.ToolStatus.OK -> RunStep(tool, true, idsOf(result).joinToString("；"))
        com.jack.ai.tools.ToolStatus.REJECTED -> RunStep(tool, false, "需要确认的操作，没被同意（后台不会自动同意）", rejected = true)
        com.jack.ai.tools.ToolStatus.FAILED -> {
            val why = if (Verify.failed(result)) "写完读回核实没通过"
            else result.removePrefix("工具调用失败：").lineSequence().first()
            RunStep(tool, false, Redact.text(why).take(120))
        }
    }

    /** 给模型看的一句话：这条提醒上一次跑得怎么样。 */
    fun lastText(ctx: Context, routineId: String): String? {
        val last = of(ctx, routineId).firstOrNull() ?: return null
        val time = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(last.startedAt))
        return "上次 $time：${last.statusText()}" + if (last.error.isNotBlank()) "（${last.error.take(60)}）" else ""
    }
}

/** 落盘、发通知之前把看起来像秘密或隐私的东西隐去：邮箱、链接的路径和参数、key=…、Bearer、长串数字、令牌样子的长串。 */
object Redact {
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    private val URL = Regex("""(https?://[^/\s?#]+)[^\s)）」]*""")
    private val PAIR = Regex("""(?i)\b(token|access_token|api[_-]?key|key|secret|password|passwd|pwd|code|auth|authorization|sig|signature|cookie|session)\s*[=:：]\s*\S+""")
    private val BEARER = Regex("""(?i)\b(bearer|basic|token)\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val CN_SECRET = Regex("""(验证码|密码|口令|令牌|密钥)\s*(?:是|为|[:：])\s*\S+""")
    private val DIGITS = Regex("""\d{6,}""")
    private val LONG = Regex("""[A-Za-z0-9_\-]{20,}""")

    private val PREFIXES = listOf("ghp_", "gho_", "ghs_", "github_pat_", "sk-", "ntn_", "secret_", "xox", "AKIA", "AIza")

    /** 带常见令牌前缀。 */
    fun hasSecretPrefix(s: String) = PREFIXES.any { s.startsWith(it) }

    /** 令牌样子：常见前缀，或 20 位以上、字母数字混着的串。 */
    fun looksSecret(s: String): Boolean =
        hasSecretPrefix(s) || (s.length >= 20 && s.any { it.isLetter() } && s.any { it.isDigit() })

    fun text(s: String): String = s
        .replace(EMAIL, "[邮箱]")
        .replace(URL) { it.groupValues[1] + if (it.value.length > it.groupValues[1].length) "/…" else "" }
        .replace(BEARER) { it.groupValues[1] + " [已隐去]" }
        .replace(PAIR) { it.groupValues[1] + "=[已隐去]" }
        .replace(CN_SECRET) { it.groupValues[1] + "[已隐去]" }
        .replace(LONG) { if (looksSecret(it.value)) "[已隐去]" else it.value }
        .replace(DIGITS, "[数字]")
}
