package net.extrawdw.apps.notisync.data.storage.protection

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

internal object ProtectedPayloadFormat {
    const val SCHEME = "android_keystore_aes_gcm"
    const val SCHEME_CODE = 1
    const val PROTECTION_VERSION = 1
    const val AAD_VERSION = 1
    const val PAYLOAD_CODEC_VERSION = 1
    const val NONCE_BYTES = 12
    const val GCM_TAG_BYTES = 16
    const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
    const val DATABASE_NAMESPACE = "net.extrawdw.apps.notisync.storage.operational"

    val AAD_MAGIC: ByteArray = byteArrayOf(
        0x4e, 0x53, 0x50, 0x50, 0x41, 0x41, 0x44, 0x31,
    ) // "NSPPAAD1"
}

/**
 * Audited storage-policy ceilings for already encoded plaintext. Protocol/body validation remains mandatory
 * before this boundary. These deliberately close gaps where protocol identifiers or canonical CBOR overhead do
 * not currently expose a mathematical global maximum.
 */
internal object ProtectedPayloadStoragePolicy {
    const val SEAL_PENDING_MAX_PLAINTEXT_BYTES = 640 * 1024
    const val SEAL_RESPONSE_MAX_PLAINTEXT_BYTES = 192 * 1024
    const val SSH_REQUEST_MAX_PLAINTEXT_BYTES = 384 * 1024
    const val SSH_RESPONSE_MAX_PLAINTEXT_BYTES = 128 * 1024
    const val SSH_HISTORY_MAX_PLAINTEXT_BYTES = 384 * 1024
    const val DISPLAY_MAX_PLAINTEXT_BYTES = 64 * 1024
    /** Reviewed against the maximum shipped v51 enrollment fixture plus canonical framing. */
    const val SEAL_ENROLLMENT_MAX_PLAINTEXT_BYTES = 8 * 1024

    const val SEAL_PENDING_MAX_CIPHERTEXT_BYTES =
        SEAL_PENDING_MAX_PLAINTEXT_BYTES + ProtectedPayloadFormat.GCM_TAG_BYTES
    const val SEAL_RESPONSE_MAX_CIPHERTEXT_BYTES =
        SEAL_RESPONSE_MAX_PLAINTEXT_BYTES + ProtectedPayloadFormat.GCM_TAG_BYTES
    const val SSH_REQUEST_MAX_CIPHERTEXT_BYTES =
        SSH_REQUEST_MAX_PLAINTEXT_BYTES + ProtectedPayloadFormat.GCM_TAG_BYTES
    const val SSH_RESPONSE_MAX_CIPHERTEXT_BYTES =
        SSH_RESPONSE_MAX_PLAINTEXT_BYTES + ProtectedPayloadFormat.GCM_TAG_BYTES
    const val SSH_HISTORY_MAX_CIPHERTEXT_BYTES =
        SSH_HISTORY_MAX_PLAINTEXT_BYTES + ProtectedPayloadFormat.GCM_TAG_BYTES
    const val DISPLAY_MAX_CIPHERTEXT_BYTES =
        DISPLAY_MAX_PLAINTEXT_BYTES + ProtectedPayloadFormat.GCM_TAG_BYTES
    const val SEAL_ENROLLMENT_MAX_CIPHERTEXT_BYTES =
        SEAL_ENROLLMENT_MAX_PLAINTEXT_BYTES + ProtectedPayloadFormat.GCM_TAG_BYTES
}

internal enum class ProtectedPayloadRole(
    val code: Int,
    val token: String,
    val payloadCodec: String,
    val maxPlaintextBytes: Int,
) {
    SEAL_PENDING(
        code = 2,
        token = "seal_pending",
        payloadCodec = "notisync.protocol.openpgp_sign.request.cbor",
        maxPlaintextBytes = ProtectedPayloadStoragePolicy.SEAL_PENDING_MAX_PLAINTEXT_BYTES,
    ),
    SEAL_RESPONSE(
        code = 3,
        token = "seal_response",
        payloadCodec = "notisync.protocol.openpgp_sign.response.cbor",
        maxPlaintextBytes = ProtectedPayloadStoragePolicy.SEAL_RESPONSE_MAX_PLAINTEXT_BYTES,
    ),
    SEAL_DISPLAY(
        code = 4,
        token = "seal_display",
        payloadCodec = "notisync.storage.seal.display.cbor",
        maxPlaintextBytes = ProtectedPayloadStoragePolicy.DISPLAY_MAX_PLAINTEXT_BYTES,
    ),
    SSH_REQUEST(
        code = 5,
        token = "ssh_request",
        payloadCodec = "notisync.protocol.ssh_agent.request.cbor",
        maxPlaintextBytes = ProtectedPayloadStoragePolicy.SSH_REQUEST_MAX_PLAINTEXT_BYTES,
    ),
    SSH_RESPONSE(
        code = 6,
        token = "ssh_response",
        payloadCodec = "notisync.protocol.ssh_agent.response.cbor",
        maxPlaintextBytes = ProtectedPayloadStoragePolicy.SSH_RESPONSE_MAX_PLAINTEXT_BYTES,
    ),
    SSH_HISTORY(
        code = 7,
        token = "ssh_history",
        payloadCodec = "notisync.storage.ssh.history.cbor",
        maxPlaintextBytes = ProtectedPayloadStoragePolicy.SSH_HISTORY_MAX_PLAINTEXT_BYTES,
    ),
    SEAL_ENROLLMENT(
        code = 8,
        token = "seal_enrollment",
        payloadCodec = "notisync.storage.seal.enrollment.v1",
        maxPlaintextBytes = ProtectedPayloadStoragePolicy.SEAL_ENROLLMENT_MAX_PLAINTEXT_BYTES,
    );

    val maxCiphertextBytes: Int get() = maxPlaintextBytes + ProtectedPayloadFormat.GCM_TAG_BYTES
}

private enum class AadValueType(val code: Int) {
    UTF8(1),
    BYTES(2),
    INT32(3),
    INT64(4),
}

private sealed interface AadValue {
    val type: AadValueType

    data class Utf8(val value: String) : AadValue {
        override val type: AadValueType = AadValueType.UTF8
    }

    data class Bytes(val value: ByteArray) : AadValue {
        override val type: AadValueType = AadValueType.BYTES
    }

    data class Int32(val value: Int) : AadValue {
        override val type: AadValueType = AadValueType.INT32
    }

    data class Int64(val value: Long) : AadValue {
        override val type: AadValueType = AadValueType.INT64
    }
}

private data class AadComponent(val name: String, val value: AadValue)

internal enum class ProtectedPayloadDomain(
    val code: Int,
    val token: String,
    internal val allowedRoles: Set<ProtectedPayloadRole>,
    internal val rowKeyNames: List<String>,
) {
    SEAL_RESPONSE_CUSTODY(
        code = 1,
        token = "seal_response_custody.payload",
        allowedRoles = setOf(ProtectedPayloadRole.SEAL_RESPONSE),
        rowKeyNames = listOf("request_id"),
    ),
    SEAL_PENDING_PAYLOAD(
        code = 2,
        token = "seal_pending_payload.payload",
        allowedRoles = setOf(ProtectedPayloadRole.SEAL_PENDING),
        rowKeyNames = listOf("request_id"),
    ),
    SEAL_REQUEST_DISPLAY(
        code = 3,
        token = "seal_request.display",
        allowedRoles = setOf(ProtectedPayloadRole.SEAL_DISPLAY),
        rowKeyNames = listOf("request_id"),
    ),
    SSH_PROVIDER_PENDING_PAYLOAD(
        code = 4,
        token = "ssh_provider_pending_payload.request",
        allowedRoles = setOf(ProtectedPayloadRole.SSH_REQUEST),
        rowKeyNames = listOf("request_id"),
    ),
    SSH_PROVIDER_REQUEST_HISTORY(
        code = 5,
        token = "ssh_provider_request.history",
        allowedRoles = setOf(ProtectedPayloadRole.SSH_HISTORY),
        rowKeyNames = listOf("request_id"),
    ),
    SSH_PROVIDER_RESPONSE_CUSTODY(
        code = 6,
        token = "ssh_provider_response_custody.payload",
        allowedRoles = setOf(ProtectedPayloadRole.SSH_RESPONSE),
        rowKeyNames = listOf("request_id"),
    ),
    SEAL_ENROLLMENT_PROTECTED(
        code = 7,
        token = "seal_enrollment.protected",
        allowedRoles = setOf(ProtectedPayloadRole.SEAL_ENROLLMENT),
        rowKeyNames = listOf("singleton_id"),
    ),
}

/**
 * Complete immutable address of one protected column. Factories are table-shaped so callers cannot omit a
 * composite primary-key component or silently reorder components when rebuilding AAD after process death.
 */
internal class ProtectedPayloadBinding private constructor(
    val domain: ProtectedPayloadDomain,
    val role: ProtectedPayloadRole,
    private val rowKey: List<AadComponent>,
) {
    private fun validated(): ProtectedPayloadBinding = apply {
        if (role !in domain.allowedRoles) invalidBinding("role is not valid for domain")
        if (rowKey.map(AadComponent::name) != domain.rowKeyNames) {
            invalidBinding("row-key shape does not match domain")
        }
        rowKey.forEach { component ->
            if (component.name.isBlank() || component.name.any(Char::isISOControl)) {
                invalidBinding("row-key name is invalid")
            }
            when (val value = component.value) {
                is AadValue.Utf8 -> if (
                    value.value.isBlank() ||
                    value.value.encodeToByteArray().size > MAX_ROW_KEY_UTF8_BYTES ||
                    value.value.any(Char::isISOControl)
                ) invalidBinding("UTF-8 row-key value is invalid")
                is AadValue.Bytes -> if (value.value.isEmpty() || value.value.size > MAX_ROW_KEY_BYTES) {
                    invalidBinding("byte row-key value is invalid")
                }
                is AadValue.Int32 -> Unit
                is AadValue.Int64 -> Unit
            }
        }
    }

    private fun invalidBinding(detail: String): Nothing = throw ProtectedPayloadException(
        code = ProtectedPayloadFailureCode.INVALID_INPUT,
        operation = ProtectedPayloadOperation.PROTECT,
        detail = detail,
    )

    companion object {
        private const val MAX_ROW_KEY_UTF8_BYTES = 512
        private const val MAX_ROW_KEY_BYTES = 512

        fun sealResponse(requestId: String): ProtectedPayloadBinding = singleStringKey(
            ProtectedPayloadDomain.SEAL_RESPONSE_CUSTODY,
            ProtectedPayloadRole.SEAL_RESPONSE,
            requestId,
        )

        fun sealPending(requestId: String): ProtectedPayloadBinding = singleStringKey(
            ProtectedPayloadDomain.SEAL_PENDING_PAYLOAD,
            ProtectedPayloadRole.SEAL_PENDING,
            requestId,
        )

        fun sealDisplay(requestId: String): ProtectedPayloadBinding = singleStringKey(
            ProtectedPayloadDomain.SEAL_REQUEST_DISPLAY,
            ProtectedPayloadRole.SEAL_DISPLAY,
            requestId,
        )

        fun sshProviderPending(requestId: String): ProtectedPayloadBinding = singleStringKey(
            ProtectedPayloadDomain.SSH_PROVIDER_PENDING_PAYLOAD,
            ProtectedPayloadRole.SSH_REQUEST,
            requestId,
        )

        fun sshProviderHistory(requestId: String): ProtectedPayloadBinding = singleStringKey(
            ProtectedPayloadDomain.SSH_PROVIDER_REQUEST_HISTORY,
            ProtectedPayloadRole.SSH_HISTORY,
            requestId,
        )

        fun sshProviderResponse(requestId: String): ProtectedPayloadBinding = singleStringKey(
            ProtectedPayloadDomain.SSH_PROVIDER_RESPONSE_CUSTODY,
            ProtectedPayloadRole.SSH_RESPONSE,
            requestId,
        )

        fun sealEnrollment(singletonId: Int = 1): ProtectedPayloadBinding {
            if (singletonId != 1) {
                throw ProtectedPayloadException(
                    code = ProtectedPayloadFailureCode.INVALID_INPUT,
                    operation = ProtectedPayloadOperation.PROTECT,
                    detail = "Seal enrollment singleton id is invalid",
                )
            }
            return ProtectedPayloadBinding(
                domain = ProtectedPayloadDomain.SEAL_ENROLLMENT_PROTECTED,
                role = ProtectedPayloadRole.SEAL_ENROLLMENT,
                rowKey = listOf(AadComponent("singleton_id", AadValue.Int32(singletonId))),
            ).validated()
        }

        private fun singleStringKey(
            domain: ProtectedPayloadDomain,
            role: ProtectedPayloadRole,
            value: String,
        ): ProtectedPayloadBinding = ProtectedPayloadBinding(
            domain = domain,
            role = role,
            rowKey = listOf(AadComponent("request_id", AadValue.Utf8(value))),
        ).validated()
    }

    internal fun writeRowKey(output: DataOutputStream) {
        output.writeInt(rowKey.size)
        rowKey.forEach { component ->
            output.writeLengthPrefixedUtf8(component.name)
            output.writeInt(component.value.type.code)
            when (val value = component.value) {
                is AadValue.Utf8 -> output.writeLengthPrefixedUtf8(value.value)
                is AadValue.Bytes -> output.writeLengthPrefixedBytes(value.value)
                is AadValue.Int32 -> output.writeInt(value.value)
                is AadValue.Int64 -> output.writeLong(value.value)
            }
        }
    }
}

/**
 * Exact, append-only AAD v1 encoding. Fixed-width integers are signed big-endian JVM primitives and
 * length-prefixed fields use a non-negative i32 byte count followed by exact UTF-8/raw bytes:
 *
 * `magic[8] | aadVersion:i32 | schemeCode:i32 | scheme:utf8 | protectionVersion:i32 | generation:i64 |
 * databaseNamespace:utf8 | domainCode:i32 | domain:utf8 | rowKeyCount:i32 |
 * { keyName:utf8 | valueType:i32 | value:(utf8|bytes|i32|i64) }* |
 * roleCode:i32 | role:utf8 | payloadCodec:utf8 | payloadCodecVersion:i32`
 *
 * Value type codes are 1 UTF-8, 2 bytes, 3 i32, and 4 i64. The registry fixes component order; mutable
 * claim, retry, outcome, and timestamp columns are intentionally absent.
 */
internal object CanonicalProtectedPayloadAadV1 {
    fun encode(
        binding: ProtectedPayloadBinding,
        protectionVersion: Int,
        generation: Long,
        payloadCodecVersion: Int,
    ): ByteArray {
        val bytes = ByteArrayOutputStream(256)
        DataOutputStream(bytes).use { output ->
            output.write(ProtectedPayloadFormat.AAD_MAGIC)
            output.writeInt(ProtectedPayloadFormat.AAD_VERSION)
            output.writeInt(ProtectedPayloadFormat.SCHEME_CODE)
            output.writeLengthPrefixedUtf8(ProtectedPayloadFormat.SCHEME)
            output.writeInt(protectionVersion)
            output.writeLong(generation)
            output.writeLengthPrefixedUtf8(ProtectedPayloadFormat.DATABASE_NAMESPACE)
            output.writeInt(binding.domain.code)
            output.writeLengthPrefixedUtf8(binding.domain.token)
            binding.writeRowKey(output)
            output.writeInt(binding.role.code)
            output.writeLengthPrefixedUtf8(binding.role.token)
            output.writeLengthPrefixedUtf8(binding.role.payloadCodec)
            output.writeInt(payloadCodecVersion)
        }
        return bytes.toByteArray()
    }
}

internal object OperationalPayloadKeyAlias {
    private const val PREFIX =
        "notisync.storage.operational.protected_payload.aes_gcm_v1.generation."

    fun forGeneration(
        generation: Long,
        operation: ProtectedPayloadOperation = ProtectedPayloadOperation.VALIDATE_KEY,
    ): String {
        if (generation < 0) invalidAlias(operation, "generation must not be negative")
        return "$PREFIX$generation"
    }

    fun generationOf(
        alias: String,
        operation: ProtectedPayloadOperation = ProtectedPayloadOperation.VALIDATE_KEY,
    ): Long {
        if (!alias.startsWith(PREFIX)) {
            invalidAlias(operation, "alias is outside the protected-payload namespace")
        }
        val generation = alias.removePrefix(PREFIX).toLongOrNull()
            ?: invalidAlias(operation, "alias generation is invalid")
        if (generation < 0 || forGeneration(generation, operation) != alias) {
            invalidAlias(operation, "alias is not canonical")
        }
        return generation
    }

    private fun invalidAlias(operation: ProtectedPayloadOperation, detail: String): Nothing =
        throw ProtectedPayloadException(
            code = ProtectedPayloadFailureCode.INVALID_INPUT,
            operation = operation,
            detail = detail,
        )
}

internal data class ProtectedCiphertext(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

/** Keystore-independent seam used by the contract and pure JVM tests. Reads through this seam never create. */
internal interface ProtectedPayloadCipher {
    fun protect(alias: String, plaintext: ByteArray, aad: ByteArray): ProtectedCiphertext

    fun open(alias: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
}

internal object OperationalProtectedPayloadContract {
    fun validateProtect(
        plaintextSize: Int,
        binding: ProtectedPayloadBinding,
        generation: Long,
        payloadCodecVersion: Int,
    ) {
        if (generation < 0) invalid(
            ProtectedPayloadOperation.PROTECT,
            ProtectedPayloadFailureCode.INVALID_INPUT,
            "negative generation",
        )
        if (payloadCodecVersion != ProtectedPayloadFormat.PAYLOAD_CODEC_VERSION) {
            invalid(
                ProtectedPayloadOperation.PROTECT,
                ProtectedPayloadFailureCode.UNSUPPORTED_PAYLOAD_CODEC_VERSION,
                "unsupported payload codec version",
            )
        }
        if (plaintextSize < 0 || plaintextSize > binding.role.maxPlaintextBytes) {
            invalid(
                ProtectedPayloadOperation.PROTECT,
                ProtectedPayloadFailureCode.PAYLOAD_BOUNDS_EXCEEDED,
                "plaintext is outside the role bound",
            )
        }
    }

    fun validateOpen(payload: ProtectedPayload, binding: ProtectedPayloadBinding) {
        if (payload.scheme != ProtectedPayloadFormat.SCHEME) {
            invalid(
                ProtectedPayloadOperation.OPEN,
                ProtectedPayloadFailureCode.UNSUPPORTED_SCHEME,
                "unsupported protection scheme",
            )
        }
        if (payload.protectionVersion != ProtectedPayloadFormat.PROTECTION_VERSION) {
            invalid(
                ProtectedPayloadOperation.OPEN,
                ProtectedPayloadFailureCode.UNSUPPORTED_PROTECTION_VERSION,
                "unsupported protection version",
            )
        }
        if (payload.generation < 0) invalid(
            ProtectedPayloadOperation.OPEN,
            ProtectedPayloadFailureCode.INVALID_INPUT,
            "negative generation",
        )
        if (payload.payloadCodecVersion != ProtectedPayloadFormat.PAYLOAD_CODEC_VERSION) {
            invalid(
                ProtectedPayloadOperation.OPEN,
                ProtectedPayloadFailureCode.UNSUPPORTED_PAYLOAD_CODEC_VERSION,
                "unsupported payload codec version",
            )
        }
        val expectedAlias = OperationalPayloadKeyAlias.forGeneration(
            payload.generation,
            ProtectedPayloadOperation.OPEN,
        )
        if (payload.keyRef != expectedAlias) {
            invalid(
                ProtectedPayloadOperation.OPEN,
                ProtectedPayloadFailureCode.KEY_REFERENCE_MISMATCH,
                "non-canonical key reference",
            )
        }
        if (payload.nonceSize != ProtectedPayloadFormat.NONCE_BYTES) {
            invalid(
                ProtectedPayloadOperation.OPEN,
                ProtectedPayloadFailureCode.INVALID_INPUT,
                "invalid GCM nonce length",
            )
        }
        if (
            payload.ciphertextSize < ProtectedPayloadFormat.GCM_TAG_BYTES ||
            payload.ciphertextSize > binding.role.maxCiphertextBytes
        ) {
            invalid(
                ProtectedPayloadOperation.OPEN,
                ProtectedPayloadFailureCode.PAYLOAD_BOUNDS_EXCEEDED,
                "ciphertext is outside the role bound",
            )
        }
    }

    private fun invalid(
        operation: ProtectedPayloadOperation,
        code: ProtectedPayloadFailureCode,
        detail: String,
    ): Nothing =
        throw ProtectedPayloadException(
            code = code,
            operation = operation,
            detail = detail,
        )
}

internal class OperationalProtectedPayloadProtector(
    private val cipher: ProtectedPayloadCipher,
) {
    fun protect(
        plaintext: ByteArray,
        binding: ProtectedPayloadBinding,
        generation: Long,
        payloadCodecVersion: Int = ProtectedPayloadFormat.PAYLOAD_CODEC_VERSION,
    ): ProtectedPayload {
        OperationalProtectedPayloadContract.validateProtect(
            plaintextSize = plaintext.size,
            binding = binding,
            generation = generation,
            payloadCodecVersion = payloadCodecVersion,
        )
        val alias = OperationalPayloadKeyAlias.forGeneration(generation, ProtectedPayloadOperation.PROTECT)
        val aad = CanonicalProtectedPayloadAadV1.encode(
            binding = binding,
            protectionVersion = ProtectedPayloadFormat.PROTECTION_VERSION,
            generation = generation,
            payloadCodecVersion = payloadCodecVersion,
        )
        var protected: ProtectedCiphertext? = null
        return try {
            protected = cipher.protect(alias, plaintext, aad)
            if (
                protected.nonce.size != ProtectedPayloadFormat.NONCE_BYTES ||
                protected.ciphertext.size != plaintext.size + ProtectedPayloadFormat.GCM_TAG_BYTES ||
                protected.ciphertext.size > binding.role.maxCiphertextBytes
            ) {
                throw ProtectedPayloadException(
                    code = ProtectedPayloadFailureCode.KEY_POLICY_VIOLATION,
                    operation = ProtectedPayloadOperation.PROTECT,
                    detail = "cipher provider returned a non-canonical GCM result",
                )
            }
            ProtectedPayload.fromStorage(
                scheme = ProtectedPayloadFormat.SCHEME,
                protectionVersion = ProtectedPayloadFormat.PROTECTION_VERSION,
                generation = generation,
                keyRef = alias,
                payloadCodecVersion = payloadCodecVersion,
                nonce = protected.nonce,
                ciphertext = protected.ciphertext,
            )
        } catch (failure: ProtectedPayloadException) {
            throw failure
        } catch (failure: Exception) {
            throw ProtectedPayloadException(
                code = ProtectedPayloadFailureCode.PROVIDER_FAILURE,
                operation = ProtectedPayloadOperation.PROTECT,
                providerFailure = ProtectedPayloadProviderFailure.from(failure),
                cause = failure,
            )
        } finally {
            aad.fill(0)
            protected?.nonce?.fill(0)
            protected?.ciphertext?.fill(0)
        }
    }

    fun open(payload: ProtectedPayload, binding: ProtectedPayloadBinding): ByteArray {
        OperationalProtectedPayloadContract.validateOpen(payload, binding)
        val aad = CanonicalProtectedPayloadAadV1.encode(
            binding = binding,
            protectionVersion = payload.protectionVersion,
            generation = payload.generation,
            payloadCodecVersion = payload.payloadCodecVersion,
        )
        val nonce = payload.nonceCopy()
        val ciphertext = payload.ciphertextCopy()
        var plaintext: ByteArray? = null
        return try {
            plaintext = cipher.open(payload.keyRef, nonce, ciphertext, aad)
            if (plaintext.size > binding.role.maxPlaintextBytes) {
                throw ProtectedPayloadException(
                    code = ProtectedPayloadFailureCode.PAYLOAD_BOUNDS_EXCEEDED,
                    operation = ProtectedPayloadOperation.OPEN,
                    detail = "opened plaintext exceeds the role bound",
                )
            }
            plaintext.also { plaintext = null }
        } catch (failure: ProtectedPayloadException) {
            throw failure
        } catch (failure: Exception) {
            throw ProtectedPayloadException(
                code = ProtectedPayloadFailureCode.PROVIDER_FAILURE,
                operation = ProtectedPayloadOperation.OPEN,
                providerFailure = ProtectedPayloadProviderFailure.from(failure),
                cause = failure,
            )
        } finally {
            aad.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
            plaintext?.fill(0)
        }
    }
}

private fun DataOutputStream.writeLengthPrefixedUtf8(value: String) {
    val encoded = value.encodeToByteArray()
    try {
        writeLengthPrefixedBytes(encoded)
    } finally {
        encoded.fill(0)
    }
}

private fun DataOutputStream.writeLengthPrefixedBytes(value: ByteArray) {
    writeInt(value.size)
    write(value)
}
