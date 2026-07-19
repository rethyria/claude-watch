package dev.claudewatch.wear.glance

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The REAL HaloTileService.onTileRequest, driven directly (issue #28).
 *
 * Proto-tree assertions, not screenshots — a deliberate deviation from the
 * issue's "adb/screenshot" wording, noted here: the tile carousel is not
 * automatable on the e2e image (no launcher swipe surface in the headless
 * emulator, and TileService is bound by the system UI we don't control), and
 * a screenshot of a renderer we don't own would test the RENDERER. The
 * load-bearing acceptance is honesty — what the tile SAYS in each state —
 * and the layout proto IS what it says; the platform renderer drawing protos
 * faithfully is the platform's contract, not ours.
 *
 * The state seam: [GlanceStateSource.resolver] (the #25 viewModelResolver
 * pattern) — overridden per test with a fixed GlanceStatus, restored in
 * @After so the production peek-derived default leaks into no other class.
 */
@RunWith(AndroidJUnit4::class)
class HaloTileServiceTest {

    /** Doorway to the protected onTileRequest/attachBaseContext — runs THIS
     *  repo's real implementation, overrides nothing. */
    private class TestableTileService : HaloTileService() {
        fun attach(context: Context) = attachBaseContext(context)
        fun request(params: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
            onTileRequest(params)
    }

    private lateinit var context: Context
    private lateinit var service: TestableTileService
    private lateinit var defaultResolver: () -> GlanceStatus

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        defaultResolver = GlanceStateSource.resolver
        service = TestableTileService().also { it.attach(context) }
    }

    @After
    fun restoreResolver() {
        GlanceStateSource.resolver = defaultResolver
    }

    private fun requestTile(): TileBuilders.Tile =
        service.request(RequestBuilders.TileRequest.Builder().build()).get()

    /** Every Text node's literal content, walked from the tile's proto tree. */
    private fun tileTexts(tile: TileBuilders.Tile): List<String> {
        val entries = tile.tileTimeline?.timelineEntries.orEmpty()
        assertTrue("the tile must carry at least one timeline entry", entries.isNotEmpty())
        return entries.flatMap { texts(it.layout?.root) }
    }

    private fun texts(element: LayoutElementBuilders.LayoutElement?): List<String> = when (element) {
        is LayoutElementBuilders.Text -> listOfNotNull(element.text?.value)
        is LayoutElementBuilders.Box -> element.contents.flatMap { texts(it) }
        is LayoutElementBuilders.Column -> element.contents.flatMap { texts(it) }
        is LayoutElementBuilders.Row -> element.contents.flatMap { texts(it) }
        else -> emptyList()
    }

    // ------------------------------------------------------------------
    // (a) Connected fixture → the census text is IN the layout proto.
    // ------------------------------------------------------------------

    @Test
    fun connectedFixtureRendersTheCensusTexts() {
        GlanceStateSource.resolver = {
            GlanceStatus(healthy = true, statusText = "2 sessions", detailText = "1 project", shortText = "2 sess")
        }
        val texts = tileTexts(requestTile())
        assertTrue("headline missing from $texts", texts.contains("2 sessions"))
        assertTrue("detail missing from $texts", texts.contains("1 project"))
    }

    // ------------------------------------------------------------------
    // (b) Disconnected fixture → the tile SAYS disconnected (the honesty
    // acceptance; the null-peek row uses these exact strings).
    // ------------------------------------------------------------------

    @Test
    fun disconnectedFixtureRendersDisconnected() {
        GlanceStateSource.resolver = { glanceStatus(null, null) }
        val texts = tileTexts(requestTile())
        assertTrue("honesty: disconnected must be spelled out, got $texts", texts.contains("disconnected"))
        assertTrue("the tap affordance line, got $texts", texts.contains("tap to open"))
    }

    // ------------------------------------------------------------------
    // Freshness + the tap-through-to-app clickable.
    // ------------------------------------------------------------------

    @Test
    fun freshnessIntervalAndLaunchClickableAreSet() {
        GlanceStateSource.resolver = { glanceStatus(null, null) }
        val tile = requestTile()
        assertEquals(HaloTileService.FRESHNESS_INTERVAL_MS, tile.freshnessIntervalMillis)

        // The root Box carries the one clickable, and it launches an
        // activity (MainActivity) rather than doing nothing or loading state.
        val root = tile.tileTimeline?.timelineEntries?.first()?.layout?.root
        val box = root as? LayoutElementBuilders.Box
            ?: throw AssertionError("root element is not the clickable Box: $root")
        val clickable = box.modifiers?.clickable
        assertNotNull("the tile must be tappable in every state", clickable)
        assertEquals(TILE_CLICK_OPEN_APP, clickable!!.id)
        val action = clickable.onClick as? ActionBuilders.LaunchAction
            ?: throw AssertionError("tap must be a LaunchAction, got ${clickable.onClick}")
        assertEquals(
            "dev.claudewatch.wear.MainActivity",
            action.androidActivity?.className,
        )
    }
}
