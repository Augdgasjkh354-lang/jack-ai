# Kavi 远程站点

在 iPhone、电脑上用安卓手机上的 Kavi。站点只当中转：网页把消息存进数据库，手机上的 Kavi（设置 → 远程连接）隔几秒来取，答完写回来。
手机不用公网地址；站点在国外时，Kavi 自动走「Kavi 专用代理」去连。

## 部署到 ChatGPT 站点（Plus 以上）

1. 在 Kavi 里：设置 → 远程连接 →「生成密钥」，再点「复制给 ChatGPT 的部署说明」。
2. 在 ChatGPT 里粘贴那段话（用「站点」功能）。它会用这个目录里的 `src/worker.js` 部署，需要：
   - D1 数据库，绑定名 `DB`（表在第一次请求时自动建）
   - R2 存储桶，绑定名 `FILES`
   - Secret `KAVI_KEY`：在站点设置里填，值就是 Kavi 里的密钥（别写进代码）
   - 访问权限：互联网上的任何人（网站自己用密钥登录把关）
3. 把站点地址填进 Kavi，点「测试连接」，打开「远程连接」。
4. iPhone 用 Safari 打开站点，输入密钥，分享 →「添加到主屏幕」。

## 安全

- 网页登录后拿到的 cookie 是密钥算出来的签名（HttpOnly、Secure、SameSite=Strict）；换了密钥，所有设备都要重新登录。
- 同一个 IP 十分钟内输错十次就先锁着。
- 上传的 HTML、SVG、脚本一律按纯文本给，不在站点里跑；网页上 Kavi 写的字先转义再排版。
- 要确认的操作（发邮件、删东西）在网页上问；「操作其他 App」要在手机的设置里单独允许远程用。

## 接口

网页（登录后）：`/api/state`、`/api/send`、`/api/upload`、`/api/file`、`/api/confirm`、`/api/stop`、`/api/delete`。
手机上的 Kavi（`Authorization: Bearer <KAVI_KEY>`）：`/api/device/ping`、`poll`、`reply`、`ask`、`cancel`、`upload`、`file`。

本地调试：`npx wrangler dev --local`（`wrangler.toml` 只给本地用，`.dev.vars` 里写 `KAVI_KEY="…"`）。
