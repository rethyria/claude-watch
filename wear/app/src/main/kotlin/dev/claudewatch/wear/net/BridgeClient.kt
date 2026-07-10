package dev.claudewatch.wear.net

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the bridge's /v1 surface: POST /v1/pair, POST /v1/command
 * (session-scoped text commands and permission decisions) and GET /v1/events
 * (SSE via okhttp-sse, which buffers correctly across chunks by construction).
 *
 * All requests go to http://bridge.internal:<port> with the hostname's DNS
 * pinned to [hostIp], which must be an RFC1918/loopback IPv4 literal — see
 * [PrivateHosts] and res/xml/network_security_config.xml for why.
 *
 * [heartbeatTimeoutMs] is the SSE watchdog: the bridge writes a `:heartbeat`
 * comment every 10 s, which okhttp-sse never surfaces to the listener, so the
 * watchdog is the socket read timeout enforced by OkHttp's own always-live
 * scheduler threads (never an app timer that can land on a dead run loop).
 * More than 25 s of wire silence fails the stream, and the ConnectionEngine
 * turns that failure into a reconnect.
 */
open class BridgeClient(
    hostIp: String,
    port: Int,
    heartbeatTimeoutMs: Long = DEFAULT_HEARTBEAT_TIMEOUT_MS,
) {

    private val address: InetAddress = PrivateHosts.parsePrivateIpv4(hostIp)
        ?: throw IllegalArgumentException(
            "Bridge host must be a private (RFC1918) or loopback IPv4 address, got: $hostIp",
        )

    private val pinnedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname == PrivateHosts.BRIDGE_URL_HOST) listOf(address)
            else Dns.SYSTEM.lookup(hostname)
    }

    private val http = OkHttpClient.Builder()
        .dns(pinnedDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // The SSE stream is comment-heartbeated every 10 s by the bridge, so a
    // finite read timeout IS the heartbeat watchdog (see class KDoc).
    private val sseHttp = http.newBuilder()
        .readTimeout(heartbeatTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val baseUrl = "http://${PrivateHosts.BRIDGE_URL_HOST}:$port"

    data class ApiResult(val status: Int, val body: JSONObject?) {
        val ok: Boolean get() = status in 200..299
    }

    /**
     * GET /v1/ping — unauthenticated discovery/version probe. Returns the
     * bridge's protocol version ("proto"), bridgeId and machineName; used for
     * the client-side proto min-version gate before pairing.
     */
    fun ping(): ApiResult {
        val request = Request.Builder().url("$baseUrl/v1/ping").build()
        return execute(request)
    }

    /** POST /v1/pair — exchanges the banner pairing code for a bearer token. */
    fun pair(code: String, deviceName: String): ApiResult =
        postJson("/v1/pair", token = null, JSONObject().put("code", code).put("deviceName", deviceName))

    /**
     * POST /v1/command — session-id-scoped text command. The explicit session
     * id is mandatory at this rung: the no-session-id path 500s against
     * PTY-less sessions until the spawn-reliability fix lands.
     */
    fun sendCommand(token: String, sessionId: String, command: String): ApiResult =
        postJson("/v1/command", token, JSONObject().put("sessionId", sessionId).put("command", command))

    /** POST /v1/command — resolve a pending permission with an allow/deny decision. */
    fun answerPermission(
        token: String,
        permissionId: String,
        behavior: String,
        message: String? = null,
    ): ApiResult {
        val decision = JSONObject().put("behavior", behavior)
        if (message != null) decision.put("message", message)
        return postJson("/v1/command", token, JSONObject().put("permissionId", permissionId).put("decision", decision))
    }

    /**
     * GET /v1/events — opens the SSE stream, replaying from [lastEventId]
     * when set. Open so tests can widen the window between the stream
     * starting to connect and this call returning (the connect-tail race).
     */
    open fun openEvents(token: String, lastEventId: String?, listener: EventSourceListener): EventSource {
        val request = Request.Builder()
            .url("$baseUrl/v1/events")
            .header("Authorization", "Bearer $token")
            .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }
            .build()
        return EventSources.createFactory(sseHttp).newEventSource(request, listener)
    }

    private fun postJson(path: String, token: String?, body: JSONObject): ApiResult {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        return execute(request)
    }

    private fun execute(request: Request): ApiResult {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string()
            val parsed = if (text.isNullOrBlank()) null else try {
                JSONObject(text)
            } catch (_: Exception) {
                null
            }
            return ApiResult(response.code, parsed)
        }
    }

    companion object {
        /** Two missed 10 s bridge heartbeats plus slack. */
        const val DEFAULT_HEARTBEAT_TIMEOUT_MS = 25_000L
    }
}
