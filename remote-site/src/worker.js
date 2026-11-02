// Kavi 远程：iPhone、电脑等别的设备通过这个站点用安卓手机上的 Kavi。
// 站点只当中转：网页把消息存进数据库（D1），手机上的 Kavi 隔几秒来取，处理完把回答写回来。文件放在 R2。
// 需要的绑定：D1 数据库 DB、R2 存储桶 FILES。Kavi 首次连接时会认领站点。
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
  `CREATE TABLE IF NOT EXISTS owner (id INTEGER PRIMARY KEY CHECK (id = 1), key_hash TEXT NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created INTEGER NOT NULL, last_seen INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS pairing (id INTEGER PRIMARY KEY CHECK (id = 1), code TEXT NOT NULL, token_hash TEXT NOT NULL, expires INTEGER NOT NULL, failures INTEGER NOT NULL DEFAULT 0)`,
];
let ready = false;
async function init(env) {
  if (ready) return;
  await env.DB.batch(SCHEMA.map((s) => env.DB.prepare(s)));
  if (env.KAVI_KEY) {
    const keyHash = await sha256(env.KAVI_KEY);
    const owner = await env.DB.prepare("SELECT key_hash FROM owner WHERE id = 1").first();
    if (!owner) {
      await env.DB.prepare("INSERT OR IGNORE INTO owner (id, key_hash) VALUES (1, ?)").bind(keyHash).run();
    } else if (!same(owner.key_hash, keyHash)) {
      await env.DB.batch([
        env.DB.prepare("UPDATE owner SET key_hash = ? WHERE id = 1").bind(keyHash),
        env.DB.prepare("DELETE FROM devices"),
        env.DB.prepare("DELETE FROM pairing"),
      ]);
    }
  }
  ready = true;
}

/** 站点代码的版本：Kavi 测试连接时看得到，接口改了要升。 */
const VERSION = 2;
const MAX_UPLOAD = 25 * 1024 * 1024;
const ONLINE_MS = 30_000;

const json = (data, status = 200, headers = {}) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers } });
const now = () => Date.now();

async function sha256(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function same(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  let d = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) d |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return d === 0;
}
function cookie(req, name) {
  const m = (req.headers.get("cookie") || "").match(new RegExp("(?:^|;\\s*)" + name + "=([^;]+)"));
  return m ? m[1] : "";
}
/** 手机上的 Kavi 用认领密钥；数据库只保存它的 SHA-256。 */
async function ownerHash(env) {
  const row = await env.DB.prepare("SELECT key_hash FROM owner WHERE id = 1").first();
  return row?.key_hash || "";
}
async function isDevice(req, env) {
  const auth = req.headers.get("authorization") || "";
  if (!auth.startsWith("Bearer ")) return false;
  const stored = await ownerHash(env);
  return !!stored && same(await sha256(auth.slice(7)), stored);
}
/** 网页 cookie 是设备令牌；只在数据库保存它的 SHA-256。 */
async function isWeb(req, env) {
  const token = cookie(req, "kavi_device");
  if (!token) return null;
  const tokenHash = await sha256(token);
  const device = await env.DB.prepare("SELECT id, token_hash, last_seen FROM devices WHERE token_hash = ?").bind(tokenHash).first();
  if (!device || !same(tokenHash, device.token_hash)) return null;
  const t = now();
  if (Number(device.last_seen) <= t - 60_000) {
    await env.DB.prepare("UPDATE devices SET last_seen = ? WHERE id = ? AND last_seen <= ?").bind(t, device.id, t - 60_000).run();
  }
  return device;
}

async function claim(req, env) {
  if (env.KAVI_KEY) return json({ error: "站点由 KAVI_KEY 管理，不能在线认领" }, 409);
  const auth = req.headers.get("authorization") || "";
  if (!auth.startsWith("Bearer ") || !auth.slice(7)) return json({ error: "请提供认领密钥" }, 401);
  const keyHash = await sha256(auth.slice(7));
  const result = await env.DB.prepare("INSERT OR IGNORE INTO owner (id, key_hash) VALUES (1, ?)").bind(keyHash).run();
  const owner = await ownerHash(env);
  if (!same(keyHash, owner)) return json({ error: "站点已被另一把密钥认领" }, 409);
  return result.meta?.changes ? json({ ok: true, claimed: true }) : json({ ok: true });
}

async function rekey(req, env) {
  if (env.KAVI_KEY) return json({ error: "站点由 KAVI_KEY 管理，不能在线换密钥" }, 409);
  const body = await req.json().catch(() => ({}));
  if (typeof body.new_key !== "string" || !body.new_key || body.new_key.length > 256) return json({ error: "新密钥无效" }, 400);
  const keyHash = await sha256(body.new_key);
  await env.DB.batch([
    env.DB.prepare("UPDATE owner SET key_hash = ? WHERE id = 1").bind(keyHash),
    env.DB.prepare("DELETE FROM devices"),
    env.DB.prepare("DELETE FROM pairing"),
  ]);
  return json({ ok: true });
}

function randomToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function guessDeviceName(ua) {
  const s = String(ua || "");
  const os = /iPhone/i.test(s) ? "iPhone" : /iPad/i.test(s) ? "iPad" : /Android/i.test(s) ? "Android" : /Macintosh|Mac OS/i.test(s) ? "Mac" : /Windows/i.test(s) ? "Windows" : "网页设备";
  const browser = /Edg\//i.test(s) ? "Edge" : /CriOS|Chrome/i.test(s) ? "Chrome" : /Firefox|FxiOS/i.test(s) ? "Firefox" : /Safari/i.test(s) ? "Safari" : "浏览器";
  return `${os} ${browser}`;
}
async function startPair(env) {
  const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000).padStart(6, "0");
  const token = randomToken();
  const expires = Math.floor(now() / 1000) + 5 * 60;
  await env.DB.prepare("INSERT INTO pairing (id, code, token_hash, expires, failures) VALUES (1, ?, ?, ?, 0) ON CONFLICT(id) DO UPDATE SET code = excluded.code, token_hash = excluded.token_hash, expires = excluded.expires, failures = 0")
    .bind(code, await sha256(token), expires).run();
  return json({ code, token, expires });
}
async function pair(req, env) {
  const body = await req.json().catch(() => ({}));
  const ip = `pair:${req.headers.get("cf-connecting-ip") || "?"}`;
  const t = now();
  const recent = await env.DB.prepare("SELECT COUNT(*) AS n FROM fails WHERE ip = ? AND at > ?").bind(ip, t - 10 * 60_000).first();
  if ((recent?.n || 0) >= 5) return json({ error: "尝试次数太多，请十分钟后再试" }, 429);

  const active = await env.DB.prepare("SELECT code, token_hash, expires FROM pairing WHERE id = 1 AND expires > ?").bind(Math.floor(t / 1000)).first();
  const tokenHash = typeof body.token === "string" && body.token.length <= 256 ? await sha256(body.token) : "";
  const valid = !!active && ((typeof body.code === "string" && same(body.code, active.code)) || (tokenHash && same(tokenHash, active.token_hash)));
  if (!valid) {
    await env.DB.prepare("INSERT INTO fails (ip, at) VALUES (?, ?)").bind(ip, t).run();
    if (active) {
      const failures = await env.DB.prepare("UPDATE pairing SET failures = failures + 1 WHERE id = 1 AND token_hash = ? AND expires > ? RETURNING failures").bind(active.token_hash, Math.floor(t / 1000)).first();
      if (failures && Number(failures.failures) >= 10) await env.DB.prepare("DELETE FROM pairing WHERE id = 1 AND token_hash = ?").bind(active.token_hash).run();
    }
    await env.DB.prepare("DELETE FROM fails WHERE ip LIKE 'pair:%' AND at < ?").bind(t - 86_400_000).run();
    return json({ error: "配对码无效或已过期" }, 401);
  }

  const consumed = await env.DB.prepare("DELETE FROM pairing WHERE id = 1 AND code = ? AND token_hash = ? AND expires > ? RETURNING id")
    .bind(active.code, active.token_hash, Math.floor(t / 1000)).first();
  if (!consumed) return json({ error: "配对码已使用或已过期" }, 401);
  const deviceToken = randomToken();
  const deviceId = crypto.randomUUID();
  const name = String(body.name || guessDeviceName(req.headers.get("user-agent"))).trim().slice(0, 60) || "网页设备";
  await env.DB.prepare("INSERT INTO devices (id, name, token_hash, created, last_seen) VALUES (?, ?, ?, ?, ?)")
    .bind(deviceId, name, await sha256(deviceToken), t, t).run();
  return json({ ok: true }, 200, { "set-cookie": `kavi_device=${deviceToken}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=31536000` });
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
  // 文件名是网页端传来的：斜杠换掉，免得手机端拼路径时被 ../ 带到别的目录（upload() 同款处理）
  const files = Array.isArray(b.files) ? b.files.slice(0, 20).map(f => ({ ...f, name: String(f?.name || "file").replace(/[\\/]/g, "_").slice(0, 120) })) : [];
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
      await init(env);
      if (p === "/" || p === "/index.html") return new Response(PAGE, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", ...SECURITY } });
      if (p === "/pair") return new Response(PAIR_PAGE, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", ...SECURITY } });
      if (p === "/manifest.json") return json(MANIFEST);
      if (!p.startsWith("/api/")) return new Response("Not found", { status: 404 });
      if (p === "/api/device/claim" && req.method === "POST") return claim(req, env);
      const owner = await ownerHash(env);
      if (!owner) return json({ error: "站点尚未认领" }, 401);
      if (p === "/api/pair" && req.method === "POST") return pair(req, env);
      if (p.startsWith("/api/device/")) {
        if (!(await isDevice(req, env))) return json({ error: "认领密钥无效" }, 401);
        if (p === "/api/device/poll") return poll(env);
        if (p === "/api/device/ping") return json({ ok: true, version: VERSION, features: ["claim", "pairing"] });
        if (p === "/api/device/rekey" && req.method === "POST") return rekey(req, env);
        if (p === "/api/device/pair/start" && req.method === "POST") return startPair(env);
        if (p === "/api/device/devices" && req.method === "GET") {
          const devices = (await env.DB.prepare("SELECT id, name, created, last_seen FROM devices ORDER BY created DESC").all()).results;
          return json(devices);
        }
        if (p === "/api/device/devices/revoke" && req.method === "POST") {
          const body = await req.json().catch(() => ({}));
          await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(String(body.id || "")).run();
          return json({ ok: true });
        }
        if (p === "/api/device/reply" && req.method === "POST") return reply(req, env);
        if (p === "/api/device/ask" && req.method === "POST") return ask(req, env);
        if (p === "/api/device/cancel" && req.method === "POST") return cancelAsk(req, env);
        if (p === "/api/device/upload" && req.method === "POST") return upload(req, env, url, "k");
        if (p === "/api/device/file") return file(url, env);
        return json({ error: "没有这个接口" }, 404);
      }
      if (!(await isWeb(req, env))) return json({ error: "这台设备尚未配对，请先配对" }, 401);
      if (p === "/api/state") return state(url, env);
      if (p === "/api/send" && req.method === "POST") return send(req, env);
      if (p === "/api/upload" && req.method === "POST") return upload(req, env, url, "u");
      if (p === "/api/file") return file(url, env);
      if (p === "/api/confirm" && req.method === "POST") return answer(req, env);
      if (p === "/api/stop" && req.method === "POST") return stop(req, env);
      if (p === "/api/delete" && req.method === "POST") return removeConv(req, env);
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
.hint{font-size:13px;color:var(--sub)}
</style></head>
<body>
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
  if (r.status === 401) { location.replace("/pair"); throw new Error("401"); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || r.status);
  return j;
}
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
function start() { $("app").hidden = false; since = 0; msgs.clear(); refresh(); }
api("/api/state").then(start).catch(() => {});
document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });
</script>
</body></html>`;

const PAIR_PAGE = `<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#1d212b" media="(prefers-color-scheme: dark)">
<title>配对设备 · Kavi</title>
<style>
:root{color-scheme:light dark;--bg:#fff;--fg:#1c1c1e;--sub:#6b7280;--line:#e5e7eb;--bubble:#f3f4f6;--accent:#1c1c1e;--card:#fff;--err:#c0392b}
@media(prefers-color-scheme:dark){:root{--bg:#1d212b;--fg:#f3f4f6;--sub:#9ca3af;--line:#343a46;--bubble:#2a303c;--accent:#f3f4f6;--card:#252a35;--err:#f87171}}
*{box-sizing:border-box}html,body{margin:0;min-height:100%;background:var(--bg);color:var(--fg);font:16px/1.55 -apple-system,BlinkMacSystemFont,"PingFang SC","Helvetica Neue",sans-serif}
main{min-height:100svh;display:grid;place-items:center;padding:calc(env(safe-area-inset-top) + 20px) 18px calc(env(safe-area-inset-bottom) + 20px)}
.card{width:100%;max-width:430px;border:1px solid var(--line);border-radius:24px;padding:26px 22px;background:var(--card);box-shadow:0 12px 36px #0000000d}
.brand{font-weight:700;letter-spacing:.04em;color:var(--sub);font-size:14px}
h1{font-size:24px;line-height:1.25;margin:10px 0}
p{color:var(--sub);margin:8px 0 22px}
label{display:block;font-size:14px;font-weight:600;margin:16px 0 6px}
input{width:100%;height:52px;border:1px solid var(--line);border-radius:14px;padding:0 14px;background:var(--bg);color:var(--fg);font:inherit}
#code{text-align:center;font-variant-numeric:tabular-nums;letter-spacing:.28em;font-size:25px;font-weight:650}
button{width:100%;min-height:52px;border:0;border-radius:15px;margin-top:20px;background:var(--accent);color:var(--bg);font:inherit;font-weight:650;cursor:pointer}
button:disabled{opacity:.55}
#message{min-height:24px;margin:12px 2px 0;font-size:14px;color:var(--sub)}
#message.error{color:var(--err)}
</style></head>
<body><main><section class="card">
  <div class="brand">KAVI · 远程连接</div>
  <h1>配对这台设备</h1>
  <p>请在手机 Kavi 的「设置 → 远程连接」中开始配对，然后扫描二维码，或输入手机显示的 6 位配对码。</p>
  <form id="form">
    <label for="code">6 位配对码</label>
    <input id="code" name="code" type="text" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" placeholder="000000" required>
    <label for="name">设备名称（可选）</label>
    <input id="name" name="name" type="text" maxlength="60" autocomplete="off">
    <button id="submit" type="submit">配对并进入</button>
  </form>
  <div id="message" role="status" aria-live="polite"></div>
</section></main>
<script>
const $=id=>document.getElementById(id);
function guessName(){
 const ua=navigator.userAgent||"";
 const os=/iPhone/i.test(ua)?"iPhone":/iPad/i.test(ua)?"iPad":/Android/i.test(ua)?"Android":/Macintosh|Mac OS/i.test(ua)?"Mac":/Windows/i.test(ua)?"Windows":"网页设备";
 const browser=/Edg\\//i.test(ua)?"Edge":/CriOS|Chrome/i.test(ua)?"Chrome":/Firefox|FxiOS/i.test(ua)?"Firefox":/Safari/i.test(ua)?"Safari":"浏览器";
 return os+" "+browser;
}
$("name").value=guessName();
async function submitPair(payload){
 const button=$("submit"), message=$("message");
 button.disabled=true; message.classList.remove("error"); message.textContent="正在配对…";
 try{
  const response=await fetch("/api/pair",{method:"POST",credentials:"same-origin",headers:{"content-type":"application/json"},body:JSON.stringify({...payload,name:$("name").value.trim()})});
  const result=await response.json().catch(()=>({}));
  if(!response.ok)throw new Error(result.error||"配对失败，请重试");
  message.textContent="配对成功，正在打开…"; location.replace("/");
 }catch(error){message.classList.add("error");message.textContent=error.message;button.disabled=false;}
}
$("form").addEventListener("submit",event=>{
 event.preventDefault();
 const code=$("code").value.trim();
 if(!/^[0-9]{6}$/.test(code)){ $("message").classList.add("error"); $("message").textContent="请输入 6 位数字配对码"; return; }
 submitPair({code});
});
const token=new URLSearchParams(location.hash.slice(1)).get("t");
if(token){
 history.replaceState(null,"",location.pathname+location.search);
 document.querySelector('label[for="code"]').hidden=true; $("code").hidden=true;
 submitPair({token});
}
</script></body></html>`;
