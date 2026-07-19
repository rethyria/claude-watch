// The Halo Tile (issue #28): one swipe from the watch face, the connection's
// honest status + the session census. Rendering is ProtoLayout — the layout
// is DATA handed to the system renderer, not our code running — which is why
// the instrumented test asserts on the proto tree instead of screenshots.
package dev.claudewatch.wear.glance

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.claudewatch.wear.MainActivity
import dev.claudewatch.wear.ui.halo.Halo

/**
 * The Tile: status headline + detail line, centered, accented by stream
 * health, one tap anywhere → MainActivity.
 *
 * PASSIVITY is the load-bearing property of this class. The carousel calls
 * [onTileRequest] whenever the user swipes PAST the tile — that must never
 * start the engine, so the state read goes through [GlanceStateSource] →
 * [peekGlanceStatus] → [dev.claudewatch.wear.BridgeViewModel.peek], which
 * returns null rather than constructing. A null peek renders as honest
 * "disconnected / tap to open". The tile never has an opinion the engine
 * didn't earn: no state is cached here, every request re-derives.
 *
 * Freshness: [FRESHNESS_INTERVAL_MS] (~60 s) asks the platform to re-request
 * the layout roughly every minute WHILE THE TILE IS ON SCREEN — the passive
 * safety net for the case where nothing pushes (process killed, so no
 * BridgeSessionService collector is alive to notice anything). The active
 * path is the push: BridgeSessionService requests an update on every
 * GlanceStatus CHANGE and at its own birth/death (see requestGlanceRefresh),
 * which is what kills the watchOS freeze-green failure mode.
 *
 * `open` solely so the instrumented test can subclass to reach the protected
 * [onTileRequest] + attachBaseContext — the test still runs THIS class's
 * implementation (no overrides), it just needs a doorway.
 */
open class HaloTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        // Peek, derive, render — synchronously. There is nothing to await:
        // the state is a StateFlow's current value (or a null peek), and an
        // async tile that answers late renders a stale carousel frame.
        val status = GlanceStateSource.resolver()
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // The platform's re-request cadence while the tile is visible.
            // ~60 s, not lower: the platform enforces its own update floor
            // anyway, and the PUSH path handles anything urgent.
            .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(tileLayout(this, status)))
            .build()
        return CallbackToFutureAdapter.getFuture { completer ->
            completer.set(tile)
            "HaloTileService.onTileRequest"
        }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            // Text-only layout: no images, so the resource bundle is empty
            // and the version never needs to change.
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
            "HaloTileService.onTileResourcesRequest"
        }

    companion object {
        internal const val RESOURCES_VERSION = "1"
        internal const val FRESHNESS_INTERVAL_MS = 60_000L
    }
}

/**
 * The whole layout from one [GlanceStatus]: a full-bleed clickable Box
 * (LaunchAction → MainActivity — the tap works in EVERY state, not just the
 * ones whose detail line advertises it) holding a centered Column of
 * headline + detail.
 *
 * Colors are the Halo palette, not Tiles material defaults: healthy =
 * Running green (the ring's "agent alive" color), unhealthy = Error red (the
 * offline screen's headline color — NOT terracotta, which Halo reserves for
 * "waiting for YOU"; a dead stream is a fault, not a request). The detail
 * line stays TextSecondary in both worlds — it is a caption, never the
 * signal.
 *
 * Top-level (not a service method) so the healthy/unhealthy accent choice is
 * visible to tests without a service instance.
 */
internal fun tileLayout(context: Context, status: GlanceStatus): LayoutElementBuilders.LayoutElement {
    val accent = if (status.healthy) Halo.Palette.Running else Halo.Palette.Error
    val openApp = ModifiersBuilders.Clickable.Builder()
        .setId(TILE_CLICK_OPEN_APP)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(context.packageName)
                        .setClassName(MainActivity::class.java.name)
                        .build(),
                )
                .build(),
        )
        .build()
    return LayoutElementBuilders.Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openApp).build())
        .addContent(
            LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(
                    Text.Builder(context, status.statusText)
                        .setTypography(Typography.TYPOGRAPHY_TITLE3)
                        .setColor(argb(accent.toArgb()))
                        .build(),
                )
                .addContent(
                    LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build(),
                )
                .addContent(
                    Text.Builder(context, status.detailText)
                        .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                        .setColor(argb(Halo.Palette.TextSecondary.toArgb()))
                        .build(),
                )
                .build(),
        )
        .build()
}

internal const val TILE_CLICK_OPEN_APP = "open-app"
