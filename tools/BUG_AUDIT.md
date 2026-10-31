# jack-ai 静态 Bug 审计（2026-10-31，基于 main 3c90ca5）

方式：230 个 Kotlin 文件 + remote-site Worker + Manifest + 打包 workflow，先用模式扫描（`!!`、WebView 设置、Zip、数字解析、Cursor、硬编码密钥、导出组件），再对高风险文件精读。**没法在这里编译 Android 工程、没法真机跑**，所以这份是静态审计：UI 交互、Compose 重组、VPN 实际流量、无障碍实操这些动态行为没验。

## 确认的 Bug（按严重度排）

### 1. 低：XlsxBook 条件格式里引号转义顺序错了，值里带 `"` 就坏公式

- 位置：`app/src/main/java/com/jack/ai/data/XlsxBook.kt`（条件格式那段，cellIs 和 contains 两条路）。
- 现状：`esc()` 先把 `"` 转成 `&quot;`，cellIs 路再 `replace("\"", "\"\"")` 就找不着引号了；contains 路干脆没做 Excel 转义，直接 `SEARCH("${esc(v)}",...)`。
- 后果：高亮值里带 `"` 时，Excel 读到的公式引号不配对，打开文件可能弹修复提示或规则丢失。
- 修法：顺序反过来——先 doubling（`v.replace("\"", "\"\"")`），再 XML 转义。

### 2. 低-中：XlsxBook 里任何以 `=` 开头的格子都会变成可执行公式

- 位置：`XlsxBook.parse()`（`v.startsWith("=")` 就 `Cell(formula = ...)`，生成 `<f>`）。
- 后果：模型或网页内容里以 `=` 开头的文本（比如 `=WEBSERVICE(...)`、`HYPERLINK`）会变成 live 公式；文件分享给别人打开就可能触发。生成方和打开方通常是同一用户，所以定低-中，不是高危。
- 修法：只有明确标记的公式才走 `<f>`，普通文本当 inlineStr；或者只放行白名单函数（SUM、AVERAGE 这类纯计算）。

### 3. 低：读 docx/pdf 的大文件没设上限，构造好的文件能顶 OOM

- 位置：`Attachments.readDocx()` 用 `zip.readBytes()` 整段读入内存；`readPdf()` 用 `PDDocument.load` 全量加载。
- 现状：调用方事后才按 MAX_FILE_CHARS 截断文本，来不及——超大 document.xml 或 PDF 先把内存占满。
- 修法：读之前先看 entry 大小（或用带上限的流），超了直接拒绝或截断。

### 4. 低（加固建议）：Token 只放普通 SharedPreferences，未加密

- 位置：`Store`（`getSharedPreferences("settings", MODE_PRIVATE)`）。
- 现状：settings.xml 已排除云备份和换机迁移，这点做得对；但本机有 root 或备份工具仍可直接读。
- 修法：关键 Key 改用 EncryptedSharedPreferences，或至少把 Token 类字段单独加密存。

### 5. 信息：Updater 在 release 没有 digest 时会跳过校验

- 位置：`Updater.checkAndDownload()`（`if (l.sha256.isNotBlank() && ...)`）。
- 现状：digest 为空就直接装。当前 GitHub 都会给 digest，实际触发概率低。
- 修法：digest 为空时也拒绝，fail-closed。

## 查过、确认没问题的（免得重复查）

- **100 个 `!!`**：PptDeck 的 19 个是形状名 `!!Title`、`!!Decor` 这种 morph 标记，不是断言；唯一真的断言（读 icons.json）在 runCatching 里；GitHubTools 的 18 个前面都有 null 守卫（needsRepo / needLocal / n 判空）。
- **Zip 三处**：Backup 恢复（allowlist + 拒 `..` 和反斜杠 + 200MB/单文件和剩余空间一半上限 + 先验 JSON 再动数据）、GitHub 解压（去顶层目录 + canonical 路径检查 + 文件数和总字节上限）、docx 读取（只读 word/document.xml）。Zip Slip 和压缩炸弹都防了。
- **WebView 三处**：AppBridge 只读写小工具自己的 `.appdata` 数据文件；DocViewer 只放行自带 viewer 文件和当前文档，其余全拦；纯查看模式 JS 全关、无 bridge。即使文档内容里有注入，也出不去。
- **Manifest**：除系统绑定的语音/通知服务外全部 exported=false；FileProvider 不导出；备份规则排除了 settings.xml、浏览器 Cookie、运行日志。
- **Updater**：下载后比对 SHA-256，不对就删文件并抛错；ready() 会校验文件存在。
- **下载文件名**：Content-Disposition / URL 取名时已把 `/\:*?"<>|` 换成下划线、去前导点、限 120 字，防路径穿越。
- **远程中转 Worker**：设备接口要 Bearer 密钥（等时比较），网页接口要 HMAC 会话 Cookie，登录限流 10 次/10 分钟/IP，上传限 25MB、文件名去斜杠。
- **定时任务与服务**：重启和 App 更新后 AlarmReceiver 会重排全部 routines；代理内核 6 秒起不来就杀掉，含密码的配置文件读完就删；录音服务 stop/release 都有兜底；本地模型闲置回收在独立 daemon 线程，不占主线程。
- **PPT 生成链**：escXml 会清控制字符；图片宽高为 0 有 4:3 兜底；图表/图片/演讲稿的 rId（rId2/rId3/rId4）不冲突；shape id 每页从 2 重置；图片扩展名和字节格式一致（PNG 原样、其余转 JPEG）。
- **其他**：日志有 ghp_/sk-/github_pat_ 等脱敏前缀表；无硬编码密钥；无 GlobalScope/runBlocking；无 TODO/FIXME 残留；Cursor 基本都包在 `?.use {}` 里。

## 没覆盖到的

- 230 个文件是“高危模式全扫 + 重点文件精读”，不是逐行通读；动态行为（真机、VPN 流量、a11y 实操、Compose 交互）没验。
- 建议合分支后让 Claude 在 Android 工程里跑一遍编译 + 在真机上开一次含 `"` 高亮值和 `=` 开头格子的 xlsx，确认第 1、2 条的实际表现。
