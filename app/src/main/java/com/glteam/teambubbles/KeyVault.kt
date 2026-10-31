package com.glteam.teambubbles

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Gemini key, encrypted with AES-GCM by a key that never leaves the Android Keystore (alias
 * `tb_gemini`, spec §10.8). Only the ciphertext is in private prefs; allowBackup=false keeps it off backups.
 */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("brain", Context.MODE_PRIVATE)

    fun save(rawKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(KeySanitizer.clean(rawKey).toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(PREF, stored).apply()
    }

    fun load(): String? {
        val stored = prefs.getString(PREF, null) ?: return null
        return runCatching {
            val (iv, ct) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    fun has() = prefs.contains(PREF)

    fun clear() = prefs.edit().remove(PREF).apply()

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "tb_gemini"
        const val PREF = "gemini_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Brain settings: the model list from the last key test, Google search, and the facts about Ayman. */
class BrainSettings(context: Context) {
    private val prefs = context.getSharedPreferences("brain", Context.MODE_PRIVATE)

    var models: List<String>
        get() = prefs.getString("models", null)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        set(v) = prefs.edit().putString("models", v.joinToString(",")).apply()

    var useSearch: Boolean
        get() = prefs.getBoolean("search", true)
        set(v) = prefs.edit().putBoolean("search", v).apply()

    fun facts(): Facts = Facts.fromJson(prefs.getString("facts", null))

    fun saveFacts(f: Facts) = prefs.edit().putString("facts", f.toJson()).apply()
}

/** Last 200 lines of brain activity, shown in settings (spec §10.8 debug log). Never contains the key. */
object DebugLog {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.addLast("$t $line")
        while (lines.size > 200) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
