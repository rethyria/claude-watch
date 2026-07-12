package dev.claudewatch.shared

import dev.claudewatch.shared.protocol.SseFrame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Loads the fixture corpus: one bridge ring-buffer entry per NDJSON line
 * (`{id, event, data}` — the exact shape `pushSseEvent` buffers in
 * skill/bridge/transport-sse.js), with payloads mirroring what the bridge's
 * own test suite drives over the wire (see skill/bridge/test/e2e.test.js).
 *
 * The corpus is a coherent two-session timeline: claude session "alpha" and
 * codex session "beta" run concurrently, alpha handles two permission
 * prompts, both sessions complete a task and end, then a third session
 * "gamma" is killed. Hand-built for now; the bridge protocol-contract issue
 * replaces this with a recorded corpus in the same format.
 */
object BridgeEventFixtures {

    const val SESSION_ALPHA = "5f0d2c9a-8b1e-4c3f-9a67-2e51b4c8d0aa"
    const val SESSION_BETA = "b7e3f1c2-4d5a-4b8e-a2f0-9c6d1e7a3b55"
    const val SESSION_GAMMA = "c9a8b7c6-d5e4-4f3a-b2c1-0d9e8f7a6b5c"
    const val PERMISSION_BASH = "1c67a4de-52b9-4f1e-8a3c-7d20e6b9f480"
    const val PERMISSION_ASK_USER = "8e15d9ab-3c74-4d2f-b6a1-0f38c5e72d94"
    const val PERMISSION_CODEX = "e4b0a2f8-96cd-4e17-8b53-1a6d9c0f2e35"

    @Serializable
    private data class FixtureEntry(val id: Long, val event: String, val data: JsonObject)

    private val json = Json { ignoreUnknownKeys = true }

    fun corpus(): List<SseFrame> =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/bridge-events.ndjson")) {
            "fixture corpus missing from test resources"
        }
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val entry = json.decodeFromString<FixtureEntry>(line)
                // JsonObject.toString() re-serializes the payload exactly as the
                // bridge stringifies it into the SSE `data:` line.
                SseFrame(entry.id.toString(), entry.event, entry.data.toString())
            }
}
