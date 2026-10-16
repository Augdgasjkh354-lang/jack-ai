package com.jack.ai.tools

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import com.jack.ai.api.ApiException
import com.jack.ai.api.TavilyClient
import com.jack.ai.data.Conversation
import com.jack.ai.data.Flashcard
import com.jack.ai.data.LibraryIndex
import com.jack.ai.data.MemoryItem
import com.jack.ai.data.Project
import com.jack.ai.data.Routine
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.Settings
import com.jack.ai.data.Skill
import com.jack.ai.data.SkillIds
import com.jack.ai.data.Store
import com.jack.ai.data.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/** 工具要改 App 里的数据时找它（由 ChatViewModel 提供）。 */
interface ToolHost {
    val app: Application
    val store: Store
    val tavily: TavilyClient
    fun conversations(): List<Conversation>
    fun setMemories(list: List<MemoryItem>)
    fun editCards(transform: (List<Flashcard>) -> List<Flashcard>)
    /** 在主线程调用。 */
    fun saveRoutine(r: Routine)
    fun deleteRoutine(id: String)
    fun libraryIndex(): LibraryIndex
    fun projectIndex(p: Project): LibraryIndex
    fun refreshWidget()
}

/** 工具执行过程要在聊天里显示的东西：进行中/完成的标签，生成的文件卡片。 */
interface ToolUi {
    fun started(label: String)
    fun finished(label: String, record: SearchRecord)
    fun attach(file: File)
}

/**
 * 一次回答里的工具：知道这次开着哪些技能、对话文件夹在哪，按工具名分发到各个工具文件里的实现
 * （WebTools、KnowledgeTools、FileTools、ScheduleTools、CodeTools）。
 */
class ToolSession(
    val host: ToolHost,
    val settings: Settings,
    val conv: Conversation,
    val project: Project?,
    val skills: List<Skill>,
    val ui: ToolUi,
) {
    val app: Application get() = host.app
    val skillIds: Set<String> = skills.map { it.id }.toSet()
    val web = SkillIds.WEB in skillIds
    val wsDir: File = Workspace.dir(host.app, Workspace.key(conv))
    val wsCache = File(wsDir, ".cache")

    /** 最多几轮工具；用了「深度研究」技能会放宽到 25。 */
    var maxRounds = 5
    /** 搜索结果的编号接着往下排，引用时不重号。 */
    internal var sourceCount = 0

    /** 执行一个工具，聊天里显示「进行中」→「完成/出错」。出错时把原因交给模型，不抛出。 */
    suspend fun runTool(label: String, action: suspend () -> Pair<String, SearchRecord>): String {
        ui.started(label)
        return try {
            val (text, rec) = action()
            ui.finished(label, rec)
            text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = when (e) {
                is ApiException -> e.message ?: "接口出错"
                is IOException -> "网络连接失败"
                else -> e.message ?: "出错了"
            }
            ui.finished(label, SearchRecord(label, error = err))
            "工具调用失败：$err"
        }
    }

    /** 按工具名执行，返回给模型看的结果。没开对应技能的工具一律不可用。 */
    suspend fun execute(name: String, argsText: String): String {
        val args = runCatching { Json.parseToJsonElement(argsText).jsonObject }.getOrNull()
        val key = when (name) {
            "read_webpage" -> "url"
            "save_memory" -> "content"
            "use_skill" -> "name"
            "run_js", "run_python" -> "code"
            "delete_routine", "delete_calendar_event" -> "id"
            else -> "query"
        }
        val arg = args?.get(key).text()
        return when {
            args == null -> "参数不是有效的 JSON，可能内容太长被截断了，请精简后重试"
            name in FILE_TOOLS && FILE_TOOLS[name] in skillIds -> makeFile(name, args)
            name == "create_chart" && SkillIds.CALC in skillIds -> makeChart(args)
            name == "create_diagram" && SkillIds.CALC in skillIds -> makeDiagram(args)
            name == "list_files" -> listFiles()
            name == "read_file" -> readFile(args)
            name == "write_file" -> writeFile(args)
            name == "edit_file" -> editFile(args)
            name == "set_alarm" && SkillIds.ROUTINE in skillIds -> setAlarm(args)
            name == "cancel_alarm" && SkillIds.ROUTINE in skillIds -> cancelAlarm(args)
            name == "list_routines" && SkillIds.ROUTINE in skillIds -> listRoutines()
            name == "list_library" && SkillIds.LIBRARY in skillIds -> listLibrary()
            name == "save_flashcards" && SkillIds.LEARN in skillIds -> saveCards(args)
            name == "create_reminder" && SkillIds.ROUTINE in skillIds -> makeRoutine("reminder", args)
            name == "create_task" && SkillIds.ROUTINE in skillIds -> makeRoutine("task", args)
            name == "add_calendar_event" && SkillIds.CALENDAR in skillIds -> addEvent(args)
            name == "list_calendar_events" && SkillIds.CALENDAR in skillIds -> listEvents(args)
            arg.isBlank() -> "参数缺失"
            name == "use_skill" -> useSkill(arg)
            name == "run_js" && SkillIds.CALC in skillIds -> runJs(arg)
            name == "run_python" && SkillIds.PYTHON in skillIds -> runPython(arg)
            name == "search_library" && SkillIds.LIBRARY in skillIds -> searchLibrary(arg)
            name == "delete_routine" && SkillIds.ROUTINE in skillIds -> removeRoutine(arg)
            name == "delete_calendar_event" && SkillIds.CALENDAR in skillIds -> deleteEvent(arg)
            name == "web_search" && web -> search(arg)
            name == "read_webpage" && web -> readPage(arg)
            name == "search_history" -> searchOld(arg)
            name == "search_project" -> searchProject(arg)
            name == "search_chats" && SkillIds.CHATS in skillIds -> searchChats(arg)
            name == "save_memory" -> remember(arg)
            else -> "这个工具现在不可用"
        }
    }

    companion object {
        val FILE_TOOLS = mapOf(
            "create_word" to SkillIds.WORD, "create_ppt" to SkillIds.PPT,
            "create_excel" to SkillIds.EXCEL, "create_pdf" to SkillIds.PDF,
        )
        const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        val TIME = Regex("""(\d{1,2})[:：](\d{2})""")

        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "docx" -> MIME_DOCX
            "pptx" -> MIME_PPTX
            "xlsx" -> MIME_XLSX
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "html", "htm" -> "text/html"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "md", "markdown" -> "text/markdown"
            else -> "text/plain"
        }

        fun sizeText(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}

// ---------- 各工具文件共用的小工具 ----------

internal fun JsonElement?.text(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> content
    else -> toString()
}

/** "2026-10-15 09:00" → (日期, 时, 分)；只给 "09:00" 时取最近的那个时间点。 */
internal fun parseDateTime(s: String): Triple<String, Int, Int>? {
    val m = Regex("""(?:(\d{4})-(\d{1,2})-(\d{1,2})[ T]+)?(\d{1,2})[:：](\d{2})""").find(s.trim()) ?: return null
    val h = m.groupValues[4].toInt()
    val min = m.groupValues[5].toInt()
    if (h !in 0..23 || min !in 0..59) return null
    val cal = java.util.Calendar.getInstance()
    if (m.groupValues[1].isNotEmpty()) {
        cal.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
    } else {
        val now = cal.clone() as java.util.Calendar
        cal.set(java.util.Calendar.HOUR_OF_DAY, h); cal.set(java.util.Calendar.MINUTE, min)
        if (!cal.after(now)) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    return Triple(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time), h, min)
}

internal fun findSkill(skills: List<Skill>, name: String): Skill? {
    val n = name.trim()
    return skills.firstOrNull { it.name == n }
        ?: skills.firstOrNull { it.name.contains(n, true) || n.contains(it.name, true) }
}

internal fun safeFileName(name: String): String =
    name.trim().replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
        .removeSuffix(".docx").removeSuffix(".pptx").removeSuffix(".xlsx").removeSuffix(".pdf").take(60).trim()

/** 打开系统 App（时钟、日历）。找不到时给模型一句看得懂的原因。 */
internal suspend fun ToolSession.startClock(intent: Intent, missing: String = "手机上没找到能设闹钟的时钟 App") = withContext(Dispatchers.Main) {
    try {
        app.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        throw IllegalStateException(missing)
    } catch (e: SecurityException) {
        throw IllegalStateException("没有设闹钟的权限")
    }
}
