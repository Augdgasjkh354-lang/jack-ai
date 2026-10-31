# Bug 修复（对应 tools/BUG_AUDIT.md 里的 5 条，分支 kavi/bug-fixes）

以下 5 条全部修了，只动任务需要的文件，没动 main。**注意：这次没法在这里编译 Android 工程、没跑单测**，推分支后请 Claude 先编译、跑相关单测，确认没问题再合。

## 1. XlsxBook 公式注入（XlsxBook.kt parse()）

- 原来：任何以 `=` 开头的格子都生成 `<f>` 公式。
- 现在：先过 `DANGEROUS_FORMULA` 黑名单——含 `!`、`[`、WEBSERVICE、HYPERLINK、IMPORTDATA/IMPORTXML/IMPORTFEED、DDE、EXEC、REGISTER、CALL、FILTERXML 的，一律当文字，不进公式；其余（SUM、AVERAGE、普通算术、单元格引用）照常当公式。
- 副作用：字符串里带 `!` 的公式（比如 `="Hi!"`）也会被当文字，安全方向上可以接受。

## 2. XlsxBook 条件格式引号转义顺序（XlsxBook.kt 条件格式那段）

- 原来：先 XML 转义（`"` → `&quot;`），再 doubling，就找不着引号了；contains 那条路干脆没 doubling。
- 现在：先 `vq = v.replace("\"", "\"\"")`，再 `esc(vq)`，cellIs 和 contains 两条路都用 vq。
- 验证方法：建一张表，加一条 contains 高亮，值里带 `"`，在 Excel/WPS 里打开不再弹修复提示。

## 3. docx/pdf 读入上限（Attachments.kt）

- 原来：30MB 的门只管 keepOriginal 拷原文件那步，不管提文字这步；readDocx 是整段 `readBytes()` 解压，压缩炸弹能直接顶 OOM。
- 现在：readDocx 改边读边数，解压后超 8MB（MAX_TEXT_BYTES，和纯文本一致）就抛“文件太大”；readPdf 包一层 `LimitedInputStream`，超 30MB（MAX_ORIGINAL_BYTES）就抛。报错走原来的 runCatching 路子，用户看到的是“文件太大”，不会闪退。

## 4. Token 存加密（新增 Secrets.kt + Store.kt）

- 新增 `data/Secrets.kt`：AndroidKeyStore + AES/GCM，不引新依赖。存的格式是 `v1:` + Base64(iv + 密文)；没前缀的当老版本明文读，下次保存时自动转加密；加解密失败返回 null，调用方按明文/空值处理，绝不崩溃、不丢设置。
- Store.kt 里 load/save/exportPrefs 三处统一走 Secrets：加密的键包括 apiKey、tavilyKey、exaKey、notionToken、githubToken、remoteKey、proxySub，以及 providers、mails、mcpServers、proxyNodes 四个 JSON 串（含里面的密码和 Key）。
- 换手机、恢复出厂后 KeyStore 密钥就没了，加密的值解不开会当空值处理，用户重新填 Key——和现在备份规则里“换手机重新填 Key”的规矩一致。
- exportPrefs 脱敏逻辑不变：先解密再脱敏，导出的备份里还是不含密钥；解密失败的直接落到默认值，不会把密文原样导出。

## 5. Updater 无 digest 时拒绝（Updater.kt）

- 原来：`sha256.isNotBlank()` 才校验，digest 为空就直接装。
- 现在：digest 为空也删文件并抛错，fail-closed。

## 改动文件

- `app/src/main/java/com/jack/ai/data/XlsxBook.kt`
- `app/src/main/java/com/jack/ai/data/Attachments.kt`
- `app/src/main/java/com/jack/ai/data/Secrets.kt`（新增）
- `app/src/main/java/com/jack/ai/data/Store.kt`
- `app/src/main/java/com/jack/ai/data/Updater.kt`
- `tools/BUG_FIXES.md`（本说明）
