package net.extrawdw.apps.notisync.data.storage.importer.target

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.data.storage.protection.OperationalProtectedPayloadProtector
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayload
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadBinding
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadException
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadFailureCode
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadStoragePolicy
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalPayloadKeyVault
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalStorageMaintenanceGate

/** Ephemeral plaintext enrollment input. Its string representation intentionally carries no values. */
internal class SealEnrollmentImportMaterial(
    val providerId: String,
    val providerKeyReference: String,
    val primaryKeyId: String,
    val displayIdentity: String,
    val enrolledAt: Long,
) {
    init {
        requireBoundedText(providerId, MAX_IDENTIFIER_BYTES, "provider id")
        requireBoundedText(providerKeyReference, MAX_IDENTIFIER_BYTES, "provider key reference")
        require(PRIMARY_KEY_ID.matches(primaryKeyId)) { "Seal primary key id is invalid" }
        requireBoundedText(displayIdentity, MAX_DISPLAY_IDENTITY_BYTES, "display identity")
        require(enrolledAt > 0) { "Seal enrollment time must be positive" }
    }

    override fun toString(): String = "SealEnrollmentImportMaterial(redacted)"
}

internal data class SealDisplayHeaderImportMaterial(
    val name: String,
    val value: String,
) {
    init {
        requireBoundedText(name, MAX_HEADER_NAME_BYTES, "display header name")
        requireBoundedText(value, MAX_HEADER_VALUE_BYTES, "display header value", allowLineBreaks = true)
    }

    override fun toString(): String = "SealDisplayHeaderImportMaterial(redacted)"
}

internal data class SealCommitDisplayImportMaterial(
    val treeId: String,
    val parentIds: List<String>,
    val author: String,
    val committer: String,
    val message: String,
    val extraHeaders: List<SealDisplayHeaderImportMaterial>,
    val payloadBytes: Int,
    val truncated: Boolean,
) {
    init {
        require(OBJECT_ID.matches(treeId)) { "Seal display tree id is invalid" }
        require(parentIds.size <= MAX_PARENT_IDS && parentIds.all(OBJECT_ID::matches)) {
            "Seal display parent ids are invalid"
        }
        requireBoundedText(author, MAX_IDENTITY_BYTES, "display author")
        requireBoundedText(committer, MAX_IDENTITY_BYTES, "display committer")
        requireBoundedText(message, MAX_MESSAGE_BYTES, "display message", allowEmpty = true, allowLineBreaks = true)
        require(extraHeaders.size <= MAX_HEADERS) { "Seal display has too many headers" }
        require(payloadBytes in 0..MAX_SIGNED_PAYLOAD_BYTES) { "Seal display payload size is invalid" }
    }

    override fun toString(): String =
        "SealCommitDisplayImportMaterial(parents=${parentIds.size}, headers=${extraHeaders.size}, " +
            "payloadBytes=$payloadBytes, truncated=$truncated)"
}

/** Exact retained history projection. It contains no raw signed payload or provider response. */
internal class SealTerminalDisplayImportMaterial(
    val primaryKeyId: String,
    val workingDirectory: String?,
    val commit: SealCommitDisplayImportMaterial?,
) {
    init {
        require(PRIMARY_KEY_ID.matches(primaryKeyId)) { "Seal primary key id is invalid" }
        workingDirectory?.let {
            requireBoundedText(it, MAX_WORKING_DIRECTORY_BYTES, "working directory")
        }
    }

    override fun toString(): String =
        "SealTerminalDisplayImportMaterial(hasWorkingDirectory=${workingDirectory != null}, hasCommit=${commit != null})"
}

internal data class ProtectedSealDisplayImport(
    val payload: ProtectedPayload,
    val plaintextDigest: ImportDigest,
    val truncated: Boolean,
)

internal fun interface SealImportPayloadVerifier {
    suspend fun verifyDisplay(
        requestId: String,
        payload: ProtectedPayload,
        expectedPlaintextDigest: ByteArray,
    ): Boolean
}

/**
 * Serializes, protects, opens, and byte-compares Seal import payloads outside Room. Keystore access
 * is fenced by the same maintenance gate as generation/reset and has no plaintext fallback. The
 * pre-authority migrator supplies the attempt generation explicitly; Core transport is not yet
 * available and is therefore never consulted by this importer-only path.
 */
internal class SealImportPayloadMaterializer(
    private val protector: OperationalProtectedPayloadProtector,
    private val payloadKeyVault: OperationalPayloadKeyVault,
    private val maintenanceGate: OperationalStorageMaintenanceGate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SealImportPayloadVerifier {
    /** Accessed only while [maintenanceGate] is held. Process death deliberately clears this cache. */
    private var preparedGeneration: Long? = null

    suspend fun protectEnrollment(
        material: SealEnrollmentImportMaterial,
        operationalGeneration: Long,
    ): ProtectedPayload {
        val plaintext = CanonicalSealImportPayloadCodecV1.encodeEnrollment(material)
        return protectAndSelfTest(
            plaintext = plaintext,
            binding = ProtectedPayloadBinding.sealEnrollment(),
            operationalGeneration = operationalGeneration,
            validateOpened = CanonicalSealImportPayloadCodecV1::validateEnrollment,
        )
    }

    suspend fun protectDisplay(
        requestId: String,
        material: SealTerminalDisplayImportMaterial,
        operationalGeneration: Long,
    ): ProtectedSealDisplayImport {
        val encoded = CanonicalSealImportPayloadCodecV1.encodeDisplayBounded(material)
        val digest = ImportDigest.sha256(MessageDigest.getInstance("SHA-256").digest(encoded.bytes))
        val payload = protectAndSelfTest(
            plaintext = encoded.bytes,
            binding = ProtectedPayloadBinding.sealDisplay(requestId),
            operationalGeneration = operationalGeneration,
            validateOpened = CanonicalSealImportPayloadCodecV1::validateDisplay,
        )
        return ProtectedSealDisplayImport(payload, digest, encoded.truncated)
    }

    override suspend fun verifyDisplay(
        requestId: String,
        payload: ProtectedPayload,
        expectedPlaintextDigest: ByteArray,
    ): Boolean {
        if (expectedPlaintextDigest.size != ImportDigest.BYTES) return false
        val opened = openUnderBoundGeneration(payload, ProtectedPayloadBinding.sealDisplay(requestId))
        return try {
            CanonicalSealImportPayloadCodecV1.validateDisplay(opened)
            MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(opened),
                expectedPlaintextDigest,
            )
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            opened.fill(0)
        }
    }

    private suspend fun protectAndSelfTest(
        plaintext: ByteArray,
        binding: ProtectedPayloadBinding,
        operationalGeneration: Long,
        validateOpened: (ByteArray) -> Unit,
    ): ProtectedPayload = try {
        require(operationalGeneration > 0) { "Operational generation must be positive" }
        maintenanceGate.withExclusiveAccess {
            try {
                prepareGeneration(operationalGeneration)
                withContext(ioDispatcher) {
                    val protected = protector.protect(plaintext, binding, operationalGeneration)
                    val opened = protector.open(protected, binding)
                    try {
                        validateOpened(opened)
                        if (!MessageDigest.isEqual(plaintext, opened)) {
                            importBlocked("seal_protection_self_test_mismatch")
                        }
                    } finally {
                        opened.fill(0)
                    }
                    protected
                }
            } catch (failure: Throwable) {
                // A missing/invalidated provider key or failed self-test must be reconciled again
                // on the next call rather than trusted through this process-local optimization.
                preparedGeneration = null
                throw failure
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: OperationalImportFailure) {
        throw failure
    } catch (failure: ProtectedPayloadException) {
        throw failure.toImportFailure()
    } catch (failure: IllegalArgumentException) {
        throw OperationalImportFailure(
            ImportFailureDisposition.BLOCKED,
            "seal_payload_encoding_invalid",
            failure,
        )
    } finally {
        plaintext.fill(0)
    }

    private suspend fun prepareGeneration(operationalGeneration: Long) {
        if (preparedGeneration == operationalGeneration) return
        payloadKeyVault.create(operationalGeneration)
        payloadKeyVault.selfTest(operationalGeneration)
        preparedGeneration = operationalGeneration
    }

    private suspend fun openUnderBoundGeneration(
        payload: ProtectedPayload,
        binding: ProtectedPayloadBinding,
    ): ByteArray = try {
        maintenanceGate.withExclusiveAccess {
            withContext(ioDispatcher) { protector.open(payload, binding) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: OperationalImportFailure) {
        throw failure
    } catch (failure: ProtectedPayloadException) {
        throw failure.toImportFailure()
    }

    private fun ProtectedPayloadException.toImportFailure(): OperationalImportFailure {
        val disposition = when (code) {
            ProtectedPayloadFailureCode.KEY_MISSING,
            ProtectedPayloadFailureCode.KEY_INVALIDATED,
            ProtectedPayloadFailureCode.PROVIDER_FAILURE,
            -> ImportFailureDisposition.RETRYABLE
            else -> ImportFailureDisposition.BLOCKED
        }
        return OperationalImportFailure(
            disposition,
            "seal_protection_${code.name.lowercase()}",
            this,
        )
    }
}

internal object CanonicalSealImportPayloadCodecV1 {
    private val ENROLLMENT_MAGIC = "NSSENR01".encodeToByteArray()
    private val DISPLAY_MAGIC = "NSSDIS01".encodeToByteArray()
    private const val VERSION = 1

    internal data class EncodedDisplay(val bytes: ByteArray, val truncated: Boolean)

    fun encodeEnrollment(material: SealEnrollmentImportMaterial): ByteArray = encode { output ->
        output.write(ENROLLMENT_MAGIC)
        output.writeInt(VERSION)
        output.writeUtf8(material.providerId)
        output.writeUtf8(material.providerKeyReference)
        output.writeUtf8(material.primaryKeyId)
        output.writeUtf8(material.displayIdentity)
        output.writeLong(material.enrolledAt)
    }.also {
        require(it.size <= ProtectedPayloadStoragePolicy.SEAL_ENROLLMENT_MAX_PLAINTEXT_BYTES) {
            "encoded Seal enrollment exceeds its reviewed bound"
        }
    }

    fun validateEnrollment(encoded: ByteArray) {
        decode(encoded, ProtectedPayloadStoragePolicy.SEAL_ENROLLMENT_MAX_PLAINTEXT_BYTES) { input ->
            input.requireMagic(ENROLLMENT_MAGIC)
            input.requireVersion()
            input.readUtf8(MAX_IDENTIFIER_BYTES)
            input.readUtf8(MAX_IDENTIFIER_BYTES)
            val primaryKeyId = input.readUtf8(PRIMARY_KEY_ID_BYTES)
            require(PRIMARY_KEY_ID.matches(primaryKeyId)) { "encoded Seal primary key id is invalid" }
            input.readUtf8(MAX_DISPLAY_IDENTITY_BYTES)
            require(input.readLong() > 0) { "encoded Seal enrollment time is invalid" }
        }
    }

    fun encodeDisplayBounded(material: SealTerminalDisplayImportMaterial): EncodedDisplay {
        var candidate = material
        var encoded = encodeDisplay(candidate)
        if (encoded.size <= ProtectedPayloadStoragePolicy.DISPLAY_MAX_PLAINTEXT_BYTES) {
            return EncodedDisplay(encoded, material.commit?.truncated == true)
        }
        encoded.fill(0)
        val commit = requireNotNull(material.commit) { "Seal display metadata exceeds its bound" }
        var headers = commit.extraHeaders
        do {
            require(headers.isNotEmpty()) { "Seal display cannot fit the protected role bound" }
            headers = headers.dropLast(1)
            candidate = SealTerminalDisplayImportMaterial(
                primaryKeyId = material.primaryKeyId,
                workingDirectory = material.workingDirectory,
                commit = commit.copy(extraHeaders = headers, truncated = true),
            )
            encoded = encodeDisplay(candidate)
            if (encoded.size <= ProtectedPayloadStoragePolicy.DISPLAY_MAX_PLAINTEXT_BYTES) {
                return EncodedDisplay(encoded, truncated = true)
            }
            encoded.fill(0)
        } while (true)
    }

    fun validateDisplay(encoded: ByteArray) {
        decode(encoded, ProtectedPayloadStoragePolicy.DISPLAY_MAX_PLAINTEXT_BYTES) { input ->
            input.requireMagic(DISPLAY_MAGIC)
            input.requireVersion()
            val primaryKeyId = input.readUtf8(PRIMARY_KEY_ID_BYTES)
            require(PRIMARY_KEY_ID.matches(primaryKeyId)) { "encoded Seal primary key id is invalid" }
            input.readNullableUtf8(MAX_WORKING_DIRECTORY_BYTES)
            when (input.readUnsignedByte()) {
                0 -> Unit
                1 -> {
                    val tree = input.readUtf8(OBJECT_ID_MAX_BYTES)
                    require(OBJECT_ID.matches(tree)) { "encoded Seal tree id is invalid" }
                    val parentCount = input.readBoundedCount(MAX_PARENT_IDS)
                    repeat(parentCount) {
                        require(OBJECT_ID.matches(input.readUtf8(OBJECT_ID_MAX_BYTES))) {
                            "encoded Seal parent id is invalid"
                        }
                    }
                    input.readUtf8(MAX_IDENTITY_BYTES)
                    input.readUtf8(MAX_IDENTITY_BYTES)
                    input.readUtf8(MAX_MESSAGE_BYTES, allowEmpty = true, allowLineBreaks = true)
                    val headerCount = input.readBoundedCount(MAX_HEADERS)
                    repeat(headerCount) {
                        input.readUtf8(MAX_HEADER_NAME_BYTES)
                        input.readUtf8(MAX_HEADER_VALUE_BYTES, allowLineBreaks = true)
                    }
                    require(input.readInt() in 0..MAX_SIGNED_PAYLOAD_BYTES) {
                        "encoded Seal payload size is invalid"
                    }
                    require(input.readUnsignedByte() in 0..1) { "encoded Seal truncation flag is invalid" }
                }
                else -> throw IllegalArgumentException("encoded Seal display discriminator is invalid")
            }
        }
    }

    private fun encodeDisplay(material: SealTerminalDisplayImportMaterial): ByteArray = encode { output ->
        output.write(DISPLAY_MAGIC)
        output.writeInt(VERSION)
        output.writeUtf8(material.primaryKeyId)
        output.writeNullableUtf8(material.workingDirectory)
        val commit = material.commit
        output.writeByte(if (commit == null) 0 else 1)
        if (commit != null) {
            output.writeUtf8(commit.treeId)
            output.writeInt(commit.parentIds.size)
            commit.parentIds.forEach { parentId -> output.writeUtf8(parentId) }
            output.writeUtf8(commit.author)
            output.writeUtf8(commit.committer)
            output.writeUtf8(commit.message)
            output.writeInt(commit.extraHeaders.size)
            commit.extraHeaders.forEach { header ->
                output.writeUtf8(header.name)
                output.writeUtf8(header.value)
            }
            output.writeInt(commit.payloadBytes)
            output.writeByte(if (commit.truncated) 1 else 0)
        }
    }

    private inline fun encode(block: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(block)
        return bytes.toByteArray()
    }

    private inline fun decode(encoded: ByteArray, maxBytes: Int, block: (DataInputStream) -> Unit) {
        require(encoded.isNotEmpty() && encoded.size <= maxBytes) { "encoded Seal payload is outside its bound" }
        try {
            ByteArrayInputStream(encoded).use { bytes ->
                DataInputStream(bytes).use { input ->
                    block(input)
                    require(bytes.available() == 0) { "encoded Seal payload has trailing bytes" }
                }
            }
        } catch (failure: EOFException) {
            throw IllegalArgumentException("encoded Seal payload is truncated", failure)
        }
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.encodeToByteArray()
        try {
            writeInt(bytes.size)
            write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataOutputStream.writeNullableUtf8(value: String?) {
        writeByte(if (value == null) 0 else 1)
        if (value != null) writeUtf8(value)
    }

    private fun DataInputStream.requireMagic(expected: ByteArray) {
        val actual = ByteArray(expected.size)
        readFully(actual)
        require(MessageDigest.isEqual(actual, expected)) { "encoded Seal payload magic is invalid" }
    }

    private fun DataInputStream.requireVersion() {
        require(readInt() == VERSION) { "encoded Seal payload version is unsupported" }
    }

    private fun DataInputStream.readUtf8(
        maxBytes: Int,
        allowEmpty: Boolean = false,
        allowLineBreaks: Boolean = false,
    ): String {
        val size = readInt()
        require(size in (if (allowEmpty) 0 else 1)..maxBytes) { "encoded Seal text length is invalid" }
        val bytes = ByteArray(size)
        return try {
            readFully(bytes)
            bytes.decodeToString(throwOnInvalidSequence = true).also { value ->
                requireSafeText(value, "encoded Seal text", allowEmpty, allowLineBreaks)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataInputStream.readNullableUtf8(maxBytes: Int): String? = when (readUnsignedByte()) {
        0 -> null
        1 -> readUtf8(maxBytes)
        else -> throw IllegalArgumentException("encoded Seal nullable discriminator is invalid")
    }

    private fun DataInputStream.readBoundedCount(max: Int): Int = readInt().also {
        require(it in 0..max) { "encoded Seal collection count is invalid" }
    }
}

private fun requireBoundedText(
    value: String,
    maxBytes: Int,
    label: String,
    allowEmpty: Boolean = false,
    allowLineBreaks: Boolean = false,
) {
    require(value.encodeToByteArray().size <= maxBytes) { "$label exceeds its byte bound" }
    requireSafeText(value, label, allowEmpty, allowLineBreaks)
}

private fun requireSafeText(
    value: String,
    label: String,
    allowEmpty: Boolean,
    allowLineBreaks: Boolean,
) {
    require(allowEmpty || value.isNotEmpty()) { "$label must not be empty" }
    require(value.none { character ->
        character == '\u0000' || character.isISOControl() &&
            (!allowLineBreaks || character !in setOf('\n', '\r', '\t'))
    }) { "$label contains unsupported controls" }
}

private fun importBlocked(code: String): Nothing = throw OperationalImportFailure(
    ImportFailureDisposition.BLOCKED,
    code,
)

private fun importRetryable(code: String): Nothing = throw OperationalImportFailure(
    ImportFailureDisposition.RETRYABLE,
    code,
)

private const val MAX_IDENTIFIER_BYTES = 256
private const val MAX_DISPLAY_IDENTITY_BYTES = 4 * 1024
private const val MAX_WORKING_DIRECTORY_BYTES = 1_024
private const val MAX_IDENTITY_BYTES = 4 * 1024
private const val MAX_MESSAGE_BYTES = 64 * 1024
private const val MAX_HEADER_NAME_BYTES = 512
private const val MAX_HEADER_VALUE_BYTES = 8 * 1024
private const val MAX_PARENT_IDS = 64
private const val MAX_HEADERS = 64
private const val MAX_SIGNED_PAYLOAD_BYTES = 512 * 1024
private const val OBJECT_ID_MAX_BYTES = 64
private const val PRIMARY_KEY_ID_BYTES = 16
private val PRIMARY_KEY_ID = Regex("[0-9A-F]{16}")
private val OBJECT_ID = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
