# 浏览器操作底层改造：对标 Mika

给接手的编程助手（Codex / GPT）看的任务书。先读 `AGENTS.md` 和 `CLAUDE.md`，规矩都适用：**不许推 `main`**，一步一个分支，交活按模板。

## 为什么改

Kavi 浏览器的功能已经很多，Jack 觉得「差点意思」的是 **Kavi 替他操作网页时不靠谱**：点了没反应、输入框填不进、读不全、弹窗和内嵌框架里的东西看不到。

原因在底层：Kavi 现在靠往网页里注入脚本（`app/src/main/assets/jack_browser.js` + `BrowserScripts`）来读页面、调 `click()`，不行再 `dispatchTouchEvent` 模拟触摸（`JackBrowser.touch`）。脚本发的事件 `isTrusted=false`，React/Vue 控件、自己画的控件、跨域 iframe、封闭 shadow root 都容易失手。

[Mika](https://github.com/wangzan101/Mika)（Apache-2.0）同样用系统 WebView，但打开了 WebView 的 DevTools 调试接口，自己通过 CDP（Chrome DevTools Protocol）接上本进程的 `webview_devtools_remote_<pid>`，用 `Input.dispatchMouseEvent` / `Input.dispatchKeyEvent` 发真实输入，用无障碍树补全元素，用 Network 域截接口数据。

## 参考代码

- Mika 固定参考提交：`0091d2d`（2026-11-03）。克隆：`git clone https://github.com/wangzan101/Mika.git && git -C Mika checkout 0091d2d`
- 重点看：
  - `core/src/main/kotlin/com/wangzan101/mika/core/cap/web/cdp/`（CdpTransport、CdpSession、CdpInput、InputPacing、WebSocket）
  - `app/src/main/java/com/wangzan101/mika/cap/web/cdp/WebDevtools.kt`（怎么连上本进程 WebView 的调试接口、连不上怎么办）
  - `core/.../cap/web/ElementFusion.kt`、`ElementProbe.kt`、`PageTree.kt`（第 2 步）
  - `core/.../cap/web/NetworkLedger.kt`（第 3 步）
  - `core/.../cap/web/PagePacing.kt`、`PageDwell.kt`、`WebBarrier.kt`（第 4 步）

## 移植规矩（和上次移植 Mika 后台屏幕一样）

- 照 `third-party/mika/README.md` 的做法：复制的文件保留 `com.wangzan101.mika` 命名空间，放进 `app/src/main/mika/`（已经是源码目录），文件头写移植出处，改过的地方标 `Jack AI modification`。
- 新增的上游文件追加到 `third-party/mika/FILES.txt`，并在 `third-party/mika/README.md` 里补一段「浏览器 CDP 移植」说明（参考提交号、范围）。
- 只搬需要的，别把 Mika 的 Agent、聊天、模型服务整套拖进来。依赖链太长时宁可在 Kavi 这边写一个薄的适配层。
- **不改 Kavi 浏览器的界面和现有功能**，只换「AI 怎么看、怎么点」这一层。工具名和参数（`BrowserTools.kt` 里的 click/type/key/tap/swipe/hover/select…）对模型保持不变。

## 分四步做，每步一个分支

### 第 1 步：接上调试接口，改用真实输入（分支 `gpt/browser-cdp-input`）

做什么：
1. 移植 CDP 连接层：连本进程 WebView 的 DevTools 套接字，按标签页找到对应 target，建会话。
2. `click`（按编号、按文字）、`tap`、`swipe`、`hover`、`type`、`key`、`delete` 改走 CDP 的真实鼠标/键盘/触摸事件；坐标从现有快照里元素的位置算（注意 CSS 像素和设备像素的换算，参考 Mika 的做法）。
3. 输入中文走 `Input.insertText`，按键（回车、退格、Tab）走 `Input.dispatchKeyEvent`。
4. **连不上 CDP 时自动退回现在的脚本做法**，不能比现在差。每次工具结果里不用告诉模型走的是哪条路，但在 `RunLog`（或现有的浏览器诊断）里记一笔，方便排查。
5. 调试开关的安全：`WebView.setWebContentsDebuggingEnabled(true)` 会让 USB 连着的电脑能检查 Kavi 的网页（包括登录状态）。只在 Kavi 要操作网页时打开，任务结束、App 退到后台一段时间后关掉；如果 WebView 要求必须在创建前打开，写清楚原因和取舍，交活时在「拿不准的」里说明。

验收（Claude 会逐条看）：
- 检查分支是绿的；新加的纯逻辑（坐标换算、按键映射、退回判断）有 1~3 个单测。
- 现有浏览器单测全过，`BrowserTools` 对模型的工具说明没变（`PromptBuilderTest` 缓存相关测试不受影响）。
- 交活时写清楚：哪些操作已经走 CDP、哪些还走旧路、连不上时怎么退回。

### 第 2 步：元素融合（分支 `gpt/browser-cdp-elements`）

读页面时，把现在脚本扫出的元素和 CDP 的无障碍树（`Accessibility.getFullAXTree`）按 `backendNodeId` 合成一份：跨域框架里的可交互元素、封闭 shadow root 里的、挂了点击监听的纯文字都能列出来；被挡住的不列。编号格式和现在一致，模型无感。

### 第 3 步：截接口数据（分支 `gpt/browser-cdp-network`）

用 Network 域记下页面发出的 XHR/fetch 和返回（只记 JSON/文本，限大小），`extract` 读列表、比价时优先用接口数据，没有再抠页面文字。现有的 `BrowserNetWatch` 可以保留做退路。

### 第 4 步：节奏和限流识别（分支 `gpt/browser-cdp-pacing`）

同一网站连续翻页像人一样停一下（参考 `PagePacing`，首页不等、换站不等、有上下限）；认出 403/429/503、「访问受限」「频繁」和常见验证码框架（`WebBarrier`），停手交给 Jack，接上现有的「请你来做」流程。

## 考题（每步做完由 Jack 在手机上跑，Claude 对比结果）

先用这 10 道，Jack 有失败的真实例子就替换进来：

1. 京东搜「保温杯」，按销量排序，读前 10 个商品的名称、价格、店铺
2. 淘宝/天猫网页版搜一个商品，读第一页列表（要登录就交给 Jack）
3. B 站搜一个关键词，打开第一个视频，读标题、播放量、简介
4. 知乎搜一个问题，读点赞最高的回答前 500 字
5. 小红书网页版搜一个关键词，读前 5 篇笔记标题
6. 12306 网页查明天北京到上海的车次（只查不买）
7. 在百度里输入中文搜索并回车，读前 5 条结果标题
8. Google 航班查一条航线最便宜的直飞
9. 一个带下拉框和日期框的表单（如携程酒店：选城市、入住日期），填好停在提交前
10. 1688、京东、拼多多三站同时比同一商品价格，合成一张表

每题记：成功/失败、用了几步、卡在哪。第 1 步前先跑一遍当基线。
