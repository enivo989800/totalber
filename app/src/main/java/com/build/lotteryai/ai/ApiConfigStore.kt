package com.build.lotteryai.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("local_ai_config", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    fun endpoint(): String = preferences.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
    fun model(): String = preferences.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun apiMode(): String = preferences.getString(KEY_API_MODE, MODE_CHAT_COMPLETIONS) ?: MODE_CHAT_COMPLETIONS
    fun hasApiKey(): Boolean = preferences.getString(KEY_API_KEY_ENCRYPTED, null) != null

    fun saveConfiguration(endpoint: String, model: String, apiKey: String, apiMode: String = this.apiMode()): Result<Unit> = runCatching {
        val normalizedEndpoint = endpoint.trim()
        val normalizedModel = model.trim()
        require(normalizedEndpoint.startsWith("https://", ignoreCase = true)) { "API 地址必须使用 HTTPS" }
        require(normalizedModel.isNotBlank()) { "模型 ID 不能为空" }
        require(normalizedEndpoint.length <= 500) { "API 地址过长" }
        require(apiKey.length <= 4096) { "API Key 长度异常" }
        require(apiMode in setOf(MODE_CHAT_COMPLETIONS, MODE_RESPONSES)) { "API 模式不受支持" }
        val parsedEndpoint = runCatching { java.net.URI(normalizedEndpoint) }.getOrElse { throw IllegalArgumentException("API 地址格式不正确") }
        require(parsedEndpoint.scheme.equals("https", ignoreCase = true) && !parsedEndpoint.host.isNullOrBlank()) { "API 地址必须是有效的 HTTPS URL" }
        require(parsedEndpoint.userInfo == null) { "API 地址中不能嵌入用户名或密码" }
        val editor = preferences.edit()
            .putString(KEY_ENDPOINT, normalizedEndpoint)
            .putString(KEY_MODEL, normalizedModel)
            .putString(KEY_API_MODE, apiMode)
        if (apiKey.isNotBlank()) {
            editor.putString(KEY_API_KEY_ENCRYPTED, encrypt(apiKey.trim()))
        }
        check(editor.commit()) { "本地设置写入失败" }
    }

    fun clearApiKey() {
        preferences.edit().remove(KEY_API_KEY_ENCRYPTED).commit()
    }

    fun getApiKey(): String {
        val encrypted = preferences.getString(KEY_API_KEY_ENCRYPTED, null) ?: return ""
        return decrypt(encrypted)
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2) { "本地 API Key 存储格式异常，请重新保存" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "lottery_ai_api_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_MODEL = "model"
        private const val KEY_API_MODE = "api_mode"
        const val MODE_CHAT_COMPLETIONS = "CHAT_COMPLETIONS"
        const val MODE_RESPONSES = "RESPONSES"
        private const val KEY_API_KEY_ENCRYPTED = "api_key_encrypted"
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_MODEL = "gpt-4.1-mini"
    }
}
