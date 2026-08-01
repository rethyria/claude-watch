package dev.claudewatch.wear.ui.halo

import dev.claudewatch.wear.BridgeViewModel.PendingPermission
import dev.claudewatch.wear.ui.halo.Halo.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure HaloNav state machine, two layers of it:
 *
 * USAGE and SETTINGS pages (issue #57): usage lives at page [USAGE_PAGE] (-1)
 * and settings at [SETTINGS_PAGE] (-2), both LEFT of home and deliberately
 * depth-less — no drill-down, no centerpiece jump — while All keeps page 0 so
 * jumpHome and every existing depth path land exactly where they always did.
 * The settings tests exercise the SAME guards as usage: both are `< 0`, so
 * the machine folds them into the no-depth/All behavior for free (narrowing a
 * guard to `== USAGE_PAGE` breaks the settings cases by name).
 *
 * The v2 list pager (Halo v2 S1, #95): the LIST depth carries a selection —
 * null there is the All scope's trailing spawn card, or an empty scope —
 * stepped with NO wrap, resolved on drill, PRESERVED by back-from-feed (the
 * shrink morph must land on the right ring segment) and cleared by
 * back-to-page. The pager's Answer pill pins the card over the LIST to that
 * session's own prompt, never the global front.
 */
class HaloNavTest {

    private fun session(id: String, project: String, state: SessionState = SessionState.RUNNING) =
        HaloSession(id = id, title = id, projectName = project, state = state)

    private fun waiting(id: String, project: String, permissionId: String) =
        HaloSession(
            id = id,
            title = id,
            projectName = project,
            state = SessionState.WAITING_PERM,
            pending = PendingPermission(
                permissionId = permissionId,
                sessionId = id,
                toolName = "Bash",
                requestSummary = "$ make",
                sessionLabel = project,
                options = emptyList(),
            ),
        )

    private fun model(): HaloModel {
        val alpha = listOf(session("s-a1", "alpha"), session("s-a2", "alpha"))
        val beta = listOf(session("s-b1", "beta", SessionState.WAITING_PERM))
        return HaloModel(
            projects = listOf(HaloProject("alpha", alpha), HaloProject("beta", beta)),
            sessions = alpha + beta,
            queue = beta,
        )
    }

    /**
     * Two waiting sessions in DIFFERENT projects with the global queue front
     * in the later one, so home-vs-project Answer scoping is observable: a
     * scoping bug and a pin-to-front bug each flip a distinct assertion.
     */
    private fun waitingModel(): HaloModel {
        val a1 = session("s-a1", "alpha")
        val a2 = waiting("s-a2", "alpha", "p-alpha")
        val b1 = waiting("s-b1", "beta", "p-beta")
        return HaloModel(
            projects = listOf(HaloProject("alpha", listOf(a1, a2)), HaloProject("beta", listOf(b1))),
            sessions = listOf(a1, a2, b1),
            queue = listOf(b1, a2),
        )
    }

    private val emptyModel = HaloModel(projects = emptyList(), sessions = emptyList(), queue = emptyList())

    @Test
    fun scopeForPageIsSafeAtTheUsagePage() {
        // -1 folds into All: a scope that always exists, never an index crash.
        assertEquals(ListScope.All, scopeForPage(USAGE_PAGE, model()))
        assertEquals(ListScope.All, scopeForPage(0, model()))
        assertEquals(ListScope.Project("alpha"), scopeForPage(1, model()))
        assertEquals(ListScope.Project("beta"), scopeForPage(2, model()))
        // Past-the-end (model shrank under the pager) still degrades to All.
        assertEquals(ListScope.All, scopeForPage(3, model()))
    }

    @Test
    fun drillToListFromTheUsagePageIsANoOp() {
        val onUsage = HaloNavState(page = USAGE_PAGE)
        // No depth below the usage page: the pager-level swipe-up gesture
        // still fires, but the state machine refuses the jump.
        assertEquals(onUsage, onUsage.drillToList(model()))
    }

    @Test
    fun drillToListResolvesTheScopeFromHomeAndProjectPages() {
        val fromHome = HaloNavState(page = 0).drillToList(model())
        assertEquals(HaloDepth.LIST, fromHome.depth)
        assertEquals(ListScope.All, fromHome.listScope)

        val fromProject = HaloNavState(page = 2).drillToList(model())
        assertEquals(HaloDepth.LIST, fromProject.depth)
        assertEquals(ListScope.Project("beta"), fromProject.listScope)
    }

    @Test
    fun openFirstWaitingFromTheUsagePageIsANoOp() {
        // The queue HAS a waiting item, but the usage page renders no
        // centerpiece — the machine must not hide a depth jump behind it.
        val onUsage = HaloNavState(page = USAGE_PAGE)
        assertEquals(onUsage, onUsage.openFirstWaiting(model()))
    }

    @Test
    fun openFirstWaitingFromHomeStillOpensTheGlobalFront() {
        val opened = HaloNavState(page = 0).openFirstWaiting(model())
        assertEquals(HaloDepth.SESSION, opened.depth)
        assertEquals("s-b1", opened.sessionId)
        assertEquals(true, opened.cardOpen)
    }

    @Test
    fun jumpHomeLandsOnAllFromTheUsagePage() {
        // jumpHome's target is untouched by the usage page: All at page 0.
        val home = HaloNavState(page = USAGE_PAGE).jumpHome()
        assertEquals(HaloNavState(), home)
        assertEquals(0, home.page)
        assertEquals(HaloDepth.PAGE, home.depth)
        assertNull(home.sessionId)
    }

    @Test
    fun backAtTheTopOfTheUsagePageStaysPut() {
        val onUsage = HaloNavState(page = USAGE_PAGE)
        assertEquals(onUsage, onUsage.back())
    }

    // ── Settings page (page = SETTINGS_PAGE, -2): the same depth-less guards ──
    // (scopeForPage(-2) == All is covered transitively by the drill/openFirst
    // no-op cases below; it needs no standalone test — the `<= 0` guard plus the
    // getOrNull fallback make it un-crashable by construction, like the
    // pre-existing usage sibling.)

    @Test
    fun drillToListFromTheSettingsPageIsANoOp() {
        // Settings is a flat glance surface with no depth below it: the
        // pager-level swipe-up gesture still fires, but the state machine
        // refuses the jump (page < 0), never a surprise All list.
        val onSettings = HaloNavState(page = SETTINGS_PAGE)
        assertEquals(onSettings, onSettings.drillToList(model()))
    }

    @Test
    fun openFirstWaitingFromTheSettingsPageIsANoOp() {
        // The queue HAS a waiting item, but settings renders no centerpiece —
        // the machine must not hide a depth jump behind a page with no tap
        // target.
        val onSettings = HaloNavState(page = SETTINGS_PAGE)
        assertEquals(onSettings, onSettings.openFirstWaiting(model()))
    }

    @Test
    fun jumpHomeLandsOnAllFromTheSettingsPage() {
        // jumpHome's target is untouched by the settings page: All at page 0.
        val home = HaloNavState(page = SETTINGS_PAGE).jumpHome()
        assertEquals(HaloNavState(), home)
        assertEquals(0, home.page)
        assertEquals(HaloDepth.PAGE, home.depth)
        assertNull(home.sessionId)
    }

    @Test
    fun backAtTheTopOfTheSettingsPageStaysPut() {
        val onSettings = HaloNavState(page = SETTINGS_PAGE)
        assertEquals(onSettings, onSettings.back())
    }

    // ── The v2 shell's nav-owned page (Halo v2 S3, #98) ──────────────────────

    @Test
    fun stepPageWalksTheRowWithHardStopsAtBothEnds() {
        // The full walk: settings ‹ usage ‹ All ‹ alpha ‹ beta, no wrap —
        // these are the bounds the retired HorizontalPager's pageCount used
        // to enforce.
        val home = HaloNavState()
        assertEquals(USAGE_PAGE, home.stepPage(-1, model()).page)
        assertEquals(SETTINGS_PAGE, home.stepPage(-1, model()).stepPage(-1, model()).page)
        // Left end: settings is the hard stop.
        val settings = HaloNavState(page = SETTINGS_PAGE)
        assertEquals(settings, settings.stepPage(-1, model()))

        assertEquals(1, home.stepPage(+1, model()).page)
        assertEquals(2, home.stepPage(+1, model()).stepPage(+1, model()).page)
        // Right end: the last project is the hard stop.
        val beta = HaloNavState(page = 2)
        assertEquals(beta, beta.stepPage(+1, model()))
    }

    @Test
    fun stepPageBelowThePageDepthIsANoOp() {
        // Deeper levels own their own horizontal gestures (the list pager's
        // step, the feed's swipe-right back): a stray page step from there
        // must not silently retarget the eventual back-out.
        val onList = HaloNavState(page = 0).drillToList(model())
        assertEquals(onList, onList.stepPage(+1, model()))
    }

    // ── The v2 list pager (Halo v2 S1, #95) ──────────────────────────────────

    @Test
    fun drillResolvesTheSelectionKeepInScopeElseFirstElseNull() {
        // A fresh drill (no prior selection) lands on the scope's first.
        assertEquals("s-a1", HaloNavState(page = 0).drillToList(model()).sessionId)
        // An in-scope selection survives the drill untouched.
        assertEquals(
            "s-a2",
            HaloNavState(page = 0, sessionId = "s-a2").drillToList(model()).sessionId,
        )
        // Out of scope (beta's session on the alpha page) re-resolves to first.
        assertEquals(
            "s-a1",
            HaloNavState(page = 1, sessionId = "s-b1").drillToList(model()).sessionId,
        )
    }

    @Test
    fun stepWalksTheScopeForwardAndBackWithoutWrapping() {
        val first = HaloNavState(page = 1).drillToList(model())
        assertEquals(ListScope.Project("alpha"), first.listScope)
        assertEquals("s-a1", first.sessionId)
        // No wrap at the start: the UI turns this edge into back(), not a step.
        assertEquals(first, first.step(-1, model()))

        val second = first.step(+1, model())
        assertEquals("s-a2", second.sessionId)
        assertEquals(first, second.step(-1, model()))
        // A project scope has NO spawn slot: its last session is the hard end.
        assertEquals(second, second.step(+1, model()))
    }

    @Test
    fun allScopeStepsOntoTheSpawnSlotThenStops() {
        val last = HaloNavState(page = 0).drillToList(model())
            .step(+1, model())
            .step(+1, model())
        assertEquals("s-b1", last.sessionId)

        // The trailing spawn card is a real slot (sessionId = null), and the
        // TRUE end of the All pager: one more step is a no-op, not a wrap.
        val spawn = last.step(+1, model())
        assertEquals(HaloDepth.LIST, spawn.depth)
        assertNull(spawn.sessionId)
        assertEquals(spawn, spawn.step(+1, model()))
        // And stepping back off it re-selects the last real session.
        assertEquals("s-b1", spawn.step(-1, model()).sessionId)
    }

    @Test
    fun stepOutsideTheListIsANoOp() {
        val onPage = HaloNavState(page = 0)
        assertEquals(onPage, onPage.step(+1, model()))
        // A vanished selection steps nowhere — the self-heal repairs it, a
        // swipe must not guess.
        val stale = HaloNavState(page = 0, depth = HaloDepth.LIST, sessionId = "s-gone")
        assertEquals(stale, stale.step(+1, model()))
    }

    @Test
    fun atListStartIsTrueOnlyOnTheFirstSlot() {
        val first = HaloNavState(page = 0).drillToList(model())
        assertTrue(first.atListStart(model()))
        assertFalse(first.step(+1, model()).atListStart(model()))
        // The spawn card is the END: a null selection with sessions present
        // must NOT read as the start, or ‹ there would pop the whole list.
        val spawn = first.copy(sessionId = null)
        assertFalse(spawn.atListStart(model()))
    }

    @Test
    fun emptyAllScopeIsJustTheSpawnCard() {
        val list = HaloNavState(page = 0).drillToList(emptyModel)
        assertEquals(HaloDepth.LIST, list.depth)
        // No sessions anywhere: the drill still lands somewhere real — the
        // spawn card, the sole slot, both start and end.
        assertNull(list.sessionId)
        assertTrue(list.atListStart(emptyModel))
        assertEquals(list, list.step(+1, emptyModel))
        assertEquals(list, list.step(-1, emptyModel))
    }

    @Test
    fun anEmptyProjectScopeResolvesToNothingAndStaysSteppable() {
        // A project can vanish under a stale scope; it must degrade to an
        // empty list, not crash or borrow All's sessions.
        assertEquals(emptyList<HaloSession>(), model().sessionsIn(ListScope.Project("gone")))
        val stale = HaloNavState(depth = HaloDepth.LIST, listScope = ListScope.Project("gone"))
        // No spawn slot outside All: nothing to step onto, trivially at start
        // so back remains reachable.
        assertEquals(stale, stale.step(+1, model()))
        assertEquals(stale, stale.step(-1, model()))
        assertTrue(stale.atListStart(model()))
    }

    @Test
    fun backFromAFeedPreservesTheSelectionBackFromTheListClearsIt() {
        val feed = HaloNavState(page = 0).drillToList(model())
            .step(+1, model())
            .drillToSession("s-a2")

        // Feed → list: the session becomes the pager selection — the shrink
        // morph must land on ITS ring segment, not snap back to the first.
        val list = feed.back()
        assertEquals(HaloDepth.LIST, list.depth)
        assertEquals("s-a2", list.sessionId)

        // List → page: the selection dies with the list, so a later drill
        // resolves fresh instead of resurrecting a stale id.
        val page = list.back()
        assertEquals(HaloDepth.PAGE, page.depth)
        assertNull(page.sessionId)
    }

    @Test
    fun listAnswerPinsTheSessionsOwnPromptOverTheList() {
        val m = waitingModel()
        val onAlphaWaiting = HaloNavState(page = 0).drillToList(m).step(+1, m)
        assertEquals("s-a2", onAlphaWaiting.sessionId)

        val card = onAlphaWaiting.openCardForListSession(m.sessions.single { it.id == "s-a2" })
        // OVER the list — not a feed drill — pinned to THIS session's prompt
        // even though the global queue front is beta's.
        assertEquals(HaloDepth.LIST, card.depth)
        assertTrue(card.cardOpen)
        assertEquals("p-alpha", card.cardPermissionId)
        assertEquals("s-a2", card.sessionId)

        // "Decide later" (back) lands right back on the same pager card.
        val later = card.back()
        assertEquals(HaloDepth.LIST, later.depth)
        assertEquals("s-a2", later.sessionId)
        assertFalse(later.cardOpen)
    }

    @Test
    fun listAnswerWithoutAPendingPromptIsANoOp() {
        val m = waitingModel()
        val onIdle = HaloNavState(page = 0).drillToList(m)
        assertEquals("s-a1", onIdle.sessionId)
        // No prompt to pin: opening anyway would fall back to the global
        // front and float beta's prompt over alpha's pager card.
        assertEquals(onIdle, onIdle.openCardForListSession(m.sessions.single { it.id == "s-a1" }))
    }

    // ── The LIST-depth self-heal (Halo v2 S5, #99) ───────────────────────────
    // step/atListStart deliberately dead-end on a vanished selection and
    // back-from-feed parks the dead id at LIST depth; the pager heals it
    // before rendering — these pin what the heal may and may not touch.

    @Test
    fun healReselectsTheRememberedIndexNeighbourWhenTheSelectionVanishes() {
        // The selection sat at slot 1 and died: the session NOW at slot 1 is
        // its next-door neighbour, and that is where the heal must land.
        val shrunk = HaloModel(
            projects = listOf(
                HaloProject("alpha", listOf(session("s-a1", "alpha"))),
                HaloProject("beta", listOf(session("s-b1", "beta"))),
            ),
            sessions = listOf(session("s-a1", "alpha"), session("s-b1", "beta")),
            queue = emptyList(),
        )
        val stale = HaloNavState(depth = HaloDepth.LIST, sessionId = "s-gone")
        val healed = stale.healListSelection(shrunk, 1)
        assertEquals(HaloDepth.LIST, healed.depth)
        assertEquals("s-b1", healed.sessionId)
        // The END was killed: the remembered index clamps to the new last.
        assertEquals("s-b1", stale.healListSelection(shrunk, 5).sessionId)
        assertEquals("s-a1", stale.healListSelection(shrunk, 0).sessionId)
    }

    @Test
    fun healLandsOnTheSpawnCardWhenTheAllScopeEmpties() {
        val stale = HaloNavState(depth = HaloDepth.LIST, sessionId = "s-gone")
        val healed = stale.healListSelection(emptyModel, 0)
        // The spawn card — All's sole remaining slot — not a back-out: the
        // user was IN the list and the list still has something to show.
        assertEquals(HaloDepth.LIST, healed.depth)
        assertNull(healed.sessionId)
    }

    @Test
    fun healBacksOutOfAnEmptiedProjectScope() {
        // A project scope with no sessions has NO slots (no spawn card
        // outside All): nothing to select, so the heal backs all the way out.
        val stale = HaloNavState(
            depth = HaloDepth.LIST,
            listScope = ListScope.Project("gone"),
            sessionId = "s-gone",
        )
        val healed = stale.healListSelection(model(), 0)
        assertEquals(HaloDepth.PAGE, healed.depth)
        assertNull(healed.sessionId)
    }

    @Test
    fun healPassesThroughEverythingThatIsNotAVanishedListSelection() {
        val m = model()
        // An in-scope selection needs no repair.
        val fine = HaloNavState(page = 0).drillToList(m)
        assertEquals(fine, fine.healListSelection(m, 3))
        // The spawn card's null selection is not a vanished session.
        val spawn = fine.copy(sessionId = null)
        assertEquals(spawn, spawn.healListSelection(m, 0))
        // Other depths own their own vanish handling (the feed backs out).
        val feed = HaloNavState(depth = HaloDepth.SESSION, sessionId = "s-gone")
        assertEquals(feed, feed.healListSelection(m, 0))
    }

    @Test
    fun openFirstWaitingScopesHomeToTheGlobalFrontAndAProjectToItsOwn() {
        val m = waitingModel()
        // Home Answer = the global queue FRONT (beta's), not list order.
        val home = HaloNavState(page = 0).openFirstWaiting(m)
        assertEquals(HaloDepth.SESSION, home.depth)
        assertEquals("s-b1", home.sessionId)
        assertEquals("p-beta", home.cardPermissionId)

        // Project Answer = that project's first waiting item, pinned to ITS
        // prompt even though the global front belongs elsewhere.
        val project = HaloNavState(page = 1).openFirstWaiting(m)
        assertEquals("s-a2", project.sessionId)
        assertEquals("p-alpha", project.cardPermissionId)
    }
}
