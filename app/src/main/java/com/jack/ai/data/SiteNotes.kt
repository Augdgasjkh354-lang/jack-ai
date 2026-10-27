package com.jack.ai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** 一个网站在以前的任务里怎么样：要登录、被拦、读不到、限流各几次，读成功几次。 */
@Serializable
data class SiteRecord(
    val host: String,
    val login: Int = 0,
    val blocked: Int = 0,
    val unreadable: Int = 0,
    val limited: Int = 0,
    val ok: Int = 0,
    /** 最近一次出问题的时间。 */
    val problemAt: Long = 0,
    val at: Long = 0,
)

/**
 * 经验沉淀：工具碰到网站时，代码自动记一笔（见 Failures 的归类），下次 Work 任务开工就知道哪个网站要登录、哪个会拦、哪个读不到，
 * 直接换来源，不用再撞一次。只记网站名和次数，不记内容；隐私项目里不记。
 */
object SiteNotes {
    private const val MAX_SITES = 200
    /** 多久以前的问题不再提（网站会变）。 */
    private const val FRESH_MS = 60L * 24 * 3600 * 1000
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "site_notes.json")

    fun load(ctx: Context): List<SiteRecord> = runCatching {
        json.decodeFromString(ListSerializer(SiteRecord.serializer()), file(ctx).readText())
    }.getOrDefault(emptyList())

    /** 链接的网站名（去掉 www）；不是链接返回 null。 */
    fun hostOf(url: String): String? =
        Regex("""^https?://([^/:?#]+)""", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.get(1)?.lowercase()?.removePrefix("www.")

    /** 记一笔：kind 为 null 是读成功了。 */
    fun record(ctx: Context, host: String, kind: com.jack.ai.tools.Failures.Kind?, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val list = load(ctx).toMutableList()
            val old = list.firstOrNull { it.host == host } ?: SiteRecord(host)
            val new = when (kind) {
                null -> old.copy(ok = old.ok + 1)
                com.jack.ai.tools.Failures.Kind.LOGIN -> old.copy(login = old.login + 1, problemAt = now)
                com.jack.ai.tools.Failures.Kind.BLOCKED -> old.copy(blocked = old.blocked + 1, problemAt = now)
                com.jack.ai.tools.Failures.Kind.UNREADABLE_PAGE -> old.copy(unreadable = old.unreadable + 1, problemAt = now)
                com.jack.ai.tools.Failures.Kind.RATE_LIMIT -> old.copy(limited = old.limited + 1, problemAt = now)
                else -> return
            }.copy(at = now)
            list.removeAll { it.host == host }
            list.add(0, new)
            runCatching { file(ctx).writeText(json.encodeToString(ListSerializer(SiteRecord.serializer()), list.take(MAX_SITES))) }
        }
    }

    /** 给 Work 任务看的一段：最近出过问题的网站。没有就是空。 */
    fun note(list: List<SiteRecord>, now: Long = System.currentTimeMillis()): String {
        val bad = list.filter { now - it.problemAt < FRESH_MS && it.login + it.blocked + it.unreadable + it.limited > 0 }
            .sortedByDescending { it.problemAt }.take(15)
        if (bad.isEmpty()) return ""
        return "【Site notes from earlier tasks】(recorded automatically; prefer other sources for these unless {user} is around to help)\n" +
            bad.joinToString("\n") { n ->
                val issues = listOfNotNull(
                    "needs login ×${n.login}".takeIf { n.login > 0 },
                    "blocks automated access ×${n.blocked}".takeIf { n.blocked > 0 },
                    "page unreadable ×${n.unreadable}".takeIf { n.unreadable > 0 },
                    "rate-limited ×${n.limited}".takeIf { n.limited > 0 },
                )
                "- ${n.host}: " + issues.joinToString(", ") + (if (n.ok > 0) "; read fine ×${n.ok}" else "")
            } + "\n"
    }
}
