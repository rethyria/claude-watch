// The SHORT_TEXT complication (issue #28): the glance status compressed into
// a watch-face corner. Same derivation as the Tile (GlanceStateSource →
// peekGlanceStatus), same passivity rule (peek, never construct), same
// honesty rule (the short text says "off"/"recon" the moment the stream is
// down — the watchOS complication it replaces stayed green through outages
// because it read the pairing state instead of the stream).
package dev.claudewatch.wear.glance

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import dev.claudewatch.wear.MainActivity
import dev.claudewatch.wear.R

/**
 * SHORT_TEXT data source. The text is [GlanceStatus.shortText] — the ~7-char
 * vocabulary mapped in the PURE layer (GlanceModel.kt), never abbreviated
 * here: a rendering class truncating status strings is how "reconnecting"
 * becomes "reconne" on one face and "recon…" on another. The long
 * [GlanceStatus.statusText] rides along as the content description so screen
 * readers get the full word.
 *
 * Push-driven: UPDATE_PERIOD_SECONDS is 0 in the manifest (no polling — a
 * poll cadence slow enough to be allowed is too slow to be honest), and
 * BridgeSessionService's collector calls requestUpdateAll on every
 * GlanceStatus change + at service birth/death. Between pushes the shown
 * value is the last derived one, which is exactly as fresh as the system
 * allows a complication to be.
 *
 * `open` for the same one reason as HaloTileService: the instrumented test
 * subclasses to reach the protected attachBaseContext. No behavior is
 * overridden.
 */
open class HaloComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        // Only the type we declared; anything else is a contract violation
        // by the face and gets an explicit "no data", not a coerced render.
        if (request.complicationType != ComplicationType.SHORT_TEXT) {
            listener.onComplicationData(null)
            return
        }
        listener.onComplicationData(shortTextData(GlanceStateSource.resolver()))
    }

    /**
     * The editor/picker preview: a static HEALTHY sample ("2 sess"). A
     * preview is a product screenshot, not a status readout — the honesty
     * rule binds [onComplicationRequest], which never serves this fixture.
     */
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return shortTextData(
            GlanceStatus(
                healthy = true,
                statusText = "2 sessions",
                detailText = "1 project",
                shortText = "2 sess",
            ),
        )
    }

    private fun shortTextData(status: GlanceStatus): ShortTextComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(status.shortText).build(),
            contentDescription = PlainComplicationText.Builder(status.statusText).build(),
        )
            // The same ring glyph as the FGS chip: monochrome (the face tints
            // it), so one drawable serves every glance surface.
            .setMonochromaticImage(
                MonochromaticImage.Builder(
                    Icon.createWithResource(this, R.drawable.ic_bridge_chip),
                ).build(),
            )
            // Tap → the app, same affordance as the tile's full-bleed click.
            // requestCode 0 + IMMUTABLE: identical target for every tap, no
            // per-instance identity needed (the #25 requestCode war story is
            // about DISTINCT decisions; this intent carries none).
            .setTapAction(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
}
