#!/usr/bin/env node
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const { chromium } = require("playwright");

const root = path.resolve(__dirname, "..");
const templatesDir = path.join(root, "app/src/main/assets/design/templates");
const indexPath = path.join(root, "app/src/main/assets/design/index.json");
const iconsPath = path.join(root, "app/src/main/resources/ppt/tabler-icons.json");
const previewsDir = path.join(root, "design-previews");
const staticOnly = process.argv.includes("--static-only");
const paletteNames = ["bg", "fg", "primary", "secondary", "accent", "muted"];

function readMetadata(html, file) {
  const match = html.match(/<meta name="kavi-template" content='([^']*)'>/);
  assert.ok(match, file + " 缺少 kavi-template 元数据");
  return JSON.parse(match[1]);
}

function normalize(value) {
  if (Array.isArray(value)) return value.map(normalize);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, normalize(value[key])]));
  }
  return value;
}

function luminance(hex) {
  const rgb = hex.match(/[0-9a-f]{2}/gi).map((part) => parseInt(part, 16) / 255);
  const linear = rgb.map((channel) => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4);
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
}

function contrast(a, b) {
  const values = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (values[0] + 0.05) / (values[1] + 0.05);
}

function checkStatic(entry, html, file, icons) {
  const meta = readMetadata(html, file);
  assert.deepEqual(normalize(meta), normalize(entry), file + " 与 index.json 元数据不一致");
  assert.equal(meta.id, path.basename(file, ".html"), file + " 的 id 与文件名不一致");
  assert.equal(meta.category, "xhs", file + " 分类应为 xhs");
  assert.deepEqual(meta.size, [1080, 1440], file + " 尺寸应为 1080×1440");
  assert.deepEqual(Object.keys(meta.colors).sort(), paletteNames.slice().sort(), file + " 应包含六个配色变量");
  assert.ok(contrast(meta.colors.fg, meta.colors.bg) >= 4.5, file + " 正文字与背景对比度不足");
  assert.ok(contrast(meta.colors.muted, meta.colors.bg) >= 4.5, file + " 次要文字与背景对比度不足");
  assert.ok(contrast(meta.colors.primary, meta.colors.bg) >= 4.5, file + " 主色与背景对比度不足");
  assert.match(html, /<body style="margin:0">/i, file + " body 必须设置 margin:0");
  assert.doesNotMatch(html, /<script\b|<link\b|https?:\/\/|@import\b|url\s*\(|\b(?:src|href)\s*=/i, file + " 不得含脚本、外链或外部资源");

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
  assert.match(canvasRule[1], /--font-sans\s*:\s*"Noto Sans CJK SC","Source Han Sans SC","PingFang SC",sans-serif/i, file + " 系统黑体字体栈不符合约定");
  assert.match(canvasRule[1], /--font-serif\s*:\s*"Noto Serif CJK SC","Source Han Serif SC","Songti SC",serif/i, file + " 系统宋体字体栈不符合约定");
  assert.match(canvasRule[1], /width\s*:\s*1080px/i, file + " 画布宽度错误");
  assert.match(canvasRule[1], /height\s*:\s*1440px/i, file + " 画布高度错误");
  assert.match(canvasRule[1], /overflow\s*:\s*hidden/i, file + " 画布需裁切溢出");
  for (const match of css.matchAll(/font-weight\s*:\s*(\d+)/gi)) {
    assert.ok(["400", "700"].includes(match[1]), file + " 只允许 400 和 700 字重");
  }

  const allowedColors = new Set(Object.values(meta.colors).map((value) => value.toUpperCase()));
  for (const match of css.matchAll(/#[0-9a-f]{3,8}\b/gi)) {
    assert.ok(allowedColors.has(match[0].toUpperCase()), file + " 出现未定义的颜色 " + match[0]);
  }
  const colorDeclarations = css.matchAll(/(?:^|[;{}])\s*(?:color|background(?:-color)?|border(?:-(?:top|right|bottom|left))?(?:-color)?|box-shadow|outline|text-shadow)\s*:\s*([^;{}]+)/gim);
  for (const match of colorDeclarations) {
    const value = match[1].trim().toLowerCase();
    const usesPalette = /var\(--(?:bg|fg|primary|secondary|accent|muted)\)/.test(value) || value.includes("color-mix(") || value.includes("currentcolor") || value === "transparent" || value === "none";
    assert.ok(usesPalette, file + " 有颜色未使用变量或变量派生值: " + match[1].trim());
  }

  const usedIcons = [...html.matchAll(/data-icon="([^"]+)"/g)].map((match) => match[1]);
  for (const icon of usedIcons) assert.ok(Object.hasOwn(icons, icon), file + " 图标不存在: " + icon);

  const declaredSlots = new Set([
    ...meta.slots.map((slot) => slot.name),
    ...meta.lists.flatMap((list) => list.slots.map((slot) => slot.name)),
    ...meta.images.map((image) => image.name),
  ]);
  const slotElements = [...html.matchAll(/<([a-z][\w-]*)\b([^>]*\bdata-slot="([^"]+)"[^>]*)>([\s\S]*?)<\/\1>/gi)];
  assert.equal(slotElements.length, [...html.matchAll(/\bdata-slot="/g)].length, file + " 有无法解析的文字位");
  const imageNames = new Set(meta.images.map((image) => image.name));
  for (const match of slotElements) {
    const [, , attributes, name, inner] = match;
    assert.ok(declaredSlots.has(name), file + " 有未声明的文字位 " + name);
    if (/\bdata-kind="image"/i.test(attributes)) {
      assert.ok(imageNames.has(name), file + " 图片位未列入 images: " + name);
      continue;
    }
    assert.ok(!imageNames.has(name), file + " 图片位应使用 data-kind=\"image\": " + name);
    const max = attributes.match(/\bdata-max="(\d+)"/i);
    assert.ok(max && Number(max[1]) > 0, file + " 文字位 " + name + " 缺少有效 data-max");
    const example = inner.replace(/<[^>]*>/g, "").trim();
    assert.ok(example.length > 0, file + " 文字位 " + name + " 缺少中文示例");
    assert.ok(Array.from(example).length <= Number(max[1]), file + " 文字位 " + name + " 示例超出 data-max");
  }
  for (const listMeta of meta.lists) {
    const open = html.match(new RegExp("<([a-z][a-z0-9-]*)[^>]*data-list=\"" + listMeta.name + "\"[^>]*>", "i"));
    assert.ok(open, file + " 缺少列表 " + listMeta.name);
    const tail = html.slice(open.index + open[0].length);
    const close = tail.search(new RegExp("</" + open[1] + ">", "i"));
    assert.ok(close >= 0, file + " 列表标签未闭合 " + listMeta.name);
    const block = tail.slice(0, close);
    const itemPattern = /<([a-z][\w-]*)\b([^<>]*\bdata-item[^<>]*)>([\s\S]*?)<\/\1>/gi;
    const items = [...block.matchAll(itemPattern)];
    assert.ok(items.length >= 3 && items.length <= 5, file + " 列表示例应有 3 到 5 项");
    assert.ok(items.length >= listMeta.min && items.length <= listMeta.max, file + " 列表示例超出元数据范围");
    const itemSlots = [...items[0][3].matchAll(/\bdata-slot="([^"]+)"[^>]*\bdata-max="(\d+)"/g)].map((slot) => ({ name: slot[1], max: Number(slot[2]) }));
    assert.deepEqual(itemSlots, listMeta.slots.map((slot) => ({ name: slot.name, max: slot.max })), file + " 列表文字位与元数据不一致");
    const tagSignatures = items.map((item) => item[0].match(/<[^>]+>/g).join(""));
    assert.ok(tagSignatures.every((signature) => signature === tagSignatures[0]), file + " 列表项结构不一致");
  }

  const inlineStyles = [...html.matchAll(/<(?!body\b)[^>]+\bstyle=/gi)];
  assert.equal(inlineStyles.length, 0, file + " 不要用内联样式绕过配色规则");
  return meta;
}

function pngSize(file) {
  const data = fs.readFileSync(file);
  assert.equal(data.toString("hex", 0, 8), "89504e470d0a1a0a", file + " 不是 PNG");
  return { width: data.readUInt32BE(16), height: data.readUInt32BE(20) };
}

async function inspectPage(page, html, meta, scenario) {
  await page.setContent(html, { waitUntil: "load" });
  await page.evaluate(() => document.fonts.ready);
  return page.evaluate(({ scenario, meta }) => {
    const canvas = document.querySelector(".canvas");
    const canvasRect = canvas.getBoundingClientRect();
    const lists = [...document.querySelectorAll("[data-list]")];
    if (scenario.listCount) {
      for (const list of lists) {
        const first = list.querySelector("[data-item]");
        const count = Number(list.getAttribute(scenario.listCount));
        const current = [...list.querySelectorAll("[data-item]")];
        while (current.length > count) current.pop().remove();
        while (list.querySelectorAll("[data-item]").length < count) list.append(first.cloneNode(true));
      }
    }
    if (scenario.maxText) {
      for (const slot of document.querySelectorAll('[data-slot]:not([data-kind="image"])')) {
        slot.textContent = "美".repeat(Number(slot.getAttribute("data-max")));
      }
    }
    const slots = [...document.querySelectorAll('[data-slot]:not([data-kind="image"])')];
    const overflow = [];
    for (const slot of slots) {
      const rect = slot.getBoundingClientRect();
      if (slot.scrollHeight > slot.clientHeight + 1) overflow.push({ name: slot.getAttribute("data-slot"), type: "text", scroll: slot.scrollHeight, client: slot.clientHeight });
      if (rect.left < canvasRect.left + 63.5 || rect.top < canvasRect.top + 63.5 || rect.right > canvasRect.right - 63.5 || rect.bottom > canvasRect.bottom - 63.5) {
        overflow.push({ name: slot.getAttribute("data-slot"), type: "safe-margin" });
      }
    }
    const listInfo = lists.map((list) => {
      const items = [...list.querySelectorAll("[data-item]")];
      const signature = (item) => [item, ...item.querySelectorAll("*")].map((element) => [
        element.tagName,
        element.getAttribute("data-slot"),
        element.getAttribute("data-max"),
        element.getAttribute("data-kind"),
      ]);
      return {
        name: list.getAttribute("data-list"),
        min: Number(list.getAttribute("data-min")),
        max: Number(list.getAttribute("data-max")),
        count: items.length,
        consistent: items.every((item) => JSON.stringify(signature(item)) === JSON.stringify(signature(items[0]))),
      };
    });
    return {
      bodyChildren: document.body.children.length,
      canvasIsOnlyChild: document.body.firstElementChild === canvas,
      width: canvasRect.width,
      height: canvasRect.height,
      overflow,
      listInfo,
      hasDpr: window.devicePixelRatio,
      colors: Object.fromEntries(["bg", "fg", "primary", "secondary", "accent", "muted"].map((name) => [name, getComputedStyle(canvas).getPropertyValue("--" + name).trim()])),
    };
  }, { scenario, meta });
}

async function main() {
  const index = JSON.parse(fs.readFileSync(indexPath, "utf8"));
  const icons = JSON.parse(fs.readFileSync(iconsPath, "utf8"));
  const files = fs.readdirSync(templatesDir).filter((name) => name.endsWith(".html")).sort();
  assert.equal(files.length, index.length, "模板文件数量与 index.json 不一致");
  assert.equal(new Set(index.map((entry) => entry.id)).size, index.length, "index.json 有重复 id");
  fs.mkdirSync(previewsDir, { recursive: true });

  const templateData = [];
  for (const file of files) {
    const html = fs.readFileSync(path.join(templatesDir, file), "utf8");
    const meta = readMetadata(html, file);
    const entry = index.find((item) => item.id === meta.id);
    assert.ok(entry, file + " 未列入 index.json");
    checkStatic(entry, html, file, icons);
    templateData.push({ file, html, meta });
  }

  if (staticOnly) {
    console.log("静态检查通过： " + templateData.length + " 个模板；元数据、外链、颜色、尺寸和图标检查完成。");
    return;
  }

  const browser = await chromium.launch({ headless: true });
  try {
    for (const { file, html, meta } of templateData) {
      const [width, declaredHeight] = meta.size;
      const height = declaredHeight || 1920;
      const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
      const scenarios = [
        { name: "示例", listCount: null, maxText: false },
        { name: "文字满字数", listCount: null, maxText: true },
        { name: "列表最少项", listCount: "data-min", maxText: false },
        { name: "列表最多项", listCount: "data-max", maxText: false },
        { name: "最少项且满字数", listCount: "data-min", maxText: true },
        { name: "最多项且满字数", listCount: "data-max", maxText: true },
      ];
      let sample;
      for (const scenario of scenarios) {
        const result = await inspectPage(page, html, meta, scenario);
        if (!sample) sample = result;
        assert.equal(result.bodyChildren, 1, file + " body 只能有一个子元素");
        assert.ok(result.canvasIsOnlyChild, file + " body 的唯一子元素必须是 .canvas");
        assert.equal(result.width, width, file + " 画布宽度错误");
        if (declaredHeight) assert.equal(result.height, declaredHeight, file + " 画布高度错误");
        else assert.ok(result.height >= 1920, file + " 长图高度不足 1920");
        assert.deepEqual(result.overflow, [], file + "「" + scenario.name + "」出现溢出或安全边距不足: " + JSON.stringify(result.overflow));
        for (const list of result.listInfo) {
          assert.ok(list.count >= list.min && list.count <= list.max, file + " 列表数量超出范围");
          assert.ok(list.consistent, file + " 重复列表项结构不一致");
        }
      }

      const previewPath = path.join(previewsDir, meta.id + ".png");
      await page.setViewportSize({ width, height });
      await page.setContent(html, { waitUntil: "load" });
      await page.evaluate(() => document.fonts.ready);
      await page.screenshot({ path: previewPath, fullPage: true, type: "png" });
      const actual = pngSize(previewPath);
      assert.equal(actual.width, width, previewPath + " PNG 宽度错误");
      if (declaredHeight) assert.equal(actual.height, declaredHeight, previewPath + " PNG 高度错误");
      console.log("通过 " + meta.id + "：示例、满字数、列表最少/最多及组合场景；预览 " + actual.width + "×" + actual.height);
      await page.close();
    }

    const columns = Math.min(3, templateData.length);
    const rows = Math.ceil(templateData.length / columns);
    const cardWidth = 340;
    const cardHeight = 454;
    const gap = 24;
    const pageWidth = columns * cardWidth + (columns + 1) * gap;
    const pageHeight = rows * (cardHeight + 42) + (rows + 1) * gap + 56;
    const cards = templateData.map(({ meta }) => {
      const source = fs.readFileSync(path.join(previewsDir, meta.id + ".png")).toString("base64");
      return '<figure><img src="data:image/png;base64,' + source + '"><figcaption>' + meta.name + " · " + meta.style + "</figcaption></figure>";
    }).join("");
    const overview = await browser.newPage({ viewport: { width: pageWidth, height: pageHeight }, deviceScaleFactor: 1 });
    await overview.setContent('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>*{box-sizing:border-box}html,body{margin:0;background:#f4f1e8;font-family:sans-serif}main{display:grid;grid-template-columns:repeat(' + columns + ',' + cardWidth + 'px);gap:' + gap + 'px;padding:' + gap + 'px}figure{margin:0}img{display:block;width:' + cardWidth + 'px;height:' + cardHeight + 'px;object-fit:cover;box-shadow:0 8px 22px #0002}figcaption{height:42px;padding-top:10px;text-align:center;font-size:16px;color:#333}</style><main>' + cards + "</main></html>", { waitUntil: "load" });
    await overview.evaluate(() => document.fonts.ready);
    await overview.screenshot({ path: path.join(previewsDir, "_all.png"), fullPage: true, type: "png" });
    console.log("总览通过： " + pngSize(path.join(previewsDir, "_all.png")).width + "×" + pngSize(path.join(previewsDir, "_all.png")).height);
    await overview.close();
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
