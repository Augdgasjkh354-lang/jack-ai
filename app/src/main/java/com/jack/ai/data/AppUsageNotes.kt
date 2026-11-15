package com.jack.ai.data

import org.yaml.snakeyaml.Yaml

/** Mika 的 App / 网站使用知识：按真实包名、网站域名转成现有的内置备忘，正文不截成 200 字。 */
object AppUsageNotes {
    data class Policy(
        val pace: String = "",
        val mainScreenOnly: Boolean = false,
        val signedOutMarkers: List<String> = emptyList(),
    ) {
        val actionIntervalMs: Long get() = if (pace == "careful") 1_500L else 0L
        val actionLimit: Int get() = if (pace == "careful") 500 else Int.MAX_VALUE
        fun signedOutMarker(texts: List<String>): String? =
            signedOutMarkers.firstOrNull { marker -> texts.any { marker in it } }
    }

    /** 一份知识文件：头部元数据和正文（正文里提到的 references 附件附在后面）。 */
    private class Entry(val id: String, val metadata: Map<String, Any>, val body: String) {
        val display get() = metadata["display"].toString()
        fun list(key: String): List<String> = (metadata[key] as? List<*>)?.map { it.toString() }.orEmpty()
    }

    private fun entries(kind: String, file: String): List<Entry> =
        resource("$kind/index.txt").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { id ->
            val parts = resource("$kind/$id/$file").split("---", limit = 3)
            Entry(id, Yaml().load(parts[1]), withReferences(kind, id, parts[2].trim()))
        }.toList()

    /** 正文说「见 references 下某份 .md」的，把那份附上；Kavi 没有单独读附件的工具。 */
    private fun withReferences(kind: String, id: String, body: String): String {
        val refs = Regex("""`(references/[\w.-]+\.md)`""").findAll(body).map { it.groupValues[1] }.distinct()
            .mapNotNull { path -> optionalResource("$kind/$id/$path")?.let { path to it.trim() } }.toList()
        if (refs.isEmpty()) return body
        return body + refs.joinToString("") { (path, text) -> "\n\n### 附：$path\n$text" }
    }

    private val apps: List<Entry> by lazy { entries("apps", "APP.md") }
    private val sites: List<Entry> by lazy { entries("sites", "SITE.md") }

    private val byPackage: Map<String, List<SiteNote>> by lazy {
        apps.flatMap { e ->
            e.list("packages").map { pkg ->
                pkg to SiteNote(id = "builtin-app-${e.id}", site = BrowserFlows.APP_PREFIX + pkg,
                    text = "【${e.display} 使用知识 · Mika】\n${e.body}", at = 0)
            }
        }.groupBy({ it.first }, { it.second })
    }

    private val policyByPackage: Map<String, Policy> by lazy {
        apps.flatMap { e ->
            val policy = Policy(
                pace = e.metadata["pace"]?.toString().orEmpty(),
                mainScreenOnly = e.metadata["background"]?.toString() == "main_screen_only",
                signedOutMarkers = e.list("signed_out"),
            )
            e.list("packages").map { it to policy }
        }.toMap()
    }

    /** 网站知识：域名（小写、不带 www.） → 备忘。 */
    private val byDomain: List<Pair<String, SiteNote>> by lazy {
        sites.flatMap { e ->
            e.list("domains").map { it.lowercase().removePrefix("www.") }.distinct().map { domain ->
                domain to SiteNote(id = "builtin-site-${e.id}", site = domain,
                    text = "【${e.display} 网站使用知识 · Mika】\n${e.body}", at = 0)
            }
        }
    }

    fun forPackage(packageName: String): List<SiteNote> = byPackage[packageName].orEmpty()
    fun policyForPackage(packageName: String): Policy = policyByPackage[packageName] ?: Policy()

    /** 网站名（BrowserPages.site 的结果）对应的内置知识；子域名也算，同一份只给一次，最具体的域名优先。 */
    fun forSite(host: String): List<SiteNote> {
        if (host.isEmpty()) return emptyList()
        return byDomain.filter { (d, _) -> host == d || host.endsWith(".$d") }
            .sortedByDescending { it.first.length }.map { it.second }.distinctBy { it.id }.take(1)
    }

    private fun resource(path: String): String = optionalResource(path)!!

    private fun optionalResource(path: String): String? = AppUsageNotes::class.java
        .getResourceAsStream("/knowledge/$path")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
}
