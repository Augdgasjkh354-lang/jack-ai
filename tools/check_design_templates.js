#!/usr/bin/env node
"use strict";
// 设计模板校验（规格见 docs/建议/设计模板_给Muse的提示词.md）。
//   node tools/check_design_templates.js                 全部检查 + 出预览图（design-previews/）
//   node tools/check_design_templates.js --static-only   只查文件本身（不开浏览器）
//   node tools/check_design_templates.js --write-index   先按模板里的元数据重新生成 index.json，再检查
//   node tools/check_design_templates.js xhs-bold-cover  只查名字里带这个的模板
// 运行时检查用的是 App 真正的填充脚本 fill.js（含审美检查），字体和手机上一样从 jsDelivr 加载，所以要联网。

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const assert = require("node:assert/strict");

const root = path.resolve(__dirname, "..");
const designDir = path.join(root, "app/src/main/assets/design");
const templatesDir = path.join(designDir, "templates");
const indexPath = path.join(designDir, "index.json");
const iconsPath = path.join(root, "app/src/main/resources/ppt/tabler-icons.json");
const previewsDir = path.join(root, "design-previews");
const args = process.argv.slice(2);
const staticOnly = args.includes("--static-only");
const writeIndex = args.includes("--write-index");
const only = args.filter((a) => !a.startsWith("--"));
const paletteNames = ["bg", "fg", "primary", "secondary", "accent", "muted"];
const SIZES = { xhs: [[1080, 1440]], square: [[1080, 1080]], poster: [[1080, 1920], [1240, 1754]], invite: [[1080, 1620]], card: [[1050, 600]], cover: [[900, 383]], long: [[1080, 0]] };
const ROLES = ["cover", "list", "steps", "compare", "grid", "quote", "photo", "data", "qa", "ending", "content"];
// fill.js 自动给列表项填的位置：不用在元数据里声明
const AUTO_SLOTS = new Set(["num", "index"]);
const fonts = JSON.parse(fs.readFileSync(path.join(designDir, "fonts.json"), "utf8"));
const styles = JSON.parse(fs.readFileSync(path.join(designDir, "styles.json"), "utf8"));
const SYSTEM_FONTS = ["noto sans cjk sc", "noto serif cjk sc", "source han sans sc", "source han serif sc", "pingfang sc", "songti sc", "sans-serif", "serif", "monospace", "inherit"];
const allowedFamilies = new Set([...SYSTEM_FONTS, ...fonts.map((f) => f.family.toLowerCase())]);

function readMetadata(html, file) {
  const match = html.match(/<meta name="kavi-template" content='([^']*)'>/);
  assert.ok(match, file + " 缺少 kavi-template 元数据");
  return JSON.parse(match[1]);
}

function normalize(value) {
  if (Array.isArray(value)) return value.map(normalize);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map((key) => [key, normalize(value[key])]));
  return value;
}

function luminance(hex) {
  const rgb = hex.match(/[0-9a-f]{2}/gi).map((part) => parseInt(part, 16) / 255);
  const linear = rgb.map((c) => c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
}
function contrast(a, b) {
  const v = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (v[0] + 0.05) / (v[1] + 0.05);
}

function checkStatic(entry, html, file, icons) {
  const meta = readMetadata(html, file);
  assert.deepEqual(normalize(meta), normalize(entry), file + " 与 index.json 元数据不一致（改了模板就跑 --write-index）");
  assert.equal(meta.id, path.basename(file, ".html"), file + " 的 id 与文件名不一致");
  assert.ok(SIZES[meta.category], file + " 分类不对：只能是 " + Object.keys(SIZES).join("、"));
  assert.ok(SIZES[meta.category].some(([w, h]) => w === meta.size[0] && h === meta.size[1]), file + " 尺寸不符合分类 " + meta.category);
  if (meta.set || meta.role || meta.look) {
    assert.ok(meta.set && meta.role && meta.look, file + " set、role、look 要一起写");
    assert.ok(ROLES.includes(meta.role), file + " role 只能是 " + ROLES.join("、"));
    assert.ok(styles.styles.some((s) => s.id === meta.look), file + " look 不是风格库里的风格 id：" + meta.look);
  }
  assert.deepEqual(Object.keys(meta.colors).sort(), paletteNames.slice().sort(), file + " 应包含六个配色变量");
  assert.ok(contrast(meta.colors.fg, meta.colors.bg) >= 4.5, file + " 正文字与背景对比度不足 4.5");
  assert.ok(contrast(meta.colors.muted, meta.colors.bg) >= 4.5, file + " 次要文字与背景对比度不足 4.5");
  assert.ok(contrast(meta.colors.primary, meta.colors.bg) >= 3, file + " 主色与背景对比度不足 3");
  assert.doesNotMatch(html, /<script\b|<link\b|https?:\/\/(?!www\.w3\.org)|@import\b|\b(?:src|href)\s*=/i, file + " 不得含脚本、外链或外部资源");
  for (const m of html.matchAll(/url\s*\(\s*([^)]*)\)/gi)) assert.match(m[1].trim(), /^['"]?#[\w-]+['"]?$/, file + " url() 只能指向页面里的 SVG（#id）：" + m[0]);

  const style = html.match(/<style>([\s\S]*?)<\/style>/i);
  assert.ok(style, file + " 缺少内嵌 CSS");
  const css = style[1];
  const canvasRule = css.match(/\.canvas\s*\{([^}]*)\}/);
  assert.ok(canvasRule, file + " 缺少 .canvas 规则");
  for (const name of paletteNames) {
    const variable = canvasRule[1].match(new RegExp("--" + name + "\\s*:\\s*(#[0-9a-f]{6})", "i"));
    assert.ok(variable, file + " .canvas 缺少 --" + name);
    assert.equal(variable[1].toUpperCase(), meta.colors[name].toUpperCase(), file + " 的 " + name + " 与元数据颜色不一致");
  }
  assert.match(canvasRule[1], new RegExp("width\\s*:\\s*" + meta.size[0] + "px", "i"), file + " 画布宽度错误");
  if (meta.size[1]) {
    assert.match(canvasRule[1], new RegExp("height\\s*:\\s*" + meta.size[1] + "px", "i"), file + " 画布高度错误");
    assert.match(canvasRule[1], /overflow\s*:\s*hidden/i, file + " 画布需裁切溢出");
  }
  // 字体：只能用 fonts.json 里的和系统字体，每个都要有系统字体兜底
  for (const m of css.matchAll(/(?:font-family|--font-[\w-]+)\s*:\s*([^;}]+)/gi)) {
    if (/^var\(/.test(m[1].trim())) continue;
    const names = m[1].split(",").map((s) => s.trim().replace(/^["']|["']$/g, "").toLowerCase());
    for (const n of names) assert.ok(allowedFamilies.has(n), file + " 用了不在 fonts.json 里的字体：" + n);
    assert.ok(names.some((n) => SYSTEM_FONTS.includes(n)), file + " 字体要用系统字体兜底：" + m[1].trim());
  }
  for (const m of css.matchAll(/font-weight\s*:\s*(\w+)/gi)) assert.match(m[1], /^[1-9]00$/, file + " 字重写成 100～900 的整百数：" + m[1]);

  const allowedColors = new Set(Object.values(meta.colors).map((v) => v.toUpperCase()));
  for (const m of css.matchAll(/#[0-9a-f]{3,8}\b/gi)) assert.ok(allowedColors.has(m[0].toUpperCase()), file + " 出现未定义的颜色 " + m[0]);
  const colorDeclarations = css.matchAll(/(?:^|[;{}])\s*(?:color|background(?:-color)?|border(?:-(?:top|right|bottom|left))?(?:-color)?|box-shadow|outline|text-shadow|-webkit-text-stroke(?:-color)?|fill|stroke)\s*:\s*([^;{}]+)/gim);
  for (const m of colorDeclarations) {
    const v = m[1].trim().toLowerCase();
    const ok = /var\(--(?:bg|fg|primary|secondary|accent|muted)\)/.test(v) || v.includes("color-mix(") || v.includes("currentcolor") ||
      /^(transparent|none|0)$/.test(v) || /rgba\((0,\s*0,\s*0|255,\s*255,\s*255),\s*[\d.]+\)/.test(v) || /^\d+px solid transparent$/.test(v);
    assert.ok(ok, file + " 有颜色没用配色变量（或黑白半透明）：" + m[1].trim());
  }

  const usedIcons = [...html.matchAll(/data-icon="([^"]+)"/g)].map((m) => m[1]);
  for (const icon of usedIcons) assert.ok(Object.hasOwn(icons, icon), file + " 图标不存在: " + icon);

  const declared = new Set([...meta.slots.map((s) => s.name), ...meta.lists.flatMap((l) => l.slots.map((s) => s.name)), ...meta.images.map((i) => i.name)]);
  const slotElements = [...html.matchAll(/<([a-z][\w-]*)\b([^>]*\bdata-slot="([^"]+)"[^>]*)>([\s\S]*?)<\/\1>/gi)];
  assert.equal(slotElements.length, [...html.matchAll(/\bdata-slot="/g)].length, file + " 有无法解析的文字位");
  const imageNames = new Set(meta.images.map((i) => i.name));
  for (const [, , attrs, name, inner] of slotElements) {
    if (AUTO_SLOTS.has(name)) continue;
    assert.ok(declared.has(name), file + " 有未声明的文字位 " + name);
    if (/\bdata-kind="image"/i.test(attrs)) { assert.ok(imageNames.has(name), file + " 图片位未列入 images: " + name); continue; }
    assert.ok(!imageNames.has(name), file + " 图片位应使用 data-kind=\"image\": " + name);
    const max = attrs.match(/\bdata-max="(\d+)"/i);
    assert.ok(max && Number(max[1]) > 0, file + " 文字位 " + name + " 缺少有效 data-max");
    const example = inner.replace(/<[^>]*>/g, "").trim();
    assert.ok(example.length > 0, file + " 文字位 " + name + " 缺少中文示例");
    assert.ok(Array.from(example).length <= Number(max[1]), file + " 文字位 " + name + " 示例超出 data-max");
  }
  for (const l of meta.lists) {
    const open = html.match(new RegExp("<([a-z][a-z0-9-]*)[^>]*data-list=\"" + l.name + "\"[^>]*>", "i"));
    assert.ok(open, file + " 缺少列表 " + l.name);
    assert.match(open[0], new RegExp("data-max=\"" + l.max + "\""), file + " 列表 " + l.name + " 要写 data-max=\"" + l.max + "\"");
    const items = html.slice(open.index).match(/\bdata-item\b/g) || [];
    assert.ok(items.length >= Math.max(3, l.min) && items.length <= l.max, file + " 列表示例应有 " + Math.max(3, l.min) + "～" + l.max + " 项");
  }
  assert.equal([...html.matchAll(/<(?!body\b|svg\b|rect\b)[^>]+\bstyle=/gi)].length, 0, file + " 不要用内联样式");
  // 规格里点过名的坑：span[data-slot]{display:inline-block} 的优先级比单个类名高，会把 .xx{display:block/flex} 盖掉
  if (/span\[data-slot\]\s*\{\s*display\s*:\s*inline-block/.test(css)) {
    for (const m of slotElements) {
      if (m[1] !== "span") continue;
      const cls = (m[2].match(/class="([^"]+)"/) || [])[1];
      if (!cls) continue;
      for (const c of cls.split(/\s+/)) {
        const rule = css.match(new RegExp("(^|\\})\\s*\\." + c + "\\s*\\{([^}]*)\\}"));
        if (rule && /display\s*:\s*(block|flex|grid)/.test(rule[2])) assert.fail(file + " ." + c + " 写的 display 会被 span[data-slot]{display:inline-block} 盖掉：选择器前面加上父级类名");
      }
    }
  }
  return meta;
}

// —— 运行时：用 App 的 fill.js 填内容，字体走 jsDelivr（和手机上一样） ——
const cacheDir = path.join(os.tmpdir(), "kavi-design-fonts");
async function get(url) {
  const f = path.join(cacheDir, url.replace(/[^a-z0-9.]+/gi, "_"));
  if (fs.existsSync(f)) return fs.readFileSync(f);
  const r = await fetch(url);
  if (!r.ok) throw new Error(url + " " + r.status);
  const b = Buffer.from(await r.arrayBuffer());
  fs.mkdirSync(cacheDir, { recursive: true });
  fs.writeFileSync(f, b);
  return b;
}
async function fontCss(font, alias) {
  if (font.bundled) return "@font-face{font-family:'" + (alias || font.family) + "';font-weight:100 900;font-display:block;src:url(/fonts/app/" + path.basename(font.bundled) + ") format('woff2')}\n";
  let out = "";
  for (const name of font.css) {
    let t = (await get("https://cdn.jsdelivr.net/npm/" + font.pkg + "/" + name)).toString();
    t = t.replace(/,\s*url\([^)]*\.woff\)\s*format\(['"]woff['"]\)/g, "")
      .replace(/url\(\s*['"]?\.\/([^'")]+)['"]?\s*\)/g, (m, p) => "url(/fonts/npm/" + font.pkg + "/" + p + ")")
      .replace(/font-display:\s*swap/g, "font-display: block");
    if (alias) t = t.split("'" + font.family + "'").join("'" + alias + "'");
    out += t + "\n";
  }
  return out;
}
async function preparePage(html, w, h) {
  const lower = html.toLowerCase();
  let css = "";
  for (const f of fonts) if (lower.includes(f.family.toLowerCase())) css += await fontCss(f);
  // 手机自带的黑体、宋体：电脑上没有，用同一套思源代替
  const sans = fonts.find((f) => f.id === "noto-sans"), serif = fonts.find((f) => f.id === "noto-serif");
  css += await fontCss({ ...sans, css: ["400.css", "700.css"] }, "Noto Sans CJK SC") + await fontCss({ ...serif, css: ["400.css", "700.css"] }, "Noto Serif CJK SC");
  html = html.replace(/<meta[^>]+name\s*=\s*["']viewport["'][^>]*>/i, "").replace(/<html/i, '<html data-kavi-size="' + w + "x" + h + '"');
  const head = '<meta name="viewport" content="width=' + w + '"><style>html{-webkit-text-size-adjust:100%}body{margin:0}.canvas{width:' + w + "px;" + (h ? "height:" + h + "px;overflow:hidden" : "") + "}</style><style>" + css + "</style><script>" + fs.readFileSync(path.join(designDir, "fill.js"), "utf8") + "</script>";
  return html.replace(/<head[^>]*>/i, (x) => x + head);
}
async function route(page, html) {
  await page.route("https://design.jack.local/**", async (r) => {
    const u = new URL(r.request().url());
    if (u.pathname === "/") return r.fulfill({ contentType: "text/html", body: html });
    if (u.pathname.startsWith("/fonts/app/")) return r.fulfill({ contentType: "font/woff2", body: fs.readFileSync(path.join(designDir, "fonts", path.basename(u.pathname))) });
    if (u.pathname.startsWith("/fonts/npm/")) {
      try { return r.fulfill({ contentType: "font/woff2", body: await get("https://cdn.jsdelivr.net/npm/" + u.pathname.slice(11)) }); } catch (e) { return r.fulfill({ status: 404, body: "" }); }
    }
    return r.fulfill({ status: 404, body: "" });
  });
}
function scenarioData(meta, scenario, icons) {
  const iconSvg = {};
  for (const name of Object.keys(icons)) iconSvg[name] = '<svg viewBox="0 0 24 24" width="100%" height="100%" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/></svg>';
  const data = { vars: {}, slots: {}, lists: {}, images: {}, icons: iconSvg };
  if (scenario.maxText) for (const s of meta.slots) data.slots[s.name] = "美".repeat(s.max);
  for (const l of meta.lists) {
    if (!scenario.listCount && !scenario.maxText) continue;
    const n = scenario.listCount === "min" ? Math.max(1, l.min) : scenario.listCount === "max" ? l.max : 3;
    data.lists[l.name] = Array.from({ length: n }, (_, i) => Object.fromEntries(l.slots.map((s) => [s.name, scenario.maxText ? "美".repeat(s.max) : "第" + (i + 1) + "项内容"])));
  }
  return data;
}
async function run(page, html, meta, data) {
  await page.goto("https://design.jack.local/");
  await page.evaluate((d) => window.kaviFill(d), data);
  await page.waitForFunction(() => window.__kaviDone, null, { timeout: 90000 });
  const report = JSON.parse(await page.evaluate(() => window.__kaviDone));
  // 安全边距：文字离画布边至少 60px（故意出血的 data-bleed 不算）
  report.margin = await page.evaluate(() => {
    const c = document.querySelector(".canvas").getBoundingClientRect(), out = [];
    const fixed = !/x0$/.test(document.documentElement.getAttribute("data-kavi-size"));
    for (const el of document.querySelectorAll("[data-slot]:not([data-kind=image])")) {
      if (el.closest("[data-bleed]") || getComputedStyle(el).display === "none") continue;
      const r = el.getBoundingClientRect();
      if (r.width < 1) continue;
      const m = c.width < 1000 ? 36 : 60;
      if (r.left < c.left + m - 0.5 || r.top < c.top + m - 0.5 || r.right > c.right - m + 0.5 || (fixed && r.bottom > c.bottom - m + 0.5)) out.push(el.getAttribute("data-slot"));
    }
    return out;
  });
  return report;
}

async function main() {
  const icons = JSON.parse(fs.readFileSync(iconsPath, "utf8"));
  const files = fs.readdirSync(templatesDir).filter((n) => n.endsWith(".html")).sort();
  if (writeIndex) {
    const index = files.map((f) => readMetadata(fs.readFileSync(path.join(templatesDir, f), "utf8"), f));
    fs.writeFileSync(indexPath, JSON.stringify(index, null, 2) + "\n");
    console.log("index.json 已按模板元数据重新生成：" + index.length + " 个");
  }
  const index = JSON.parse(fs.readFileSync(indexPath, "utf8"));
  assert.equal(files.length, index.length, "模板文件数量与 index.json 不一致（跑 --write-index）");
  assert.equal(new Set(index.map((e) => e.id)).size, index.length, "index.json 有重复 id");

  const templates = [];
  for (const file of files) {
    if (only.length && !only.some((o) => file.includes(o))) continue;
    const html = fs.readFileSync(path.join(templatesDir, file), "utf8");
    const meta = readMetadata(html, file);
    const entry = index.find((e) => e.id === meta.id);
    assert.ok(entry, file + " 未列入 index.json");
    checkStatic(entry, html, file, icons);
    templates.push({ file, html, meta });
  }
  if (staticOnly) { console.log("静态检查通过：" + templates.length + " 个模板"); return; }

  const { chromium } = require("playwright");
  const browser = await chromium.launch({ headless: true });
  const failures = [];
  fs.mkdirSync(previewsDir, { recursive: true });
  try {
    for (const { file, html, meta } of templates) {
      const [w, h] = meta.size;
      const prepared = await preparePage(html, w, h);
      const page = await browser.newPage({ viewport: { width: w, height: h || 1920 }, deviceScaleFactor: 1 });
      await route(page, prepared);
      const scenarios = [
        { name: "示例", strict: true },
        { name: "文字满字数", maxText: true },
        { name: "列表最少项", listCount: "min" },
        { name: "列表最多项且满字数", listCount: "max", maxText: true },
      ];
      for (const sc of scenarios) {
        const r = await run(page, html, meta, scenarioData(meta, sc, icons));
        const bad = [...r.problems, ...r.margin.map((s) => "「" + s + "」离边不到 60px"), ...(sc.strict ? r.advice.map((a) => "审美：" + a) : [])];
        if (bad.length) failures.push(file + "「" + sc.name + "」\n  - " + bad.join("\n  - "));
        if (sc.strict) {
          const height = h || r.height;
          await page.setViewportSize({ width: w, height });
          await page.screenshot({ path: path.join(previewsDir, meta.id + ".png"), clip: { x: 0, y: 0, width: w, height } });
        }
      }
      console.log((failures.some((f) => f.startsWith(file)) ? "没通过 " : "通过 ") + meta.id);
      await page.close();
    }
    // 总览：所有模板的预览拼一张
    const all = fs.readdirSync(templatesDir).filter((n) => n.endsWith(".html")).sort().map((f) => readMetadata(fs.readFileSync(path.join(templatesDir, f), "utf8"), f))
      .filter((m) => fs.existsSync(path.join(previewsDir, m.id + ".png")));
    const cols = Math.min(4, all.length), cw = 300, ch = 400, gap = 20;
    const cards = all.map((m) => '<figure><img src="data:image/png;base64,' + fs.readFileSync(path.join(previewsDir, m.id + ".png")).toString("base64") + '"><figcaption>' + m.name + "</figcaption></figure>").join("");
    const ov = await browser.newPage({ viewport: { width: cols * cw + (cols + 1) * gap, height: 800 }, deviceScaleFactor: 1 });
    await ov.setContent('<!doctype html><meta charset="utf-8"><style>body{margin:0;background:#eee;font-family:sans-serif}main{display:grid;grid-template-columns:repeat(' + cols + "," + cw + "px);gap:" + gap + "px;padding:" + gap + "px}figure{margin:0}img{display:block;width:" + cw + "px;height:" + ch + "px;object-fit:contain;background:#fff}figcaption{padding-top:8px;text-align:center;font-size:15px}</style><main>" + cards + "</main>");
    await ov.screenshot({ path: path.join(previewsDir, "_all.png"), fullPage: true });
    await ov.close();
  } finally {
    await browser.close();
  }
  if (failures.length) {
    console.error("\n没通过的：\n" + failures.join("\n"));
    process.exitCode = 1;
  } else console.log("全部通过：" + templates.length + " 个模板，预览在 design-previews/");
}

main().catch((e) => { console.error(e.stack || e.message); process.exitCode = 1; });
