# 中危修复批（2026-10-31）

基于 main 3c90ca5，分支 kavi/fix-medium-1。只修第二轮深挖里中危 36 条中「先修这批」约 30 条；
动静大的 3 件（run_python 真沙箱、网页会话改随机 token、WorkService/RecordService 被杀后持久化）单独立项，本批不动。
**没有编译、没有跑单测**：合 main 前请 Claude 在其环境里编译 + 跑相关单测，并真机抽查标注了真机项的几处。

## 安全类

- `api/BrowserDownload.kt`：新增 `guardCookieOnRedirect()`（应用拦截器记最初的站，网络拦截器每次跳转比对：
  站变了、或 https 降级成 http 就摘掉 Cookie——OkHttp 只剥离跨站 Authorization，不剥 Cookie），
  `client()` 全量接上，fetch/head 都覆盖；新增 `readBoundedBytes()`（边读边数，超限返回 null）。
- `api/MediaGrab.kt`：`client()` 接上 Cookie 重定向防护；`text()`/`bytes()` 改用 `readBoundedBytes()`，
  分块传输（contentLength = -1）时长度头拦不住的问题一并修掉；`hex()` 非十六进制字符返回 null，不再抛未检查异常。
- `api/TavilyClient.kt`：`download()` 改用 `readBoundedBytes()`，分块超限返回 null。
- `api/BrowserNet.kt`：`isLocal()` 改按 inet_aton 语义解析 IPv4（十进制整数、0x 十六进制、0 开头八进制、127.1 这类缩写），
  并处理 IPv4 映射地址 `::ffff:127.0.0.1` 和展开写的回环 `0:0:0:0:0:0:0:1`。原来只认点分十进制，2130706433、0x7f.0.0.1 都能绕过。
- `tools/ToolSession.kt`：注入门禁改传 `strict = true`（完全授权也不能放行门禁，否则门禁在完全授权下等于没有）；
  `sawExternal` 的口径从 EXTERNAL_TOOLS 扩到 SCANNED_TOOLS（读文件、手机文件、电话、手机数据同样算外部来源，
  否则读了文件里的注入内容再往外发信时，严格确认会被绕过）。
- `data/InjectionGuard.kt`：`gated()` 加上 `run_python`/`run_js`（读到注入后跑代码先问用户；真正的沙箱另行再做）。
- `data/Updater.kt`：下载后加 `checkSignature()`：安装包签名要和已装应用一致，对不上或读不出来就删掉、不提示安装
  （只验 SHA-256 只能证明文件是发布者原样，证明不了发布者就是装机时那个签名方；API 28 以下走旧 GET_SIGNATURES）。
  注意：kavi/bug-fixes 分支也在 Updater 的下载校验附近改过（digest 为空时 fail-closed），两分支合 main 时这处附近可能冲突，保留两边检查即可。
- `.github/workflows/build.yml`：checkout 加 `persist-credentials: false`；sing-box 锁死提交
  `7054cac5124465ffdb145870d7c01885566711b1`（v1.14.3，ls-remote 核实过，和 hev 那处做法一样）；
  CI 的 pip 锁死 `python-docx==1.2.0 python-pptx==1.0.2 openpyxl==3.1.5`（PyPI 当天最新版核实过）。
- `assets/jack_browser.js`：`describe()` 里密码、电话、邮箱、验证码类（autocomplete one-time-code/cc-number、标签/占位/名字含验证码/卡号/身份证等）
  只报「已填」，原值不进模型上下文。原来只掩密码。

## 丢数据类

- `data/MemoryOps.kt`：`find()`/`findDeep()` 前缀撞车时返回 -1（原来取第一条，撞车就是静默改错记忆）；
  `norm()` 不再去掉小数点和千分位逗号（原来「3.5%」和「35%」会被当成同一条，纠正被吞掉）。
- `data/OwnMemory.kt`：`find()` 同样改成撞车返回 -1。
- `data/MemoryReview.kt`：新增 `applyItems()`，直接在带 id 的记忆上改，改过的条目保留 id、历史、用量和来源；
  原来先转字符串、再按文字拼回去，改过的变新条目（丢 id 和历史），同文字的多条还会被合并成一条。
- `ChatViewModel.kt`：回看的全局记忆改走 `applyItems()`（工作记忆是纯字符串文件，继续走 `apply()`）；
  插话队列不再 `clear()`：上一条回答收尾、新回答还没开始跑的窗口里，用户发的话会被当成插话塞进队列，clear 会把它静默丢掉。
  不 clear 是安全的：回答结束时没用上的插话本来就会重发成新消息。
- `chat/ContextSlim.kt`：`dropLast(KEEP_RECENT)` 改成至少留一条（原来工具消息 ≤6 条时返回空，永远压不到）。
- `data/Dreamer.kt`：日记先校验再写记忆（原来先写完记忆、存档、清暂存，才在建条目时因日记为空抛错，等于一次没凭证的整理）。
- `data/FreeConversation.kt`：`load()` 解析失败时先把损坏的原文归档成 `free_corrupt_<时间>.json` 再返回空状态
  （原来直接返回空，下一次 save 会把坏文件覆盖掉，原文再也找不回来；归档的不挂待整理标记）。
- `data/Backup.kt`：导入改成临时文件 + 同目录改名（原子写，写一半崩了不留半截目标文件；临时文件名前缀和导出过滤一致）；
  新增 `restoreRollback()`：把最近一次恢复前备份的旧文件写回去（原来回滚目录只写不读）。界面入口（设置里加一个「恢复到上次恢复前」）留给合分支时接。
- `data/Routines.kt`：`fire()` 的保存/刷新包进 try，`schedule()` 放 finally（原来先副作用后持久化，中间一抛错下次执行就排不上了，重复任务静默停摆）；
  `AlarmReceiver` 加上 TIME_SET 和 TIMEZONE_CHANGED（改系统时间、跨时区后提醒重排）。
- `AndroidManifest.xml`：AlarmReceiver 的 intent-filter 加上 TIME_SET 和 TIMEZONE_CHANGED。
- `RecordService.kt`：前台通知 ID 7401 → 7402（原来和 RemoteService 的 7401 撞号，互相顶掉按钮）。

## 花钱类

- `data/FreePool.kt`：没有价格页核对的服务（DashScope），探测调通了也不再当免费收——调通了也可能是付费模型在扣余额；
  只有明确标着「仅免费额度」的（probeResult 2）才算免费。硅基流动有价格页核对，行为不变。
  附带影响：DashScope 的免费模型在免费额度还有剩余时也不会被自动当免费池用（探测分不出来），这是 fail-closed 的代价；
  要恢复的话得给 DashScope 也接一个价格核对源。
  另外：所有服务都没列出模型时不保存当天的 checkedAt（原来全失败也写 checkedAt，当天不再重试，免费池静默停摆一整天）。
- `data/Pricing.kt`：`priceAt()` 不认识的 DeepSeek 模型返回 null（原来按 Flash 价估，用量统计会系统性偏低；调用方都已处理 null）。
- `api/WebClient.kt`：联网 Key 全在冷却中时抛错（原来返回空结果，模型会以为「搜过了，什么都没有」）。
- `data/Pricing.kt` 价格表**没有改**：审计说 DeepSeek 价格低估了约 4-6%，但 2026-10-31 现查官方价目页
  （api-docs.deepseek.com/zh-cn/quick_start/pricing），Flash 高峰 0.04/2.0/8.0、Pro 高峰 0.30/9.0/27.0 和表里一字不差，审计那条是误报。

## 崩溃、坏文件类

- `data/XlsxBook.kt`：图表分类列是「ZZ」这种字母、超出表宽时不再直接当下标（会越界崩溃），超宽时警告并用第 0 列；
  工作表名去重后缀每次递增（原来固定用 i+1，28 个 A + "_2" 这种名字会原地不动死循环）；
  `parse()` 算不出来的数（1e999 → Infinity）当文字留着，`num()` 非有限数写 0，不再让 NaN/Infinity 进 XML。
- `data/PptChart.kt`：`clean()` 里非有限数当成 0 并记一条 problems（原来直接写进 XML，Office 判文件损坏）。
- `data/WordDoc.kt`：标题只认围栏外的「# 」（代码块里的「# 注释」不再被当标题吞掉正文第一行）；
  `headingCount` 改用下标算（原来 `lines.indexOf(it)` 会让同文字的行指到同一处）。
- `data/ChartWriter.kt`：饼图画第一组有正数的系列（原来固定画第一组，全是 0 或负数就是空饼）。
- `data/PdfWriter.kt`：`table()` 里一行比一页还高时按页切开画（边框和表头底色跟着切），不再整行换页后把超出的部分裁掉。
- `ui/DocViewer.kt`：`render()` 按总像素封顶 1600 万（等比缩小），超长页不再一张位图吃掉几百 MB。
- `data/MediaMux.kt`：`toWav()` 转码时按实际解出来的采样数算上限（元数据里没写时长时原来的上限拦不住；超了抛错，已写的文件由外层 catch 删掉）。

## 本批没动（留给后面）

- run_python 真沙箱、网页会话改随机 token、WorkService/RecordService 被杀后持久化（动静大，单独立项）。
- Chaquopy 的 pip 依赖没锁版本：手机端构建的 wheel 兼容性没法在不编译的情况下核实，锁错版本会直接断掉打包，等 Claude 在构建环境里定版本。
- 事实库「来源只验 URL 读过、不验内容」不在本批里：URL 核对改不成内容核对，要会话里留住读过的页面正文、存事实时比对文本重合度，得另做一次管子改造。
- Backup 的 `restoreRollback()` 界面入口未接（见上）。

## 待 Claude 验证

- 编译 + 相关单测（本批没有在本机编译验证过）。
- 真机抽查：Updater 签名校验（新包覆盖安装一次）、PdfWriter 超高行分页、DocViewer 超长页、MediaMux 无时长音频转码、备份导入失败回滚。
