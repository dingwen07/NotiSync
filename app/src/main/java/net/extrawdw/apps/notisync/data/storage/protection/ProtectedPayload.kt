package net.extrawdw.apps.notisync.data.storage.protection

import java.security.MessageDigest

/**
 * Opaque, storage-facing protected bytes. The byte-array constructor inputs and accessors are copied so a
 * Room mapper or caller cannot mutate authenticated state after validation. Unknown durable format values are
 * intentionally representable here: [OperationalProtectedPayloadContract] rejects them at the crypto boundary
 * with a typed recovery failure instead of a permissive fallback.
 */
internal class ProtectedPayload private constructor(
    val scheme: String,
    val protectionVersion: Int,
    val generation: Long,
    val keyRef: String,
    val payloadCodecVersion: Int,
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    private val nonceBytes = nonce.copyOf()
    private val ciphertextBytes = ciphertext.copyOf()

    val nonceSize: Int get() = nonceBytes.size
    val ciphertextSize: Int get() = ciphertextBytes.size

    fun nonceCopy(): ByteArray = nonceBytes.copyOf()

    fun ciphertextCopy(): ByteArray = ciphertextBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ProtectedPayload &&
            scheme == other.scheme &&
            protectionVersion == other.protectionVersion &&
            generation == other.generation &&
            keyRef == other.keyRef &&
            payloadCodecVersion == other.payloadCodecVersion &&
            MessageDigest.isEqual(nonceBytes, other.nonceBytes) &&
            MessageDigest.isEqual(ciphertextBytes, other.ciphertextBytes)

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + protectionVersion
        result = 31 * result + generation.hashCode()
        result = 31 * result + keyRef.hashCode()
        result = 31 * result + payloadCodecVersion
        result = 31 * result + nonceBytes.contentHashCode()
        result = 31 * result + ciphertextBytes.contentHashCode()
        return result
    }

    /** Never include ciphertext, nonce, key material, or plaintext-derived values in diagnostics. */
    override fun toString(): String =
        "ProtectedPayload(supportedScheme=${scheme == ProtectedPayloadFormat.SCHEME}, " +
            "protectionVersion=$protectionVersion, " +
            "generation=$generation, payloadCodecVersion=$payloadCodecVersion, " +
            "nonceBytes=$nonceSize, ciphertextBytes=$ciphertextSize)"

    companion object {
        /** Maps a durable row without treating any persisted format value as trusted or supported. */
        fun fromStorage(
            scheme: String,
            protectionVersion: Int,
            generation: Long,
            keyRef: String,
            payloadCodecVersion: Int,
            nonce: ByteArray,
            ciphertext: ByteArray,
        ): ProtectedPayload = ProtectedPayload(
            scheme = scheme,
            protectionVersion = protectionVersion,
            generation = generation,
            keyRef = keyRef,
            payloadCodecVersion = payloadCodecVersion,
            nonce = nonce,
            ciphertext = ciphertext,
        )
    }
}

internal enum class ProtectedPayloadOperation {
    CHECK_EXISTS,
    CREATE_KEY,
    VALIDATE_KEY,
    DELETE_KEY,
    SELF_TEST_KEY,
    PROTECT,
    OPEN,
}

/** Stable categories for fail-closed aggregate recovery and privacy-safe Activity rendering. */
internal enum class ProtectedPayloadFailureCode {
    INVALID_INPUT,
    UNSUPPORTED_SCHEME,
    UNSUPPORTED_PROTECTION_VERSION,
    UNSUPPORTED_PAYLOAD_CODEC_VERSION,
    PAYLOAD_BOUNDS_EXCEEDED,
    KEY_REFERENCE_MISMATCH,
    KEY_MISSING,
    KEY_ALIAS_CONFLICT,
    KEY_INVALIDATED,
    KEY_POLICY_VIOLATION,
    AUTHENTICATION_FAILED,
    WRONG_THREAD,
    PROVIDER_FAILURE,
}

/** Bounded provider diagnostics; never populate this from payloads, enrollment values, or key bytes. */
internal data class ProtectedPayloadProviderFailure(
    val exceptionType: String,
    val message: String?,
) {
    companion object {
        private const val MAX_MESSAGE_CHARS = 384

        fun from(failure: Throwable): ProtectedPayloadProviderFailure = ProtectedPayloadProviderFailure(
            exceptionType = failure.javaClass.name.take(192),
            message = failure.message
                ?.filterNot(Char::isISOControl)
                ?.take(MAX_MESSAGE_CHARS)
                ?.takeIf(String::isNotBlank),
        )
    }
}

internal class ProtectedPayloadException(
    val code: ProtectedPayloadFailureCode,
    val operation: ProtectedPayloadOperation,
    val providerFailure: ProtectedPayloadProviderFailure? = null,
    detail: String? = null,
    cause: Throwable? = null,
) : Exception(
    buildString {
        append("Protected payload ")
        append(operation.name.lowercase())
        append(" failed: ")
        append(code.name.lowercase())
        detail?.takeIf(String::isNotBlank)?.let {
            append(" (")
            append(it.filterNot(Char::isISOControl).take(256))
            append(')')
        }
    },
    cause,
)
