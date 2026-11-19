// 设计图编辑器（网页这一侧）：在底稿页面上选中、拖动、缩放、旋转元素，直接在图上改字、局部改样式，改色、换字体、换图，撤销重做。
// App 那一侧见 DesignEditor.kt：通过 KaviEditor（addJavascriptInterface）收选中和状态，用 kvEd.cmd({...}) 发命令。
// 改动都写成元素自己的 style（translate、scale、rotate、color、font-size……），存底稿时一起存下，之后出图、Kavi 再改都认。
// 编辑器自己加的东西都带 data-kavi-temp，存底稿时 kaviSource() 会去掉。
(function () {
  // sel 是主选中（工具栏、改字跟着它）；sels 是全部选中的（多选时一起挪、一起对齐）
  var canvas, overlay, boxes = [], handles = {}, guides = {}, sel = null, sels = [], multi = false, history = [], future = [], dragging = null;
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
    var paths = sels.map(pathOf), main = sel ? pathOf(sel) : null;
    canvas.innerHTML = s.slice(0, i);
    if (s.slice(i + 1)) canvas.setAttribute("style", s.slice(i + 1)); else canvas.removeAttribute("style");
    sels = paths.map(byPath).filter(Boolean);
    sel = main ? byPath(main) : null;
    if (sel && sels.indexOf(sel) < 0) sels.push(sel);
    drawBox(); report();
  }
  // 撤销历史里的一步：连着的同一种小操作（微调、数值加减）合成一步
  var lastOp = { op: "", t: 0 };
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
    if (!el) return { kind: "none", editing: false, count: 0, multi: multi };
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
      opacity: Math.round((parseFloat(cs.opacity) || 1) * 100), locked: el.hasAttribute("data-kv-lock"),
      count: sels.length, multi: multi, width: Math.round(el.offsetWidth),
      photo: kind(el) === "image" && !!photoUrl(el), cropping: cropping === el, img: kind(el) === "image" ? imgOf(el) : null,
    };
  }

  // —— 选中框、手柄和对齐线 ——
  // 手柄（只在选中一个、没锁住时有）：四角缩放、顶上旋转、文字框右边拖宽。手柄是框的子元素，框转了它们跟着转
  var HANDLE = 44, HIT = 60, ROT_GAP = 90;
  function makeOverlay() {
    overlay = document.createElement("div");
    overlay.setAttribute("data-kavi-temp", "");
    // 剪到画布大小：选中框、手柄、参考线伸出画布时不能把网页撑宽，否则 WebView 会把整页缩小
    overlay.style.cssText = "position:absolute;left:0;top:0;width:0;height:0;overflow:hidden;pointer-events:none;z-index:2147483647";
    ["v", "h"].forEach(function (key) {
      var g = document.createElement("div");
      g.style.cssText = "position:absolute;background:#FF3B7F;display:none;" + (key === "v" ? "top:0;width:4px;" : "left:0;height:4px;");
      overlay.appendChild(g); guides[key] = g;
    });
    // 参考线可能有好几条（对齐别的元素时）：再备几条
    guides.extra = [];
    for (var i = 0; i < 4; i++) {
      var g = document.createElement("div");
      g.style.cssText = "position:absolute;background:#FF3B7F;display:none";
      overlay.appendChild(g); guides.extra.push(g);
    }
    document.body.appendChild(overlay);
  }
  function makeBox() {
    var b = document.createElement("div");
    b.style.cssText = "position:absolute;border:6px solid #2F7CF6;box-shadow:0 0 0 2px rgba(255,255,255,.9);border-radius:4px;display:none;transform-origin:center";
    overlay.appendChild(b);
    return b;
  }
  function makeHandles(b) {
    function dot(kind, css) {
      var d = document.createElement("div");
      d.style.cssText = "position:absolute;width:" + HANDLE + "px;height:" + HANDLE + "px;margin:-" + (HANDLE / 2 + 3) + "px 0 0 -" + (HANDLE / 2 + 3) + "px;background:#fff;border:6px solid #2F7CF6;border-radius:50%;box-sizing:border-box;" + css;
      b.appendChild(d); handles[kind] = d;
      return d;
    }
    dot("nw", "left:0;top:0"); dot("ne", "left:100%;top:0"); dot("sw", "left:0;top:100%"); dot("se", "left:100%;top:100%");
    dot("e", "left:100%;top:50%;width:24px;height:" + (HANDLE + 20) + "px;margin:-" + (HANDLE / 2 + 13) + "px 0 0 -15px;border-radius:12px");
    var stem = document.createElement("div");
    stem.style.cssText = "position:absolute;left:50%;top:-" + ROT_GAP + "px;width:4px;height:" + ROT_GAP + "px;margin-left:-5px;background:#2F7CF6";
    b.appendChild(stem); handles.stem = stem;
    dot("rot", "left:50%;top:-" + ROT_GAP + "px;background:#2F7CF6;border-color:#fff");
  }
  // 元素没变换时的大小和中心（中心不受旋转影响）
  function geom(el) {
    var t = tf(el), r = el.getBoundingClientRect();
    var w = el.offsetWidth || r.width, h = el.offsetHeight || r.height;
    return { t: t, w: w * t.s + 16, h: h * t.s + 16, cx: r.left + r.width / 2, cy: r.top + r.height / 2 };
  }
  function drawBox() {
    sels = sels.filter(function (x) { return document.contains(x); });
    if (sel && !document.contains(sel)) sel = null;
    while (boxes.length < sels.length) boxes.push(makeBox());
    if (!handles.rot && boxes.length) makeHandles(boxes[0]);
    var cr = canvas.getBoundingClientRect();
    overlay.style.width = px(cr.right + window.scrollX); overlay.style.height = px(cr.bottom + window.scrollY);
    boxes.forEach(function (b, i) {
      var el = sels[i];
      if (!el) { b.style.display = "none"; return; }
      var g = geom(el), locked = el.hasAttribute("data-kv-lock");
      b.style.display = "block";
      // 正在改字是虚线；锁住的是灰色；多选里不是主选中的浅一点
      b.style.borderStyle = editing === el || locked || cropping === el ? "dashed" : "solid";
      b.style.borderColor = locked ? "#8A8F98" : cropping === el ? "#F59E0B" : el === sel ? "#2F7CF6" : "#7FB0FA";
      b.style.width = px(g.w); b.style.height = px(g.h);
      b.style.left = px(g.cx + window.scrollX - g.w / 2); b.style.top = px(g.cy + window.scrollY - g.h / 2);
      b.style.transform = g.t.r ? "rotate(" + g.t.r + "deg)" : "";
    });
    // 手柄在第一个框上：只选了一个、没锁、没在打字时显示
    var show = sels.length === 1 && sel && !sel.hasAttribute("data-kv-lock") && !editing && cropping !== sel;
    if (handles.rot && sels[0] !== sel) show = false;
    ["nw", "ne", "sw", "se", "rot", "stem"].forEach(function (key) { if (handles[key]) handles[key].style.display = show ? "block" : "none"; });
    if (handles.e) handles.e.style.display = show && kind(sel) === "text" ? "block" : "none";
    guides.v.style.height = px(cr.height); guides.h.style.width = px(cr.width);
  }
  // 手指按在哪个手柄上（按旋转后的真实位置算）
  function handleAt(x, y) {
    if (sels.length !== 1 || !sel || sel.hasAttribute("data-kv-lock") || editing || cropping === sel) return null;
    var g = geom(sel), a = g.t.r * Math.PI / 180, cos = Math.cos(a), sin = Math.sin(a);
    var spots = { nw: [-g.w / 2, -g.h / 2], ne: [g.w / 2, -g.h / 2], sw: [-g.w / 2, g.h / 2], se: [g.w / 2, g.h / 2], rot: [0, -g.h / 2 - ROT_GAP] };
    if (kind(sel) === "text") spots.e = [g.w / 2, 0];
    // 手指在元素里面：多半是想拖动它，离手柄很近才算抓手柄；在外面才用宽的感应范围
    var lx0 = (x - g.cx) * cos + (y - g.cy) * sin, ly0 = -(x - g.cx) * sin + (y - g.cy) * cos;
    var inside = Math.abs(lx0) < g.w / 2 && Math.abs(ly0) < g.h / 2;
    var best = null, bd = inside ? HIT / 2.2 : HIT;
    Object.keys(spots).forEach(function (key) {
      var lx = spots[key][0], ly = spots[key][1];
      var d = Math.hypot(x - (g.cx + lx * cos - ly * sin), y - (g.cy + lx * sin + ly * cos));
      if (d < bd) { bd = d; best = key; }
    });
    return best;
  }
  function report() { bridge.select(JSON.stringify(info(sel))); }
  function select(el) {
    if (editing && el !== editing) endEdit();
    if (cropping && el !== cropping) cropping = null;
    sel = el;
    sels = el ? [el] : [];
    drawBox();
    report();
  }
  // 多选：点一下加进来，再点一下拿掉
  function toggle(el) {
    if (!el) return;
    if (editing) endEdit();
    var i = sels.indexOf(el);
    if (i >= 0) { sels.splice(i, 1); sel = sels[sels.length - 1] || null; }
    else { sels.push(el); sel = el; }
    drawBox(); report();
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

  // —— 手势：点一下选中；双击文字直接改字；按住选中的东西拖动（多选时一起动）；拖手柄缩放、旋转、改宽；两指缩放加旋转；
  //    长按一个东西把它加进多选。没选中的地方照常滚动（长图） ——
  var pointers = {}, pressTimer = null;
  function center() { var ks = Object.keys(pointers), a = pointers[ks[0]], b = pointers[ks[1]]; return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, d: Math.hypot(a.x - b.x, a.y - b.y), ang: Math.atan2(b.y - a.y, b.x - a.x) * 180 / Math.PI }; }
  function movable() { return sels.filter(function (x) { return !x.hasAttribute("data-kv-lock"); }); }
  function onDown(e) {
    // 正在改字：在这段字里点、拖，是挪光标、选字，交给浏览器
    if (editing && editing.contains(e.target)) return;
    // 新的第一根手指：清掉没收到抬起的旧手指（系统吞掉了抬起事件时，不然会一直当成两指）
    if (e.isPrimary) { pointers = {}; if (dragging && dragging.moved && dragging.before) push(dragging.before); dragging = null; }
    clearTimeout(pressTimer);
    pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
    var n = Object.keys(pointers).length;
    // 裁剪中：在照片框里拖是挪照片，两指是缩放照片（框不动）
    if (cropping && sel === cropping) {
      var cr0 = cropping.getBoundingClientRect();
      if (n === 1 && e.clientX >= cr0.left && e.clientX <= cr0.right && e.clientY >= cr0.top && e.clientY <= cr0.bottom) {
        dragging = { mode: "crop", x: e.clientX, y: e.clientY, o0: imgOf(cropping), before: snapshot(), moved: false };
        e.preventDefault();
        return;
      }
      if (n === 2) {
        dragging = { mode: "cropPinch", c: center(), o0: imgOf(cropping), before: dragging && dragging.before || snapshot(), moved: false };
        e.preventDefault();
        return;
      }
    }
    if (n === 1) {
      var h = handleAt(e.clientX, e.clientY);
      if (h) {
        var g = geom(sel);
        dragging = { mode: "handle", handle: h, x: e.clientX, y: e.clientY, start: g.t, g: g, width: sel.offsetWidth, before: snapshot(), moved: false };
        e.preventDefault();
        return;
      }
      var target = pickable(document.elementFromPoint(e.clientX, e.clientY));
      var hit = target && sels.filter(function (x) { return x === target || x.contains(target); })[0];
      if (hit && !multi) {
        dragging = { mode: "move", x: e.clientX, y: e.clientY, starts: movable().map(function (x) { return [x, tf(x)]; }), moved: false, before: snapshot(), target: target };
        if (!dragging.starts.length) dragging.mode = "tap";
        else e.preventDefault();
      } else dragging = { mode: "tap", x: e.clientX, y: e.clientY, target: target };
      // 长按：把它加进多选（或者拿掉）
      if (target) pressTimer = setTimeout(function () {
        if (dragging && !dragging.moved && (dragging.mode === "tap" || dragging.mode === "move") && Object.keys(pointers).length === 1) {
          if (sels.length === 1 && sels[0] === target) return;
          toggle(target); dragging = { mode: "done" };
          if (navigator.vibrate) navigator.vibrate(20);
        }
      }, 520);
    } else if (n === 2 && sel && !sel.hasAttribute("data-kv-lock") && sels.length === 1) {
      clearTimeout(pressTimer);
      dragging = { mode: "pinch", c: center(), start: tf(sel), moved: false, before: dragging && dragging.before || snapshot() };
      e.preventDefault();
    }
  }
  function onMove(e) {
    if (!pointers[e.pointerId]) return;
    pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
    if (!dragging || dragging.mode === "tap" || dragging.mode === "done") {
      if (dragging && dragging.mode === "tap" && Math.hypot(e.clientX - dragging.x, e.clientY - dragging.y) > 10) clearTimeout(pressTimer);
      return;
    }
    if (dragging.mode === "crop" || dragging.mode === "cropPinch") {
      if (!cropping) return;
      if (dragging.mode === "crop") {
        if (!dragging.moved && Math.hypot(e.clientX - dragging.x, e.clientY - dragging.y) < 4) return;
        dragging.moved = true;
        cropPan(cropping, dragging.o0, e.clientX - dragging.x, e.clientY - dragging.y);
      } else if (Object.keys(pointers).length >= 2) {
        var cc = center();
        dragging.moved = true;
        cropZoom(cropping, dragging.o0.z * cc.d / Math.max(1, dragging.c.d));
      }
      e.preventDefault();
      return;
    }
    if (dragging.mode === "move") {
      var dx = e.clientX - dragging.x, dy = e.clientY - dragging.y;
      if (!dragging.moved && Math.hypot(dx, dy) < 6) return;
      clearTimeout(pressTimer);
      dragging.moved = true;
      dragging.starts.forEach(function (p) { var t = p[1]; setTf(p[0], { x: t.x + dx, y: t.y + dy, s: t.s, r: t.r }); });
      // 吸附：整组的外框去对齐画布和别的元素，差多少大家一起补
      var fix = snapTo(dragging.starts.map(function (p) { return p[0]; }));
      if (fix.dx || fix.dy) dragging.starts.forEach(function (p) { var t = tf(p[0]); t.x += fix.dx; t.y += fix.dy; setTf(p[0], t); });
      drawBox();
      e.preventDefault();
    } else if (dragging.mode === "handle") {
      var g = dragging.g, s0 = dragging.start, h = dragging.handle;
      dragging.moved = true;
      if (h === "rot") {
        var a0 = Math.atan2(dragging.y - g.cy, dragging.x - g.cx), a1 = Math.atan2(e.clientY - g.cy, e.clientX - g.cx);
        var r = s0.r + (a1 - a0) * 180 / Math.PI;
        r = ((r % 360) + 540) % 360 - 180;
        if (Math.abs(r - Math.round(r / 45) * 45) < 4) r = Math.round(r / 45) * 45; // 0°、45°、90° 附近吸住
        setTf(sel, { x: s0.x, y: s0.y, s: s0.s, r: r });
      } else if (h === "e") {
        // 拖右边改宽：沿着元素自己的横轴量（转过也对），宽度变了文字自动换行
        var a = s0.r * Math.PI / 180, ddx = e.clientX - dragging.x, ddy = e.clientY - dragging.y;
        var along = (ddx * Math.cos(a) + ddy * Math.sin(a)) / s0.s;
        var w = Math.max(60, dragging.width + along);
        sel.style.width = px(w); sel.style.maxWidth = "none";
        // 左边不动：排版里框往右长了 dw，中心右移 dw/2；缩放、旋转后左边要不动，中心应该沿元素横轴移 s·dw/2，差的用位移补上
        var dw = w - dragging.width;
        setTf(sel, { x: s0.x + s0.s * dw / 2 * Math.cos(a) - dw / 2, y: s0.y + s0.s * dw / 2 * Math.sin(a), s: s0.s, r: s0.r });
      } else {
        // 四角：按到中心的距离等比缩放
        var d0 = Math.hypot(dragging.x - g.cx, dragging.y - g.cy), d1 = Math.hypot(e.clientX - g.cx, e.clientY - g.cy);
        setTf(sel, { x: s0.x, y: s0.y, s: Math.max(0.15, Math.min(8, s0.s * d1 / Math.max(1, d0))), r: s0.r });
      }
      drawBox();
      e.preventDefault();
    } else if (dragging.mode === "pinch" && Object.keys(pointers).length >= 2) {
      var c = center(), p0 = dragging.start;
      var sc = Math.max(0.15, Math.min(8, p0.s * c.d / dragging.c.d));
      var rr = p0.r + (c.ang - dragging.c.ang);
      if (Math.abs(rr % 90) < 4) rr = Math.round(rr / 90) * 90; // 接近水平、竖直时吸住
      dragging.moved = true;
      setTf(sel, { x: p0.x + (c.x - dragging.c.x), y: p0.y + (c.y - dragging.c.y), s: sc, r: rr });
      drawBox();
      e.preventDefault();
    }
  }
  function onUp(e) {
    clearTimeout(pressTimer);
    if (!pointers[e.pointerId]) return;
    delete pointers[e.pointerId];
    if (!dragging) return;
    if (dragging.mode === "done") { dragging = null; return; }
    var tapped = (dragging.mode === "tap" || (dragging.mode === "move" && !dragging.moved)) && Math.hypot(e.clientX - dragging.x, e.clientY - dragging.y) < 10;
    if (tapped) {
      var target = dragging.target, now = Date.now();
      if (multi) { if (target) toggle(target); }
      // 同一段字连点两下：直接改字
      else if (target && target === lastTap.el && now - lastTap.t < 380 && kind(target) === "text") { select(target); startEdit(target); lastTap = { t: 0, el: null }; }
      // 双击照片：进裁剪
      else if (target && target === lastTap.el && now - lastTap.t < 380 && kind(target) === "image" && photoUrl(target)) { select(target); startCrop(target); lastTap = { t: 0, el: null }; }
      else { if (target !== sel || sels.length > 1) select(target); lastTap = { t: now, el: target }; }
      dragging = null;
      return;
    }
    if (dragging.mode === "tap") { dragging = null; return; }
    if (Object.keys(pointers).length === 0) {
      if (dragging.moved) { push(dragging.before); if (dragging.mode === "handle" && dragging.handle === "e") fit(sel); report(); }
      hideGuides();
      dragging = null;
      drawBox();
    }
  }

  // —— 智能参考线：拖动时，外框的左中右、上中下去对齐画布边、中线、安全边距（64px），和别的元素的边、中线 ——
  function hideGuides() {
    guides.v.style.display = guides.h.style.display = "none";
    guides.extra.forEach(function (g) { g.style.display = "none"; });
  }
  function union(els) {
    var u = null;
    els.forEach(function (el) {
      var r = el.getBoundingClientRect();
      if (!u) u = { left: r.left, top: r.top, right: r.right, bottom: r.bottom };
      else { u.left = Math.min(u.left, r.left); u.top = Math.min(u.top, r.top); u.right = Math.max(u.right, r.right); u.bottom = Math.max(u.bottom, r.bottom); }
    });
    return u;
  }
  // 能拿来对齐的别的元素：文字位、图片位、图标、列表项、画布的直接子元素（不含正在拖的、看不见的、铺满的）
  function others(moving) {
    var cr = canvas.getBoundingClientRect(), out = [];
    Array.prototype.forEach.call(canvas.querySelectorAll("[data-slot],[data-icon],[data-item],.canvas > *"), function (el) {
      if (el.closest("[data-kavi-temp]") || moving.some(function (m) { return m === el || m.contains(el) || el.contains(m); })) return;
      var r = el.getBoundingClientRect();
      if (r.width < 4 || r.height < 4 || r.width > cr.width * 0.95 && r.height > cr.height * 0.6) return;
      if (getComputedStyle(el).visibility === "hidden") return;
      out.push(r);
    });
    return out;
  }
  function snapTo(moving) {
    hideGuides();
    var cr = canvas.getBoundingClientRect(), u = union(moving), th = 12 * k(), m = 64 * k();
    var fixed = cr.height < 4000;
    var xs = [[cr.left, "c"], [cr.left + m, "m"], [cr.left + cr.width / 2, "c"], [cr.right - m, "m"], [cr.right, "c"]];
    var ys = fixed ? [[cr.top, "c"], [cr.top + m, "m"], [cr.top + cr.height / 2, "c"], [cr.bottom - m, "m"], [cr.bottom, "c"]] : [];
    others(moving).forEach(function (r) {
      xs.push([r.left, r], [r.left + r.width / 2, r], [r.right, r]);
      ys.push([r.top, r], [r.top + r.height / 2, r], [r.bottom, r]);
    });
    function best(edges, lines) {
      var b = null;
      edges.forEach(function (v) { lines.forEach(function (l) { var d = l[0] - v; if (Math.abs(d) < th && (!b || Math.abs(d) < Math.abs(b.d))) b = { d: d, at: l[0], ref: l[1] }; }); });
      return b;
    }
    var bx = best([u.left, (u.left + u.right) / 2, u.right], xs), by = best([u.top, (u.top + u.bottom) / 2, u.bottom], ys);
    var used = 0;
    function line(vertical, at, ref) {
      var g = used === 0 ? (vertical ? guides.v : guides.h) : guides.extra[used - 1];
      used++;
      if (!g) return;
      // 对齐画布的线贯穿整张图；对齐别的元素的线只连到那个元素
      var a, b;
      if (ref === "c" || ref === "m") { a = vertical ? cr.top : cr.left; b = vertical ? cr.bottom : cr.right; }
      else { a = Math.min(vertical ? ref.top : ref.left, vertical ? u.top : u.left) - 20; b = Math.max(vertical ? ref.bottom : ref.right, vertical ? u.bottom : u.right) + 20; }
      g.style.display = "block";
      g.style.background = ref === "m" ? "#14B8A6" : "#FF3B7F";
      if (vertical) { g.style.left = px(at + window.scrollX - 2); g.style.top = px(a + window.scrollY); g.style.width = "4px"; g.style.height = px(b - a); }
      else { g.style.top = px(at + window.scrollY - 2); g.style.left = px(a + window.scrollX); g.style.height = "4px"; g.style.width = px(b - a); }
    }
    if (bx) line(true, bx.at, bx.ref);
    if (by) line(false, by.at, by.ref);
    return { dx: bx ? bx.d : 0, dy: by ? by.d : 0 };
  }

  // —— 对齐、分布：一个时对齐画布（左右上下留 64px 安全边距），几个时对齐它们的外框 ——
  function shift(el, dx, dy) { var t = tf(el); t.x += dx; t.y += dy; setTf(el, t); }
  function alignTo(how) {
    var els = movable();
    if (!els.length) return;
    var cr = canvas.getBoundingClientRect(), m = 64 * k();
    var ref = els.length > 1 ? union(els) : { left: cr.left + m, right: cr.right - m, top: cr.top + m, bottom: cr.bottom - m };
    if (els.length === 1 && (how === "hcenter" || how === "vcenter")) ref = { left: cr.left, right: cr.right, top: cr.top, bottom: cr.bottom };
    els.forEach(function (el) {
      var r = el.getBoundingClientRect();
      if (how === "left") shift(el, ref.left - r.left, 0);
      else if (how === "right") shift(el, ref.right - r.right, 0);
      else if (how === "hcenter") shift(el, (ref.left + ref.right) / 2 - (r.left + r.right) / 2, 0);
      else if (how === "top") shift(el, 0, ref.top - r.top);
      else if (how === "bottom") shift(el, 0, ref.bottom - r.bottom);
      else if (how === "vcenter") shift(el, 0, (ref.top + ref.bottom) / 2 - (r.top + r.bottom) / 2);
    });
  }
  // 等距分布：首尾不动，中间的按间距相等排开
  function distribute(axis) {
    var els = movable();
    if (els.length < 3) return;
    var a = axis === "x" ? ["left", "right", "width"] : ["top", "bottom", "height"];
    var items = els.map(function (el) { return [el, el.getBoundingClientRect()]; }).sort(function (p, q) { return p[1][a[0]] + p[1][a[2]] / 2 - (q[1][a[0]] + q[1][a[2]] / 2); });
    var span = items[items.length - 1][1][a[1]] - items[0][1][a[0]];
    var sum = items.reduce(function (n, p) { return n + p[1][a[2]]; }, 0);
    var gap = (span - sum) / (items.length - 1), pos = items[0][1][a[1]] + gap;
    for (var i = 1; i < items.length - 1; i++) {
      var d = pos - items[i][1][a[0]];
      shift(items[i][0], axis === "x" ? d : 0, axis === "x" ? 0 : d);
      pos += items[i][1][a[2]] + gap;
    }
  }


  // —— 图片：裁剪（框不动，框里的照片拖动、缩放）、滤镜调色、蒙层、圆角、描边、阴影 ——
  // 设置记在元素的 data-kv-img（JSON），每次按它重新写 style；照片地址是 background-image 最后一层（fill.js 填的）。
  // 大小、位置都写成百分比，出图时不靠脚本也一样。
  var cropping = null;
  var FILTERS = {
    film: "contrast(1.08) saturate(.85) sepia(.18) brightness(1.03)", fresh: "brightness(1.07) saturate(1.18) contrast(.95)",
    warm: "sepia(.28) saturate(1.2) brightness(1.04)", cool: "saturate(.9) hue-rotate(12deg) brightness(1.03)",
    mono: "grayscale(1) contrast(1.1)", fade: "contrast(.85) brightness(1.08) saturate(.7) sepia(.15)", vivid: "saturate(1.45) contrast(1.08)",
  };
  var MASKS = {
    bottom: "linear-gradient(to top, rgba(0,0,0,.62), rgba(0,0,0,0) 55%)",
    top: "linear-gradient(to bottom, rgba(0,0,0,.55), rgba(0,0,0,0) 50%)",
    dark: "linear-gradient(rgba(0,0,0,.36), rgba(0,0,0,.36))",
    light: "linear-gradient(rgba(255,255,255,.4), rgba(255,255,255,.4))",
    fade: "linear-gradient(to top, var(--bg) 4%, color-mix(in srgb, var(--bg) 0%, transparent) 58%)",
  };
  var IMG_DEF = { z: 1, px: 50, py: 50, b: 0, c: 0, s: 0, t: 0, f: "", m: "", r: "", ol: 0, olc: "", sh: false };
  function imgOf(el) {
    var o = {}; try { o = JSON.parse(el.getAttribute("data-kv-img") || "{}") || {}; } catch (e) {}
    Object.keys(IMG_DEF).forEach(function (key) { if (o[key] == null) o[key] = IMG_DEF[key]; });
    return o;
  }
  function photoUrl(el) {
    if (el.tagName === "IMG") return el.getAttribute("src") || "";
    var m = /url\(\s*["']?([^"')]+)["']?\s*\)\s*$/.exec(el.style.backgroundImage || "");
    return m ? m[1] : "";
  }
  function photoSize(el, o) {
    if (o.z <= 1.001) return "cover";
    var W = el.offsetWidth || 1, H = el.offsetHeight || 1, z = Math.round(o.z * 1000) / 10;
    if (!o.iw || !o.ih) return z + "% auto";
    return o.iw / o.ih > W / H ? "auto " + z + "%" : z + "% auto";
  }
  function applyImg(el, o) {
    var f = o.f && FILTERS[o.f] ? [FILTERS[o.f]] : [];
    if (o.b) f.push("brightness(" + (1 + o.b / 100) + ")");
    if (o.c) f.push("contrast(" + (1 + o.c / 100) + ")");
    if (o.s) f.push("saturate(" + (1 + o.s / 100) + ")");
    el.style.filter = f.join(" ");
    el.style.borderRadius = o.r || "";
    if (o.ol) { el.style.outline = o.ol + "px solid " + (o.olc || "var(--bg)"); el.style.outlineOffset = -o.ol + "px"; }
    else { el.style.outline = ""; el.style.outlineOffset = ""; }
    el.style.boxShadow = o.sh ? "0 18px 40px rgba(0,0,0,.28)" : "";
    var url = photoUrl(el), pos = o.px + "% " + o.py + "%";
    if (el.tagName === "IMG") { el.style.objectFit = "cover"; el.style.objectPosition = pos === "50% 50%" ? "" : pos; }
    else if (url) {
      var layers = [], sizes = [], at = [], blend = [];
      if (o.m && MASKS[o.m]) { layers.push(MASKS[o.m]); sizes.push("100% 100%"); at.push("0 0"); blend.push("normal"); }
      if (o.t) {
        var a = Math.round(Math.min(60, Math.abs(o.t) * 0.6)) / 100, col = o.t > 0 ? "rgba(255,150,50," + a + ")" : "rgba(60,130,255," + a + ")";
        layers.push("linear-gradient(" + col + ", " + col + ")"); sizes.push("100% 100%"); at.push("0 0"); blend.push("soft-light");
      }
      layers.push('url("' + url.replace(/"/g, "%22") + '")'); sizes.push(photoSize(el, o)); at.push(pos); blend.push("normal");
      el.style.backgroundImage = layers.join(", ");
      el.style.backgroundSize = sizes.join(", ");
      el.style.backgroundPosition = at.join(", ");
      el.style.backgroundRepeat = "no-repeat";
      el.style.backgroundBlendMode = blend.indexOf("soft-light") >= 0 ? blend.join(", ") : "";
    }
    var keep = {};
    Object.keys(o).forEach(function (key) { if (key === "iw" || key === "ih" || o[key] !== IMG_DEF[key]) keep[key] = o[key]; });
    if (Object.keys(keep).length) el.setAttribute("data-kv-img", JSON.stringify(keep));
    else el.removeAttribute("data-kv-img");
    if (!el.getAttribute("style")) el.removeAttribute("style");
  }
  // 照片原来的宽高（裁剪时算能挪多少要用），读到了记下来
  function loadSize(el, done) {
    var o = imgOf(el), url = photoUrl(el);
    if (o.iw || !url) { if (done) done(); return; }
    var im = new Image();
    im.onload = function () { var p = imgOf(el); p.iw = im.naturalWidth; p.ih = im.naturalHeight; applyImg(el, p); if (done) done(); };
    im.src = url;
  }
  // 照片在框里实际铺多大（框自己的像素）
  function photoBox(el, o) {
    var W = el.offsetWidth || 1, H = el.offsetHeight || 1;
    if (!o.iw || !o.ih) return { W: W, H: H, w: W * o.z, h: H * o.z };
    var c = Math.max(W / o.iw, H / o.ih) * o.z;
    return { W: W, H: H, w: o.iw * c, h: o.ih * c };
  }
  function cropPan(el, o0, dxScreen, dyScreen) {
    var t = tf(el), sc = k() * t.s, r = -t.r * Math.PI / 180;
    var dx = (dxScreen * Math.cos(r) - dyScreen * Math.sin(r)) / sc, dy = (dxScreen * Math.sin(r) + dyScreen * Math.cos(r)) / sc;
    var b = photoBox(el, o0), o = imgOf(el);
    function clamp(v) { return Math.max(0, Math.min(100, Math.round(v * 10) / 10)); }
    if (b.w - b.W > 0.5) o.px = clamp(o0.px - dx / (b.w - b.W) * 100);
    if (b.h - b.H > 0.5) o.py = clamp(o0.py - dy / (b.h - b.H) * 100);
    applyImg(el, o);
  }
  function cropZoom(el, z) { var o = imgOf(el); o.z = Math.max(1, Math.min(5, Math.round(z * 100) / 100)); applyImg(el, o); }
  function startCrop(el) {
    cropping = el && kind(el) === "image" && photoUrl(el) ? el : null;
    if (cropping) loadSize(cropping, report);
    report();
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
    color: function (c) { sels.forEach(function (x) { x.style.color = c.value; }); },
    background: function (c) { sels.forEach(function (x) { x.style.backgroundColor = c.value; }); },
    // 局部样式：改字时选中的那几个字（没选中就整段）
    span: function (c) { wrapSelection(c.style); },
    spanClear: function () { var el = editing || sel; if (el) clearSpans(el); },
    vars: function (c) { Object.keys(c.value).forEach(function (key) { canvas.style.setProperty("--" + key, c.value[key]); }); },
    hide: function () { var h = sel && sel.style.visibility !== "hidden"; sels.forEach(function (x) { x.style.visibility = h ? "hidden" : ""; }); },
    remove: function () { var gone = sels.filter(function (x) { return x.hasAttribute("data-kv-added"); }); if (gone.length) { select(null); gone.forEach(function (x) { x.remove(); }); } },
    addText: function () { var el = addText(); select(el); startEdit(el); selectAllIn(el); },
    reset: function () { movable().forEach(function (x) { setTf(x, { x: 0, y: 0, s: 1, r: 0 }); x.style.width = ""; x.style.maxWidth = ""; }); },
    z: function (c) {
      sels.forEach(function (x) {
        var z = parseInt(getComputedStyle(x).zIndex, 10) || 0;
        if (getComputedStyle(x).position === "static") x.style.position = "relative";
        x.style.zIndex = String(c.dir > 0 ? z + 1 : z - 1);
      });
    },
    // 排列：对齐、分布、微调、精确数值、透明度、锁定
    arrange: function (c) { alignTo(c.how); },
    distribute: function (c) { distribute(c.axis); },
    nudge: function (c) { movable().forEach(function (x) { shift(x, c.dx * k(), c.dy * k()); }); },
    tf: function (c) {
      movable().forEach(function (x) {
        var t = tf(x);
        if (c.ds) t.s = Math.max(0.15, Math.min(8, Math.round((t.s + c.ds) * 100) / 100));
        if (c.dr) { t.r = Math.round(t.r + c.dr); t.r = ((t.r % 360) + 540) % 360 - 180; }
        setTf(x, t);
      });
    },
    opacity: function (c) {
      sels.forEach(function (x) {
        var o = Math.max(0.1, Math.min(1, Math.round(((parseFloat(getComputedStyle(x).opacity) || 1) + c.delta) * 10) / 10));
        x.style.opacity = o >= 1 ? "" : String(o);
      });
    },
    lock: function () { var on = sel && !sel.hasAttribute("data-kv-lock"); sels.forEach(function (x) { if (on) x.setAttribute("data-kv-lock", ""); else x.removeAttribute("data-kv-lock"); }); },
    image: function (c) {
      if (!sel || kind(sel) !== "image") return;
      if (sel.tagName === "IMG") sel.src = c.src;
      else { sel.style.backgroundImage = 'url("' + c.src.replace(/"/g, "%22") + '")'; sel.style.backgroundSize = "cover"; sel.style.backgroundPosition = "center"; sel.style.display = ""; }
      // 换了照片：裁剪从头来，滤镜、蒙层这些留着
      var o = imgOf(sel); delete o.iw; delete o.ih; o.z = 1; o.px = 50; o.py = 50; applyImg(sel, o); loadSize(sel);
    },
    // 图片设置：img 一次改几项（滤镜、蒙层、形状、描边、阴影）；imgAdd 调色加减；imgReset 全部还原
    img: function (c) { if (!sel || kind(sel) !== "image") return; var o = imgOf(sel); Object.keys(c.set || {}).forEach(function (key) { o[key] = c.set[key]; }); applyImg(sel, o); },
    imgAdd: function (c) {
      if (!sel || kind(sel) !== "image") return;
      var o = imgOf(sel); o[c.key] = Math.max(-100, Math.min(100, (o[c.key] || 0) + c.delta)); applyImg(sel, o);
    },
    imgReset: function () {
      if (!sel || kind(sel) !== "image") return;
      var o = imgOf(sel), keep = { iw: o.iw, ih: o.ih, z: o.z, px: o.px, py: o.py };
      Object.keys(IMG_DEF).forEach(function (key) { if (!(key in keep)) keep[key] = IMG_DEF[key]; });
      applyImg(sel, keep);
    },
    crop: function (c) { startCrop(c.on ? sel : null); },
    cropZoom: function (c) { if (cropping) loadSize(cropping, function () { cropZoom(cropping, imgOf(cropping).z + c.delta); report(); }); },
    cropReset: function () { if (cropping) { var o = imgOf(cropping); o.z = 1; o.px = 50; o.py = 50; applyImg(cropping, o); } },
    fontCss: function (c) {
      var s = document.createElement("style"); s.setAttribute("data-kavi-temp", ""); s.textContent = c.css; document.head.appendChild(s);
    },
  };
  // 新加的文字框：一进去就全选，直接打字就替换掉「双击改字」
  function selectAllIn(el) { var r = document.createRange(); r.selectNodeContents(el); var s = window.getSelection(); s.removeAllRanges(); s.addRange(r); }
  // 这些命令不进撤销历史（本身不改画布，或者自己管历史）
  var NO_HISTORY = { fontCss: 1, edit: 1, done: 1, deselect: 1, addText: 0, crop: 1 };

  window.kvEd = {
    cmd: function (c) {
      if (c.op === "undo" || c.op === "redo") {
        endEdit();
        var from = c.op === "undo" ? history : future, to = c.op === "undo" ? future : history;
        if (from.length) { to.push(snapshot()); restore(from.pop()); if (cropping) cropping = sel && kind(sel) === "image" ? sel : null; state(); report(); }
        return;
      }
      if (c.op === "deselect") { select(null); return; }
      if (c.op === "multi") { multi = !!c.on; if (!multi && sels.length > 1) select(sel); else report(); return; }
      if (c.op === "edit") { startEdit(sel); return; }
      if (c.op === "done") { endEdit(); return; }
      if (!ops[c.op]) return;
      // 改字的过程中加的样式算在这次改字里（结束改字时一起进历史）
      // 连着按的微调、数值加减（一秒内同一种）合成一步撤销
      var now = Date.now(), coalesce = (c.op === "nudge" || c.op === "tf" || c.op === "opacity" || c.op === "imgAdd" || c.op === "cropZoom") && lastOp.op === c.op && now - lastOp.t < 1000;
      lastOp = { op: c.op, t: now };
      if (!NO_HISTORY[c.op] && !editing && !coalesce) remember();
      ops[c.op](c);
      if (editing && c.op !== "addText") editing.focus();
      // 字体换了、字号改了，等排好版再画框
      requestAnimationFrame(function () { drawBox(); report(); });
    },
    // 存底稿：先结束改字，去掉选中框这些临时的东西
    source: function () {
      endEdit();
      var keep = sels; sels = []; drawBox(); hideGuides();
      var html = window.kaviSource();
      sels = keep; drawBox();
      return html;
    },
    // 这页的风格：六个配色变量加上字体变量（同步到整组用）
    theme: function () {
      var cs = getComputedStyle(canvas), o = {};
      VARS.concat(["font-title", "font-sans", "font-serif", "font-num"]).forEach(function (key) {
        var v = cs.getPropertyValue("--" + key).trim();
        if (v) o[key] = v;
      });
      return JSON.stringify(o);
    },
    // 选中的照片地址（抠图用）
    photo: function () { return sel && kind(sel) === "image" ? photoUrl(sel) : ""; },
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
