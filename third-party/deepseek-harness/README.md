# DeepSeek Harness 设计借鉴

- 来源：https://github.com/deepseek-ai/deepseek-harness
- 参考提交：`ce7073d51254c26cd63b22a60835b8f948deb447`
- Copyright (c) 2026 DeepSeek
- 许可：MIT，原文保存在本目录 LICENSE。
- 按设计用 Kotlin 重写，没有复制 TypeScript 实现。

借鉴范围与 Jack AI 对应文件（路径相对 app/src/main/java/com/jack/ai/）：

- `packages/ptc-runtime/` 和 `.agents/notes/implemented/feature/2026-06-15-ptc.zh.md`：`data/CodeRunner.kt`、`data/JsRunner.kt`、`tools/CodeTools.kt`、`tools/ToolSession.kt`、`data/Skills.kt`、`chat/PromptBuilder.kt`。借鉴 Code Mode，将重复只读调用放进一段程序，保留普通工具的检查与统计。
- `packages/guard/timeout-policy/`、`packages/compaction/compaction-image-offload/`：`tools/ToolTimeout.kt`、`tools/ToolSession.kt`、`chat/ImageOffload.kt`、`chat/Agent.kt`：工具分类超时、确认等待不计时，以及请求图片超额降级。
- `packages/goal/`（goal、tool-goal、goal-round-driver）、`docs/subsystems/goal.zh.md`：对话持久目标、后台续做与目标状态条（第三项提交补齐实现路径）。
