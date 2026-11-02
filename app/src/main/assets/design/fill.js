// 设计出图：在看不见的 WebView 里把模板填好、检查有没有装不下的，Kotlin 那边再截图（见 DesignRender.kt）。
// 调用：kaviFill(data)，填完把结果写到 window.__kaviDone（JSON 字符串）。
// data = { vars: {bg,fg,primary,...}, slots: {title:"…"}, lists: {points:[{text:"…"}, …]},
//          images: {photo:"/files/xx.jpg" 或 ""}, icons: {name: "<svg…>"} }
// 约定和模板规则一致：data-slot、data-max、data-list/data-item、data-kind="image"、data-icon、.canvas。
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
      if (out) issues.push("跑出了画布，看不到");
      if (issues.length) notes.problems.push(label + "：" + issues.join("，"));
    });
    // 固定大小的画布：内容比画布高，底下的被裁掉了（长图的画布高度跟着内容走，不算）
    var fixed = document.documentElement.getAttribute("data-kavi-size");
    if (fixed && !/x0$/.test(fixed) && c.scrollHeight > c.clientHeight + 2) notes.problems.push("内容比画布高 " + (c.scrollHeight - c.clientHeight) + " 像素，底部被裁掉了");
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

  window.kaviFill = function (data) {
    window.__kaviDone = "";
    var notes = { problems: [], unknown: [] };
    try {
      var c = canvasOf();
      Object.keys(data.vars || {}).forEach(function (k) { if (data.vars[k]) c.style.setProperty("--" + k, data.vars[k]); });
      fillLists(data.lists, notes);
      fillSlots(document, data.slots, data.images, true, notes);
      icons(data.icons || {});
    } catch (e) { notes.problems.push("填内容出错：" + e); }
    Promise.all([document.fonts ? document.fonts.ready : null, waitImages()]).then(function () {
      return new Promise(function (ok) { requestAnimationFrame(function () { requestAnimationFrame(ok); }); });
    }).then(function () {
      var size = check(notes);
      window.__kaviDone = JSON.stringify({ width: size.width, height: size.height, problems: notes.problems, unknown: notes.unknown });
    });
  };

  // 存底稿：图标的图形不存（每次出图重新放），去掉出图时临时加的东西
  window.kaviSource = function () {
    var doc = document.documentElement.cloneNode(true);
    Array.prototype.forEach.call(doc.querySelectorAll("[data-icon]"), function (el) { el.innerHTML = ""; });
    Array.prototype.forEach.call(doc.querySelectorAll("[data-kavi-temp]"), function (el) { el.remove(); });
    return "<!doctype html>\n" + doc.outerHTML;
  };
})();
