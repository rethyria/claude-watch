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
        // Additive `title` field (issue #50): parsed when present...
        assertEquals("Fix the flaky auth tests", running.title)

        // ...and absent (older bridge) stays null without failing the frame.
        val beta = BridgeEventParser.parse(byId.getValue("5")) as SessionEvent
        assertNull(beta.title)

        val toolOutput = BridgeEventParser.parse(byId.getValue("4")) as ToolOutputEvent
        assertEquals("Read", toolOutput.toolName)
        assertEquals("file contents here", toolOutput.toolOutputText)
        assertEquals("claude", toolOutput.source)

        val permission = BridgeEventParser.parse(byId.getValue("6")) as PermissionRequestEvent
        assertEquals(BridgeEventFixtures.PERMISSION_BASH, permission.permissionId)
        assertEquals("Bash", permission.toolName)
        // Machine-readable behaviors, never option position or wording.
        assertEquals(listOf("allow", "allow-always", "deny"), permission.options.map { it.behavior })

        // AskUserQuestion: no top-level options; questions forwarded verbatim
        // AND surfaced typed — question text (the answer key), header, the
        // per-question option labels, multiSelect.
        val askUser = BridgeEventParser.parse(byId.getValue("9")) as PermissionRequestEvent
        assertTrue(askUser.options.isEmpty())
        assertEquals(1, askUser.toolInput?.get("questions")?.jsonArray?.size)
        val question = askUser.questions.single()
        assertEquals("Which database should the service use?", question.question)
        assertEquals("Database", question.header)
        assertEquals(listOf("PostgreSQL", "SQLite"), question.options.map { it.label })
        assertEquals("Relational, hosted", question.options[0].description)
        assertEquals(false, question.multiSelect)

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
    // AskUserQuestion questions: typed, and lenient (hook content)
    // ------------------------------------------------------------------

    @Test
    fun multiQuestionAskUserQuestionSurfacesEveryQuestion() {
        val event = BridgeEventParser.parse(
            "permission-request",
            """{"permissionId":"p-ask","tool_name":"AskUserQuestion","tool_input":{"questions":[""" +
                """{"question":"Which color scheme?","header":"Color",""" +
                """"options":[{"label":"Blue"},{"label":"Green"}],"multiSelect":false},""" +
                """{"question":"Which linters?","header":"Lint",""" +
                """"options":[{"label":"ktlint"},{"label":"detekt"}],"multiSelect":true}]}}""",
        ) as PermissionRequestEvent
        // ALL questions, in payload order — the legacy single-question
        // assumption is exactly the defect this card fixes.
        assertEquals(
            listOf("Which color scheme?", "Which linters?"),
            event.questions.map { it.question },
        )
        assertEquals(listOf(false, true), event.questions.map { it.multiSelect })
        assertEquals(listOf("ktlint", "detekt"), event.questions[1].options.map { it.label })
    }

    @Test
    fun questionParsingIsLenientBecauseToolInputIsHookContent() {
        // tool_input is forwarded verbatim by the bridge, so unlike the event
        // contract it must never fail the frame: non-object entries, missing
        // question text (the answer key), and label-less options are skipped.
        val event = BridgeEventParser.parse(
            "permission-request",
            """{"permissionId":"p-ask","tool_name":"AskUserQuestion","tool_input":{"questions":[""" +
                """"not an object",42,{"header":"NoQuestionText"},""" +
                """{"question":"Keeps parsing?","options":[{"description":"no label"},{"label":"Yes"}],""" +
                """"multiSelect":"not a bool"}]}}""",
        ) as PermissionRequestEvent
        val question = event.questions.single()
        assertEquals("Keeps parsing?", question.question)
        assertNull(question.header)
        assertEquals(listOf("Yes"), question.options.map { it.label })
        assertEquals(false, question.multiSelect)
    }

    @Test
    fun questionsAreEmptyOffTheAskUserQuestionTool() {
        // A "questions" key in some other tool's input is that tool's
        // business, not a questionnaire.
        val bash = BridgeEventParser.parse(
            "permission-request",
            """{"permissionId":"p-1","tool_name":"Bash",""" +
                """"tool_input":{"questions":[{"question":"looks like one"}],"command":"ls"},""" +
                """"options":[{"behavior":"allow","label":"Yes"},{"behavior":"deny","label":"No"}]}""",
        ) as PermissionRequestEvent
        assertTrue(bash.questions.isEmpty())

        // And an AskUserQuestion without a usable questions array is simply
        // question-less (clients degrade to the plain card), never an error.
        val empty = BridgeEventParser.parse(
            "permission-request",
            """{"permissionId":"p-2","tool_name":"AskUserQuestion","tool_input":{"questions":"nope"}}""",
        ) as PermissionRequestEvent
        assertTrue(empty.questions.isEmpty())
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
    fun externalFlagParsesWhenPresentAndIsNullWhenOmitted() {
        // Hook-created (external) session: the additive flag parses to true.
        val external = BridgeEventParser.parse(
            "session",
            """{"state":"running","sessionId":"s-1","external":true}""",
        ) as SessionEvent
        assertEquals(true, external.external)

        // Bridge-owned PTY slot omits it: null (clients treat absent as false),
        // and its absence never fails the frame.
        val pty = BridgeEventParser.parse(
            "session",
            """{"state":"running","sessionId":"s-2"}""",
        ) as SessionEvent
        assertNull(pty.external)
    }

    @Test
    fun gitMetadataParsesWhenPresentAndIsNullWhenOmitted() {
        // Issue #54: a worktree session carries all three additive fields.
        val worktree = BridgeEventParser.parse(
            "session",
            """{"state":"running","sessionId":"s-1","branch":"issue-53-fix",""" +
                """"worktree":true,"repoRoot":"/home/dev/projects/alpha"}""",
        ) as SessionEvent
        assertEquals("issue-53-fix", worktree.branch)
        assertEquals(true, worktree.worktree)
        assertEquals("/home/dev/projects/alpha", worktree.repoRoot)

        // A main-checkout session carries only `branch` — `worktree` and
        // `repoRoot` are present ONLY for linked worktrees.
        val checkout = BridgeEventParser.parse(
            "session",
            """{"state":"running","sessionId":"s-2","branch":"main"}""",
        ) as SessionEvent
        assertEquals("main", checkout.branch)
        assertNull(checkout.worktree)
        assertNull(checkout.repoRoot)

        // Non-git root / older bridge: all absent, and absence never fails
        // the frame (absent = preserve previously-known, per the contract).
        val nonGit = BridgeEventParser.parse(
            "session",
            """{"state":"running","sessionId":"s-3"}""",
        ) as SessionEvent
        assertNull(nonGit.branch)
        assertNull(nonGit.worktree)
        assertNull(nonGit.repoRoot)
    }

    @Test
    fun agentsActivityParsesWhenPresentAndIsNullWhenOmitted() {
        // Issue #55: workflow activity arrives as a nested {running, done}
        // object once the bridge has observed any.
        val active = BridgeEventParser.parse(
            "session",
            """{"state":"running","sessionId":"s-1","agents":{"running":3,"done":1}}""",
        ) as SessionEvent
        assertEquals(3, active.agents?.running)
        assertEquals(1, active.agents?.done)

        // The explicit completion re-announce: present-but-zero IS a value
        // (the bridge's only clear path), so it must parse as one.
        val completed = BridgeEventParser.parse(
            "session",
            """{"state":"running","sessionId":"s-1","agents":{"running":0,"done":4}}""",
        ) as SessionEvent
        assertEquals(0, completed.agents?.running)
        assertEquals(4, completed.agents?.done)

        // Absent (no workflow observed / older bridge) stays null without
        // failing the frame.
        val absent = BridgeEventParser.parse(
            "session",
            """{"state":"running","sessionId":"s-2"}""",
        ) as SessionEvent
        assertNull(absent.agents)
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
