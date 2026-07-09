package dev.claudewatch.wear.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wire-contract tests against MockWebServer on loopback. The client always
 * addresses http://bridge.internal:<port> with DNS pinned to the entered IP,
 * so these tests also exercise the pinning path end to end.
 */
class BridgeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BridgeClient("127.0.0.1", server.port)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesNonPrivateHosts() {
        BridgeClient("8.8.8.8", 7860)
    }

    @Test
    fun pairPostsCodeToV1AndParsesToken() {
        server.enqueue(
            MockResponse().setBody(
                """{"token":"tok-123","bridgeId":"b-1","sessions":[{"id":"s-1","state":"running"}]}""",
            ),
        )

        val result = client.pair("123456", "wear-skeleton")

        assertEquals(200, result.status)
        assertEquals("tok-123", result.body!!.getString("token"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/pair", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("123456", body.getString("code"))
        assertEquals("wear-skeleton", body.getString("deviceName"))
    }

    @Test
    fun pairSurfacesRejection() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid pairing code"}"""))

        val result = client.pair("000000", "wear-skeleton")

        assertEquals(401, result.status)
        assertEquals("Invalid pairing code", result.body!!.getString("error"))
    }

    @Test
    fun sendCommandIsSessionScopedAndBearerAuthed() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"sessionId":"s-1"}"""))

        val result = client.sendCommand("tok-123", "s-1", "say hello")

        assertEquals(200, result.status)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/command", request.path)
        assertEquals("Bearer tok-123", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("s-1", body.getString("sessionId"))
        assertEquals("say hello", body.getString("command"))
    }

    @Test
    fun answerPermissionPostsDecision() {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val result = client.answerPermission("tok-123", "perm-9", "deny", "Denied from the watch")

        assertEquals(200, result.status)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("perm-9", body.getString("permissionId"))
        assertEquals("deny", body.getJSONObject("decision").getString("behavior"))
        assertEquals("Denied from the watch", body.getJSONObject("decision").getString("message"))
    }

    @Test
    fun eventsStreamParsesSseAndSendsAuthAndLastEventId() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    ":connected\n\n" +
                        "id: 1\nevent: session\ndata: {\"state\":\"running\",\"sessionId\":\"s-1\"}\n\n" +
                        "id: 2\nevent: tool-output\ndata: {\"tool_name\":\"Read\",\"tool_output\":\"hi\"}\n\n",
                ),
        )

        val received = CopyOnWriteArrayList<Triple<String?, String?, String>>()
        val done = CountDownLatch(1)
        val source = client.openEvents(
            "tok-123",
            lastEventId = "41",
            listener = object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    received.add(Triple(id, type, data))
                    if (received.size == 2) done.countDown()
                }
            },
        )
        try {
            assertTrue("expected 2 SSE events", done.await(10, TimeUnit.SECONDS))
        } finally {
            source.cancel()
        }

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/events", request.path)
        assertEquals("Bearer tok-123", request.getHeader("Authorization"))
        assertEquals("41", request.getHeader("Last-Event-ID"))

        assertEquals(Triple<String?, String?, String>("1", "session", """{"state":"running","sessionId":"s-1"}"""), received[0])
        assertEquals("2", received[1].first)
        assertEquals("tool-output", received[1].second)
    }
}
