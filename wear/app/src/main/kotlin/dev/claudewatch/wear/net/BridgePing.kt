package dev.claudewatch.wear.net

/**
 * The validated shape of the bridge's unauthenticated `GET /v1/ping` reply:
 * `{proto, bridgeId, machineName}` (see skill/bridge/commands.js handlePing).
 *
 * This is the discovery ladder's decoy filter. The bridge's default port,
 * 7860, is ALSO Gradio's default — so "some HTTP server answered on the
 * bridge's port" proves nothing. A responder only counts as a bridge when the
 * reply is 2xx JSON carrying a positive integer `proto`, a non-empty
 * `bridgeId` and a non-empty `machineName`; anything else (HTML, a JSON API
 * with different keys, an error page) is rejected and never receives a token.
 */
data class BridgePing(
    val proto: Int,
    val bridgeId: String,
    val machineName: String,
) {
    companion object {
        /** Null unless [result] is a well-formed bridge ping reply. */
        fun from(result: BridgeClient.ApiResult): BridgePing? {
            if (!result.ok) return null
            val body = result.body ?: return null
            val proto = body.optString("proto").toIntOrNull() ?: return null
            if (proto < 1) return null
            val bridgeId = body.optString("bridgeId")
            if (bridgeId.isEmpty()) return null
            val machineName = body.optString("machineName")
            if (machineName.isEmpty()) return null
            return BridgePing(proto, bridgeId, machineName)
        }
    }
}
