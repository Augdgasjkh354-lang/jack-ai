// Jack 操作网页用的脚本：给能点、能填的元素编号，读当前屏的文字，按编号点击、输入、删字、选择、按键。
// 每次调用前注入（页面跳转后会丢），定义在 window.__jack 上。
(function () {
  if (window.__jack) return;

  var SELECTOR = 'a[href],button,input,textarea,select,summary,[role=button],[role=link],[role=checkbox],[role=radio],' +
    '[role=tab],[role=menuitem],[role=option],[role=switch],[role=textbox],[role=combobox],[role=searchbox],' +
    '[contenteditable=""],[contenteditable=true],[onclick],[tabindex]:not([tabindex="-1"])';
  var MAX = 120;

  function clean(t) { return (t || '').replace(/\s+/g, ' ').trim(); }

  function styleOf(el) { return (el.ownerDocument.defaultView || window).getComputedStyle(el); }

  /** 主页面和能读到的嵌入页面（同一个网站的 iframe，最多两层），带着它们在屏幕上的偏移。读不到的（别的网站的）记个数。 */
  var hiddenFrames = 0;
  function docs() {
    var out = [{ doc: document, ox: 0, oy: 0, frame: false }];
    hiddenFrames = 0;
    (function walk(d, ox, oy, depth) {
      if (depth >= 2) return;
      var fs = d.querySelectorAll('iframe,frame');
      for (var i = 0; i < fs.length; i++) {
        var f = fs[i], cd = null, r = f.getBoundingClientRect();
        if (r.width < 20 || r.height < 20) continue;
        try { cd = f.contentDocument; } catch (e) { cd = null; }
        if (!cd || !cd.body) { hiddenFrames++; continue; }
        var o = { doc: cd, ox: ox + r.left, oy: oy + r.top, frame: true };
        out.push(o);
        walk(cd, o.ox, o.oy, depth + 1);
      }
    })(document, 0, 0, 0);
    return out;
  }

  function visibleRect(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return null;
    var s = styleOf(el);
    if (s.visibility === 'hidden' || s.display === 'none' || parseFloat(s.opacity) === 0) return null;
    return r;
  }

  function label(el) {
    var t = el.getAttribute('aria-label') || '';
    if (!t && el.labels && el.labels.length) t = el.labels[0].innerText;
    if (!t && el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA' && el.tagName !== 'SELECT') t = el.innerText;
    if (!clean(t)) t = el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('alt') || '';
    if (!clean(t) && el.querySelector) { var img = el.querySelector('img[alt],svg[aria-label]'); if (img) t = img.getAttribute('alt') || img.getAttribute('aria-label'); }
    if (!clean(t) && (el.type === 'submit' || el.type === 'button')) t = el.value;
    if (!clean(t)) t = el.getAttribute('name') || '';
    return clean(t).slice(0, 80);
  }

  function kind(el) {
    var tag = el.tagName.toLowerCase(), role = el.getAttribute('role');
    if (tag === 'a') return '链接';
    if (tag === 'select') return '下拉框';
    if (tag === 'textarea' || role === 'textbox' || el.isContentEditable) return '输入框';
    if (tag === 'input') {
      return ({ checkbox: '勾选框', radio: '单选', submit: '按钮', button: '按钮', reset: '按钮', image: '按钮', file: '文件选择',
        password: '密码框', search: '搜索框', date: '日期框', 'datetime-local': '日期时间框', time: '时间框', month: '月份框',
        week: '周框', range: '滑条', color: '颜色框' })[el.type] || '输入框';
    }
    if (tag === 'button' || role === 'button') return '按钮';
    if (role === 'searchbox' || role === 'combobox') return '输入框';
    if (role) return { link: '链接', checkbox: '勾选框', radio: '单选', tab: '标签页', menuitem: '菜单项', option: '选项', switch: '开关' }[role] || '可点';
    return '可点';
  }

  function isText(el) {
    return el.tagName === 'TEXTAREA' || el.isContentEditable ||
      (el.tagName === 'INPUT' && ['text', 'search', 'email', 'url', 'tel', 'number', ''].indexOf(el.type) >= 0) ||
      ['textbox', 'searchbox', 'combobox'].indexOf(el.getAttribute('role')) >= 0;
  }

  /** 当前屏附近（上方半屏到下方一屏半）的文字。 */
  function nearbyText(limit) {
    var vh = innerHeight, out = [], len = 0, seen = new Set();
    var walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT);
    var node;
    while ((node = walker.nextNode()) && len < limit) {
      var t = clean(node.nodeValue);
      if (!t) continue;
      var p = node.parentElement;
      if (!p || seen.has(p)) continue;
      var tag = p.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') continue;
      var r = p.getBoundingClientRect();
      if (r.width < 1 || r.height < 1 || r.bottom < -vh * 0.5 || r.top > vh * 1.5) continue;
      var s = styleOf(p);
      if (s.visibility === 'hidden' || s.display === 'none') continue;
      seen.add(p);
      var block = clean(p.innerText);
      if (!block) continue;
      out.push(block.slice(0, 600));
      len += Math.min(block.length, 600);
    }
    // 父子元素可能重复：去掉被别的段落完整包含的短段
    var res = [];
    for (var i = 0; i < out.length; i++) {
      if (i > 0 && out[i - 1].indexOf(out[i]) >= 0) continue;
      res.push(out[i]);
    }
    return res.join('\n').slice(0, limit);
  }

  /** 不是标准按钮、但鼠标放上去是手指形状的元素（网页自己画的按钮、卡片）：也算能点。 */
  function pointerItems(d, vh) {
    var out = [], all = d.querySelectorAll('div,span,li,img,i,svg,label,p,td,em,strong');
    for (var i = 0; i < all.length && out.length < 60; i++) {
      var el = all[i];
      // 自己上次编过号的照样算；在别的已编号元素里面或包着已编号元素的不算（会重复）
      if ((el.parentElement && el.parentElement.closest('[data-jack]')) || el.querySelector('[data-jack]')) continue;
      var r = el.getBoundingClientRect();
      if (r.width < 8 || r.height < 8 || r.bottom < 0 || r.top > vh * 1.5) continue;
      var st = styleOf(el);
      if (st.cursor !== 'pointer') continue;
      var p = el.parentElement;
      if (p && styleOf(p).cursor === 'pointer') continue;
      out.push(el);
    }
    return out;
  }

  function describe(el, n, r, frame) {
    var item = { n: n, kind: kind(el), label: (frame ? '[嵌入] ' : '') + label(el), x: r.left, y: r.top, w: r.width, h: r.height };
    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
      if (el.type === 'password') item.value = el.value ? '（已填）' : '';
      else if (el.type === 'checkbox' || el.type === 'radio') item.checked = el.checked;
      else if (el.type !== 'submit' && el.type !== 'button' && el.type !== 'file') item.value = (el.value || '').slice(0, 100);
      if (el.type === 'range') item.value = (el.value || '') + '（' + (el.min || 0) + '–' + (el.max || 100) + '）';
      if (SPECIAL[el.type] && el.type !== 'range') item.value = (el.value || '') + (el.value ? '' : '（格式 ' + SPECIAL[el.type] + '）');
      if (!clean(label(el))) item.label = (frame ? '[嵌入] ' : '') + clean(el.placeholder);
    } else if (el.isContentEditable) {
      item.value = clean(el.innerText).slice(0, 100);
    } else if (el.tagName === 'SELECT') {
      var sel = el.options[el.selectedIndex];
      item.value = sel ? clean(sel.text) : '';
      item.options = Array.prototype.slice.call(el.options, 0, 20).map(function (o) { return clean(o.text); });
    } else if (el.getAttribute('aria-checked') != null) {
      item.checked = el.getAttribute('aria-checked') === 'true';
    }
    if (el.tagName === 'A' && el.href) item.href = el.href.slice(0, 150);
    return item;
  }

  /**
   * 编号在同一个页面里不变：上次编过号的元素还用原来的号，新出现的接着往后编（跳到新页面才从 1 重来）。
   * 这样页面没跳转时只要告诉模型哪些是新的，旧的编号照样能用。
   */
  function snapshot() {
    var ds = docs();
    ds.forEach(function (d) { d.doc.querySelectorAll('[data-jack="0"]').forEach(function (e) { e.removeAttribute('data-jack'); }); });
    var vh = innerHeight, items = [], above = 0, below = 0, n = 0;
    if (!window.__jackNext) window.__jackNext = 1;
    ds.forEach(function (d) {
      var all = Array.prototype.slice.call(d.doc.querySelectorAll(SELECTOR));
      for (var pass = 0; pass < 2; pass++) {
        for (var i = 0; i < all.length; i++) {
          var el = all[i];
          if (el.disabled || (el.tagName === 'INPUT' && el.type === 'hidden')) continue;
          var r0 = visibleRect(el);
          if (!r0) continue;
          var r = { left: r0.left + d.ox, top: r0.top + d.oy, width: r0.width, height: r0.height, bottom: r0.bottom + d.oy };
          if (r.bottom < 0) { above++; continue; }
          if (r.top > vh * 1.5) { below++; continue; }
          // 嵌套的同名元素（链接里的按钮之类）只留外层
          var outer = el.parentElement && el.parentElement.closest('[data-jack]');
          var lb = label(el);
          if (pass === 0 && outer && (label(outer) === lb || !lb)) continue;
          if (pass === 1 && !lb && !el.querySelector('img,svg')) continue;
          if (n >= MAX) { below++; continue; }
          n++;
          var id = parseInt(el.getAttribute('data-jack'), 10);
          if (!(id > 0)) { id = window.__jackNext++; el.setAttribute('data-jack', id); }
          items.push(describe(el, id, r, d.frame));
        }
        // 第二遍：标准元素之外、鼠标是手指形状的
        all = pass === 0 ? pointerItems(d.doc, vh) : [];
      }
    });
    var vv = window.visualViewport;
    return JSON.stringify({
      url: location.href, title: document.title, items: items, above: above, below: below,
      scroll: Math.round(scrollY), height: document.documentElement.scrollHeight, vh: vh,
      vx: vv ? vv.offsetLeft : 0, vy: vv ? vv.offsetTop : 0, vw: vv ? vv.width : innerWidth,
      text: nearbyText(4000), frames: hiddenFrames,
    });
  }

  function find(n) {
    var ds = docs();
    for (var i = 0; i < ds.length; i++) { var el = ds[i].doc.querySelector('[data-jack="' + n + '"]'); if (el) return el; }
    return null;
  }

  /** 元素在屏幕上的位置：在嵌入页面里的要加上嵌入框的偏移。 */
  function offsetOf(el) {
    var ox = 0, oy = 0, w = el.ownerDocument.defaultView;
    while (w && w !== window && w.frameElement) {
      var r = w.frameElement.getBoundingClientRect();
      ox += r.left; oy += r.top;
      w = w.parent;
    }
    return { x: ox, y: oy };
  }

  /** 找到的元素：已经有编号就用原来的，没有就标成 0；返回它的情况，带上编号 n。 */
  function mark(el) {
    var id = el.getAttribute('data-jack');
    if (id === null) { id = '0'; el.setAttribute('data-jack', '0'); }
    var info = JSON.parse(inspect(id));
    info.n = parseInt(id, 10);
    return JSON.stringify(info);
  }

  /** 按文字找元素：先找文字完全一样的，再找包含的；找到后往上找能点的那一层，标成编号 0。 */
  function markText(t) {
    var want = clean(t);
    if (!want) return JSON.stringify({ found: false });
    var best = null, bestScore = 1e15;
    docs().forEach(function (d) {
      d.doc.querySelectorAll('[data-jack="0"]').forEach(function (e) { e.removeAttribute('data-jack'); });
      var walker = d.doc.createTreeWalker(d.doc.body || d.doc.documentElement, NodeFilter.SHOW_TEXT), node;
      var cands = [];
      while ((node = walker.nextNode())) {
        var v = clean(node.nodeValue);
        if (v && v.indexOf(want) >= 0 && node.parentElement) cands.push(node.parentElement);
      }
      d.doc.querySelectorAll('[aria-label],[title],input[type=submit],input[type=button],img[alt]').forEach(function (e) {
        var v = clean(e.getAttribute('aria-label') || e.getAttribute('title') || e.value || e.getAttribute('alt'));
        if (v && v.indexOf(want) >= 0) cands.push(e);
      });
      cands.forEach(function (el) {
        var r = visibleRect(el);
        if (!r) return;
        var whole = clean(el.innerText || el.getAttribute('aria-label') || el.value || el.getAttribute('alt') || '');
        if (whole.length > want.length * 4 + 20) return;
        // 往上找能点的那层（链接、按钮、手指形状），最多 4 层
        var target = el;
        for (var k = 0, p = el; k < 4 && p; k++, p = p.parentElement) {
          if (p.matches && (p.matches(SELECTOR) || styleOf(p).cursor === 'pointer')) { target = p; break; }
        }
        var score = (whole === want ? 0 : 1e6) + r.width * r.height;
        if (score < bestScore) { best = target; bestScore = score; }
      });
    });
    if (!best) return JSON.stringify({ found: false });
    return mark(best);
  }

  /** 这个位置（网页可视区 CSS px）上是什么：标成编号 0 返回它的情况；是读不到的嵌入框（别的网站）就说一声。 */
  function at(x, y) {
    var el = document.elementFromPoint(x, y);
    if (!el) return JSON.stringify({ found: false });
    if (el.tagName === 'IFRAME' || el.tagName === 'FRAME') {
      var cd = null;
      try { cd = el.contentDocument; } catch (e) { cd = null; }
      if (!cd) return JSON.stringify({ found: true, frame: true, label: el.title || el.name || el.src || '' });
      var r = el.getBoundingClientRect();
      var inner = cd.elementFromPoint(x - r.left, y - r.top);
      if (inner) el = inner;
    }
    docs().forEach(function (d) { d.doc.querySelectorAll('[data-jack="0"]').forEach(function (e) { e.removeAttribute('data-jack'); }); });
    var target = el;
    for (var k = 0, p = el; k < 4 && p; k++, p = p.parentElement) {
      if (p.matches && (p.matches(SELECTOR) || styleOf(p).cursor === 'pointer')) { target = p; break; }
    }
    return mark(target);
  }

  /** 元素中心在网页可视区里的位置（CSS px），真手指点它用。先滚到屏幕中间。 */
  function center(n) {
    var el = find(n);
    if (!el) return JSON.stringify({ found: false });
    el.scrollIntoView({ block: 'center', inline: 'center' });
    var r = el.getBoundingClientRect(), o = offsetOf(el);
    return JSON.stringify({ found: true, x: o.x + r.left + r.width / 2, y: o.y + r.top + r.height / 2, vw: innerWidth });
  }

  /**
   * 关掉挡在网页上的弹窗：「打开 App」「下载 App」「领优惠券」、Cookie 提示这类。
   * 只点写着关闭、跳过、以后再说之类的按钮（Cookie 提示可以点同意）；登录、注册框不碰。
   * 找不到关闭按钮、又明显是让下 App 的横幅，就直接藏起来。返回关掉了什么。
   */
  var CLOSE = /^(×|✕|✖|x|X|关闭|关掉|跳过|取消|不了|暂不|暂不需要|以后再说|下次再说|我知道了|知道了|残忍拒绝|不用了|close|dismiss|no thanks|not now|skip|reject all|decline|仅必要|拒绝)$/i;
  var NAG = /(打开.{0,8}app|打开.{0,6}客户端|下载\s*app|app\s*内打开|app\s*打开|立即下载|领取.{0,6}券|优惠券|新人.{0,4}礼|cookie|隐私政策|使用条款)/i;
  var LOGIN_BOX = /登录|登入|注册|sign in|log in|sign up|continue with|使用.{0,10}(账户|账号)继续/i;
  function dismiss() {
    var closed = [];
    var vw = innerWidth, vh = innerHeight;
    var all = document.querySelectorAll('body *');
    for (var i = 0; i < all.length && closed.length < 5; i++) {
      var el = all[i], st = styleOf(el);
      if (st.position !== 'fixed' && st.position !== 'sticky') continue;
      var r = el.getBoundingClientRect();
      if (r.height < 40 || r.bottom < 0 || r.top > vh) continue;
      // 登录、注册框（ChatGPT 那种先填邮箱的也算）不碰：是用户要用的，不是广告
      if (el.querySelector('input[type=password],input[type=email],input[type=tel],input[autocomplete*=username]')) continue;
      var text = clean(el.innerText).slice(0, 300);
      if (LOGIN_BOX.test(text)) continue;
      // 窄的只管 Cookie、隐私偏好这种角落小卡片；别的窄条（客服按钮、悬浮购物车）不碰
      if (r.width < vw * 0.5 && !(r.width >= vw * 0.3 && /cookie|隐私|偏好设置|privacy/i.test(text))) continue;
      if (!NAG.test(text) && r.width * r.height < vw * vh * 0.4) continue;
      var btn = null;
      el.querySelectorAll('button,a,[role=button],span,i,div,img,svg').forEach(function (b) {
        if (btn) return;
        var t = clean(b.innerText || b.getAttribute('aria-label') || b.getAttribute('title') || b.getAttribute('alt') || '');
        var cls = (typeof b.className === 'string' ? b.className : '') + ' ' + (b.id || '');
        if ((t && t.length <= 8 && CLOSE.test(t)) || (!t && /close|guanbi|cancel/i.test(cls))) {
          if (visibleRect(b)) btn = b;
        }
      });
      if (!btn && /cookie|隐私|偏好设置|privacy/i.test(text)) {
        el.querySelectorAll('button,a,[role=button]').forEach(function (b) { if (!btn && /^(拒绝非必要|仅限必要|仅必要|reject non-essential|reject all|同意|接受|全部接受|accept|accept all|ok|got it)$/i.test(clean(b.innerText))) btn = b; });
      }
      if (btn) { btn.click(); closed.push(text.slice(0, 30) || '弹窗'); continue; }
      if (NAG.test(text) && r.height < vh * 0.5) { el.style.setProperty('display', 'none', 'important'); closed.push(text.slice(0, 30)); }
    }
    return JSON.stringify(closed);
  }

  /** 页面在不在变：文字长度 + 元素个数，连着几次一样就算加载完了。 */
  function signature() {
    var b = document.body;
    return (b ? b.innerText.length : 0) + ':' + document.getElementsByTagName('*').length;
  }

  /** 比 signature 细：文字内容的哈希 + 元素个数 + 勾选和展开状态。点完对比，看点没点上（数字 1 变 2 也算变了）。 */
  function fingerprint() {
    var b = document.body, t = b ? b.innerText : '', h = 0;
    for (var i = 0; i < t.length; i++) h = (h * 31 + t.charCodeAt(i)) | 0;
    var states = document.querySelectorAll('[aria-expanded="true"],[aria-pressed="true"],[aria-selected="true"],[aria-checked="true"],:checked,[open]').length;
    return h + ':' + document.getElementsByTagName('*').length + ':' + states;
  }

  function hasText(t) {
    var want = clean(t), ds = docs();
    for (var i = 0; i < ds.length; i++) { var b = ds[i].doc.body; if (b && clean(b.innerText).indexOf(want) >= 0) return true; }
    return false;
  }

  /** 一项里的各段文字（名称、价格、店名……）用 | 隔开，不粘在一起。 */
  function leafText(el) {
    var parts = [], w = el.ownerDocument.createTreeWalker(el, NodeFilter.SHOW_TEXT), t;
    while ((t = w.nextNode())) {
      var v = clean(t.nodeValue), pe = t.parentElement;
      if (!v || (pe && (pe.tagName === 'SCRIPT' || pe.tagName === 'STYLE'))) continue;
      if (parts[parts.length - 1] !== v) parts.push(v);
    }
    return parts.join(' | ');
  }

  /** 网页里重复出现的一组东西（商品卡片、搜索结果、列表）：找最像的几组，每项给文字、链接、图片。 */
  function lists(maxItems) {
    var found = [];
    var parents = document.querySelectorAll('ul,ol,tbody,div,section,main');
    for (var i = 0; i < parents.length; i++) {
      var p = parents[i], kids = p.children;
      if (kids.length < 4) continue;
      var groups = {};
      for (var j = 0; j < kids.length; j++) {
        var c = kids[j], sig = c.tagName + '.' + ((c.className && c.className.split) ? c.className.split(/\s+/)[0] : '');
        (groups[sig] = groups[sig] || []).push(c);
      }
      var bestSig = null, bestLen = 0;
      for (var g in groups) if (groups[g].length > bestLen) { bestLen = groups[g].length; bestSig = g; }
      if (bestLen < 4 || bestLen < kids.length * 0.6) continue;
      var items = groups[bestSig].filter(function (c) { return visibleRect(c) && clean(c.innerText).length >= 6; });
      if (items.length < 4) continue;
      var total = items.reduce(function (a, c) { return a + Math.min(clean(c.innerText).length, 300); }, 0);
      found.push({ el: p, items: items, score: total });
    }
    found.sort(function (a, b) { return b.score - a.score; });
    var chosen = [];
    for (var k = 0; k < found.length && chosen.length < 3; k++) {
      var f = found[k];
      if (chosen.some(function (c) { return c.el.contains(f.el) || f.el.contains(c.el); })) continue;
      chosen.push(f);
    }
    return chosen.map(function (f) {
      return f.items.slice(0, maxItems).map(function (c) {
        var a = c.matches('a[href]') ? c : c.querySelector('a[href]'), img = c.querySelector('img');
        return {
          text: leafText(c).slice(0, 400),
          href: a ? a.href : '', img: img ? (img.currentSrc || img.src || '') : '',
        };
      });
    });
  }

  /** 读整页：what = text（全文，从 start 起最多 limit 字）、links（全部链接）、table（表格和重复出现的列表）。 */
  function extract(what, start, limit) {
    if (what === 'links') {
      var seen = {}, links = [];
      document.querySelectorAll('a[href]').forEach(function (a) {
        if (links.length >= 800 || !/^https?:/.test(a.href) || seen[a.href]) return;
        var t = clean(a.innerText || a.getAttribute('aria-label') || a.title);
        if (!t) return;
        seen[a.href] = 1;
        links.push({ text: t.slice(0, 120), href: a.href });
      });
      return JSON.stringify({ links: links });
    }
    if (what === 'table') {
      var tables = [];
      document.querySelectorAll('table').forEach(function (t) {
        if (tables.length >= 5 || !visibleRect(t)) return;
        var rows = Array.prototype.slice.call(t.rows || []);
        if (rows.length < 2) return;
        var cells = function (tr) { return Array.prototype.map.call(tr.cells, function (c) { return clean(c.innerText).slice(0, 200); }); };
        var head = t.tHead && t.tHead.rows.length ? cells(t.tHead.rows[0]) : cells(rows[0]);
        var body = rows.slice(t.tHead && t.tHead.rows.length ? t.tHead.rows.length : 1).map(cells).filter(function (r) { return r.some(Boolean); });
        tables.push({ headers: head, rows: body.slice(0, 1000) });
      });
      return JSON.stringify({ tables: tables, lists: tables.length ? [] : lists(300) });
    }
    var all = (document.body ? document.body.innerText : '').replace(/[ \t]+/g, ' ').replace(/\n\s*\n+/g, '\n').trim();
    return JSON.stringify({ total: all.length, start: start, text: all.slice(start, start + limit) });
  }

  /** 元素的情况：Jack 动手前先看要不要问用户。 */
  function inspect(n) {
    var el = find(n);
    if (!el) return JSON.stringify({ found: false });
    var form = el.form || el.closest('form');
    var formText = '';
    if (form) {
      form.querySelectorAll('button,input[type=submit],input[type=button]').forEach(function (b) { formText += ' ' + label(b); });
    }
    return JSON.stringify({
      found: true, kind: kind(el), label: label(el), tag: el.tagName.toLowerCase(), type: el.type || '',
      password: el.type === 'password', text: isText(el), file: el.type === 'file',
      inForm: !!form, formPost: !!form && (form.method || '').toLowerCase() === 'post',
      formPassword: !!form && !!form.querySelector('input[type=password]'),
      formButtons: clean(formText).slice(0, 200),
      submit: el.type === 'submit' || (el.tagName === 'BUTTON' && (el.type || 'submit') === 'submit' && !!form),
    });
  }

  function fire(el, type, init) {
    var E = type.indexOf('pointer') === 0 && window.PointerEvent ? PointerEvent : MouseEvent;
    el.dispatchEvent(new E(type, init));
  }

  function setValue(el, v) {
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    // 走原生的 setter，React 之类的框架才认
    setter.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function key(el, name) {
    var map = { enter: ['Enter', 13], backspace: ['Backspace', 8], tab: ['Tab', 9], escape: ['Escape', 27],
      down: ['ArrowDown', 40], up: ['ArrowUp', 38] };
    var k = map[name];
    if (!k) return '不认识的按键：' + name;
    var init = { key: k[0], code: k[0], keyCode: k[1], which: k[1], bubbles: true, cancelable: true };
    var go = el.dispatchEvent(new KeyboardEvent('keydown', init));
    if (name === 'enter') el.dispatchEvent(new KeyboardEvent('keypress', init));
    el.dispatchEvent(new KeyboardEvent('keyup', init));
    if (!go) return '';
    if (name === 'enter' && el.form && el.tagName === 'INPUT') {
      if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit();
    } else if (name === 'backspace' && isText(el)) {
      del(el, 1);
    }
    return '';
  }

  /** 不能当普通文字输入的框，和它要的格式。 */
  var SPECIAL = { date: 'yyyy-MM-dd', 'datetime-local': 'yyyy-MM-ddTHH:mm', time: 'HH:mm', month: 'yyyy-MM', week: 'yyyy-Www',
    range: '数字', color: '#rrggbb' };

  function del(el, count) {
    if (el.isContentEditable) {
      el.focus();
      for (var i = 0; i < count; i++) document.execCommand('delete');
    } else {
      setValue(el, (el.value || '').slice(0, Math.max(0, (el.value || '').length - count)));
    }
  }

  function act(n, op, arg, clear) {
    var el = find(n);
    if (!el) return JSON.stringify({ err: '找不到编号 ' + n + '，页面可能变了，先 read 一下拿新的编号' });
    el.scrollIntoView({ block: 'center', inline: 'center' });
    try {
      if (op === 'click') {
        if (el.focus) el.focus();
        var r = el.getBoundingClientRect();
        var init = { bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0 };
        fire(el, 'pointerdown', init); fire(el, 'mousedown', init); fire(el, 'pointerup', init); fire(el, 'mouseup', init);
        el.click();
      } else if (op === 'type' && el.tagName === 'INPUT' && SPECIAL[el.type]) {
        // 日期、时间、滑条这类：直接设值（格式见 SPECIAL），网页照样收到 input / change
        el.focus();
        setValue(el, arg);
        if (el.value === '' && arg) return JSON.stringify({ err: '「' + arg + '」不是这个框认的格式，要写成 ' + SPECIAL[el.type] });
      } else if (op === 'type') {
        if (!isText(el)) return JSON.stringify({ err: '编号 ' + n + ' 不是输入框（是' + kind(el) + '）' });
        el.focus();
        if (el.isContentEditable) {
          if (clear) { document.execCommand('selectAll'); document.execCommand('delete'); }
          document.execCommand('insertText', false, arg);
        } else {
          setValue(el, (clear ? '' : (el.value || '')) + arg);
        }
      } else if (op === 'delete') {
        if (!isText(el)) return JSON.stringify({ err: '编号 ' + n + ' 不是输入框' });
        var count = parseInt(arg, 10);
        if (!(count > 0)) count = 1e9;
        del(el, count);
      } else if (op === 'select') {
        if (el.tagName !== 'SELECT') return JSON.stringify({ err: '编号 ' + n + ' 不是下拉框' });
        var want = clean(arg), idx = -1;
        for (var i = 0; i < el.options.length; i++) if (clean(el.options[i].text) === want) { idx = i; break; }
        if (idx < 0) for (var j = 0; j < el.options.length; j++) if (clean(el.options[j].text).indexOf(want) >= 0) { idx = j; break; }
        if (idx < 0) return JSON.stringify({ err: '下拉框里没有「' + arg + '」' });
        el.selectedIndex = idx;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      } else if (op === 'hover') {
        // 鼠标放上去：要悬停才展开的菜单用
        var hr = el.getBoundingClientRect();
        var hi = { bubbles: true, cancelable: true, view: window, clientX: hr.left + hr.width / 2, clientY: hr.top + hr.height / 2 };
        ['pointerover', 'pointerenter', 'mouseover', 'mouseenter', 'pointermove', 'mousemove'].forEach(function (t) { fire(el, t, hi); });
      } else if (op === 'key') {
        if (el.focus) el.focus();
        var e = key(el, arg);
        if (e) return JSON.stringify({ err: e });
      } else {
        return JSON.stringify({ err: '不认识的操作：' + op });
      }
    } catch (ex) {
      return JSON.stringify({ err: '网页出错：' + ex.message });
    }
    return JSON.stringify({ ok: true });
  }

  /**
   * 阅读模式：找出正文那一块（文章、帖子），去掉导航、侧栏、评论、推荐，排成 Markdown。
   * 返回 {title, md}。
   */
  var SKIP_TAGS = /^(NAV|ASIDE|FOOTER|FORM|SCRIPT|STYLE|NOSCRIPT|BUTTON|IFRAME|SVG|CANVAS|VIDEO|AUDIO|SELECT|TEXTAREA|INPUT|TEMPLATE)$/;
  var SKIP_NAME = /comment|share|related|recommend|footer|sidebar|advert|banner|breadcrumb|toolbar|popup|modal|cookie|subscribe|editsection|navbox|\btoc\b|reflist|references|评论|推荐/i;
  function skipName(el) { return SKIP_NAME.test((typeof el.className === 'string' ? el.className : '') + ' ' + (el.id || '')); }
  /** 元素里的字（收起来的段落也算），跳过「编辑」链接、脚本这类。 */
  function ownText(el) {
    var parts = [], w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, { acceptNode: function (n) {
      for (var e = n.parentElement; e && e !== el; e = e.parentElement) if (SKIP_TAGS.test(e.tagName) || skipName(e)) return NodeFilter.FILTER_REJECT;
      return NodeFilter.FILTER_ACCEPT;
    } }), t;
    while ((t = w.nextNode())) parts.push(t.nodeValue);
    return clean(parts.join(''));
  }
  var BLOCKS = 'div,p,ul,ol,li,h1,h2,h3,h4,h5,h6,table,section,article,pre,blockquote,figure,header,main,dl';
  function mainRoot() {
    var best = null, bestScore = 0, scores = new Map();
    document.querySelectorAll('p,pre,blockquote,li').forEach(function (p) {
      var len = clean(p.textContent).length;
      if (len < 25) return;
      // 列表项（目录、参考文献、导航）分量轻一些，正文主要看段落
      if (p.tagName === 'LI') len *= 0.3;
      var a = p.parentElement, b = a && a.parentElement;
      if (a) scores.set(a, (scores.get(a) || 0) + len);
      if (b) scores.set(b, (scores.get(b) || 0) + len / 2);
    });
    scores.forEach(function (v, el) { if (v > bestScore) { bestScore = v; best = el; } });
    var art = document.querySelector('article,[itemprop=articleBody]');
    if (art && clean(art.textContent).length > 200 && (!best || art.contains(best) || clean(art.textContent).length >= bestScore)) return art;
    // 正文常拆成好几段平级的块：最好的那块太小，就往上找一层
    if (best && bestScore < 600 && best.parentElement && best.parentElement !== document.body) best = best.parentElement;
    return best || document.querySelector('main,[role=main]') || document.body;
  }
  function readable() {
    var root = mainRoot(), out = [];
    function push(t) { t = t.replace(/[ \t]+/g, ' ').trim(); if (t.length >= 2 && out[out.length - 1] !== t) out.push(t); }
    function walk(el) {
      if (el.nodeType !== 1 || SKIP_TAGS.test(el.tagName)) return;
      if (el !== root) {
        if (skipName(el) || el.getAttribute('aria-hidden') === 'true') return;
        var st = styleOf(el);
        if (st.display === 'none' || st.visibility === 'hidden' || st.position === 'fixed') return;
      }
      var tag = el.tagName, text = function () { return ownText(el); };
      var h = /^H([1-6])$/.exec(tag);
      if (h) return push(new Array(Math.min(+h[1] + 1, 4) + 1).join('#') + ' ' + text());
      if (tag === 'PRE') return push('```\n' + (el.innerText || el.textContent || '').replace(/\n+$/, '') + '\n```');
      if (tag === 'BLOCKQUOTE') return push('> ' + text());
      if (tag === 'LI') return push('- ' + text());
      if (tag === 'TABLE') {
        var rows = Array.prototype.slice.call(el.rows || [], 0, 60).map(function (tr) {
          return '| ' + Array.prototype.map.call(tr.cells, function (c) { return ownText(c).replace(/\|/g, '/'); }).join(' | ') + ' |';
        });
        if (rows.length) { rows.splice(1, 0, rows[0].replace(/[^|]+/g, ' --- ')); push(rows.join('\n')); }
        return;
      }
      if (tag === 'P' || !el.querySelector(BLOCKS)) return push(text());
      for (var c = el.firstChild; c; c = c.nextSibling) {
        if (c.nodeType === 3) push(c.nodeValue); else walk(c);
      }
    }
    walk(root);
    var h1 = document.querySelector('h1');
    var title = (h1 && ownText(h1)) || clean(document.title);
    if (out.length && out[0].replace(/^#+ /, '') === title) out.shift();
    return JSON.stringify({ title: title, md: out.join('\n\n') });
  }

  /**
   * 视频字幕：YouTube、B 站各取一种字幕，别的网站读 <video> 上的字幕轨。要发请求，异步做：
   * 结果放进 window.__jackSubs = {ok, text, lang, from} 或 {ok:false, why}。
   */
  /** 网页源码里 key 后面那个 JSON 数组（按括号配对截出来，字符串里的括号不算）。 */
  function jsonArrayAfter(src, key) {
    var i = src.indexOf(key);
    if (i < 0) return null;
    i += key.length;
    if (src[i] !== '[') return null;
    var depth = 0, inStr = false;
    for (var j = i; j < src.length; j++) {
      var ch = src[j];
      if (inStr) { if (ch === '\\') j++; else if (ch === '"') inStr = false; continue; }
      if (ch === '"') inStr = true;
      else if (ch === '[' || ch === '{') depth++;
      else if ((ch === ']' || ch === '}') && --depth === 0) return JSON.parse(src.slice(i, j + 1));
    }
    return null;
  }

  function subtitles() {
    window.__jackSubs = null;
    function done(o) { window.__jackSubs = o; }
    function fail(why) { done({ ok: false, why: why }); }
    function plain(lines) { return lines.map(function (l) { return clean(l); }).filter(Boolean).join('\n'); }
    var host = location.hostname;
    /** YouTube 的转写稿（视频简介里「显示转写稿」用的接口）。 */
    function transcript(html, lang) {
      var m = html.match(/"getTranscriptEndpoint":\{"params":"([^"]+)"/);
      var cfg = window.ytcfg && window.ytcfg.get;
      if (!m || !cfg) return fail('YouTube 没给字幕内容（可能要登录或换个视频）');
      return fetch('/youtubei/v1/get_transcript?prettyPrint=false&key=' + (cfg('INNERTUBE_API_KEY') || ''), {
        method: 'POST', credentials: 'include', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ context: cfg('INNERTUBE_CONTEXT'), params: m[1] })
      }).then(function (r) { return r.json(); }).then(function (j) {
        var lines = [];
        (function find(o) {
          if (!o || typeof o !== 'object') return;
          if (o.transcriptSegmentRenderer) { lines.push(((o.transcriptSegmentRenderer.snippet || {}).runs || []).map(function (x) { return x.text; }).join('')); return; }
          for (var k in o) find(o[k]);
        })(j);
        if (!lines.length) return fail('YouTube 没给字幕内容（可能要登录或换个视频）');
        done({ ok: true, text: plain(lines), lang: lang, from: 'YouTube 转写稿' });
      });
    }
    try {
      if (/(^|\.)youtube\.com$/.test(host) || host === 'youtu.be') {
        var id = new URLSearchParams(location.search).get('v') || (location.pathname.match(/\/(shorts|embed)\/([\w-]{11})/) || [])[2];
        if (!id) return fail('不是视频页');
        fetch('/watch?v=' + id, { credentials: 'include' }).then(function (r) { return r.text(); }).then(function (html) {
          var tracks = jsonArrayAfter(html, '"captionTracks":');
          if (!tracks || !tracks.length) return fail('这个视频没有字幕');
          var pick = tracks.filter(function (t) { return /^zh/.test(t.languageCode); })[0] || tracks.filter(function (t) { return t.kind !== 'asr'; })[0] || tracks[0];
          var lang = (pick.name && (pick.name.simpleText || (pick.name.runs || [{}])[0].text)) || pick.languageCode;
          return fetch(pick.baseUrl + '&fmt=json3', { credentials: 'include' }).then(function (r) { return r.text(); }).then(function (body) {
            // 字幕地址现在常要额外的验证，拿到空的：改用网页上「显示转写稿」那个接口
            if (!body) return transcript(html, lang);
            var j = JSON.parse(body);
            var lines = (j.events || []).map(function (e) { return (e.segs || []).map(function (s) { return s.utf8; }).join(''); });
            done({ ok: true, text: plain(lines), lang: lang, from: 'YouTube' });
          });
        }).catch(function (e) { fail('取 YouTube 字幕出错：' + e); });
        return;
      }
      if (/(^|\.)bilibili\.com$/.test(host)) {
        var bv = (location.pathname.match(/(BV[\w]{10})/) || [])[1];
        if (!bv) return fail('不是视频页');
        var p = +(new URLSearchParams(location.search).get('p') || 1);
        fetch('https://api.bilibili.com/x/web-interface/view?bvid=' + bv, { credentials: 'include' }).then(function (r) { return r.json(); }).then(function (v) {
          if (!v.data) return fail('B 站没返回视频信息：' + (v.message || v.code));
          var cid = ((v.data.pages || [])[p - 1] || {}).cid || v.data.cid;
          var q = '?aid=' + v.data.aid + '&cid=' + cid;
          function subsOf(pl) { return (((pl.data || {}).subtitle || {}).subtitles || []); }
          function player(path) { return fetch('https://api.bilibili.com/x/player/' + path + q, { credentials: 'include' }).then(function (r) { return r.json(); }).catch(function () { return {}; }); }
          // 新接口要签名，不签有时也给；不给就退回旧接口
          return player('wbi/v2').then(function (pl) { return subsOf(pl).length ? pl : player('v2'); }).then(function (pl) {
            var subs = subsOf(pl);
            if (!subs.length) return fail('这个视频没有字幕（B 站的 AI 字幕要登录才拿得到）');
            var pick = subs.filter(function (s) { return /zh/.test(s.lan); })[0] || subs[0];
            var url = pick.subtitle_url.replace(/^\/\//, 'https://');
            return fetch(url).then(function (r) { return r.json(); }).then(function (j) {
              done({ ok: true, text: plain((j.body || []).map(function (b) { return b.content; })), lang: pick.lan_doc || pick.lan, from: 'B 站' });
            });
          });
        }).catch(function (e) { fail('取 B 站字幕出错：' + e); });
        return;
      }
      var v = document.querySelector('video');
      if (!v || !v.textTracks || !v.textTracks.length) return fail('这个网页上没找到带字幕的视频');
      var t = v.textTracks[0];
      t.mode = 'hidden';
      setTimeout(function () {
        var cues = t.cues ? Array.prototype.map.call(t.cues, function (c) { return c.text; }) : [];
        if (cues.length) done({ ok: true, text: plain(cues), lang: t.label || t.language, from: '网页' }); else fail('字幕轨是空的');
      }, 1500);
    } catch (e) { fail('出错：' + e); }
    return '1';
  }

  window.__jack = { snapshot: snapshot, inspect: inspect, act: act, markText: markText, center: center,
    signature: signature, fingerprint: fingerprint, hasText: hasText, extract: extract, at: at, dismiss: dismiss, readable: readable, subtitles: subtitles };
})();
