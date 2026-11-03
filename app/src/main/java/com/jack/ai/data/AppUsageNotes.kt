package com.jack.ai.data

import org.yaml.snakeyaml.Yaml

/** Mika 的 App 使用知识：按真实包名转成现有的内置备忘，正文不截成 200 字。 */
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

    private val byPackage: Map<String, List<SiteNote>> by lazy {
        val entries = resource("index.txt").lineSequence().filter { it.isNotBlank() }.flatMap { id ->
            val parts = resource("$id/APP.md").split("---", limit = 3)
            val metadata = Yaml().load<Map<String, Any>>(parts[1])
            val display = metadata["display"] as String
            val body = parts[2].trim()
            (metadata["packages"] as List<*>).asSequence().map { pkg ->
                val site = BrowserFlows.APP_PREFIX + pkg
                SiteNote(id = "builtin-app-$id", site = site,
                    text = "【$display 使用知识 · Mika】\n$body", at = 0)
            }
        }.toList()
        entries.groupBy { it.site.removePrefix(BrowserFlows.APP_PREFIX) }
    }

    private val policyByPackage: Map<String, Policy> by lazy {
        resource("index.txt").lineSequence().filter { it.isNotBlank() }.flatMap { id ->
            val parts = resource("$id/APP.md").split("---", limit = 3)
            val metadata = Yaml().load<Map<String, Any>>(parts[1])
            val policy = Policy(
                pace = metadata["pace"]?.toString().orEmpty(),
                mainScreenOnly = metadata["background"]?.toString() == "main_screen_only",
                signedOutMarkers = (metadata["signed_out"] as? List<*>)?.map { it.toString() }.orEmpty(),
            )
            (metadata["packages"] as List<*>).asSequence().map { it.toString() to policy }
        }.toMap()
    }

    fun forPackage(packageName: String): List<SiteNote> = byPackage[packageName].orEmpty()
    fun policyForPackage(packageName: String): Policy = policyByPackage[packageName] ?: Policy()

    private fun resource(path: String): String = AppUsageNotes::class.java
        .getResourceAsStream("/knowledge/apps/$path")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
