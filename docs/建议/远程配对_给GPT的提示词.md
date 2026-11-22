请改仓库 Augdgasjkh354-lang/jack-ai 里的远程站点 `remote-site/src/worker.js`（Cloudflare Worker，部署在 ChatGPT 站点上，D1 绑定 DB、R2 绑定 FILES），加上「Kavi 自动认领站点 + 设备配对」。先读 `AGENTS.md`、`CLAUDE.md`、`remote-site/README.md`，以及手机端 `app/src/main/java/com/jack/ai/data/RemoteClient.kt`（看手机怎么调接口）。

## 目标

1. 部署站点时**不再需要手动填 Secret `KAVI_KEY`**。手机上的 Kavi 自己生成密钥，第一次连上时「认领」站点。
2. 网页**不再用密钥登录**。iPhone、电脑要先和 Kavi 配对才能用：扫 Kavi 显示的二维码，或者输入 6 位配对码，两种都支持。每台设备单独发一把令牌，Kavi 能看到设备列表，也能随时踢掉某一台。

## 具体要求

### 认领（手机端，Bearer 鉴权）
- `POST /api/device/claim`，请求头 `Authorization: Bearer <密钥>`：
  - 站点还没被认领：把密钥的 SHA-256 存进 D1，返回 `{ ok: true, claimed: true }`。
  - 已被同一把密钥认领：返回 `{ ok: true }`（幂等）。
  - 已被别的密钥认领：返回 409。
- 换密钥用 `POST /api/device/rekey { new_key }`，用旧密钥鉴权；换完让所有已配对设备失效。
- 兼容：如果环境里配了 `KAVI_KEY`，就以它为准（老部署照常能用），认领接口返回 409。
- 所有 `/api/device/*` 接口改成和存着的哈希比对（常量时间比较）。还没认领时，除了 `claim` 一律返回 401。
- 现有的 `/api/device/ping|poll|reply|ask|cancel|upload|file` 行为保持不变。ping 返回的 `version` 加 1，再加一个 `features: ["claim","pairing"]`。

### 配对（手机端发起）
- `POST /api/device/pair/start` → `{ code: "6 位数字", token: "32 字节随机、URL 安全 base64", expires: 秒级时间戳 }`。5 分钟有效，只能用一次。同时只留最新一个，新的出来旧的作废。
- 二维码的内容由手机拼：`<站点地址>/pair#t=<token>`。token 放在 `#` 后面，不进服务器日志，由页面里的 JS 读出来再提交。
- `GET /api/device/devices` → `[{ id, name, created, last_seen }]`
- `POST /api/device/devices/revoke { id }` → 踢掉这台设备，它下一次请求就得到 401。

### 配对（网页端）
- `/pair` 页面：
  - 网址带 `#t=` 时自动提交。
  - 不带时显示 6 位数字输入框，再加一个可选的「设备名」输入框，默认按 UA 猜，比如「iPhone Safari」「Mac Chrome」。
- `POST /api/pair { token? , code?, name }`：
  - 校验通过就建一条设备记录：随机 id，设备令牌只存 SHA-256。
  - 设置 cookie：HttpOnly、Secure、SameSite=Strict、Path=/，一年有效。
  - 配对码立即作废。
- 防暴力猜码：同一个 IP 十分钟内错 5 次就锁住；整个站点同一个配对码累计错 10 次就作废这个码。
- 网页的所有 `/api/*`（state、send、upload、file、confirm、stop、delete）改成认设备 cookie。没配对或已被踢掉时返回 401，前端跳到 `/pair`。每次请求顺手更新 `last_seen`，一分钟最多写一次库。
- 去掉原来的「输入密钥登录」页面和接口。

### 其他
- 表照旧在第一次请求时自动建，加 `owner`（存密钥哈希）、`devices`、`pairing` 三张表。老数据不动。
- 界面文字用中文，跟现有页面风格一致。在 iPhone 尺寸下要好用。
- 更新 `remote-site/README.md`：部署步骤不再有填 KAVI_KEY 这一步，写上新的接口。
- **手机端（Kotlin）不用改**，Claude 会接。

## 交付
- 推到分支 `codex/remote-pairing`，**不许推 main**。
- 用 `npx wrangler dev --local` 加 curl 跑一遍：认领 → 重复认领 → 别的密钥认领被拒 → 发起配对 → 用 token 配对 → 用码配对 → 输错被锁 → 踢掉设备后返回 401 → 老的 device 接口照常能用。贴出结果。
- 做完用中文告诉 Jack 改了什么、分支名。
