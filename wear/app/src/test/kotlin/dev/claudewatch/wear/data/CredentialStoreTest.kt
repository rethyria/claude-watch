package dev.claudewatch.wear.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * At-rest and process-death guarantees: what DataStore writes to disk is
 * ciphertext (in production the key is a non-exportable Android Keystore key;
 * here a local AES key drives the identical GCM code path), and a fresh store
 * over the same file — a new process, as far as DataStore is concerned —
 * reads back both the credentials and the lastEventId replay cursor.
 */
class CredentialStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun newStore(file: File, scope: CoroutineScope) =
        CredentialStore({ file }, AesGcmTokenCipher { key }, scope)

    /** Simulates process death: tears the store down so the file is released. */
    private fun killProcess(scope: CoroutineScope) = runBlocking {
        val job = scope.coroutineContext[Job]!!
        job.cancel()
        job.join()
    }

    @Test
    fun tokenAtRestIsEncrypted() = runBlocking<Unit> {
        val file = File(tmp.root, "conn.bin")
        val scope = newScope()
        val store = newStore(file, scope)

        store.saveCredentials(BridgeCredentials("secret-token-abc123", "192.168.1.20", 7860, "b-1"))
        store.saveLastEventId("42")
        assertEquals("secret-token-abc123", store.read().credentials!!.token)

        val onDisk = file.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("expected a non-empty blob on disk", onDisk.isNotEmpty())
        assertFalse("token must not be readable at rest", onDisk.contains("secret-token-abc123"))
        assertFalse("host must not be readable at rest", onDisk.contains("192.168.1.20"))
        assertFalse("even field names must be ciphertext", onDisk.contains("lastEventId"))

        killProcess(scope)
    }

    @Test
    fun credentialsAndLastEventIdSurviveProcessDeath() = runBlocking<Unit> {
        val file = File(tmp.root, "conn.bin")
        val scope1 = newScope()
        val store1 = newStore(file, scope1)
        store1.saveCredentials(BridgeCredentials("tok-1", "10.0.2.2", 7861, "b-9"))
        store1.saveLastEventId("1337")
        killProcess(scope1)

        val scope2 = newScope()
        val store2 = newStore(file, scope2)
        val revived = store2.read()
        assertEquals("tok-1", revived.credentials!!.token)
        assertEquals("10.0.2.2", revived.credentials!!.hostIp)
        assertEquals(7861, revived.credentials!!.port)
        assertEquals("b-9", revived.credentials!!.bridgeId)
        assertEquals("1337", revived.lastEventId)
        killProcess(scope2)
    }

    @Test
    fun corruptFileFallsBackToUnpairedInsteadOfCrashing() = runBlocking<Unit> {
        val file = File(tmp.root, "conn.bin")
        file.writeBytes(ByteArray(64) { (it * 7).toByte() })

        val scope = newScope()
        val store = newStore(file, scope)
        val state = store.read()
        assertNull(state.credentials)
        assertEquals(PersistedConnection.FULL_REPLAY_EVENT_ID, state.lastEventId)
        killProcess(scope)
    }

    @Test
    fun lastEventIdWriteAfterClearCannotResurrectState() = runBlocking<Unit> {
        val file = File(tmp.root, "conn.bin")
        val scope = newScope()
        val store = newStore(file, scope)
        store.saveCredentials(BridgeCredentials("tok-1", "127.0.0.1", 7860))
        store.clear()
        // A late in-flight cursor write from a torn-down stream: dropped.
        store.saveLastEventId("99")
        val state = store.read()
        assertNull(state.credentials)
        assertEquals(PersistedConnection.FULL_REPLAY_EVENT_ID, state.lastEventId)
        killProcess(scope)
    }

    @Test
    fun aesGcmCipherRoundTripsWithFreshIvPerEncryption() {
        val cipher = AesGcmTokenCipher { key }
        val plain = "the-bearer-token".toByteArray()
        val blobA = cipher.encrypt(plain)
        val blobB = cipher.encrypt(plain)
        assertArrayEquals(plain, cipher.decrypt(blobA))
        assertArrayEquals(plain, cipher.decrypt(blobB))
        assertFalse("IV reuse would break GCM", blobA.contentEquals(blobB))
    }
}
