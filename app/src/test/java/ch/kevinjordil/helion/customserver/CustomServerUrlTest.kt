package ch.kevinjordil.helion.customserver

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [validateCustomServerUrl] is the only gate between a typo'd server address and an actual
 * network attempt, and the only place that decides whether a URL is plain HTTP -- the flag
 * [CustomServerPublisher] checks before ever sending health data in clear text.
 */
class CustomServerUrlTest {

    @Test
    fun `blank input is Blank`() {
        assertEquals(CustomServerUrlValidation.Blank, validateCustomServerUrl(""))
        assertEquals(CustomServerUrlValidation.Blank, validateCustomServerUrl("   "))
    }

    @Test
    fun `a well-formed https URL is Valid and not plain HTTP`() {
        assertEquals(CustomServerUrlValidation.Valid(isPlainHttp = false), validateCustomServerUrl("https://example.com/helion"))
    }

    @Test
    fun `a well-formed http URL is Valid and flagged as plain HTTP`() {
        assertEquals(CustomServerUrlValidation.Valid(isPlainHttp = true), validateCustomServerUrl("http://192.168.1.50:8080/ingest"))
    }

    @Test
    fun `text with no scheme at all is Malformed`() {
        assertEquals(CustomServerUrlValidation.Malformed, validateCustomServerUrl("example.com/helion"))
    }

    @Test
    fun `plain garbage text is Malformed`() {
        assertEquals(CustomServerUrlValidation.Malformed, validateCustomServerUrl("not a url"))
    }

    @Test
    fun `an unsupported scheme is Malformed`() {
        assertEquals(CustomServerUrlValidation.Malformed, validateCustomServerUrl("ftp://example.com/helion"))
    }

    @Test
    fun `a scheme with no host is Malformed`() {
        assertEquals(CustomServerUrlValidation.Malformed, validateCustomServerUrl("https://"))
    }

    @Test
    fun `surrounding whitespace does not change the result`() {
        assertEquals(CustomServerUrlValidation.Valid(isPlainHttp = false), validateCustomServerUrl("  https://example.com  "))
    }
}
