package app.show.tv.vod

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A tiny key/value store for secrets such as OAuth tokens (1.8.0). */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * Secrets encrypted with an AES-256-GCM key that lives in the Android Keystore (hardware-backed where the
 * device has it), stored as Base64 in app-private [SharedPreferences] that are excluded from backups.
 *
 * Built on the platform Keystore rather than androidx.security-crypto (deprecated upstream in 2025), so no
 * new dependency. Where the Keystore is unavailable (Robolectric, a broken OEM keystore) it falls back to
 * the existing app-private prefs pattern, marked "p:", and upgrades to encrypted storage on the next write
 * once the Keystore works.
 */
class KeystoreSecretStore(private val prefs: SharedPreferences, private val alias: String = "show_vod_secrets") : SecretStore {
    private val key: SecretKey? by lazy { runCatching { loadOrCreateKey() }.getOrNull() }

    /** True when values are actually encrypted (false under Robolectric). */
    val encrypted: Boolean get() = key != null

    override fun get(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return when {
            raw.startsWith("p:") -> raw.substring(2)
            raw.startsWith("e1:") -> runCatching { decrypt(raw.substring(3)) }.getOrNull()
            else -> null
        }
    }

    override fun put(key: String, value: String?) {
        if (value == null) { prefs.edit().remove(key).apply(); return }
        val stored = this.key?.let { runCatching { "e1:" + encrypt(it, value) }.getOrNull() } ?: "p:$value"
        prefs.edit().putString(key, stored).apply()
    }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun encrypt(k: SecretKey, plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(b64: String): String {
        val k = key ?: error("no key")
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, all, 0, 12)) }
        return String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }
}

/** In-memory store for tests. */
class MemorySecretStore : SecretStore {
    private val m = mutableMapOf<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
}
