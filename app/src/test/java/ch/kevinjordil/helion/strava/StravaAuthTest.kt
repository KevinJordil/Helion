package ch.kevinjordil.helion.strava

import android.net.Uri
import ch.kevinjordil.helion.ui.activity.publicationFailureReasonRes
import ch.kevinjordil.helion.ui.activity.stravaAuthFailureArgs
import ch.kevinjordil.helion.ui.activity.stravaAuthFailureRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The parts of the redirect-handling fix that need no network at all: parsing the redirect
 * itself (a `code`, an `error`, or neither), Strava's own error body turned into readable
 * text, and every failure kind's mapping to a distinct French message -- in particular that
 * "never connected" and "expired" read differently, the whole point of splitting them out.
 * Never exercises [StravaAuth.exchangeCode] or [StravaAuth.validAccessToken] here: both make
 * a real network call, which this test file deliberately does not.
 */
@RunWith(RobolectricTestRunner::class)
class StravaAuthTest {

    private fun redirectUri(query: String) = Uri.parse("helion://oauth-callback?$query")

    @Test
    fun `a redirect carrying a code is parsed as Code`() {
        val redirect = parseOAuthRedirect(redirectUri("code=abc123&scope=activity:write"))
        assertEquals(OAuthRedirect.Code("abc123"), redirect)
    }

    @Test
    fun `a redirect carrying an error is parsed as Error`() {
        val redirect = parseOAuthRedirect(redirectUri("error=access_denied"))
        assertEquals(OAuthRedirect.Error("access_denied"), redirect)
    }

    @Test
    fun `a redirect carrying neither code nor error is not an OAuth result`() {
        val redirect = parseOAuthRedirect(redirectUri("state=foo"))
        assertEquals(OAuthRedirect.NotAnOAuthRedirect, redirect)
    }

    @Test
    fun `a redirect with the wrong scheme or host is not an OAuth result even with a code`() {
        assertEquals(OAuthRedirect.NotAnOAuthRedirect, parseOAuthRedirect(Uri.parse("https://oauth-callback?code=abc")))
        assertEquals(OAuthRedirect.NotAnOAuthRedirect, parseOAuthRedirect(Uri.parse("helion://somewhere-else?code=abc")))
    }

    @Test
    fun `Strava's own message and field are extracted from a structured error body`() {
        val body = """{"message":"Bad Request","errors":[{"resource":"Application","field":"client_id","code":"invalid"}]}"""
        val detail = describeStravaError(StravaHttpException(400, body, "Strava request failed with HTTP 400"))
        assertTrue("expected Strava's message in \"$detail\"", detail.contains("Bad Request"))
        assertTrue("expected the rejected field in \"$detail\"", detail.contains("client_id"))
    }

    @Test
    fun `an unparsable error body falls back to the bare HTTP status`() {
        val detail = describeStravaError(StravaHttpException(500, "not json", "Strava request failed with HTTP 500"))
        assertEquals("HTTP 500", detail)
    }

    @Test
    fun `never-connected and expired authorization produce different publication failure text`() {
        val neverConnectedRes = publicationFailureReasonRes(PublicationFailureReason.NEVER_CONNECTED)
        val expiredRes = publicationFailureReasonRes(PublicationFailureReason.AUTH_EXPIRED)
        assertNotEquals(neverConnectedRes, expiredRes)
    }

    @Test
    fun `every StravaAuthFailure kind maps to its own message`() {
        val declined = stravaAuthFailureRes(StravaAuthFailure.Declined("access_denied"))
        val rejected = stravaAuthFailureRes(StravaAuthFailure.Rejected("Bad Request"))
        val network = stravaAuthFailureRes(StravaAuthFailure.NetworkError("timeout"))
        val notConfigured = stravaAuthFailureRes(StravaAuthFailure.NotConfigured)

        assertNotEquals(declined, rejected)
        assertNotEquals(declined, network)
        assertNotEquals(rejected, network)
        assertNotEquals(declined, notConfigured)
    }

    @Test
    fun `NotConfigured carries no format args, every other failure carries its detail`() {
        assertEquals(emptyList<Any>(), stravaAuthFailureArgs(StravaAuthFailure.NotConfigured))
        assertEquals(listOf("Bad Request"), stravaAuthFailureArgs(StravaAuthFailure.Rejected("Bad Request")))
        assertEquals(listOf("access_denied"), stravaAuthFailureArgs(StravaAuthFailure.Declined("access_denied")))
        assertEquals(listOf("timeout"), stravaAuthFailureArgs(StravaAuthFailure.NetworkError("timeout")))
    }
}
