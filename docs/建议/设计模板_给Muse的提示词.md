请给仓库 Augdgasjkh354-lang/jack-ai 做第 3 批「设计模板」（第 2 版规格）。Kavi 是安卓 App 里的 AI 助手，会套这些模板做小红书图文、海报、邀请函、名片这类图片，有点像 Canva。先读 `AGENTS.md`、`CLAUDE.md`。

**你只做模板，不改 Kotlin 代码。** 渲染引擎、工具、技能、编辑器都由 Claude 来做。

## 零、先看样板

`app/src/main/assets/design/templates/` 里已经有 35 个模板：Claude 做的大字报样板（`xhs-bold-*`），和你第 2 批做的 8 套小红书套组。**质量以大字报样板和你第 2 批里最好的那几张为准，写法照它们来。** 预览图在 `design-previews/`，先把 `_all.png` 看一遍。

新做的不要和已有的撞版式：同一种 role（比如 list），换风格时版式也要有变化，不是换个颜色。

## 一、这批做什么（第 3 批）

### A. 补齐小红书套组：20 个（5 种风格 × 每套 4 张）

`styles.json` 里还没有模板的 5 种风格：

| 风格 id | 名字 | 这套的 4 张（role） |
|---|---|---|
| `film` | 胶片复古 | cover、photo、quote、ending |
| `black-gold` | 高级黑金 | cover、data、list、ending |
| `study` | 学习笔记 | cover、steps、qa、ending |
| `japanese` | 日系清新 | cover、photo、list、ending |
| `newspaper` | 报纸 | cover、list、compare、ending |

### B. 别的用途：12 个

现在除了小红书，别的分类一个模板都没有。每种做 2～3 个，风格从上面 14 种里挑最合适的，**同一分类里风格不重样**：

| 分类 | 尺寸 | 做几个 | 内容建议 |
|---|---|---|---|
| `poster` 竖版海报 | 1080×1920 | 3 | 活动海报（时间地点大）、促销海报（价格大）、招聘/招生 |
| `invite` 邀请函贺卡 | 1080×1620 | 3 | 婚礼/生日邀请、开业/发布会邀请、节日贺卡 |
| `square` 方图 | 1080×1080 | 2 | 朋友圈产品图（大图+卖点）、金句方图 |
| `card` 名片 | 1050×600 | 2 | 一个简洁、一个有设计感；姓名、职位、电话、微信、地址 |
| `cover` 公众号封面 | 900×383 | 2 | 大标题横版；左字右图 |

id 写成 `<分类>-<风格id>-<用途>`，比如 `poster-bold-event`、`invite-guofeng-wedding`。元数据的 `set` 填 `<分类>-<风格id>`，`role` 填用途（event、sale、wedding……）。

邀请函、海报这类：时间、地点、联系方式这几个字段要有自己的 `data-slot`（`date`、`time`、`place`、`contact`），Kavi 会单独填。

### C. 和编辑器配合（新要求）

用户现在能在编辑器里改模板：拖动、换图、裁剪、滤镜、改色、换字体。写模板时照下面几条，编辑器才好用：

- 图片位只用 `<div data-slot="photo" data-kind="image">`，**不要用 `<img>`**。编辑器靠 `background-image` 放照片、做裁剪和滤镜，大小和裁切写在这个 div 的 CSS 上。
- 每块可改的字单独一个元素、带 `data-slot`。不要把两段字塞进同一个元素里，用户没法分开拖。
- 装饰图形（色块、胶带、印章、线框）做成独立元素，不要画在文字元素的 `::before`、`::after` 上。编辑器选不中伪元素，用户就删不掉、挪不动。
- 文字颜色、底色都写 `var(--…)`。用户换配色时整张跟着变，写死的颜色会剩下来。
- 标题字体写 `var(--font-title)`、正文写 `var(--font-sans)`。编辑器的「大标题、正文」预设读这两个变量。

## 二、文件放哪、叫什么

- 模板：`app/src/main/assets/design/templates/<id>.html`。id 写成 `xhs-<风格id>-<role>`，比如 `xhs-magazine-cover`。
- 目录：`app/src/main/assets/design/index.json` **不要手写**。模板写好后跑 `node tools/check_design_templates.js --write-index`，它会按每个模板里的元数据重新生成。
- 预览图：跑检查脚本时自动生成到 `design-previews/<id>.png` 和总览 `design-previews/_all.png`，一起提交。

## 三、模板规则

### 1. 单文件、不联网
- 一个模板就是一个 HTML 文件，CSS 写在 `<style>` 里。
- 不许有 JS、`<link>`、外部网址、图片文件、`@import`。
- `url()` 只能指向页面里的 SVG，比如 `filter:url(#g)`。
- 纹理、颗粒、胶带、手绘箭头、印章这类装饰，都用 CSS 或内联 SVG 画。颗粒层的写法照样板里的 `.grain`。

### 2. 画布
- `<body>` 里只有一个 `<div class="canvas">`。
- 宽高正好是规定尺寸（小红书 1080×1440），并且 `overflow:hidden`。

### 3. 配色全部走变量
在 `.canvas` 上定义六个变量：`--bg` 背景、`--fg` 正文字、`--primary` 主色、`--secondary` 辅色、`--accent` 点缀、`--muted` 次要文字和线条。
- 默认值**照抄 `styles.json` 里这个风格的第一套配色**。
- 所有颜色都要从这六个变量来，可以用 `color-mix()` 派生。黑白半透明（`rgba(0,0,0,.x)`、`rgba(255,255,255,.x)`）可以直接用。
- 这样用户在编辑器里点一下就能整套换配色，你要换另外两套配色各看一眼，确认也好看。

### 4. 字体（这一版最大的变化）
- 可以用 `app/src/main/assets/design/fonts.json` 里的字体，App 会自动下载。用法：
  - 得意黑写 `"Smiley Sans"`；思源宋体写 `"Noto Serif SC"`；霞鹜文楷写 `"LXGW WenKai"`；英文字体比如 `"Playfair Display"`、`"Bebas Neue"`。
  - 每个 `font-family` 后面都要跟系统字体兜底，比如 `"Noto Serif SC","Noto Serif CJK SC",serif`。没网时会退回系统字体，退回后也不能难看到没法用。
- 字重写 100～900 的整百数，只用这个字体在 `fonts.json` 里列出的那几档。
- 一张图最多两种中文字体，英文数字字体最多再加一种。
- 推荐像样板那样定义 `--font-title`、`--font-sans`、`--font-num` 三个变量，再在各处引用。

### 5. 文字位
- 每个可填的文字都加 `data-slot="名字"` 和 `data-max="最多字数"`。
- 示例要写成像真的一样的中文内容，不要写「标题文字」这种占位话。同一套的 4 张讲同一个话题，像一篇真的笔记。
- 常用的名字：`title`、`subtitle`、`tag`、`body`、`note`、`author`（账号名）、`date`、`footer`。
- 每张都要有 `page`（页码，比如 `01/04`），出成组的图时引擎会自动填。
- 封面的 `title` 可以用 `<br>` 分行，元数据的 label 里注明「可用\n分行」。

### 6. 列表
- 可重复的内容（要点、步骤、宫格）用一个容器加 `data-list="名字"` 和 `data-max="最多项数"`，里面每项加 `data-item`。项里的文字同样用 `data-slot`。
- 引擎会照第一项复制出需要的数量，所以每项的结构要一样。
- 编号写成 `<span data-slot="num">01</span>`，引擎会自动填 01、02…（`num` 不用在元数据里声明）。
- 模板里放 3～5 项示例。最少项和最多项的时候都要好看。
- **不要用 `flex:1` 把列表项撑满剩余高度。** 只有 3 项时每项会被撑成一个大空框。

### 7. 图片位
- 用 `<div data-slot="photo" data-kind="image">`，用 CSS 定大小、裁切。
- **没有照片时整张图也必须完整好看。** 大多数时候用户不会给照片，要用变量配色的色块、纹理或图形当占位。
- 照片上压字要加渐变蒙层或纯色条，保证看得清。

### 8. 图标
- 写成 `<i data-icon="图标名"></i>`，名字要在 `app/src/main/resources/ppt/tabler-icons.json` 里存在。
- 克制着用，不要每个小标题前都放一个。

### 9. 故意出血的装饰
压在角上、故意露出一半的巨型数字或图形，加 `data-bleed`（见样板封面的大数字）。检查脚本就不会把它当成超出画布。

### 10. 一个容易踩的坑
写全局规则「文字位是行内块」时，用 `:where(span[data-slot]){display:inline-block}`，不要直接写 `span[data-slot]{…}`。后者优先级比单个类名高，会把你给 `.head{display:block}` 这类写的 display 盖掉。样板就是这么写的，检查脚本也会查。

### 11. 元数据
在 `<head>` 里放一行 `<meta name="kavi-template" content='…JSON…'>`，格式照样板：

```json
{"id":"xhs-magazine-cover","name":"杂志编辑·封面","category":"xhs","size":[1080,1440],
 "style":"杂志编辑","look":"magazine","set":"magazine-a","role":"cover",
 "description":"一句话：长什么样、适合什么内容",
 "slots":[{"name":"title","label":"标题（可用\\n分两行）","max":12}],
 "lists":[],"images":[{"name":"photo","label":"封面照片","optional":true}],
 "colors":{"bg":"…","fg":"…","primary":"…","secondary":"…","accent":"…","muted":"…"}}
```

- `look`：风格 id，必须是 `styles.json` 里的。
- `set`：套组 id，写成 `<风格id>-a`。同一套的 4 张写一样的。
- `role`：这一张的角色。
- `colors`：和 CSS 变量的默认值一致。

## 四、审美要求（不合格的不收）

逐条对照 `app/src/main/java/com/jack/ai/data/Skills.kt` 里「审美」技能的第 3～6 条，这里只列最要紧的：

- **一个焦点**：每张图最先看到的只有一个东西。标题字号至少是正文的 2.5 倍。
- **够大**：正文至少 24px；小标签至少 20px，而且要加粗。封面标题缩到 300px 宽的缩略图里还能一眼读出来。
- **对比度**：正文 4.5 以上，大标题 3 以上。
- **留白**：文字离画布四边至少 60px。宁可少放字。
- **颜色**：彩色最多三种。
- **别有 AI 味**：不要蓝紫渐变、发光字、卡片套卡片、处处圆角加阴影、所有东西都居中、满屏 emoji 星星。
- **原创**：不许照搬 Canva、稿定或任何品牌的模板、商标、插画。

## 五、交付前自己检查

1. 跑 `node tools/check_design_templates.js --write-index`。它会用 App 真正的填充脚本和真实字体（从 jsDelivr 下载，要联网），把每个模板按四种情况各跑一遍：示例、文字填满、列表最少项、列表最多项且文字填满。检查内容包括规则、溢出、边距和审美，最后出预览图。必须**全部通过**。
2. 自己打开 `design-previews/` 里的每张预览图看一遍。脚本查不出「丑」，你要查。另外两套配色也各渲染一次看看。
3. 推到分支 `muse/design-templates-3`，**不许推 main**。A 部分做完先推一次，再做 B。做完用中文告诉 Jack：做了哪几套、各几张、分支名，附上 `_all.png` 的总览。
