package com.jack.ai.api

import kotlinx.serialization.Serializable

/**
 * 网页脚本（像油猴脚本）：按网站自动在网页上跑的一小段 JavaScript，比如自动展开全文、去掉烦人的弹层、把价格换算成人民币。
 * 用户自己写、贴别人的油猴脚本，或者让 Kavi 写（Kavi 写的存之前给用户看代码、要用户同意）。
 * 网页加载完在主页面里跑，和网页自己的脚本权限一样：碰不到手机上的文件，也碰不到 Kavi。
 */
object UserScripts {
    @Serializable
    data class Script(
        val id: String,
        val name: String,
        /** 在哪些网站上跑：网站名（含子域名），"*" 是所有网站。 */
        val sites: List<String>,
        val code: String,
        val enabled: Boolean = true,
        /** 谁写的：user 或 kavi。 */
        val by: String = "user",
        val at: Long = 0,
    )

    const val ALL = "*"
    /** 最多存几个、每个最长多少字。 */
    const val MAX = 100
    const val MAX_CODE = 100_000

    /** 这个脚本在这个网站（host）上跑不跑。 */
    fun matches(s: Script, host: String): Boolean {
        if (!s.enabled) return false
        val h = host.lowercase().removePrefix("www.")
        return s.sites.any { site -> site == ALL || h == site || h.endsWith(".$site") }
    }

    /** 用户填的网站整理一下：去掉 http://、路径、www.、*.，逗号空格分开的都认。 */
    fun cleanSites(input: String): List<String> = input.split(Regex("[,，;；\\s]+")).mapNotNull { raw ->
        val t = raw.trim().lowercase()
        when {
            t.isEmpty() -> null
            t == "*" || t == "<all_urls>" || t == "全部" || t == "所有网站" -> ALL
            else -> t.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("*.").removePrefix("www.").trim('.')
                .takeIf { it.isNotEmpty() && it != "*" && Regex("^[a-z0-9.-]+$").matches(it) } ?: if (t.substringAfter("://").startsWith("*")) ALL else null
        }
    }.distinct()

    /** 油猴脚本开头的 ==UserScript== 说明：(名字, 网站)。没有这段返回 null。 */
    fun header(code: String): Pair<String, List<String>>? {
        val block = Regex("""//\s*==UserScript==([\s\S]*?)//\s*==/UserScript==""").find(code)?.groupValues?.get(1) ?: return null
        var name = ""
        val sites = mutableListOf<String>()
        block.lines().forEach { line ->
            val m = Regex("""^\s*//\s*@(\S+)\s+(.+?)\s*$""").find(line) ?: return@forEach
            val (key, value) = m.destructured
            when (key) {
                "name" -> if (name.isEmpty()) name = value
                "name:zh-CN", "name:zh" -> name = value
                "match", "include" -> sites += cleanSites(value)
            }
        }
        return name to sites.distinct().let { if (ALL in it) listOf(ALL) else it }
    }

    /**
     * 包起来放进网页：同一页只跑一次，出错不影响网页（错误记进网页诊断）；
     * 补上油猴脚本常用的几个函数（GM_addStyle、GM_getValue / GM_setValue 存在这个网站的本地存储里、unsafeWindow）。
     */
    fun wrap(s: Script): String {
        val key = kotlinx.serialization.json.JsonPrimitive("__kaviUS_" + s.id).toString()
        val label = kotlinx.serialization.json.JsonPrimitive("[网页脚本 ${s.name}] ").toString()
        return """(function(){if(window[$key])return;window[$key]=1;
var unsafeWindow=window,GM_info={script:{name:$label}};
function GM_addStyle(c){var e=document.createElement('style');e.textContent=c;(document.head||document.documentElement).appendChild(e);return e;}
function GM_getValue(k,d){try{var v=localStorage.getItem('kaviUS:'+k);return v===null?d:JSON.parse(v);}catch(e){return d;}}
function GM_setValue(k,v){try{localStorage.setItem('kaviUS:'+k,JSON.stringify(v));}catch(e){}}
function GM_deleteValue(k){try{localStorage.removeItem('kaviUS:'+k);}catch(e){}}
function GM_log(){console.log.apply(console,arguments);}
function GM_openInTab(u){window.open(u,'_blank');}
try{
${s.code}
}catch(e){console.error($label+(e&&e.message||e));}})();"""
    }

    /** 列表里显示的网站。 */
    fun sitesText(s: Script): String = if (ALL in s.sites) "所有网站" else s.sites.joinToString("、").ifEmpty { "（没填网站，不会跑）" }
}
