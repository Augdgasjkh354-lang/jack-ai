package com.jack.ai.data.packs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 工具包的说明文件 kavi-pack.json：名字、工具、要什么权限、要用户填什么。
 * 格式是 Kavi 自己定的（见 docs/工具包与工作流方案.md），思路借鉴 Operit 的 ToolPkg，没有用它的代码。
 */

/** 工具的一个参数。type 是 JSON Schema 的基本类型。 */
@Serializable
data class PackParam(
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false,
    val enum: List<String> = emptyList(),
)

@Serializable
data class PackToolSpec(
    val name: String,
    val description: String = "",
    val parameters: Map<String, PackParam> = emptyMap(),
    /** 这个工具最多跑几秒；0 = 默认 30 秒，最多 300。 */
    val timeout: Int = 0,
)

/** 要用户在设置里填的一项（Key、账号……）。secret 的随设置加密，不进备份，界面上打码。 */
@Serializable
data class PackConfigField(
    val key: String,
    val label: String = "",
    val secret: Boolean = false,
    val required: Boolean = false,
    val hint: String = "",
)

@Serializable
data class PackPermissions(
    /** 能访问的网站。「*.example.com」= 这个域名和它所有的子域名。 */
    val net: List<String> = emptyList(),
    /** 空 = 不碰文件；pack = 只在工具包自己的数据文件夹；workspace = 还能读写当前对话的文件夹。 */
    val files: String = "",
    val notify: Boolean = false,
    /** 能用后台模型（花的钱记在统计页）。 */
    val ai: Boolean = false,
    /** 能调的 Kavi 自带工具（只读的那几个）。 */
    val tools: List<String> = emptyList(),
)

@Serializable
data class PackManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    /** quickjs（默认，App 里直接跑）或 node（Linux 沙箱里的 Node，以后做）。 */
    val runtime: String = "quickjs",
    val main: String = "main.js",
    val permissions: PackPermissions = PackPermissions(),
    val config: List<PackConfigField> = emptyList(),
    val tools: List<PackToolSpec> = emptyList(),
)

/** 权限页上的一行：图标字、标题、说明；risky 的标成醒目的颜色。 */
data class PermLine(val glyph: String, val title: String, val detail: String, val risky: Boolean = false)

object PackFormat {
    const val FILE = "kavi-pack.json"
    const val DEFAULT_TIMEOUT = 30
    const val MAX_TIMEOUT = 300
    /** 工具包能调的 Kavi 自带工具：只读的，不往外做事。 */
    val CALLABLE_TOOLS = mapOf(
        "web_search" to "联网搜索",
        "read_webpage" to "读网页",
    )
    private val ID = Regex("^[a-z0-9][a-z0-9_-]{1,31}$")
    private val TOOL = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$")
    private val KEY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,39}$")
    private val HOST = Regex("^(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    private val TYPES = setOf("string", "number", "integer", "boolean", "array", "object")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val out = Json { prettyPrint = true; encodeDefaults = false }
    fun encode(m: PackManifest): String = out.encodeToString(PackManifest.serializer(), m)

    /** 解析说明文件：成功给出说明，不对就给出用户看得懂的原因（一条条列出来）。 */
    fun parse(text: String): Result<PackManifest> {
        val m = runCatching { json.decodeFromString(PackManifest.serializer(), text) }.getOrElse { e ->
            val why = e.message.orEmpty().lineSequence().firstOrNull().orEmpty()
            return Result.failure(PackError(listOf("$FILE 不是有效的 JSON，或者缺了 id、name 这些必填的：${why.take(160)}")))
        }
        val problems = check(m)
        return if (problems.isEmpty()) Result.success(normalize(m)) else Result.failure(PackError(problems))
    }

    fun check(m: PackManifest): List<String> = buildList {
        if (!ID.matches(m.id)) add("id「${m.id}」只能用小写字母、数字、- 和 _，2 到 32 个字")
        if (m.name.isBlank()) add("name 不能空")
        if (m.runtime !in setOf("quickjs", "node")) add("runtime 只能是 quickjs 或 node")
        if (m.runtime == "node") add("这一版还不能跑 runtime 是 node 的工具包，先用默认的 quickjs")
        if (m.main.contains("..") || m.main.startsWith("/")) add("main 要是工具包里的文件")
        if (m.tools.isEmpty()) add("tools 里至少要有一个工具")
        if (m.tools.size > 30) add("一个工具包最多 30 个工具")
        val seen = mutableSetOf<String>()
        m.tools.forEach { t ->
            if (!TOOL.matches(t.name)) add("工具名「${t.name}」只能用字母、数字和 _，字母开头")
            if (!seen.add(t.name)) add("工具名「${t.name}」重复了")
            if (t.description.isBlank()) add("工具「${t.name}」要写 description（模型靠它决定什么时候用）")
            t.parameters.forEach { (k, p) ->
                if (!KEY.matches(k)) add("工具「${t.name}」的参数名「$k」只能用字母、数字和 _")
                if (p.type !in TYPES) add("工具「${t.name}」的参数「$k」类型「${p.type}」不认识")
            }
        }
        m.permissions.net.forEach { h -> if (!HOST.matches(h.lowercase())) add("网站「$h」要写域名，比如 api.example.com 或 *.example.com") }
        if (m.permissions.files !in setOf("", "pack", "workspace")) add("permissions.files 只能是 pack 或 workspace")
        m.permissions.tools.forEach { if (it !in CALLABLE_TOOLS) add("工具包只能调这些 Kavi 工具：${CALLABLE_TOOLS.keys.joinToString()}（不认识「$it」）") }
        val keys = mutableSetOf<String>()
        m.config.forEach { c ->
            if (!KEY.matches(c.key)) add("配置项「${c.key}」的名字只能用字母、数字和 _")
            if (!keys.add(c.key)) add("配置项「${c.key}」重复了")
        }
    }

    private fun normalize(m: PackManifest) = m.copy(
        name = m.name.trim().take(40),
        description = m.description.trim().take(400),
        author = m.author.trim().take(60),
        permissions = m.permissions.copy(net = m.permissions.net.map { it.lowercase().trim() }.distinct()),
        tools = m.tools.map { it.copy(timeout = it.timeout.coerceIn(0, MAX_TIMEOUT)) },
    )

    /** 这个网站在不在声明过的里面。「*.a.com」认 a.com 和 x.a.com。 */
    fun allows(net: List<String>, host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return net.any { d -> if (d.startsWith("*.")) d.removePrefix("*.").let { h == it || h.endsWith(".$it") } else h == d }
    }

    /** 权限页上一行行列出来（没要的不列）。 */
    fun permLines(m: PackManifest): List<PermLine> = buildList {
        val p = m.permissions
        if (p.net.isNotEmpty()) add(PermLine("🌐", "访问 ${p.net.size} 个网站", p.net.joinToString("\n") { if (it.startsWith("*.")) it.removePrefix("*.") + " 及其子域名" else it } +
            "\n别的网站一律连不上。", risky = p.net.size > 5))
        when (p.files) {
            "pack" -> add(PermLine("📁", "自己的数据文件夹", "只能读写它自己的文件夹，碰不到你的其他文件。"))
            "workspace" -> add(PermLine("📂", "当前对话的文件夹", "能读写正在聊的这个对话（或项目）的文件夹，包括 Kavi 做出来的文件。", risky = true))
        }
        if (p.notify) add(PermLine("🔔", "发通知", "能给你的手机发通知。"))
        if (p.ai) add(PermLine("✦", "用后台模型", "能自己问后台模型（设置里选的那个便宜的），花的钱记在统计页「后台 · 工具包」。", risky = true))
        if (p.tools.isNotEmpty()) add(PermLine("⚙", "调用 Kavi 的工具", p.tools.joinToString("、") { CALLABLE_TOOLS[it] ?: it } + "（只读的，不会往外做事）。"))
        if (m.config.isNotEmpty()) add(PermLine("🔑", "要你填", m.config.joinToString("、") { it.label.ifBlank { it.key } } +
            (if (m.config.any { it.secret }) "。带密钥的加密存在手机上，不进备份。" else "。")))
        if (isEmpty()) add(PermLine("✓", "什么权限都不要", "只在 App 里算东西，不联网、不碰文件。"))
    }

    /** 新版本比旧版本多要了哪些（更新时只在多要了时再确认）。 */
    fun widened(old: PackManifest, new: PackManifest): List<String> = buildList {
        val o = old.permissions; val n = new.permissions
        (n.net - o.net.toSet()).forEach { add("访问 $it") }
        if (n.files == "workspace" && o.files != "workspace") add("读写对话文件夹")
        if (n.files == "pack" && o.files.isEmpty()) add("自己的数据文件夹")
        if (n.notify && !o.notify) add("发通知")
        if (n.ai && !o.ai) add("用后台模型")
        (n.tools - o.tools.toSet()).forEach { add("调用 ${CALLABLE_TOOLS[it] ?: it}") }
    }

    /** 给模型的参数说明（JSON Schema）。 */
    fun schema(t: PackToolSpec): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(t.parameters.mapValues { (_, p) ->
            buildJsonObject {
                put("type", p.type)
                if (p.description.isNotBlank()) put("description", p.description.take(500))
                if (p.enum.isNotEmpty()) put("enum", JsonArray(p.enum.map { JsonPrimitive(it) }))
                if (p.type == "array") put("items", buildJsonObject { put("type", "string") })
            }
        }))
        val req = t.parameters.filterValues { it.required }.keys
        if (req.isNotEmpty()) put("required", JsonArray(req.map { JsonPrimitive(it) }))
    }
}

class PackError(val problems: List<String>) : Exception(problems.joinToString("；"))
