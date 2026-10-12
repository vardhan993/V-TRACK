package com.vtrack.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small encrypted key/value store for credentials (access token, refresh token, user id).
 *
 * Design notes:
 *  - The AES-256-GCM key lives in the Android Keystore and never leaves it; only
 *    ciphertext is written to disk (no keys, no plaintext tokens in files or logs).
 *  - The blob is excluded from cloud backup and device transfer (see the XML rules), and
 *    if the Keystore key is invalidated (factory reset, lock-screen change removing the
 *    key) the store self-heals by wiping rather than crashing.
 *  - Deliberately not `EncryptedSharedPreferences` (alpha-only dependency); this is a
 *    ~150 line, dependency-free equivalent with the same guarantee.
 */
@Singleton
class SecureStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val storeFile = File(File(context.filesDir, "vtrack_secure").apply { mkdirs() }, "secure.bin")

    private val cache: MutableMap<String, String> = linkedMapOf()

    init {
        load()
    }

    @Synchronized
    fun getString(key: String): String? = cache[key]

    @Synchronized
    fun putString(key: String, value: String) {
        cache[key] = value
        persist()
    }

    @Synchronized
    fun remove(key: String) {
        cache.remove(key)
        persist()
    }

    @Synchronized
    fun clear() {
        cache.clear()
        persist()
    }

    fun hasSession(): Boolean = getString(KEY_REFRESH_TOKEN) != null

    /* ---------------- internals ---------------- */

    private fun load() {
        if (!storeFile.exists()) return
        try {
            val payload = decrypt(storeFile.readText())
            val decoded = json.decodeFromString(mapSerializer, payload)
            cache.clear()
            cache.putAll(decoded)
        } catch (t: Throwable) {
            // Key invalidated or file corrupt: drop the data instead of crashing forever.
            Log.w(TAG, "Secure store unreadable, resetting: ${t.javaClass.simpleName}")
            runCatching { storeFile.delete() }
            cache.clear()
        }
    }

    private fun persist() {
        try {
            if (cache.isEmpty()) {
                if (storeFile.exists()) storeFile.delete()
                return
            }
            storeFile.writeText(encrypt(json.encodeToString(mapSerializer, cache)))
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist secure store", t)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split(":")
        require(parts.size == 2) { "Malformed secure payload" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "SecureStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "vtrack_session_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_EMAIL = "user_email"
        const val KEY_USER_NAME = "user_display_name"
    }
}
