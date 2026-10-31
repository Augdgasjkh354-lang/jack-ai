package com.jack.ai.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 自己更新自己：查 GitHub 上 jack-ai 的最新版本，比手上的新就下载（校验 SHA-256），下好了发通知，
 * 用户点通知打开系统安装界面，自己确认覆盖安装（聊天记录不会丢）。
 * 版本号就是 CI 的构建号：装的是 versionCode N；发布的标签 N < 100 时是 v1.0.N，从 100 起是 v1.1.(N-100)。
 */
object Updater {
    const val REPO = "Augdgasjkh354-lang/jack-ai"
    private const val WORK = "jack-update"
    private const val CHANNEL = "update"
    private const val PREFS = "updater"

    data class Latest(val code: Long, val tag: String, val asset: GitHub.Asset, val sha256: String, val published: String)

    /** 下好了等安装的版本。 */
    data class Ready(val code: Long, val tag: String, val file: File, val notes: String)

    private fun JsonElement?.s(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun installedCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()

    /** v1.1.0 对应第几次打包（和 app/build.gradle.kts、打包流程里的 100 一致）。 */
    private const val V11_FROM = 100L

    /** 「v1.0.69」→ 69；「v1.1.3」→ 103。看不懂返回 null。 */
    internal fun codeOf(tag: String): Long? {
        val parts = tag.trim().removePrefix("v").split('.')
        val patch = parts.getOrNull(2)?.toLongOrNull() ?: return null
        return when (parts.take(2)) {
            listOf("1", "0") -> patch
            listOf("1", "1") -> V11_FROM + patch
            else -> null
        }
    }

    /** codeOf 反过来：打包次数 → 发布的标签。 */
    internal fun tagOf(code: Long): String = if (code >= V11_FROM) "v1.1.${code - V11_FROM}" else "v1.0.$code"

    /** GitHub 上最新发布的版本。 */
    fun latest(token: String): Latest {
        val r = GitHub.get(token, "/repos/$REPO/releases/latest").jsonObject
        val tag = r["tag_name"].s()
        val code = codeOf(tag) ?: throw GitHub.GitHubException("看不懂最新版本号：$tag")
        val a = r["assets"]?.jsonArray.orEmpty().map { it.jsonObject }.firstOrNull { it["name"].s().endsWith(".apk") }
            ?: throw GitHub.GitHubException("$tag 里还没有安装包（可能还在打包）")
        return Latest(code, tag, GitHub.Asset(a["id"].s().toLong(), a["name"].s(), a["size"].s().toLongOrNull() ?: 0),
            a["digest"].s().removePrefix("sha256:"), r["published_at"].s())
    }

    /** 从手上的版本到最新版之间改了什么：每个提交的第一行，最多 15 条。 */
    fun notes(token: String, from: Long, to: Latest): String = runCatching {
        val commits = GitHub.get(token, "/repos/$REPO/compare/${tagOf(from)}...${to.tag}").jsonObject["commits"]?.jsonArray.orEmpty()
        commits.map { it.jsonObject["commit"]?.jsonObject?.get("message").s().lineSequence().first().trim() }
            .filter { it.isNotBlank() }.reversed().take(15).joinToString("\n") { "· $it" }
    }.getOrDefault("")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates")

    /** 已经下好、还没装的新版本（装上了或文件没了就算没有）。 */
    fun ready(ctx: Context): Ready? {
        val p = prefs(ctx)
        val code = p.getLong("code", 0)
        val f = File(p.getString("path", "").orEmpty())
        if (code <= installedCode(ctx) || !f.isFile) return null
        return Ready(code, p.getString("tag", "").orEmpty(), f, p.getString("notes", "").orEmpty())
    }

    internal fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val buf = ByteArray(64 * 1024); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    sealed class Result {
        object UpToDate : Result()
        data class Downloaded(val ready: Ready) : Result()
    }

    /**
     * 查一次：有新版就下载、校验、记下来。已经下好同一个版本就不再下。
     * 网络跟着 Kavi 专用代理的分流走（GitHub 不在直连名单里）。
     */
    fun checkAndDownload(ctx: Context, token: String): Result {
        val installed = installedCode(ctx)
        val l = latest(token)
        prefs(ctx).edit().putLong("checkedAt", System.currentTimeMillis()).putLong("latest", l.code).apply()
        if (l.code <= installed) return Result.UpToDate
        ready(ctx)?.takeIf { it.code == l.code }?.let { return Result.Downloaded(it) }
        val d = dir(ctx)
        d.deleteRecursively()
        d.mkdirs()
        val f = GitHub.downloadAsset(token, REPO, l.asset, d, limit = 1L shl 30)
        if (l.sha256.isBlank()) {
            f.delete()
            throw GitHub.GitHubException("下载的安装包没有校验信息，已删掉，下次再试")
        }
        if (!sha256(f).equals(l.sha256, true)) {
            f.delete()
            throw GitHub.GitHubException("下载的安装包校验不对（可能下坏了），已删掉，下次再试")
        }
        val target = File(d, "Kavi-${l.tag}.apk")
        if (f != target) f.renameTo(target)
        val notes = notes(token, installed, l)
        prefs(ctx).edit().putLong("code", l.code).putString("tag", l.tag).putString("path", target.path).putString("notes", notes).apply()
        return Result.Downloaded(Ready(l.code, l.tag, target, notes))
    }

    /** 这个版本还没提醒过就记下来并返回 true。 */
    fun markNotified(ctx: Context, code: Long): Boolean {
        val p = prefs(ctx)
        if (p.getLong("notified", 0) >= code) return false
        p.edit().putLong("notified", code).apply()
        return true
    }

    fun checkedAt(ctx: Context): Long = prefs(ctx).getLong("checkedAt", 0)

    /** 打开系统安装界面的 Intent。装不装由用户在系统界面里确认。 */
    fun installIntent(ctx: Context, f: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 发「新版本下载好了」的通知，点一下就装。 */
    fun notifyReady(ctx: Context, r: Ready) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "版本更新", NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (!Routines.canNotify(ctx)) return
        val tap = PendingIntent.getActivity(ctx, WORK.hashCode(), installIntent(ctx, r.file),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = "点这里安装（覆盖安装，聊天记录不会丢）" + if (r.notes.isNotBlank()) "\n这次更新：\n${r.notes}" else ""
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(com.jack.ai.R.drawable.ic_widget_chat)
            .setContentTitle("Kavi 新版本 ${r.tag} 已下载好")
            .setContentText("点这里安装（覆盖安装，聊天记录不会丢）")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(WORK.hashCode(), n) } catch (_: SecurityException) {}
    }

    /** 按设置排好（或取消）后台检查：每 6 小时一次，要联网；只在 Wi-Fi 下的话就等不按流量计费的网络。 */
    fun schedule(ctx: Context, s: Settings) {
        val wm = WorkManager.getInstance(ctx)
        if (!s.autoUpdate) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (s.updateWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

/** 后台检查更新：有新版就下好、发通知。失败了不吵用户，下次再试。 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // 进程可能是为这次检查才起来的：先读设置，代理分流才就位
        val s = Store(applicationContext).loadSettings()
        if (!s.autoUpdate) return Result.success()
        return try {
            val r = Updater.checkAndDownload(applicationContext, s.githubToken)
            // 同一个版本只提醒一次
            if (r is Updater.Result.Downloaded && Updater.markNotified(applicationContext, r.ready.code)) Updater.notifyReady(applicationContext, r.ready)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}
