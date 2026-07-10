package dev.claudewatch.shared

import dev.claudewatch.shared.protocol.BridgeEvent
import dev.claudewatch.shared.protocol.BridgeEventParser
import dev.claudewatch.shared.protocol.PermissionRequestEvent
import dev.claudewatch.shared.protocol.SessionEvent
import dev.claudewatch.shared.protocol.SessionRunState
import dev.claudewatch.shared.protocol.ToolOutputEvent
import dev.claudewatch.shared.protocol.UnknownEvent
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire-model half of issue #16's contract: every fixture in the corpus
 * parses into a typed event, unknown fields are tolerated, and contract
 * violations fail loudly instead of degrading into wrong UI.
 */
class WireModelContractTest {

    // ------------------------------------------------------------------
    // The fixture corpus parses
    // ------------------------------------------------------------------

    @Test
    fun everyCorpusFixtureParsesIntoATypedEvent() {
        val frames = BridgeEventFixtures.corpus()
        assertTrue("corpus must not be empty", frames.isNotEmpty())
        for (frame in frames) {
            val event: BridgeEvent = BridgeEventParser.parse(frame)
            assertTrue(
                "corpus frame ${frame.id} (${frame.type}) must map to a typed model, got UnknownEvent",
                event !is UnknownEvent,
            )
        }
    }

    @Test
    fun corpusFieldsSurviveTheRoundTripTyped() {
        val byId = BridgeEventFixtures.corpus().associateBy { it.id }

        val running = BridgeEventParser.parse(byId.getValue("2")) as SessionEvent
        assertEquals(SessionRunState.RUNNING, running.state)
        assertEquals(BridgeEventFixtures.SESSION_ALPHA, running.sessionId)
        assertEquals("claude", running.agent)
        assertEquals("alpha", running.folderName)

        val toolOutput = BridgeEventParser.parse(byId.getValue("4")) as ToolOutputEvent
        assertEquals("Read", toolOutput.toolName)
        assertEquals("file contents here", toolOutput.toolOutputText)
        assertEquals("claude", toolOutput.source)

        val permission = BridgeEventParser.parse(byId.getValue("6")) as PermissionRequestEvent
        assertEquals(BridgeEventFixtures.PERMISSION_BASH, permission.permissionId)
        assertEquals("Bash", permission.toolName)
        // Machine-readable behaviors, never option position or wording.
        assertEquals(listOf("allow", "allow-always", "deny"), permission.options.map { it.behavior })

        // AskUserQuestion: no top-level options; questions forwarded verbatim.
        val askUser = BridgeEventParser.parse(byId.getValue("9")) as PermissionRequestEvent
        assertTrue(askUser.options.isEmpty())
        assertEquals(1, askUser.toolInput?.get("questions")?.jsonArray?.size)

        // Codex tool-output posts an explicit JSON null tool_output.
        val codexTool = BridgeEventParser.parse(byId.getValue("14")) as ToolOutputEvent
        assertNull(codexTool.toolOutputText)

        // pty exit carries exitCode + explicit-null signal.
        val ended = BridgeEventParser.parse(byId.getValue("18")) as SessionEvent
        assertEquals(SessionRunState.ENDED, ended.state)
        assertEquals(0, ended.exitCode)
        assertNull(ended.signal)

        val killed = BridgeEventParser.parse(byId.getValue("21")) as SessionEvent
        assertEquals(true, killed.killed)
    }

    // ------------------------------------------------------------------
    // Tolerant: unknown fields and unknown event types
    // ------------------------------------------------------------------

    @Test
    fun unknownFieldsAreTolerated() {
        // Hook bodies are forwarded verbatim; a future Claude Code can add
        // arbitrary fields without breaking older clients.
        val event = BridgeEventParser.parse(
            "session",
            """{"state":"running","sessionId":"s-1","agent":"claude",""" +
                """"someFutureField":{"nested":[1,2,3]},"anotherOne":null}""",
        ) as SessionEvent
        assertEquals("s-1", event.sessionId)
    }

    @Test
    fun unknownEventTypesAreToleratedNotFatal() {
        val event = BridgeEventParser.parse("shiny-new-event", """{"anything":true}""")
        assertTrue(event is UnknownEvent)
        assertEquals("shiny-new-event", (event as UnknownEvent).type)
    }

    // ------------------------------------------------------------------
    // Strict: contract violations fail loudly
    // ------------------------------------------------------------------

    private fun assertFailsLoudly(type: String, data: String) {
        assertThrows(IllegalArgumentException::class.java) {
            BridgeEventParser.parse(type, data)
        }
    }

    @Test
    fun malformedJsonFailsLoudly() {
        assertFailsLoudly("session", "not json at all")
    }

    @Test
    fun sessionEventWithoutStateFailsLoudly() {
        assertFailsLoudly("session", """{"sessionId":"s-1","agent":"claude"}""")
    }

    @Test
    fun sessionEventWithUnknownStateFailsLoudly() {
        assertFailsLoudly("session", """{"state":"paused","sessionId":"s-1"}""")
    }

    @Test
    fun runningSessionWithoutSessionIdFailsLoudly() {
        assertFailsLoudly("session", """{"state":"running","agent":"claude"}""")
    }

    @Test
    fun endedSessionWithoutSessionIdFailsLoudly() {
        assertFailsLoudly("session", """{"state":"ended","agent":"claude"}""")
    }

    @Test
    fun permissionRequestWithoutPermissionIdFailsLoudly() {
        assertFailsLoudly("permission-request", """{"tool_name":"Bash","tool_input":{"command":"ls"}}""")
    }

    @Test
    fun permissionOptionWithoutMachineReadableBehaviorFailsLoudly() {
        // Exactly the server-side canonicalPermissionOptions() rule: a client
        // must never have to guess approve/deny from position or wording.
        assertFailsLoudly(
            "permission-request",
            """{"permissionId":"p-1","tool_name":"Bash",""" +
                """"options":[{"label":"Yes"},{"label":"No"}]}""",
        )
        assertFailsLoudly(
            "permission-request",
            """{"permissionId":"p-1","tool_name":"Bash",""" +
                """"options":[{"behavior":"approve-forever","label":"Yes"}]}""",
        )
    }

    @Test
    fun ptyOutputWithoutTextFailsLoudly() {
        assertFailsLoudly("pty-output", """{"sessionId":"s-1"}""")
    }

    @Test
    fun permissionClearedWithoutPermissionIdFailsLoudly() {
        assertFailsLoudly("permission-cleared", """{"reason":"hook-aborted"}""")
    }
}
