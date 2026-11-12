package com.jack.ai.data

import android.content.Context
import com.jack.ai.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 转到后台接着做的事（现在是下载）做完后，回头告诉那个对话里的 Kavi：
 * 回答还在进行就插进下一步；已经结束就叫醒它接着做。聊天页不在（界面被系统回收了）时先存着、推一条通知，
 * 用户点开后再送达。
 */
object BackgroundReports {
    /** text 是给模型看的话；file 是存进对话文件夹的文件（挂成卡片），存到手机的没有。 */
    data class Report(val convId: String, val text: String, val file: File? = null)

    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Report>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 聊天页（ChatViewModel）在的时候由它来取；在主线程调。 */
    @Volatile var listener: (() -> Unit)? = null

    fun drain(): List<Report> = generateSequence { pending.poll() }.toList()

    fun post(ctx: Context, r: Report, title: String) {
        pending += r
        scope.launch {
            val l = listener
            if (l != null) l()
            // 没人接：推通知，点开对话后再送达
            else Routines.notify(ctx.applicationContext, "bg-${r.convId}-${title.hashCode()}", title, "点开对话，Kavi 接着处理", openChat = r.convId)
        }
    }

    /** 给模型看的：下好了。 */
    internal fun doneText(name: String, size: Long?, where: String, url: String) =
        "【后台下载完成。这是系统通知，不是用户说的话】「$name」" + (size?.let { "（${ToolSizes.text(it)}）" }.orEmpty()) +
            "已存到$where。来源：${url.take(200)}\n用户之前交代过下好之后要做的事，现在接着做；没有交代就用一两句话告诉用户下好了、文件在哪。"

    /** 给模型看的：没下成。 */
    internal fun failedText(url: String, why: String) =
        "【后台下载没成功。这是系统通知，不是用户说的话】${url.take(200)}：$why\n告诉用户；能换办法（换个地址、用浏览器下）就换，不要原样再试一遍。"

    /** 盯着一个还在下的文件，下完（或失败）后报告。 */
    fun watchDownload(ctx: Context, convId: String, task: BrowserDownloads.Task, url: String, toPhone: Boolean) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val saved = task.result.await()
                withContext(Dispatchers.IO) { Downloads.log(app, saved, url) }
                val where = if (toPhone) saved.where else "对话文件夹"
                post(app, Report(convId, doneText(saved.name, saved.file?.length(), where, url), saved.file), "下载好了：${saved.name.take(40)}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(app, Report(convId, failedText(url, e.message ?: "下载失败")), "下载没成功")
            }
        }
    }
}
