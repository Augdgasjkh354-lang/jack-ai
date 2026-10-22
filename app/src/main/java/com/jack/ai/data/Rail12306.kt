package com.jack.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 查 12306 余票：用的是 12306 网页版查票时调的接口，不用登录，也不用自己架服务器。
 * 不是官方开放接口，12306 改版时可能要跟着改；只查不买。
 */
object Rail12306 {
    data class Station(val name: String, val code: String, val pinyin: String, val abbr: String, val city: String)

    /** 一趟车：车次、实际上下车站、时间、各席位余票（有 / 剩几张 / 无 / 未开售）。 */
    data class Train(
        val code: String,
        val from: String,
        val to: String,
        val depart: String,
        val arrive: String,
        val duration: String,
        val canBuy: Boolean,
        val seats: List<Pair<String, String>>,
    )

    class RailException(message: String) : Exception(message)

    private const val BASE = "https://kyfw.12306.cn/otn/"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /** 余票字段在「|」分隔的那一行里的位置，和 12306 网页版的解析一致。 */
    private val SEAT_FIELDS = listOf(
        32 to "商务座", 31 to "一等座", 30 to "二等座", 21 to "高级软卧", 23 to "软卧", 33 to "动卧",
        28 to "硬卧", 24 to "软座", 29 to "硬座", 26 to "无座",
    )

    /** 12306 要 cookie 才稳定，按域名在内存里记着。 */
    private val http: OkHttpClient by lazy {
        val jar = object : CookieJar {
            private val store = ConcurrentHashMap<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                store[url.host] = (store[url.host].orEmpty().filterNot { old -> cookies.any { it.name == old.name } } + cookies)
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
        }
        com.jack.ai.api.sharedHttp.newBuilder().cookieJar(jar).build()
    }

    @Volatile private var queryPath: String? = null
    @Volatile private var stations: List<Station>? = null

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Referer", BASE + "leftTicket/init").build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RailException("12306 没有响应（${resp.code}），稍后再试")
            resp.body?.string().orEmpty()
        }
    }

    // ---------- 车站 ----------

    /** 解析 12306 的 station_name.js：「@bjb|北京北|VAP|beijingbei|bjb|0|0357|北京|||@…」。 */
    fun parseStations(js: String): List<Station> =
        js.substringAfter("'").substringBeforeLast("'").split('@').mapNotNull { s ->
            val f = s.split('|')
            if (f.size < 5 || f[1].isBlank() || f[2].isBlank()) null
            else Station(f[1], f[2], f[3], f[4], f.getOrNull(7).orEmpty().ifBlank { f[1] })
        }

    /** 车站表一周更新一次，存在手机里。 */
    private fun stations(dir: File): List<Station> {
        stations?.let { return it }
        val f = File(dir, "stations_12306.js")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L
        val js = if (fresh) f.readText() else runCatching {
            get(BASE + "resources/js/framework/station_name.js").also { if (it.contains('@')) f.writeText(it) }
        }.getOrElse { if (f.exists()) f.readText() else throw RailException("下载 12306 车站表失败，检查网络") }
        return parseStations(js).also { if (it.isNotEmpty()) stations = it }
    }

    /**
     * 站名或城市名 → 电报码。写城市名（北京）会查这个城市所有车站；也认拼音和简拼。
     * 找不到返回 null。
     */
    fun resolve(list: List<Station>, input: String): Station? {
        val n = input.trim().removeSuffix("站").removeSuffix("火车").trim()
        if (n.isEmpty()) return null
        val lower = n.lowercase()
        return list.firstOrNull { it.name == n }
            ?: list.firstOrNull { it.code.equals(n, true) && n.length == 3 && n.all { c -> c.isLetter() } }
            ?: list.firstOrNull { it.pinyin == lower || it.abbr == lower }
            ?: list.firstOrNull { it.city == n }
            ?: list.firstOrNull { it.name.startsWith(n) }
    }

    // ---------- 余票 ----------

    /** 解析一行余票数据（「|」分隔）。map 是电报码 → 站名。 */
    fun parseTrain(line: String, map: Map<String, String>): Train? {
        val f = line.split('|')
        if (f.size < 34) return null
        fun station(code: String) = map[code] ?: code
        val seats = SEAT_FIELDS.mapNotNull { (i, label) ->
            val v = f[i].trim()
            if (v.isEmpty()) null else label to when (v) {
                "*" -> "未开售"
                else -> v
            }
        }
        return Train(f[3], station(f[6]), station(f[7]), f[8], f[9], f[10], f[11] == "Y", seats)
    }

    /** 查一天的车。date 是 yyyy-MM-dd。 */
    fun query(dir: File, date: String, from: String, to: String): Pair<String, List<Train>> {
        val list = stations(dir)
        val a = resolve(list, from) ?: throw RailException("不认识「$from」这个车站或城市")
        val b = resolve(list, to) ?: throw RailException("不认识「$to」这个车站或城市")
        val path = queryPath ?: runCatching {
            Regex("""CLeftTicketUrl\s*=\s*'([^']+)'""").find(get(BASE + "leftTicket/init"))?.groupValues?.get(1)
        }.getOrNull() ?: "leftTicket/queryG"
        queryPath = path
        fun url(p: String) = BASE + p + "?leftTicketDTO.train_date=$date&leftTicketDTO.from_station=${a.code}" +
            "&leftTicketDTO.to_station=${b.code}&purpose_codes=ADULT"
        var body = get(url(path))
        var obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        // 查票接口改名了：12306 会在 c_url 里告诉新名字
        val moved = (obj?.get("c_url") as? JsonPrimitive)?.content
        if (moved != null && moved != path) {
            queryPath = moved
            body = get(url(moved))
            obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        }
        val data = obj?.get("data") as? JsonObject
            ?: throw RailException("12306 没返回车次（日期可能不在预售期内，一般只能查今天起 15 天内；也可能是 12306 正在维护）")
        val map = (data["map"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }
        val trains = (data["result"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let { l -> parseTrain(l, map) } }
        return "${a.name} → ${b.name}" to trains
    }

    /** 把车次整理成给模型看的文字，一趟一行。 */
    fun describe(t: Train): String =
        "${t.code}｜${t.from} ${t.depart} → ${t.to} ${t.arrive}｜历时 ${t.duration}｜" +
            (if (t.seats.isEmpty()) "暂无席位信息" else t.seats.joinToString("，") { (k, v) -> "$k $v" }) +
            if (!t.canBuy) "｜暂不可订" else ""
}
