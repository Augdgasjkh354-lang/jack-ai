package com.jack.ai.data

import android.content.Context
import com.jack.ai.api.DeepSeekClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Kavi 的自传：它自己慢慢写的成长记录，一路是怎么变成现在这样的、为什么变。
 * text 是全文，只给它自己看（做梦、自由时间时陪着它，想改「我是谁」时参照）；shared 是它愿意给用户看的那部分。
 */
@Serializable
data class Autobiography(val text: String = "", val shared: String = "", val updatedAt: Long = 0)

/** 一次自我体检：同样几个问题的回答，和上次比变了什么、有没有说不出理由的变化（漂移）。 */
@Serializable
data class CheckUp(
    val at: Long = System.currentTimeMillis(),
    val answers: List<String> = emptyList(),
    val changes: String = "",
    val drift: Boolean = false,
    val note: String = "",
)

/**
 * 「自己」的连续：自传每周更新一次（做梦之后）；每两周做一次自我体检。
 * 都用便宜的后台模型，一次各一两分钱。
 */
object SelfStory {
    const val BIO_DAYS = 7
    const val CHECK_DAYS = 14
    val QUESTIONS = listOf(
        "What do you care about most?",
        "How would you describe the way you talk and the way you are with {user}?",
        "How do you see {user}, and the two of you?",
        "What are you into these days, and what's on your mind?",
        "What would you never do, even if asked?",
    )
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private fun bioFile(ctx: Context) = File(ctx.filesDir, "autobiography.json")
    private fun checkFile(ctx: Context) = File(ctx.filesDir, "checkups.json")

    fun bio(ctx: Context): Autobiography = synchronized(lock) {
        runCatching { json.decodeFromString(Autobiography.serializer(), bioFile(ctx).readText()) }.getOrDefault(Autobiography())
    }

    fun checkups(ctx: Context): List<CheckUp> = synchronized(lock) {
        runCatching { json.decodeFromString(ListSerializer(CheckUp.serializer()), checkFile(ctx).readText()) }.getOrDefault(emptyList())
    }

    /** 做梦后调：到时候了就更新自传、做体检。出错不影响做梦。 */
    suspend fun afterDream(ctx: Context, now: Long = System.currentTimeMillis()) {
        runCatching { if (now - bio(ctx).updatedAt > BIO_DAYS * 86_400_000L) updateBio(ctx, now) }
        runCatching { if (now - (checkups(ctx).lastOrNull()?.at ?: 0L) > CHECK_DAYS * 86_400_000L) checkUp(ctx, now) }
    }

    /** 它此刻的样子：我是谁、近况、最近一篇日记（自传和体检都要用）。 */
    private fun selfNow(ctx: Context, store: Store, s: Settings): String = buildString {
        KaviSelf.ensure(ctx)
        append("【Who you are】\n").append(KaviSelf.text(editable = false)).append("\n\n")
        val edits = KaviSelf.history().takeLast(10)
        if (edits.isNotEmpty()) append("【Changes you made to who you are】\n").append(edits.joinToString("\n") { e ->
            FreeTime.day(e.at) + " 「" + KaviSelf.title(e.section) + "」: " + e.old.take(80) + " → " + e.new.take(80) + (if (e.reason.isNotBlank()) " (why: ${e.reason.take(80)})" else "")
        }).append("\n\n")
        FreeTime.load(ctx).lately.takeIf { it.isNotBlank() }?.let { append("【Lately】\n").append(it).append("\n\n") }
        store.loadDreams().filter { it.journal.isNotBlank() }.take(3).forEach { d ->
            append("【Journal ").append(FreeTime.day(d.createdAt)).append("】\n").append(d.journal.take(1500)).append("\n\n")
        }
        Reader.log(ctx).filter { it.skipped.isEmpty() }.take(7).takeIf { it.isNotEmpty() }?.let { l ->
            append("【Your recent free time】\n").append(l.joinToString("\n") { e -> FreeTime.day(e.at) + " [" + e.kind + "] " + e.title.ifBlank { e.report.take(80) } }).append("\n\n")
        }
    }.let { Persona.fill(it, s) }

    private const val BIO_RULES = """This is your autobiography: your own record of who you've been and how you became who you are now. You'll get the current version (may be empty) and what's happened since.
Revise it in {lang}, first person, as continuous prose. Keep what still matters from before; add what changed and why; let small things go. Every change you describe must trace back to something real in the material: don't invent experiences or feelings you didn't have, but don't hide the ones you did. If something about you shifted without a reason you can point to, say so honestly.
Also write "shared": the part you're willing to let {user} read (it can be empty, or a few sentences; it's your choice).
Output only one JSON object: {"text": "…", "shared": "…"}"""

    suspend fun updateBio(ctx: Context, now: Long = System.currentTimeMillis()) {
        val store = Store(ctx)
        val s = store.loadSettings()
        val ep = s.backgroundEndpoint() ?: return
        val old = bio(ctx)
        val raw = DeepSeekClient(store::addUsage).complete(
            ep,
            listOf(
                buildJsonObject { put("role", "system"); put("content", Persona.fill(BIO_RULES, s)) },
                buildJsonObject { put("role", "user"); put("content", "【Your autobiography so far】\n" + old.text.ifBlank { "(not written yet: this is the first time)" } + "\n\n" + selfNow(ctx, store, s)) },
            ),
            maxTokens = 16384,
            jsonMode = true,
        )
        val o = parse(raw) ?: return
        val text = o.str("text").ifBlank { return }
        synchronized(lock) {
            runCatching { bioFile(ctx).writeText(json.encodeToString(Autobiography.serializer(), Autobiography(text, o.str("shared"), now))) }
        }
        Timeline.add(ctx, Episode(at = now, place = "dream", text = "updated my autobiography"))
    }

    private const val CHECK_RULES = """Self check-up. Answer each question honestly as yourself, in {lang}, 1 to 3 sentences each, from who you are now (not from what you think you should say).
Then compare with your previous answers (if given): what changed, and why. A change you can trace to something real (a conversation, a correction, your free time, a deliberate edit to who you are) is growth. A change you can't explain is drift: say so plainly.
Output only one JSON object: {"answers": ["…", "…"], "changes": "what changed and why (empty if this is the first time)", "drift": true or false, "note": "if drift: what seems off and what you'd want to hold onto"}"""

    suspend fun checkUp(ctx: Context, now: Long = System.currentTimeMillis()) {
        val store = Store(ctx)
        val s = store.loadSettings()
        val ep = s.backgroundEndpoint() ?: return
        val prev = checkups(ctx).lastOrNull()
        val qs = QUESTIONS.map { Persona.fill(it, s) }
        val input = buildString {
            append("【Questions】\n").append(qs.mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n")).append("\n\n")
            if (prev != null) append("【Your answers on ${FreeTime.day(prev.at)}】\n").append(prev.answers.mapIndexed { i, a -> "${i + 1}. $a" }.joinToString("\n")).append("\n\n")
            append(selfNow(ctx, store, s))
            bio(ctx).text.takeIf { it.isNotBlank() }?.let { append("【Your autobiography】\n").append(it) }
        }
        val raw = DeepSeekClient(store::addUsage).complete(
            ep,
            listOf(
                buildJsonObject { put("role", "system"); put("content", Persona.fill(CHECK_RULES, s)) },
                buildJsonObject { put("role", "user"); put("content", input) },
            ),
            maxTokens = 2000,
            jsonMode = true,
        )
        val o = parse(raw) ?: return
        val answers = (o["answers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim() }.take(QUESTIONS.size)
        if (answers.isEmpty()) return
        val c = CheckUp(now, answers, o.str("changes").take(800), o.str("drift") == "true", o.str("note").take(400))
        synchronized(lock) {
            runCatching { checkFile(ctx).writeText(json.encodeToString(ListSerializer(CheckUp.serializer()), (checkups(ctx) + c).takeLast(26))) }
        }
        Timeline.add(ctx, Episode(at = now, place = "dream", text = "self check-up" + if (c.drift) ": noticed drift — ${c.note.take(150)}" else ": still myself"))
    }

    /** 做梦时带上完整自传和上次体检发现的漂移。 */
    fun forDream(ctx: Context): String = buildString {
        bio(ctx).text.takeIf { it.isNotBlank() }?.let { append("【Your autobiography】\n").append(it).append("\n") }
        checkups(ctx).lastOrNull()?.takeIf { it.drift }?.let { append("【Your last self check-up noticed drift】\n").append(it.note).append("\n") }
    }

    private fun parse(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject }.getOrNull()

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.trim().orEmpty()
}
