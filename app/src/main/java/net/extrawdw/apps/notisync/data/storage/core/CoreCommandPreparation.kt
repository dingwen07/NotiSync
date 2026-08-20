package net.extrawdw.apps.notisync.data.storage.core

import java.security.MessageDigest
import net.extrawdw.apps.notisync.data.activity.ActivityDirection
import net.extrawdw.apps.notisync.data.activity.ActivityEventId
import net.extrawdw.apps.notisync.data.activity.ActivityFeature
import net.extrawdw.apps.notisync.data.activity.ActivityOutcome
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgsCodec
import net.extrawdw.apps.notisync.data.activity.ActivitySemanticCode
import net.extrawdw.apps.notisync.data.activity.ActivityStableIdentifier

/** Immutable, repository-derived idempotency identity used by both the fast path and the write transaction. */
internal class PreparedCoreCommandIdentity(
    val commandId: String,
    val authenticatedRequestId: String,
    commandDigest: ByteArray,
    val commandType: String,
) {
    private val storedCommandDigest = commandDigest.copyOf()
    val commandDigest: ByteArray get() = storedCommandDigest.copyOf()
}

/** Privacy-reviewed Activity values prepared before the write transaction. */
internal class PreparedCoreCommandActivity(
    val eventId: String,
    val feature: String,
    val semanticAction: String,
    val direction: String,
    val outcome: String,
    val peerClientId: String?,
    val correlationId: String,
    val deliveryMode: String?,
    val argsVersion: Int,
    renderArgs: ByteArray,
    val occurredAt: Long,
) {
    private val storedRenderArgs = renderArgs.copyOf()
    val renderArgs: ByteArray get() = storedRenderArgs.copyOf()
}

/**
 * Pure preparation of the immutable handoff identity. This is intentionally outside Room: hashing a hostile or
 * merely large canonical command must never lengthen the database write transaction.
 */
internal fun CoreTrustCommand.prepareIdentity(): PreparedCoreCommandIdentity {
    requireCompactCoreIdentifier(commandId, "core command id")
    requireCompactCoreIdentifier(authenticatedRequestId, "authenticated core request id")
    require(expectedOperationalGeneration > 0) { "expected operational generation must be positive" }
    validateOperationalStorageIncarnationId(expectedOperationalIncarnationId)
    expectedSnapshotDigestCopy()?.let { digest ->
        require(digest.size == TRUST_SNAPSHOT_DIGEST_BYTES) {
            "expected trust snapshot digest must be SHA-256"
        }
    }
    val canonical = canonicalCommandCopy()
    require(canonical.size in 1..MAX_CORE_COMMAND_CANONICAL_BYTES) {
        "canonical core command is outside the reviewed memory bound"
    }
    return PreparedCoreCommandIdentity(
        commandId = commandId,
        authenticatedRequestId = authenticatedRequestId,
        commandDigest = MessageDigest.getInstance("SHA-256").digest(canonical),
        commandType = commandType.token,
    )
}

/**
 * Pure Activity preparation. Event identity is a function of command kind and command ID, not mutable presentation
 * content, so retries and process restarts address the same outbox row.
 */
internal fun CoreTrustCommand.prepareActivity(): PreparedCoreCommandActivity? = activity?.let { projection ->
    require(projection.occurredAt > 0) { "core Activity occurrence time must be positive" }
    projection.peerClientId?.let { requireCompactCoreIdentifier(it, "core Activity peer id") }
    val feature = when (commandType) {
        CoreTrustCommandType.DATA_SYNC_PROFILE -> ActivityFeature.PROFILE
        CoreTrustCommandType.DATA_SYNC_TRUST,
        CoreTrustCommandType.DATA_SYNC_CARD -> ActivityFeature.TRUST
    }
    val eventId = coreCommandActivityEventId(commandType, commandId)
    PreparedCoreCommandActivity(
        eventId = eventId,
        feature = feature.token,
        semanticAction = projection.action.token,
        direction = ActivityDirection.INBOUND.token,
        outcome = ActivityOutcome.SUCCESS.token,
        peerClientId = projection.peerClientId,
        correlationId = authenticatedRequestId,
        deliveryMode = projection.deliveryMode?.token,
        argsVersion = projection.renderArgs.version,
        renderArgs = ActivityRenderArgsCodec.encode(projection.renderArgs),
        occurredAt = projection.occurredAt,
    )
}

internal fun coreCommandActivityEventId(commandType: CoreTrustCommandType, commandId: String): String =
    ActivityEventId.derive(
        semanticCode = ActivitySemanticCode.of("core.${commandType.token}.transition"),
        identifiers = listOf(ActivityStableIdentifier.of(commandId)),
    )

internal fun requireCompactCoreIdentifier(value: String, name: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value.length <= CORE_COMMAND_MAX_IDENTIFIER_CHARS) { "$name is too long" }
    require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
        "$name must be a compact identifier"
    }
}

private const val CORE_COMMAND_MAX_IDENTIFIER_CHARS = 256
