package com.jack.ai.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * 设置里的密钥（API Key、邮箱授权码、代理节点密码等）存进 SharedPreferences 之前先加密：
 * 密钥存在系统 KeyStore 里（跟设备走，换手机、恢复出厂就没了——和现在“换手机重新填 Key”的规矩一致），
 * 算法 AES/GCM。存的格式是 v1: + Base64(iv + 密文)，没这个前缀的当明文读（老版本升上来不用重填）。
 * 加解密失败一律返回 null，调用方按明文/空值处理，绝不因此崩溃、也绝不丢设置。
 */
object Secrets {
    private const val ALIAS = "kavi-secrets"
    private const val PREFIX = "v1:"
    private val lock = Any()

    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.getOrNull()
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return stored
        if (!stored.startsWith(PREFIX)) return stored // 老版本存的明文，照常用，下次保存时转加密
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.DEFAULT)
            if (raw.size <= 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): java.security.Key = synchronized(lock) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(ALIAS, null)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        gen.generateKey()
    }
}
