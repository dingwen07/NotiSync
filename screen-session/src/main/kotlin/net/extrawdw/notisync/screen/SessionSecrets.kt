package net.extrawdw.notisync.screen

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import net.extrawdw.notisync.protocol.ScreenMirrorQualityLimits

enum class ScreenChannel(val wireName: String) {
    VIDEO("video"),
    CONTROL("control");

    companion object {
        fun fromWireName(value: String): ScreenChannel? = entries.firstOrNull { it.wireName == value }
    }
}

enum class SessionRole { SOURCE, VIEWER }

data class SessionDescriptor(
    val sessionId: String,
    val sourcePeerId: String,
    val requesterPeerId: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val codec: String,
    val controlEnabled: Boolean,
    val clipboardEnabled: Boolean,
    val maxDimension: Int,
    val maxFps: Int,
    val videoBitrateBps: Int,
) {
    init {
        requireUtf8(sessionId, 1, 128, "sessionId")
        requireUtf8(sourcePeerId, 1, 256, "sourcePeerId")
        requireUtf8(requesterPeerId, 1, 256, "requesterPeerId")
        require(expiresAtEpochMillis > issuedAtEpochMillis) { "session expiry must follow issue time" }
        require(expiresAtEpochMillis - issuedAtEpochMillis in 1..MAX_SESSION_LIFETIME_MILLIS) {
            "screen session lifetime exceeds five minutes"
        }
        requireUtf8(codec, 1, 32, "codec")
        require(codec in setOf("h264", "h265", "av1")) { "codec must use the screen v1 canonical name" }
        require(maxDimension in ScreenMirrorQualityLimits.MIN_DIMENSION..ScreenMirrorQualityLimits.MAX_DIMENSION) {
            "maxDimension is out of range"
        }
        require(maxFps in ScreenMirrorQualityLimits.MIN_FPS..ScreenMirrorQualityLimits.MAX_FPS) {
            "maxFps is out of range"
        }
        require(
            videoBitrateBps in
                ScreenMirrorQualityLimits.MIN_BITRATE_BPS..ScreenMirrorQualityLimits.MAX_BITRATE_BPS,
        ) { "videoBitrateBps is out of range" }
    }
}

data class ChannelBinding(
    val descriptor: SessionDescriptor,
    val channel: ScreenChannel,
    val role: SessionRole,
)

class SecretBytes private constructor(private var value: ByteArray?) : AutoCloseable {
    val size: Int get() = requireNotNull(value) { "secret was destroyed" }.size

    fun copy(): ByteArray = requireNotNull(value) { "secret was destroyed" }.copyOf()

    override fun close() {
        value?.fill(0)
        value = null
    }

    companion object {
        fun copyOf(bytes: ByteArray, expectedSize: Int, name: String): SecretBytes {
            require(bytes.size == expectedSize) { "$name must be $expectedSize bytes" }
            return SecretBytes(bytes.copyOf())
        }

        fun random(size: Int, random: SecureRandom = SecureRandom()): SecretBytes =
            SecretBytes(ByteArray(size).also(random::nextBytes))
    }
}

data class GeneratedSessionSecrets(
    val routingToken: SecretBytes,
    val masterPsk: SecretBytes,
) : AutoCloseable {
    override fun close() {
        routingToken.close()
        masterPsk.close()
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): GeneratedSessionSecrets =
            GeneratedSessionSecrets(
                SecretBytes.random(ROUTING_TOKEN_BYTES, random),
                SecretBytes.random(MASTER_PSK_BYTES, random),
            )
    }
}

data class ParsedRoutingIdentity(
    val token: ByteArray,
    val channel: ScreenChannel,
)

object RoutingIdentity {
    private const val PREFIX = "nss1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(token: ByteArray, channel: ScreenChannel): ByteArray {
        require(token.size == ROUTING_TOKEN_BYTES) { "routing token must be $ROUTING_TOKEN_BYTES bytes" }
        return "$PREFIX.${encoder.encodeToString(token)}.${channel.wireName}".encodeToByteArray()
    }

    fun parse(bytes: ByteArray): ParsedRoutingIdentity? {
        if (bytes.size !in 1..96 || bytes.any { it < 0x20 || it > 0x7e }) return null
        val pieces = bytes.decodeToString().split('.')
        if (pieces.size != 3 || pieces[0] != PREFIX) return null
        val token = runCatching { decoder.decode(pieces[1]) }.getOrNull()
            ?.takeIf { it.size == ROUTING_TOKEN_BYTES } ?: return null
        if (encoder.encodeToString(token) != pieces[1]) {
            token.fill(0)
            return null
        }
        val channel = ScreenChannel.fromWireName(pieces[2]) ?: run {
            token.fill(0)
            return null
        }
        return ParsedRoutingIdentity(token, channel)
    }
}

object SessionKeyDeriver {
    fun derive(
        masterPsk: ByteArray,
        routingToken: ByteArray,
        descriptor: SessionDescriptor,
        channel: ScreenChannel,
    ): ByteArray {
        require(masterPsk.size == MASTER_PSK_BYTES) { "master PSK must be $MASTER_PSK_BYTES bytes" }
        require(routingToken.size == ROUTING_TOKEN_BYTES) { "routing token must be $ROUTING_TOKEN_BYTES bytes" }
        val baseContext = canonicalContext(descriptor, routingToken, null)
        val fullContext = canonicalContext(descriptor, routingToken, channel)
        val salt = MessageDigest.getInstance("SHA-256").digest(baseContext)
        val prk = hmac(salt, masterPsk)
        return try {
            // RFC 5869 expand for one SHA-256 block.
            hmac(prk, fullContext + byteArrayOf(1)).copyOf(CHANNEL_PSK_BYTES)
        } finally {
            salt.fill(0)
            prk.fill(0)
            baseContext.fill(0)
            fullContext.fill(0)
        }
    }

    private fun canonicalContext(
        descriptor: SessionDescriptor,
        routingToken: ByteArray,
        channel: ScreenChannel?,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUtf8("notisync-screen")
            output.writeInt(ScreenSessionProtocol.VERSION)
            output.writeUtf8(descriptor.sessionId)
            output.writeUtf8(descriptor.sourcePeerId)
            output.writeUtf8(descriptor.requesterPeerId)
            output.writeLong(descriptor.issuedAtEpochMillis)
            output.writeLong(descriptor.expiresAtEpochMillis)
            output.writeUtf8(descriptor.codec)
            output.writeBoolean(descriptor.controlEnabled)
            output.writeBoolean(descriptor.clipboardEnabled)
            output.writeInt(descriptor.maxDimension)
            output.writeInt(descriptor.maxFps)
            output.writeInt(descriptor.videoBitrateBps)
            output.writeInt(routingToken.size)
            output.write(routingToken)
            output.writeUtf8(channel?.wireName ?: "session")
        }
        bytes.toByteArray()
    }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }
}

internal fun DataOutputStream.writeUtf8(value: String) {
    val bytes = value.encodeToByteArray()
    require(bytes.size <= 65_535) { "string is too large" }
    writeShort(bytes.size)
    write(bytes)
}

internal fun requireUtf8(value: String, minBytes: Int, maxBytes: Int, name: String) {
    val size = value.encodeToByteArray().size
    require(size in minBytes..maxBytes) { "$name must be $minBytes..$maxBytes UTF-8 bytes" }
}

const val ROUTING_TOKEN_BYTES: Int = 16
const val MASTER_PSK_BYTES: Int = 32
const val CHANNEL_PSK_BYTES: Int = 32
const val MAX_SESSION_LIFETIME_MILLIS: Long = 5 * 60 * 1_000L
