package com.jack.ai.api

import kotlinx.serialization.Serializable

/** Kavi 浏览器的小规则（纯逻辑）：最近访问、书签、浏览器标识、选文件的类型。 */
object BrowserPages {
    /** 一个网页：最近访问和书签都用它。folder：书签从别的浏览器导进来时所在的文件夹。 */
    @Serializable
    data class Visit(val url: String, val title: String = "", val at: Long = 0, val folder: String = "")

    /** 存成离线的整页网页（MHTML，连图片带排版）：file 是 App 里 offline_pages 下的文件名。 */
    @Serializable
    data class OfflinePage(val url: String, val title: String = "", val at: Long = 0, val file: String = "", val size: Long = 0)

    @Serializable
    data class Later(val url: String, val title: String = "", val at: Long = 0, val read: Boolean = false)

    fun addLater(list: List<Later>, url: String, title: String, now: Long): List<Later> =
        if (!isWeb(url) || list.any { it.url == url }) list
        else (listOf(Later(url, title.trim(), now)) + list).take(200)

    fun removeLater(list: List<Later>, url: String) = list.filterNot { it.url == url }
    fun readLater(list: List<Later>, url: String) = list.map { if (it.url == url) it.copy(read = true) else it }

    const val RECENT_MAX = 30

    /** 记一次访问：同一个网址只留最新的一条，排最前；只记 http/https。 */
    fun visit(list: List<Visit>, url: String, title: String, now: Long, max: Int = RECENT_MAX): List<Visit> {
        if (!isWeb(url)) return list
        return (listOf(Visit(url, title.trim(), now)) + list.filterNot { it.url == url }).take(max)
    }

    /** 网页标题晚到时补上（只改这个网址的，不改顺序）。 */
    fun retitle(list: List<Visit>, url: String, title: String): List<Visit> =
        if (title.isBlank() || list.none { it.url == url && it.title != title.trim() }) list
        else list.map { if (it.url == url) it.copy(title = title.trim()) else it }

    /** 书签：有就去掉，没有就加在最前。 */
    fun toggle(marks: List<Visit>, url: String, title: String, now: Long): List<Visit> =
        if (marks.any { it.url == url }) marks.filterNot { it.url == url }
        else if (!isWeb(url)) marks
        else listOf(Visit(url, title.trim(), now)) + marks

    fun isWeb(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

    /** 列表里显示的名字：有标题用标题，没有用网站名。 */
    fun label(v: Visit): String = v.title.ifBlank { host(v.url) }

    fun host(url: String): String = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url

    /** 网址去掉 http(s):// 和 www.，地址栏联想按这个比。 */
    private fun bare(url: String) = url.substringAfter("://").removePrefix("www.").lowercase()

    /**
     * 地址栏联想：从书签、最近访问、浏览记录里挑和输入对得上的（每个词都要在标题或网址里出现）。
     * 网址开头就对上的排最前，书签其次，再按列表原来的先后（越近的越靠前）。同一个网址只出一次。
     */
    fun suggest(query: String, marks: List<Visit>, pages: List<Visit>, max: Int = 6): List<Visit> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val first = words.first()
        val seen = HashSet<String>()
        return (marks.map { it to true } + pages.map { it to false })
            .filter { (v, _) -> isWeb(v.url) && seen.add(v.url) }
            .filter { (v, _) -> val b = bare(v.url); val t = v.title.lowercase(); words.all { b.contains(it) || t.contains(it) } }
            .withIndex()
            .sortedWith(compareBy({ (_, p) -> if (bare(p.first.url).startsWith(first)) 0 else 1 }, { (_, p) -> if (p.second) 0 else 1 }, { it.index }))
            .take(max)
            .map { it.value.first }
    }

    // ---- 网页要用相机、麦克风、位置 ----

    const val CAMERA = "camera"
    const val MIC = "mic"
    const val LOCATION = "location"

    /** 网页组件的权限名 → 我们的叫法（相机、麦克风）；别的（MIDI 之类）不认。 */
    fun mediaKinds(resources: Array<String>): Set<String> = resources.mapNotNull {
        when (it) {
            "android.webkit.resource.VIDEO_CAPTURE" -> CAMERA
            "android.webkit.resource.AUDIO_CAPTURE" -> MIC
            else -> null
        }
    }.toSet()

    fun kindsText(kinds: Set<String>): String =
        listOf(CAMERA to "相机", MIC to "麦克风", LOCATION to "位置").filter { it.first in kinds }.joinToString("和") { it.second }

    /** 记住的网站权限存成「网站|权限」。 */
    fun permKey(site: String, kind: String) = "$site|$kind"

    // ---- 关掉 App 再打开时恢复标签页 ----

    /** 存下来的一个标签页（隐身的不存）。 */
    @Serializable
    data class SavedTab(val url: String, val title: String = "", val account: Int = 0)

    @Serializable
    data class SavedTabs(val tabs: List<SavedTab> = emptyList(), val current: Int = 0)

    /** 要存的标签页：只要网页的、不要隐身的；current 跟着换算（当前页是隐身或空白时取它前面最近的一个）。 */
    fun tabsToSave(all: List<Pair<SavedTab, Boolean>>, current: Int, max: Int): SavedTabs {
        // Pair：(标签页, 是不是隐身)
        val kept = all.withIndex().filter { (_, t) -> !t.second && isWeb(t.first.url) }.take(max)
        val at = kept.indexOfLast { it.index <= current }.coerceAtLeast(0)
        return SavedTabs(kept.map { it.value.first }, if (kept.isEmpty()) 0 else at)
    }

    /**
     * 去掉网页组件自带的「App 内嵌」标记（`; wv` 和 `Version/4.0`），让网站当成普通手机 Chrome。
     * 带着这个标记，有的网站会给简化版页面，或者不让登录。
     */
    fun cleanUserAgent(ua: String): String =
        ua.replace("; wv)", ")").replace(Regex("""Version/\d+(\.\d+)* """), "")

    /** 电脑版用的浏览器标识：Windows 上的 Chrome，版本号跟手机上的网页组件一致。 */
    fun desktopUserAgent(mobileUa: String): String {
        val v = Regex("""Chrome/([\d.]+)""").find(mobileUa)?.groupValues?.get(1) ?: "130.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Safari/537.36"
    }

    /** 电脑版页面按多宽排（不放大时；和网页里假装的屏幕尺寸配套）。 */
    const val DESKTOP_WIDTH = 1200

    /** 网页字号能选的比例（百分比，所有网页通用）。 */
    val TEXT_ZOOMS = listOf(85, 100, 115, 130, 150)

    /**
     * 后台继续放：切出浏览器时网页里正有声音在放，就让网页以为自己还在前台（很多播放器一切后台就自己暂停）。
     * 回到前台那次切换也吞掉，网页一直以为没离开过。没在放声音时照常。
     */
    const val KEEP_PLAYING_JS = """(function(){if(window.__kaviBg)return;window.__kaviBg=1;var keep=false;
function playing(){try{return [].some.call(document.querySelectorAll('video,audio'),function(m){return !m.paused&&!m.ended&&!m.muted&&m.volume>0;});}catch(e){return false;}}
var P=Document.prototype,dh=Object.getOwnPropertyDescriptor(P,'hidden'),dv=Object.getOwnPropertyDescriptor(P,'visibilityState');
if(!dh||!dv)return;
try{Object.defineProperty(P,'hidden',{get:function(){return keep?false:dh.get.call(this);},configurable:true});
Object.defineProperty(P,'visibilityState',{get:function(){return keep?'visible':dv.get.call(this);},configurable:true});}catch(e){return;}
function on(e){if(dh.get.call(document)){if(!keep&&playing())keep=true;if(keep)e.stopImmediatePropagation();}else if(keep){keep=false;e.stopImmediatePropagation();}}
window.addEventListener('visibilitychange',on,true);window.addEventListener('webkitvisibilitychange',on,true);})();"""

    const val MEDIA_PREFIX = "KAVI_MEDIA:"
    /** 每个框架单独报声音状态，动态加入的播放器也能收到事件。 */
    const val MEDIA_JS = """(function(){if(window.__kaviMedia)return;window.__kaviMedia=1;
var id=Math.random().toString(36).slice(2),last=false;
function report(){var on=false;[].forEach.call(document.querySelectorAll('video,audio'),function(m){if(!m.paused&&!m.ended&&!m.muted&&m.volume>0){on=true;m.__kaviResume=true;}});
if(on||on!==last)console.log('KAVI_MEDIA:'+JSON.stringify({id:id,on:on}));last=on;}
['play','pause','ended','volumechange','emptied'].forEach(function(e){document.addEventListener(e,report,true);});
var R=1,H=0,lp=null,badge=null,eat=0;
function rate(m){var r=H||R;if(r===1&&!m.__kaviRated)return;try{m.playbackRate=r;m.__kaviRated=r!==1;}catch(e){}}
function rateAll(){[].forEach.call(document.querySelectorAll('video,audio'),rate);}
['play','playing','loadedmetadata'].forEach(function(n){document.addEventListener(n,function(e){var m=e.target;if(m&&/^(VIDEO|AUDIO)$/.test(m.tagName||''))rate(m);},true);});
function vidAt(x,y){var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){var v=vs[i],b=v.getBoundingClientRect();
if(!v.paused&&!v.ended&&b.width>80&&b.height>60&&x>=b.left&&x<=b.right&&y>=b.top&&y<=b.bottom)return v;}return null;}
function unhold(){if(!lp)return;clearTimeout(lp.t);if(lp.on){H=0;rate(lp.v);eat=Date.now();if(badge&&badge.parentNode)badge.parentNode.removeChild(badge);badge=null;}lp=null;}
document.addEventListener('touchstart',function(e){unhold();if(e.touches.length!==1)return;var p=e.touches[0],v=vidAt(p.clientX,p.clientY);if(!v)return;
lp={v:v,x:p.clientX,y:p.clientY,on:false,t:setTimeout(function(){if(!lp)return;lp.on=true;H=R<2?2:3;rate(lp.v);
var fe=document.fullscreenElement||document.webkitFullscreenElement,host=fe&&fe.tagName!=='VIDEO'?fe:(fe?null:(document.body||document.documentElement));if(!host)return;
var b=lp.v.getBoundingClientRect();badge=document.createElement('div');badge.textContent='\u25B6\u25B6 '+H+'\u00D7 \u5FEB\u8FDB\u4E2D';
badge.style.cssText='position:fixed;left:50%;transform:translateX(-50%);top:'+Math.max(12,b.top+12)+'px;z-index:2147483647;background:rgba(0,0,0,.6);color:#fff;padding:6px 14px;border-radius:16px;font:14px/1.4 sans-serif;pointer-events:none';
host.appendChild(badge);},450)};},{capture:true,passive:true});
document.addEventListener('touchmove',function(e){if(lp&&!lp.on&&e.touches.length===1){var p=e.touches[0];if(Math.abs(p.clientX-lp.x)>12||Math.abs(p.clientY-lp.y)>12){clearTimeout(lp.t);lp=null;}}},{capture:true,passive:true});
document.addEventListener('touchend',unhold,{capture:true,passive:true});document.addEventListener('touchcancel',unhold,{capture:true,passive:true});
document.addEventListener('click',function(e){if(eat&&Date.now()-eat<500){eat=0;e.preventDefault();e.stopPropagation();}},true);
document.addEventListener('contextmenu',function(e){if(lp&&lp.on)e.preventDefault();},true);
function cmd(op){if(op.indexOf('rate:')===0){R=parseFloat(op.slice(5))||1;rateAll();}
else document.querySelectorAll('video,audio').forEach(function(m){if(op==='play'){if(m.__kaviResume){var p=m.play();if(p)p.catch(function(){});}}
else {if(!m.paused&&!m.ended)m.__kaviResume=true;if(op==='stop')m.__kaviResume=false;m.pause();}});
for(var i=0;i<frames.length;i++)frames[i].postMessage({kaviMedia:op},'*');report();}
window.__kaviMediaCommand=cmd;window.addEventListener('message',function(e){var d=e.data;if(!d)return;
if(e.source===parent&&d.kaviMedia)cmd(d.kaviMedia);else if(d.kaviRateAsk&&R!==1&&e.source)e.source.postMessage({kaviMedia:'rate:'+R},'*');});
if(window!==top)try{parent.postMessage({kaviRateAsk:1},'*');}catch(e){}
setInterval(report,1000);})();"""

    /** 视频倍速能选的档位。 */
    val VIDEO_RATES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

    /** 倍速写成「1.5×」「1×」。 */
    fun rateText(r: Float): String = (if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString().trimEnd('0').trimEnd('.')) + "×"

    /** 让网页（含各层嵌入框）里的视频、音频按这个倍速放。 */
    fun rateJs(r: Float): String = "window.__kaviMediaCommand&&window.__kaviMediaCommand('rate:$r');"

    /** 暂停网页里所有在放的声音和视频（关掉浏览器、关掉画中画时）。 */
    const val PAUSE_ALL_JS = "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){m.pause();});}catch(e){}})();"

    /** 电脑版能选的放大比例（百分比）。 */
    val DESKTOP_ZOOMS = listOf(100, 115, 130, 150)

    /**
     * 电脑版放大：和电脑 Chrome 的页面缩放一样，放大多少，页面就按窄多少排，字、图、按钮一起变大。
     * 最窄不低于 760，免得网站换成手机排版。
     */
    fun desktopWidth(zoom: Int): Int = (DESKTOP_WIDTH * 100 / zoom.coerceIn(100, 200)).coerceAtLeast(760)

    /**
     * 电脑版：网页一开始加载就装上（每个嵌入框也装），只在浏览器标识是 Windows 时起作用（标识按网站在打开前换好）。
     * 网站除了标识还看这些来认手机：系统平台、能不能多点触控、屏幕多宽、是手指还是鼠标、新式设备标识（userAgentData），
     * 都改成电脑的；页面宽度在网页自己的 viewport 一出来就改，不等加载完，免得先排成手机版。
     * 触屏属性（ontouchstart）网页组件在文档建好后会再装回去，所以在每次页面变动时（网页脚本跑之前）再删。
     */
    fun desktopJs(width: Int = DESKTOP_WIDTH): String = """(function(){if(window.__kaviDesk||!/Windows NT/.test(navigator.userAgent))return;window.__kaviDesk=1;
function nt(){['ontouchstart','ontouchmove','ontouchend','ontouchcancel'].forEach(function(k){try{delete window[k];delete Document.prototype[k];delete HTMLElement.prototype[k];}catch(e){}});}
function d(o,k,v){try{Object.defineProperty(o,k,{get:function(){return v;},configurable:true});}catch(e){}}
try{var N=Navigator.prototype;d(N,'platform','Win32');d(N,'maxTouchPoints',0);
var S=Screen.prototype;d(S,'width',${width + 80});d(S,'height',${(width + 80) * 5 / 8});d(S,'availWidth',${width + 80});d(S,'availHeight',${(width + 80) * 5 / 8 - 40});
nt();
try{delete window.TouchEvent;}catch(e){}
var ud=navigator.userAgentData;if(ud&&ud.mobile){var v=(navigator.userAgent.match(/Chrome\/([\d.]+)/)||[0,'130.0.0.0'])[1],m=v.split('.')[0];
var brands=[{brand:'Google Chrome',version:m},{brand:'Chromium',version:m},{brand:'Not.A/Brand',version:'99'}];
d(N,'userAgentData',{brands:brands,mobile:false,platform:'Windows',toJSON:function(){return{brands:brands,mobile:false,platform:'Windows'};},
getHighEntropyValues:function(){return Promise.resolve({brands:brands,mobile:false,platform:'Windows',platformVersion:'10.0.0',architecture:'x86',bitness:'64',model:'',uaFullVersion:v,
fullVersionList:brands.map(function(b){return{brand:b.brand,version:b.brand==='Not.A/Brand'?'99.0.0.0':v};})});}});}
var mm=window.matchMedia;window.matchMedia=function(q){q=String(q).replace(/(any-)?pointer\s*:\s*(coarse|fine)/g,function(_,a,x){return (a||'')+'pointer: '+(x==='coarse'?'fine':'coarse');})
.replace(/(any-)?hover\s*:\s*(none|hover)/g,function(_,a,x){return (a||'')+'hover: '+(x==='none'?'hover':'none');});return mm.call(window,q);};}catch(e){}
var W='width=$width';function fix(m){if(m&&m.name==='viewport'&&m.content!==W)m.content=W;}
try{new MutationObserver(function(rs){nt();rs.forEach(function(r){r.addedNodes.forEach(function(n){if(n.tagName==='META')fix(n);});if(r.type==='attributes')fix(r.target);});})
.observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['content','name']});}catch(e){}
document.addEventListener('DOMContentLoaded',function(){var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}fix(m);});})();"""

    /** 「电脑版」按网站记：网站名去掉 www.。 */
    fun site(url: String): String = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.").orEmpty()

    /** 这个网址在不在电脑版名单里（名单里的网站和它的子域名都算）。 */
    fun isDesktop(sites: Set<String>, url: String): Boolean {
        val h = site(url)
        return h.isNotEmpty() && sites.any { h == it || h.endsWith(".$it") }
    }

    /** 「问 Kavi」带过去的文件名：网页标题，去掉文件名里不能用的字符。 */
    fun fileName(title: String, url: String): String =
        title.ifBlank { host(url) }.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().take(40).ifBlank { "网页" } + ".txt"

    /**
     * 网页 <input accept> 要的类型 → 选文件时的 (type, 额外的 MIME 列表)。
     * 「.pdf」这种扩展名换成 MIME；只有一种就直接用，几种就用 * / * 加列表，看不懂的不限制。
     */
    fun pickTypes(accept: Array<String>?): Pair<String, List<String>> {
        val types = accept.orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .map { t -> if (t.startsWith(".")) EXT_MIME[t.drop(1)] ?: "" else t }
        if (types.isEmpty() || types.any { it.isEmpty() || '/' !in it }) return "*/*" to emptyList()
        val distinct = types.distinct()
        return if (distinct.size == 1) distinct[0] to emptyList() else "*/*" to distinct
    }

    private val EXT_MIME = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "heic" to "image/heic", "pdf" to "application/pdf", "txt" to "text/plain", "csv" to "text/csv",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "zip" to "application/zip", "mp4" to "video/mp4", "mp3" to "audio/mpeg", "json" to "application/json",
    )
}
