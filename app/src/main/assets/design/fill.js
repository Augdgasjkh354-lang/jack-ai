// 设计出图：在看不见的 WebView 里把模板填好、检查有没有装不下的，Kotlin 那边再截图（见 DesignRender.kt）。
// 调用：kaviFill(data)，填完把结果写到 window.__kaviDone（JSON 字符串）。
// data = { vars: {bg,fg,primary,...}, slots: {title:"…"}, lists: {points:[{text:"…"}, …]},
//          images: {photo:"/files/xx.jpg" 或 ""}, icons: {name: "<svg…>"} }
// 约定和模板规则一致：data-slot、data-max、data-list/data-item、data-kind="image"、data-icon、data-sticker、.canvas。
(function () {
  function canvasOf() { return document.querySelector(".canvas") || document.body; }

  function setText(el, value) {
    if (value === "" || value === null) { el.style.display = "none"; return; }
    el.style.display = "";
    el.textContent = "";
    String(value).split("\n").forEach(function (line, i) {
      if (i) el.appendChild(document.createElement("br"));
      el.appendChild(document.createTextNode(line));
    });
  }

  function setImage(el, src) {
    if (!src) { el.style.display = "none"; return; }
    el.style.display = "";
    if (el.tagName === "IMG") { el.src = src; return; }
    el.style.backgroundImage = 'url("' + src.replace(/"/g, "%22") + '")';
    el.style.backgroundSize = "cover";
    el.style.backgroundPosition = "center";
    el.style.backgroundRepeat = "no-repeat";
  }

  // 不在任何 data-item 里的那些（列表项里的同名位置归列表管）
  function outside(root, sel) {
    return Array.prototype.filter.call(root.querySelectorAll(sel), function (el) { return !el.closest("[data-item]"); });
  }

  function fillSlots(root, slots, images, top, notes) {
    Object.keys(slots || {}).forEach(function (name) {
      var els = top ? outside(root, '[data-slot="' + name + '"]') : root.querySelectorAll('[data-slot="' + name + '"]');
      if (!els.length) { if (top) notes.unknown.push(name); return; }
      Array.prototype.forEach.call(els, function (el) {
        if (el.getAttribute("data-kind") === "image") setImage(el, slots[name]);
        else setText(el, slots[name]);
      });
    });
    Object.keys(images || {}).forEach(function (name) {
      var els = top ? outside(root, '[data-slot="' + name + '"]') : root.querySelectorAll('[data-slot="' + name + '"]');
      if (!els.length) { if (top) notes.unknown.push(name); return; }
      Array.prototype.forEach.call(els, function (el) { setImage(el, images[name]); });
    });
  }

  function fillLists(lists, notes) {
    Object.keys(lists || {}).forEach(function (name) {
      var box = document.querySelector('[data-list="' + name + '"]');
      if (!box) { notes.unknown.push(name); return; }
      var items = box.querySelectorAll(":scope > [data-item]");
      if (!items.length) items = box.querySelectorAll("[data-item]");
      if (!items.length) return;
      var proto = items[0].cloneNode(true);
      var parent = items[0].parentNode;
      var anchor = items[items.length - 1].nextSibling;
      Array.prototype.forEach.call(items, function (it) { it.remove(); });
      var rows = lists[name] || [];
      var max = parseInt(box.getAttribute("data-max") || "0", 10);
      if (max && rows.length > max) { notes.problems.push("列表「" + name + "」最多 " + max + " 项，多出的 " + (rows.length - max) + " 项没放"); rows = rows.slice(0, max); }
      rows.forEach(function (row, i) {
        var it = proto.cloneNode(true);
        if (typeof row === "string") row = { text: row };
        row = Object.assign({ index: String(i + 1), num: String(i + 1).padStart(2, "0") }, row);
        var imgs = {}, texts = {};
        Object.keys(row).forEach(function (k) {
          var el = it.querySelector('[data-slot="' + k + '"]');
          if (el && el.getAttribute("data-kind") === "image") imgs[k] = row[k]; else texts[k] = row[k];
        });
        fillSlots(it, texts, imgs, false, notes);
        parent.insertBefore(it, anchor);
      });
    });
  }

  function icons(map) {
    Array.prototype.forEach.call(document.querySelectorAll("[data-icon]"), function (el) {
      var svg = map[el.getAttribute("data-icon")];
      el.innerHTML = svg || "";
    });
  }

  // 贴纸（data-sticker="贴纸id"）：图形不存在底稿里，出图、打开编辑器时按 id 填进去；没给的不动
  function stickers(map) {
    Array.prototype.forEach.call(document.querySelectorAll("[data-sticker]"), function (el) {
      var svg = map[el.getAttribute("data-sticker")];
      if (svg) el.innerHTML = svg;
    });
  }

  // 检查：文字位装不下（内容比框大）、跑出画布、字数超过 data-max
  function check(notes) {
    var c = canvasOf();
    var cr = c.getBoundingClientRect();
    var seen = {};
    Array.prototype.forEach.call(c.querySelectorAll("[data-slot]"), function (el) {
      if (el.getAttribute("data-kind") === "image") return;
      var cs = getComputedStyle(el);
      if (cs.display === "none" || cs.visibility === "hidden") return;
      var name = el.getAttribute("data-slot");
      var item = el.closest("[data-item]");
      var label = item ? (item.parentNode.closest("[data-list]") || item.parentNode).getAttribute("data-list") + " 第 " +
        (Array.prototype.indexOf.call(item.parentNode.querySelectorAll(":scope > [data-item]"), item) + 1) + " 项的「" + name + "」" : "「" + name + "」";
      if (seen[label]) return;
      seen[label] = 1;
      var issues = [];
      var text = (el.textContent || "").trim();
      var max = parseInt(el.getAttribute("data-max") || "0", 10);
      if (max && text.length > max) issues.push("有 " + text.length + " 字，超过建议的 " + max + " 字");
      var over = el.scrollHeight > el.clientHeight + 2 || el.scrollWidth > el.clientWidth + 2;
      var clamped = cs.webkitLineClamp && cs.webkitLineClamp !== "none" && el.scrollHeight > el.clientHeight + 2;
      var r = el.getBoundingClientRect();
      var out = r.width > 0 && (r.left < cr.left - 1 || r.right > cr.right + 1 || r.top < cr.top - 1 || r.bottom > cr.bottom + 1);
      if (clamped) issues.push("太长被截断了（末尾显示成省略号）");
      else if (over && cs.overflow !== "visible") issues.push("装不下，有一部分被遮住了");
      if (out && !el.closest("[data-bleed]")) issues.push("跑出了画布，看不到");
      if (issues.length) notes.problems.push(label + "：" + issues.join("，"));
    });
    // 固定大小的画布：内容比画布高，底下的被裁掉了（长图的画布高度跟着内容走，不算）
    // 故意出血的装饰（data-bleed，比如压在角上的巨型数字）不算
    var bleeds = Array.prototype.map.call(c.querySelectorAll("[data-bleed]"), function (el) { var d = el.style.display; el.style.display = "none"; return [el, d]; });
    var overflow = c.scrollHeight - c.clientHeight;
    bleeds.forEach(function (x) { x[0].style.display = x[1]; });
    var fixed = document.documentElement.getAttribute("data-kavi-size");
    if (fixed && !/x0$/.test(fixed) && overflow > 2) notes.problems.push("内容比画布高 " + overflow + " 像素，底部被裁掉了");
    return { width: Math.round(cr.width), height: Math.round(Math.max(cr.height, c.scrollHeight)) };
  }

  function waitImages() {
    var urls = [];
    Array.prototype.forEach.call(document.querySelectorAll("*"), function (el) {
      var bg = getComputedStyle(el).backgroundImage;
      var m = bg && bg.match(/url\("?([^")]+)"?\)/);
      if (m) urls.push(m[1]);
      if (el.tagName === "IMG" && el.src) urls.push(el.src);
    });
    return Promise.all(urls.map(function (u) {
      return new Promise(function (ok) { var i = new Image(); i.onload = i.onerror = function () { ok(); }; i.src = u; });
    }));
  }


  // —— 审美检查：不靠模型看，用规则查常见的毛病（结果是建议，模型自己判断改不改） ——
  function rgba(str) {
    var m = String(str).match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    var p = m[1].split(/[\s,\/]+/).filter(Boolean).map(parseFloat);
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  }
  function lum(c) {
    function ch(v) { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
    return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b);
  }
  function contrast(a, b) { var x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); }
  function hsl(c) {
    var r = c.r / 255, g = c.g / 255, b = c.b / 255, mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2, h = 0, s = 0;
    if (mx !== mn) {
      var d = mx - mn; s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
      h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4; h *= 60;
    }
    return { h: h, s: s, l: l };
  }
  function blend(top, under) {
    var a = top.a;
    return { r: top.r * a + under.r * (1 - a), g: top.g * a + under.g * (1 - a), b: top.b * a + under.b * (1 - a), a: 1 };
  }
  // 文字后面的底色：往上找背景色（半透明的叠起来）；碰到照片、渐变就不知道了（返回 null）
  function backdrop(el, c) {
    var layers = [];
    for (var n = el; n; n = n.parentElement) {
      var cs = getComputedStyle(n);
      if (cs.backgroundImage && cs.backgroundImage !== "none") return null;
      var bg = rgba(cs.backgroundColor);
      if (bg && bg.a > 0) { layers.push(bg); if (bg.a >= 0.99) break; }
      if (n === c) break;
    }
    var base = { r: 255, g: 255, b: 255, a: 1 };
    for (var i = layers.length - 1; i >= 0; i--) base = blend(layers[i], base);
    return base;
  }
  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    for (var n = el; n; n = n.parentElement) {
      var cs = getComputedStyle(n);
      if (cs.display === "none" || cs.visibility === "hidden" || parseFloat(cs.opacity) < 0.05) return false;
    }
    return true;
  }
  function ownText(el) {
    var t = "";
    Array.prototype.forEach.call(el.childNodes, function (n) { if (n.nodeType === 3) t += n.nodeValue; });
    return t.replace(/\s+/g, "");
  }
  function lint() {
    var c = canvasOf(), cr = c.getBoundingClientRect(), k = cr.width / 1080, out = [];
    var fixed = !/x0$/.test(document.documentElement.getAttribute("data-kavi-size") || "");
    var texts = [];
    Array.prototype.forEach.call(c.querySelectorAll("*"), function (el) {
      if (el.closest("svg") || !ownText(el) || !visible(el)) return;
      texts.push(el);
    });
    function name(el) {
      var s = el.closest("[data-slot]");
      var t = ownText(el);
      return (s ? "「" + s.getAttribute("data-slot") + "」" : "") + "“" + (t.length > 10 ? t.slice(0, 10) + "…" : t) + "”";
    }
    // 1. 字太小：手机上一张 1080 宽的图只有三百多个点宽，24px 的字已经接近看不清
    var small = texts.filter(function (el) {
      var cs = getComputedStyle(el), size = parseFloat(cs.fontSize) / k;
      return size < 19.5 || (size < 22 && parseInt(cs.fontWeight, 10) < 600);
    });
    if (small.length) out.push("字太小，手机上看不清：" + small.slice(0, 3).map(name).join("、") + (small.length > 3 ? " 等 " + small.length + " 处" : "") + "。正文至少 24px，小标签至少 20px 且加粗");
    // 2. 对比度
    var weak = [], bad = [];
    texts.forEach(function (el) {
      var cs = getComputedStyle(el), fg = rgba(cs.color), bg = backdrop(el, c);
      if (!fg || !bg || cs.webkitTextStroke && parseFloat(cs.webkitTextStrokeWidth) > 0) return;
      var size = parseFloat(cs.fontSize) / k, bold = parseInt(cs.fontWeight, 10) >= 600;
      var large = size >= 48 || (bold && size >= 34);
      var ratio = contrast(blend(fg, bg), bg);
      if (ratio < 2.2) bad.push(name(el) + " " + ratio.toFixed(1));
      else if (ratio < (large ? 3 : 4.5)) weak.push(name(el) + " " + ratio.toFixed(1));
    });
    if (bad.length) out.push("文字和底色几乎一样，看不清（对比度）：" + bad.slice(0, 4).join("、") + "。正文要 4.5 以上，大标题 3 以上");
    if (weak.length) out.push("对比度偏低：" + weak.slice(0, 4).join("、") + "。正文要 4.5 以上，大标题 3 以上；次要文字可以淡，但别淡到要凑近看");
    // 3. 彩色太多：按色相归堆（差 25° 以内算一种），面积太小的不算
    var hues = [];
    function addHue(col, area) {
      var cc = rgba(col);
      if (!cc || cc.a < 0.3) return;
      var x = hsl(cc);
      if (x.s < 0.28 || x.l < 0.12 || x.l > 0.93) return;
      for (var i = 0; i < hues.length; i++) { var d = Math.abs(hues[i].h - x.h); if (Math.min(d, 360 - d) < 25) { hues[i].area += area; return; } }
      hues.push({ h: x.h, area: area });
    }
    var total = cr.width * cr.height;
    Array.prototype.forEach.call(c.querySelectorAll("*"), function (el) {
      if (el.closest("svg") && el.tagName.toLowerCase() !== "svg") return;
      if (!visible(el)) return;
      var r = el.getBoundingClientRect(), cs = getComputedStyle(el), area = r.width * r.height;
      addHue(cs.backgroundColor, area);
      if (ownText(el)) addHue(cs.color, area * 0.3);
    });
    addHue(getComputedStyle(c).backgroundColor, total);
    var colorful = hues.filter(function (x) { return x.area > total * 0.004; });
    if (colorful.length > 4) out.push("彩色用了 " + colorful.length + " 种，显得乱：留一个主色、一个辅色、最多一个点缀色，其余换成黑白灰或主色的深浅");
    // 4. 字体太多
    var fams = {};
    texts.forEach(function (el) { fams[getComputedStyle(el).fontFamily.split(",")[0].replace(/["']/g, "").trim()] = 1; });
    var nf = Object.keys(fams).length;
    if (nf > 3) out.push("用了 " + nf + " 种字体（" + Object.keys(fams).join("、") + "）：标题一种、正文一种，英文数字最多再加一种");
    // 5. 贴边：文字离画布边太近，显得局促，小红书还会被裁掉一点
    var edge = 40 * k, tight = [];
    texts.forEach(function (el) {
      var r = el.getBoundingClientRect();
      var d = Math.min(r.left - cr.left, cr.right - r.right, r.top - cr.top, fixed ? cr.bottom - r.bottom : 9999);
      if (d < edge && d > -2 && !el.closest("[data-bleed]")) tight.push(name(el));
    });
    if (tight.length) out.push("文字离边不到 40px，太挤：" + tight.slice(0, 3).join("、") + "。四边留 64px 以上（名片、封面 40px）");
    // 6. 层级：最大的字和正文差不多大，就没有视觉焦点
    var sizes = texts.map(function (el) { return parseFloat(getComputedStyle(el).fontSize); }).sort(function (a, b) { return a - b; });
    if (sizes.length >= 3) {
      var body = sizes[Math.floor(sizes.length / 2)], top = sizes[sizes.length - 1];
      if (top / body < 1.8) out.push("标题不够突出：最大的字 " + Math.round(top / k) + "px，正文 " + Math.round(body / k) + "px，标题至少是正文的 2.5 倍");
    }
    // 7. 大段文字居中
    texts.forEach(function (el) {
      var cs = getComputedStyle(el), lines = el.getBoundingClientRect().height / (parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.4);
      if (cs.textAlign === "center" && lines > 3.5 && ownText(el).length > 50) out.push(name(el) + " 是一大段居中的字，难读：长段落改成左对齐");
    });
    // 8. 字太多（只看固定大小的图）
    var chars = texts.reduce(function (n, el) { return n + ownText(el).length; }, 0);
    var limit = Math.round(300 * (cr.width * cr.height) / (1080 * 1440));
    if (fixed && chars > limit) out.push("整张图有 " + chars + " 个字，太多了（这么大的图建议不超过 " + limit + " 字）：精简，或者拆成两张");
    // 9. AI 味：蓝紫渐变、发光字、到处都是阴影
    var slop = [];
    Array.prototype.forEach.call(c.querySelectorAll("*"), function (el) {
      var cs = getComputedStyle(el);
      var g = cs.backgroundImage;
      if (g && /gradient/.test(g)) {
        var cols = (g.match(/rgba?\([^)]+\)/g) || []).map(rgba).filter(Boolean).map(hsl).filter(function (x) { return x.s > 0.35; });
        var blue = cols.some(function (x) { return x.h >= 200 && x.h < 250; }), purple = cols.some(function (x) { return x.h >= 250 && x.h < 300; });
        if (blue && purple && slop.indexOf("蓝紫渐变") < 0) slop.push("蓝紫渐变");
      }
      if (cs.textShadow && cs.textShadow !== "none" && /\d+px\s+\d+px\s+([2-9]\d|1[6-9])px/.test(cs.textShadow) && slop.indexOf("发光字") < 0) slop.push("发光字");
    });
    var shadows = Array.prototype.filter.call(c.querySelectorAll("*"), function (el) { var s = getComputedStyle(el).boxShadow; return s && s !== "none"; }).length;
    if (shadows > 5) slop.push(shadows + " 处投影");
    if (slop.length) out.push("有「AI 味」：" + slop.join("、") + "。换成纯色块、细线、纸纹这类更克制的处理");
    return out;
  }

  // 编辑器里「Kavi 点评」用：现在这张图的检查结果
  window.kaviLint = function () {
    var notes = { problems: [] };
    try { check(notes); } catch (e) {}
    var advice = [];
    try { advice = lint(); } catch (e) {}
    return { problems: notes.problems, advice: advice };
  };

  window.kaviFill = function (data) {
    window.__kaviDone = "";
    var notes = { problems: [], unknown: [] };
    try {
      var c = canvasOf();
      Object.keys(data.vars || {}).forEach(function (k) { if (data.vars[k]) c.style.setProperty("--" + k, data.vars[k]); });
      fillLists(data.lists, notes);
      fillSlots(document, data.slots, data.images, true, notes);
      icons(data.icons || {});
      stickers(data.stickers || {});
    } catch (e) { notes.problems.push("填内容出错：" + e); }
    // 字体按字分块下载：先排一次版，浏览器才知道要哪几块；等它们到了再排一次、再等一次
    function frames() { return new Promise(function (ok) { requestAnimationFrame(function () { requestAnimationFrame(ok); }); }); }
    function fonts() { void document.body.offsetHeight; return document.fonts ? document.fonts.ready : null; }
    Promise.all([fonts(), waitImages()]).then(frames).then(fonts).then(frames).then(fonts).then(frames).then(function () {
      var size = check(notes);
      var advice = [];
      try { advice = lint(); } catch (e) { advice = []; }
      window.__kaviDone = JSON.stringify({ width: size.width, height: size.height, problems: notes.problems, unknown: notes.unknown, advice: advice });
    });
  };

  // 存底稿：图标的图形不存（每次出图重新放），去掉出图时临时加的东西
  window.kaviSource = function () {
    var doc = document.documentElement.cloneNode(true);
    Array.prototype.forEach.call(doc.querySelectorAll("[data-icon],[data-sticker]"), function (el) { el.innerHTML = ""; });
    Array.prototype.forEach.call(doc.querySelectorAll("[data-kavi-temp]"), function (el) { el.remove(); });
    return "<!doctype html>\n" + doc.outerHTML;
  };
})();
