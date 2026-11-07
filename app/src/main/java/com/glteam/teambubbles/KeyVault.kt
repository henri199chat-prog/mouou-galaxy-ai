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
 * An API key, encrypted with AES-GCM by a key that never leaves the Android Keystore (Gemini: alias
 * `tb_gemini`, spec §10.8; Claude since 2.6.0: `tb_claude`). Only the ciphertext is in private prefs;
 * allowBackup=false keeps it off backups.
 */
class KeyVault(context: Context, private val alias: String = GEMINI_ALIAS, private val pref: String = GEMINI_PREF) {
    private val prefs = context.getSharedPreferences("brain", Context.MODE_PRIVATE)

    fun save(rawKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(ClaudeKey.clean(rawKey).toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(pref, stored).apply()
    }

    fun load(): String? {
        val stored = prefs.getString(pref, null) ?: return null
        return runCatching {
            val (iv, ct) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    fun has() = prefs.contains(pref)

    fun clear() = prefs.edit().remove(pref).apply()

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        const val GEMINI_ALIAS = "tb_gemini"
        const val GEMINI_PREF = "gemini_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Claude's key: its own Keystore key and pref, so clearing one never touches the other. */
        fun claude(context: Context) = KeyVault(context, "tb_claude", "claude_key")
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

    /** Spec §16: "always-attach-screen (ON)": every question carries the screen when the service is on. */
    var attachScreen: Boolean
        get() = prefs.getBoolean("attach_screen", true)
        set(v) = prefs.edit().putBoolean("attach_screen", v).apply()

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
