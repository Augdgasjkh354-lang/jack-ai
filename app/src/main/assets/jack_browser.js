// Jack 操作网页用的脚本：给能点、能填的元素编号，读当前屏的文字，按编号点击、输入、删字、选择、按键。
// 每次调用前注入（页面跳转后会丢），定义在 window.__jack 上。
(function () {
  if (window.__jack) return;

  var SELECTOR = 'a[href],button,input,textarea,select,summary,[role=button],[role=link],[role=checkbox],[role=radio],' +
    '[role=tab],[role=menuitem],[role=option],[role=switch],[role=textbox],[role=combobox],[role=searchbox],' +
    '[contenteditable=""],[contenteditable=true],[onclick],[tabindex]:not([tabindex="-1"])';
  var MAX = 120;

  function clean(t) { return (t || '').replace(/\s+/g, ' ').trim(); }

  function visibleRect(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return null;
    var s = getComputedStyle(el);
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
        password: '密码框', search: '搜索框' })[el.type] || '输入框';
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
      var s = getComputedStyle(p);
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

  function snapshot() {
    document.querySelectorAll('[data-jack]').forEach(function (e) { e.removeAttribute('data-jack'); });
    var vh = innerHeight, items = [], above = 0, below = 0, n = 0;
    var all = document.querySelectorAll(SELECTOR);
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.disabled || (el.tagName === 'INPUT' && el.type === 'hidden')) continue;
      var r = visibleRect(el);
      if (!r) continue;
      if (r.bottom < 0) { above++; continue; }
      if (r.top > vh * 1.5) { below++; continue; }
      // 嵌套的同名元素（链接里的按钮之类）只留外层
      var outer = el.parentElement && el.parentElement.closest('[data-jack]');
      var lb = label(el);
      if (outer && (label(outer) === lb || !lb)) continue;
      if (n >= MAX) { below++; continue; }
      n++;
      el.setAttribute('data-jack', n);
      var item = { n: n, kind: kind(el), label: lb, x: r.left, y: r.top, w: r.width, h: r.height };
      if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
        if (el.type === 'password') item.value = el.value ? '（已填）' : '';
        else if (el.type === 'checkbox' || el.type === 'radio') item.checked = el.checked;
        else if (el.type !== 'submit' && el.type !== 'button' && el.type !== 'file') item.value = (el.value || '').slice(0, 100);
        if (!item.label) item.label = clean(el.placeholder);
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
      items.push(item);
    }
    var vv = window.visualViewport;
    return JSON.stringify({
      url: location.href, title: document.title, items: items, above: above, below: below,
      scroll: Math.round(scrollY), height: document.documentElement.scrollHeight, vh: vh,
      vx: vv ? vv.offsetLeft : 0, vy: vv ? vv.offsetTop : 0, vw: vv ? vv.width : innerWidth,
      text: nearbyText(4000),
    });
  }

  function find(n) { return document.querySelector('[data-jack="' + n + '"]'); }

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

  window.__jack = { snapshot: snapshot, inspect: inspect, act: act };
})();
