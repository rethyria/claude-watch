package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The usage page LEFT of home (issue #57), driven with fixture UiStates and a
 * RECORDED onUsageOpen action seam — no bridge, no network. Swipe right from
 * home lands on usage and fires the fetch seam (on EVERY entry — fetch-on-open
 * is the whole caching policy); swipe left returns home; the bars render from
 * injected Data (including the "as of ..." freshness label once the data is
 * MORE THAN A MINUTE old — cache and live api alike — absent under that,
 * and tappable: the tap fires the recorded onUsageRefresh force seam); the
 * eyebrow toggles the REMAINING/USED reading of a known wire percent; Error
 * renders the message with a tappable retry that re-fires the seam; and the
 * page has no drill-down — swipe up stays put.
 */
@RunWith(AndroidJUnit4::class)
class HaloUsagePageTest {

    @get:Rule
    val compose = createComposeRule()

    // The REMAINING/USED choice persists in SharedPreferences now — reset it
    // per test, or the toggle test's USED choice leaks into every later test
    // that asserts the REMAINING default (order-dependent flakiness).
    @org.junit.Before
    fun resetPersistedUsageMode() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("halo_ui", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun fixtureBridge() = BridgeState(
        sessions = mapOf(
            "s-1" to SessionState(
                sessionId = "s-1",
                agent = "claude",
                cwd = "/home/dev/alpha",
                folderName = "alpha",
            ),
        ),
    )

    private fun ui(usage: BridgeViewModel.UsageUi) = BridgeViewModel.UiState(
        status = "paired, stream open",
        paired = true,
        bridge = fixtureBridge(),
        usage = usage,
    )

    private fun fixtureData(source: String = "api", fetchedAtMs: Long = System.currentTimeMillis()) =
        BridgeViewModel.UsageUi.Data(
            limits = listOf(
                BridgeViewModel.UsageLimit("session", "5-hour", 37.5, "2026-07-18T19:10:00Z"),
                BridgeViewModel.UsageLimit("weekly_all", "weekly", 80.0, "2026-07-24T00:00:00Z"),
                BridgeViewModel.UsageLimit("weekly_scoped", "Fable", 12.0, "2026-07-24T00:00:00Z"),
            ),
            source = source,
            fetchedAtMs = fetchedAtMs,
        )

    /**
     * Pager swipes as a real finger drags them: frame-by-frame moves (the
     * same injection discipline as the swipe-threshold gestures — a batched
     * swipe collapses into one frame-sized delta and can behave nothing like
     * a finger on API 31+ surfaces). ~0.8 of the width at fling speed settles
     * the HorizontalPager one page over.
     */
    private fun swipeToUsage() {
        compose.onNodeWithTag("haloRoot").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(width / 12f, 0f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
    }

    private fun swipeBackHome() {
        compose.onNodeWithTag("haloRoot").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-width / 12f, 0f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun swipeRightLandsOnUsageFetchesOnEveryEntryAndSwipesBackHome() {
        var opens = 0
        var state by mutableStateOf(ui(BridgeViewModel.UsageUi.Loading))
        compose.setContent {
            HaloApp(ui = state, actions = HaloActions(onUsageOpen = { opens++ }))
        }
        // Home is the initial page (the census inside the merged centerpiece).
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("home entry never fetches", 0, opens)

        swipeToUsage()
        compose.onNodeWithTag("haloUsage").assertIsDisplayed()
        compose.onNodeWithTag("haloUsageLoading").assertIsDisplayed()
        assertEquals("landing on usage fires the fetch seam", 1, opens)

        // The fetch lands: one bar per wire entry, with the presentation-only
        // display names (session → "Session", weekly_all → "Weekly", any
        // other kind keeps its wire label) per the Halo usage design.
        state = state.copy(usage = fixtureData())
        compose.waitForIdle()
        compose.onNodeWithText("Session").assertIsDisplayed()
        compose.onNodeWithText("Weekly").assertIsDisplayed()
        compose.onNodeWithText("Fable").assertIsDisplayed()

        swipeBackHome()
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()

        // Re-entry re-fetches: fetch-on-open, no client cache.
        swipeToUsage()
        compose.onNodeWithTag("haloUsage").assertIsDisplayed()
        assertEquals("every entry re-fetches", 2, opens)
    }

    @Test
    fun tappingTheEyebrowFlipsRemainingToUsed() {
        // One window with a KNOWN wire (USED) percent: 28 used → the default
        // REMAINING mode shows 72%; tapping the eyebrow flips the reading to
        // USED and the same wire renders 28%. Screen-local state — no wire
        // round-trip involved.
        compose.setContent {
            HaloApp(
                ui = ui(
                    BridgeViewModel.UsageUi.Data(
                        limits = listOf(
                            BridgeViewModel.UsageLimit(
                                "session", "5-hour", 28.0, "2026-07-18T19:10:00Z",
                            ),
                        ),
                        source = "api",
                        fetchedAtMs = System.currentTimeMillis(),
                    ),
                ),
                actions = HaloActions(),
            )
        }
        swipeToUsage()
        compose.onNodeWithText("REMAINING").assertIsDisplayed()
        compose.onNodeWithText("72%").assertIsDisplayed()

        compose.onNodeWithTag("haloUsageMode").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("USED").assertIsDisplayed()
        compose.onNodeWithText("28%").assertIsDisplayed()
        assertEquals(
            "the remaining reading is gone after the flip",
            0,
            compose.onAllNodes(hasText("72%")).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun cacheSourceRendersTheStalenessLine() {
        val fiveMinutesAgo = System.currentTimeMillis() - 5 * 60_000L
        compose.setContent {
            HaloApp(
                ui = ui(fixtureData(source = "cache", fetchedAtMs = fiveMinutesAgo)),
                actions = HaloActions(),
            )
        }
        swipeToUsage()
        compose.onNodeWithTag("haloUsage").assertIsDisplayed()
        // The bridge served stale cache: the always-on freshness label says
        // how old the bars are instead of pretending they are live. (The tag
        // keeps its historical name — the label was once cache-only.)
        compose.onNodeWithTag("haloUsageStale").assertIsDisplayed()
        compose.onNode(hasText("as of", substring = true)).assertIsDisplayed()
    }

    @Test
    fun apiSourceRendersTheUpdatedLabelOncePastAMinute() {
        // 2026-07-18 refinements: the freshness label is not cache-only — a
        // live api result older than a minute renders it too. 90s old keeps
        // the assertion stable ("as of 1 minute ago" holds until the age
        // crosses 2 minutes — far beyond any test runtime).
        compose.setContent {
            HaloApp(
                ui = ui(fixtureData(source = "api", fetchedAtMs = System.currentTimeMillis() - 90_000L)),
                actions = HaloActions(),
            )
        }
        swipeToUsage()
        compose.onNodeWithTag("haloUsage").assertIsDisplayed()
        compose.onNodeWithTag("haloUsageStale").assertIsDisplayed()
        compose.onNode(hasText("as of 1 minute ago")).assertIsDisplayed()
    }

    @Test
    fun freshDataUnderAMinuteRendersNoUpdatedLabel() {
        // Under a minute the label DIES (2026-07-18): fresh bars need no
        // caveat — the tag itself is absent, not just an empty string.
        compose.setContent {
            HaloApp(
                ui = ui(fixtureData(source = "api", fetchedAtMs = System.currentTimeMillis())),
                actions = HaloActions(),
            )
        }
        swipeToUsage()
        compose.onNodeWithTag("haloUsage").assertIsDisplayed()
        compose.onNodeWithTag("haloUsageStale").assertDoesNotExist()
    }

    @Test
    fun tappingTheUpdatedLabelFiresTheForcedRefreshSeam() {
        // The freshness label doubles as the manual-refresh affordance: the
        // tap fires onUsageRefresh (wired to fetchUsage(force = true) in
        // MainActivity), NOT the non-forced onUsageOpen entry seam.
        var refreshes = 0
        var opens = 0
        compose.setContent {
            HaloApp(
                ui = ui(fixtureData(source = "api", fetchedAtMs = System.currentTimeMillis() - 5 * 60_000L)),
                actions = HaloActions(
                    onUsageOpen = { opens++ },
                    onUsageRefresh = { refreshes++ },
                ),
            )
        }
        swipeToUsage()
        val entryOpens = opens // the page entry fired the non-forced seam
        compose.onNodeWithTag("haloUsageStale").assertIsDisplayed()
        compose.onNodeWithTag("haloUsageStale").performClick()
        compose.waitForIdle()
        assertEquals("tapping the as-of label fires the forced refresh", 1, refreshes)
        assertEquals("the tap never doubles as a page entry", entryOpens, opens)
    }

    @Test
    fun errorRendersTheMessageWithATappableRetryThatRefetches() {
        var opens = 0
        compose.setContent {
            HaloApp(
                ui = ui(BridgeViewModel.UsageUi.Error("usage unavailable: no credentials")),
                actions = HaloActions(onUsageOpen = { opens++ }),
            )
        }
        swipeToUsage()
        assertEquals(1, opens)
        compose.onNodeWithText("usage unavailable: no credentials").assertIsDisplayed()

        // Retry re-fires the SAME seam the page entry does.
        compose.onNodeWithTag("haloUsageRetry").performClick()
        compose.waitForIdle()
        assertEquals("retry re-calls onUsageOpen", 2, opens)
    }

    @Test
    fun theUsagePageHasNoDrillDown() {
        compose.setContent {
            HaloApp(ui = ui(fixtureData()), actions = HaloActions())
        }
        swipeToUsage()
        compose.onNodeWithTag("haloUsage").assertIsDisplayed()

        // Swipe up — the drill gesture — must be a no-op here (HaloNav's
        // usage-page guard): still the usage page, no session list.
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloUsage").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodes(hasText("all sessions")).fetchSemanticsNodes().size,
        )
    }
}
