# Mika 后台手机操作移植

- 来源：https://github.com/wangzan101/Mika
- 固定提交：`ebb0e44750d80aabb6b642c69908825d6b1a8107`
- Copyright 2026 wangzan101
- 许可：Apache-2.0，完整许可与原 NOTICE 保存在本目录，并随 APK 放在 `assets/licenses/mika/`。
- 上游文件清单：`FILES.txt`。保留 `com.wangzan101.mika` 命名空间，避免将上游代码误记为 Jack AI 原创。
- `ShellContext.kt` 内原有 scrcpy 版权声明保留，另见 `THIRD_PARTY_NOTICES.md`。

移植范围：无线调试 ADB 配对、密钥加密保存、本机发现与连接、辅助进程、Binder 交接、虚拟屏幕、画面采集及其直接依赖。未移植 Mika 的聊天、记忆、模型服务和浏览器。

另移植同一提交的 `core/src/main/resources/knowledge/apps/` 共 23 份 `APP.md`，原文保存在 `app/src/main/resources/knowledge/apps/`，未改动知识内容。自建 `index.txt` 用于枚举资源。它们遵循本目录的 Apache-2.0 与 NOTICE，在 Kavi 中按包名转换为内置 App 备忘，与用户备忘、手机流程一起在首次读屏时提供，不进入系统提示词。

Jack AI 适配（2026-11-02）：

- ADB 配对客户端显示名改为 Kavi；删去两个与后台助手无关的上游 UI/Agent 类型转换，避免引入整套 Agent 依赖。
- 新建 `PhoneServices` 宿主，接回原有设置、手机操作工具与任务收尾。不开启上游额外的自动系统授权与恢复选项。
- 为原有无障碍读屏、手势和输入增加明确的屏幕编号，主屏的「问这一屏」和教学仍用原路径。
- 新增后台模式开关、通知栏配对码输入、手动配对入口、实时画面、停止与人工接管。
- 配对私钥存放于 Android Keystore 包装的 `noBackupFilesDir`；助手交接口令和配对状态排除系统备份。
- 所有复制的 Kotlin 文件添加本移植出处说明；上游功能修改以文件头的 `Jack AI modification` 标记说明。

使用：设置 → 操作其他 App → 后台操作 App。开启无障碍、选好允许操作的 App，打开无线调试后在通知里完成配对。任务使用后台屏幕时可以从通知或设置查看画面；接管后任务暂停，回到后台屏幕页点「继续后台」恢复。

后台操作仅在 Android 12 及以上启用。未开启时保持原来的主屏操作；开启后连接失败不会自动操作主屏。

## 浏览器 CDP 移植（2026-11-03）

- 固定来源提交：`0091d2d67b541a417df913a23fefbb8e3a04e4a7`（Mika，Apache-2.0）。
- 仅移植 DevTools 本地套接字、HTTP/WebSocket、CDP 命令会话和标签匹配的直接依赖；保留上游命名空间。
- Jack AI modification：浏览器侧在 `com.jack.ai.ui.BrowserCdp` 使用精简适配层，保持 Kavi 原有浏览器界面和工具签名；CDP 不可用时使用原脚本输入。

- 第 2 步（元素融合）：基于同一提交的 `ElementFusion.kt`、`ElementProbe.kt`，为 Kavi 的稳定编号、网页脚本节点表及 CSS 像素坐标做 Jack AI modification。只读 CDP 无障碍树与 DOM 信息，不移植 Mika 的整套 WebHost、UiTree 或 Agent。
- 页面读屏以原脚本结果为基线，按 backendNodeId 对齐无障碍名称与角色；可见的跨源框架、封闭 shadow root 元素和直接挂点击监听器的文字补入清单。CDP 不可用时继续原脚本读页。

- 第 3 步（Network 域截接口）：增加 `NetworkLedger.kt`，同样固定参考 Mika `0091d2d`；保留上游命名空间及 Apache-2.0 版权说明。
- Jack AI modification：用 `BrowserNetwork` 为 Kavi 原有标签页订阅 XHR/fetch、限定正文大小并在任务结束前缓存 JSON/文本；`BrowserNetworkRows` 只把同网站、非敏感路径的结构化商品列表转换为 `extract(table)` 的原格式，失败退回原网页脚本。
- 未移植 Mika 的 `NetworkTracker.kt`（直接依赖上游 WebTab），由 Kavi 的精简会话适配代替；保留 `BrowserNetWatch` 原有等待和请求失败诊断。

- 第 4 步（节奏与风控）：基于 Mika `0091d2d` 的 `PagePacing.kt`、`WebBarrier.kt` 裁剪移植到 `app/src/main/mika/`，保留原命名空间、版权和 Apache-2.0 许可；不移植依赖上游 WebPort/ScreenFacts 的 `PageDwell.kt`。
- Jack AI modification：`BrowserPacing` 在 Kavi 的标签页按同站新页及滚动加载批次计时，首页和换站免等待、逐页增加停留、上限 12 秒；主文档 403/429/503、接口风控文字、短拦截页及可见验证码 iframe 会停止自动操作，交由现有 `wait_user` 流程，保留原浏览器 UI 和工具参数。

## App 操作移植（2026-11-05）

- 固定来源提交：`0091d2d67b541a417df913a23fefbb8e3a04e4a7`（Mika，Apache-2.0）。
- 第 1 步移植 `GesturePacing.kt`、`TypingPacing.kt`、`OperatingPace.kt`、`ActionEffect.kt` 及直接依赖 `Pace.kt`，保留上游命名空间，源码放在 `app/src/main/mika/`。
- Jack AI modification：删去对 Mika 的 TextField、AppPacing、ScreenSnapshot、Agent 的依赖，改用 Kavi 的无障碍控件树作为薄适配输入；手机手势优先真实触摸；坐标不可用、控件不在可见区域时才尝试节点动作。触摸派发一旦开始，取消也不补点，避免重复提交。后台虚拟屏幕同样调用无障碍的目标显示编号。
- 「像人」模式采用轻微落点偏移、落指前短停顿、续接的弧线滑动和按词递增输入；「快」模式保留固定手势、整段输入。动作之间按 App 节奏停顿和限流识别留到第 3 步。
- 判断效果只使用本次动作前后的控件树；界面有变化、无变化、读不准分别告知模型。无变化不自动重复触摸，防止发送、提交等按钮重复执行。
## App 操作移植：第 2 步（2026-11-05）

- 固定参考 Mika 提交 `0091d2d67b541a417df913a23fefbb8e3a04e4a7`，Apache-2.0；移植 `TreeQuality.kt`、`RefStabilizer.kt` 至 `app/src/main/mika/` 并保留 `com.wangzan101.mika` 命名空间。
- Jack AI modification：用 `PhoneScreenAnalysis` 把 Kavi 的 `UiNode` 和 `Element` 转成精简树质量、稳定编号输入；不移植 Mika 的 Agent、ScreenSnapshot、UiTree。
- `read` 在树完整时只返回原来的文字清单；遇到缺块、自绘区域或几乎读不到内容时自动附标号截图，编号在同一 App 的连续读屏中尽量稳定，新控件顺延编号。主屏和后台虚拟屏幕都只截自己的画面。
- 对接近全黑的截图提示用户接管，不继续操作；截图失败时保留纯文字并说明。原有 PhoneGuard 的禁用 App、付款页面和密码保护不变。
- `ScreenMap.kt` 和 `VisibleText.kt` 依赖 OCR；本步没有接入手机 App 的 OCR，故未移植。

## App 操作移植：第 3 步（2026-11-05）

- 固定参考 Mika 提交 `0091d2d67b541a417df913a23fefbb8e3a04e4a7`，Apache-2.0；裁剪移植 `AppPacing.kt`、`AppDwell.kt`、`LimitNotice.kt`、`ScreenDiagnosis.kt` 到 `app/src/main/mika/`，保留上游 `com.wangzan101.mika` 命名空间。
- Jack AI modification：Kavi 沿用原有 `Screen`、`PhoneControl` 和 `PhoneGuard`；删除上游对 `ScreenPort`、`ScreenSnapshot`、`PlacePace`、`Hant` 和 Agent 的依赖，通过精简参数适配。未引入额外自动滑动，避免改变模型手里现有的控件编号。
- 「像人」模式从上次改动完成时记下等待，模型读屏和思考时间一并抵扣；同屏约 0.5–1.2 秒、换屏约 0.9–2 秒，标为 careful 的 App 增加读字时间与连续浏览上限。批量同类操作额外随机间隔，旧 `actionIntervalMs` 作为下限。
- 「快」模式维持原来的固定动作间隔和已有的安全计数；限额和登录、验证码拦截仍生效。只识别动作后新出现的短限额字样，不会按原样再试。
- 验证码、登录等需用户处理的屏幕：主屏使用现有无障碍悬浮确认卡片，后台使用现有 `handOver()/resume()` 与通知接管，用户交回后重读屏幕；付款页面仍严格按 `PhoneGuard` 停止。重复确认失败后交给用户重新发起。
- 防截屏全黑画面二次采样核对，避免把加载时的瞬时黑屏当成防截屏。没有移植 `DwellStop.kt` 中依赖 Mika 工具框架的结果格式，使用 Kavi 既有中文工具反馈。

## 连接器 OAuth 登录移植（2026-11-06）

- 固定来源提交：`9fbbc89`（Mika，Apache-2.0）：`core/oauth/Pkce.kt`、`core/oauth/LoopbackCallback.kt`、`core/cap/mcp/McpOAuth.kt`，保留上游命名空间和版权。
- Jack AI modification：注册时客户端名写 Kavi；令牌存在连接器设置里（随设置一起用手机密钥库加密，不进备份），调用前快过期就自动续，接回 Kavi 原有的连接器客户端和网络路线。
