package com.jack.ai.features

import com.jack.ai.data.Feedback
import com.jack.ai.data.appVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 问题反馈：用户在某条回答上点「有问题」，把这一轮的现场存下来；攒够了导出成一个 Markdown 文件发给开发者。
 * 比截图多了工具调用的原始参数和返回，能直接看出是哪一步错的。
 */
class FeedbackFeature(core: AppCore) : Feature(core) {
    fun report(convId: String, msgId: String, note: String) {
        val conv = state.value.conversations.firstOrNull { it.id == convId } ?: return
        val i = conv.messages.indexOfFirst { it.id == msgId }.takeIf { it >= 0 } ?: return
        val reply = conv.messages[i]
        val question = conv.messages.take(i).lastOrNull { it.role == "user" }?.content.orEmpty()
        val f = Feedback(
            at = System.currentTimeMillis(),
            convTitle = conv.title,
            model = reply.model,
            appVersion = reply.appVersion.takeIf { it > 0 } ?: appVersion(app),
            note = note.trim(),
            question = question.take(4000),
            answer = reply.content.take(8000),
            steps = reply.trail.takeLast(40).map { if (it.length > 2000) it.take(2000) + "…（截断）" else it },
            errors = listOfNotNull(reply.error) + reply.searches.mapNotNull { r -> r.error?.let { "${r.query}：$it" } },
        )
        scope.launch {
            withContext(Dispatchers.IO) { store.addFeedback(f) }
            core.toast("已记下这条反馈，在「统计」页最下面导出")
        }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { store.loadFeedback().size }

    fun export(onReady: (File) -> Unit) {
        scope.launch {
            val list = withContext(Dispatchers.IO) { store.loadFeedback() }
            if (list.isEmpty()) {
                core.toast("还没有反馈")
                return@launch
            }
            val file = withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                File(dir, "JackAI反馈-${SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date())}.md").apply { writeText(render(list)) }
            }
            onReady(file)
        }
    }

    fun clear() {
        scope.launch {
            withContext(Dispatchers.IO) { store.clearFeedback() }
            core.toast("反馈已清空")
        }
    }

    companion object {
        fun render(list: List<Feedback>): String = buildString {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            append("# Jack AI 问题反馈（${list.size} 条）\n")
            list.forEachIndexed { n, f ->
                append("\n---\n\n## ${n + 1}. ${time.format(Date(f.at))}｜${f.convTitle}\n\n")
                append("- 模型：${f.model}｜App 版本：${f.appVersion}\n")
                if (f.note.isNotBlank()) append("- **用户说的问题**：${f.note}\n")
                if (f.errors.isNotEmpty()) append("- 出错：\n").also { f.errors.forEach { e -> append("  - $e\n") } }
                append("\n### 用户的问题\n\n${f.question.ifBlank { "（无）" }}\n\n### 回答\n\n${f.answer.ifBlank { "（空）" }}\n")
                if (f.steps.isNotEmpty()) {
                    append("\n### 工具调用过程\n\n")
                    f.steps.forEach { append("```json\n$it\n```\n") }
                }
            }
        }
    }
}
