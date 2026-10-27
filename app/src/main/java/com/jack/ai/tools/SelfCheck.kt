package com.jack.ai.tools

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jack.ai.api.Transcriber
import com.jack.ai.chat.PromptBuilder
import com.jack.ai.data.CalendarTool
import com.jack.ai.data.PhoneFiles
import com.jack.ai.data.Routines
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.SkillIds
import com.jack.ai.data.allSkills
import com.jack.ai.data.fullAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自查（self_check）：Kavi 看清自己现在的样子——版本、模型能不能看图、这次带了哪些工具、哪些没带和为什么、
 * 设置和权限的真实状态、最近没做成的工具调用（完整原因）。只读；Key、密码一律不报。
 * 说「做不到」「没有这个功能」之前先查这个，免得把「这次没加载」当成「不存在」。
 */
internal suspend fun ToolSession.selfCheck(args: JsonObject): String = runTool("自查") {
    val ctx = app
    val text = withContext(Dispatchers.IO) {
        buildString {
            val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
            append("【版本】Kavi $ver；模型 ${modelName.ifBlank { "（未知）" }}，${if (canSee) "能看图（用户发的图片会随消息一起给你）" else "看不了图片"}\n")

            // 这次的工具：按现在的设置和已经读过的技能重算一遍
            val tools = PromptBuilder.toolSet(settings, skills, conv, project, unlocked.toSet() + conv.skillTools)
                ?.mapNotNull { runCatching { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.getOrNull() }.orEmpty()
            append("【这次带着的工具】${tools.joinToString("、")}\n")
            val missing = mutableListOf<String>()
            if ("phone_files" !in tools) missing += "phone_files：用户还没授权手机文件夹（设置 → 手机文件夹）"
            if ("web_search" !in tools) missing += "web_search、read_webpage、download_file：联网搜索没开，或没有可用的 Key"
            val locked = skills.filter { it.id in SkillIds.ON_DEMAND && it.id !in unlocked && it.id !in conv.skillTools }
            if (locked.isNotEmpty()) missing += "要先 use_skill 读做法才出现工具的技能：" + locked.joinToString("、") { "「${it.name}」" }
            val off = settings.allSkills().filter { !it.enabled }
            if (off.isNotEmpty()) missing += "用户关掉的技能（设置 → 技能 能打开）：" + off.joinToString("、") { "「${it.name}」" }
            if (missing.isNotEmpty()) append("【没带的和原因】\n" + missing.joinToString("\n") { "- $it" } + "\n")

            append("【设置】\n")
            val folders = settings.phoneFolders
            append("- 手机文件夹：" + if (folders.isEmpty()) "没有授权" else folders.joinToString("、") { it.name + if (PhoneFiles.alive(ctx, it)) "" else "（授权失效）" })
            append("\n- 转文字：" + (Transcriber.engine(settings.providers)?.let { "${it.name} ${it.model}" } ?: "没配（要硅基流动、OpenAI 或 Groq 的 Key）"))
            append("\n- 其他模型服务：" + settings.providers.filter { it.apiKey.isNotBlank() }.joinToString("、") { it.name }.ifBlank { "没有" })
            append("\n- 邮箱：" + settings.mails.filter { it.ready }.size + " 个能用")
            append("\n- 完全授权：" + if (settings.fullAuth) "开着（发信、删除这类不再弹窗问）" else "关着（做了收不回的事会先问用户）")
            append("\n- 微信消息交给 Kavi：" + if (settings.wechatWatch) "开着" else "关着")
            append("\n")

            append("【权限】\n")
            fun perm(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            val items = listOf(
                "发通知" to Routines.canNotify(ctx),
                "精确定时（提醒准点响）" to (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()),
                "读写日历" to CalendarTool.canWrite(ctx),
                "麦克风（录音纪要）" to perm(Manifest.permission.RECORD_AUDIO),
                "读微信通知" to com.jack.ai.WeChatListener.granted(ctx),
                "不被省电限制（后台任务不被掐）" to pm.isIgnoringBatteryOptimizations(ctx.packageName),
            )
            append(items.joinToString("\n") { (n, ok) -> "- $n：" + if (ok) "有" else "没有" } + "\n")
            append("（闹钟写进系统时钟，系统不给读别的闹钟详情；设完会用「下一个闹钟」核对。）\n")

            com.jack.ai.data.Facts.load(ctx).let { fs ->
                val live = fs.filter { it.status != "retired" }
                append("【事实库】${live.size} 条（已核对 ${live.count { it.status == "verified" }}，有冲突 ${live.count { it.status == "conflict" }}）；向量检索：" +
                    (if (com.jack.ai.api.Embedder.engine(settings.providers) != null) "开着（硅基流动 bge-m3）" else "没开，只按关键词找（要在 设置 → 其他模型服务 加硅基流动）") + "\n")
            }
            com.jack.ai.data.BrowserStats.summary(com.jack.ai.data.BrowserStats.load(ctx)).takeIf { it.isNotEmpty() }?.let { append("【浏览器实测】$it\n") }

            val errors = host.store.loadToolErrors().takeLast(args["errors"].text().toIntOrNull()?.coerceIn(1, 30) ?: 8)
            if (errors.isEmpty()) append("【最近没做成的工具调用】没有\n")
            else {
                val f = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
                append("【最近没做成的工具调用】（旧的在前）\n")
                errors.forEach { e ->
                    append("- ${f.format(Date(e.at))} ${e.name}" + (if (e.rejected) "（用户没同意）" else "") + "\n  参数：${e.args.take(150)}\n  结果：${e.result.take(400).replace("\n", " ")}\n")
                }
            }
        }
    }
    text to SearchRecord("自查")
}
