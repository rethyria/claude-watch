package dev.claudewatch.wear.ui.halo

import dev.claudewatch.wear.ui.halo.Halo.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure HaloNav state machine around the USAGE and SETTINGS pages (issue
 * #57): usage lives at page [USAGE_PAGE] (-1) and settings at [SETTINGS_PAGE]
 * (-2), both LEFT of home and deliberately depth-less — no drill-down, no
 * centerpiece jump — while All keeps page 0 so jumpHome and every existing
 * depth path land exactly where they always did. The settings tests exercise
 * the SAME guards as usage: both are `< 0`, so the machine folds them into the
 * no-depth/All behavior for free (narrowing a guard to `== USAGE_PAGE` breaks
 * the settings cases by name).
 */
class HaloNavTest {

    private fun session(id: String, project: String, state: SessionState = SessionState.RUNNING) =
        HaloSession(id = id, title = id, projectName = project, state = state)

    private fun model(): HaloModel {
        val alpha = listOf(session("s-a1", "alpha"), session("s-a2", "alpha"))
        val beta = listOf(session("s-b1", "beta", SessionState.WAITING_PERM))
        return HaloModel(
            projects = listOf(HaloProject("alpha", alpha), HaloProject("beta", beta)),
            sessions = alpha + beta,
            queue = beta,
        )
    }

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
    fun drillToListFromHomeAndProjectPagesIsUnchanged() {
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
}
