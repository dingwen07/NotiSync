package net.extrawdw.apps.notisync.screen

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import net.extrawdw.notisync.screen.MASTER_PSK_BYTES
import net.extrawdw.notisync.screen.ROUTING_TOKEN_BYTES
import net.extrawdw.notisync.screen.ScreenSessionProtocol
import net.extrawdw.notisync.screen.SessionDescriptor

/** Domain-separated PMK derivation for the link layer; TLS channel keys use a different KDF context. */
internal object AndroidWifiAwarePmkDeriver {
    fun derive(
        masterPsk: ByteArray,
        routingToken: ByteArray,
        descriptor: SessionDescriptor,
    ): ByteArray {
        require(masterPsk.size == MASTER_PSK_BYTES) { "master PSK must be $MASTER_PSK_BYTES bytes" }
        require(routingToken.size == ROUTING_TOKEN_BYTES) {
            "routing token must be $ROUTING_TOKEN_BYTES bytes"
        }

        val salt = MessageDigest.getInstance("SHA-256").digest(PMK_SALT_DOMAIN.encodeToByteArray())
        val context = canonicalContext(descriptor, routingToken)
        val prk = hmac(salt, masterPsk)
        return try {
            // RFC 5869 expand for one SHA-256 block. The PMK is always exactly 32 bytes.
            hmac(prk, context + byteArrayOf(1))
        } finally {
            salt.fill(0)
            context.fill(0)
            prk.fill(0)
        }
    }

    private fun canonicalContext(
        descriptor: SessionDescriptor,
        routingToken: ByteArray,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeLengthPrefixedUtf8(PMK_INFO_DOMAIN)
            output.writeInt(ScreenSessionProtocol.VERSION)
            output.writeLengthPrefixedUtf8(descriptor.sessionId)
            output.writeLengthPrefixedUtf8(descriptor.sourcePeerId)
            output.writeLengthPrefixedUtf8(descriptor.requesterPeerId)
            output.writeLong(descriptor.issuedAtEpochMillis)
            output.writeLong(descriptor.expiresAtEpochMillis)
            output.writeLengthPrefixedUtf8(descriptor.codec)
            output.writeBoolean(descriptor.controlEnabled)
            output.writeBoolean(descriptor.clipboardEnabled)
            output.writeInt(descriptor.maxDimension)
            output.writeInt(descriptor.maxFps)
            output.writeInt(descriptor.videoBitrateBps)
            output.writeInt(routingToken.size)
            output.write(routingToken)
        }
        bytes.toByteArray()
    }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }

    private const val PMK_SALT_DOMAIN = "notisync-screen/wifi-aware/pmk/extract/v1"
    private const val PMK_INFO_DOMAIN = "notisync-screen/wifi-aware/pmk/expand/v1"
}

internal object AndroidWifiAwareServiceNames {
    fun random(random: SecureRandom = SecureRandom()): String =
        fromEntropy(ByteArray(SERVICE_ENTROPY_BYTES).also(random::nextBytes))

    fun fromEntropy(entropy: ByteArray): String {
        require(entropy.size >= SERVICE_ENTROPY_BYTES) {
            "Wi-Fi Aware service entropy must be at least $SERVICE_ENTROPY_BYTES bytes"
        }
        return buildString(SERVICE_PREFIX.length + SERVICE_ENTROPY_BYTES * 2) {
            append(SERVICE_PREFIX)
            for (byte in entropy.take(SERVICE_ENTROPY_BYTES)) {
                append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
                append(HEX_DIGITS[byte.toInt() and 0x0f])
            }
        }
    }

    fun isValid(value: String): Boolean =
        value.length == SERVICE_PREFIX.length + SERVICE_ENTROPY_BYTES * 2 &&
            value.startsWith(SERVICE_PREFIX) &&
            value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    private const val SERVICE_PREFIX = "notisync-screen-"
    private const val SERVICE_ENTROPY_BYTES = 16
    private const val HEX_DIGITS = "0123456789abcdef"
}

internal enum class AndroidWifiAwareEndpointDecision { WAIT, REJECT, ACCEPT }

/** Pure checks shared by callback code and local JVM tests. */
internal object AndroidWifiAwareEndpointPolicy {
    const val TCP_PROTOCOL_NUMBER = 6

    fun validSignedCandidate(serviceName: String?, port: Int?): Boolean =
        serviceName?.let(AndroidWifiAwareServiceNames::isValid) == true && port in 1..65_535

    fun evaluate(
        signedPort: Int?,
        hasAwareTransport: Boolean,
        discoveredPort: Int?,
        transportProtocol: Int?,
        peerAddress: InetAddress?,
        interfaceName: String?,
        localAddresses: List<InetAddress>?,
    ): AndroidWifiAwareEndpointDecision {
        if (signedPort !in 1..65_535) return AndroidWifiAwareEndpointDecision.REJECT
        if (!hasAwareTransport) return AndroidWifiAwareEndpointDecision.REJECT
        if (discoveredPort == null || transportProtocol == null || peerAddress == null) {
            return AndroidWifiAwareEndpointDecision.WAIT
        }
        if (discoveredPort != signedPort || transportProtocol != TCP_PROTOCOL_NUMBER) {
            return AndroidWifiAwareEndpointDecision.REJECT
        }
        return evaluateScopedPeer(peerAddress, interfaceName, localAddresses)
    }

    fun evaluateScopedPeer(
        peerAddress: InetAddress?,
        interfaceName: String?,
        localAddresses: List<InetAddress>?,
    ): AndroidWifiAwareEndpointDecision {
        if (peerAddress == null) return AndroidWifiAwareEndpointDecision.WAIT
        val peer = peerAddress as? Inet6Address ?: return AndroidWifiAwareEndpointDecision.REJECT
        if (!peer.isLinkLocalAddress || peer.scopeId <= 0) return AndroidWifiAwareEndpointDecision.REJECT
        if (interfaceName.isNullOrBlank() || localAddresses == null || localAddresses.isEmpty()) {
            return AndroidWifiAwareEndpointDecision.WAIT
        }

        val scopeMatchesInterface = peer.scopedInterface?.name == interfaceName
        val scopeMatchesLocalAddress = localAddresses.asSequence()
            .filterIsInstance<Inet6Address>()
            .filter(Inet6Address::isLinkLocalAddress)
            .any { it.scopeId > 0 && it.scopeId == peer.scopeId }
        return if (scopeMatchesInterface || scopeMatchesLocalAddress) {
            AndroidWifiAwareEndpointDecision.ACCEPT
        } else {
            AndroidWifiAwareEndpointDecision.REJECT
        }
    }

    fun acceptsSocket(
        expectedPeer: Inet6Address,
        awareLocalAddresses: List<InetAddress>,
        remoteAddress: InetAddress?,
        localAddress: InetAddress?,
    ): Boolean {
        val remote = remoteAddress as? Inet6Address ?: return false
        val local = localAddress as? Inet6Address ?: return false
        if (!remote.isLinkLocalAddress || remote.scopeId <= 0 ||
            !MessageDigest.isEqual(remote.address, expectedPeer.address) ||
            remote.scopeId != expectedPeer.scopeId
        ) {
            return false
        }
        return awareLocalAddresses.asSequence()
            .filterIsInstance<Inet6Address>()
            .any { expected ->
                expected.isLinkLocalAddress &&
                    MessageDigest.isEqual(expected.address, local.address) &&
                    (expected.scopeId == 0 || local.scopeId == 0 || expected.scopeId == local.scopeId)
            }
    }
}

private fun DataOutputStream.writeLengthPrefixedUtf8(value: String) {
    val bytes = value.encodeToByteArray()
    require(bytes.size <= 65_535) { "string is too large" }
    writeShort(bytes.size)
    write(bytes)
}
