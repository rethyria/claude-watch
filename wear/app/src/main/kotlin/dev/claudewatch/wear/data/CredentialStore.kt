package dev.claudewatch.wear.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The paired bridge's identity and bearer token. Null token never persists. */
data class BridgeCredentials(
    val token: String,
    val hostIp: String,
    val port: Int,
    val bridgeId: String? = null,
)

/**
 * Everything the connection layer persists. [lastEventId] survives process
 * death so a relaunch resumes the SSE replay cursor instead of re-reading
 * (or worse, missing) the bridge's ring buffer; "0" means "replay everything"
 * and is also the reset value for a fresh pairing.
 */
data class PersistedConnection(
    val credentials: BridgeCredentials? = null,
    val lastEventId: String = FULL_REPLAY_EVENT_ID,
) {
    companion object {
        const val FULL_REPLAY_EVENT_ID = "0"
    }
}

/**
 * Keystore-encrypted DataStore for [PersistedConnection]. Uses DataStore's
 * single-typed-object mechanism (the Proto DataStore machinery) with a custom
 * [Serializer] whose on-disk bytes are wholly encrypted by a [TokenCipher] —
 * in production a non-exportable Android Keystore key ([KeystoreTokenCipher]).
 * A blob that fails to decrypt or parse is treated as corruption and replaced
 * with the unpaired default rather than crashing the app.
 */
class CredentialStore(
    produceFile: () -> File,
    cipher: TokenCipher,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val dataStore: DataStore<PersistedConnection> = DataStoreFactory.create(
        serializer = PersistedConnectionSerializer(cipher),
        corruptionHandler = ReplaceFileCorruptionHandler { PersistedConnection() },
        scope = scope,
        produceFile = produceFile,
    )

    suspend fun read(): PersistedConnection = dataStore.data.first()

    /** New pairing: replaces everything, resetting the replay cursor. */
    suspend fun saveCredentials(credentials: BridgeCredentials) {
        dataStore.updateData { PersistedConnection(credentials) }
    }

    /**
     * Advances the replay cursor. A no-op once credentials are cleared so a
     * late in-flight event from a torn-down stream cannot resurrect state
     * after unpair.
     */
    suspend fun saveLastEventId(id: String) {
        dataStore.updateData { current ->
            if (current.credentials == null) current else current.copy(lastEventId = id)
        }
    }

    suspend fun clear() {
        dataStore.updateData { PersistedConnection() }
    }

    companion object {
        @Volatile
        private var instance: CredentialStore? = null

        /**
         * Process-wide singleton: DataStore forbids two active instances on
         * the same file, and ViewModels are recreated within one process.
         */
        fun singleton(context: Context): CredentialStore =
            instance ?: synchronized(this) {
                instance ?: CredentialStore(
                    produceFile = {
                        File(context.applicationContext.filesDir, "bridge_connection.bin")
                    },
                    cipher = KeystoreTokenCipher(),
                ).also { instance = it }
            }
    }
}

private class PersistedConnectionSerializer(
    private val cipher: TokenCipher,
) : Serializer<PersistedConnection> {

    override val defaultValue: PersistedConnection = PersistedConnection()

    override suspend fun readFrom(input: InputStream): PersistedConnection {
        val blob = input.readBytes()
        if (blob.isEmpty()) return defaultValue
        val json = try {
            JSONObject(String(cipher.decrypt(blob), Charsets.UTF_8))
        } catch (e: Exception) {
            // Wrong key, truncated file, garbage: corruption, not a crash.
            throw CorruptionException("cannot decrypt persisted connection state", e)
        }
        val credentials = json.optJSONObject("credentials")?.let { c ->
            val token = c.optString("token")
            val hostIp = c.optString("hostIp")
            val port = c.optInt("port", -1)
            if (token.isEmpty() || hostIp.isEmpty() || port !in 1..65535) null
            else BridgeCredentials(
                token = token,
                hostIp = hostIp,
                port = port,
                bridgeId = c.optString("bridgeId").takeUnless { it.isEmpty() },
            )
        }
        val lastEventId = json.optString("lastEventId")
            .ifEmpty { PersistedConnection.FULL_REPLAY_EVENT_ID }
        return PersistedConnection(credentials, lastEventId)
    }

    override suspend fun writeTo(t: PersistedConnection, output: OutputStream) {
        val json = JSONObject().put("lastEventId", t.lastEventId)
        t.credentials?.let { c ->
            json.put(
                "credentials",
                JSONObject()
                    .put("token", c.token)
                    .put("hostIp", c.hostIp)
                    .put("port", c.port)
                    .apply { if (c.bridgeId != null) put("bridgeId", c.bridgeId) },
            )
        }
        output.write(cipher.encrypt(json.toString().toByteArray(Charsets.UTF_8)))
    }
}
