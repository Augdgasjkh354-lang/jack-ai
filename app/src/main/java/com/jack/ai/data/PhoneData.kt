package com.jack.ai.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.jack.ai.a11y.PhoneGuard
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 记下来的一条通知：哪个 App、标题、正文（验证码已遮掉）、什么时候。 */
@Serializable
data class Notice(val at: Long, val pkg: String, val app: String, val title: String, val text: String)

/**
 * 手机上的数据，每样单独开关、默认关：
 * - 通知：所有 App 的通知记三天（涉及钱的 App、Kavi 自己、常驻和进度通知不记，微信私聊另有「微信消息交给 Kavi」），验证码遮掉；
 * - 联系人：按名字或号码查；
 * - 位置：大概在哪（只用粗略定位，不在后台跟踪）；
 * - 使用时长：今天、这几天各个 App 用了多久。
 * Kavi 用 phone_data 工具查；「主动找你」回看时带上最近的通知和今天的使用时长。
 */
object PhoneData {
    private const val KEEP_MS = 3 * 86_400_000L
    private const val MAX = 400
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "notifications.json")

    // ---------- 通知（纯规则，单测） ----------

    private val OTP_HINT = Regex("验证码|校验码|动态码|动态密码|确认码|驗證碼|verification|code|OTP|passcode", RegexOption.IGNORE_CASE)
    private val OTP = Regex("(?<![0-9A-Za-z])[0-9A-Za-z]{4,8}(?![0-9A-Za-z])")

    /** 像验证码的通知：把 4 到 8 位的码遮掉（Kavi 用不着它，被骗去转发就麻烦了）。 */
    fun mask(text: String): String =
        if (!OTP_HINT.containsMatchIn(text)) text
        else OTP.replace(text) { m -> if (m.value.any { it.isDigit() }) "****" else m.value }

    /** 这条通知记不记。 */
    fun keep(pkg: String, app: String, self: String, skip: Collection<String>, ongoing: Boolean, title: String, text: String): Boolean = when {
        ongoing || pkg == self || pkg in skip -> false
        pkg == WeChat.PACKAGE -> false
        PhoneGuard.money(pkg, app) -> false
        title.isBlank() && text.isBlank() -> false
        else -> true
    }

    fun notices(ctx: Context): List<Notice> = synchronized(lock) {
        runCatching { json.decodeFromString(ListSerializer(Notice.serializer()), file(ctx).readText()) }.getOrDefault(emptyList())
    }

    /** 记一条：同一个 App 一样的内容一分钟内不重复记，只留三天、最多 400 条。 */
    fun add(ctx: Context, n: Notice) = synchronized(lock) {
        val list = notices(ctx)
        if (list.any { it.pkg == n.pkg && it.title == n.title && it.text == n.text && n.at - it.at < 60_000 }) return@synchronized
        val kept = (list + n).filter { n.at - it.at < KEEP_MS }.takeLast(MAX)
        runCatching { file(ctx).writeText(json.encodeToString(ListSerializer(Notice.serializer()), kept)) }
    }

    /** 查通知：最近几小时、哪个 App（名字或包名）、包含什么字。 */
    fun search(list: List<Notice>, query: String, app: String, hours: Int, now: Long = System.currentTimeMillis()): List<Notice> {
        val q = query.trim(); val a = app.trim()
        return list.filter { now - it.at <= hours * 3_600_000L }
            .filter { a.isEmpty() || it.pkg == a || a in it.app }
            .filter { q.isEmpty() || q in it.title || q in it.text }
            .sortedByDescending { it.at }
    }

    fun format(list: List<Notice>, max: Int = 40): String {
        val f = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        return list.take(max).joinToString("\n") { "${f.format(Date(it.at))}［${it.app}］${it.title.take(60)}：${it.text.replace('\n', ' ').take(200)}" }
    }

    // ---------- 联系人 ----------

    fun canContacts(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** 按名字或号码找联系人：名字、电话、邮箱。 */
    fun contacts(ctx: Context, query: String, max: Int = 10): String {
        val q = query.trim()
        if (q.isEmpty()) return "要填 query（名字或号码的一部分）。"
        val uri = ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon().appendPath(q).build()
        val out = ArrayList<String>()
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < max) {
                val id = c.getString(0); val name = c.getString(1).orEmpty()
                val phones = column(ctx, ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID, id)
                val mails = column(ctx, ContactsContract.CommonDataKinds.Email.CONTENT_URI, ContactsContract.CommonDataKinds.Email.ADDRESS,
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID, id)
                out += name + (if (phones.isNotEmpty()) "：电话 " + phones.joinToString("、") else "") + (if (mails.isNotEmpty()) "；邮箱 " + mails.joinToString("、") else "")
            }
        }
        return if (out.isEmpty()) "通讯录里没找到「$q」。" else out.joinToString("\n")
    }

    private fun column(ctx: Context, uri: android.net.Uri, col: String, idCol: String, id: String): List<String> =
        ctx.contentResolver.query(uri, arrayOf(col), "$idCol = ?", arrayOf(id), null)?.use { c ->
            buildList { while (c.moveToNext()) c.getString(0)?.replace(" ", "")?.let { if (it !in this) add(it) } }
        }.orEmpty()

    // ---------- 位置 ----------

    fun canLocate(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 大概在哪：先用最近一次定位，太旧就现定一次（最多等十秒）；能查到地址就给地址。 */
    @SuppressLint("MissingPermission")
    suspend fun location(ctx: Context): String {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return "手机的定位服务关着。请用户在系统设置里打开「位置信息」。"
        var loc: Location? = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if ((loc == null || System.currentTimeMillis() - loc.time > 30 * 60_000L) && Build.VERSION.SDK_INT >= 30) {
            loc = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                kotlinx.coroutines.suspendCancellableCoroutine<Location?> { c ->
                    runCatching {
                        lm.getCurrentLocation(providers.first(), null, ctx.mainExecutor) { l -> if (c.isActive) c.resumeWith(Result.success(l)) }
                    }.onFailure { if (c.isActive) c.resumeWith(Result.success(null)) }
                }
            } ?: loc
        }
        loc ?: return "定不到位置（可能在室内、刚开机，或定位服务没准备好）。"
        val ago = ((System.currentTimeMillis() - loc.time) / 60_000).coerceAtLeast(0)
        @Suppress("DEPRECATION")
        val addr = runCatching { if (Geocoder.isPresent()) Geocoder(ctx, Locale.CHINA).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() else null }.getOrNull()
        val place = addr?.let { a -> (0..a.maxAddressLineIndex).joinToString(" ") { a.getAddressLine(it) }.ifBlank { listOfNotNull(a.adminArea, a.locality, a.subLocality, a.thoroughfare).joinToString("") } }
        return (if (!place.isNullOrBlank()) "大概在：$place\n" else "") +
            "坐标：%.4f, %.4f（精度约 %d 米，%s）".format(loc.latitude, loc.longitude, loc.accuracy.toInt(), if (ago < 1) "刚刚" else "$ago 分钟前") +
            if (place.isNullOrBlank()) "\n手机查不到地址名（国内很多手机没带地址查询）；要知道在哪个区，可以按坐标联网搜。" else ""
    }

    // ---------- 使用时长 ----------

    fun canUsage(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 最近几天（1 是今天）各 App 在前台用了多久，前十个。 */
    fun usage(ctx: Context, days: Int, now: Long = System.currentTimeMillis()): String {
        val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = java.util.Calendar.getInstance().apply {
            timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            add(java.util.Calendar.DAY_OF_YEAR, -(days - 1))
        }.timeInMillis
        val stats = um.queryAndAggregateUsageStats(start, now).values
            .filter { it.totalTimeInForeground > 60_000 && it.packageName != ctx.packageName }
            .sortedByDescending { it.totalTimeInForeground }
        if (stats.isEmpty()) return "没有使用记录（可能刚给权限，系统还没攒下数据）。"
        val pm = ctx.packageManager
        fun label(p: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        val total = stats.sumOf { it.totalTimeInForeground }
        return (if (days == 1) "今天" else "最近 $days 天") + "一共用了 ${dur(total)}（各 App 在前台的时间加起来）：\n" +
            stats.take(10).joinToString("\n") { "- ${label(it.packageName)}：${dur(it.totalTimeInForeground)}" }
    }

    fun dur(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟"
    }
}
