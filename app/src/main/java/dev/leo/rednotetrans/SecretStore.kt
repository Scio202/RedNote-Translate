package dev.leo.rednotetrans

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
 * The API key, encrypted at rest with a hardware-backed AES key from the Android Keystore.
 * The ciphertext lives in SharedPreferences; the key itself never leaves the Keystore and is
 * not extractable, so a copy of the prefs file on its own is useless.
 *
 * Deliberately readable without authentication: the accessibility service translates in the
 * background and cannot put a fingerprint prompt in the user's way. Authentication gates
 * *viewing and changing* the key in the UI, which is where a person is present to answer it.
 */
object SecretStore {

    private const val ALIAS = "rednotetrans.apikey.v1"
    private const val PREF_KEY = "apiKeyCipher"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    fun save(ctx: Context, value: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        Prefs.of(ctx).edit()
            .putString(PREF_KEY, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .apply()
        true
    }.getOrDefault(false)

    fun load(ctx: Context): String? = runCatching {
        val blob = Prefs.of(ctx).getString(PREF_KEY, null) ?: return null
        val sealed = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        }
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    fun has(ctx: Context): Boolean = Prefs.of(ctx).getString(PREF_KEY, null) != null

    fun clear(ctx: Context) {
        Prefs.of(ctx).edit().remove(PREF_KEY).apply()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    /** Shown in the UI instead of the key itself, so a shoulder-surfer learns nothing. */
    fun masked(ctx: Context): String =
        load(ctx)?.let { "${it.take(6)}…${it.takeLast(4)}" } ?: "Not set"
}
