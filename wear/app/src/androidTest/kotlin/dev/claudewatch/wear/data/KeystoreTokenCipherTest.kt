package dev.claudewatch.wear.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the production cipher path really goes through the
 * Android Keystore: encrypt/decrypt round-trips with a key this process can
 * use but never export, and every encryption gets a fresh IV.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenCipherTest {

    @Test
    fun roundTripsThroughTheAndroidKeystore() {
        val cipher = KeystoreTokenCipher()
        val plaintext = "watch-bearer-token-${System.currentTimeMillis()}".toByteArray()

        val blob = cipher.encrypt(plaintext)
        assertFalse(
            "ciphertext must not contain the plaintext",
            String(blob, Charsets.ISO_8859_1).contains(String(plaintext)),
        )
        assertArrayEquals(plaintext, cipher.decrypt(blob))
    }

    @Test
    fun freshIvPerEncryption() {
        val cipher = KeystoreTokenCipher()
        val plaintext = "same-token".toByteArray()
        assertFalse(cipher.encrypt(plaintext).contentEquals(cipher.encrypt(plaintext)))
    }
}
