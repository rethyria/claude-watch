// The one push entry point for both glanceables (issue #28).
package dev.claudewatch.wear.glance

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/**
 * Ask the platform to re-request the Tile layout and the complication data.
 *
 * Called from exactly three places, all in BridgeSessionService:
 *  - the glance collector, on a distinctUntilChanged CHANGE of the derived
 *    GlanceStatus (never on every state emission — see the collector's
 *    comment for the ~30 s platform floor arithmetic);
 *  - onCreate — the service (and therefore the stream's keeper) is back, so
 *    whatever the glanceables froze on while the process was gone is stale
 *    NOW;
 *  - onDestroy — the mirror image, and the exact watchOS bug this issue
 *    kills: the service dying (user Disconnect, terminal state, system kill)
 *    must flip the glanceables to "disconnected" instead of leaving them
 *    frozen on the last healthy render. The re-request lands on
 *    peekGlanceStatus, which reads the terminal connection state (or a null
 *    peek in a fresh process) and says so.
 *
 * Both requests are fire-and-forget broadcasts; when no tile is in the
 * carousel / no complication on the face, the platform drops them — cheap
 * enough that we never track "is anyone listening".
 */
fun requestGlanceRefresh(context: Context) {
    TileService.getUpdater(context).requestUpdate(HaloTileService::class.java)
    ComplicationDataSourceUpdateRequester
        .create(context, ComponentName(context, HaloComplicationService::class.java))
        .requestUpdateAll()
}
