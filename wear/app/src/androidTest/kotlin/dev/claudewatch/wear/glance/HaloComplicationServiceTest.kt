package dev.claudewatch.wear.glance

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The REAL HaloComplicationService.onComplicationRequest with a recording
 * listener (issue #28): honest SHORT_TEXT for connected and disconnected
 * fixtures, preview data present. Same resolver seam + restore discipline as
 * HaloTileServiceTest.
 */
@RunWith(AndroidJUnit4::class)
class HaloComplicationServiceTest {

    private class TestableComplicationService : HaloComplicationService() {
        fun attach(context: Context) = attachBaseContext(context)
    }

    /** Records the one onComplicationData delivery; the impl answers inline
     *  but the latch keeps this correct if that ever changes. */
    private class RecordingListener : ComplicationDataSourceService.ComplicationRequestListener {
        val delivered = CountDownLatch(1)
        var data: ComplicationData? = null

        override fun onComplicationData(complicationData: ComplicationData?) {
            data = complicationData
            delivered.countDown()
        }
    }

    private lateinit var context: Context
    private lateinit var service: TestableComplicationService
    private lateinit var defaultResolver: () -> GlanceStatus

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        defaultResolver = GlanceStateSource.resolver
        service = TestableComplicationService().also { it.attach(context) }
    }

    @After
    fun restoreResolver() {
        GlanceStateSource.resolver = defaultResolver
    }

    private fun requestShortText(): ShortTextComplicationData {
        val listener = RecordingListener()
        service.onComplicationRequest(
            ComplicationRequest(
                complicationInstanceId = 1,
                complicationType = ComplicationType.SHORT_TEXT,
                immediateResponseRequired = false,
            ),
            listener,
        )
        assertTrue("listener never answered", listener.delivered.await(5, TimeUnit.SECONDS))
        return listener.data as? ShortTextComplicationData
            ?: throw AssertionError("expected SHORT_TEXT data, got ${listener.data}")
    }

    private fun textOf(data: ShortTextComplicationData): String =
        data.text.getTextAt(context.resources, Instant.now()).toString()

    @Test
    fun connectedFixtureServesTheShortCensus() {
        GlanceStateSource.resolver = {
            GlanceStatus(healthy = true, statusText = "2 sessions", detailText = "1 project", shortText = "2 sess")
        }
        val data = requestShortText()
        assertEquals("2 sess", textOf(data))
        // The tap-through affordance and the monochrome icon ride every
        // serving — a face without them renders a dead-end number.
        assertNotNull("tap action must open the app", data.tapAction)
        assertNotNull("monochrome icon expected", data.monochromaticImage)
    }

    @Test
    fun disconnectedFixtureServesHonestOff() {
        GlanceStateSource.resolver = { glanceStatus(null, null) }
        val data = requestShortText()
        assertEquals("the watchOS stale-green bug in SHORT_TEXT form", "off", textOf(data))
        // The long word rides as the content description for screen readers.
        val description = data.contentDescription
            ?: throw AssertionError("content description must carry the long status word")
        assertEquals(
            "disconnected",
            description.getTextAt(context.resources, Instant.now()).toString(),
        )
    }

    @Test
    fun previewDataIsAStaticHealthySample() {
        // The preview must NOT go through the resolver: a picker preview is
        // a product shot, not a status readout. Poison the resolver to prove
        // the preview ignores it.
        GlanceStateSource.resolver = { glanceStatus(null, null) }
        val preview = service.getPreviewData(ComplicationType.SHORT_TEXT)
        assertNotNull("SHORT_TEXT preview data must exist for the editor", preview)
        assertEquals(
            "2 sess",
            textOf(preview as ShortTextComplicationData),
        )
    }
}
