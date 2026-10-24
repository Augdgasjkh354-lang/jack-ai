package com.jack.ai.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网页诊断：网页点了没反应、打不开时，把发生了什么记下来（脚本出错、请求失败、被拦下的、弹框开没开），
 * 用户复制给开发者看。平时只记出错的（很省）；「开始记录」后才往网页里加钩子，记下点击、弹框和请求。
 */
object BrowserDiag {
    /** 每个标签页最多留几行，旧的挤掉。 */
    const val MAX_LINES = 300

    /** 一个标签页的记录（拦截请求在后台线程，加锁）。 */
    class Log {
        private val lines = ArrayDeque<String>()
        /** 一共记过几行（被挤掉的也算），用来取「某个时刻以后」记的。 */
        private var total = 0
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun add(line: String, now: Long = System.currentTimeMillis()) = synchronized(this) {
            val l = fmt.format(Date(now)) + " " + line.replace('\n', ' ').take(400)
            // 同一句连着出现（比如一页里几十个被拦的请求）只记一次
            if (lines.lastOrNull()?.substring(9) == l.substring(9)) return@synchronized
            lines.addLast(l)
            total++
            while (lines.size > MAX_LINES) lines.removeFirst()
        }

        fun clear() = synchronized(this) { lines.clear() }
        fun mark(): Int = synchronized(this) { total }
        /** mark 之后记的那些行。 */
        fun since(mark: Int): List<String> = synchronized(this) { lines.toList().takeLast((total - mark).coerceIn(0, lines.size)) }
        fun lines(): List<String> = synchronized(this) { lines.toList() }
    }

    /** 记录时放进网页的钩子：点击、弹框（dialog）开关、fetch 失败、资源没加载出来；用 console.log 带前缀传回来。 */
    const val PREFIX = "[jack-diag] "
    val HOOK_JS = """
(function(){if(window.__jackDiag)return;window.__jackDiag=1;
function d(m){try{console.log('$PREFIX'+m)}catch(e){}}
function desc(el){if(!el||!el.tagName)return '?';var t=(el.innerText||el.value||(el.getAttribute&&el.getAttribute('aria-label'))||'').trim().replace(/\s+/g,' ').slice(0,30);return el.tagName.toLowerCase()+(t?'「'+t+'」':'');}
document.addEventListener('click',function(e){var t=e.target&&e.target.closest?(e.target.closest('button,a,[role=button],input,label')||e.target):e.target;d('点击 '+desc(t)+(e.isTrusted?'':'（脚本点的）')+(e.defaultPrevented?'（被网页拦下）':''));},true);
var D=window.HTMLDialogElement&&HTMLDialogElement.prototype;
if(D)['showModal','show','close'].forEach(function(m){var f=D[m];if(!f)return;D[m]=function(){d('弹框 '+m+' '+desc(this));try{return f.apply(this,arguments)}catch(err){d('弹框 '+m+' 出错：'+err);throw err}}});
window.addEventListener('error',function(e){var t=e.target;if(t&&t!==window&&t.tagName)d('没加载出来 '+t.tagName.toLowerCase()+' '+String(t.src||(t.href&&t.href.baseVal!==undefined?t.href.baseVal:t.href)||'').slice(0,160));},true);
window.addEventListener('unhandledrejection',function(e){d('Promise 出错：'+String(e.reason&&(e.reason.stack||e.reason.message)||e.reason).slice(0,200));});
var F=window.fetch;if(F)window.fetch=function(u){var url=String(u&&u.url||u).slice(0,160);return F.apply(this,arguments).then(function(r){if(!r.ok)d('请求 '+r.status+' '+url);return r},function(err){d('请求失败 '+url+' '+err);throw err})};
d('钩子已装好 '+location.href.slice(0,120));
})();
""".trimIndent()

    /** 出报告时读一下网页现在的样子：大小、弹框在哪、屏幕下方被什么挡着。 */
    val SNAPSHOT_JS = """
(function(){var o=[];try{
o.push('网址 '+location.href.slice(0,200));o.push('状态 '+document.readyState+'，标题「'+document.title.slice(0,60)+'」');
o.push('窗口 '+innerWidth+'x'+innerHeight+' 缩放 '+devicePixelRatio+(window.visualViewport?' 可见区 '+Math.round(visualViewport.width)+'x'+Math.round(visualViewport.height):''));
o.push('UA '+navigator.userAgent);
o.push('Cookie '+(navigator.cookieEnabled?'开':'关')+'，localStorage '+(function(){try{localStorage.setItem('__j','1');localStorage.removeItem('__j');return '能用'}catch(e){return '不能用 '+e}})());
var ds=document.querySelectorAll('dialog,[role=dialog]');o.push('弹框 '+ds.length+' 个');
for(var i=0;i<ds.length&&i<6;i++){var x=ds[i],r=x.getBoundingClientRect(),cs=getComputedStyle(x);
o.push('  '+x.tagName.toLowerCase()+(x.open?' 开着':(x.tagName==='DIALOG'?' 关着':''))+' 位置 '+Math.round(r.left)+','+Math.round(r.top)+' 大小 '+Math.round(r.width)+'x'+Math.round(r.height)+' display='+cs.display+' visibility='+cs.visibility+' opacity='+cs.opacity+' transform='+cs.transform+' 「'+(x.innerText||'').trim().replace(/\s+/g,' ').slice(0,30)+'」');}
[0.2,0.5,0.85].forEach(function(f){var e=document.elementFromPoint(innerWidth/2,innerHeight*f);o.push('屏幕 '+Math.round(f*100)+'% 处是 '+(e?e.tagName.toLowerCase()+'.'+String(e.className).slice(0,50)+' 「'+(e.innerText||'').trim().replace(/\s+/g,' ').slice(0,20)+'」':'（空）'));});
}catch(e){o.push('读网页出错 '+e)}return o.join('\n');})()
""".trimIndent()

    private val PROBLEM = Regex("出错|失败|拦下|没加载出来|没打开|进程没了|(请求|网页) [45]\\d\\d ")

    /** 记录里像问题的那几行（脚本出错、请求失败、被拦），去掉时间，最多 n 行。 */
    fun problems(lines: List<String>, n: Int = 6): List<String> =
        lines.map { it.substringAfter(' ') }.filter { PROBLEM.containsMatchIn(it) }.distinct().takeLast(n)

    /** 拼成一份给开发者看的报告。 */
    fun report(header: List<String>, page: String, log: List<String>, recording: Boolean): String = buildString {
        append("【Jack 浏览器诊断】\n")
        header.forEach { append(it).append('\n') }
        append("\n【网页现在】\n").append(page.ifBlank { "（读不到）" }).append('\n')
        append("\n【记录】" + if (recording) "（正在记录点击、弹框、请求）\n" else "（只记了出错的；要更细就点「开始记录」，网页会刷新，再照常操作一遍）\n")
        if (log.isEmpty()) append("（没有）\n") else log.forEach { append(it).append('\n') }
    }
}
