package com.jack.ai.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 屏蔽网页上的某一块：用户点一下选中，存成这个网站的 CSS 选择器，以后打开就藏起来。
 */
object BrowserBlocks {
    const val PREFIX = "[jack-pick] "
    /** 每个网站最多屏蔽几处。 */
    const val PER_SITE = 30

    /** 选中的那一块：选择器、页面上一共匹配几处、给人看的描述。 */
    data class Picked(val selector: String, val count: Int, val label: String)

    /** 选块模式：点哪里就框住哪里（不会真的点下去），把选择器用 console 传回来。 */
    val PICK_JS = """
(function(){if(window.__jackPickStop)window.__jackPickStop();
var cur=null,box=document.createElement('div');
box.style.cssText='position:fixed;z-index:2147483647;pointer-events:none;border:2px solid #e53935;background:rgba(229,57,53,.18);border-radius:4px;display:none';
document.documentElement.appendChild(box);
function ok(s){return /^[A-Za-z_][\w-]{1,40}$/.test(s)&&!/\d{3,}/.test(s);}
function sel(el){
 if(el.id&&ok(el.id)&&document.querySelectorAll('#'+CSS.escape(el.id)).length===1)return '#'+CSS.escape(el.id);
 var parts=[],e=el;
 for(var d=0;e&&e.nodeType===1&&e!==document.body&&e!==document.documentElement&&d<6;d++){
  if(e.id&&ok(e.id)){parts.unshift('#'+CSS.escape(e.id));break;}
  var p=e.tagName.toLowerCase();
  var cls=[].filter.call(e.classList,function(c){return ok(c)&&!/^(active|show|open|hover|on|cur|current|selected|visible|fixed)$/i.test(c);}).slice(0,2);
  if(cls.length)p+='.'+cls.map(function(c){return CSS.escape(c);}).join('.');
  var par=e.parentElement;
  if(par&&!cls.length){var same=[].filter.call(par.children,function(x){return x.tagName===e.tagName;});if(same.length>1)p+=':nth-of-type('+(same.indexOf(e)+1)+')';}
  parts.unshift(p);
  try{if(document.querySelectorAll(parts.join(' > ')).length===1)break;}catch(_){}
  e=par;
 }
 return parts.join(' > ');
}
function desc(el){var r=el.getBoundingClientRect(),t=(el.innerText||el.getAttribute('aria-label')||el.getAttribute('alt')||'').trim().replace(/\s+/g,' ').slice(0,24);
 return el.tagName.toLowerCase()+(t?'「'+t+'」':'')+' '+Math.round(r.width)+'×'+Math.round(r.height);}
function place(){if(!cur)return;var r=cur.getBoundingClientRect();box.style.display='block';box.style.left=r.left+'px';box.style.top=r.top+'px';box.style.width=r.width+'px';box.style.height=r.height+'px';}
function show(el){cur=el;place();var s=sel(el),n=0;try{n=document.querySelectorAll(s).length;}catch(_){}
 console.log('$PREFIX'+JSON.stringify({selector:s,count:n,label:desc(el)}));}
function onClick(e){e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();show(e.target);}
window.addEventListener('click',onClick,true);window.addEventListener('scroll',place,true);
window.__jackPickUp=function(){if(cur&&cur.parentElement&&cur.parentElement!==document.body&&cur.parentElement!==document.documentElement)show(cur.parentElement);};
window.__jackPickStop=function(){window.removeEventListener('click',onClick,true);window.removeEventListener('scroll',place,true);box.remove();window.__jackPickStop=null;window.__jackPickUp=null;};
})();
""".trimIndent()

    /** 选块模式里 console 传回来的一行 → Picked；不是就返回 null。 */
    fun parsePick(line: String): Picked? {
        if (!line.startsWith(PREFIX)) return null
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line.removePrefix(PREFIX)) as JsonObject }.getOrNull() ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
        val sel = s("selector").trim()
        if (sel.isEmpty()) return null
        return Picked(sel, s("count").toIntOrNull() ?: 0, s("label"))
    }

    /** 网站 → 选择器：加一条（同样的不重复，每个网站最多 PER_SITE 条，新的挤掉旧的）。 */
    fun add(rules: Map<String, List<String>>, site: String, selector: String): Map<String, List<String>> {
        val now = rules[site].orEmpty()
        if (selector in now || site.isEmpty()) return rules
        return rules + (site to (now + selector).takeLast(PER_SITE))
    }

    fun remove(rules: Map<String, List<String>>, site: String, selector: String): Map<String, List<String>> {
        val left = rules[site].orEmpty() - selector
        return if (left.isEmpty()) rules - site else rules + (site to left)
    }

    /** 这个网站（和它的上级网站）要藏的选择器。 */
    fun forSite(rules: Map<String, List<String>>, site: String): List<String> =
        rules.filterKeys { site == it || site.endsWith(".$it") }.values.flatten().distinct()

    /** 放进网页的样式（重复执行会换成最新的）。没有规则就清掉。 */
    fun injectJs(selectors: List<String>): String {
        val css = selectors.joinToString("\n") { "$it{display:none!important}" }
        return "(function(){var s=document.getElementById('jack-block');if(!s){s=document.createElement('style');s.id='jack-block';" +
            "(document.head||document.documentElement).appendChild(s);}s.textContent=" + JsonPrimitive(css) + ";})();"
    }
}

/**
 * 演示教学：用户在网站上操作一遍，记下点了什么、填了什么、到了哪一页，整理成文字步骤交给 Kavi 存成浏览器流程。
 * 密码只记「这里要填密码」，不记内容。
 */
object BrowserTeach {
    const val PREFIX = "[jack-rec] "
    const val MAX_STEPS = 80

    val RECORD_JS = """
(function(){if(window.__jackRec)return;window.__jackRec=1;
function d(o){try{console.log('$PREFIX'+JSON.stringify(o))}catch(e){}}
function clean(t){return (t||'').replace(/\s+/g,' ').trim().slice(0,40);}
function label(el){if(!el||!el.getAttribute)return '';
 var l=el.getAttribute('aria-label')||el.getAttribute('placeholder')||el.getAttribute('title')||el.getAttribute('alt');
 if(!l&&el.id){var lb=document.querySelector('label[for="'+el.id+'"]');if(lb)l=lb.innerText;}
 if(!l&&el.closest){var p=el.closest('label');if(p)l=p.innerText;}
 if(/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName)){if(!l)l=el.getAttribute('name');if(!l&&/^(submit|button)$/i.test(el.type))l=el.value;return clean(l);}
 if(!l)l=el.innerText;return clean(l);}
function kind(el){var t=el.tagName,r=el.getAttribute&&el.getAttribute('role'),ty=(el.type||'').toLowerCase();
 if(t==='A'||r==='link')return '链接';if(t==='SELECT')return '下拉框';if(t==='TEXTAREA')return '输入框';
 if(t==='INPUT'){if(ty==='checkbox')return '勾选框';if(ty==='radio')return '单选';if(ty==='submit'||ty==='button')return '按钮';return '输入框';}
 if(t==='BUTTON'||r==='button')return '按钮';if(r==='tab')return '标签';if(r==='menuitem')return '菜单项';return '可点的';}
var TEXT='input:not([type]),input[type=text],input[type=search],input[type=email],input[type=tel],input[type=number],input[type=url],input[type=password],textarea';
document.addEventListener('click',function(e){var t=e.target&&e.target.closest?(e.target.closest('a,button,[role=button],[role=link],[role=tab],[role=menuitem],[role=option],input,select,label,[onclick]')||e.target):e.target;
 if(!t||(t.matches&&(t.matches(TEXT)||t.tagName==='SELECT')))return;d({a:'click',k:kind(t),l:label(t)});},true);
document.addEventListener('change',function(e){var t=e.target;if(!t||!t.tagName)return;
 if(t.tagName==='SELECT'){var o=t.options[t.selectedIndex];d({a:'select',l:label(t),v:o?clean(o.text):''});}
 else if((t.type||'').toLowerCase()==='password')d({a:'password',l:label(t)});
 else if((t.type||'').toLowerCase()==='file')d({a:'upload',l:label(t)});
 else if(t.matches&&t.matches(TEXT))d({a:'type',l:label(t),v:(t.value||'').slice(0,80)});},true);
document.addEventListener('keydown',function(e){var t=e.target;if(e.key==='Enter'&&t&&t.matches&&t.matches(TEXT)&&(t.type||'').toLowerCase()!=='password'){d({a:'type',l:label(t),v:(t.value||'').slice(0,80)});d({a:'enter'});}},true);
})();
""".trimIndent()

    /** console 传回来的一行 → 一句步骤；不是记录就返回 null。 */
    fun stepOf(line: String): String? {
        if (!line.startsWith(PREFIX)) return null
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line.removePrefix(PREFIX)) as JsonObject }.getOrNull() ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
        val l = s("l").let { if (it.isBlank()) "" else "「$it」" }
        return when (s("a")) {
            "click" -> "点${s("k")}$l"
            "type" -> if (s("v").isBlank()) null else "在输入框$l 输入「${s("v")}」"
            "select" -> "在下拉框$l 选「${s("v")}」"
            "password" -> "在密码框$l 填密码（请用户自己填）"
            "upload" -> "在$l 选文件上传"
            "enter" -> "按回车"
            else -> null
        }
    }

    /** 加一步：和上一步一样的不重复记；同一次输入（回车一次、失焦一次）隔着「按回车」也只记一次。 */
    fun append(steps: List<String>, step: String): List<String> {
        if (steps.lastOrNull() == step || steps.size >= MAX_STEPS) return steps
        if (step.startsWith("在输入框") && step in steps.takeLast(2)) return steps
        return steps + step
    }

    /** 交给 Kavi 的话：让它把演示整理成流程存下来。 */
    fun request(site: String, steps: List<String>): String =
        "我刚在 $site 上演示了一遍操作，帮我存成浏览器流程（用 browser 的 save_flow）：名字和「什么时候用」你根据步骤起；" +
            "每次会变的内容（搜的词、数量、日期这类）写成 {关键词} 这样的空；密码、验证码、付款写成「请用户自己……」；" +
            "看得出是多余的步骤（点错、来回切）就去掉。存好告诉我流程名和步骤。\n\n我的操作：\n" +
            steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
}
