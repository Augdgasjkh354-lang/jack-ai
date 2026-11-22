# 3 条高危修复说明（分支 kavi/fix-high-3，基于 main 3c90ca5）

本分支只修第二轮深挖里确认的 3 条高危，不动其他条目；合 main 前请 Claude 编译 + 跑相关单测（本分支未在本机编译验证）。

## 1. 远程文件名穿越写设置
- `app/src/main/java/com/jack/ai/tools/RemoteChat.kt`：下载路径的文件名改走 `Workspace.safeName(f.name)`（斜杠等换成下划线、去首部圆点、限 80 字），`../` 再也拼不出去。
- `remote-site/src/worker.js` 的 `send()`：存库前把每个 file 的 name 按 `upload()` 同款处理（斜杠换下划线、限 120 字），纵深防御；手机端是主防线，Worker 端是第二道。
- 注意：只清洗了拼路径用的那一段；图片类型判断与失败提示仍用原始 `f.name`，行为不变。

## 2. 备份导入注入还原清单外的设置键
- `app/src/main/java/com/jack/ai/features/BackupFeature.kt`：恢复后的还原清单补上 `baseUrl`、`remoteOn`、`remoteUrl`、`remoteKey`、`appLock`、`contactsOn`、`locationOn`、`usageOn`、`wechatWatch`、`noticeWatch`，一律还原成手机上现有的值。
- 不用另加 `fullAuth`：它是 `fullAuthUntil` 的派生属性（Models.kt），而 `fullAuthUntil` 本来就在清单里。
- 取舍说明：这样改后，恢复自己备份时这几项也会回到手机当前值而不是备份值——与清单里已有的密钥项是同一取舍（安全边界优先于“备份带走设置”）。

## 3. 订阅 YAML 非安全反序列化
- `app/src/main/java/com/jack/ai/data/ProxyNodes.kt` 的 `parseClash`：`Yaml()` 默认构造器换成 `Yaml(SafeConstructor(LoaderOptions()))`（snakeyaml 2.3，API 自 1.26 起就有）。
- SafeConstructor 仍产出标准 Map/List/标量，Clash 的 proxies 结构不受影响；带自定义 tag 的订阅会解析失败，走原有的 runCatching 回落（返回空列表），属于 fail-closed。
- 仓库里只有这一处 `Yaml(` 调用，已 grep 确认无其他同类用法。
