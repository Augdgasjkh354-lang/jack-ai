package com.jack.ai.tools

import com.jack.ai.api.DeepSeekClient
import com.jack.ai.data.Endpoint
import com.jack.ai.data.Persona
import com.jack.ai.data.PlanItem
import com.jack.ai.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Work 的收尾关卡，不靠提示词自觉，靠代码看清单：
 * - 开工后第一次调别的工具时还没列清单，在工具结果后面提醒一次；
 * - 模型想收尾时：没列清单、还有没做完的步骤、验收标准没写或没核对，就推回去接着做（最多 [MAX_NUDGES] 次，免得死循环）；
 * - 清单齐了，开着「交付前复核」时再请另一次请求（不看过程、只看结果）按验收标准复核一次，不合格打回去改一次。
 */
class WorkGate(
    private val session: ToolSession,
    /** 复核：给最终回答，返回复核结果；null 表示不复核（关了，或者没配好模型）。 */
    private val review: (suspend (answer: String) -> Review?)? = null,
) {
    data class Review(val pass: Boolean, val problems: List<String>)

    private var nudges = 0
    private var reviewed = false
    private var reminded = false

    private fun fill(t: String) = Persona.fill(t, session.settings)

    /** 加在工具结果后面：Work 开工了还没列清单，提醒一次。 */
    fun reminder(name: String): String {
        if (!session.conv.work || reminded || session.steps.isNotEmpty() || name in NO_REMINDER) return ""
        reminded = true
        return "\n\n(Work mode goes step by step: write the step list and acceptance criteria with update_plan now, before going further.)"
    }

    /** 模型这一轮想直接收尾：返回 null 就让它收；返回一段话就塞回去让它接着做。 */
    suspend fun beforeFinish(answer: String): String? {
        if (!session.conv.work || session.maxRounds == 0) return null
        // 一个工具都没调过的，是问答不是任务，不拦
        if (session.calls.get() == 0 && session.steps.isEmpty()) return null
        if (nudges < MAX_NUDGES) {
            unfinished(session.steps, session.criteria)?.let { nudges++; return fill(it) }
        }
        val r = review ?: return null
        if (reviewed || session.steps.isEmpty()) return null
        reviewed = true
        val result = runCatching { r(answer) }.getOrNull() ?: return null
        if (result.pass || result.problems.isEmpty()) {
            session.ui.notice("交付前复核：通过")
            return null
        }
        session.ui.notice("交付前复核：发现 ${result.problems.size} 处问题，已让 Kavi 接着改")
        return fill(
            "An independent review of your result, against {user}'s request and your acceptance criteria, found these problems:\n" +
                result.problems.joinToString("\n") { "- $it" } +
                "\nFix them, update the plan and criteria with update_plan, then deliver again. If something truly can't be fixed, say so plainly in your final message.",
        )
    }

    companion object {
        const val MAX_NUDGES = 3
        /** 这些工具本身就是在准备或读东西，不用提醒列清单。 */
        private val NO_REMINDER = setOf("update_plan", "use_skill", "read_file", "self_check")

        /** 还差什么：没清单、没做完的步骤、没写或没核对的验收标准。都齐了返回 null。 */
        internal fun unfinished(steps: List<PlanItem>, criteria: List<PlanItem>): String? {
            if (steps.isEmpty()) return "You're about to finish, but there is no plan yet. Work mode goes step by step: write the steps and 2 to 6 checkable acceptance criteria with update_plan, " +
                "check each against what you've actually done, finish whatever is still missing, then deliver."
            val open = steps.withIndex().filter { it.value.status == "pending" || it.value.status == "doing" }
            val unchecked = criteria.filter { it.status == "pending" }
            if (open.isEmpty() && unchecked.isEmpty() && criteria.isNotEmpty()) return null
            return buildString {
                append("You're about to finish, but the plan isn't complete:\n")
                open.forEach { (i, s) -> append("- step ${i + 1} 「${s.text}」 is still ${s.status}\n") }
                unchecked.forEach { append("- criterion 「${it.text}」 hasn't been checked\n") }
                if (criteria.isEmpty()) append("- there are no acceptance criteria: add 2 to 6 checkable ones with update_plan\n")
                append("Keep working on these. Before marking a criterion met, check it against the actual results (read the file back, count the items, open the sources). ")
                append("If a step truly can't be done, mark it skipped, or mark the criterion failed, with update_plan and explain why in your final message.")
            }
        }

        /** 这次回答里新生成或改过的文件（对话文件夹里，修改时间在 since 之后）。 */
        internal fun produced(dir: File, since: Long): List<File> =
            Workspace.files(dir).filter { it.lastModified() >= since }.sortedByDescending { it.lastModified() }

        private const val REVIEW_RULES = """You are an independent reviewer. You did not do this work and you can't see how it was done; you only see the request, the acceptance criteria, the final answer and the files produced.
Check the result against the request and each criterion. Flag only concrete, checkable problems: a criterion not actually met, something requested that is missing, numbers that don't add up, missing or made-up-looking sources, the answer claiming something the files don't show, a file that is empty or cut off. Don't flag style or wording, and don't ask for extras nobody requested.
Output only one JSON object: {"pass": true or false, "problems": ["one sentence each, at most 5, in the language of the request"]}"""

        /** 复核用的那次请求：只给要求、标准、回答、产出的文件内容，不给干活过程。 */
        fun reviewer(session: ToolSession, client: DeepSeekClient, ep: Endpoint, request: String, since: Long): suspend (String) -> Review? = { answer ->
            val dir = Workspace.dir(session.app, Workspace.key(session.conv))
            val files = withContext(Dispatchers.IO) {
                produced(dir, since).take(3).map { f ->
                    val text = runCatching { Workspace.readText(f, extracted = true) }.getOrDefault("")
                    "【File: ${f.name}】\n" + (if (text.isBlank()) "(no readable text)" else text.take(6000)) + "\n【End of file】"
                }
            }
            val input = buildString {
                append("【Request】\n").append(request.take(4000)).append("\n\n")
                append("【Acceptance criteria】\n").append(session.criteria.joinToString("\n") { "- [${it.status}] ${it.text}" }.ifEmpty { "(none written)" }).append("\n\n")
                append("【Steps】\n").append(session.steps.joinToString("\n") { "- [${it.status}] ${it.text}" }).append("\n\n")
                append("【Final answer】\n").append(answer.take(6000)).append("\n\n")
                append("【Files produced】\n").append(files.joinToString("\n\n").ifEmpty { "(none)" })
            }
            val raw = client.complete(
                ep,
                listOf(
                    buildJsonObject { put("role", "system"); put("content", REVIEW_RULES) },
                    buildJsonObject { put("role", "user"); put("content", input) },
                ),
                maxTokens = 2048,
                jsonMode = true,
            )
            parse(raw)
        }

        internal fun parse(raw: String): Review? = runCatching {
            val o = Json.parseToJsonElement(raw.trim().removePrefix("```json").removeSuffix("```").trim()).jsonObject
            val pass = (o["pass"] as? JsonPrimitive)?.content == "true"
            val problems = (o["problems"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { t -> t.isNotEmpty() } }.take(5)
            Review(pass, problems)
        }.getOrNull()
    }
}
