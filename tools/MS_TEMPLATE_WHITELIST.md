# PPT 模板白名单（免署名、无水印）

只收录明确不需要署名、没有水印的来源。协议会变，入库前和每季度复核一次。

## 已入库

- Microsoft Create 官方模板（create.microsoft.com / powerpoint.cloud.microsoft）
  - 微软官方提供的模板，免费使用，无水印，无需署名。
  - 本批 10 套，见 `ms-templates.json`，主题用 `ppt_theme_extract.py` 从原文件抽出，
    只存配色和字体参数，不原样分发模板文件。
  - 下载方式：模板页里嵌的微软公开 CDN 直链（createcatalog.public.onecdn.static.microsoft），
    无需登录即可下载 .pptx。

## 明确排除（需要署名，不要入库）

- SlidesCarnival：CC BY 4.0，必须署名（留 Credits 页或页脚链接），官网 FAQ 原文为准。
- AllPPT：需要标明出处，且禁止把模板素材单独抽出来再分发。
- Slidesgo 免费版：必须保留 credits 页。
- Storyset / Freepik 免费版、Icons8 / Flaticon / Noun Project 免费版：免费使用要署名。

## 候选（需用户登录，暂未批量入库）

- OfficePLUS（微软中国官方）：免费、无水印、商用授权清晰；免费账号每天限下 10 次，
  下载需微信扫码登录。用户手动下载后，可用本脚本抽主题入库。

## 导入 App 的方法

`ms-templates.json` 的条目结构和 App 里 `style_templates.json` 的 Template 完全一致
（name、kind、settings、colors、isDefault、source、createdAt）。
把条目合并进 App 文件目录下的 `style_templates.json` 数组即可在“我的模板”里使用。

## 工具

- `ppt_theme_extract.py`：纯标准库，从 .pptx 抽主题。逻辑对照 jack-ai 的 PptStyle.kt：
  读主题配色和字体，按面积算页面上涂得最多的颜色，判断深浅色，算出主色、强调色、
  背景色、标题色、文字色和柔色/卡片色/静音色。
- 微软目录文件常有多个母版（如 slideMaster11 对应 theme11），脚本按
  presentation rels → 母版 rels → 主题的链路解析，不假设一定是 theme1。
