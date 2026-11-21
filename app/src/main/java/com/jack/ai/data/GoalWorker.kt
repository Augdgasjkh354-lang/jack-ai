package com.jack.ai.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jack.ai.WorkService
import com.jack.ai.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** 思路来自 deepseek-ai/deepseek-harness packages/goal/goal-round-driver/，MIT。连续续做最多五轮，每轮开始前持久计数。 */
class GoalWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("convId") ?: return Result.failure()
        val ctx = applicationContext
        if (id in Goals.foreground) return Result.retry()
        val owner = currentCoroutineContext().job
        if (Goals.workers.putIfAbsent(id, owner) != null) return Result.retry()
        var title = "目标续做"
        try {
            while (true) {
                val goal = Goals.current(ctx, id) ?: return Result.success()
                if (goal.status != "active" || id in Goals.foreground) return Result.success()
                if (!goal.canContinue) {
                    Goals.change(ctx, id) { if (it?.id == goal.id) it.update("paused", "连续自动续做已达 5 轮，请查看进展后点继续。") else it }
                    Routines.notify(ctx, Goals.workId(id), "目标已暂停", "自动续做已达 5 轮，点开对话查看进展。", openChat = id)
                    return Result.success()
                }
                if (Goals.pauseForBudget(ctx, id)) return Result.success()
                // 上一轮做到一半被杀（inRound 还挂着）：接着这一轮，不再加一；新的一轮才计数
                val resuming = goal.inRound
                val admitted = Goals.change(ctx, id) { g ->
                    if (g?.id == goal.id && g.canContinue) g.copy(autoRounds = if (g.inRound) g.autoRounds else g.autoRounds + 1, inRound = true) else g
                } ?: return Result.success()
                /** 这一轮收尾了（做完、出错、被停）：下一次就是新的一轮。 */
                fun endRound() = Goals.change(ctx, id) { g -> if (g?.id == goal.id) g.copy(inRound = false) else g }
                if (admitted.id != goal.id || admitted.status != "active") return Result.success()
                title = goal.objective
                WorkService.start(ctx, id, title, true)
                runCatching { setForeground(WorkService.foregroundInfo(ctx)) }
                val result = try {
                    coroutineScope {
                        val run = async(start = CoroutineStart.LAZY) {
                            runBackgroundWork(ctx, Routine(id = Goals.workId(id), title = title, prompt = Goals.prompt(goal), hour = 0, minute = 0, convId = id, work = true),
                                goalId = goal.id, goalSpent = admitted.spent, roundResumed = resuming, brief = "【Goal continuation】Continue the user's persistent goal. Read get_goal first, verify acceptance criteria, record progress with update_goal, mark complete only when all criteria are met. Stop for any user confirmation or takeover.",
                                onProgress = { WorkService.progress(ctx, id, title, it, true) })
                        }
                        val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
                            Goals.changes.collect { changed ->
                                if (changed == id) {
                                    val g = Goals.current(ctx, id)
                                    if (g?.id != goal.id || g.status in setOf("paused", "blocked") || id in Goals.foreground) run.cancel()
                                }
                            }
                        }
                        try { run.await() } finally { monitor.cancel() }
                    }
                } catch (e: CancellationException) {
                    // 被用户停下、接管（进程被杀时这里跑不到，inRound 留着，重跑时接着这一轮）
                    withContext(NonCancellable) { endRound() }
                    currentCoroutineContext().ensureActive()
                    return Result.success()
                } catch (e: GoalStopped) {
                    endRound()
                    return Result.success()
                } catch (e: Exception) {
                    // 出错也算这一轮结束了：不然一直出错时轮数不涨，会无休止地重试
                    endRound()
                    if (e is com.jack.ai.api.ApiException && e.code in setOf(400, 401, 402, 403, 404, 422)) {
                        Goals.change(ctx, id) { g -> if (g?.id == goal.id) g.update("blocked", ToolSession.errorText(e)) else g }
                        return Result.success()
                    }
                    Goals.change(ctx, id) { g -> if (g?.id == goal.id && g.status == "active") g.update(note = "本轮中断：${ToolSession.errorText(e)}") else g }
                    delay(5_000)
                    continue
                }
                val store = Store(ctx)
                store.addInbox(InboxItem(Goals.workId(id), id, title, "", result.text, files = result.files, aside = true, work = true))
                Goals.changes.tryEmit(id)
                if (result.status == TaskStatus.NEEDS_SETUP) {
                    Goals.change(ctx, id) { it?.update("blocked", result.text) }
                    return Result.success()
                }
                Goals.change(ctx, id) { g -> if (g?.id == goal.id && g.status == "active") g.update(note = result.text) else g }
                val limit = store.loadSettings().workCostLimit
                // result.spent 只是这一轮花的（见 runBackgroundWork 的 goalSpent），这里累加到整个目标上；同时收掉这一轮
                val total = Goals.change(ctx, id) { g -> if (g?.id == goal.id) g.copy(spent = g.spent + result.spent, inRound = false) else g }?.spent ?: result.spent
                if (Goal.budgetReached(limit, total) && Goals.current(ctx, id)?.status == "active") {
                    Goals.change(ctx, id) { it?.update("paused", "自动续做累计花了 ¥%.2f，到了任务上限，请查看进展后继续。".format(total)) }
                    Routines.notify(ctx, Goals.workId(id), "目标已暂停", "自动续做累计花费已达上限。", openChat = id)
                }
            }
        } finally {
            Goals.workers.remove(id, owner)
            WorkService.finish(ctx, id, title, "目标续做已停止", true, notifyResult = false)
        }
    }
}

/** 思路来自 deepseek-ai/deepseek-harness packages/goal/goal-round-driver/，MIT。正常停止不当成网络故障重试。 */
class GoalStopped : Exception()
