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
