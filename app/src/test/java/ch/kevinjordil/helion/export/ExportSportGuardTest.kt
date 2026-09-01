package ch.kevinjordil.helion.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityOrigin
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.SportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Downloads and share export paths refuse an activity with no sport set, the same
 * refusal [ch.kevinjordil.helion.customserver.CustomServerPublisherTest] and
 * [ch.kevinjordil.helion.healthconnect.HealthConnectExporterTest] already cover for the
 * other two export targets -- see [Activity.sport]'s own kdoc for why a refusal, not a
 * guessed or generic fallback, is the one consistent behaviour across all three.
 */
@RunWith(RobolectricTestRunner::class)
class ExportSportGuardTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun activity(sport: SportType?) = Activity(
        id = 1,
        startTimestamp = 1_000,
        endTimestamp = 2_000,
        sport = sport,
        title = "Séance repérée",
        notes = null,
        origin = ActivityOrigin.DETECTED,
        status = ActivityStatus.CONFIRMED,
    )

    @Test
    fun `saving to Downloads is refused with SportMissing when the activity has no sport`() {
        val result = saveTcxToDownloads(context, activity(sport = null), samples = emptyList(), calories = null)
        assertEquals(DownloadsSaveResult.SportMissing, result)
    }

    @Test
    fun `saving to Downloads is not blocked by the sport guard once a sport is set`() {
        // Whether the write itself succeeds under Robolectric's MediaStore shadow is not
        // this test's concern (see saveViaMediaStore); what matters here is that a
        // sport-bearing activity is never turned away by the same guard that refuses one
        // with no sport -- SportMissing is the one outcome that must never appear.
        val result = saveTcxToDownloads(context, activity(sport = SportType.BADMINTON), samples = emptyList(), calories = null)
        assertEquals(false, result is DownloadsSaveResult.SportMissing)
    }

    @Test
    fun `sharing is refused with a null intent when the activity has no sport`() {
        val intent = buildShareIntent(context, activity(sport = null), samples = emptyList())
        assertNull(intent)
    }

    @Test
    fun `sharing produces an intent once a sport is set`() {
        val intent = buildShareIntent(context, activity(sport = SportType.BADMINTON), samples = emptyList())
        assertEquals(false, intent == null)
    }
}
