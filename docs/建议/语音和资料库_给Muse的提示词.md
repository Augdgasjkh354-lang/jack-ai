请给仓库 Augdgasjkh354-lang/jack-ai 做「语音对话、资料库检索、浏览器整机考试」升级里的**第 1 步：边写边读**。Kavi 是一个安卓 AI 助手 App（Kotlin + Jetpack Compose）。

先读这三份，再动手：
1. `AGENTS.md`：规矩（最重要的一条：**不许推 `main`**，也不许合进 `main`）
2. `CLAUDE.md`：项目结构、本地怎么编译和跑单测
3. `docs/语音和资料库升级.md`：任务书。这次只做「第 1 步」那一节，别的步骤不要碰

要求：
- 分支用 `muse/voice-stream`，只做这一步，不顺手改别的。
- 动手前先用两三句中文说清楚打算怎么做；任务书里有拿不准的先问，不要自己脑补。
- `ChatViewModel.kt` 是热门文件，尽量不改；非改不可只改必要的几行，交活时写明。
- 推之前编译通过、跑完相关单测（命令在 `CLAUDE.md`「本地编译」一节）。
- 推完马上按 `AGENTS.md` 末尾的「交活模板」填好发给我，不用等「检查分支」跑完。

---

（Jack 用：下一步时把上面的「第 1 步：边写边读」和分支名换成任务书里对应的那一步：
第 2 步 `muse/voice-vad`、第 3 步 `muse/kb-bench`、第 4 步 `muse/kb-rerank`、第 5 步 `muse/browser-exam`。每一步都等 Claude 审过、合进 main 再开下一步。）
