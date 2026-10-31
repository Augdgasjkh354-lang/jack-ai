# 插画与配图扩容：可内置的、可用户自助的、可接 API 的，三条路分开

## 先说 App 里插图的现状（已确认）

- 图片进 PPT 走 `<p:pic>` + `<a:blip r:embed>` 图片部件（见 `PptDeck.picture()`），插的是光栅图字节流；**SVG 不能直接这样嵌**，SVG 必须先转 PNG 再嵌。
- 版式上现成的有两种：image（整页大图封面）和带图内容页（右侧圆角配图），新插画、配图直接进这两个版式，不用新写版式。

## 第一路：Open Peeps，可直接内置（CC0）

- 授权：CC0 1.0 公共领域，官网首页原文就是“copy, modify, distribute, remix, burn, and use the work, even for commercial purposes, without asking permission”——**打包进 App、改色、商用都行，无需署名**。
- 本分支内置首包：`app/src/main/resources/ppt/open-peeps/`，8 张（mix-1..7、sit-stand-walk），SVG 原文件 + 1200px 宽透明底 PNG + `index.json`（每张有中英文 tag 和一句话描述，模型按 tag 挑图）。
  - 8 张都是官网首页公开的官方示例 SVG，用 cairosvg 转的 PNG（透明底已验证，alpha 0–255），不是网上随手扒的第三方转载。
  - 8 张全是黑白线条版：浅色幻灯片上效果好，深色底上黑线会看不清；彩色完整版去 openpeeps.com 下载整库（Gumroad），按同样路子转 PNG 扩包。
- 接入方式（给改 App 代码的人）：照 `icons.json` 的路子，用 `getResourceAsStream("/ppt/open-peeps/<file>")` 读字节流，走现有 `s.image` + `imageW/imageH` 插进版式；模型按 `index.json` 的 tag 选图。

## 第二路：unDraw，只能用户自助下载，不能内置、不能自动抓（重要纠正）

- 之前我说过“unDraw 按需下载”，查了官网 license 原文后要纠正：**unDraw 不允许把图打包进 App，也不允许用自动化方式抓取、嵌入、搜索、下载它的图**（原文：does not include the right to ... distribute the assets in packs or otherwise；extends to automated and non-automated ways to link, embed, scrape, search or download the assets without our consent；且“add them in an app”被点名不建议）。
- 所以 unDraw 的正确用法是：**用户自己上 undraw.co 挑场景、调好主题色、下载 SVG/PNG，再把文件交给 App**（和「我的模板」一个路子：用户自带素材）。App 侧只接“用户给的文件”，不做 unDraw 自动下载、不内置 unDraw 图。
- 用户给的是 SVG 时，App 侧转 PNG 再嵌（和 Open Peeps 同一个转法）；用户给 PNG 就直接嵌。
- 授权底线（官网 license 页，2026 版）：免费商用、免署名；但不能转卖、不能打包分发、不能做竞品图库、不能拿图训练 AI；图里含有的品牌 logo 是商标，不能暗示品牌代言。

## 第三路：配图接三家图库 API（官方 API 就是给 App 集成用的）

- Pexels：`api.pexels.com/v1/search`，请求头 Authorization 放 key；取 `photos[].src.large`（或 medium）+ `photographer` + `alt`。免费商用、免署名。
- Pixabay：`pixabay.com/api/?key=&q=&image_type=photo`，取 `hits[].webformatURL` 或 `largeImageURL` + `tags`。免费商用、免署名。
- Unsplash：`api.unsplash.com/search/photos`，请求头 Authorization: Client-ID 放 key；取 `results[].urls.regular` + `user.name`。免费商用；**走 API 时按 Unsplash 最新 API 指南给摄影师署名**（成品 PPT 里放一行小字即可），接入时以官网指南为准。
- Key 存放：走现在填 Tavily Key 那种路子，让用户在设置里填，不硬编码、不进仓库。
- 三家共同红线：不能当商标用、不能暗示照片里的人或品牌代言了你的东西、不能原图转卖、不能做成竞品图库；**可识别人像有肖像权风险，平台不担保模特授权**——商用 PPT 优先选无可识别人脸的图，或只用风景、物品、背影。
- 建议的取图顺序：Pexels → Pixabay → Unsplash（前两家免署名最省事）；搜不到再换关键词，不要为了凑图硬塞不相关的。

## 本分支内容

- `app/src/main/resources/ppt/open-peeps/`：8 张 PNG + 8 张 SVG 原文件 + `index.json`。
- `tools/ILLUSTRATION_EXPANSION.md`：本说明。
- 没做的事：unDraw 不内置（授权不允许）；Humaaans 没入库（授权说法打架，CC0 vs CC BY，入库前以官网为准）；图库 API 只给了接入规格和取字段，真正写搜索代码、存 key、做缓存是下一步（需要用户给 key 后再动手）。
