package ch.kevinjordil.helion.customserver

import java.net.MalformedURLException
import java.net.URL

/**
 * What [validateCustomServerUrl] found about a configured server URL -- enough to catch an
 * obvious typo before ever attempting a send, and to know whether the plain-HTTP
 * confirmation gate applies.
 */
sealed class CustomServerUrlValidation {

    /** A well-formed `http(s)://host[...]` URL. [isPlainHttp] is what the send path gates on. */
    data class Valid(val isPlainHttp: Boolean) : CustomServerUrlValidation()

    /** Empty or blank -- nothing configured yet. */
    object Blank : CustomServerUrlValidation()

    /**
     * Not a usable `http(s)://` URL: no scheme, an unsupported scheme, or no host at all
     * (`https://`, `not a url`, `ftp://example.com`, ...). This is the "obvious typo" check
     * the owner's settings screen and the send path both run before anything touches the
     * network.
     */
    object Malformed : CustomServerUrlValidation()
}

/**
 * Validates a configured custom-server URL. Deliberately shallow -- it only rules out the
 * typo-shaped mistakes a text field can catch on its own (no scheme, a typo'd scheme, a
 * scheme with nothing after it): it can never confirm the host actually answers, that is
 * what an actual send attempt is for.
 */
fun validateCustomServerUrl(raw: String): CustomServerUrlValidation {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return CustomServerUrlValidation.Blank
    val parsed = try {
        URL(trimmed)
    } catch (e: MalformedURLException) {
        return CustomServerUrlValidation.Malformed
    }
    if (parsed.protocol !in setOf("http", "https")) return CustomServerUrlValidation.Malformed
    if (parsed.host.isNullOrBlank()) return CustomServerUrlValidation.Malformed
    return CustomServerUrlValidation.Valid(isPlainHttp = parsed.protocol == "http")
}
