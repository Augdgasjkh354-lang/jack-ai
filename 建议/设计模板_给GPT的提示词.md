请给仓库 Augdgasjkh354-lang/jack-ai 做一批「设计模板」：Kavi（安卓 App 里的 AI 助手）会套这些模板，做小红书配图、海报、邀请函、名片、公众号封面这类单张图片，有点像 Canva。先读 `AGENTS.md`、`CLAUDE.md`。

**出图方式**：模板是一个 HTML 文件。Kavi 把文字、图片、配色填进去，在手机的 WebView（Chromium 内核）里按固定尺寸渲染，截成 PNG 或 PDF。所以模板就是一张固定大小的网页。

**你只做模板，不改 Kotlin 代码。** 渲染引擎、工具和技能 Claude 来做。

## 一、做哪些（共 28 个，风格不要重复）

| 分类 id | 用途 | 尺寸（px） | 数量 |
|---|---|---|---|
| `xhs` | 小红书封面、图文卡片、知识点合集、清单、金句 | 1080×1440 | 6 |
| `square` | 朋友圈、微博方图 | 1080×1080 | 3 |
| `poster` | 竖版海报：活动、促销、招募、讲座、通知 | 1080×1920（手机竖屏）选 3 个，1240×1754（A4 比例）选 2 个 | 5 |
| `invite` | 邀请函、贺卡：生日、婚礼、节日、开业、年会 | 1080×1620 | 5 |
| `card` | 名片（横版） | 1050×600 | 3 |
| `cover` | 公众号封面（横版） | 900×383 | 3 |
| `long` | 长图：信息图、流程说明、攻略 | 宽 1080，高度随内容（至少 1920） | 3 |

**风格**要拉开，比如清新、商务、极简、杂志感、可爱、国风、复古、科技感、温暖节日、高级黑金。每个模板的风格写在元数据里。

## 二、文件放哪

- 模板放在 `app/src/main/assets/design/templates/<id>.html`。id 用小写字母、数字和横杠，比如 `xhs-knowledge-fresh`。
- 目录文件放在 `app/src/main/assets/design/index.json`。它是一个数组，每个模板一项，内容和下面「元数据」完全一样。
- 预览图放在仓库根目录的 `design-previews/<id>.png`，不要放进 assets，以免打进安装包。另外再拼一张总览 `design-previews/_all.png`。

## 三、模板规则（必须遵守，引擎靠这些规则来填内容）

1. **单文件、不联网**：一个模板就是一个 HTML 文件，CSS 写在 `<style>` 里。不许有 JS，不许引用外部字体、图片、CDN 或任何网址。
2. **画布**：`<body style="margin:0">` 里只有一个 `<div class="canvas">`，宽高必须正好是规定尺寸，并且 `overflow:hidden`。长图只固定宽度，高度由内容撑开。
3. **配色全部走变量**：在 `.canvas` 上定义以下六个变量：
   - `--bg` 背景
   - `--fg` 正文字
   - `--primary` 主色
   - `--secondary` 辅色
   - `--accent` 点缀
   - `--muted` 次要文字和线条

   模板里所有颜色都必须用这六个变量，或者从它们派生（`color-mix()`、透明度都可以）。黑白半透明蒙层例外。这样 Kavi 一换变量就能换配色。
4. **字体只用系统字体**：定义 `--font-sans: "Noto Sans CJK SC","Source Han Sans SC","PingFang SC",sans-serif;` 和 `--font-serif: "Noto Serif CJK SC","Source Han Serif SC","Songti SC",serif;`。字重只用 400 和 700，有些手机上没有别的字重。用到宋体的模板，在宋体缺失、退回黑体时也要好看。
5. **文字位**：每个可填的文字元素都要加 `data-slot="名字"` 和 `data-max="最多字数"`，并且填上像真的一样的中文示例，不要写「标题文字」这种占位话。常用的名字有：
   - `title` 标题、`subtitle` 副标题、`body` 正文、`tag` 标签
   - `date` 日期、`time` 时间、`place` 地点
   - `name` 名字、`role` 职位、`phone` 电话、`email` 邮箱、`address` 地址
   - `cta` 行动号召、`footer` 落款
   - 有需要可以自己加，比如 `price`、`host`。
6. **列表**：要点、步骤、日程这类可重复的内容，用一个容器加 `data-list="名字"`（比如 `points`、`steps`），里面每一项加 `data-item`，项里的文字同样用 `data-slot`。引擎会照第一项复制出需要的数量，所以每一项的结构要一样。模板里放 3 到 5 项示例。容器上要写 `data-min` 和 `data-max`，表示最少和最多几项，并且在最少和最多的情况下都要好看。
7. **图片位**：照片、头像、产品图用 `<div data-slot="photo" data-kind="image">`，用 CSS 控制大小、圆角、裁切（引擎会设 `background-image` 和 `background-size:cover`）。没图的时候，要用变量配色的渐变或纹理当占位，**而且整张图没有照片也必须完整、好看**，因为大多数时候用户不会给照片。
8. **图标**：写成 `<i data-icon="图标名"></i>`，用 CSS 定大小和颜色，颜色用 `currentColor`。引擎会把它换成 Tabler 的线框图标。图标名必须在 `app/src/main/resources/ppt/tabler-icons.json` 的键里存在，比如 `calendar`、`map-pin`、`phone`、`mail`、`heart`、`star`、`gift`。用之前去核对一下。装饰性的几何图形（圆、线、波浪、点阵）可以直接写 CSS 或内联简单 SVG。
9. **装得下**：每个文字位在字数达到 `data-max` 的一半到全满时，都不能溢出、不能被遮挡、不能挤出画布。标题可以换行，必要时用 `-webkit-line-clamp`。四边至少留 64px 的安全边距，名片和封面留 40px。文字和背景的对比度至少要到 WCAG AA。
10. **元数据**：在 `<head>` 里放一行 `<meta name="kavi-template" content='…JSON…'>`，JSON 的格式如下：

    ```json
    {"id":"xhs-knowledge-fresh","name":"知识点卡片·清新","category":"xhs","size":[1080,1440],
     "style":"清新","description":"一个大标题加 3 到 6 条要点，适合干货、清单",
     "slots":[{"name":"title","label":"标题","max":16},{"name":"subtitle","label":"副标题","max":24}],
     "lists":[{"name":"points","label":"要点","min":3,"max":6,"slots":[{"name":"text","label":"要点","max":28}]}],
     "images":[{"name":"photo","label":"配图","optional":true}],
     "colors":{"bg":"#F6FBF8","fg":"#1F2D27","primary":"#2F9E6E","secondary":"#BFE6D3","accent":"#F2B84B","muted":"#7A8C84"}}
    ```

    `colors` 就是模板默认的那套配色，和 CSS 里的变量值一致。长图的 `size` 写 `[1080,0]`。
11. **原创**：不许照搬 Canva、稿定或任何品牌的模板、商标、插画。
12. **空间分配**（第 1 批出过的问题，后面别再犯）：
    - 列表项不要用 `flex:1` 平分剩余高度。只有 3 项时，每项会被撑成一个大空框。每项要设最大高度（大约 240px），整组列表居中，或者靠上并留出合理间距。项少的时候可以用 `:has()` 把字放大一档。
    - 内容少的时候，下半截不能空一大块：要么让照片或主视觉吃掉多出来的高度，要么让主体内容上下居中（`margin-top:auto`）。
    - 交付前看「列表最少项、文字最短」那张预览图，它要和示例图一样饱满。

## 四、交付前自己检查

1. 本机装中文字体（Ubuntu 上是 `fonts-noto-cjk`），用 Playwright 的 Chromium 按模板尺寸、`deviceScaleFactor: 1` 把每个模板渲染成 PNG，放进 `design-previews/`。
2. 写一个检查脚本，放在 `tools/check_design_templates.py` 或 `.js`。每个模板都要跑三种情况：
   - 示例内容
   - 每个文字位填满 `data-max` 个字
   - 列表取最少项和最多项

   并检查以下几点：
   - 没有任何 `data-slot` 元素溢出（`scrollHeight > clientHeight` 或超出画布）
   - 画布尺寸对
   - 没有外部网址
   - 图标名都存在
   - 所有颜色都来自六个变量
   - `index.json` 和每个文件的元数据一致

   把检查结果贴出来。
3. 推到分支 `codex/design-templates`，**不许推 main**。做完用中文告诉 Jack：做了哪几类、各几个、分支名。
