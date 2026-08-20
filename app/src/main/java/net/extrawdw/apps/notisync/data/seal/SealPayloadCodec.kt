package net.extrawdw.apps.notisync.data.seal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.MessageDigest
import net.extrawdw.apps.notisync.seal.GitCommitDisplayHeader
import net.extrawdw.apps.notisync.seal.GitCommitDisplaySnapshot
import net.extrawdw.apps.notisync.seal.OpenPgpEnrollment
import net.extrawdw.notisync.protocol.OpenPgpSignLimits

/**
 * Runtime counterpart of the v1 protected Seal payload format. It intentionally lives outside the
 * importer package: production writes and reads the same stable bytes, while legacy SQL readers stay
 * independent of the Room/domain model.
 */
internal object SealPayloadCodec {
    private val ENROLLMENT_MAGIC = "NSSENR01".encodeToByteArray()
    private val DISPLAY_MAGIC = "NSSDIS01".encodeToByteArray()
    private const val VERSION = 1

    fun encodeEnrollment(value: OpenPgpEnrollment): ByteArray {
        val providerId = requireText(value.providerId, MAX_IDENTIFIER_BYTES, "provider id")
        val providerReference = requireText(value.providerKeyReference, MAX_IDENTIFIER_BYTES, "provider key reference")
        val primaryKeyId = requireText(value.primaryKeyId, PRIMARY_KEY_ID_BYTES, "primary key id")
        require(PRIMARY_KEY_ID.matches(primaryKeyId)) { "Seal primary key id is invalid" }
        val identity = requireText(value.displayIdentity, MAX_DISPLAY_IDENTITY_BYTES, "display identity")
        val enrolledAt = requireNotNull(value.enrolledAt) { "Seal enrollment time is missing" }
        require(enrolledAt > 0) { "Seal enrollment time is invalid" }
        return encode { output ->
            output.write(ENROLLMENT_MAGIC)
            output.writeInt(VERSION)
            output.writeUtf8(providerId)
            output.writeUtf8(providerReference)
            output.writeUtf8(primaryKeyId)
            output.writeUtf8(identity)
            output.writeLong(enrolledAt)
        }
    }

    fun decodeEnrollment(encoded: ByteArray): OpenPgpEnrollment = decode(
        encoded,
        MAX_ENROLLMENT_BYTES,
    ) { input ->
        input.requireMagic(ENROLLMENT_MAGIC)
        input.requireVersion()
        val providerId = input.readUtf8(MAX_IDENTIFIER_BYTES)
        val providerReference = input.readUtf8(MAX_IDENTIFIER_BYTES)
        val primaryKeyId = input.readUtf8(PRIMARY_KEY_ID_BYTES)
        require(PRIMARY_KEY_ID.matches(primaryKeyId)) { "encoded Seal primary key id is invalid" }
        val identity = input.readUtf8(MAX_DISPLAY_IDENTITY_BYTES)
        val enrolledAt = input.readLong()
        require(enrolledAt > 0) { "encoded Seal enrollment time is invalid" }
        OpenPgpEnrollment(
            enabled = true,
            providerId = providerId,
            providerKeyReference = providerReference,
            primaryKeyId = primaryKeyId,
            displayIdentity = identity,
            enrolledAt = enrolledAt,
        )
    }

    fun encodeDisplay(
        primaryKeyId: String,
        workingDirectory: String?,
        commit: GitCommitDisplaySnapshot?,
    ): ByteArray = encodeDisplayBounded(primaryKeyId, workingDirectory, commit).bytes

    fun encodeDisplayBounded(
        primaryKeyId: String,
        workingDirectory: String?,
        commit: GitCommitDisplaySnapshot?,
    ): EncodedDisplay {
        require(PRIMARY_KEY_ID.matches(primaryKeyId)) { "Seal primary key id is invalid" }
        workingDirectory?.let { requireText(it, MAX_WORKING_DIRECTORY_BYTES, "working directory") }
        commit?.validate()
        var candidate = commit
        var encoded = encodeDisplayRaw(primaryKeyId, workingDirectory, candidate)
        if (encoded.size <= MAX_DISPLAY_BYTES) return EncodedDisplay(encoded, candidate)
        val original = requireNotNull(commit) { "encoded Seal display exceeds its reviewed bound" }
        var headers = original.extraHeaders
        while (headers.isNotEmpty()) {
            headers = headers.dropLast(1)
            candidate = original.copy(extraHeaders = headers, truncated = true)
            encoded.fill(0)
            encoded = encodeDisplayRaw(primaryKeyId, workingDirectory, candidate)
            if (encoded.size <= MAX_DISPLAY_BYTES) return EncodedDisplay(encoded, candidate)
        }
        encoded.fill(0)
        throw IllegalArgumentException("encoded Seal display exceeds its reviewed bound")
    }

    private fun encodeDisplayRaw(
        primaryKeyId: String,
        workingDirectory: String?,
        commit: GitCommitDisplaySnapshot?,
    ): ByteArray = encode { output ->
            output.write(DISPLAY_MAGIC)
            output.writeInt(VERSION)
            output.writeUtf8(primaryKeyId)
            output.writeNullableUtf8(workingDirectory)
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

    data class EncodedDisplay(
        val bytes: ByteArray,
        val snapshot: GitCommitDisplaySnapshot?,
    )

    fun decodeDisplay(encoded: ByteArray): SealDisplayPayload = decode(
        encoded,
        MAX_DISPLAY_BYTES,
    ) { input ->
        input.requireMagic(DISPLAY_MAGIC)
        input.requireVersion()
        val primaryKeyId = input.readUtf8(PRIMARY_KEY_ID_BYTES)
        require(PRIMARY_KEY_ID.matches(primaryKeyId)) { "encoded Seal primary key id is invalid" }
        val workingDirectory = input.readNullableUtf8(MAX_WORKING_DIRECTORY_BYTES)
        val commit = when (input.readUnsignedByte()) {
            0 -> null
            1 -> {
                val treeId = input.readUtf8(OBJECT_ID_MAX_BYTES)
                require(OBJECT_ID.matches(treeId)) { "encoded Seal tree id is invalid" }
                val parents = input.readBoundedCount(MAX_PARENT_IDS).let { count ->
                    buildList(count) { repeat(count) { add(input.readUtf8(OBJECT_ID_MAX_BYTES)) } }
                }
                require(parents.all(OBJECT_ID::matches)) { "encoded Seal parent id is invalid" }
                val author = input.readUtf8(MAX_IDENTITY_BYTES)
                val committer = input.readUtf8(MAX_IDENTITY_BYTES)
                val message = input.readUtf8(MAX_MESSAGE_BYTES, allowEmpty = true, allowLineBreaks = true)
                val headers = input.readBoundedCount(MAX_HEADERS).let { count ->
                    buildList(count) {
                        repeat(count) {
                            add(
                                GitCommitDisplayHeader(
                                    name = input.readUtf8(MAX_HEADER_NAME_BYTES),
                                    value = input.readUtf8(MAX_HEADER_VALUE_BYTES, allowLineBreaks = true),
                                )
                            )
                        }
                    }
                }
                val payloadBytes = input.readInt()
                require(payloadBytes in 0..OpenPgpSignLimits.MAX_PAYLOAD_BYTES) {
                    "encoded Seal payload size is invalid"
                }
                val truncated = when (input.readUnsignedByte()) {
                    0 -> false
                    1 -> true
                    else -> throw IllegalArgumentException("encoded Seal truncation flag is invalid")
                }
                GitCommitDisplaySnapshot(
                    treeId = treeId,
                    parentIds = parents,
                    author = author,
                    committer = committer,
                    message = message,
                    extraHeaders = headers,
                    payloadBytes = payloadBytes,
                    truncated = truncated,
                )
            }
            else -> throw IllegalArgumentException("encoded Seal display discriminator is invalid")
        }
        SealDisplayPayload(primaryKeyId, workingDirectory, commit)
    }

    private fun GitCommitDisplaySnapshot.validate() {
        require(OBJECT_ID.matches(treeId)) { "Seal display tree id is invalid" }
        require(parentIds.size <= MAX_PARENT_IDS && parentIds.all(OBJECT_ID::matches)) {
            "Seal display parent ids are invalid"
        }
        requireText(author, MAX_IDENTITY_BYTES, "display author")
        requireText(committer, MAX_IDENTITY_BYTES, "display committer")
        requireText(message, MAX_MESSAGE_BYTES, "display message", allowEmpty = true, allowLineBreaks = true)
        require(extraHeaders.size <= MAX_HEADERS) { "Seal display has too many headers" }
        extraHeaders.forEach { header ->
            requireText(header.name, MAX_HEADER_NAME_BYTES, "display header name")
            requireText(header.value, MAX_HEADER_VALUE_BYTES, "display header value", allowLineBreaks = true)
        }
        require(payloadBytes in 0..OpenPgpSignLimits.MAX_PAYLOAD_BYTES) { "display payload size is invalid" }
    }

    private inline fun <T> decode(
        encoded: ByteArray,
        maxBytes: Int,
        block: (DataInputStream) -> T,
    ): T {
        require(encoded.isNotEmpty() && encoded.size <= maxBytes) { "encoded Seal payload is outside its bound" }
        try {
            ByteArrayInputStream(encoded).use { bytes ->
                DataInputStream(bytes).use { input ->
                    val value = block(input)
                    require(bytes.available() == 0) { "encoded Seal payload has trailing bytes" }
                    return value
                }
            }
        } catch (failure: EOFException) {
            throw IllegalArgumentException("encoded Seal payload is truncated", failure)
        }
    }

    private inline fun encode(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use(block)
        bytes.toByteArray()
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
        try {
            require(MessageDigest.isEqual(actual, expected)) { "encoded Seal payload magic is invalid" }
        } finally {
            actual.fill(0)
        }
    }

    private fun DataInputStream.requireVersion() = require(readInt() == VERSION) {
        "encoded Seal payload version is unsupported"
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
                requireSafeText(value, allowEmpty, allowLineBreaks)
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

    private fun requireText(
        value: String?,
        maxBytes: Int,
        label: String,
        allowEmpty: Boolean = false,
        allowLineBreaks: Boolean = false,
    ): String = requireNotNull(value) { "$label is missing" }.also {
        require(it.encodeToByteArray().size <= maxBytes) { "$label exceeds its byte bound" }
        requireSafeText(it, allowEmpty, allowLineBreaks)
    }

    private fun requireSafeText(value: String, allowEmpty: Boolean, allowLineBreaks: Boolean) {
        require(allowEmpty || value.isNotEmpty()) { "Seal text must not be empty" }
        require(value.none { character ->
            character == '\u0000' || character.isISOControl() &&
                (!allowLineBreaks || character !in setOf('\n', '\r', '\t'))
        }) { "Seal text contains unsupported controls" }
    }

    data class SealDisplayPayload(
        val primaryKeyId: String,
        val workingDirectory: String?,
        val commit: GitCommitDisplaySnapshot?,
    )

    private const val MAX_IDENTIFIER_BYTES = 256
    private const val MAX_DISPLAY_IDENTITY_BYTES = 4 * 1024
    private const val MAX_ENROLLMENT_BYTES = 8 * 1024
    private const val MAX_WORKING_DIRECTORY_BYTES = 1_024
    private const val MAX_IDENTITY_BYTES = 4 * 1024
    private const val MAX_MESSAGE_BYTES = 64 * 1024
    private const val MAX_HEADER_NAME_BYTES = 512
    private const val MAX_HEADER_VALUE_BYTES = 8 * 1024
    private const val MAX_PARENT_IDS = 64
    private const val MAX_HEADERS = 64
    private const val MAX_DISPLAY_BYTES = 64 * 1024
    private const val OBJECT_ID_MAX_BYTES = 64
    private const val PRIMARY_KEY_ID_BYTES = 16
    private val PRIMARY_KEY_ID = Regex("[0-9A-F]{16}")
    private val OBJECT_ID = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
}
