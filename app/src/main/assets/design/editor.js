// 设计图编辑器（网页这一侧）：在底稿页面上选中、拖动、缩放、旋转元素，改字、改色、换字体、换图，撤销重做。
// App 那一侧见 DesignEditor.kt：通过 KaviEditor（addJavascriptInterface）收选中和状态，用 kvEd.cmd({...}) 发命令。
// 改动都写成元素自己的 style（translate、scale、rotate、color、font-size……），存底稿时一起存下，之后出图、Kavi 再改都认。
// 编辑器自己加的东西都带 data-kavi-temp，存底稿时 kaviSource() 会去掉。
(function () {
  var canvas, overlay, box, guides = {}, sel = null, history = [], future = [], dragging = null;
  var bridge = window.KaviEditor || { select: function () {}, state: function () {} };

  function q(s, r) { return (r || document).querySelector(s); }
  function px(v) { return Math.round(v * 10) / 10 + "px"; }

  // —— 历史：每次改动前存一份画布 ——
  function snapshot() { return canvas.innerHTML + "\u0000" + (canvas.getAttribute("style") || ""); }
  function restore(s) {
    var i = s.indexOf("\u0000");
    var path = sel ? pathOf(sel) : null;
    canvas.innerHTML = s.slice(0, i);
    if (s.slice(i + 1)) canvas.setAttribute("style", s.slice(i + 1)); else canvas.removeAttribute("style");
    select(path ? byPath(path) : null);
  }
  function remember() { history.push(snapshot()); if (history.length > 60) history.shift(); future = []; state(); }
  function state() { bridge.state(JSON.stringify({ undo: history.length > 0, redo: future.length > 0 })); }
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
  function info(el) {
    if (!el) return { kind: "none" };
    var cs = getComputedStyle(el);
    var text = "";
    if (kind(el) === "text") {
      // <br> 换成换行，和 fill.js 填进去的一致
      text = Array.prototype.map.call(el.childNodes, function (n) { return n.nodeName === "BR" ? "\n" : n.textContent; }).join("");
    }
    return {
      kind: kind(el), slot: el.getAttribute("data-slot") || "", text: text,
      color: cs.color, background: cs.backgroundColor, fontSize: Math.round(parseFloat(cs.fontSize)),
      fontFamily: cs.fontFamily.split(",")[0].replace(/["']/g, "").trim(), weight: parseInt(cs.fontWeight, 10) || 400,
      align: cs.textAlign, hidden: cs.visibility === "hidden", tf: tf(el),
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
    ["v", "h"].forEach(function (k) {
      var g = document.createElement("div");
      g.style.cssText = "position:absolute;background:#FF3B7F;display:none;" + (k === "v" ? "top:0;width:4px;" : "left:0;height:4px;");
      overlay.appendChild(g); guides[k] = g;
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
    box.style.width = px(w * t.s + 16); box.style.height = px(h * t.s + 16);
    box.style.left = px(cx - (w * t.s + 16) / 2); box.style.top = px(cy - (h * t.s + 16) / 2);
    box.style.transform = t.r ? "rotate(" + t.r + "deg)" : "";
    guides.v.style.height = px(cr.height); guides.h.style.width = px(cr.width);
  }
  function select(el) {
    sel = el;
    drawBox();
    bridge.select(JSON.stringify(info(el)));
  }

  // —— 手势：点一下选中；按住选中的东西拖动；两指缩放加旋转。没选中的地方照常滚动（长图） ——
  var pointers = {};
  function center() { var k = Object.keys(pointers), a = pointers[k[0]], b = pointers[k[1]]; return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, d: Math.hypot(a.x - b.x, a.y - b.y), ang: Math.atan2(b.y - a.y, b.x - a.x) * 180 / Math.PI }; }
  function onDown(e) {
    pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
    var n = Object.keys(pointers).length;
    if (n === 1) {
      var target = pickable(document.elementFromPoint(e.clientX, e.clientY));
      if (sel && target && (target === sel || sel.contains(target))) {
        dragging = { mode: "move", x: e.clientX, y: e.clientY, start: tf(sel), moved: false, before: snapshot() };
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
    delete pointers[e.pointerId];
    if (!dragging) return;
    if (dragging.mode === "tap") {
      if (Math.hypot(e.clientX - dragging.x, e.clientY - dragging.y) < 10) select(dragging.target);
      dragging = null;
    } else if (Object.keys(pointers).length === 0) {
      if (dragging.moved) { history.push(dragging.before); if (history.length > 60) history.shift(); future = []; state(); bridge.select(JSON.stringify(info(sel))); }
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
  var ops = {
    text: function (c) { if (sel && kind(sel) === "text") setText(sel, c.value); },
    style: function (c) { if (sel) sel.style.setProperty(c.prop, c.value); },
    fontSize: function (c) {
      if (!sel) return;
      // 有文字的子元素一起按比例调（选中整个列表项时）
      var els = kind(sel) === "text" ? [sel] : Array.prototype.filter.call(sel.querySelectorAll("*"), function (x) { return kind(x) === "text"; });
      els.forEach(function (x) { var s = parseFloat(getComputedStyle(x).fontSize); x.style.fontSize = px(Math.max(10, Math.round(s * c.factor))); });
    },
    font: function (c) { if (sel) sel.style.fontFamily = c.value; },
    weight: function (c) { if (sel) sel.style.fontWeight = String(c.value); },
    align: function (c) { if (sel) sel.style.textAlign = c.value; },
    color: function (c) { if (sel) sel.style.color = c.value; },
    background: function (c) { if (sel) sel.style.backgroundColor = c.value; },
    vars: function (c) { Object.keys(c.value).forEach(function (k) { canvas.style.setProperty("--" + k, c.value[k]); }); },
    hide: function () { if (sel) { sel.style.visibility = sel.style.visibility === "hidden" ? "" : "hidden"; } },
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
  window.kvEd = {
    cmd: function (c) {
      if (c.op === "undo") { if (history.length) { future.push(snapshot()); restore(history.pop()); state(); } return; }
      if (c.op === "redo") { if (future.length) { history.push(snapshot()); restore(future.pop()); state(); } return; }
      if (c.op === "deselect") { select(null); return; }
      if (!ops[c.op]) return;
      if (c.op !== "fontCss") remember();
      ops[c.op](c);
      // 字体换了、字号改了，等排好版再画框
      requestAnimationFrame(function () { drawBox(); bridge.select(JSON.stringify(info(sel))); });
    },
    // 存底稿：去掉选中框这些临时的东西
    source: function () {
      var keep = sel; select(null);
      var html = window.kaviSource();
      if (keep) select(keep);
      return html;
    },
    // 当前配色（六个变量）
    vars: function () {
      var cs = getComputedStyle(canvas), o = {};
      ["bg", "fg", "primary", "secondary", "accent", "muted"].forEach(function (k) { o[k] = cs.getPropertyValue("--" + k).trim(); });
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
    // 选中的元素上不让浏览器自己滚动或缩放（手势归编辑器）
    document.addEventListener("touchmove", function (e) { if (dragging && dragging.mode !== "tap") e.preventDefault(); }, { passive: false });
    window.addEventListener("scroll", drawBox);
    state();
  };
})();
