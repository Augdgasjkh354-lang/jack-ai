# App 操作底层改造：对标 Mika

给接手的编程助手（Codex / GPT）看的任务书。先读 `AGENTS.md`、`CLAUDE.md`，再看 `docs/browser-cdp.md`（浏览器那次的做法和规矩，这次照搬）。**不许推 `main`**，一步一个分支，交活按模板。

## 为什么改

浏览器已经换成 Mika 的底层做法，「操作其他 App」也要做深。Kavi 现在的做法（`a11y/KaviAccessibility.kt`、`tools/PhoneTools.kt`）：

- **点**：先对控件 `performAction(ACTION_CLICK)`，不行再 `dispatchGesture` 在中心点一下。很多 App（微信、抖音、自绘界面）不认无障碍点击，或者点了没反应 Kavi 也不知道。手势是一条直线、固定 60ms，一看就是机器。
- **看**：只读无障碍树。树质量差（控件没名字、Flutter / 游戏 / 自绘界面几乎读不到）时，模型只能自己想起来去截图。
- **节奏**：只有 `policy.actionIntervalMs` 一个固定间隔。批量操作（BOSS 打招呼、小红书逐条看）容易被判成机器，也认不出「操作太频繁」「今天次数用完了」这类提示。

Mika 在这三块都有成熟的纯逻辑模块（`core/.../uistate/`、`core/.../cap/phone/`），Apache-2.0，可以移植。

**不做**：截别的 App 的网络数据（要装证书中间人，有安全和条款风险）。

## 参考代码

- Mika 固定参考提交：`0091d2d`（和浏览器那次同一个）。`git clone https://github.com/wangzan101/Mika.git && git -C Mika checkout 0091d2d`
- 重点看：
  - 第 1 步：`core/src/main/kotlin/com/wangzan101/mika/core/cap/phone/GesturePacing.kt`、`TypingPacing.kt`、`OperatingPace.kt`；`core/.../uistate/ActionEffect.kt`、`EffectTuning.kt`
  - 第 2 步：`core/.../uistate/TreeQuality.kt`、`RefStabilizer.kt`、`ScreenMap.kt`、`VisibleText.kt`
  - 第 3 步：`core/.../cap/phone/AppPacing.kt`、`AppDwell.kt`；`core/.../uistate/ScreenDiagnosis.kt`、`LimitNotice.kt`、`DwellStop.kt`

## 移植规矩（和前两次一样）

- 复制的文件保留 `com.wangzan101.mika` 命名空间，放进 `app/src/main/mika/`，文件头写出处，改过的地方标 `Jack AI modification`；新增文件追加到 `third-party/mika/FILES.txt`，`third-party/mika/README.md` 补一段「App 操作移植」。
- 只搬纯逻辑和直接依赖，不拖 Mika 的 Agent、UiTree 整套数据结构；在 Kavi 这边写薄适配层，把 Kavi 的 `Element`（`KaviAccessibility`）转成 Mika 模块要的输入。
- **不改对模型的工具名和参数**（`PhoneTools.kt` 里的 open / read / screenshot / tap / long_press / type / enter / swipe / back / home / wait），结果文字可以多写几句（比如「点了没反应」「这一屏树读不全，已附截图」）。
- **`PhoneGuard` 的规矩一条都不能松**：钱相关的 App 和付款页不碰、密码框不读不填、往外做事的按钮先问。
- 主屏（无障碍）和后台屏幕（`BackgroundPhone`，Mika 的虚拟显示）两条路都要能用；后台屏幕走不通的新功能就只在主屏开，不能比现在差。

## 分三步做，每步一个分支

### 第 1 步：像人的手势 + 点没点上（分支 `gpt/app-gesture`）

1. 移植 `GesturePacing`：落指前停一下、按住时长有长有短、落点在控件中心附近略偏（始终在控件里）、滑动走小弧线先快后慢。`KaviAccessibility.tap / swipe / gesture` 改用它生成路径和时长。
2. 点击顺序调整：**能用手势点的优先用手势**（真实触摸），`performAction(ACTION_CLICK)` 作为后备（控件在屏外、被挡住、坐标拿不到时）。拿不准哪个更好的情况，交活时写清楚。
3. 移植 `ActionEffect`：每次点 / 输入 / 滑动后，对比前后的控件树判断「明显变了 / 没变化 / 拿不准」。没变化时在工具结果里明确告诉模型「点了没反应」，别让它以为成功了；不自动重复点（可能是发送、提交类按钮）。
4. 移植 `TypingPacing` 和 `OperatingPace`：设置 → 操作其他 App 里加「操作节奏」：**像人（默认）** / 快（现在的做法，打字一次写入）。像人模式下打字按词上屏，动作之间不另加停顿（停顿是第 3 步的事）。

验收：检查分支是绿的；手势路径生成、效果判断各 1~2 个单测；`PhoneGuard` 相关单测不受影响；交活写清楚手势优先和后备的判断条件。

### 第 2 步：树读不全时自动带截图（分支 `gpt/app-screen`）

1. 移植 `TreeQuality`：读屏时判断这一屏的无障碍树「够用 / 缺一块 / 几乎读不到 / 防截屏」。
2. 「缺一块」「几乎读不到」时，`read` 自动附上截图（复用现有 `screenshot(marks)` 的标号截图），并在结果里说明「树读不全，已附截图，可以按截图坐标 tap」；「防截屏」（画面全黑）直接说明要用户来。
3. 移植 `RefStabilizer`：同一屏反复 read 时控件编号保持不变，新出现的往后编（和浏览器一样），模型不会因为编号乱跳点错。
4. `ScreenMap`（OCR 文字和控件对位置）可选：Kavi 现在没有本地 OCR 就不做，交活时说明。

验收：检查分支是绿的；`TreeQuality` 判定、编号稳定各 1~2 个单测；树正常的 App 读屏结果和现在一样（不多附截图、不多花 token）。

### 第 3 步：节奏和拦截识别（分支 `gpt/app-pacing`）

1. 移植 `AppPacing` / `AppDwell`：每个改动屏幕的动作后按「有没有换到新的一屏」定下一个动作至少隔多久，到新的一屏先停一会儿；模型思考已经花掉的时间算进去，不重复等。替换现在的 `policy.actionIntervalMs`（原设置保留为下限）。只在「像人」模式下生效。
2. 批量操作（同一种动作对一串对象重复做，比如逐个打招呼）每条之间随机间隔，参考 Mika 的批量规则。
3. 移植 `LimitNotice`：动作之后新冒出来的「操作太频繁」「今天次数用完了」「明天再来」这类短提示，认出来就停手，告诉用户原因，不继续重试。
4. 移植 `ScreenDiagnosis`：登录页、验证码、防截屏黑屏、一直在加载，分别给出状态；需要人的接上现有「请你来做 / 接管」流程（主屏弹卡片，后台屏幕走通知里的接管），用户处理完自动接着做。和 `PhoneGuard` 的付款判断不冲突：付款仍然一律停。

验收：检查分支是绿的；节奏计算、限额提示识别各 1~2 个单测；「快」模式下行为和现在一样。

## 考题（每步做完由 Jack 在手机上跑，Claude 对比结果）

先用这些，Jack 有失败的真实例子就替换进来：

1. 微信：给「文件传输助手」发一句话
2. 微信：读某个群今天的聊天，总结要点（只读不发）
3. 小红书：搜一个关键词，读前 5 篇笔记标题和点赞数
4. 抖音：搜一个博主，读最近 5 条视频标题
5. 淘宝：搜一个商品，读前 10 个结果的名称和价格（不下单）
6. BOSS 直聘：按条件筛 3 个职位，读职位名和薪资（先不打招呼）
7. 美团 / 饿了么：搜一家店，读菜单前 10 项（不下单）
8. 闹钟 / 时钟：定一个明早 7 点的闹钟
9. 一个 Flutter 或自绘界面的 App（Jack 选一个平时点不动的）
10. 后台屏幕模式下重做第 1、3 题

每题记：成功 / 失败、用了几步、卡在哪。第 1 步开工前先跑一遍当基线。
