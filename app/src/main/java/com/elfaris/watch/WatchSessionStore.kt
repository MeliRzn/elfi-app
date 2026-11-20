package com.elfaris.watch

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class WatchSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("elfaris_watch_secure", Context.MODE_PRIVATE)

    val server: String
        get() = preferences.getString(KEY_SERVER, "").orEmpty()

    val microphoneEnabled: Boolean
        get() = preferences.getBoolean(KEY_MICROPHONE, false)

    fun saveMicrophoneEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_MICROPHONE, enabled).apply()
    }

    val token: String
        get() {
            val stored = preferences.getString(KEY_TOKEN, "").orEmpty()
            if (stored.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return stored
            return try {
                decrypt(stored)
            } catch (_: Exception) {
                preferences.edit().remove(KEY_TOKEN).apply()
                ""
            }
        }

    fun saveServer(value: String) {
        preferences.edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply()
    }

    fun saveSession(server: String, token: String) {
        val storedToken = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) encrypt(token) else token
        preferences.edit()
            .putString(KEY_SERVER, server.trim().trimEnd('/'))
            .putString(KEY_TOKEN, storedToken)
            .apply()
    }

    fun clearSession() {
        preferences.edit().remove(KEY_TOKEN).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "Sessão local inválida." }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_SERVER = "server"
        private const val KEY_TOKEN = "session_token"
        private const val KEY_MICROPHONE = "microphone_enabled"
        private const val KEY_ALIAS = "elfaris_watch_session_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
