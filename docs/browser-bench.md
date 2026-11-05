# 浏览器测试台：不用手机，用无头 Chrome 验证 CDP 改造

给接手的编程助手（Codex / GPT）看的任务书。先读 `AGENTS.md`、`CLAUDE.md`、`docs/browser-cdp.md`。**不许推 `main`**，分支 `gpt/browser-bench`，推完先确认「检查分支」是绿的再交活。

> 这个任务本身就是要建测试工具，`AGENTS.md` 里「不要为了验证新建脚本、工具类或测试辅助框架」「只加 1~3 个单测」两条**对这个任务不适用**。其他规矩照旧。

## 为什么做

`docs/browser-cdp.md` 的四步（CDP 真实输入、元素融合、截接口、节奏和限流识别）都合进了 `main`，但只过了编译和纯逻辑单测，没在真的浏览器里跑过。Jack 暂时没法在手机上测。

安卓 WebView 和桌面 Chromium 是同一个内核，DevTools 协议一样。Kavi 移植的 CDP 相关代码（`app/src/main/mika/com/wangzan101/mika/core/cap/web/` 下的 `cdp/*`、`ElementProbe`、`ElementFusion`、`NetworkLedger`、`WebBarrier`、`PagePacing`）和页面脚本 `app/src/main/assets/jack_browser.js` 都不依赖安卓，可以直接在 JVM 单测里接一个**真的无头 Chromium** 跑。

目标：每个分支的「检查分支」都在真浏览器里跑一遍这些能力；并且出一份对比报告，看 **CDP 新做法 vs 旧的脚本做法** 在各种难点页面上谁能成。

## 怎么搭

### 1. 启动浏览器、接上 CDP

- 测试里用 `ProcessBuilder` 启动 Chromium：`--headless=new --remote-debugging-port=0 --user-data-dir=<临时目录> --no-sandbox --disable-gpu`，从 stderr 读 `DevTools listening on ws://127.0.0.1:<端口>/...` 拿端口。
- 浏览器路径按顺序找：环境变量 `CHROME_PATH` → `/opt/pw-browsers/chromium`（Claude 的云端容器）→ `google-chrome` / `chromium` / `chromium-browser`（GitHub 的 ubuntu 机器自带 `google-chrome`）。**都找不到就 `Assume` 跳过**，不能让没装浏览器的环境变红。
- 在测试源码里写一个 TCP 版的 `CdpTransport`（`Socket("127.0.0.1", port)` 的输入输出流），然后**直接用移植来的** `CdpDiscovery`、`CdpSession` 连上去，不另写一套 CDP 客户端。这样测的就是 App 里实际跑的那份代码。
- 整个测试类共用一个浏览器进程，每个用例开新的 target（`Target.createTarget`），用完关掉；进程在 `@AfterClass` 里杀掉。

### 2. 测试页面（本地起服务，不碰外网）

- 用 JDK 自带的 `com.sun.net.httpserver.HttpServer` 在 `127.0.0.1` 随机端口起服务，页面放 `app/src/test/resources/bench/`。
- **跨站**用 `*.localhost` 子域名：Chromium 会把 `a.localhost`、`b.localhost`、`captcha.localhost` 都解析到本机，又算不同站点，不用改 hosts。
- 每个页面自带判定：做对了就在 `window.__benchResult` 写结果，测试读它判断成没成。

要覆盖的页面（每个一个 html，越短越好）：

| 页面 | 难点 | 怎么算成 |
|---|---|---|
| `trusted-click.html` | 按钮只认 `event.isTrusted === true` 的点击（模拟风控、React 合成事件） | 点了以后 `__benchResult = "clicked"` |
| `controlled-input.html` | 仿 React 受控输入框：只监听 `beforeinput`/`input` 事件更新状态，直接改 `.value` 会被下次渲染冲掉 | 输入「测试中文 abc」后，状态里的值一致 |
| `enter-submit.html` | 搜索框按回车提交（`keydown` 里判断 `key === "Enter"` 且 `isTrusted`） | 提交后结果区出现搜索词 |
| `cross-origin-frame.html` | 按钮在 `b.localhost` 的 iframe 里，主页面在 `a.localhost` | 元素融合后清单里能看到这个按钮，并且能点中 |
| `closed-shadow.html` | 按钮在 `attachShadow({mode: "closed"})` 里 | 同上 |
| `listener-text.html` | 一段纯文字 `span` 上挂了 `click` 监听，没有 role、没有 cursor:pointer | 融合后列为「可点」，点中 |
| `covered-button.html` | 按钮被透明遮罩盖住 | 融合清单**不**列它（被挡住的不列） |
| `canvas-list.html` | 商品列表从 `/api/items`（同站 JSON，含 name/price/shop/url）拉回来，画在 `<canvas>` 上，DOM 里没有文字 | 网页脚本 `extract(table)` 读不到，`NetworkLedger` + `BrowserNetworkRows` 能读出 ≥10 行 |
| `dom-list.html` | 同样的数据正常渲染成 DOM 列表，另外页面还拉了一个同站的「猜你喜欢」推荐 JSON | 走网页结果（行数正确、带销量列），**不被**推荐流替换 |
| `rate-limited.html` | 服务返回 429，正文「访问过于频繁」 | `WebBarrier.classify` 判成受限 |
| `captcha-frame.html` | 嵌一个可见的 `captcha.localhost` iframe | 判成验证码 |
| `article.html` | 长文章，正文里提到「访问过于频繁」「人机验证」 | **不**判成拦截（长页面不看敏感词） |

### 3. 两条路都跑，出对比

每个交互类页面跑两遍：

- **旧做法**：注入 `jack_browser.js`，调 `window.__jack.act(n, "click"/"type"...)`（就是 `BrowserScripts.act` 生成的那段）。
- **新做法**：用 `jack_browser.js` 的 `center(n)` 取坐标，再按 `BrowserCdp` 的事件顺序发 CDP 输入。

为了测的是真代码而不是测试里抄的一份：把 `BrowserCdp` 里 `Sender` 的事件序列（mouseMoved → pressed → released、按键、`insertText`、全选删除、触摸滑动）**抽成一个纯 Kotlin 类**（不碰 Android，比如 `app/src/main/java/com/jack/ai/ui/CdpInputScript.kt`），`BrowserCdp` 和测试台都调它。只做这一处重构，`BrowserCdp` 行为不变。

元素融合：同一页面里先跑 `jack_browser.js` 的 `snapshot()` 得到脚本清单，再用 `ElementProbe` + `ElementFusion` 融合，断言融合后多出了 iframe / shadow / 监听文字里的元素，并能用 `ElementProbe.locate` 拿到坐标点中。融合里 Kavi 侧的胶水（`BrowserCdp.enrich` 依赖 Android）不用测，测到 `ElementFusion.fuse` 的结果就行。

### 4. 报告

- 跑完在 `app/build/reports/browser-bench.md` 写一张表：每个页面 × 旧做法 / 新做法 → 成 / 不成 / 不适用，加一句失败原因。
- 同时把这张表打印到测试输出（CI 日志里能直接看到）。
- **测试断言只卡新做法**：新做法该成的必须成，旧做法成不成只记录不断言（旧做法在 `trusted-click` 这类页面上本来就该失败，这正是要证明的）。

### 5. 接进 CI

- 确认 `.github/workflows/check.yml` 的单测步骤会跑到这些测试（在 `com.jack.ai.*` 包下就会）；GitHub 的 ubuntu 机器自带 Chrome，不需要额外安装步骤。如果确实要装，只加最少的步骤并说明。
- 测试总时长控制在 2 分钟以内；单个用例有超时（比如 20 秒），卡住不能拖死整个 CI。
- 打包工作流（`build.yml`）不要碰。

## 放在哪

- 测试代码：`app/src/test/java/com/jack/ai/bench/`（`BrowserBenchTest.kt` + 启动浏览器、TCP 传输、本地服务这几个小文件）。
- 测试页面：`app/src/test/resources/bench/*.html`。
- 业务代码只动一处：抽出 `CdpInputScript`，`BrowserCdp` 改为调用它。

## 交活时写清楚

- 报告表格原样贴出来（哪些页面新做法成了、旧做法没成）。
- 有哪些页面**新做法也没成**：这是最有价值的信息，说明 App 里的实现可能有问题，写下你看到的原因，不要为了变绿去改断言或删页面。
- 在本机和 CI 上各跑了多久。
