package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** 一次运行里调用的一个工具：成没成，留下的编号或出错原因（不记正文、密码、网页内容）。 */
@Serializable
data class RunStep(val tool: String, val ok: Boolean, val note: String = "")

/**
 * 提醒、定时任务、监控的一次运行记录：什么时候跑的、跑到哪一步、结果如何、为什么失败。
 * status：running 正在跑（进程被杀就一直停在这里，显示成「中途被打断」）、ok 完成、not_met 查了但条件没满足、failed 失败。
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
    /** 结果的开头（给人看一眼是什么）。 */
    val result: String = "",
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

    /** 最新的在前；同一毫秒开始的，后写进去的算新。文件里是按时间先后存的。 */
    private fun newestFirst(list: List<RunRecord>): List<RunRecord> =
        list.withIndex().sortedWith(compareByDescending<IndexedValue<RunRecord>> { it.value.startedAt }.thenByDescending { it.index }).map { it.value }

    private fun update(ctx: Context, change: (List<RunRecord>) -> List<RunRecord>) = synchronized(lock) {
        val list = change(all(ctx))
        // 每个提醒最多留 20 次，总共最多 300 次，旧的先丢
        val keep = newestFirst(list).groupBy { it.routineId }.values.flatMap { it.take(PER_ROUTINE) }
            .let { newestFirst(it) }.take(TOTAL).toSet()
        // 存的时候按时间先后（旧的在前），和读回来时的顺序约定一致
        val kept = list.filter { it in keep }
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

    fun finish(ctx: Context, runId: String, status: String, result: String = "", error: String = "", retry: Boolean = false) =
        update(ctx) { l ->
            l.map {
                if (it.id == runId) it.copy(status = status, finishedAt = System.currentTimeMillis(), result = result.take(200),
                    error = error.take(400), retry = retry) else it
            }
        }

    /** 一次就结束的记录（提醒弹出、监控到期这类）。 */
    fun once(ctx: Context, r: Routine, status: String, result: String = "", error: String = "") {
        val id = start(ctx, r)
        finish(ctx, id, status, result, error)
    }

    /** 留下的东西：工具结果里带编号、提交号、「已…」的那几行（不留正文）。 */
    private val KEY = Regex("编号|提交|PR #|issue #|已设置|已改好|已写进|已新建|已加|已推送|已下载|已保存|已发送|已删除|id=")

    /** 把一次工具调用的结果变成一步记录。 */
    fun stepOf(tool: String, result: String): RunStep {
        val failed = result.startsWith("工具调用失败") || result.startsWith("这个工具") || result.startsWith("用户没有同意")
        val note = if (failed) result.removePrefix("工具调用失败：").lineSequence().first().take(160)
        else result.lineSequence().map { it.trim() }.filter { KEY.containsMatchIn(it) && !it.startsWith("【") }.take(2).joinToString("；").take(160)
        return RunStep(tool, !failed, note)
    }

    /** 给模型看的一句话：这条提醒上一次跑得怎么样。 */
    fun lastText(ctx: Context, routineId: String): String? {
        val last = of(ctx, routineId).firstOrNull() ?: return null
        val time = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(last.startedAt))
        return "上次 $time：${last.statusText()}" + if (last.error.isNotBlank()) "（${last.error.take(60)}）" else ""
    }
}
