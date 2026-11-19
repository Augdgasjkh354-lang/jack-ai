#!/usr/bin/env node
"use strict";
// 设计贴纸和装饰素材库校验（规格见用户派发的贴纸库 prompt）。
//   node tools/check_design_stickers.js                 全部检查 + 出三张总览预览图（design-previews/stickers/）
//   node tools/check_design_stickers.js --static-only   只查文件本身（不开浏览器）
//   node tools/check_design_stickers.js --write-index   先按 SVG 里的元数据重新生成 stickers.json，再检查
//   node tools/check_design_stickers.js arrow           只查名字里带这个的贴纸
// 预览用 Playwright 渲染：先在 ~/workspace/node_modules 找，没有再找仓库自带的。

const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");

const root = path.resolve(__dirname, "..");
const designDir = path.join(root, "app/src/main/assets/design");
const stickersDir = path.join(designDir, "stickers");
const indexPath = path.join(designDir, "stickers.json");
const previewsDir = path.join(root, "design-previews", "stickers");
const args = process.argv.slice(2);
const staticOnly = args.includes("--static-only");
const writeIndex = args.includes("--write-index");
const only = args.filter((a) => !a.startsWith("--"));

const CATEGORIES = [
  ["tape", "胶带"], ["arrow", "手绘箭头"], ["mark", "圈画标注"],
  ["label", "标签角标"], ["badge", "印章徽章"], ["frame", "边框相框"],
  ["line", "分割线"], ["shape", "色块"], ["doodle", "涂鸦小图"],
  ["floral", "植物花草"], ["festival", "节日"], ["texture", "纹理"],
];
const CATEGORY_IDS = new Set(CATEGORIES.map(([id]) => id));
const TEXT_AREA_CATEGORIES = new Set(["label", "badge", "frame"]);
// 全库统一的默认配色（暖色）：写进 SVG 的 var(--x, #HEX) 时默认色只能是这六个。
const DEFAULTS = { bg: "#FFF8F0", fg: "#2B2620", primary: "#E8483B", secondary: "#F6C453", accent: "#E07856", muted: "#9A8A7A" };
const DEFAULT_HEX = new Set(Object.values(DEFAULTS).map((v) => v.toUpperCase()));
const VARS = new Set(Object.keys(DEFAULTS));
const MAX_BYTES = 12 * 1024;

function loadPlaywright() {
  try { return require("playwright"); } catch (e) { /* fall through */ }
  for (const p of ["/home/hatch/workspace/node_modules/playwright", path.join(root, "node_modules", "playwright")]) {
    try { return require(p); } catch (e) { /* try next */ }
  }
  throw new Error("找不到 playwright：先在 ~/workspace 下装好（npm i playwright），或把仓库 node_modules 配好");
}

function readMetadata(svg, file) {
  const m = svg.match(/<metadata>([\s\S]*?)<\/metadata>/);
  assert.ok(m, file + " 缺少 <metadata> 元数据");
  // 元数据必须是 <svg> 的第一个子元素
  const before = svg.slice(0, m.index);
  assert.ok(/^<svg\b[^>]*>\s*$/.test(before), file + " <metadata> 必须是 <svg> 的第一个子元素");
  let meta;
  try { meta = JSON.parse(m[1]); } catch (e) { assert.fail(file + " 元数据不是合法 JSON：" + e.message); }
  return meta;
}

function ratioOf(w, h) {
  const r = w / h;
  if (r > 1.25) return "wide";
  if (r < 0.8) return "tall";
  return "square";
}

function checkStatic(file) {
  const full = path.join(stickersDir, file);
  const svg = fs.readFileSync(full, "utf8");
  const base = path.basename(file, ".svg");
  const problems = [];
  const fail = (msg) => problems.push(msg);

  const size = fs.statSync(full).size;
  if (size > MAX_BYTES) fail("文件 " + size + " 字节，超过 12KB");

  const cat = base.split("-")[0];
  if (!CATEGORY_IDS.has(cat)) fail("类别前缀不对：只能是 " + [...CATEGORY_IDS].join("、"));
  if (!/^[a-z]+-[a-z0-9]+(-[a-z0-9]+)*$/.test(base)) fail("文件名只能用英文小写加连字符");

  const rootTag = svg.match(/<svg\b[^>]*>/);
  if (!rootTag) fail("没有根元素 <svg>");
  else {
    if (/\bwidth\s*=/.test(rootTag[0])) fail("根元素不许写 width");
    if (/\bheight\s*=/.test(rootTag[0])) fail("根元素不许写 height");
    const vb = rootTag[0].match(/viewBox\s*=\s*"([^"]+)"/);
    if (!vb) fail("根元素缺少 viewBox");
    else {
      const parts = vb[1].trim().split(/[\s,]+/).map(Number);
      if (parts.length !== 4 || parts.some((n) => !Number.isFinite(n)) || parts[2] <= 0 || parts[3] <= 0) {
        fail("viewBox 不合法：" + vb[1]);
      } else {
        var viewBox = parts;
      }
    }
  }

  assert.ok(!/<\s*(script|image|foreignObject|text)\b/i.test(svg), file + " 不许有 <script>、<image>、<foreignObject>、<text>");
  // xmlns="http://www.w3.org/2000/svg" 是 SVG 命名空间，不算外部链接，先去掉再查
  const noNs = svg.replace(/xmlns\s*=\s*"http:\/\/www\.w3\.org\/2000\/svg"/, "");
  assert.ok(!/https?:\/\//i.test(noNs), file + " 不许有外部链接");
  assert.ok(!/@import\b/i.test(svg), file + " 不许有 @import");
  assert.ok(!/\son\w+\s*=/i.test(svg), file + " 不许有事件属性（onload 之类）");
  for (const m of svg.matchAll(/url\s*\(\s*([^)]*)\)/gi)) {
    assert.match(m[1].trim(), /^['"]?#[\w-]+['"]?$/, file + " url() 只能指向文件内的 id：" + m[0]);
  }
  // id 必须带文件名前缀
  for (const m of svg.matchAll(/\bid\s*=\s*"([^"]+)"/g)) {
    assert.ok(m[1].startsWith(base + "-"), file + " id 要带文件名前缀：" + m[1]);
  }

  // 颜色：只允许 var(--x, #HEX) / currentColor / 黑白半透明 / none / transparent / url(#...)
  const usedVars = new Set();
  for (const m of svg.matchAll(/var\(\s*--([a-z]+)\s*,\s*(#[0-9a-fA-F]{6})\s*\)/g)) {
    if (!VARS.has(m[1])) fail("用了不在六个变量里的 --" + m[1]);
    if (!DEFAULT_HEX.has(m[2].toUpperCase())) fail("默认色不在全库统一的那套里：" + m[2]);
    usedVars.add(m[1]);
  }
  for (const m of svg.matchAll(/var\(\s*--([a-z]+)\s*\)/g)) {
    if (!VARS.has(m[1])) fail("用了不在六个变量里的 --" + m[1]);
    usedVars.add(m[1]);
  }
  if (usedVars.size > 3) fail("一个贴纸最多用三个颜色变量，用了 " + usedVars.size + " 个：" + [...usedVars].join("、"));
  for (const m of svg.matchAll(/#[0-9a-fA-F]{3,8}\b/g)) {
    if (!DEFAULT_HEX.has(m[0].toUpperCase())) fail("出现未定义的颜色 " + m[0]);
  }
  // 颜色属性里出现裸颜色词 / rgb()（黑白半透明 rgba 除外）一律不许
  for (const m of svg.matchAll(/(?:fill|stroke|stop-color|color)\s*=\s*"([^"]*)"/g)) {
    const v = m[1].trim();
    if (v === "" || v === "none" || v === "currentColor" || v === "transparent") continue;
    if (/^url\(#[\w-]+\)$/.test(v)) continue;
    if (/^var\(--(?:bg|fg|primary|secondary|accent|muted),\s*#[0-9a-fA-F]{6}\)$/.test(v)) continue;
    if (/^rgba\((?:0,\s*0,\s*0|255,\s*255,\s*255),\s*[\d.]+\)$/.test(v)) continue;
    fail("颜色写法不对：" + m[0]);
  }
  for (const m of svg.matchAll(/style\s*=\s*"([^"]*)"/g)) {
    if (/#[0-9a-fA-F]{3,8}\b/.test(m[1]) && ![...m[1].matchAll(/#[0-9a-fA-F]{3,8}\b/g)].every((x) => DEFAULT_HEX.has(x[0].toUpperCase()))) {
      fail("style 里出现未定义的颜色：" + m[1]);
    }
    if (/rgba\(/.test(m[1]) && !/rgba\((?:0,\s*0,\s*0|255,\s*255,\s*255),\s*[\d.]+\)/.test(m[1])) fail("style 里 rgba 只能是黑白半透明：" + m[1]);
  }

  const meta = readMetadata(svg, file);
  if (typeof meta.name !== "string" || [...meta.name].length === 0 || [...meta.name].length > 6) fail("元数据 name 必须是 1～6 个字");
  if (!Array.isArray(meta.tags) || meta.tags.length < 3 || meta.tags.length > 6 || meta.tags.some((t) => typeof t !== "string" || !t)) fail("元数据 tags 必须是 3～6 个中文关键词");
  if (!["hand", "clean"].includes(meta.style)) fail("元数据 style 只能是 hand 或 clean");
  if (!["stroke", "fill"].includes(meta.recolor)) fail("元数据 recolor 只能是 stroke 或 fill");
  if (!["square", "wide", "tall"].includes(meta.ratio)) fail("元数据 ratio 只能是 square、wide 或 tall");
  if (meta.recolor === "stroke" && !/currentColor/.test(svg)) fail("recolor 是 stroke，但文件里没用 currentColor");
  if (meta.recolor === "fill" && !/var\(--|fill="currentColor"/.test(svg)) fail("recolor 是 fill，但文件里没用颜色变量或 currentColor 填充");
  if (viewBox) {
    const expect = ratioOf(viewBox[2], viewBox[3]);
    if (meta.ratio !== expect) fail("ratio 是 " + meta.ratio + "，但 viewBox " + viewBox[2] + "x" + viewBox[3] + " 应该是 " + expect);
    var vbArr = [viewBox[0], viewBox[1], viewBox[2], viewBox[3]];
    if (meta.textArea) {
      const ta = meta.textArea;
      if (!Array.isArray(ta) || ta.length !== 4 || ta.some((n) => typeof n !== "number")) fail("textArea 必须是 [x,y,w,h] 四个数字");
      else if (ta[0] < viewBox[0] || ta[1] < viewBox[1] || ta[0] + ta[2] > viewBox[0] + viewBox[2] || ta[1] + ta[3] > viewBox[1] + viewBox[3] || ta[2] <= 0 || ta[3] <= 0) {
        fail("textArea 超出 viewBox：" + JSON.stringify(ta));
      }
    }
    if (TEXT_AREA_CATEGORIES.has(cat) && !meta.textArea) fail("label/badge/frame 类必须写 textArea（可放字的区域）");
    if (meta.tile !== undefined && (cat !== "texture" || meta.tile !== true)) fail("tile:true 只能写在 texture 类上");
    if (cat === "texture" && meta.tile !== true) fail("texture 类必须写 \"tile\":true");
  }
  if (problems.length) throw new Error(file + "\n  - " + problems.join("\n  - "));
  return { id: base, category: cat, name: meta.name, tags: meta.tags, style: meta.style, recolor: meta.recolor, ratio: meta.ratio, viewBox: vbArr, ...(meta.textArea ? { textArea: meta.textArea } : {}), ...(meta.tile ? { tile: true } : {}) };
}

function paletteOf(styleId, paletteName) {
  const styles = JSON.parse(fs.readFileSync(path.join(designDir, "styles.json"), "utf8"));
  const style = styles.styles.find((s) => s.id === styleId);
  if (!style) return null;
  if (paletteName) return style.palettes.find((p) => p.name === paletteName) || style.palettes[0];
  return style.palettes[0];
}

async function renderOverview(browser, stickers, palette, outPath) {
  const vars = ["bg", "fg", "primary", "secondary", "accent", "muted"]
    .map((k) => "--" + k + ":" + palette[k]).join(";");
  const groups = CATEGORIES.map(([id, name]) => ({
    id, name, items: stickers.filter((s) => s.category === id),
  })).filter((g) => g.items.length);
  const cellW = 220, cellH = 168;
  const cards = groups.map((g) => {
    const items = g.items.map((s) => {
      const svg = fs.readFileSync(path.join(stickersDir, s.id + ".svg"), "utf8");
      const [, , w, h] = s.viewBox;
      const scale = Math.min((cellW - 16) / w, (cellH - 30) / h);
      const dw = Math.round(w * scale), dh = Math.round(h * scale);
      return '<figure><div class="cell" style="width:' + dw + 'px;height:' + dh + 'px">' + svg + '</div><figcaption>' + s.id + "</figcaption></figure>";
    }).join("");
    return "<h2>" + g.name + " · " + g.id + "（" + g.items.length + "）</h2><div class=\"grid\">" + items + "</div>";
  }).join("");
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 }, deviceScaleFactor: 1 });
  await page.setContent("<!doctype html><meta charset=\"utf-8\"><style>"
    + "body{margin:0;background:#f2f0ec;font-family:sans-serif;color:#222}"
    + "main{padding:24px}.wrap{" + vars + ";color:var(--fg)}"
    + "h2{font-size:20px;margin:28px 0 12px}h2:first-child{margin-top:0}"
    + ".grid{display:grid;grid-template-columns:repeat(4," + cellW + "px);gap:16px}"
    + "figure{margin:0;background:#fff;border:1px solid #ddd;border-radius:8px;padding:8px 0 0;overflow:hidden}"
    + ".cell{margin:0 auto;display:flex;align-items:center;justify-content:center}.cell svg{width:100%;height:100%}"
    + "figcaption{padding:6px 4px 8px;text-align:center;font-size:12px;color:#555;border-top:1px solid #eee;margin-top:8px}"
    + "</style><main><div class=\"wrap\">" + cards + "</div></main>");
  await page.screenshot({ path: outPath, fullPage: true });
  await page.close();
}

async function main() {
  if (!fs.existsSync(stickersDir)) {
    console.error("还没有 stickers 目录：" + stickersDir);
    process.exitCode = 1;
    return;
  }
  const files = fs.readdirSync(stickersDir).filter((n) => n.endsWith(".svg")).sort();
  if (files.length === 0) {
    console.error("stickers 目录是空的");
    process.exitCode = 1;
    return;
  }
  if (writeIndex) {
    const all = files.map((f) => checkStatic(f));
    const index = {
      version: 1,
      categories: CATEGORIES.map(([id, name]) => ({ id, name })),
      stickers: all.sort((a, b) => (CATEGORIES.findIndex(([id]) => id === a.category) - CATEGORIES.findIndex(([id]) => id === b.category)) || (a.id < b.id ? -1 : 1)),
    };
    if (args.includes("--print-index")) {
      // 沙箱有时拦进程内写文件时用：把 JSON 打到 stdout，再由 shell 重定向落盘；
      // 只打 JSON 就退出，后面的“通过 ……”行不要混进 stdout
      process.stdout.write(JSON.stringify(index, null, 2) + "\n");
      console.error("stickers.json 已按 SVG 元数据重新生成：" + all.length + " 个");
      return;
    } else {
      fs.writeFileSync(indexPath, JSON.stringify(index, null, 2) + "\n");
    }
    console.error("stickers.json 已按 SVG 元数据重新生成：" + all.length + " 个");
  }
  let index = null;
  if (fs.existsSync(indexPath)) index = JSON.parse(fs.readFileSync(indexPath, "utf8"));
  if (!writeIndex && !index) {
    console.log("还没有 stickers.json，先只做静态检查（最后跑 --write-index 生成）");
  }
  if (!writeIndex && index) {
    const filesToCheck = only.length ? files.filter((f) => only.some((o) => f.includes(o))) : files;
    const indexToCheck = only.length ? index.stickers.filter((e) => only.some((o) => e.id.includes(o))) : index.stickers;
    assert.equal(filesToCheck.length, indexToCheck.length, "SVG 文件数量与 stickers.json 不一致（跑 --write-index）");
  }

  const stickers = [];
  const failures = [];
  for (const file of files) {
    if (only.length && !only.some((o) => file.includes(o))) continue;
    try {
      const entry = checkStatic(file);
      if (index) {
        const found = index.stickers.find((e) => e.id === entry.id);
        assert.ok(found, file + " 未列入 stickers.json");
        assert.deepEqual(found, entry, file + " 与 stickers.json 元数据不一致（跑 --write-index）");
      }
      stickers.push(entry);
      console.log("通过 " + entry.id);
    } catch (e) {
      failures.push(e.message);
      console.log("没通过 " + file);
    }
  }
  if (failures.length) {
    console.error("\n没通过的：\n" + failures.join("\n"));
    process.exitCode = 1;
    return;
  }
  console.log("静态检查通过：" + stickers.length + " 个贴纸");
  if (staticOnly) return;

  const { chromium } = loadPlaywright();
  const browser = await chromium.launch({ headless: true });
  try {
    fs.mkdirSync(previewsDir, { recursive: true });
    const palettes = [
      ["_all.png", DEFAULTS],
      ["_all-2.png", paletteOf("cream", "奶油焦糖") || DEFAULTS],
      ["_all-3.png", paletteOf("dopamine", "粉绿") || DEFAULTS],
    ];
    for (const [name, palette] of palettes) {
      await renderOverview(browser, stickers, palette, path.join(previewsDir, name));
      console.log("总览图已生成：design-previews/stickers/" + name);
    }
  } finally {
    await browser.close();
  }
  console.log("全部通过：" + stickers.length + " 个贴纸，预览在 design-previews/stickers/");
}

main().catch((e) => { console.error(e.stack || e.message); process.exitCode = 1; });
