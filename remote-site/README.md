# Kavi 远程站点

在 iPhone、电脑上用安卓手机里的 Kavi。站点只负责中转：网页把消息存进 D1，手机上的 Kavi 隔几秒来取，答完再写回来；文件放在 R2。
手机不用公网地址；站点在国外时，Kavi 自动走「Kavi 专用代理」去连。

## 部署到 ChatGPT 站点（Plus 以上）

1. 在 ChatGPT 里用「站点」功能部署本目录的 `src/worker.js`，配置：
   - D1 数据库，绑定名 `DB`
   - R2 存储桶，绑定名 `FILES`
   - 网站访问权限设为「互联网上的任何人」。访问控制由设备配对负责。
2. 在 Kavi 里打开「设置 → 远程连接」，生成密钥，把站点地址填入后点「测试连接」。Kavi 第一次连上时会自动认领站点，不用在站点设置里添加 Secret。
3. 测试连接成功后，在 Kavi 里打开「远程连接」。iPhone 或电脑访问站点的 `/pair` 页面，再扫描 Kavi 显示的二维码，或输入 Kavi 显示的 6 位配对码。配对后浏览器会保存独立设备令牌一年，可在 Kavi 端查看设备并随时撤销。

数据表会在站点第一次收到请求时自动创建。已有对话、消息和文件表不会重建或清空。

如果旧站点仍设置了 Secret `KAVI_KEY`，它会继续优先作为手机密钥，认领和换密钥接口会返回 409。删除这个 Secret 后，站点使用 D1 中保存的密钥哈希。

## 安全

- Kavi 认领站点时只把密钥的 SHA-256 写入 D1。认领后，手机 Bearer 密钥每次都与存储的哈希做常量时间比较。
- 配对令牌只存 SHA-256；浏览器 cookie 为 HttpOnly、Secure、SameSite=Strict，路径为 `/`，有效一年。每台设备有独立令牌；撤销后下一次请求立即返回 401。
- 配对码和二维码令牌 5 分钟有效、只能使用一次；新配对请求会作废旧码。同一 IP 十分钟内输错 5 次会锁定，同一个配对码累计输错 10 次会作废。
- 上传的 HTML、SVG、脚本一律按纯文本返回；网页上 Kavi 写的字先转义再排版。要确认的操作（发邮件、删东西）在网页上问；「操作其他 App」要在手机设置里单独允许远程使用。

## 接口

手机端使用 `Authorization: Bearer <密钥>`：

- `POST /api/device/claim`：首次认领；重复使用同一密钥会幂等成功。
- `POST /api/device/rekey`，JSON `{ "new_key": "…" }`：换密钥并撤销所有已配对设备。
- `POST /api/device/pair/start`：生成配对码、二维码令牌和过期时间。
- `GET /api/device/devices`：查看设备；`POST /api/device/devices/revoke`，JSON `{ "id": "…" }`：撤销设备。
- `GET /api/device/ping|poll|file`、`POST /api/device/reply|ask|cancel|upload`：手机中转接口。ping 返回版本号和功能列表。

网页配对时不需要 cookie：`POST /api/pair` 接受 JSON `{ "token": "…", "name": "…" }` 或 `{ "code": "123456", "name": "…" }`，成功后设置设备 cookie。网页的 `/api/state`、`send`、`upload`、`file`、`confirm`、`stop`、`delete` 均要求有效设备 cookie；未配对或已撤销时返回 401，页面会跳到 `/pair`。

本地调试：`npx wrangler dev --local`。无需设置 `.dev.vars`；要检查旧部署兼容性时，可在本地单独设置 `KAVI_KEY`。
