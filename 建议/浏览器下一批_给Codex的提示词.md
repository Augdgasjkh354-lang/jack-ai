请在仓库 Augdgasjkh354-lang/jack-ai 里给 Kavi 浏览器加 5 个功能。先读 `AGENTS.md`、`CLAUDE.md`（规矩、编译方法都在里面），再读下面列的文件。

**分支**：在 `codex/browser-extras` 上做，推这个分支，**不许推 `main`**。一个功能一个提交，提交说明用英文写清改了什么、为什么。推完看「检查分支」是不是绿的，红了修到绿再交。

## 先读这些

- `app/src/main/java/com/jack/ai/ui/JackBrowser.kt`：浏览器的状态和网页窗口（标签页 `Tab`、下载 `saveToPhone`/`store`、`pauseAll`、`snap`、偏好存在 `prefs()` 里）
- `app/src/main/java/com/jack/ai/ui/BrowserActivity.kt`：浏览器页面（地址栏、菜单 `DropdownMenu`、起始页 `StartPage`、`savePdf()`、`HistoryPanel`）
- `app/src/main/java/com/jack/ai/ui/JackWebView.kt`、`app/src/main/java/com/jack/ai/api/BrowserPages.kt`（纯逻辑，单测在 `app/src/test/java/com/jack/ai/api/BrowserPagesTest.kt`）
- `app/src/main/java/com/jack/ai/api/BrowserNet.kt`（`normalize()` 决定搜索用哪个网站）
- `app/src/main/java/com/jack/ai/api/BrowserDownload.kt`、`app/src/main/java/com/jack/ai/data/Downloads.kt`、`app/src/main/java/com/jack/ai/ui/DownloadsScreen.kt`（现在的下载）
- 现有的前台服务写法参考 `app/src/main/java/com/jack/ai/WorkService.kt`

## 1. 后台播放的常驻通知

现在：网页在放声音时切到后台会接着放（`BrowserPages.KEEP_PLAYING_JS`），但系统可能把 App 杀掉，也没地方控制播放。

要做：
- 浏览器里有声音在放、并且用户离开了浏览器页时，起一个前台服务，显示媒体通知：网页标题、网站名，按钮有「暂停 / 播放」「停止」。用 `androidx.media` 的 `MediaSessionCompat` + `MediaStyle`，锁屏和耳机按键也能控制。
- 怎么知道在放：给网页加一个开头注入的脚本（照 `KEEP_PLAYING_JS` 的装法，每个标签页都装），监听 `video`/`audio` 的 `play`、`pause`、`ended`，用 `console.log` 发带固定前缀的消息，在 `JackBrowser` 的 `onConsoleMessage` 里接（照 `BrowserBlocks.PREFIX` 的写法）。
- 暂停 / 播放 / 停止都是对发声的那个标签页执行 JS。点「停止」或网页自己停了超过 1 分钟，就关掉服务和通知。用户回到浏览器页时，通知可以留着，但服务不要停。
- 用户点关闭浏览器（`onDestroy` 里 `isFinishing`）时已经会调 `pauseAll()`，这时也要关掉服务。
- 清单里加 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限，服务类型写 `mediaPlayback`。通知渠道名用「网页播放」。

## 2. 阅读列表（稍后读）

- 菜单加「加入阅读列表」（已在列表里时显示「移出阅读列表」）。长按链接的菜单（`PressMenu`）也加一项「稍后读」。
- 存：网址、标题、加入时间、读没读过。存法照书签（`BrowserPages.Visit` + `prefs()`），另起一个 key。最多 200 条。
- 起始页在「书签」上面加「阅读列表」分组，只列没读过的，最多 10 条，后面跟一个「全部」按钮。点「全部」开一个和 `HistoryPanel` 一样风格的面板，分「未读 / 已读」两组，能删单条。
- 从阅读列表打开一篇，就把它标成已读。
- **离线**：加入时如果页面已经打开，顺手用现有的 `JackBrowser.readable()` 把正文（Markdown）存进 App 私有目录 `files/readlater/<网址的 sha1>.md`。没网打不开时，显示这份正文，用现在阅读模式的显示方式。删条目时把文件一起删掉。

## 3. 选搜索引擎

- 菜单加「搜索引擎」，弹窗选：自动（现在的行为：走代理用 Google，直连用必应）、Google、必应、百度、DuckDuckGo。存进偏好。
- 改 `BrowserNet.normalize()`：多一个搜索引擎参数，按选的拼搜索地址。「自动」保持现在的逻辑不变。
- 起始页的说明文字不用改。给 `normalize` 补一个单测，覆盖每个引擎的拼法和「自动」。

## 4. 更好用的下载管理

现在浏览器同一时间只能下一个文件（`JackBrowser.downloading` 是一行进度文字），不能暂停、取消，离开浏览器页进度也看不到。

要做：
- 同时最多下 3 个，多的排队。每个下载有自己的状态：排队、下载中（已下多少 / 共多少、速度）、暂停、完成、失败（原因）。
- 能暂停、继续、取消。继续要从断的地方接着下（HTTP Range）；服务器不支持 Range 就从头下，并提示一句。取消要删掉没下完的文件（`Downloads.Target.discard()`）。
- 下载时有一条系统通知显示进度（几个下载合成一条，写「正在下载 2 个文件，45%」），点通知打开侧边栏的「下载」页面。下载放进前台服务跑，离开浏览器页、锁屏也不停。
- 浏览器底部那一行进度改成：「正在下载 N 个 · 点这里看」，点开一个下载列表面板（和 `HistoryPanel` 一个风格），里面每个下载能暂停、继续、取消；下好的能打开、分享。
- `DownloadsScreen` 里正在下的也要显示出来，带同样的按钮。
- `blob:`/`data:` 这类网页自己生成的文件（`blobBytes`）是一次性拿到的，不支持暂停，按钮置灰就行。
- Kavi 操作时的下载确认流程（`pending` + `download` 动作）不要动，只是确认后进同一个队列。

## 5. 网页长截图

- 菜单加「长截图」：把整页从上到下截成一张图，存进手机相册的「Pictures/Kavi」文件夹（用 `MediaStore`），存完提示，并给「打开」「分享」两个按钮。
- 做法：参考 `PageShots.kt` 里画网页的方法（`w.draw(c)`）。按屏幕高度一屏一屏滚动，每屏之间等 300ms，让懒加载的图片出来，再拼起来，最后滚回原来的位置。
- 拼之前藏掉吸顶或吸底的元素（`position: fixed`/`sticky`），不然每屏都重复出现；截完恢复。
- 最长 20 屏，超过就截前 20 屏并提示「页面太长，只截了前面一部分」。图宽按手机屏幕宽，存 JPEG，质量 85。截图期间显示「正在截图…」，可以取消。
- 电脑版页面（很宽）照样按 WebView 实际显示的宽度截。

## 通用要求

- 界面文字、注释都用中文，跟周围代码的风格一致（看 `BrowserActivity.kt` 的弹窗和菜单写法）。
- 隐身标签页：阅读列表不能加（菜单项置灰）；它的下载照常下，但不记进下载记录；不显示后台播放通知里的标题，写「隐身标签页」。
- Kavi 自己操作浏览器（`JackBrowser.userViewing == false`）时，不要弹任何新加的东西。
- README.md 里浏览器那几条后面补一句，说清楚这 5 个功能。
- 单测只给纯逻辑加（搜索地址拼法、下载队列的状态变化、阅读列表的增删和已读），每个功能 1 到 3 个就够了，按 `AGENTS.md` 的「够用就行」来。
- 不要改这次没涉及的东西，不要升级依赖。要加 `androidx.media` 依赖的话，版本写死，并在提交说明里写明。

做完告诉 Jack：分支名、每个功能他在手机上哪里能看到、哪些地方你没法测（比如通知、锁屏控制）需要他在手机上试。
