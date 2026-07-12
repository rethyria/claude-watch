package dev.claudewatch.wear.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the persisted connection blob (bearer token included) at rest.
 * Production uses [KeystoreTokenCipher]; JVM unit tests use [AesGcmTokenCipher]
 * with a locally generated key, exercising the identical GCM code path.
 */
interface TokenCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES/GCM with a caller-provided key. The wire format is
 * `[ivLength: 1 byte][iv][ciphertext+tag]`; the IV always comes from the
 * cipher itself (Android Keystore keys refuse caller-supplied IVs by design,
 * and letting the provider pick one is the safe default everywhere else).
 */
open class AesGcmTokenCipher(private val keyProvider: () -> SecretKey) : TokenCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        check(iv.size in 1..255) { "unexpected GCM IV length: ${iv.size}" }
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size >= 2) { "ciphertext too short" }
        val ivLength = blob[0].toInt() and 0xFF
        require(blob.size > 1 + ivLength) { "ciphertext too short for its IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyProvider(),
            GCMParameterSpec(GCM_TAG_BITS, blob, 1, ivLength),
        )
        return cipher.doFinal(blob, 1 + ivLength, blob.size - 1 - ivLength)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

/**
 * [AesGcmTokenCipher] keyed by a non-exportable AES-256 key in the Android
 * Keystore, created on first use. The key never leaves secure hardware (or
 * the keystore daemon on devices without a StrongBox/TEE): what DataStore
 * writes to disk is unreadable without going through this device's keystore.
 */
class KeystoreTokenCipher : AesGcmTokenCipher(::obtainKeystoreKey) {
    internal companion object {
        const val KEY_ALIAS = "bridge-credentials-key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

private fun obtainKeystoreKey(): SecretKey {
    val keyStore = KeyStore.getInstance(KeystoreTokenCipher.ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getEntry(KeystoreTokenCipher.KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
        ?.let { return it.secretKey }
    val generator = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        KeystoreTokenCipher.ANDROID_KEYSTORE,
    )
    generator.init(
        KeyGenParameterSpec.Builder(
            KeystoreTokenCipher.KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build(),
    )
    return generator.generateKey()
}
