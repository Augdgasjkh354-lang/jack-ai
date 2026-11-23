package com.jack.ai.data.integrations

import android.content.Context
import android.content.SharedPreferences
import com.jack.ai.api.ApiException
import com.jack.ai.data.Secrets
import com.wangzan101.mika.core.oauth.LoopbackCallback
import com.wangzan101.mika.core.oauth.Pkce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/**
 * 用 ChatGPT 账号登录（OpenAI 的 Sign in with ChatGPT，开源、本机运行的 App 用）：花的是用户 ChatGPT 套餐
 * （Go / Plus / Pro）里的额度，不用 API Key。文档：developers.openai.com/siwc/token-sharing-open-source。
 *
 * 在浏览器（默认是 Kavi 浏览器，走 Kavi 专用代理；设置里关了就是系统浏览器）里打开 OpenAI 自己的登录页，用户在那里输账号密码、点同意，浏览器跳回本机 127.0.0.1 的回调，
 * 这里换到令牌。Kavi 碰不到密码；令牌只能花额度，看不到 ChatGPT 里的聊天和记忆。
 *
 * 令牌单独存（不放进设置：续令牌时旧的会作废，跟着设置来回存容易拿旧的去续，账号就被踢下线），
 * 加密存在手机上（[Secrets]），不进备份。只有一个账号。
 */
object ChatGptAuth {
    /** 请求发到这里（Responses 接口）。 */
    const val BASE_URL = "https://api.openai.com/v1"
    /** 用量和每个 App 的每周上限在这里看、改。 */
    const val USAGE_URL = "https://chatgpt.com/settings/usage"
    /** 断开 Kavi：设置 → 安全与登录 → 登录连接。 */
    const val CONNECTIONS_URL = "https://chatgpt.com/#settings/Security"

    private const val AUTHORIZE = "https://auth.openai.com/api/accounts/authorize"
    private const val TOKEN = "https://auth.openai.com/api/accounts/oauth/token"
    private const val DISCOVERY = "https://auth.openai.com/.well-known/openid-configuration"
    private const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    /** 有这个才能花套餐额度；只登录、没给这个的不算数。 */
    private const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    /** 第一次注册时用的 client_id，注册后换成 OpenAI 发的（oaiapp_…），以后一直用那个。 */
    private const val DYNAMIC_CLIENT = "dynamic_agent_client"
    private const val APP_NAME = "Kavi"
    /** 文档里第一次注册用的端口；占用了就让系统挑一个（以后登录只有端口能变）。 */
    private const val PORT = 1455

    /** 续了就作废：这几个错误表示得重新登录。 */
    private val TERMINAL = setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
        "refresh_token_invalidated", "refresh_token_reused")

    @Serializable
    data class Account(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String = "",
        /** 访问令牌到什么时候过期（毫秒）。 */
        val expiresAt: Long = 0,
        val email: String = "",
        val name: String = "",
        /** 账号的唯一标识（ID 令牌里的 sub）。 */
        val sub: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var cached: Account? = null

    /** App 启动时接上（存令牌要用）。 */
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("chatgpt_auth", Context.MODE_PRIVATE)
        prefs = p
        cached = p.getString("account", null)?.let { raw ->
            runCatching { json.decodeFromString(Account.serializer(), Secrets.open(raw)) }.getOrNull()
        }
    }

    /** 当前登录的账号；没登录是 null。 */
    val account: Account? get() = cached

    private fun save(a: Account?) {
        cached = a
        prefs?.edit()?.apply {
            if (a == null) remove("account") else putString("account", Secrets.seal(json.encodeToString(Account.serializer(), a)))
        }?.commit()
    }

    private fun clientId(): String? = prefs?.getString("client_id", null)?.takeIf { it.isNotBlank() }

    /** 这台手机的标识（随机的，不含账号信息），每台设备一个、一直不变。 */
    private fun hostId(): String {
        prefs?.getString("host_id", null)?.let { return it }
        val id = "urn:uuid:" + java.util.UUID.randomUUID()
        prefs?.edit()?.putString("host_id", id)?.commit()
        return id
    }

    private val http get() = com.jack.ai.api.sharedHttp

    /**
     * 登录一次：[open] 打开 OpenAI 的登录页（系统浏览器），用户同意后浏览器跳回本机，这里换到令牌、存好。最多等 5 分钟。
     */
    suspend fun signIn(open: (String) -> Unit): Account {
        val cb = runCatching { LoopbackCallback.open(PORT) }.getOrElse { LoopbackCallback.open() }
        // 在 Kavi 浏览器里登录时，跳回本机的回调要放行（平时本机地址一律拦下）
        com.jack.ai.api.BrowserNet.loginCallbackPort = cb.port
        try { cb.use {
            val redirect = cb.redirectUri
            val verifier = Pkce.verifier()
            val state = Pkce.state()
            val nonce = Pkce.state()
            val saved = clientId()
            val previous = cached
            val url = AUTHORIZE.toHttpUrl().newBuilder().apply {
                addQueryParameter("client_id", saved ?: DYNAMIC_CLIENT)
                if (saved == null) addQueryParameter("agent_name_hint", APP_NAME)
                else previous?.idToken?.takeIf { it.isNotBlank() }?.let { addQueryParameter("id_token_hint", it) }
                addQueryParameter("ext_agent_host_id", hostId())
                addQueryParameter("response_type", "code")
                addQueryParameter("redirect_uri", redirect)
                addQueryParameter("scope", SCOPE)
                addQueryParameter("resource", BASE_URL)
                addQueryParameter("state", state)
                addQueryParameter("nonce", nonce)
                addQueryParameter("code_challenge_method", "S256")
                addQueryParameter("code_challenge", Pkce.challenge(verifier))
            }.build().toString()
            open(url)
            var result: Account? = null
            var failure: Exception? = null
            cb.await(5 * 60_000L, accept = { it["state"] == state }) { params ->
                try {
                    params["error"]?.let { err ->
                        throw ApiException(
                            if (err == "access_denied") "没拿到使用套餐的许可：可能点了拒绝，或者这个账号不能把额度给其他 App 用（要 Go、Plus 或 Pro）。"
                            else "登录没成功：" + (params["error_description"] ?: err),
                            403, err,
                        )
                    }
                    val code = params["code"] ?: throw ApiException("登录回来没带授权码")
                    val issued = params["client_id"]?.takeIf { it.isNotBlank() }
                    if (saved != null && issued != null && issued != saved) {
                        // 不是这台手机注册的那个：不认，下次重新注册
                        prefs?.edit()?.remove("client_id")?.commit()
                        throw ApiException("登录回来的应用编号对不上，再登录一次试试")
                    }
                    val client = saved ?: issued ?: throw ApiException("登录回来没带应用编号（client_id）")
                    if (saved == null) prefs?.edit()?.putString("client_id", client)?.commit()
                    val form = FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("client_id", client)
                        .add("code", code)
                        .add("code_verifier", verifier)
                        .add("redirect_uri", redirect)
                        .add("resource", BASE_URL)
                        .build()
                    result = withContext(Dispatchers.IO) { tokenRequest(form, null, client, nonce) }
                    PAGE_OK
                } catch (e: Exception) {
                    failure = e
                    PAGE_FAIL
                }
            } ?: throw IOException("等了 5 分钟没等到登录完成")
            failure?.let { throw it }
            return result!!.also { save(it) }
        } } finally {
            if (com.jack.ai.api.BrowserNet.loginCallbackPort == cb.port) com.jack.ai.api.BrowserNet.loginCallbackPort = 0
        }
    }

    /** 能用的访问令牌：快过期了先续（一次只续一个，续过旧的就作废）。 */
    suspend fun accessToken(): String = mutex.withLock {
        val a = cached ?: throw ApiException("还没用 ChatGPT 登录：到 设置 → 模型服务 里登录", 401, "chatgpt_signed_out")
        if (a.expiresAt - System.currentTimeMillis() > 2 * 60_000L) return a.accessToken
        val client = clientId() ?: throw signedOut()
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", client)
            .add("refresh_token", a.refreshToken)
            .add("resource", BASE_URL)
            .build()
        val next = withContext(Dispatchers.IO) { tokenRequest(form, a, client, null) }
        save(next)
        next.accessToken
    }

    /** 服务说令牌不认（401）：下次用之前先续一下。 */
    fun invalidate() {
        cached?.let { cached = it.copy(expiresAt = 0) }
    }

    /** 退出登录：通知 OpenAI 作废令牌（连不上也照样在手机上删掉）。应用编号和设备标识留着，下次登录不用重新注册。 */
    suspend fun signOut() {
        val a = cached
        save(null)
        val client = clientId() ?: return
        if (a == null) return
        runCatching {
            withContext(Dispatchers.IO) {
                val endpoint = http.newCall(Request.Builder().url(DISCOVERY).get().build()).execute().use { r ->
                    json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["revocation_endpoint"].string()
                }.takeIf { it.startsWith("https://") } ?: return@withContext
                val form = FormBody.Builder().add("token", a.refreshToken).add("token_type_hint", "refresh_token").add("client_id", client).build()
                http.newCall(Request.Builder().url(endpoint).post(form).build()).execute().close()
            }
        }
    }

    private fun signedOut() = ApiException("ChatGPT 登录失效了，到 设置 → 模型服务 里重新登录", 401, "chatgpt_signed_out")

    /** 换令牌、续令牌都走这里。[nonce] 不为空时核对 ID 令牌是这次登录发的。 */
    private fun tokenRequest(form: FormBody, previous: Account?, client: String, nonce: String?): Account {
        val req = Request.Builder().url(TOKEN).post(form).header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (!r.isSuccessful) {
                val err = o?.get("error").string().ifBlank { (o?.get("error") as? JsonObject)?.get("code").string() }
                if (previous != null && err in TERMINAL) { save(null); throw signedOut() }
                val msg = o?.get("error_description").string().ifBlank { err.ifBlank { text.take(200) } }
                throw ApiException("ChatGPT 登录服务返回错误（${r.code}）：$msg", r.code, err)
            }
            o ?: throw ApiException("ChatGPT 登录服务返回的内容看不懂")
            val scope = o["scope"].string()
            if (scope.isNotBlank() && PLAN_SCOPE !in scope.split(' ')) {
                throw ApiException("登录成功了，但没拿到使用套餐额度的许可（这个账号可能不支持，要 Go、Plus 或 Pro）", 403, "no_plan_scope")
            }
            val idToken = o["id_token"].string().ifBlank { previous?.idToken.orEmpty() }
            val claims = claims(idToken)
            if (nonce != null) {
                if (claims == null) throw ApiException("登录回来没带身份信息（ID 令牌）")
                if (claims["nonce"].string() != nonce) throw ApiException("身份信息对不上（nonce），不是这次登录发的，没有保存")
                val aud = claims["aud"]
                val audOk = when (aud) {
                    is JsonArray -> aud.any { it.string() == client }
                    else -> aud.string() == client
                }
                if (!audOk) throw ApiException("身份信息不是发给 Kavi 的（aud 对不上），没有保存")
                if (claims["iss"].string().trimEnd('/') != "https://auth.openai.com") throw ApiException("身份信息不是 OpenAI 发的，没有保存")
            }
            val expiresIn = (o["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3600
            return Account(
                accessToken = o["access_token"].string().ifBlank { throw ApiException("登录服务没给访问令牌") },
                refreshToken = o["refresh_token"].string().ifBlank { previous?.refreshToken ?: throw ApiException("登录服务没给续期令牌") },
                idToken = idToken,
                expiresAt = System.currentTimeMillis() + expiresIn * 1000,
                email = claims?.get("email").string().ifBlank { previous?.email.orEmpty() },
                name = claims?.get("name").string().ifBlank { previous?.name.orEmpty() },
                sub = claims?.get("sub").string().ifBlank { previous?.sub.orEmpty() },
            )
        }
    }

    /**
     * 读 JWT 的内容（不验签名：令牌是刚从 auth.openai.com 走 HTTPS 直接拿到的，没经过别人的手；
     * 防的是串了别的登录，核对 nonce、aud、iss 就够）。
     */
    internal fun claims(jwt: String): JsonObject? = runCatching {
        val part = jwt.split('.')[1]
        val bytes = java.util.Base64.getUrlDecoder().decode(part.trimEnd('='))
        json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as JsonObject
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement?.string(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private const val PAGE_OK = "<html><meta charset=utf-8><meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:40px'>" +
        "<h2>已用 ChatGPT 登录</h2><p>回到 Kavi 就能用了，这个页面可以关掉。</p>" +
        "<p><a href='intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=com.jack.ai;end'>回到 Kavi</a></p></body></html>"
    private const val PAGE_FAIL = "<html><meta charset=utf-8><meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:40px'>" +
        "<h2>没登录成功</h2><p>回到 Kavi 看看原因，再试一次。</p>" +
        "<p><a href='intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=com.jack.ai;end'>回到 Kavi</a></p></body></html>"
}
