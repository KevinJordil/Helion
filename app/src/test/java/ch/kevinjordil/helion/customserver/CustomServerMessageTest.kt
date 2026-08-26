package ch.kevinjordil.helion.customserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sanitizeServerMessage] and [formatServerDetail] are what stands between arbitrary text
 * from the owner's own server and the UI it gets rendered into -- this exercises the
 * defensive rules directly (trimming, the length cap, control characters, blank bodies)
 * that [CustomServerPublisherTest] then only has to check the end-to-end wiring of.
 */
class CustomServerMessageTest {

    @Test
    fun `a plain message passes through unchanged`() {
        assertEquals("Activité reçue.", sanitizeServerMessage("Activité reçue."))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Activité reçue.", sanitizeServerMessage("  Activité reçue.  \n"))
    }

    @Test
    fun `an empty body sanitizes to null, not an empty string`() {
        assertNull(sanitizeServerMessage(""))
    }

    @Test
    fun `a body that is only whitespace sanitizes to null`() {
        assertNull(sanitizeServerMessage("   \n\t  "))
    }

    @Test
    fun `a body that is only control bytes sanitizes to null`() {
        assertNull(sanitizeServerMessage("\u0000\u0001\u0002"))
    }

    @Test
    fun `control characters other than newline and tab are stripped, never reaching the UI`() {
        val withStrayControlBytes = "Activit\u0000\u0007 reçue."
        val cleaned = sanitizeServerMessage(withStrayControlBytes)
        assertEquals("Activit reçue.", cleaned)
    }

    @Test
    fun `newlines and tabs are kept -- they wrap, they do not break the layout`() {
        val message = "Ligne un\nLigne deux\tavec tabulation"
        assertEquals(message, sanitizeServerMessage(message))
    }

    @Test
    fun `a very long body -- a stray HTML error page, say -- is capped, not shown in full`() {
        val hugeBody = "x".repeat(10_000)

        val cleaned = sanitizeServerMessage(hugeBody)!!

        assertTrue("expected the cleaned message to be capped well under the raw body's length", cleaned.length < 600)
        assertTrue("expected a truncation marker so a capped message never reads as complete", cleaned.endsWith("…"))
    }

    @Test
    fun `a body just at the cap is not marked truncated`() {
        val exactlyAtCap = "y".repeat(500)
        assertEquals(exactlyAtCap, sanitizeServerMessage(exactlyAtCap))
    }

    @Test
    fun `formatServerDetail keeps the HTTP status alongside a real message`() {
        assertEquals(
            "HTTP 401: Jeton refusé.",
            formatServerDetail(401, "Jeton refusé."),
        )
    }

    @Test
    fun `formatServerDetail falls back to the bare status when the body has nothing to show`() {
        assertEquals("HTTP 500", formatServerDetail(500, ""))
        assertEquals("HTTP 500", formatServerDetail(500, "   "))
    }
}
