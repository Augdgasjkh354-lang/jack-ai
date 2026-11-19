// 设计图编辑器（网页这一侧）：在底稿页面上选中、拖动、缩放、旋转元素，直接在图上改字、局部改样式，改色、换字体、换图，撤销重做。
// App 那一侧见 DesignEditor.kt：通过 KaviEditor（addJavascriptInterface）收选中和状态，用 kvEd.cmd({...}) 发命令。
// 改动都写成元素自己的 style（translate、scale、rotate、color、font-size……），存底稿时一起存下，之后出图、Kavi 再改都认。
// 编辑器自己加的东西都带 data-kavi-temp，存底稿时 kaviSource() 会去掉。
(function () {
  var canvas, overlay, box, guides = {}, sel = null, history = [], future = [], dragging = null;
  var editing = null, editBefore = null, lastRange = null, lastTap = { t: 0, el: null };
  var bridge = window.KaviEditor || { select: function () {}, state: function () {} };
  var VARS = ["bg", "fg", "primary", "secondary", "accent", "muted"];

  function q(s, r) { return (r || document).querySelector(s); }
  function px(v) { return Math.round(v * 10) / 10 + "px"; }
  function k() { return canvas.getBoundingClientRect().width / 1080 || 1; }

  // —— 历史：每次改动前存一份画布 ——
  function snapshot() { return canvas.innerHTML + "\u0000" + (canvas.getAttribute("style") || ""); }
  function restore(s) {
    var i = s.indexOf("\u0000");
    var path = sel ? pathOf(sel) : null;
    canvas.innerHTML = s.slice(0, i);
    if (s.slice(i + 1)) canvas.setAttribute("style", s.slice(i + 1)); else canvas.removeAttribute("style");
    select(path ? byPath(path) : null);
  }
  function push(s) { history.push(s); if (history.length > 60) history.shift(); future = []; state(); }
  function remember() { push(snapshot()); }
  function state() { bridge.state(JSON.stringify({ undo: history.length > 0, redo: future.length > 0, editing: !!editing })); }
  // 选中的元素在画布里的位置（撤销后 DOM 换了，按位置找回来）
  function pathOf(el) { var p = []; while (el && el !== canvas) { p.unshift(Array.prototype.indexOf.call(el.parentNode.children, el)); el = el.parentNode; } return p; }
  function byPath(p) { var el = canvas; for (var i = 0; i < p.length && el; i++) el = el.children[p[i]]; return el && el !== canvas ? el : null; }

  // —— 变换：translate / scale / rotate 存在元素的 style 上 ——
  function tf(el) {
    var t = (el.style.translate || "0px 0px").split(/\s+/);
    return { x: parseFloat(t[0]) || 0, y: parseFloat(t[1]) || 0, s: parseFloat(el.style.scale) || 1, r: parseFloat(el.style.rotate) || 0 };
  }
  function setTf(el, t) {
    el.style.translate = (Math.abs(t.x) < 0.05 && Math.abs(t.y) < 0.05) ? "" : px(t.x) + " " + px(t.y);
    el.style.scale = Math.abs(t.s - 1) < 0.005 ? "" : String(Math.round(t.s * 1000) / 1000);
    el.style.rotate = Math.abs(t.r) < 0.1 ? "" : Math.round(t.r * 10) / 10 + "deg";
    if (!el.getAttribute("style")) el.removeAttribute("style");
  }

  // —— 能选中哪些：文字位、图片位、列表项、图标；点到装饰图形就选它自己 ——
  function pickable(el) {
    if (!el || el === canvas || !canvas.contains(el) || el.closest("[data-kavi-temp]")) return null;
    var svg = el.closest("svg");
    if (svg && svg.closest("[data-icon]")) return svg.closest("[data-icon]");
    if (svg && svg.parentNode === canvas && /grain|noise/.test(svg.getAttribute("class") || "")) return null; // 铺满的颗粒层不选
    if (svg) el = svg;
    return el.closest("[data-slot]") || el.closest("[data-icon]") || el;
  }
  function kind(el) {
    if (!el) return "none";
    if (el.getAttribute("data-kind") === "image" || el.tagName === "IMG") return "image";
    if (el.hasAttribute("data-item")) return "group";
    var t = (el.textContent || "").trim();
    return t && !el.querySelector("[data-slot]") ? "text" : "box";
  }
  // 文字（<br> 换成换行，和 fill.js 填进去的一致）
  function textOf(el) {
    var out = "";
    (function walk(n) {
      Array.prototype.forEach.call(n.childNodes, function (c) {
        if (c.nodeName === "BR") out += "\n";
        else if (c.nodeType === 3) out += c.nodeValue;
        else walk(c);
      });
    })(el);
    return out;
  }
  function info(el) {
    if (!el) return { kind: "none", editing: false };
    var cs = getComputedStyle(el), size = parseFloat(cs.fontSize) || 16;
    var lh = parseFloat(cs.lineHeight);
    return {
      kind: kind(el), slot: el.getAttribute("data-slot") || "", text: kind(el) === "text" ? textOf(el) : "",
      color: cs.color, background: cs.backgroundColor, fontSize: Math.round(size),
      fontFamily: cs.fontFamily.split(",")[0].replace(/["']/g, "").trim(), weight: parseInt(cs.fontWeight, 10) || 400,
      align: cs.textAlign, hidden: cs.visibility === "hidden", tf: tf(el),
      lineHeight: isNaN(lh) ? 1.4 : Math.round(lh / size * 100) / 100,
      letterSpacing: Math.round((parseFloat(cs.letterSpacing) || 0) / size * 100) / 100,
      fx: el.getAttribute("data-kv-fx") || "", added: el.hasAttribute("data-kv-added"), editing: editing === el,
      styled: !!el.querySelector("span[style]"),
    };
  }

  // —— 选中框和对齐线 ——
  function makeOverlay() {
    overlay = document.createElement("div");
    overlay.setAttribute("data-kavi-temp", "");
    overlay.style.cssText = "position:absolute;left:0;top:0;width:0;height:0;pointer-events:none;z-index:2147483647";
    box = document.createElement("div");
    box.style.cssText = "position:absolute;border:6px solid #2F7CF6;box-shadow:0 0 0 2px rgba(255,255,255,.9);border-radius:4px;display:none;transform-origin:center";
    overlay.appendChild(box);
    ["v", "h"].forEach(function (key) {
      var g = document.createElement("div");
      g.style.cssText = "position:absolute;background:#FF3B7F;display:none;" + (key === "v" ? "top:0;width:4px;" : "left:0;height:4px;");
      overlay.appendChild(g); guides[key] = g;
    });
    document.body.appendChild(overlay);
  }
  function drawBox() {
    if (!sel || !document.contains(sel)) { box.style.display = "none"; return; }
    // 框画在没变换时的位置，再套上同样的变换，旋转了也贴合
    var t = tf(sel);
    var r = sel.getBoundingClientRect(), cr = canvas.getBoundingClientRect();
    var w = sel.offsetWidth || r.width, h = sel.offsetHeight || r.height;
    var cx = r.left + r.width / 2 + window.scrollX, cy = r.top + r.height / 2 + window.scrollY;
    box.style.display = "block";
    // 正在改字时框变成虚线，看得出是在打字
    box.style.borderStyle = editing ? "dashed" : "solid";
    box.style.width = px(w * t.s + 16); box.style.height = px(h * t.s + 16);
    box.style.left = px(cx - (w * t.s + 16) / 2); box.style.top = px(cy - (h * t.s + 16) / 2);
    box.style.transform = t.r ? "rotate(" + t.r + "deg)" : "";
    guides.v.style.height = px(cr.height); guides.h.style.width = px(cr.width);
  }
  function report() { bridge.select(JSON.stringify(info(sel))); }
  function select(el) {
    if (editing && el !== editing) endEdit();
    sel = el;
    drawBox();
    report();
  }

  // —— 直接在图上改字 ——
  function startEdit(el) {
    if (!el || kind(el) !== "text") return;
    if (editing && editing !== el) endEdit();
    sel = el;
    if (editing === el) return;
    editBefore = snapshot();
    editing = el;
    el.setAttribute("contenteditable", "true");
    el.setAttribute("spellcheck", "false");
    el.focus();
    // 光标放到最后
    var r = document.createRange(); r.selectNodeContents(el); r.collapse(false);
    var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
    lastRange = null;
    drawBox(); report(); state();
  }
  function endEdit() {
    if (!editing) return;
    var el = editing;
    editing = null;
    el.removeAttribute("contenteditable");
    el.removeAttribute("spellcheck");
    normalize(el);
    var s = window.getSelection(); if (s) s.removeAllRanges();
    el.blur();
    if (snapshot() !== editBefore) { push(editBefore); fit(el); }
    editBefore = null; lastRange = null;
    drawBox(); report(); state();
  }
  // 浏览器打字时会插 <div>、<p>、空的 <span>：整理成 <br> 分行，和 fill.js 的写法一致
  function normalize(el) {
    Array.prototype.slice.call(el.querySelectorAll("div,p")).forEach(function (d) {
      var f = document.createDocumentFragment();
      if (d.previousSibling) f.appendChild(document.createElement("br"));
      while (d.firstChild) f.appendChild(d.firstChild);
      d.parentNode.replaceChild(f, d);
    });
    Array.prototype.slice.call(el.querySelectorAll("span")).forEach(function (s) {
      if (!s.textContent && !s.querySelector("br")) s.remove();
      else if (!s.getAttribute("style") && !s.attributes.length) unwrap(s);
    });
    while (el.lastChild && el.lastChild.nodeName === "BR" && el.childNodes.length > 1) el.removeChild(el.lastChild);
    el.normalize();
  }
  function unwrap(n) { var p = n.parentNode; while (n.firstChild) p.insertBefore(n.firstChild, n); p.removeChild(n); }
  document.addEventListener("selectionchange", function () {
    if (!editing) return;
    var s = window.getSelection();
    if (s.rangeCount && editing.contains(s.getRangeAt(0).commonAncestorContainer)) lastRange = s.getRangeAt(0).cloneRange();
  });
  document.addEventListener("beforeinput", function (e) {
    if (!editing) return;
    // 回车换行写成 <br>，不让浏览器插 <div>
    if (e.inputType === "insertParagraph") { e.preventDefault(); document.execCommand("insertLineBreak"); }
  });
  document.addEventListener("paste", function (e) {
    if (!editing) return;
    e.preventDefault();
    document.execCommand("insertText", false, (e.clipboardData || window.clipboardData).getData("text/plain"));
  });
  document.addEventListener("input", function () { if (editing) drawBox(); });

  // 选中的几个字套一层 span；没选中字就整段改
  function wrapSelection(style) {
    var el = editing || sel;
    if (!el) return;
    var r = editing && lastRange && !lastRange.collapsed && el.contains(lastRange.commonAncestorContainer) ? lastRange : null;
    if (!r) { Object.keys(style).forEach(function (p) { el.style.setProperty(p, style[p]); }); return; }
    var span = document.createElement("span");
    Object.keys(style).forEach(function (p) { span.style.setProperty(p, style[p]); });
    span.appendChild(r.extractContents());
    r.insertNode(span);
    // 套完把这几个字重新选上，可以接着加别的样式
    var nr = document.createRange(); nr.selectNodeContents(span);
    var s = window.getSelection(); s.removeAllRanges(); s.addRange(nr);
    lastRange = nr.cloneRange();
  }
  function clearSpans(el) {
    Array.prototype.slice.call(el.querySelectorAll("span")).forEach(function (s) { if (!s.hasAttribute("data-slot") && !s.hasAttribute("data-kv-fx-in")) unwrap(s); });
    el.normalize();
  }

  // —— 字改长了装不下：自动把字号缩到装得下（最多缩到一半）。改短了再长回原来的字号 ——
  function overflows(el) {
    var cs = getComputedStyle(el);
    if (cs.overflow !== "visible" && (el.scrollHeight > el.clientHeight + 2 || el.scrollWidth > el.clientWidth + 2)) return true;
    var cr = canvas.getBoundingClientRect(), r = el.getBoundingClientRect();
    if (r.right > cr.right + 1 || r.left < cr.left - 1) return true;
    var fixed = !/x0$/.test(document.documentElement.getAttribute("data-kavi-size") || "");
    if (fixed && (canvas.scrollHeight > canvas.clientHeight + 2 || r.bottom > cr.bottom + 1)) return true;
    // 字跑出了它所在的框（列表的一栏、卡片）：框的高度如果是跟着内容走的，字不会比它大，所以超出就是装不下。
    // 被拖动过、新加的（自己定位的）只看画布
    if (el.style.translate || el.hasAttribute("data-kv-added")) return false;
    for (var n = el.parentElement; n && n !== canvas; n = n.parentElement) {
      var pr = n.getBoundingClientRect();
      if (r.bottom > pr.bottom + 2 || r.right > pr.right + 2) return true;
    }
    return false;
  }
  function fit(el) {
    if (!el || kind(el) !== "text" || el.hasAttribute("data-bleed")) return;
    var base = parseFloat(el.getAttribute("data-kv-base")) || 0;
    if (base) el.style.fontSize = px(base);
    if (!overflows(el)) { if (base) { el.removeAttribute("data-kv-base"); } return; }
    if (!base) { base = parseFloat(getComputedStyle(el).fontSize); el.setAttribute("data-kv-base", String(Math.round(base * 10) / 10)); }
    var size = base;
    while (overflows(el) && size > base * 0.5) { size *= 0.94; el.style.fontSize = px(size); }
  }

  // —— 文字效果：描边、硬投影、软阴影、荧光底、反白块（一次一种，颜色走配色变量） ——
  var FX_PROPS = ["text-shadow", "-webkit-text-stroke", "paint-order"];
  // 荧光底、反白块要贴着每一行字（不是整个框）：套一层行内的 span 来画
  function fxInner(el, create) {
    var inner = el.querySelector(":scope > span[data-kv-fx-in]");
    if (!inner && create) {
      inner = document.createElement("span");
      inner.setAttribute("data-kv-fx-in", "");
      while (el.firstChild) inner.appendChild(el.firstChild);
      el.appendChild(inner);
    }
    return inner;
  }
  function setFx(el, fx, color) {
    if (el.getAttribute("data-kv-fx")) FX_PROPS.forEach(function (p) { el.style.removeProperty(p); });
    var old = fxInner(el, false);
    if (old) unwrap(old);
    el.removeAttribute("data-kv-fx");
    if (!fx || fx === "none") return;
    var size = parseFloat(getComputedStyle(el).fontSize), c = color || "var(--accent)";
    if (fx === "stroke") {
      // 一圈 16 个方向的硬阴影拼成描边（比 text-stroke 稳，不会吃掉笔画）
      var w = Math.max(2, Math.round(size * 0.045)), parts = [];
      for (var i = 0; i < 16; i++) { var a = i * Math.PI / 8; parts.push(px(Math.cos(a) * w) + " " + px(Math.sin(a) * w) + " 0 " + c); }
      el.style.textShadow = parts.join(",");
    } else if (fx === "shadow") {
      var d = Math.max(3, Math.round(size * 0.06));
      el.style.textShadow = px(d) + " " + px(d) + " 0 " + c;
    } else if (fx === "soft") {
      el.style.textShadow = "0 " + px(size * 0.04) + " " + px(Math.min(14, size * 0.12)) + " rgba(0,0,0,.35)";
    } else {
      var inner = fxInner(el, true);
      inner.style.setProperty("-webkit-box-decoration-break", "clone");
      inner.style.setProperty("box-decoration-break", "clone");
      if (fx === "highlight") {
        inner.style.backgroundImage = "linear-gradient(transparent 58%, color-mix(in srgb, " + c + " 55%, transparent) 58%)";
        inner.style.padding = "0 .08em";
      } else {
        inner.style.backgroundColor = c;
        inner.style.color = color === "var(--bg)" ? "var(--fg)" : "var(--bg)";
        inner.style.padding = "0 .2em";
      }
    }
    el.setAttribute("data-kv-fx", fx);
  }

  // —— 样式预设（大标题、小标题、正文、标签）：字号按 1080 宽的画布算，字体用模板定义的标题字体 ——
  function preset(el, name) {
    var cs = getComputedStyle(canvas), s = k();
    var title = cs.getPropertyValue("--font-title").trim() || cs.getPropertyValue("--font-serif").trim();
    var body = cs.getPropertyValue("--font-sans").trim();
    var p = {
      title: { size: 120, weight: "900", lh: "1.08", ls: "0", font: title },
      sub: { size: 48, weight: "700", lh: "1.3", ls: "0", font: "" },
      body: { size: 30, weight: "400", lh: "1.65", ls: "0", font: body },
      label: { size: 24, weight: "700", lh: "1.3", ls: ".12em", font: body },
    }[name];
    if (!p) return;
    el.removeAttribute("data-kv-base");
    el.style.fontSize = px(p.size * s);
    el.style.fontWeight = p.weight;
    el.style.lineHeight = p.lh;
    el.style.letterSpacing = p.ls;
    if (p.font) el.style.fontFamily = p.font;
  }

  // —— 加一段新的文字，放在当前看得见的画面中间 ——
  function addText() {
    if (getComputedStyle(canvas).position === "static") canvas.style.position = "relative";
    var n = 1;
    while (canvas.querySelector('[data-slot="text-' + n + '"]')) n++;
    var el = document.createElement("div");
    el.setAttribute("data-slot", "text-" + n);
    el.setAttribute("data-max", "60");
    el.setAttribute("data-kv-added", "");
    var cs = getComputedStyle(canvas), s = k();
    el.style.cssText = "position:absolute;z-index:20;max-width:" + px(canvas.clientWidth * 0.8) + ";font-size:" + px(56 * s) + ";font-weight:700;line-height:1.25;color:var(--fg);white-space:pre-wrap";
    var font = cs.getPropertyValue("--font-title").trim() || cs.getPropertyValue("--font-sans").trim();
    if (font) el.style.fontFamily = font;
    el.textContent = "双击改字";
    canvas.appendChild(el);
    var cr = canvas.getBoundingClientRect();
    var viewMid = Math.min(cr.bottom, window.innerHeight) / 2 + Math.max(cr.top, 0) / 2 - cr.top;
    el.style.left = px((canvas.clientWidth - el.offsetWidth) / 2);
    el.style.top = px(Math.max(0, Math.min(canvas.clientHeight - el.offsetHeight, viewMid - el.offsetHeight / 2)));
    return el;
  }

  // —— 手势：点一下选中；双击文字直接改字；按住选中的东西拖动；两指缩放加旋转。没选中的地方照常滚动（长图） ——
  var pointers = {};
  function center() { var ks = Object.keys(pointers), a = pointers[ks[0]], b = pointers[ks[1]]; return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, d: Math.hypot(a.x - b.x, a.y - b.y), ang: Math.atan2(b.y - a.y, b.x - a.x) * 180 / Math.PI }; }
  function onDown(e) {
    // 正在改字：在这段字里点、拖，是挪光标、选字，交给浏览器
    if (editing && editing.contains(e.target)) return;
    // 新的第一根手指：清掉没收到抬起的旧手指（系统吞掉了抬起事件时，不然会一直当成两指）
    if (e.isPrimary) { pointers = {}; if (dragging && dragging.moved && dragging.before) push(dragging.before); dragging = null; }
    pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
    var n = Object.keys(pointers).length;
    if (n === 1) {
      var target = pickable(document.elementFromPoint(e.clientX, e.clientY));
      if (sel && target && (target === sel || sel.contains(target))) {
        dragging = { mode: "move", x: e.clientX, y: e.clientY, start: tf(sel), moved: false, before: snapshot(), target: target };
        e.preventDefault();
      } else dragging = { mode: "tap", x: e.clientX, y: e.clientY, target: target };
    } else if (n === 2 && sel) {
      var c = center();
      dragging = { mode: "pinch", c: c, start: tf(sel), moved: false, before: dragging && dragging.before || snapshot() };
      e.preventDefault();
    }
  }
  function onMove(e) {
    if (!pointers[e.pointerId]) return;
    pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
    if (!dragging || !sel) return;
    if (dragging.mode === "move") {
      var dx = e.clientX - dragging.x, dy = e.clientY - dragging.y;
      if (!dragging.moved && Math.hypot(dx, dy) < 6) return;
      dragging.moved = true;
      var t = dragging.start;
      setTf(sel, { x: t.x + dx, y: t.y + dy, s: t.s, r: t.r });
      snap();
      drawBox();
      e.preventDefault();
    } else if (dragging.mode === "pinch" && Object.keys(pointers).length >= 2) {
      var c = center(), s0 = dragging.start;
      var s = Math.max(0.2, Math.min(6, s0.s * c.d / dragging.c.d));
      var r = s0.r + (c.ang - dragging.c.ang);
      if (Math.abs(r % 90) < 4) r = Math.round(r / 90) * 90; // 接近水平、竖直时吸住
      dragging.moved = true;
      setTf(sel, { x: s0.x + (c.x - dragging.c.x), y: s0.y + (c.y - dragging.c.y), s: s, r: r });
      drawBox();
      e.preventDefault();
    }
  }
  function onUp(e) {
    if (!pointers[e.pointerId]) return;
    delete pointers[e.pointerId];
    if (!dragging) return;
    var tapped = (dragging.mode === "tap" || (dragging.mode === "move" && !dragging.moved)) && Math.hypot(e.clientX - dragging.x, e.clientY - dragging.y) < 10;
    if (tapped) {
      var target = dragging.target, now = Date.now();
      // 同一段字连点两下：直接改字
      if (target && target === lastTap.el && now - lastTap.t < 380 && kind(target) === "text") { select(target); startEdit(target); lastTap = { t: 0, el: null }; }
      else { if (target !== sel) select(target); lastTap = { t: now, el: target }; }
      dragging = null;
      return;
    }
    if (dragging.mode === "tap") { dragging = null; return; }
    if (Object.keys(pointers).length === 0) {
      if (dragging.moved) { push(dragging.before); report(); }
      guides.v.style.display = guides.h.style.display = "none";
      dragging = null;
    }
  }
  // 拖到画布正中（横、竖）附近时吸过去，显示一条粉线
  function snap() {
    var cr = canvas.getBoundingClientRect(), r = sel.getBoundingClientRect(), t = tf(sel), th = 10;
    var dx = (cr.left + cr.width / 2) - (r.left + r.width / 2), dy = (cr.top + cr.height / 2) - (r.top + r.height / 2);
    var showV = Math.abs(dx) < th, showH = Math.abs(dy) < th && cr.height < 4000;
    if (showV) t.x += dx;
    if (showH) t.y += dy;
    if (showV || showH) setTf(sel, t);
    guides.v.style.display = showV ? "block" : "none";
    guides.h.style.display = showH ? "block" : "none";
    guides.v.style.left = px(cr.left + cr.width / 2 + window.scrollX - 2); guides.v.style.top = px(cr.top + window.scrollY);
    guides.h.style.top = px(cr.top + cr.height / 2 + window.scrollY - 2); guides.h.style.left = px(cr.left + window.scrollX);
  }

  // —— App 发来的命令 ——
  function setText(el, value) {
    el.textContent = "";
    String(value).split("\n").forEach(function (line, i) {
      if (i) el.appendChild(document.createElement("br"));
      el.appendChild(document.createTextNode(line));
    });
  }
  function textEls() {
    if (!sel) return [];
    return kind(sel) === "text" ? [sel] : Array.prototype.filter.call(sel.querySelectorAll("*"), function (x) { return kind(x) === "text" && !x.querySelector("*:not(br):not(span)"); });
  }
  var ops = {
    text: function (c) { if (sel && kind(sel) === "text") { setText(sel, c.value); fit(sel); } },
    style: function (c) { if (sel) sel.style.setProperty(c.prop, c.value); },
    fontSize: function (c) {
      // 有文字的子元素一起按比例调（选中整个列表项时）；手动调过就不再自动缩
      textEls().forEach(function (x) { x.removeAttribute("data-kv-base"); var s = parseFloat(getComputedStyle(x).fontSize); x.style.fontSize = px(Math.max(10, Math.round(s * c.factor))); });
    },
    lineHeight: function (c) {
      textEls().forEach(function (x) {
        var cs = getComputedStyle(x), size = parseFloat(cs.fontSize), lh = parseFloat(cs.lineHeight);
        var cur = isNaN(lh) ? 1.4 : lh / size;
        x.style.lineHeight = String(Math.max(0.8, Math.min(3, Math.round((cur + c.delta) * 100) / 100)));
      });
    },
    letterSpacing: function (c) {
      textEls().forEach(function (x) {
        var cs = getComputedStyle(x), cur = (parseFloat(cs.letterSpacing) || 0) / parseFloat(cs.fontSize);
        x.style.letterSpacing = Math.max(-0.1, Math.min(0.6, Math.round((cur + c.delta) * 100) / 100)) + "em";
      });
    },
    fx: function (c) { textEls().forEach(function (x) { setFx(x, c.fx, c.color); }); },
    preset: function (c) { textEls().forEach(function (x) { preset(x, c.name); fit(x); }); },
    font: function (c) { if (sel) sel.style.fontFamily = c.value; },
    weight: function (c) { if (sel) sel.style.fontWeight = String(c.value); },
    align: function (c) { if (sel) sel.style.textAlign = c.value; },
    color: function (c) { if (sel) sel.style.color = c.value; },
    background: function (c) { if (sel) sel.style.backgroundColor = c.value; },
    // 局部样式：改字时选中的那几个字（没选中就整段）
    span: function (c) { wrapSelection(c.style); },
    spanClear: function () { var el = editing || sel; if (el) clearSpans(el); },
    vars: function (c) { Object.keys(c.value).forEach(function (key) { canvas.style.setProperty("--" + key, c.value[key]); }); },
    hide: function () { if (sel) { sel.style.visibility = sel.style.visibility === "hidden" ? "" : "hidden"; } },
    remove: function () { if (sel && sel.hasAttribute("data-kv-added")) { var el = sel; select(null); el.remove(); } },
    addText: function () { var el = addText(); select(el); startEdit(el); selectAllIn(el); },
    reset: function () { if (sel) setTf(sel, { x: 0, y: 0, s: 1, r: 0 }); },
    z: function (c) {
      if (!sel) return;
      var z = parseInt(getComputedStyle(sel).zIndex, 10) || 0;
      if (getComputedStyle(sel).position === "static") sel.style.position = "relative";
      sel.style.zIndex = String(c.dir > 0 ? z + 1 : z - 1);
    },
    image: function (c) {
      if (!sel || kind(sel) !== "image") return;
      if (sel.tagName === "IMG") sel.src = c.src;
      else { sel.style.backgroundImage = 'url("' + c.src.replace(/"/g, "%22") + '")'; sel.style.backgroundSize = "cover"; sel.style.backgroundPosition = "center"; sel.style.display = ""; }
    },
    fontCss: function (c) {
      var s = document.createElement("style"); s.setAttribute("data-kavi-temp", ""); s.textContent = c.css; document.head.appendChild(s);
    },
  };
  // 新加的文字框：一进去就全选，直接打字就替换掉「双击改字」
  function selectAllIn(el) { var r = document.createRange(); r.selectNodeContents(el); var s = window.getSelection(); s.removeAllRanges(); s.addRange(r); }
  // 这些命令不进撤销历史（本身不改画布，或者自己管历史）
  var NO_HISTORY = { fontCss: 1, edit: 1, done: 1, deselect: 1, addText: 0 };

  window.kvEd = {
    cmd: function (c) {
      if (c.op === "undo" || c.op === "redo") {
        endEdit();
        var from = c.op === "undo" ? history : future, to = c.op === "undo" ? future : history;
        if (from.length) { to.push(snapshot()); restore(from.pop()); state(); }
        return;
      }
      if (c.op === "deselect") { select(null); return; }
      if (c.op === "edit") { startEdit(sel); return; }
      if (c.op === "done") { endEdit(); return; }
      if (!ops[c.op]) return;
      // 改字的过程中加的样式算在这次改字里（结束改字时一起进历史）
      if (!NO_HISTORY[c.op] && !editing) remember();
      ops[c.op](c);
      if (editing && c.op !== "addText") editing.focus();
      // 字体换了、字号改了，等排好版再画框
      requestAnimationFrame(function () { drawBox(); report(); });
    },
    // 存底稿：先结束改字，去掉选中框这些临时的东西
    source: function () {
      endEdit();
      var keep = sel; sel = null; drawBox();
      var html = window.kaviSource();
      sel = keep; drawBox();
      return html;
    },
    // 当前配色（六个变量）
    vars: function () {
      var cs = getComputedStyle(canvas), o = {};
      VARS.forEach(function (key) { o[key] = cs.getPropertyValue("--" + key).trim(); });
      return JSON.stringify(o);
    },
  };

  window.kvEdStart = function () {
    canvas = q(".canvas") || document.body;
    makeOverlay();
    document.documentElement.style.touchAction = "pan-y";
    document.addEventListener("pointerdown", onDown, { passive: false });
    document.addEventListener("pointermove", onMove, { passive: false });
    document.addEventListener("pointerup", onUp);
    document.addEventListener("pointercancel", onUp);
    // 拖动、缩放时不让浏览器自己滚动或缩放（手势归编辑器）
    document.addEventListener("touchmove", function (e) { if (dragging && dragging.mode !== "tap") e.preventDefault(); }, { passive: false });
    window.addEventListener("scroll", drawBox);
    state();
  };
})();
