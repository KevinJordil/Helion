package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.settings.SyncOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshBannerTest {

    @Test
    fun `a failed pass is an attention banner`() {
        val banner = refreshBanner(SyncOutcome.Failed("boom"))
        assertTrue(banner.isAttention)
        assertEquals(R.string.sync_result_failed, banner.messageRes)
        assertEquals(listOf("boom"), banner.args)
    }

    @Test
    fun `an unreadable configured file is an attention banner`() {
        val banner = refreshBanner(SyncOutcome.Unavailable("moved"))
        assertTrue(banner.isAttention)
    }

    @Test
    fun `a triggered success is not an attention banner`() {
        val banner = refreshBanner(SyncOutcome.Ingested(minutes = 5, points = 7))
        assertFalse(banner.isAttention)
    }

    @Test
    fun `a degraded-but-working pass is stated plainly, not as an attention banner`() {
        // "a degraded-yet-working sync is not an error and must not look alarming" --
        // refreshTriggered == false means Gadgetbridge could not be confirmed fresh, but
        // the data itself is still good.
        val banner = refreshBanner(SyncOutcome.Ingested(minutes = 5, points = 7, refreshTriggered = false))
        assertFalse(banner.isAttention)
        assertEquals(R.string.sync_result_success_stale, banner.messageRes)
    }

    @Test
    fun `phase labels exist for the in-progress phases and not for DONE`() {
        assertEquals(R.string.refresh_phase_syncing, refreshPhaseLabel(RefreshPhase.SYNCING))
        assertEquals(R.string.refresh_phase_reading, refreshPhaseLabel(RefreshPhase.READING))
        assertEquals(null, refreshPhaseLabel(RefreshPhase.DONE))
    }
}
