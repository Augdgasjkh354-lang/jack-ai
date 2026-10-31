// Kavi 远程：iPhone、电脑等别的设备通过这个站点用安卓手机上的 Kavi。
// 站点只当中转：网页把消息存进数据库（D1），手机上的 Kavi 隔几秒来取，处理完把回答写回来。文件放在 R2。
// 需要的绑定：D1 数据库 DB、R2 存储桶 FILES、密钥 KAVI_KEY（在 Kavi 设置 → 远程连接 里生成，填进站点的 Secrets）。
// 表在第一次请求时自动建好。

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS convs (id TEXT PRIMARY KEY, title TEXT, updated INTEGER)`,
  `CREATE TABLE IF NOT EXISTS msgs (id INTEGER PRIMARY KEY AUTOINCREMENT, conv TEXT, role TEXT, text TEXT, files TEXT, steps TEXT,
     state TEXT, reply_to INTEGER, stop INTEGER DEFAULT 0, stop_sent INTEGER DEFAULT 0, created INTEGER, updated INTEGER)`,
  `CREATE INDEX IF NOT EXISTS msgs_conv ON msgs(conv, updated)`,
  `CREATE INDEX IF NOT EXISTS msgs_state ON msgs(state)`,
  `CREATE TABLE IF NOT EXISTS confirms (id TEXT PRIMARY KEY, conv TEXT, msg INTEGER, title TEXT, body TEXT, action TEXT,
     answer INTEGER, sent INTEGER DEFAULT 0, created INTEGER)`,
  `CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT)`,
  `CREATE TABLE IF NOT EXISTS fails (ip TEXT, at INTEGER)`,
];
let ready = false;
async function init(env) {
  if (ready) return;
  await env.DB.batch(SCHEMA.map((s) => env.DB.prepare(s)));
  ready = true;
}

/** 站点代码的版本：Kavi 测试连接时看得到，接口改了要升。 */
const VERSION = 1;
const MAX_UPLOAD = 25 * 1024 * 1024;
const ONLINE_MS = 30_000;

const json = (data, status = 200, headers = {}) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers } });
const now = () => Date.now();

async function hmac(key, text) {
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode(key), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(text));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function same(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}
function cookie(req, name) {
  const m = (req.headers.get("cookie") || "").match(new RegExp("(?:^|;\\s*)" + name + "=([^;]+)"));
  return m ? m[1] : "";
}
/** 手机上的 Kavi：Authorization: Bearer <KAVI_KEY>。 */
function isDevice(req, env) {
  const h = req.headers.get("authorization") || "";
  return !!env.KAVI_KEY && h.startsWith("Bearer ") && same(h.slice(7), env.KAVI_KEY);
}
/** 网页：登录后的 cookie（密钥算出来的签名，换了密钥就全部失效）。 */
async function isWeb(req, env) {
  const c = cookie(req, "kavi_session");
  return !!c && !!env.KAVI_KEY && same(c, await hmac(env.KAVI_KEY, "web-v1"));
}

async function login(req, env) {
  const ip = req.headers.get("cf-connecting-ip") || "?";
  const recent = await env.DB.prepare("SELECT COUNT(*) AS n FROM fails WHERE ip = ? AND at > ?").bind(ip, now() - 10 * 60_000).first();
  if ((recent?.n || 0) >= 10) return json({ error: "试错太多次，十分钟后再试" }, 429);
  const body = await req.json().catch(() => ({}));
  if (!env.KAVI_KEY) return json({ error: "站点还没设置密钥 KAVI_KEY" }, 500);
  if (!same(String(body.key || "").trim(), env.KAVI_KEY)) {
    await env.DB.prepare("INSERT INTO fails (ip, at) VALUES (?, ?)").bind(ip, now()).run();
    await env.DB.prepare("DELETE FROM fails WHERE at < ?").bind(now() - 86_400_000).run();
    return json({ error: "密钥不对" }, 401);
  }
  const v = await hmac(env.KAVI_KEY, "web-v1");
  return json({ ok: true }, 200, { "set-cookie": `kavi_session=${v}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=31536000` });
}

const parseMsg = (r) => r && ({ ...r, files: JSON.parse(r.files || "[]"), steps: JSON.parse(r.steps || "[]") });

async function lastSeen(env) {
  const r = await env.DB.prepare("SELECT v FROM kv WHERE k = 'last_seen'").first();
  return r ? Number(r.v) : 0;
}

// ---------- 网页用的接口 ----------

async function state(url, env) {
  const conv = url.searchParams.get("conv") || "";
  const since = Number(url.searchParams.get("since") || 0);
  const seen = await lastSeen(env);
  const convs = (await env.DB.prepare("SELECT id, title, updated FROM convs ORDER BY updated DESC LIMIT 100").all()).results;
  let msgs = [], confirms = [];
  if (conv) {
    msgs = (await env.DB.prepare("SELECT * FROM msgs WHERE conv = ? AND updated > ? ORDER BY id").bind(conv, since).all()).results.map(parseMsg);
    confirms = (await env.DB.prepare("SELECT id, msg, title, body, action FROM confirms WHERE conv = ? AND answer IS NULL ORDER BY created").bind(conv).all()).results;
  }
  return json({ now: now(), device: { online: now() - seen < ONLINE_MS, lastSeen: seen }, convs, msgs, confirms });
}

async function send(req, env) {
  const b = await req.json().catch(() => ({}));
  const text = String(b.text || "").slice(0, 100_000);
  const files = Array.isArray(b.files) ? b.files.slice(0, 20) : [];
  if (!text.trim() && !files.length) return json({ error: "空消息" }, 400);
  let conv = String(b.conv || "");
  const t = now();
  if (!conv) {
    conv = crypto.randomUUID();
    const title = (text.trim() || files[0]?.name || "新对话").replace(/\s+/g, " ").slice(0, 30);
    await env.DB.prepare("INSERT INTO convs (id, title, updated) VALUES (?, ?, ?)").bind(conv, title, t).run();
  } else {
    await env.DB.prepare("UPDATE convs SET updated = ? WHERE id = ?").bind(t, conv).run();
  }
  const r = await env.DB.prepare("INSERT INTO msgs (conv, role, text, files, steps, state, created, updated) VALUES (?, 'user', ?, ?, '[]', 'pending', ?, ?)")
    .bind(conv, text, JSON.stringify(files), t, t).run();
  return json({ conv, id: r.meta.last_row_id });
}

async function upload(req, env, url, prefix) {
  const name = (url.searchParams.get("name") || "file").replace(/[\\/]/g, "_").slice(0, 120);
  const type = url.searchParams.get("type") || req.headers.get("content-type") || "application/octet-stream";
  const len = Number(req.headers.get("content-length") || 0);
  if (len > MAX_UPLOAD) return json({ error: "文件太大（最多 25MB）" }, 413);
  const key = `${prefix}/${crypto.randomUUID()}/${name}`;
  const body = await req.arrayBuffer();
  if (body.byteLength > MAX_UPLOAD) return json({ error: "文件太大（最多 25MB）" }, 413);
  await env.FILES.put(key, body, { httpMetadata: { contentType: type } });
  return json({ key, name, size: body.byteLength, type });
}

async function file(url, env) {
  const key = url.searchParams.get("key") || "";
  const obj = key ? await env.FILES.get(key) : null;
  if (!obj) return new Response("找不到文件", { status: 404 });
  const name = key.split("/").pop();
  const h = new Headers();
  obj.writeHttpMetadata(h);
  h.set("cache-control", "private, max-age=86400");
  h.set("content-disposition", `inline; filename*=UTF-8''${encodeURIComponent(name)}`);
  h.set("x-content-type-options", "nosniff");
  // 用户和 Kavi 传上来的 HTML、SVG 不在站点里直接跑
  const t = (h.get("content-type") || "").toLowerCase();
  if (t.includes("html") || t.includes("svg") || t.includes("xml") || t.includes("javascript")) h.set("content-type", "text/plain; charset=utf-8");
  return new Response(obj.body, { headers: h });
}

async function answer(req, env) {
  const b = await req.json().catch(() => ({}));
  await env.DB.prepare("UPDATE confirms SET answer = ? WHERE id = ? AND answer IS NULL").bind(b.ok ? 1 : 0, String(b.id || "")).run();
  return json({ ok: true });
}

async function stop(req, env) {
  const b = await req.json().catch(() => ({}));
  await env.DB.prepare("UPDATE msgs SET stop = 1, updated = ? WHERE id = ? AND role = 'user'").bind(now(), Number(b.id || 0)).run();
  return json({ ok: true });
}

async function removeConv(req, env) {
  const b = await req.json().catch(() => ({}));
  const conv = String(b.conv || "");
  const rows = (await env.DB.prepare("SELECT files FROM msgs WHERE conv = ?").bind(conv).all()).results;
  const keys = rows.flatMap((r) => JSON.parse(r.files || "[]").map((f) => f.key)).filter(Boolean);
  for (let i = 0; i < keys.length; i += 500) await env.FILES.delete(keys.slice(i, i + 500));
  await env.DB.batch([
    env.DB.prepare("DELETE FROM msgs WHERE conv = ?").bind(conv),
    env.DB.prepare("DELETE FROM confirms WHERE conv = ?").bind(conv),
    env.DB.prepare("DELETE FROM convs WHERE id = ?").bind(conv),
  ]);
  return json({ ok: true });
}

// ---------- 手机上的 Kavi 用的接口 ----------

/** 来取活：新消息（取走就标成 taken）、用户回了的确认、要停的回答。顺便记一下 Kavi 在线。 */
async function poll(env) {
  const t = now();
  await env.DB.prepare("INSERT INTO kv (k, v) VALUES ('last_seen', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v").bind(String(t)).run();
  const msgs = (await env.DB.prepare("SELECT id, conv, text, files FROM msgs WHERE role = 'user' AND state = 'pending' ORDER BY id LIMIT 5").all()).results;
  if (msgs.length) await env.DB.prepare(`UPDATE msgs SET state = 'taken', updated = ? WHERE id IN (${msgs.map(() => "?").join(",")})`).bind(t, ...msgs.map((m) => m.id)).run();
  const answers = (await env.DB.prepare("SELECT id, answer FROM confirms WHERE answer IS NOT NULL AND sent = 0").all()).results;
  if (answers.length) await env.DB.prepare(`UPDATE confirms SET sent = 1 WHERE id IN (${answers.map(() => "?").join(",")})`).bind(...answers.map((a) => a.id)).run();
  const stops = (await env.DB.prepare("SELECT id FROM msgs WHERE role = 'user' AND stop = 1 AND stop_sent = 0").all()).results;
  if (stops.length) await env.DB.prepare(`UPDATE msgs SET stop_sent = 1 WHERE id IN (${stops.map(() => "?").join(",")})`).bind(...stops.map((s) => s.id)).run();
  return json({
    now: t,
    messages: msgs.map((m) => ({ id: m.id, conv: m.conv, text: m.text, files: JSON.parse(m.files || "[]") })),
    answers: answers.map((a) => ({ id: a.id, ok: a.answer === 1 })),
    stops: stops.map((s) => s.id),
  });
}

/** 写回答（同一条用户消息的回答一直更新同一行）。state：running 还在写、done 写完、error 出错。 */
async function reply(req, env) {
  const b = await req.json().catch(() => ({}));
  const to = Number(b.reply_to || 0);
  const user = await env.DB.prepare("SELECT conv FROM msgs WHERE id = ? AND role = 'user'").bind(to).first();
  if (!user) return json({ error: "没有这条消息" }, 404);
  const t = now();
  const text = String(b.text || "").slice(0, 200_000);
  const files = JSON.stringify(Array.isArray(b.files) ? b.files : []);
  const steps = JSON.stringify(Array.isArray(b.steps) ? b.steps.slice(-40) : []);
  const st = ["running", "done", "error"].includes(b.state) ? b.state : "running";
  const old = await env.DB.prepare("SELECT id FROM msgs WHERE reply_to = ? AND role = 'assistant'").bind(to).first();
  if (old) await env.DB.prepare("UPDATE msgs SET text = ?, files = ?, steps = ?, state = ?, updated = ? WHERE id = ?").bind(text, files, steps, st, t, old.id).run();
  else await env.DB.prepare("INSERT INTO msgs (conv, role, text, files, steps, state, reply_to, created, updated) VALUES (?, 'assistant', ?, ?, ?, ?, ?, ?, ?)")
    .bind(user.conv, text, files, steps, st, to, t, t).run();
  if (st !== "running") await env.DB.prepare("UPDATE msgs SET state = 'done', updated = ? WHERE id = ?").bind(t, to).run();
  await env.DB.prepare("UPDATE convs SET updated = ? WHERE id = ?").bind(t, user.conv).run();
  return json({ ok: true });
}

/** Kavi 要用户确认一件事：网页上弹出来，用户点了以后 Kavi 下次来取时拿到。 */
async function ask(req, env) {
  const b = await req.json().catch(() => ({}));
  const to = Number(b.msg || 0);
  const user = await env.DB.prepare("SELECT conv FROM msgs WHERE id = ?").bind(to).first();
  if (!user) return json({ error: "没有这条消息" }, 404);
  const id = crypto.randomUUID();
  await env.DB.prepare("INSERT INTO confirms (id, conv, msg, title, body, action, created) VALUES (?, ?, ?, ?, ?, ?, ?)")
    .bind(id, user.conv, to, String(b.title || "").slice(0, 200), String(b.body || "").slice(0, 4000), String(b.action || "确定").slice(0, 20), now()).run();
  await env.DB.prepare("UPDATE convs SET updated = ? WHERE id = ?").bind(now(), user.conv).run();
  return json({ id });
}

/** 确认超时（Kavi 不等了）：从网页上撤掉。 */
async function cancelAsk(req, env) {
  const b = await req.json().catch(() => ({}));
  await env.DB.prepare("UPDATE confirms SET answer = 0, sent = 1 WHERE id = ? AND answer IS NULL").bind(String(b.id || "")).run();
  return json({ ok: true });
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const p = url.pathname;
    try {
      if (p === "/" || p === "/index.html") return new Response(PAGE, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", ...SECURITY } });
      if (p === "/manifest.json") return json(MANIFEST);
      if (!p.startsWith("/api/")) return new Response("Not found", { status: 404 });
      await init(env);
      if (p === "/api/login" && req.method === "POST") return login(req, env);
      if (p.startsWith("/api/device/")) {
        if (!isDevice(req, env)) return json({ error: "密钥不对" }, 401);
        if (p === "/api/device/poll") return poll(env);
        if (p === "/api/device/ping") return json({ ok: true, version: VERSION });
        if (p === "/api/device/reply" && req.method === "POST") return reply(req, env);
        if (p === "/api/device/ask" && req.method === "POST") return ask(req, env);
        if (p === "/api/device/cancel" && req.method === "POST") return cancelAsk(req, env);
        if (p === "/api/device/upload" && req.method === "POST") return upload(req, env, url, "k");
        if (p === "/api/device/file") return file(url, env);
        return json({ error: "没有这个接口" }, 404);
      }
      if (!(await isWeb(req, env))) return json({ error: "请先登录" }, 401);
      if (p === "/api/state") return state(url, env);
      if (p === "/api/send" && req.method === "POST") return send(req, env);
      if (p === "/api/upload" && req.method === "POST") return upload(req, env, url, "u");
      if (p === "/api/file") return file(url, env);
      if (p === "/api/confirm" && req.method === "POST") return answer(req, env);
      if (p === "/api/stop" && req.method === "POST") return stop(req, env);
      if (p === "/api/delete" && req.method === "POST") return removeConv(req, env);
      if (p === "/api/logout" && req.method === "POST") return json({ ok: true }, 200, { "set-cookie": "kavi_session=; Path=/; Max-Age=0" });
      return json({ error: "没有这个接口" }, 404);
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 500);
    }
  },
};

const SECURITY = {
  "content-security-policy": "default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'",
  "x-frame-options": "DENY",
  "referrer-policy": "no-referrer",
};

const MANIFEST = { name: "Kavi", short_name: "Kavi", start_url: "/", display: "standalone", background_color: "#ffffff", theme_color: "#ffffff" };

const PAGE = `<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="Kavi">
<meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#1d212b" media="(prefers-color-scheme: dark)">
<link rel="manifest" href="/manifest.json">
<title>Kavi</title>
<style>
:root{--bg:#fff;--fg:#1c1c1e;--sub:#6b7280;--line:#e5e7eb;--bubble:#f3f4f6;--accent:#1c1c1e;--card:#fff;--err:#c0392b}
@media (prefers-color-scheme: dark){:root{--bg:#1d212b;--fg:#f3f4f6;--sub:#9ca3af;--line:#343a46;--bubble:#2a303c;--accent:#f3f4f6;--card:#252a35}}
*{box-sizing:border-box}html,body{margin:0;height:100%;background:var(--bg);color:var(--fg);font:16px/1.55 -apple-system,BlinkMacSystemFont,"PingFang SC","Helvetica Neue",sans-serif}
button{font:inherit;color:inherit;background:none;border:0;cursor:pointer}
#app{display:flex;flex-direction:column;height:100%;max-width:820px;margin:0 auto}
header{display:flex;align-items:center;gap:10px;padding:calc(env(safe-area-inset-top) + 10px) 14px 10px;border-bottom:1px solid var(--line)}
header h1{font-size:17px;margin:0;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dot{width:8px;height:8px;border-radius:50%;background:#9ca3af;display:inline-block;margin-right:6px}.dot.on{background:#22c55e}
#status{font-size:12px;color:var(--sub);white-space:nowrap}
#list{flex:1;overflow-y:auto;padding:14px;-webkit-overflow-scrolling:touch}
.msg{margin:10px 0;display:flex;flex-direction:column}
.user{align-items:flex-end}.user .b{background:var(--bubble);border-radius:18px;padding:9px 14px;max-width:85%;white-space:pre-wrap;word-break:break-word}
.bot .b{max-width:100%;word-break:break-word}
.bot .b p{margin:.5em 0}.bot pre{background:var(--bubble);padding:10px;border-radius:10px;overflow-x:auto;font-size:13px}
.bot code{font-family:ui-monospace,Menlo,monospace;font-size:.9em}
.steps{font-size:13px;color:var(--sub);border-left:2px solid var(--line);padding-left:10px;margin:4px 0 6px}
.files{display:flex;flex-wrap:wrap;gap:8px;margin-top:6px}
.files a{font-size:13px;border:1px solid var(--line);border-radius:10px;padding:6px 10px;color:var(--fg);text-decoration:none}
.files img{max-width:220px;max-height:220px;border-radius:12px;display:block}
.err{color:var(--err);font-size:14px}.wait{color:var(--sub);font-size:14px}
.confirm{border:1px solid var(--line);border-radius:16px;padding:14px;margin:10px 0;background:var(--card)}
.confirm h3{margin:0 0 6px;font-size:16px}.confirm p{margin:0 0 10px;white-space:pre-wrap;font-size:14px}
.row{display:flex;gap:10px;justify-content:flex-end}
.btn{border:1px solid var(--line);border-radius:20px;padding:7px 16px}.btn.main{background:var(--accent);color:var(--bg);border-color:var(--accent)}
footer{border-top:1px solid var(--line);padding:8px 10px calc(env(safe-area-inset-bottom) + 8px)}
#pending{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:6px}#pending span{font-size:12px;background:var(--bubble);border-radius:8px;padding:3px 8px}
.in{display:flex;align-items:flex-end;gap:8px}
textarea{flex:1;resize:none;border:1px solid var(--line);border-radius:20px;padding:9px 14px;font:inherit;background:var(--bg);color:var(--fg);max-height:160px}
.icon{width:38px;height:38px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:20px;flex:none}
#send{background:var(--accent);color:var(--bg)}
#drawer{position:fixed;inset:0;background:rgba(0,0,0,.35);display:none;z-index:5}#drawer.open{display:block}
#drawer nav{background:var(--bg);width:80%;max-width:320px;height:100%;padding:calc(env(safe-area-inset-top) + 14px) 10px 14px;overflow-y:auto}
#drawer nav button{display:block;width:100%;text-align:left;padding:10px;border-radius:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#drawer nav button.cur{background:var(--bubble)}
#login{display:flex;flex-direction:column;gap:12px;padding:40px 24px;max-width:420px;margin:0 auto}
#login input{border:1px solid var(--line);border-radius:12px;padding:12px;font:inherit;background:var(--bg);color:var(--fg)}
.hint{font-size:13px;color:var(--sub)}
</style></head>
<body>
<div id="login" hidden>
  <h2>Kavi</h2>
  <p class="hint">填 Kavi 设置 →「远程连接」里的密钥。这台设备会记住一年。</p>
  <input id="key" type="password" autocomplete="current-password" placeholder="密钥">
  <button class="btn main" id="go">进入</button>
  <div class="err" id="loginErr"></div>
</div>
<div id="app" hidden>
  <header>
    <button class="icon" id="menu" aria-label="对话列表">☰</button>
    <h1 id="title">Kavi</h1>
    <span id="status"><span class="dot"></span>…</span>
    <button class="icon" id="new" aria-label="新对话">＋</button>
  </header>
  <div id="list"></div>
  <footer>
    <div id="pending"></div>
    <div class="in">
      <button class="icon" id="attach" aria-label="附件">📎</button>
      <input type="file" id="fileIn" multiple hidden>
      <textarea id="text" rows="1" placeholder="问 Kavi"></textarea>
      <button class="icon" id="send" aria-label="发送">↑</button>
    </div>
  </footer>
</div>
<div id="drawer"><nav id="convs"></nav></div>
<script>
const $ = (id) => document.getElementById(id);
let conv = localStorage.getItem("conv") || "", since = 0, msgs = new Map(), confirms = [], convs = [], files = [], timer = null, busy = false;
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
function md(src) {
  // 先转义再排版：Kavi 写的字不会变成网页上能跑的东西
  const blocks = String(src).split(/\`\`\`/);
  return blocks.map((b, i) => {
    if (i % 2) return "<pre><code>" + esc(b.replace(/^[\\w-]*\\n/, "")) + "</code></pre>";
    return esc(b).split(/\\n{2,}/).map((p) => "<p>" + p
      .replace(/\`([^\`]+)\`/g, "<code>$1</code>")
      .replace(/\\*\\*([^*]+)\\*\\*/g, "<b>$1</b>")
      .replace(/^#{1,6} (.+)$/gm, "<b>$1</b>")
      .replace(/(https?:\\/\\/[^\\s<]+)/g, '<a href="$1" target="_blank" rel="noopener noreferrer">$1</a>')
      .replace(/\\n/g, "<br>") + "</p>").join("");
  }).join("");
}
async function api(path, opt = {}) {
  const r = await fetch(path, { credentials: "same-origin", ...opt });
  if (r.status === 401 && !path.includes("login")) { showLogin(); throw new Error("401"); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || r.status);
  return j;
}
function showLogin() { $("app").hidden = true; $("login").hidden = false; clearTimeout(timer); }
$("go").onclick = async () => {
  $("loginErr").textContent = "";
  try { await api("/api/login", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ key: $("key").value }) }); start(); }
  catch (e) { $("loginErr").textContent = e.message; }
};
function fileLinks(fs) {
  if (!fs || !fs.length) return "";
  return '<div class="files">' + fs.map((f) => {
    const u = "/api/file?key=" + encodeURIComponent(f.key);
    return /^image\\//.test(f.type || "") || /\\.(png|jpe?g|gif|webp)$/i.test(f.name) ? '<a href="' + u + '" target="_blank"><img src="' + u + '" alt="' + esc(f.name) + '"></a>'
      : '<a href="' + u + '" target="_blank">📄 ' + esc(f.name) + "</a>";
  }).join("") + "</div>";
}
function render() {
  const list = $("list");
  const atBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 80;
  const sorted = [...msgs.values()].sort((a, b) => a.id - b.id);
  const replies = new Map(sorted.filter((m) => m.role === "assistant").map((m) => [m.reply_to, m]));
  let html = "";
  busy = false;
  for (const m of sorted) {
    if (m.role !== "user") continue;
    html += '<div class="msg user"><div class="b">' + esc(m.text) + "</div>" + fileLinks(m.files) + "</div>";
    const r = replies.get(m.id);
    if (!r) {
      busy = true;
      html += '<div class="msg bot"><div class="wait">' + (m.state === "pending" ? "等手机上的 Kavi 来取…" : "Kavi 在想…") +
        ' <button class="btn" onclick="stopIt(' + m.id + ')">停止</button></div></div>';
      continue;
    }
    if (r.state === "running") busy = true;
    html += '<div class="msg bot">' + (r.steps && r.steps.length ? '<div class="steps">' + r.steps.map(esc).join("<br>") + "</div>" : "") +
      '<div class="b">' + md(r.text || "") + "</div>" + fileLinks(r.files) +
      (r.state === "error" ? '<div class="err">出错了</div>' : "") +
      (r.state === "running" ? '<div class="wait">…<button class="btn" onclick="stopIt(' + m.id + ')">停止</button></div>' : "") + "</div>";
  }
  for (const c of confirms) {
    html += '<div class="confirm"><h3>' + esc(c.title) + "</h3><p>" + esc(c.body) + '</p><div class="row"><button class="btn" onclick="answer(\\'' + c.id + '\\',false)">不要</button><button class="btn main" onclick="answer(\\'' + c.id + '\\',true)">' + esc(c.action) + "</button></div></div>";
  }
  if (!sorted.length) html = '<p class="hint" style="text-align:center;margin-top:30vh">在 iPhone、电脑上用手机里的 Kavi：记忆、文件、邮件、浏览器都在。</p>';
  list.innerHTML = html;
  if (atBottom) list.scrollTop = list.scrollHeight;
  const c = convs.find((x) => x.id === conv);
  $("title").textContent = c ? c.title : "Kavi";
  $("convs").innerHTML = '<button onclick="pick(\\'\\')">＋ 新对话</button>' + convs.map((x) =>
    '<button class="' + (x.id === conv ? "cur" : "") + '" onclick="pick(\\'' + x.id + '\\')">' + esc(x.title) + "</button>").join("");
}
async function refresh() {
  clearTimeout(timer);
  try {
    const s = await api("/api/state?conv=" + encodeURIComponent(conv) + "&since=" + since);
    for (const m of s.msgs) { msgs.set(m.id, m); since = Math.max(since, m.updated); }
    confirms = s.confirms; convs = s.convs;
    const d = s.device;
    $("status").innerHTML = '<span class="dot ' + (d.online ? "on" : "") + '"></span>' + (d.online ? "手机在线" : d.lastSeen ? "手机离线 " + ago(s.now - d.lastSeen) : "手机还没连上");
    render();
  } catch (e) { if (e.message === "401") return; }
  timer = setTimeout(refresh, busy || confirms.length ? 1500 : 4000);
}
const ago = (ms) => ms < 3600e3 ? Math.round(ms / 60e3) + " 分钟前" : ms < 86400e3 ? Math.round(ms / 3600e3) + " 小时前" : Math.round(ms / 86400e3) + " 天前";
window.pick = (id) => { conv = id; localStorage.setItem("conv", id); since = 0; msgs.clear(); confirms = []; $("drawer").classList.remove("open"); render(); refresh(); };
window.answer = async (id, ok) => { await api("/api/confirm", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ id, ok }) }); confirms = confirms.filter((c) => c.id !== id); render(); refresh(); };
window.stopIt = async (id) => { await api("/api/stop", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ id }) }); };
$("menu").onclick = () => $("drawer").classList.add("open");
$("drawer").onclick = (e) => { if (e.target.id === "drawer") $("drawer").classList.remove("open"); };
$("new").onclick = () => pick("");
$("attach").onclick = () => $("fileIn").click();
$("fileIn").onchange = async (e) => {
  for (const f of e.target.files) {
    const tag = document.createElement("span"); tag.textContent = "上传中 " + f.name; $("pending").appendChild(tag);
    try {
      const r = await api("/api/upload?name=" + encodeURIComponent(f.name) + "&type=" + encodeURIComponent(f.type || ""), { method: "POST", body: f });
      files.push(r); tag.textContent = "📄 " + f.name;
    } catch (err) { tag.textContent = "没传上 " + f.name + "：" + err.message; }
  }
  e.target.value = "";
};
const ta = $("text");
ta.oninput = () => { ta.style.height = "auto"; ta.style.height = Math.min(ta.scrollHeight, 160) + "px"; };
$("send").onclick = async () => {
  const text = ta.value.trim();
  if (!text && !files.length) return;
  $("send").disabled = true;
  try {
    const r = await api("/api/send", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ conv, text, files }) });
    if (!conv) { conv = r.conv; localStorage.setItem("conv", conv); }
    ta.value = ""; ta.oninput(); files = []; $("pending").innerHTML = "";
    refresh();
  } catch (e) { alert("没发出去：" + e.message); }
  $("send").disabled = false;
};
function start() { $("login").hidden = true; $("app").hidden = false; since = 0; msgs.clear(); refresh(); }
api("/api/state").then(start).catch(() => {});
document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });
</script>
</body></html>`;
