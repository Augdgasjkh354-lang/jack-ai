package com.jack.ai.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 网页的后台请求：每一页开头装一个小钩子，数着还没回来的 fetch / XHR，记下最近失败的请求。
 * - 动作做完等请求都回来再读页面（页面看着加载完了，数据可能还没刷新）；只等 [FRESH_MS] 内发出的，
 *   挂着不回的长连接、轮询不算，免得每一步都白等；
 * - 点完、提交完查一下这期间同一个网站的请求有没有失败（页面变了不等于后台成功了）。
 * 只记方法、网址和状态码，不记内容。
 */
object BrowserNetWatch {
    val JS = """
(function(){if(window.__kaviNet)return;var N=window.__kaviNet={seq:0,fails:[],p:{},id:0};
N.fresh=function(ms){var t=Date.now(),c=0;for(var k in N.p)if(t-N.p[k]<ms)c++;return c};
function start(){var id=++N.id;N.p[id]=Date.now();return id}function stop(id){delete N.p[id]}
function fail(m,u,s){N.seq++;N.fails.push({i:N.seq,m:String(m||'GET').toUpperCase(),u:String(u||'').slice(0,200),s:s});if(N.fails.length>20)N.fails.shift();}
function same(f,g){try{f.toString=function(){return Function.prototype.toString.call(g)}}catch(e){}return f}
var F=window.fetch;if(F){window.fetch=same(function(i,o){var u=(i&&i.url)||i,m=(o&&o.method)||(i&&i.method)||'GET',id=start();var p;try{p=F.apply(this,arguments)}catch(e){stop(id);throw e}
return p.then(function(r){stop(id);if(!r.ok&&r.type!=='opaque')fail(m,u,r.status);return r},function(e){stop(id);if(!(e&&e.name==='AbortError'))fail(m,u,0);throw e})},F)}
var X=window.XMLHttpRequest&&XMLHttpRequest.prototype;if(X){var o=X.open,s=X.send;X.open=same(function(m,u){this.__kn=[m,u];return o.apply(this,arguments)},o);
X.send=same(function(){var x=this,k=x.__kn||['GET',''],done=false,id=start();function end(){if(done)return;done=true;stop(id);if(x.status>=400||(x.status===0&&x.__ke))fail(k[0],k[1],x.status)}
x.addEventListener('error',function(){x.__ke=1});x.addEventListener('loadend',end);try{return s.apply(this,arguments)}catch(e){end();throw e}},s)}
})();
""".trimIndent()

    /** 发出多久以内、还没回来的请求才等（更早的多半是长连接或轮询，不会回来）。 */
    const val FRESH_MS = 8_000
    const val STATE_JS = "JSON.stringify(window.__kaviNet?{inflight:__kaviNet.fresh($FRESH_MS),seq:__kaviNet.seq}:null)"
    fun failsSinceJs(seq: Int) = "JSON.stringify(window.__kaviNet?__kaviNet.fails.filter(function(f){return f.i>$seq}):[])"

    data class Fail(val method: String, val url: String, val status: Int)

    private val json = Json { ignoreUnknownKeys = true }

    /** 网页给回来的字符串（evaluateJavascript 会多包一层引号）解成 JSON。 */
    private fun parse(raw: String) = runCatching {
        val inner = json.parseToJsonElement(raw)
        if (inner is JsonPrimitive && inner.isString) json.parseToJsonElement(inner.content) else inner
    }.getOrNull()

    /** （还没回来的请求数，失败序号）；钩子没装上返回 null。 */
    fun state(raw: String): Pair<Int, Int>? = (parse(raw) as? JsonObject)?.let { o ->
        val inflight = (o["inflight"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        inflight.coerceAtLeast(0) to ((o["seq"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0)
    }

    fun fails(raw: String): List<Fail> = (parse(raw) as? JsonArray).orEmpty().mapNotNull { e ->
        val o = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
        Fail((o["m"] as? JsonPrimitive)?.content.orEmpty(), (o["u"] as? JsonPrimitive)?.content.orEmpty(), (o["s"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0)
    }

    /** 网站的主域名（m.taobao.com、h5api.m.taobao.com 都是 taobao.com；.com.cn 这类取三段）。 */
    fun base(host: String): String {
        val parts = host.lowercase().removePrefix("www.").split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val n = if (parts[parts.size - 2] in setOf("com", "net", "org", "gov", "edu", "co", "ac") && parts.last().length == 2) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    /**
     * 值得告诉模型的失败：同一个网站的；GET 只算 4xx/5xx（取消、广告被拦这类不算），提交类（POST 等）连不上也算。
     */
    fun relevant(fails: List<Fail>, pageHost: String): List<Fail> {
        val site = base(pageHost)
        return fails.filter { f ->
            val host = if (f.url.startsWith("http", ignoreCase = true)) runCatching { java.net.URI(f.url).host.orEmpty() }.getOrDefault("") else pageHost
            base(host) == site && (f.status >= 400 || (f.status == 0 && f.method != "GET"))
        }.distinctBy { it.method + it.url + it.status }
    }

    fun describe(f: Fail): String = "${f.method} ${f.url.substringBefore('?').take(100)} → " + if (f.status == 0) "连不上" else "${f.status}"
}
