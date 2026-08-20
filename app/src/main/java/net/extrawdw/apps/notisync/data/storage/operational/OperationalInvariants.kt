package net.extrawdw.apps.notisync.data.storage.operational

import java.security.MessageDigest

internal object OperationalRetention {
    const val ACTIVITY_MAX_ROWS = 1_000
    const val ACTIVITY_MAX_AGE_MILLIS = 90L * 24 * 60 * 60 * 1_000
    const val ACTIVITY_PRUNE_BATCH_SIZE = 64

    const val MESSAGE_LEDGER_RETENTION_MILLIS = 72L * 60 * 60 * 1_000

    const val RUN_ACTIVE_STALE_AFTER_MILLIS = 3L * 60 * 60 * 1_000
    const val RUN_COMPLETED_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
    const val RUN_MAX_STORAGE_BYTES = 100L * 1024 * 1024
    const val RUN_MAX_COMPLETED_ROWS = 50
    const val RUN_PRUNE_BATCH_SIZE = 64

    const val SEAL_HISTORY_RETENTION_MILLIS = 72L * 60 * 60 * 1_000
    const val SEAL_MAX_HISTORY_ROWS = 500
    const val SEAL_PRUNE_BATCH_SIZE = 64
    const val SEAL_MAX_PENDING_GLOBAL = 10
    const val SEAL_MAX_PENDING_PER_SENDER = 3

    const val SCREEN_MAX_REPLAY_ROWS = 512

    const val SSH_MAX_HISTORY_ROWS = 500
    const val SSH_PRUNE_BATCH_SIZE = 64
    const val SSH_MAX_PENDING_GLOBAL = 128
    const val SSH_MAX_PENDING_PER_REQUESTER = 16
    const val SSH_MAX_REMEMBERED_AUTHORIZATIONS_GLOBAL = 1_024
    const val SSH_MAX_REMEMBERED_AUTHORIZATIONS_PER_KEY = 128
}

internal object OperationalStorageLimits {
    const val MAX_ID_CHARS = 256
    const val MAX_CODE_CHARS = 128
    const val MAX_STORAGE_INCARNATION_ID_CHARS = 128
    const val MAX_PACKAGE_CHARS = 512
    const val MAX_DISPLAY_CHARS = 1_024
    const val MAX_ACTIVITY_RENDER_ARGS_BYTES = 4 * 1_024
    const val MAX_PROTECTED_BLOB_BYTES = 16 * 1024 * 1024
    const val SEAL_ENROLLMENT_MAX_PLAINTEXT_BYTES = 8 * 1024
    const val SEAL_ENROLLMENT_MAX_CIPHERTEXT_BYTES =
        SEAL_ENROLLMENT_MAX_PLAINTEXT_BYTES + 16
    const val SHA256_BYTES = 32
    const val RELAY_BATCH_PAGE_MAX_ROWS = 128
}

internal fun requireIdentifier(value: String, name: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value.length <= OperationalStorageLimits.MAX_ID_CHARS) { "$name is too long" }
    require(value.none(Char::isISOControl)) { "$name contains control characters" }
}

internal fun requireCode(value: String?, name: String) {
    if (value == null) return
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value.length <= OperationalStorageLimits.MAX_CODE_CHARS) { "$name is too long" }
    require(value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
        "$name contains unsupported characters"
    }
}

internal fun requireStorageIncarnationId(value: String) {
    require(value.isNotBlank()) { "storage incarnation id must not be blank" }
    require(value.length <= OperationalStorageLimits.MAX_STORAGE_INCARNATION_ID_CHARS) {
        "storage incarnation id is too long"
    }
    require(
        value.all { character ->
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                character == '_' || character == '-' || character == '.'
        },
    ) { "storage incarnation id contains unsupported characters" }
}

/** Validates a persisted SHA-256 projection when the DAO owns both the canonical bytes and digest. */
internal fun requireSha256Projection(bytes: ByteArray, digest: ByteArray, name: String) {
    require(digest.size == OperationalStorageLimits.SHA256_BYTES) { "$name must be SHA-256" }
    require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes), digest)) {
        "$name does not match its canonical bytes"
    }
}

internal fun requireProtectedBlob(
    scheme: String,
    version: Int,
    keyRef: String,
    generation: Long,
    payloadCodecVersion: Int,
    ciphertext: ByteArray,
    nonce: ByteArray,
) {
    require(scheme == ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM) {
        "plaintext or unknown protected-blob schemes are forbidden"
    }
    require(version > 0) { "protected-blob version must be positive" }
    requireIdentifier(keyRef, "protected-blob key reference")
    require(generation >= 0) { "protected-blob generation must not be negative" }
    require(payloadCodecVersion > 0) { "protected payload codec version must be positive" }
    require(ciphertext.size >= 16) { "protected ciphertext is too short" }
    require(ciphertext.size <= OperationalStorageLimits.MAX_PROTECTED_BLOB_BYTES) {
        "protected ciphertext exceeds the operational schema bound"
    }
    // AAD binding, role-specific bounds, and current-generation key custody are enforced by the
    // reviewed protection boundary before the short Room transaction.
    require(nonce.size == 12) {
        "protected-blob nonce/parameters are invalid"
    }
}

internal fun ActivityEventEntity.requireValid() {
    requireIdentifier(eventId, "activity event id")
    require(occurredAt > 0 && recordedAt > 0) { "activity timestamps must be positive" }
    peerClientId?.let { requireIdentifier(it, "activity peer id") }
    correlationId?.let { requireIdentifier(it, "activity correlation id") }
    require(renderArgsVersion > 0) { "activity render-args version must be positive" }
    require(renderArgs.size <= OperationalStorageLimits.MAX_ACTIVITY_RENDER_ARGS_BYTES) {
        "activity render args exceed the privacy-reviewed bound"
    }
    require(coalescingKeyToken == null || coalescingKeyToken.size == OperationalStorageLimits.SHA256_BYTES) {
        "activity coalescing token is invalid"
    }
    require(coalescedCount > 0) { "activity coalesced count must be positive" }
}

internal fun MessageDedupEntity.requireValid(allowLegacyMessageIdOnly: Boolean) {
    requireIdentifier(messageId, "handled message id")
    when (evidenceKind) {
        MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY -> {
            require(allowLegacyMessageIdOnly) { "new handled outcomes require an authenticated fingerprint" }
            require(authenticatedFingerprint == null) {
                "legacy message-id-only provenance cannot carry a synthesized fingerprint"
            }
        }
        MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT -> {
            require(authenticatedFingerprint?.size == OperationalStorageLimits.SHA256_BYTES) {
                "authenticated handled outcome requires a SHA-256 fingerprint"
            }
        }
    }
    require(handledAt > 0) { "handled time must be positive" }
}

internal fun RelayBatchStageEntity.requireValid() {
    requireIdentifier(messageId, "relay batch message id")
    requireAuthenticatedFingerprint(authenticatedFingerprint, "relay batch authenticated fingerprint")
}

internal fun requireAuthenticatedFingerprint(fingerprint: ByteArray, name: String) {
    require(fingerprint.size == OperationalStorageLimits.SHA256_BYTES) { "$name must be SHA-256" }
}

internal fun SealRequestEntity.requireValid() {
    requireIdentifier(requestId, "Seal request id")
    requireIdentifier(requesterClientId, "Seal requester id")
    requireIdentifier(senderClientId, "Seal sender id")
    require(requestFingerprint.isNotEmpty()) { "Seal request fingerprint must not be empty" }
    require(payloadSha256.size == OperationalStorageLimits.SHA256_BYTES) {
        "Seal payload digest must be SHA-256"
    }
    require(issuedAt > 0 && expiresAt > issuedAt && createdAt > 0 && updatedAt >= createdAt) {
        "Seal request timestamps are invalid"
    }
    requireProtectedBlob(
        displayProtectionScheme,
        displayProtectionVersion,
        displayProtectionKeyRef,
        displayProtectionGeneration,
        displayPayloadCodecVersion,
        displayCiphertext,
        displayNonce,
    )
    require((outcome == null) == (decisionAt == null)) {
        "Seal outcome and decision time must be present together"
    }
    when (state) {
        SealRequestState.PENDING_REVIEW,
        SealRequestState.USER_APPROVED,
        SealRequestState.PROVIDER_INTERACTION ->
            require(outcome == null) { "active Seal request cannot have a terminal outcome" }

        SealRequestState.RESPONSE_QUEUED,
        SealRequestState.SENT,
        SealRequestState.CANCELLED,
        SealRequestState.EXPIRED,
        SealRequestState.FAILED -> require(outcome != null) { "terminal Seal request requires an outcome" }
    }
}

internal fun SealPendingPayloadEntity.requireValid() {
    requireIdentifier(requestId, "Seal pending request id")
    requireProtectedBlob(
        protectionScheme,
        protectionVersion,
        protectionKeyRef,
        protectionGeneration,
        payloadCodecVersion,
        payloadCiphertext,
        payloadNonce,
    )
    require(createdAt > 0 && updatedAt >= createdAt) { "Seal pending-payload timestamps are invalid" }
}

internal fun strongBoxFactsAreValid(
    securityLevel: SshSecurityLevelToken,
    strongBoxAttempted: Boolean,
    strongBoxFallback: Boolean,
): Boolean =
    (!strongBoxFallback || (strongBoxAttempted && securityLevel == SshSecurityLevelToken.TRUSTED_ENVIRONMENT)) &&
        (securityLevel != SshSecurityLevelToken.STRONGBOX || strongBoxAttempted) &&
        (!strongBoxAttempted || strongBoxFallback || securityLevel == SshSecurityLevelToken.STRONGBOX)
