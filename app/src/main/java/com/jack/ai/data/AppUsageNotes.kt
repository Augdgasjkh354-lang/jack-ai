package com.jack.ai.data

import org.yaml.snakeyaml.Yaml

/** Mika 的 App 使用知识：按真实包名转成现有的内置备忘，正文不截成 200 字。 */
object AppUsageNotes {
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

    fun forPackage(packageName: String): List<SiteNote> = byPackage[packageName].orEmpty()

    private fun resource(path: String): String = AppUsageNotes::class.java
        .getResourceAsStream("/knowledge/apps/$path")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
