package net.extrawdw.apps.notisync.data.activity

import java.nio.ByteBuffer
import java.security.MessageDigest

/** A validated non-secret identifier that may participate in an Activity idempotency key. */
@JvmInline
value class ActivityStableIdentifier private constructor(val value: String) {
    companion object {
        fun of(value: String): ActivityStableIdentifier {
            require(value.isNotBlank()) { "activity stable identifier must not be blank" }
            require(value.length <= ActivityLimits.MAX_IDENTIFIER_CHARS) {
                "activity stable identifier is too long"
            }
            require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
                "activity stable identifier must be a compact identifier"
            }
            return ActivityStableIdentifier(value)
        }
    }
}

/** A validated semantic code, kept separate from arbitrary strings at producer call sites. */
@JvmInline
value class ActivitySemanticCode private constructor(val value: String) {
    companion object {
        fun of(value: String): ActivitySemanticCode {
            require(value.length in 1..ActivityLimits.MAX_SEMANTIC_CODE_CHARS) {
                "activity semantic code length is outside the reviewed bound"
            }
            require(value.first() in 'a'..'z') { "activity semantic code must start with ASCII lowercase" }
            require(
                value.drop(1).all {
                    it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' || it == '.'
                },
            ) {
                "activity semantic code contains unsupported characters"
            }
            return ActivitySemanticCode(value)
        }
    }
}

/**
 * Stable idempotency identity for a semantic transition.
 *
 * The framed input and explicit domain label prevent collisions with unrelated hashes and ensure that
 * changing the semantic code or identifier ordering produces a different event id.  Callers must supply
 * stable, non-secret identifiers (for example an authenticated request id or a domain revision), never
 * payload bytes, plaintext, keys, paths, commands, or provider responses.
 */
object ActivityEventId {
    private val DOMAIN = "notisync.activity.event-id.v1".encodeToByteArray()
    private const val MAX_IDENTIFIERS = 16

    fun derive(
        semanticCode: ActivitySemanticCode,
        identifiers: List<ActivityStableIdentifier>,
    ): String {
        require(identifiers.isNotEmpty()) {
            "activity idempotency identity requires at least one stable identifier"
        }
        require(identifiers.size <= MAX_IDENTIFIERS) {
            "too many activity idempotency identifiers"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN)
        digest.update(0)
        updateFramed(digest, semanticCode.value.encodeToByteArray())
        updateFramed(digest, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(identifiers.size).array())
        identifiers.forEach { identifier ->
            updateFramed(digest, identifier.value.encodeToByteArray())
        }
        return "activity-v1-" + digest.digest().toHex()
    }

    private fun updateFramed(digest: MessageDigest, bytes: ByteArray) {
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) append("%02x".format(byte.toInt() and 0xFF))
    }
}
