package net.extrawdw.apps.notisync.data.storage.core

import java.net.URI
import java.util.Locale

/**
 * Canonical broker identity used by endpoint-revision transitions.
 *
 * A configured broker may include a meaningful base path; [BrokerClient][net.extrawdw.notisync.peer.transport.BrokerClient]
 * appends its protocol paths to that base. Preserve the raw path (including case and percent-encoding), while
 * matching the client's trailing-slash equivalence. Inputs whose interpretation may change across URI consumers are
 * rejected. Legacy ws/wss spellings map to the HTTP base used by the control plane. Canonicalization happens before
 * a Room transaction so parsing never lengthens the write lock.
 */
internal fun canonicalizeBrokerEndpoint(raw: String): String {
    val value = raw.trim()
    require(value.isNotEmpty()) { "Broker endpoint must not be blank" }
    val uri = runCatching { URI(value) }
        .getOrElse { throw IllegalArgumentException("Invalid broker endpoint", it) }
    require(!uri.isOpaque) { "Broker endpoint must be hierarchical" }

    val scheme = when (uri.scheme?.lowercase(Locale.ROOT)) {
        "http", "ws" -> "http"
        "https", "wss" -> "https"
        else -> throw IllegalArgumentException("Broker endpoint must use http, https, ws, or wss")
    }
    require(uri.userInfo == null) { "Broker endpoint must not include credentials" }
    require(uri.rawQuery == null) { "Broker endpoint must not include a query" }
    require(uri.rawFragment == null) { "Broker endpoint must not include a fragment" }
    val rawPath = uri.rawPath.orEmpty()
    require('\\' !in rawPath && !ENCODED_BACKSLASH.containsMatchIn(rawPath)) {
        "Broker endpoint path must not include a backslash"
    }
    require(rawPath.split('/').none(::isAmbiguousDotSegment)) {
        "Broker endpoint path must not include dot segments"
    }
    // BrokerClient trims every trailing slash before appending /v2/...; store the same identity so equivalent
    // spellings do not invalidate credentials or route registration. Encoded slashes are intentionally preserved.
    val canonicalPath = rawPath.trimEnd('/')

    val parsedHost = requireNotNull(uri.host) { "Broker endpoint must include a valid host" }
    val host = parsedHost.lowercase(Locale.ROOT).let { normalized ->
        if (normalized.contains(':')) normalized else normalized.removeSuffix(".")
    }
    require(host.isNotBlank()) { "Broker endpoint host must not be blank" }
    val port = when {
        uri.port == -1 -> -1
        uri.port !in 1..65_535 -> throw IllegalArgumentException("Broker endpoint port is out of range")
        scheme == "http" && uri.port == 80 -> -1
        scheme == "https" && uri.port == 443 -> -1
        else -> uri.port
    }
    val renderedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    return buildString {
        append(scheme).append("://").append(renderedHost)
        if (port != -1) append(':').append(port)
        append(canonicalPath)
    }
}

private val ENCODED_DOT = Regex("%2e", RegexOption.IGNORE_CASE)
private val ENCODED_BACKSLASH = Regex("%5c", RegexOption.IGNORE_CASE)

private fun isAmbiguousDotSegment(rawSegment: String): Boolean {
    val dotDecoded = ENCODED_DOT.replace(rawSegment, ".")
    return dotDecoded == "." || dotDecoded == ".."
}
