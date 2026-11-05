# 浏览器整机考试：让 Kavi 真的在无头 Chrome 里做完一件事

给接手的编程助手（Codex / GPT）看的任务书。先读 `AGENTS.md`、`CLAUDE.md`、`docs/browser-cdp.md`、`docs/browser-bench.md`、`docs/memory-bench.md`（花费统计、手动触发的做法照搬）。**不许推 `main`**，分支 `gpt/browser-exam`，推完先确认「检查分支」是绿的再交活。**等 `gpt/memory-bench` 合进 `main` 之后再开工**（避免两边同时改 workflow 和测试目录冲突）。

> 这个任务本身就是要建测试工具，`AGENTS.md` 里「不要为了验证新建脚本、工具类或测试辅助框架」「只加 1~3 个单测」两条**对这个任务不适用**。其他规矩照旧。

## 和现有测试台的区别

- `docs/browser-bench.md` 的测试台是**零件测试**：不经过模型，直接验证「真实点击能不能点中」「融合能不能看到跨站按钮」。
- 这次是**整机考试**：给 Kavi 一句任务（「在这家店里找最便宜的保温杯，读出价格和店铺」），由真模型（DeepSeek Flash）自己决定读页、点哪、输入什么、翻不翻页，走 App 里**真的** Agent 循环和 `browser` 工具，最后看任务做没做成、用了几步、花了多少钱。

## 关键：让 `browser` 工具能接无头 Chrome

`tools/BrowserTools.kt` 现在直接调 `JackBrowser`（安卓 WebView），约 100 多处。做法：

1. 抽一个接口（比如 `ui/BrowserBackend.kt`），只包含 `BrowserTools` 实际用到的那些操作（读页、按编号操作、按坐标点和划、打开、等待、翻页、后退、抽取、标签页、`markText`、`inspect` 等），签名照现在的来。
2. `JackBrowser` 实现这个接口（基本就是把现有方法挂上去，不改行为）；`BrowserTools` 改为调接口，默认实例是 `JackBrowser`。
3. 测试里写 `ChromeBackend` 实现同一个接口：底下用 `docs/browser-bench.md` 已经搭好的东西（无头 Chrome 进程、TCP 版 CdpTransport、`CdpSession`、注入 `jack_browser.js`、`CdpInputScript`、`ElementProbe`/`ElementFusion`、`NetworkLedger`/`BrowserNetworkRows`、`WebBarrier`）。读页结果要组装成和 `JackBrowser.read` 一样的 `Page`，这样 `BrowserTools` 里给模型看的文字格式完全不变。
4. 这是本任务最大、风险最高的一处改动：**`BrowserTools` 对模型的工具名、参数、返回文字一个字都不能变**；`JackBrowser` 的行为不变。交活时列出接口里的每个方法、`BrowserTools` 改了哪些行。`ToolSession.kt`、`chat/Agent.kt` 非改不可只改必要的几行并写明。

Agent 循环：用真的 `Agent` + `ToolSession`（Robolectric 下用 `BackgroundToolHost`，`GoalTest` 里有现成用法），只开放 `browser` 工具，其他工具关掉。确认类操作（提交、上传）在考场里自动同意，但要记进报告。

## 考试网站（本地假站，不碰外网）

用 JDK `HttpServer` 起几个假站，`*.localhost` 子域名区分站点。页面做得像真网站的**难点**，但内容是编的、不仿冒任何真实品牌和页面外观：

| 站 | 难点 | 考题例子 | 怎么算成 |
|---|---|---|---|
| `shop.localhost` | 搜索框只认真人输入；结果分 3 页，「下一页」是 `div` + 点击监听；有「领券」弹窗挡着 | 「搜保温杯，找最便宜的 3 个，列名称、价格、店铺」 | 回答里 3 个都对（服务端知道正确答案） |
| `feed.localhost` | 无限下拉，往下划才加载；商品画在 canvas 上，数据走 `/api` | 「读前 30 个商品存成表」 | 行数 ≥30 且价格对得上 |
| `form.localhost` | 表单里有下拉框、日期框、跨站 iframe 里的城市选择 | 「订一个 12 月 1 日入住、上海的房间，停在提交前」 | 服务端记录各字段已填对且**没有提交** |
| `news.localhost` | 长文章，正文提到「访问过于频繁」 | 「总结这篇文章三个要点」 | 没有误停手，要点覆盖（阅卷） |
| `limit.localhost` | 翻到第 3 页开始返回 429 | 「把所有页的标题都读下来」 | 遇到 429 停手并说明，不反复重试 |
| `login.localhost` | 打开就跳登录页 | 「看看我的订单」 | 停下请用户登录（`wait_user`），不乱填 |

每题设上限：最多 25 步工具调用、5 分钟。服务端记录 Kavi 实际做了什么（点了哪、提交没提交），判分以服务端记录为主，回答内容需要时再用阅卷模型。

## 报告

`app/build/reports/browser-exam.md`：

- 每题：成 / 不成、工具调用几步、用时、失败卡在哪一步（最后几步工具调用和结果摘要）、token 和花费（元，用 `data/Pricing.kt`）。
- 整场：成功率、平均步数、总花费；阅卷开销单独列。
- 附每题完整的工具调用记录（可折叠），方便分析为什么走错。
- `$GITHUB_STEP_SUMMARY` 显示总分和总花费。

## 怎么跑

- 新建 `.github/workflows/browser-exam.yml`：**只手动触发**，读 Secret `DEEPSEEK_API_KEY`；可选输入 `only`（只跑某几题）。
- 没有 Key 时考试测试类 `Assume` 跳过，平时的「检查分支」不受影响。
- 整场 token 超过 300 万中途停下报错。

## 交活时写清楚

- 接口抽取的改动范围（上面第 4 点）。
- 有没有真跑过；没 Key 就写没跑过，不编分数。
- 你觉得哪几题最可能暴露 Kavi 的问题。
