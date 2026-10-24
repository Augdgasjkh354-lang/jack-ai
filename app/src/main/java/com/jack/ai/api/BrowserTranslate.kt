package com.jack.ai.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 网页翻译：网页上的文字按「一段文字一个编号」取出来，分批交给模型翻成中文，再按编号换回去。
 * 只换文字、不动网页结构，链接和按钮都还能点；「显示原文」就把原来的文字放回去。
 */
object BrowserTranslate {
    /** 一次最多翻多少段（太长的网页只翻前面这些）。 */
    const val MAX_SEGMENTS = 600

    /** 取出要翻的文字：跳过脚本、代码、输入框，跳过太短、纯数字符号、已经是中文的。返回 JSON 数组。 */
    const val COLLECT_JS = """(function(){
var skip={SCRIPT:1,STYLE:1,NOSCRIPT:1,CODE:1,PRE:1,TEXTAREA:1,INPUT:1,SELECT:1,svg:1,SVG:1,MATH:1};
var segs=[];window.__jackSeg=[];if(!document.body)return JSON.stringify(segs);
var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{acceptNode:function(n){
for(var e=n.parentElement;e&&e!==document.body;e=e.parentElement){if(skip[e.tagName]||e.isContentEditable)return 2;}
var t=n.nodeValue.trim();if(t.length<2)return 2;
if(!/[A-Za-zÀ-ɏЀ-ӿ぀-ヿ가-힯]/.test(t))return 2;
var cjk=(t.match(/[一-鿿]/g)||[]).length;if(cjk/t.length>0.3)return 2;
return 1;}});
var n;while((n=w.nextNode())&&segs.length<600){window.__jackSeg.push(n);if(n.__jackOrig===undefined)n.__jackOrig=n.nodeValue;segs.push(n.__jackOrig.trim().slice(0,1000));}
return JSON.stringify(segs);})()"""

    /** 按编号换成译文（保留原文前后的空白）。 */
    fun applyJs(translations: Map<Int, String>): String {
        val obj = buildJsonObject { translations.forEach { (k, v) -> put(k.toString(), v) } }
        return "(function(m){var s=window.__jackSeg||[];for(var k in m){var n=s[+k];if(!n||n.__jackOrig===undefined)continue;" +
            "var o=n.__jackOrig;n.nodeValue=o.match(/^\\s*/)[0]+m[k]+o.match(/\\s*$/)[0];}})($obj);"
    }

    /** 放回原文。 */
    const val RESTORE_JS = "(function(){(window.__jackSeg||[]).forEach(function(n){if(n.__jackOrig!==undefined)n.nodeValue=n.__jackOrig;});})();"

    /** 分批：每批不超过 maxItems 段、maxChars 字（一段本身超长也单独成一批）。返回每批的编号。 */
    fun batches(segs: List<String>, maxItems: Int = 50, maxChars: Int = 3000): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        var cur = mutableListOf<Int>()
        var chars = 0
        segs.forEachIndexed { i, s ->
            if (cur.isNotEmpty() && (cur.size >= maxItems || chars + s.length > maxChars)) { out += cur; cur = mutableListOf(); chars = 0 }
            cur += i; chars += s.length
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    const val SYSTEM = "你是网页翻译。把网页上的文字翻译成自然、通顺的简体中文。" +
        "输入是一个 JSON 对象：键是编号，值是网页上的一段文字（同一句话可能被拆在相邻的几段里）。" +
        "输出同样格式的 JSON 对象：键不变、一个不少，值换成译文。只翻译，不解释、不加内容；" +
        "人名、品牌、产品型号、代码、网址保持原样；原文是中文或没有可翻译的，就原样返回。"

    /** 一批的输入：{"编号": "原文"}。 */
    fun request(segs: List<String>, ids: List<Int>): String = buildJsonObject { ids.forEach { put(it.toString(), segs[it]) } }.toString()

    /** 模型返回的 JSON 里取出译文；只要这一批里的编号、不要空的。 */
    fun parse(raw: String, ids: List<Int>): Map<Int, String> {
        val obj = runCatching {
            Json.parseToJsonElement(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).jsonObject
        }.getOrNull() ?: return emptyMap()
        val wanted = ids.toSet()
        return obj.entries.mapNotNull { (k, v) ->
            val id = k.trim().toIntOrNull()?.takeIf { it in wanted } ?: return@mapNotNull null
            val text = (v as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            id to text
        }.toMap()
    }

    /** COLLECT_JS 的结果（evaluateJavascript 会再包一层引号）解成文字列表。 */
    fun segments(raw: String): List<String> = runCatching {
        val inner = Json.parseToJsonElement(raw).let { (it as? JsonPrimitive)?.content ?: raw }
        (Json.parseToJsonElement(inner) as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }
    }.getOrDefault(emptyList())
}
