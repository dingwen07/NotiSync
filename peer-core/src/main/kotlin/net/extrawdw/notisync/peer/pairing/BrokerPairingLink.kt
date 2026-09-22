package net.extrawdw.notisync.peer.pairing

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.Base64
import net.extrawdw.notisync.protocol.BrokerPairing

data class BrokerPairingLink(
    val sessionId: String,
    val secret: String,
    val hostId: String,
    val brokerUrl: String = BrokerPairing.DEFAULT_BROKER,
) {
    init {
        require(hostId.matches(Regex("[a-z2-7]{32}"))) { "Invalid host identity" }
        require(validSecret(secret)) { "Invalid pairing secret" }
        require(BrokerPairing.validSessionId(sessionId)) { "Invalid pairing session" }
        require(brokerUrl == normalizeBroker(brokerUrl)) { "Noncanonical pairing broker" }
    }

    // The optical secret stays in the fragment, absent from HTTP requests and relay frames.
    fun encode(): String = "https://${PairingDeepLinks.HTTPS_HOST}/pair?pair=1.$sessionId" +
        (if (brokerUrl == BrokerPairing.DEFAULT_BROKER) "" else "&b=${URLEncoder.encode(brokerUrl, UTF_8)}") +
        "#i=$hostId&k=$secret"

    override fun toString(): String = "BrokerPairingLink(sessionId=$sessionId, brokerUrl=$brokerUrl, hostId=$hostId, secret=[redacted])"

    companion object {
        fun generateSecret(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

        fun validSecret(secret: String): Boolean = runCatching {
            secret.matches(Regex("[A-Za-z0-9_-]{43}")) &&
                Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(secret)) == secret
        }.getOrDefault(false)

        fun parse(input: String?): BrokerPairingLink? = runCatching {
            require(input != null && input.length <= 2048)
            val uri = URI(input.trim())
            require(uri.rawUserInfo == null && uri.port == -1)
            require(
                (uri.scheme == "https" && uri.host == PairingDeepLinks.HTTPS_HOST && uri.path in setOf("/pair", "/pair/")) ||
                    (uri.scheme == "notisync" && uri.host == "pair" && uri.path.orEmpty().isEmpty())
            )
            val params = parameters(uri.rawQuery ?: error("Missing pairing session"))
            require(params.keys.all { it in setOf("pair", "b") })
            val marker = params.getValue("pair")
            require(marker.startsWith("1.")) { "Unsupported pairing version" }
            val fragment = parameters(uri.rawFragment ?: error("Missing pairing secret"))
            require(fragment.keys == setOf("i", "k"))
            BrokerPairingLink(marker.removePrefix("1."), fragment.getValue("k"), fragment.getValue("i"),
                normalizeBroker(params["b"] ?: BrokerPairing.DEFAULT_BROKER))
        }.getOrNull()

        private fun parameters(value: String): Map<String, String> {
            val pairs = value.split('&').map {
                val pair = it.split('=', limit = 2)
                require(pair.size == 2)
                pair[0] to URLDecoder.decode(pair[1], UTF_8)
            }
            require(pairs.map { it.first }.distinct().size == pairs.size)
            return pairs.toMap()
        }

        fun normalizeBroker(input: String): String {
            require(input.length <= 512)
            val uri = URI(input.trim().trimEnd('/'))
            require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Invalid pairing broker URL"
            }
            require(uri.scheme in setOf("https", "wss") ||
                (uri.scheme in setOf("http", "ws") && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))) {
                "Pairing requires HTTPS (HTTP is allowed only on loopback)"
            }
            require(uri.rawPath.orEmpty().split('/').none { it == "." || it == ".." })
            val scheme = if (uri.scheme in setOf("https", "wss")) "https" else "http"
            return "$scheme://${uri.rawAuthority}${uri.rawPath.orEmpty()}"
        }
    }
}
