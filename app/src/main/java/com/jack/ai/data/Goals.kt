package com.jack.ai.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 思路来自 deepseek-ai/deepseek-harness packages/goal/goal-round-driver/，MIT。持久状态与进程内执行占用分开。 */
object Goals {
    val changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val foreground: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val workers = ConcurrentHashMap<String, Job>()
    fun workId(convId: String) = "goal-$convId"
    fun current(ctx: Context, id: String) = Store(ctx).loadConversations().firstOrNull { it.id == id }?.goal
    fun prompt(g: Goal) = "目标：${g.objective}\n验收标准：\n" + g.criteria.joinToString("\n") { "- $it" }
    fun monthlySpent(store: Store): Double {
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        return store.loadUsage().filter { it.date.startsWith(month) }.sumOf { it.costYuan() ?: 0.0 }
    }

    /** 思路来自 deepseek-ai/deepseek-harness packages/goal/goal/，MIT。写入完成才广播；终态只在状态切换时通知。 */
    fun change(ctx: Context, id: String, fallback: Conversation? = null, transform: (Goal?) -> Goal?): Goal? {
        var before: Goal? = null
        val conv = Store(ctx).changeGoal(id, fallback) { old -> before = old; transform(old) } ?: return null
        val goal = conv.goal
        changes.tryEmit(id)
        if (goal?.status in setOf("complete", "blocked") && goal?.status != before?.status) {
            Routines.notify(ctx, workId(id), "目标${goal!!.label}：${goal.objective}", goal.progress.lastOrNull() ?: goal.objective, openChat = id)
        }
        if (goal?.status == "active") schedule(ctx, id)
        else if (!workers.containsKey(id)) WorkManager.getInstance(ctx).cancelUniqueWork(workId(id))
        return goal
    }

    /** 思路来自 deepseek-ai/deepseek-harness packages/goal/goal-round-driver/，MIT。WorkManager 负责杀进程、重启与网络恢复后的调度。 */
    fun schedule(ctx: Context, id: String, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<GoalWorker>()
            .setInputData(workDataOf("convId" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(workId(id), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun recover(ctx: Context) {
        Store(ctx).loadConversations().filter { it.goal?.status == "active" }.forEach { schedule(ctx, it.id) }
    }

    fun pauseForBudget(ctx: Context, id: String): Boolean {
        val store = Store(ctx)
        val limit = store.loadSettings().monthlyBudget
        if (limit <= 0 || !Goal.budgetReached(limit, monthlySpent(store))) return false
        val paused = change(ctx, id) { if (it?.status == "active") it.update("paused", "本月预算已用完，自动续做已暂停。") else it }
        if (paused?.status == "paused") Routines.notify(ctx, workId(id), "目标已暂停", "本月预算已用完，打开对话查看进展。", openChat = id)
        return true
    }
}
