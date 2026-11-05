package com.jack.ai.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设置里的 Key、令牌、邮箱授权码、代理节点：存进手机前用安卓密钥库里的一把 AES 钥匙加密，钥匙本身拿不出这台手机。
 * 手机被 root、App 数据被整个拷走，看到的也只是密文。思路来自 Mika（Key 和记忆加密存在手机上），Apache-2.0。
 *
 * 加密过的写成「kv1:…」；读到不带这个前缀的就是旧版本存的明文，原样用，下次保存时加密。
 * 密钥库用不了（个别系统、单元测试）就照旧存明文，不能因此存不上。
 */
object Secrets {
    private const val PREFIX = "kv1:"
    private const val ALIAS = "kavi_settings"
    // 读设置很频繁：解过的记在内存里，免得每次都过一遍密钥库
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return plain
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val out = c.iv + c.doFinal(plain.toByteArray())
            (PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)).also { if (cache.size < 64) cache[it] = plain }
        }.getOrDefault(plain)
    }

    /** 解不开（钥匙没了，比如系统清掉了密钥库）就当没填，请用户重新填，不把密文当 Key 发出去。 */
    fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        cache[stored]?.let { return it }
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
            String(c.doFinal(raw, 12, raw.size - 12)).also { if (cache.size < 64) cache[stored] = it }
        }.getOrDefault("")
    }
}
