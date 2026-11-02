// Kavi 远程：iPhone、电脑等别的设备通过这个站点用安卓手机上的 Kavi。
// 站点只当中转：网页把消息存进数据库（D1），手机上的 Kavi 隔几秒来取，处理完把回答写回来。文件放在 R2。
// 需要的绑定：D1 数据库 DB、R2 存储桶 FILES。表在第一次请求时自动建好。
//
// 好几台手机能共用一个站点，各自一块、互相看不到：
// - 第一台来认领的手机是站长（admin）；之后的手机要拿站长在 Kavi 里生成的邀请码才能加入。
// - 每台手机用自己的密钥（只存 SHA-256）；它配对的网页设备、对话、确认、文件都记在它名下。
// - 站长能看成员列表、把成员移出（连同他的对话和文件一起删掉）。

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS convs (id TEXT PRIMARY KEY, title TEXT, updated INTEGER)`,
  `CREATE TABLE IF NOT EXISTS msgs (id INTEGER PRIMARY KEY AUTOINCREMENT, conv TEXT, role TEXT, text TEXT, files TEXT, steps TEXT,
     state TEXT, reply_to INTEGER, stop INTEGER DEFAULT 0, stop_sent INTEGER DEFAULT 0, created INTEGER, updated INTEGER)`,
  `CREATE INDEX IF NOT EXISTS msgs_conv ON msgs(conv, updated)`,
  `CREATE INDEX IF NOT EXISTS msgs_state ON msgs(state)`,
  `CREATE TABLE IF NOT EXISTS confirms (id TEXT PRIMARY KEY, conv TEXT, msg INTEGER, title TEXT, body TEXT, action TEXT,
     answer INTEGER, sent INTEGER DEFAULT 0, created INTEGER)`,
  `CREATE TABLE IF NOT EXISTS fails (ip TEXT, at INTEGER)`,
  `CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created INTEGER NOT NULL, last_seen INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS owners (id TEXT PRIMARY KEY, key_hash TEXT NOT NULL UNIQUE, name TEXT NOT NULL DEFAULT '', admin INTEGER NOT NULL DEFAULT 0,
     created INTEGER NOT NULL, last_seen INTEGER NOT NULL DEFAULT 0)`,
  `CREATE TABLE IF NOT EXISTS invites (code_hash TEXT PRIMARY KEY, by_owner TEXT NOT NULL, expires INTEGER NOT NULL, used_by TEXT)`,
  `CREATE TABLE IF NOT EXISTS pairings (owner TEXT PRIMARY KEY, code TEXT NOT NULL, token_hash TEXT NOT NULL, expires INTEGER NOT NULL, failures INTEGER NOT NULL DEFAULT 0)`,
];
/** 老站点（只认一台手机）的表加上「属于谁」这一列；已经有了就报错，忽略。 */
const OWNER_COLUMNS = ["convs", "msgs", "confirms", "devices"];

let ready = false;
async function init(env) {
  if (ready) return;
  await env.DB.batch(SCHEMA.map((s) => env.DB.prepare(s)));
  for (const t of OWNER_COLUMNS) await env.DB.prepare(`ALTER TABLE ${t} ADD COLUMN owner TEXT`).run().catch(() => {});
  await env.DB.batch([
    env.DB.prepare("CREATE INDEX IF NOT EXISTS msgs_owner ON msgs(owner, role, state)"),
    env.DB.prepare("CREATE INDEX IF NOT EXISTS convs_owner ON convs(owner, updated)"),
  ]);
  // 老站点的主人（owner 表那一行）搬成站长，原来的对话、设备都归他
  const legacy = await env.DB.prepare("SELECT key_hash FROM owner WHERE id = 1").first().catch(() => null);
  if (legacy && !(await env.DB.prepare("SELECT id FROM owners LIMIT 1").first())) await adopt(env, legacy.key_hash);
  // 用 Secrets 里 KAVI_KEY 管的老站点：那把密钥就是站长
  if (env.KAVI_KEY) {
    const h = await sha256(env.KAVI_KEY);
    if (!(await env.DB.prepare("SELECT id FROM owners WHERE key_hash = ?").bind(h).first())) {
      const admin = await env.DB.prepare("SELECT id FROM owners WHERE admin = 1").first();
      if (admin) await env.DB.prepare("UPDATE owners SET key_hash = ? WHERE id = ?").bind(h, admin.id).run();
      else await adopt(env, h);
    }
  }
  ready = true;
}
/** 建站长，把还没主人的旧数据都记到他名下。 */
async function adopt(env, keyHash) {
  const id = crypto.randomUUID();
  await env.DB.prepare("INSERT OR IGNORE INTO owners (id, key_hash, name, admin, created) VALUES (?, ?, '站长', 1, ?)").bind(id, keyHash, now()).run();
  const row = await env.DB.prepare("SELECT id FROM owners WHERE key_hash = ?").bind(keyHash).first();
  await env.DB.batch(OWNER_COLUMNS.map((t) => env.DB.prepare(`UPDATE ${t} SET owner = ? WHERE owner IS NULL`).bind(row.id)));
}

/** 站点代码的版本：Kavi 测试连接时看得到，接口改了要升。 */
const VERSION = 3;
const MAX_UPLOAD = 25 * 1024 * 1024;
const ONLINE_MS = 30_000;
const INVITE_DAYS = 7;

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
const ip = (req, kind) => `${kind}:${req.headers.get("cf-connecting-ip") || "?"}`;
async function tooMany(env, key, max) {
  const r = await env.DB.prepare("SELECT COUNT(*) AS n FROM fails WHERE ip = ? AND at > ?").bind(key, now() - 10 * 60_000).first();
  return (r?.n || 0) >= max;
}
async function failed(env, key) {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO fails (ip, at) VALUES (?, ?)").bind(key, now()),
    env.DB.prepare("DELETE FROM fails WHERE at < ?").bind(now() - 86_400_000),
  ]);
}

/** 手机上的 Kavi：Bearer 密钥对上哪台手机（owners 一行），对不上返回 null。 */
async function deviceOwner(req, env) {
  const auth = req.headers.get("authorization") || "";
  if (!auth.startsWith("Bearer ") || !auth.slice(7)) return null;
  const h = await sha256(auth.slice(7));
  const row = await env.DB.prepare("SELECT * FROM owners WHERE key_hash = ?").bind(h).first();
  return row && same(row.key_hash, h) ? row : null;
}
/** 网页：cookie 里的设备令牌对上哪台配过对的设备（只存 SHA-256），返回它属于哪台手机。 */
async function webOwner(req, env) {
  const token = cookie(req, "kavi_device");
  if (!token) return null;
  const h = await sha256(token);
  const d = await env.DB.prepare("SELECT id, token_hash, last_seen, owner FROM devices WHERE token_hash = ?").bind(h).first();
  if (!d || !d.owner || !same(h, d.token_hash)) return null;
  const t = now();
  if (Number(d.last_seen) <= t - 60_000) await env.DB.prepare("UPDATE devices SET last_seen = ? WHERE id = ?").bind(t, d.id).run();
  return d.owner;
}

function randomToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
/** 邀请码：8 个好认的字符（去掉 0O1IL），中间一个横杠，方便口头或微信发。 */
function inviteCode() {
  const abc = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
  const r = crypto.getRandomValues(new Uint8Array(8));
  const s = [...r].map((b) => abc[b % abc.length]).join("");
  return s.slice(0, 4) + "-" + s.slice(4);
}
const normInvite = (s) => String(s || "").toUpperCase().replace(/[^0-9A-Z]/g, "");

// ---------- 认领、邀请、成员 ----------

/**
 * 认领：密钥已经是成员的，直接认；站点还没有人的，第一个来的是站长；
 * 其他人要带站长给的邀请码（一次性、7 天有效）。没邀请码或不对返回 403。
 */
async function claim(req, env) {
  const auth = req.headers.get("authorization") || "";
  if (!auth.startsWith("Bearer ") || !auth.slice(7)) return json({ error: "请提供密钥" }, 401);
  const keyHash = await sha256(auth.slice(7));
  const b = await req.json().catch(() => ({}));
  const name = String(b.name || "").trim().slice(0, 40);
  if (await env.DB.prepare("SELECT id FROM owners WHERE key_hash = ?").bind(keyHash).first()) return json({ ok: true });
  const t = now();
  if (!(await env.DB.prepare("SELECT id FROM owners LIMIT 1").first())) {
    // 两台手机同时来抢：INSERT 只成功一个，另一个再查一遍就知道自己不是站长
    await env.DB.prepare("INSERT INTO owners (id, key_hash, name, admin, created) SELECT ?, ?, ?, 1, ? WHERE NOT EXISTS (SELECT 1 FROM owners)")
      .bind(crypto.randomUUID(), keyHash, name || "站长", t).run();
    if (await env.DB.prepare("SELECT id FROM owners WHERE key_hash = ?").bind(keyHash).first()) return json({ ok: true, claimed: true, admin: true });
  }
  const limit = ip(req, "invite");
  if (await tooMany(env, limit, 10)) return json({ error: "试错太多次，十分钟后再试" }, 429);
  const code = normInvite(b.invite);
  if (!code) return json({ error: "need_invite", message: "这个站点已经有站长了，加入要邀请码" }, 403);
  const codeHash = await sha256(code);
  const id = crypto.randomUUID();
  // 邀请码用掉（一次性）和建成员放一起：两台手机拿同一个码同时来，只有一台成
  const used = await env.DB.prepare("UPDATE invites SET used_by = ? WHERE code_hash = ? AND used_by IS NULL AND expires > ? RETURNING code_hash")
    .bind(id, codeHash, Math.floor(t / 1000)).first();
  if (!used) { await failed(env, limit); return json({ error: "bad_invite", message: "邀请码不对、用过了或过期了" }, 403); }
  await env.DB.prepare("INSERT INTO owners (id, key_hash, name, admin, created) VALUES (?, ?, ?, 0, ?)").bind(id, keyHash, name || "成员", t).run();
  return json({ ok: true, claimed: true, admin: false });
}

async function invite(owner, env) {
  if (!owner.admin) return json({ error: "只有站长能邀请" }, 403);
  const code = inviteCode();
  const expires = Math.floor(now() / 1000) + INVITE_DAYS * 86400;
  await env.DB.batch([
    env.DB.prepare("DELETE FROM invites WHERE expires < ?").bind(Math.floor(now() / 1000)),
    env.DB.prepare("INSERT INTO invites (code_hash, by_owner, expires) VALUES (?, ?, ?)").bind(await sha256(normInvite(code)), owner.id, expires),
  ]);
  return json({ code, expires });
}

async function members(owner, env) {
  if (!owner.admin) return json({ error: "只有站长能看成员" }, 403);
  const rows = (await env.DB.prepare("SELECT id, name, admin, created, last_seen FROM owners ORDER BY created").all()).results;
  return json(rows.map((r) => ({ ...r, admin: r.admin === 1, me: r.id === owner.id })));
}

/** 站长把一个成员移出：他的密钥作废，配过的设备、对话、文件一起删。 */
async function removeMember(req, owner, env) {
  if (!owner.admin) return json({ error: "只有站长能移出成员" }, 403);
  const id = String((await req.json().catch(() => ({}))).id || "");
  if (!id || id === owner.id) return json({ error: "不能移出自己" }, 400);
  await deleteFiles(env, `${id}/`);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM owners WHERE id = ? AND admin = 0").bind(id),
    env.DB.prepare("DELETE FROM devices WHERE owner = ?").bind(id),
    env.DB.prepare("DELETE FROM pairings WHERE owner = ?").bind(id),
    env.DB.prepare("DELETE FROM confirms WHERE owner = ?").bind(id),
    env.DB.prepare("DELETE FROM msgs WHERE owner = ?").bind(id),
    env.DB.prepare("DELETE FROM convs WHERE owner = ?").bind(id),
  ]);
  return json({ ok: true });
}
async function deleteFiles(env, prefix) {
  let cursor;
  do {
    const list = await env.FILES.list({ prefix, cursor });
    if (list.objects.length) await env.FILES.delete(list.objects.map((o) => o.key));
    cursor = list.truncated ? list.cursor : undefined;
  } while (cursor);
}

async function rekey(req, owner, env) {
  if (env.KAVI_KEY && owner.admin) return json({ error: "站长密钥由站点的 KAVI_KEY 管，不能在线换" }, 409);
  const b = await req.json().catch(() => ({}));
  if (typeof b.new_key !== "string" || !b.new_key || b.new_key.length > 256) return json({ error: "新密钥无效" }, 400);
  await env.DB.batch([
    env.DB.prepare("UPDATE owners SET key_hash = ? WHERE id = ?").bind(await sha256(b.new_key), owner.id),
    env.DB.prepare("DELETE FROM devices WHERE owner = ?").bind(owner.id),
    env.DB.prepare("DELETE FROM pairings WHERE owner = ?").bind(owner.id),
  ]);
  return json({ ok: true });
}

// ---------- 配对网页设备 ----------

function guessDeviceName(ua) {
  const s = String(ua || "");
  const os = /iPhone/i.test(s) ? "iPhone" : /iPad/i.test(s) ? "iPad" : /Android/i.test(s) ? "Android" : /Macintosh|Mac OS/i.test(s) ? "Mac" : /Windows/i.test(s) ? "Windows" : "网页设备";
  const browser = /Edg\//i.test(s) ? "Edge" : /CriOS|Chrome/i.test(s) ? "Chrome" : /Firefox|FxiOS/i.test(s) ? "Firefox" : /Safari/i.test(s) ? "Safari" : "浏览器";
  return `${os} ${browser}`;
}
/** 发起配对：每台手机同时只留一个，新的出来旧的作废；6 位码在全站有效的码里不重复。 */
async function startPair(owner, env) {
  const t = Math.floor(now() / 1000);
  let code;
  for (let i = 0; i < 20; i++) {
    code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000).padStart(6, "0");
    if (!(await env.DB.prepare("SELECT owner FROM pairings WHERE code = ? AND expires > ? AND owner != ?").bind(code, t, owner.id).first())) break;
  }
  const token = randomToken();
  const expires = t + 5 * 60;
  await env.DB.prepare("INSERT INTO pairings (owner, code, token_hash, expires, failures) VALUES (?, ?, ?, ?, 0) ON CONFLICT(owner) DO UPDATE SET code = excluded.code, token_hash = excluded.token_hash, expires = excluded.expires, failures = 0")
    .bind(owner.id, code, await sha256(token), expires).run();
  return json({ code, token, expires });
}
async function pair(req, env) {
  const body = await req.json().catch(() => ({}));
  const limit = ip(req, "pair");
  if (await tooMany(env, limit, 5)) return json({ error: "尝试次数太多，请十分钟后再试" }, 429);
  const t = now();
  const sec = Math.floor(t / 1000);
  let active = null;
  if (typeof body.token === "string" && body.token && body.token.length <= 256)
    active = await env.DB.prepare("SELECT * FROM pairings WHERE token_hash = ? AND expires > ?").bind(await sha256(body.token), sec).first();
  else if (typeof body.code === "string" && /^[0-9]{6}$/.test(body.code))
    active = await env.DB.prepare("SELECT * FROM pairings WHERE code = ? AND expires > ?").bind(body.code, sec).first();
  if (!active) { await failed(env, limit); return json({ error: "配对码无效或已过期" }, 401); }
  // 用掉（一次性）：两边同时拿同一个码来，只有一边删得掉
  const consumed = await env.DB.prepare("DELETE FROM pairings WHERE owner = ? AND token_hash = ? RETURNING owner").bind(active.owner, active.token_hash).first();
  if (!consumed) return json({ error: "配对码已使用或已过期" }, 401);
  const deviceToken = randomToken();
  const name = String(body.name || guessDeviceName(req.headers.get("user-agent"))).trim().slice(0, 60) || "网页设备";
  await env.DB.prepare("INSERT INTO devices (id, name, token_hash, created, last_seen, owner) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(crypto.randomUUID(), name, await sha256(deviceToken), t, t, active.owner).run();
  return json({ ok: true }, 200, { "set-cookie": `kavi_device=${deviceToken}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=31536000` });
}

const parseMsg = (r) => r && ({ ...r, files: JSON.parse(r.files || "[]"), steps: JSON.parse(r.steps || "[]") });

// ---------- 网页用的接口（都只看这台手机名下的） ----------

async function state(url, owner, env) {
  const conv = url.searchParams.get("conv") || "";
  const since = Number(url.searchParams.get("since") || 0);
  const o = await env.DB.prepare("SELECT last_seen FROM owners WHERE id = ?").bind(owner).first();
  const seen = Number(o?.last_seen || 0);
  const convs = (await env.DB.prepare("SELECT id, title, updated FROM convs WHERE owner = ? ORDER BY updated DESC LIMIT 100").bind(owner).all()).results;
  let msgs = [], confirms = [];
  if (conv) {
    msgs = (await env.DB.prepare("SELECT * FROM msgs WHERE conv = ? AND owner = ? AND updated > ? ORDER BY id").bind(conv, owner, since).all()).results.map(parseMsg);
    confirms = (await env.DB.prepare("SELECT id, msg, title, body, action FROM confirms WHERE conv = ? AND owner = ? AND answer IS NULL ORDER BY created").bind(conv, owner).all()).results;
  }
  return json({ now: now(), device: { online: now() - seen < ONLINE_MS, lastSeen: seen }, convs, msgs, confirms });
}

/** 文件只能是这台手机名下的：网页、Kavi 传的都在「手机 id/」下面；老站点搬过来的（u/、k/ 开头）归站长。 */
async function ownsFile(env, owner, key) {
  if (key.startsWith(`${owner}/`)) return true;
  if (!/^[uk]\//.test(key)) return false;
  return !!(await env.DB.prepare("SELECT 1 FROM owners WHERE id = ? AND admin = 1").bind(owner).first());
}

async function send(req, owner, env) {
  const b = await req.json().catch(() => ({}));
  const text = String(b.text || "").slice(0, 100_000);
  // 文件名是网页端传来的：斜杠换掉，免得手机端拼路径时被 ../ 带到别的目录（upload() 同款处理）
  const raw = Array.isArray(b.files) ? b.files.slice(0, 20) : [];
  const files = [];
  for (const f of raw) {
    if (!(await ownsFile(env, owner, String(f?.key || "")))) return json({ error: "文件不对" }, 400);
    files.push({ ...f, name: String(f?.name || "file").replace(/[\\/]/g, "_").slice(0, 120) });
  }
  if (!text.trim() && !files.length) return json({ error: "空消息" }, 400);
  let conv = String(b.conv || "");
  const t = now();
  if (!conv) {
    conv = crypto.randomUUID();
    const title = (text.trim() || files[0]?.name || "新对话").replace(/\s+/g, " ").slice(0, 30);
    await env.DB.prepare("INSERT INTO convs (id, title, updated, owner) VALUES (?, ?, ?, ?)").bind(conv, title, t, owner).run();
  } else {
    const r = await env.DB.prepare("UPDATE convs SET updated = ? WHERE id = ? AND owner = ?").bind(t, conv, owner).run();
    if (!r.meta?.changes) return json({ error: "没有这个对话" }, 404);
  }
  const r = await env.DB.prepare("INSERT INTO msgs (conv, role, text, files, steps, state, created, updated, owner) VALUES (?, 'user', ?, ?, '[]', 'pending', ?, ?, ?)")
    .bind(conv, text, JSON.stringify(files), t, t, owner).run();
  return json({ conv, id: r.meta.last_row_id });
}

async function upload(req, env, url, owner, prefix) {
  const name = (url.searchParams.get("name") || "file").replace(/[\\/]/g, "_").slice(0, 120);
  const type = url.searchParams.get("type") || req.headers.get("content-type") || "application/octet-stream";
  const len = Number(req.headers.get("content-length") || 0);
  if (len > MAX_UPLOAD) return json({ error: "文件太大（最多 25MB）" }, 413);
  const key = `${owner}/${prefix}/${crypto.randomUUID()}/${name}`;
  const body = await req.arrayBuffer();
  if (body.byteLength > MAX_UPLOAD) return json({ error: "文件太大（最多 25MB）" }, 413);
  await env.FILES.put(key, body, { httpMetadata: { contentType: type } });
  return json({ key, name, size: body.byteLength, type });
}

async function file(url, owner, env) {
  const key = url.searchParams.get("key") || "";
  const obj = key && (await ownsFile(env, owner, key)) ? await env.FILES.get(key) : null;
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

async function answer(req, owner, env) {
  const b = await req.json().catch(() => ({}));
  await env.DB.prepare("UPDATE confirms SET answer = ? WHERE id = ? AND owner = ? AND answer IS NULL").bind(b.ok ? 1 : 0, String(b.id || ""), owner).run();
  return json({ ok: true });
}

async function stop(req, owner, env) {
  const b = await req.json().catch(() => ({}));
  await env.DB.prepare("UPDATE msgs SET stop = 1, updated = ? WHERE id = ? AND owner = ? AND role = 'user'").bind(now(), Number(b.id || 0), owner).run();
  return json({ ok: true });
}

async function removeConv(req, owner, env) {
  const b = await req.json().catch(() => ({}));
  const conv = String(b.conv || "");
  const rows = (await env.DB.prepare("SELECT files FROM msgs WHERE conv = ? AND owner = ?").bind(conv, owner).all()).results;
  const keys = [];
  for (const r of rows) for (const f of JSON.parse(r.files || "[]")) if (f.key && (await ownsFile(env, owner, f.key))) keys.push(f.key);
  for (let i = 0; i < keys.length; i += 500) await env.FILES.delete(keys.slice(i, i + 500));
  await env.DB.batch([
    env.DB.prepare("DELETE FROM msgs WHERE conv = ? AND owner = ?").bind(conv, owner),
    env.DB.prepare("DELETE FROM confirms WHERE conv = ? AND owner = ?").bind(conv, owner),
    env.DB.prepare("DELETE FROM convs WHERE id = ? AND owner = ?").bind(conv, owner),
  ]);
  return json({ ok: true });
}

// ---------- 手机上的 Kavi 用的接口（都只动自己名下的） ----------

/** 来取活：新消息（取走就标成 taken）、用户回了的确认、要停的回答。顺便记一下 Kavi 在线。 */
async function poll(owner, env) {
  const t = now();
  await env.DB.prepare("UPDATE owners SET last_seen = ? WHERE id = ?").bind(t, owner).run();
  const msgs = (await env.DB.prepare("SELECT id, conv, text, files FROM msgs WHERE owner = ? AND role = 'user' AND state = 'pending' ORDER BY id LIMIT 5").bind(owner).all()).results;
  if (msgs.length) await env.DB.prepare(`UPDATE msgs SET state = 'taken', updated = ? WHERE id IN (${msgs.map(() => "?").join(",")})`).bind(t, ...msgs.map((m) => m.id)).run();
  const answers = (await env.DB.prepare("SELECT id, answer FROM confirms WHERE owner = ? AND answer IS NOT NULL AND sent = 0").bind(owner).all()).results;
  if (answers.length) await env.DB.prepare(`UPDATE confirms SET sent = 1 WHERE id IN (${answers.map(() => "?").join(",")})`).bind(...answers.map((a) => a.id)).run();
  const stops = (await env.DB.prepare("SELECT id FROM msgs WHERE owner = ? AND role = 'user' AND stop = 1 AND stop_sent = 0").bind(owner).all()).results;
  if (stops.length) await env.DB.prepare(`UPDATE msgs SET stop_sent = 1 WHERE id IN (${stops.map(() => "?").join(",")})`).bind(...stops.map((s) => s.id)).run();
  return json({
    now: t,
    messages: msgs.map((m) => ({ id: m.id, conv: m.conv, text: m.text, files: JSON.parse(m.files || "[]") })),
    answers: answers.map((a) => ({ id: a.id, ok: a.answer === 1 })),
    stops: stops.map((s) => s.id),
  });
}

/** 写回答（同一条用户消息的回答一直更新同一行）。state：running 还在写、done 写完、error 出错。 */
async function reply(req, owner, env) {
  const b = await req.json().catch(() => ({}));
  const to = Number(b.reply_to || 0);
  const user = await env.DB.prepare("SELECT conv FROM msgs WHERE id = ? AND owner = ? AND role = 'user'").bind(to, owner).first();
  if (!user) return json({ error: "没有这条消息" }, 404);
  const t = now();
  const text = String(b.text || "").slice(0, 200_000);
  const list = Array.isArray(b.files) ? b.files : [];
  for (const f of list) if (!(await ownsFile(env, owner, String(f?.key || "")))) return json({ error: "文件不对" }, 400);
  const files = JSON.stringify(list);
  const steps = JSON.stringify(Array.isArray(b.steps) ? b.steps.slice(-40) : []);
  const st = ["running", "done", "error"].includes(b.state) ? b.state : "running";
  const old = await env.DB.prepare("SELECT id FROM msgs WHERE reply_to = ? AND owner = ? AND role = 'assistant'").bind(to, owner).first();
  if (old) await env.DB.prepare("UPDATE msgs SET text = ?, files = ?, steps = ?, state = ?, updated = ? WHERE id = ?").bind(text, files, steps, st, t, old.id).run();
  else await env.DB.prepare("INSERT INTO msgs (conv, role, text, files, steps, state, reply_to, created, updated, owner) VALUES (?, 'assistant', ?, ?, ?, ?, ?, ?, ?, ?)")
    .bind(user.conv, text, files, steps, st, to, t, t, owner).run();
  if (st !== "running") await env.DB.prepare("UPDATE msgs SET state = 'done', updated = ? WHERE id = ?").bind(t, to).run();
  await env.DB.prepare("UPDATE convs SET updated = ? WHERE id = ? AND owner = ?").bind(t, user.conv, owner).run();
  return json({ ok: true });
}

/** Kavi 要用户确认一件事：网页上弹出来，用户点了以后 Kavi 下次来取时拿到。 */
async function ask(req, owner, env) {
  const b = await req.json().catch(() => ({}));
  const to = Number(b.msg || 0);
  const user = await env.DB.prepare("SELECT conv FROM msgs WHERE id = ? AND owner = ?").bind(to, owner).first();
  if (!user) return json({ error: "没有这条消息" }, 404);
  const id = crypto.randomUUID();
  await env.DB.prepare("INSERT INTO confirms (id, conv, msg, title, body, action, created, owner) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
    .bind(id, user.conv, to, String(b.title || "").slice(0, 200), String(b.body || "").slice(0, 4000), String(b.action || "确定").slice(0, 20), now(), owner).run();
  await env.DB.prepare("UPDATE convs SET updated = ? WHERE id = ? AND owner = ?").bind(now(), user.conv, owner).run();
  return json({ id });
}

/** 确认超时（Kavi 不等了）：从网页上撤掉。 */
async function cancelAsk(req, owner, env) {
  const b = await req.json().catch(() => ({}));
  await env.DB.prepare("UPDATE confirms SET answer = 0, sent = 1 WHERE id = ? AND owner = ? AND answer IS NULL").bind(String(b.id || ""), owner).run();
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
      if (p === "/api/pair" && req.method === "POST") return pair(req, env);
      if (p.startsWith("/api/device/")) {
        const me = await deviceOwner(req, env);
        if (!me) {
          const anyone = await env.DB.prepare("SELECT id FROM owners LIMIT 1").first();
          return json({ error: anyone ? "密钥不对：这台手机还不是站点成员" : "站点尚未认领" }, 401);
        }
        const o = me.id;
        if (p === "/api/device/poll") return poll(o, env);
        if (p === "/api/device/ping") return json({ ok: true, version: VERSION, features: ["claim", "pairing", "members"], admin: me.admin === 1 });
        if (p === "/api/device/rekey" && req.method === "POST") return rekey(req, me, env);
        if (p === "/api/device/pair/start" && req.method === "POST") return startPair(me, env);
        if (p === "/api/device/invite" && req.method === "POST") return invite(me, env);
        if (p === "/api/device/members" && req.method === "GET") return members(me, env);
        if (p === "/api/device/members/remove" && req.method === "POST") return removeMember(req, me, env);
        if (p === "/api/device/devices" && req.method === "GET") {
          const devices = (await env.DB.prepare("SELECT id, name, created, last_seen FROM devices WHERE owner = ? ORDER BY created DESC").bind(o).all()).results;
          return json(devices);
        }
        if (p === "/api/device/devices/revoke" && req.method === "POST") {
          const body = await req.json().catch(() => ({}));
          await env.DB.prepare("DELETE FROM devices WHERE id = ? AND owner = ?").bind(String(body.id || ""), o).run();
          return json({ ok: true });
        }
        if (p === "/api/device/reply" && req.method === "POST") return reply(req, o, env);
        if (p === "/api/device/ask" && req.method === "POST") return ask(req, o, env);
        if (p === "/api/device/cancel" && req.method === "POST") return cancelAsk(req, o, env);
        if (p === "/api/device/upload" && req.method === "POST") return upload(req, env, url, o, "k");
        if (p === "/api/device/file") return file(url, o, env);
        return json({ error: "没有这个接口" }, 404);
      }
      const o = await webOwner(req, env);
      if (!o) return json({ error: "这台设备尚未配对，请先配对" }, 401);
      if (p === "/api/state") return state(url, o, env);
      if (p === "/api/send" && req.method === "POST") return send(req, o, env);
      if (p === "/api/upload" && req.method === "POST") return upload(req, env, url, o, "u");
      if (p === "/api/file") return file(url, o, env);
      if (p === "/api/confirm" && req.method === "POST") return answer(req, o, env);
      if (p === "/api/stop" && req.method === "POST") return stop(req, o, env);
      if (p === "/api/delete" && req.method === "POST") return removeConv(req, o, env);
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
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="default">
<meta name="apple-mobile-web-app-title" content="Kavi">
<meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#0c0e13" media="(prefers-color-scheme: dark)">
<link rel="manifest" href="/manifest.json">
<title>Kavi</title>
<style>
/* 配色跟 App 一样：浅色黑白，深色是带一点蓝的深灰 */
:root{--bg:#fff;--fg:#0a0a0a;--sub:#6b6b6b;--line:#e5e5e5;--soft:#f2f2f2;--softer:#f8f8f8;--accent:#0a0a0a;--on-accent:#fff;--card:#fff;--err:#d92d20;--ok:#22a06b;--shadow:0 1px 2px #0000000d,0 8px 24px #0000000a}
@media (prefers-color-scheme: dark){:root{--bg:#0c0e13;--fg:#f0f2f5;--sub:#9ea4b0;--line:#232833;--soft:#1b1f28;--softer:#12151c;--accent:#f0f2f5;--on-accent:#0c0e13;--card:#12151c;--err:#f87171;--ok:#34d399;--shadow:0 1px 2px #0000004d}}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;height:100%;background:var(--bg);color:var(--fg);font:16px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC","Helvetica Neue","Microsoft YaHei",sans-serif;-webkit-font-smoothing:antialiased}
button{font:inherit;color:inherit;background:none;border:0;cursor:pointer;padding:0}
svg{display:block}
#app{display:flex;flex-direction:column;height:100%;height:100dvh;max-width:860px;margin:0 auto}

/* 顶栏 */
header{display:flex;align-items:center;gap:6px;padding:calc(env(safe-area-inset-top) + 8px) 10px 8px}
header h1{font-size:17px;font-weight:650;margin:0 4px;flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.icon{width:40px;height:40px;border-radius:12px;display:flex;align-items:center;justify-content:center;flex:none;color:var(--fg)}
.icon:active{background:var(--soft)}
.pill{display:flex;align-items:center;gap:6px;font-size:12px;color:var(--sub);background:var(--soft);border-radius:99px;padding:5px 10px;white-space:nowrap}
.dot{width:7px;height:7px;border-radius:50%;background:var(--sub)}.dot.on{background:var(--ok);box-shadow:0 0 0 3px color-mix(in srgb,var(--ok) 25%,transparent)}

/* 消息 */
#list{flex:1;overflow-y:auto;padding:8px 16px 16px;-webkit-overflow-scrolling:touch;overscroll-behavior:contain}
.msg{margin:18px 0;display:flex;flex-direction:column}.fresh{animation:in .25s ease-out}
@keyframes in{from{opacity:0;transform:translateY(6px)}}
.user{align-items:flex-end}
.user .b{background:var(--soft);border-radius:20px 20px 6px 20px;padding:9px 15px;max-width:82%;white-space:pre-wrap;word-break:break-word}
.bot .b{max-width:100%;word-break:break-word}
.bot .b>:first-child{margin-top:0}.bot .b>:last-child{margin-bottom:0}
.bot .b p{margin:.6em 0}.bot .b ul,.bot .b ol{margin:.5em 0;padding-left:1.4em}.bot .b li{margin:.2em 0}
.bot .b h3{font-size:1.05em;margin:1em 0 .4em}
.bot pre{background:var(--softer);border:1px solid var(--line);padding:12px;border-radius:12px;overflow-x:auto;font-size:13px;line-height:1.5}
.bot code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.88em;background:var(--soft);padding:.1em .35em;border-radius:5px}
.bot pre code{background:none;padding:0}
.bot a{color:inherit;text-underline-offset:3px}
.steps{margin:0 0 10px;font-size:13px;color:var(--sub)}
.steps summary{list-style:none;cursor:pointer;display:inline-flex;align-items:center;gap:6px;padding:4px 10px;border-radius:99px;background:var(--soft)}
.steps summary::-webkit-details-marker{display:none}
.steps ol{margin:8px 0 0;padding:0 0 0 4px;list-style:none;border-left:2px solid var(--line)}
.steps li{padding:2px 0 2px 12px}
.spin{width:12px;height:12px;border:2px solid var(--line);border-top-color:var(--sub);border-radius:50%;animation:spin .8s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
.typing{display:inline-flex;gap:4px;padding:10px 0}.typing i{width:7px;height:7px;border-radius:50%;background:var(--sub);opacity:.4;animation:blink 1.2s infinite}
.typing i:nth-child(2){animation-delay:.2s}.typing i:nth-child(3){animation-delay:.4s}
@keyframes blink{40%{opacity:1}}
.meta{display:flex;align-items:center;gap:10px;font-size:13px;color:var(--sub);margin-top:4px}
.chip{border:1px solid var(--line);border-radius:99px;padding:3px 12px;font-size:13px;color:var(--sub)}
.err{color:var(--err);font-size:14px;margin-top:6px}

/* 文件 */
.files{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}
.user .files{justify-content:flex-end}
.file{display:flex;align-items:center;gap:10px;border:1px solid var(--line);background:var(--card);border-radius:14px;padding:8px 12px 8px 8px;color:var(--fg);text-decoration:none;max-width:260px}
.file .fi{width:34px;height:34px;border-radius:9px;background:var(--soft);display:flex;align-items:center;justify-content:center;flex:none;font-size:10px;font-weight:700;color:var(--sub)}
.file span{font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.files img{max-width:240px;max-height:240px;border-radius:14px;display:block;border:1px solid var(--line)}

/* 确认卡片 */
.confirm{border:1px solid var(--line);border-radius:18px;padding:16px;margin:16px 0;background:var(--card);box-shadow:var(--shadow)}
.confirm h3{margin:0 0 6px;font-size:16px}.confirm p{margin:0 0 14px;white-space:pre-wrap;font-size:14px;color:var(--sub)}
.row{display:flex;gap:10px;justify-content:flex-end}
.btn{border:1px solid var(--line);border-radius:99px;padding:8px 18px;font-size:15px}
.btn.main{background:var(--accent);color:var(--on-accent);border-color:var(--accent);font-weight:600}

/* 空白页：和 App 一样的问候和建议卡片 */
.empty{min-height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:24px 4px 12px;text-align:center}
.logo{width:60px;height:60px;border-radius:18px;background:var(--accent);color:var(--on-accent);display:flex;align-items:center;justify-content:center;font-size:30px;font-weight:800;letter-spacing:-.02em;box-shadow:var(--shadow)}
.empty h2{font-size:26px;margin:20px 0 6px;font-weight:700}
.empty .sub{color:var(--sub);margin:0 0 28px}
.cards{display:grid;grid-template-columns:1fr 1fr;gap:12px;width:100%;max-width:520px}
.card{display:flex;flex-direction:column;align-items:flex-start;gap:14px;text-align:left;padding:14px;border-radius:20px;background:var(--softer);border:1px solid var(--line)}
.card:active{transform:scale(.98)}
.card .ci{width:34px;height:34px;border-radius:11px;display:flex;align-items:center;justify-content:center}
.card b{font-weight:600;font-size:15px}
.offline{margin:0 0 12px;font-size:13px;color:var(--sub);background:var(--soft);border-radius:12px;padding:10px 12px;text-align:left}

/* 输入框 */
footer{padding:6px 12px calc(env(safe-area-inset-bottom) + 10px)}
.box{border:1px solid var(--line);background:var(--card);border-radius:26px;padding:6px;box-shadow:var(--shadow)}
#pending{display:flex;flex-wrap:wrap;gap:6px;padding:4px 6px 6px}#pending:empty{display:none}
#pending span{display:flex;align-items:center;gap:6px;font-size:12px;background:var(--soft);border-radius:10px;padding:4px 6px 4px 10px}
#pending button{width:18px;height:18px;border-radius:50%;display:flex;align-items:center;justify-content:center;color:var(--sub)}
.in{display:flex;align-items:flex-end;gap:4px}
textarea{flex:1;resize:none;border:0;outline:0;padding:8px 6px;font:inherit;background:transparent;color:var(--fg);max-height:180px;line-height:1.5}
textarea::placeholder{color:var(--sub)}
#send{width:38px;height:38px;border-radius:50%;background:var(--accent);color:var(--on-accent);display:flex;align-items:center;justify-content:center;flex:none;transition:opacity .15s}
#send:disabled{opacity:.3}
#attach{width:38px;height:38px;border-radius:50%;color:var(--sub)}

/* 侧边的对话列表 */
#drawer{position:fixed;inset:0;z-index:5;pointer-events:none}
#drawer .shade{position:absolute;inset:0;background:#0006;opacity:0;transition:opacity .2s}
#drawer nav{position:absolute;top:0;bottom:0;left:0;background:var(--bg);width:84%;max-width:320px;padding:calc(env(safe-area-inset-top) + 12px) 10px 14px;overflow-y:auto;transform:translateX(-100%);transition:transform .25s ease;border-right:1px solid var(--line)}
#drawer.open{pointer-events:auto}#drawer.open .shade{opacity:1}#drawer.open nav{transform:none}
.navhead{display:flex;align-items:center;gap:10px;padding:4px 6px 14px}
.navhead .logo{width:32px;height:32px;border-radius:10px;font-size:17px;box-shadow:none}
.navhead b{flex:1;font-size:17px}
.newbtn{display:flex;align-items:center;gap:10px;width:100%;padding:11px 12px;border-radius:14px;background:var(--soft);font-weight:600;margin-bottom:12px}
.label{font-size:12px;color:var(--sub);padding:6px 12px}
.conv{display:flex;align-items:center;border-radius:12px}
.conv.cur{background:var(--soft)}
.conv>button:first-child{flex:1;min-width:0;text-align:left;padding:10px 12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.conv .del{width:34px;height:34px;border-radius:10px;color:var(--sub);display:flex;align-items:center;justify-content:center;opacity:.6}
</style></head>
<body>
<div id="app" hidden>
  <header>
    <button class="icon" id="menu" aria-label="对话列表"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 7h16M4 12h10M4 17h16"/></svg></button>
    <h1 id="title">Kavi</h1>
    <span class="pill" id="status"><span class="dot"></span>…</span>
    <button class="icon" id="new" aria-label="新对话"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg></button>
  </header>
  <div id="list"></div>
  <footer>
    <div class="box">
      <div id="pending"></div>
      <div class="in">
        <button id="attach" class="icon" aria-label="附件"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg></button>
        <input type="file" id="fileIn" multiple hidden>
        <textarea id="text" rows="1" placeholder="问 Kavi"></textarea>
        <button id="send" aria-label="发送" disabled><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5M5 12l7-7 7 7"/></svg></button>
      </div>
    </div>
  </footer>
</div>
<div id="drawer"><div class="shade"></div><nav>
  <div class="navhead"><div class="logo">K</div><b>Kavi</b></div>
  <button class="newbtn" onclick="pick('')"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>新对话</button>
  <div id="convs"></div>
</nav></div>
<script>
const $ = (id) => document.getElementById(id);
let conv = localStorage.getItem("conv") || "", since = 0, msgs = new Map(), confirms = [], convs = [], files = [], timer = null, busy = false, device = null, openSteps = new Set(), shown = new Set();
// 只有第一次出现的消息、卡片才淡入：每几秒刷新一次整页重画，不能每次都重播动画
const fresh = (k) => shown.has(k) ? "" : (shown.add(k), " fresh");
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
// 和 App 空白页一样的四张建议卡片
const SUGGEST = [
  ["今天有什么新闻", "今天有什么值得关注的新闻？", "#3b82f6", '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>'],
  ["帮我记一件事", "提醒我", "#ef5a5a", '<rect x="4" y="5" width="16" height="15" rx="2"/><path d="M8 3v4M16 3v4M4 10h16"/>'],
  ["读个网页给我听", "帮我打开这个网页，读完告诉我重点：", "#8b5cf6", '<path d="M11 5 6 9H3v6h3l5 4z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13"/>'],
  ["整理成表格", "帮我把下面的内容整理成表格：\\n", "#22a06b", '<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M4 10h16M10 4v16"/>'],
];
function greeting() {
  const h = new Date().getHours();
  return h >= 5 && h <= 10 ? "早上好" : h <= 12 && h > 10 ? "中午好" : h >= 13 && h <= 17 ? "下午好" : h >= 18 && h <= 22 ? "晚上好" : "夜深了";
}
function inline(s) {
  return s.replace(/\`([^\`]+)\`/g, "<code>$1</code>")
    .replace(/\\*\\*([^*]+)\\*\\*/g, "<b>$1</b>")
    .replace(/(https?:\\/\\/[^\\s<]+)/g, '<a href="$1" target="_blank" rel="noopener noreferrer">$1</a>');
}
function md(src) {
  // 先转义再排版：Kavi 写的字不会变成网页上能跑的东西
  return String(src).split(/\`\`\`/).map((b, i) => {
    if (i % 2) return "<pre><code>" + esc(b.replace(/^[\\w-]*\\n/, "")) + "</code></pre>";
    return esc(b).split(/\\n{2,}/).map((p) => {
      const lines = p.split("\\n");
      if (lines.every((l) => /^\\s*[-*•] /.test(l))) return "<ul>" + lines.map((l) => "<li>" + inline(l.replace(/^\\s*[-*•] /, "")) + "</li>").join("") + "</ul>";
      if (lines.every((l) => /^\\s*\\d+[.、)] /.test(l))) return "<ol>" + lines.map((l) => "<li>" + inline(l.replace(/^\\s*\\d+[.、)] /, "")) + "</li>").join("") + "</ol>";
      if (lines.length === 1 && /^#{1,6} /.test(p)) return "<h3>" + inline(p.replace(/^#{1,6} /, "")) + "</h3>";
      return "<p>" + inline(p).replace(/^#{1,6} (.+)$/gm, "<b>$1</b>").replace(/\\n/g, "<br>") + "</p>";
    }).join("");
  }).join("");
}
async function api(path, opt = {}) {
  const r = await fetch(path, { credentials: "same-origin", ...opt });
  if (r.status === 401) { location.replace("/pair"); throw new Error("401"); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || r.status);
  return j;
}
const size = (n) => !n ? "" : n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(0) + " KB" : (n / 1048576).toFixed(1) + " MB";
const ext = (name) => ((String(name).match(/\\.([a-z0-9]{1,4})$/i) || [])[1] || "文件").toUpperCase();
function fileLinks(fs) {
  if (!fs || !fs.length) return "";
  return '<div class="files">' + fs.map((f) => {
    const u = "/api/file?key=" + encodeURIComponent(f.key);
    return /^image\\//.test(f.type || "") || /\\.(png|jpe?g|gif|webp)$/i.test(f.name) ? '<a href="' + u + '" target="_blank"><img src="' + u + '" alt="' + esc(f.name) + '" loading="lazy"></a>'
      : '<a class="file" href="' + u + '" target="_blank"><div class="fi">' + esc(ext(f.name)) + '</div><span>' + esc(f.name) + (f.size ? " · " + size(f.size) : "") + "</span></a>";
  }).join("") + "</div>";
}
function steps(m, running) {
  if (!m.steps || !m.steps.length) return "";
  const open = running || openSteps.has(m.id);
  return '<details class="steps"' + (open ? " open" : "") + '><summary onclick="toggleSteps(' + m.id + ',!this.parentNode.open)">' + (running ? '<span class="spin"></span>' : "") +
    (running ? esc(m.steps[m.steps.length - 1]) : "做了 " + m.steps.length + " 步") + "</summary><ol>" + m.steps.map((s) => "<li>" + esc(s) + "</li>").join("") + "</ol></details>";
}
window.toggleSteps = (id, open) => { open ? openSteps.add(id) : openSteps.delete(id); };
function emptyPage() {
  const off = device && !device.online;
  return '<div class="empty"><div class="logo">K</div><h2>' + greeting() + '</h2><p class="sub">想聊点什么？</p>' +
    (off ? '<div class="offline">手机上的 Kavi 现在没连着' + (device.lastSeen ? "（" + ago(Date.now() - device.lastSeen) + "还在）" : "") + '。消息照样能发，它连上后就会回。</div>' : "") +
    '<div class="cards">' + SUGGEST.map((s, i) => '<button class="card" onclick="suggest(' + i + ')"><div class="ci" style="background:' + s[2] + '22;color:' + s[2] +
      '"><svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + s[3] + "</svg></div><b>" + s[0] + "</b></button>").join("") + "</div></div>";
}
window.suggest = (i) => { ta.value = SUGGEST[i][1]; ta.oninput(); ta.focus(); };
function render() {
  const list = $("list");
  const atBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 80;
  const sorted = [...msgs.values()].sort((a, b) => a.id - b.id);
  const replies = new Map(sorted.filter((m) => m.role === "assistant").map((m) => [m.reply_to, m]));
  let html = "";
  busy = false;
  for (const m of sorted) {
    if (m.role !== "user") continue;
    html += '<div class="msg user' + fresh("u" + m.id) + '">' + (m.text ? '<div class="b">' + esc(m.text) + "</div>" : "") + fileLinks(m.files) + "</div>";
    const r = replies.get(m.id);
    const stopBtn = '<button class="chip" onclick="stopIt(' + m.id + ')">停止</button>';
    if (!r) {
      busy = true;
      html += '<div class="msg bot' + fresh("w" + m.id) + '"><div class="meta"><span class="typing"><i></i><i></i><i></i></span>' +
        (m.state === "pending" ? (device && !device.online ? "等手机上的 Kavi 连上…" : "等手机上的 Kavi 来取…") : "在想…") + stopBtn + "</div></div>";
      continue;
    }
    const running = r.state === "running";
    if (running) busy = true;
    html += '<div class="msg bot' + fresh("r" + m.id) + '">' + steps(r, running) + '<div class="b">' + md(r.text || "") + "</div>" + fileLinks(r.files) +
      (r.state === "error" ? '<div class="err">出错了，没答完</div>' : "") +
      (running ? '<div class="meta"><span class="typing"><i></i><i></i><i></i></span>' + stopBtn + "</div>" : "") + "</div>";
  }
  for (const c of confirms) {
    html += '<div class="confirm' + fresh("c" + c.id) + '"><h3>' + esc(c.title) + "</h3><p>" + esc(c.body) + '</p><div class="row"><button class="btn" onclick="answer(\\'' + c.id + '\\',false)">不要</button><button class="btn main" onclick="answer(\\'' + c.id + '\\',true)">' + esc(c.action) + "</button></div></div>";
  }
  if (!sorted.length) html = emptyPage();
  list.innerHTML = html;
  if (atBottom || !sorted.length) list.scrollTop = list.scrollHeight;
  const c = convs.find((x) => x.id === conv);
  $("title").textContent = c ? c.title : "Kavi";
  $("convs").innerHTML = (convs.length ? '<div class="label">最近</div>' : "") + convs.map((x) =>
    '<div class="conv' + (x.id === conv ? " cur" : "") + '"><button onclick="pick(\\'' + x.id + '\\')">' + esc(x.title) + '</button><button class="del" aria-label="删除" onclick="del(\\'' + x.id + '\\')">' +
    '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/></svg></button></div>').join("");
}
async function refresh() {
  clearTimeout(timer);
  try {
    const s = await api("/api/state?conv=" + encodeURIComponent(conv) + "&since=" + since);
    for (const m of s.msgs) { msgs.set(m.id, m); since = Math.max(since, m.updated); }
    confirms = s.confirms; convs = s.convs;
    const d = s.device;
    device = { online: d.online, lastSeen: d.lastSeen ? Date.now() - (s.now - d.lastSeen) : 0 };
    $("status").innerHTML = '<span class="dot ' + (d.online ? "on" : "") + '"></span>' + (d.online ? "在线" : d.lastSeen ? "离线 · " + ago(s.now - d.lastSeen) : "没连上");
    render();
  } catch (e) { if (e.message === "401") return; }
  timer = setTimeout(refresh, busy || confirms.length ? 1500 : 4000);
}
const ago = (ms) => ms < 60e3 ? "刚刚" : ms < 3600e3 ? Math.round(ms / 60e3) + " 分钟前" : ms < 86400e3 ? Math.round(ms / 3600e3) + " 小时前" : Math.round(ms / 86400e3) + " 天前";
window.pick = (id) => { conv = id; localStorage.setItem("conv", id); since = 0; msgs.clear(); confirms = []; openSteps.clear(); shown.clear(); $("drawer").classList.remove("open"); render(); refresh(); };
window.del = async (id) => {
  if (!confirm("删掉这个对话？网页上的记录和文件会一起删掉（手机上 Kavi 里的不受影响）。")) return;
  await api("/api/delete", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ conv: id }) });
  convs = convs.filter((c) => c.id !== id);
  if (id === conv) pick(""); else render();
};
window.answer = async (id, ok) => { await api("/api/confirm", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ id, ok }) }); confirms = confirms.filter((c) => c.id !== id); render(); refresh(); };
window.stopIt = async (id) => { await api("/api/stop", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ id }) }); };
$("menu").onclick = () => $("drawer").classList.add("open");
$("drawer").onclick = (e) => { if (e.target.classList.contains("shade")) $("drawer").classList.remove("open"); };
$("new").onclick = () => pick("");
$("attach").onclick = () => $("fileIn").click();
const ta = $("text");
function canSend() { $("send").disabled = !ta.value.trim() && !files.length; }
function pendingChips() {
  $("pending").innerHTML = files.map((f, i) => "<span>" + esc(f.uploading ? "上传中 " + f.name : f.name) + (f.uploading ? "" : '<button aria-label="去掉" onclick="unattach(' + i + ')">×</button>') + "</span>").join("");
  canSend();
}
window.unattach = (i) => { files.splice(i, 1); pendingChips(); };
$("fileIn").onchange = async (e) => {
  for (const f of e.target.files) {
    const slot = { name: f.name, uploading: true };
    files.push(slot); pendingChips();
    try {
      const r = await api("/api/upload?name=" + encodeURIComponent(f.name) + "&type=" + encodeURIComponent(f.type || ""), { method: "POST", body: f });
      Object.assign(slot, r, { uploading: false });
    } catch (err) { files.splice(files.indexOf(slot), 1); alert("没传上 " + f.name + "：" + err.message); }
    pendingChips();
  }
  e.target.value = "";
};
ta.oninput = () => { ta.style.height = "auto"; ta.style.height = Math.min(ta.scrollHeight, 180) + "px"; canSend(); };
// 电脑上回车直接发，Shift+回车换行；手机上回车照常换行
ta.onkeydown = (e) => { if (e.key === "Enter" && !e.shiftKey && !e.isComposing && matchMedia("(hover: hover)").matches) { e.preventDefault(); $("send").click(); } };
$("send").onclick = async () => {
  const text = ta.value.trim();
  if ((!text && !files.length) || files.some((f) => f.uploading)) return;
  $("send").disabled = true;
  try {
    const sendFiles = files.map(({ key, name, size, type }) => ({ key, name, size, type }));
    const r = await api("/api/send", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ conv, text, files: sendFiles }) });
    if (!conv) { conv = r.conv; localStorage.setItem("conv", conv); }
    ta.value = ""; files = []; ta.oninput(); pendingChips();
    $("list").scrollTop = $("list").scrollHeight;
    refresh();
  } catch (e) { alert("没发出去：" + e.message); }
  canSend();
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
 }catch(error){
  message.classList.add("error");message.textContent=error.message;button.disabled=false;
  // 扫码配对没成：把输码的框露出来，可以改用 6 位码
  document.querySelector('label[for="code"]').hidden=false; $("code").hidden=false;
 }
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
