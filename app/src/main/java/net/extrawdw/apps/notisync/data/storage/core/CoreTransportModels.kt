package net.extrawdw.apps.notisync.data.storage.core

/**
 * Continuity is ready either because no operational-ledger loss occurred for this identity generation, or because
 * an explicitly required cryptographic replay fence was established after a destructive operational reset.
 */
internal enum class ReplayFenceState(val token: String) {
    CONTINUITY_INTACT("CONTINUITY_INTACT"),
    FENCE_REQUIRED("FENCE_REQUIRED"),
    ESTABLISHING("ESTABLISHING"),
    ESTABLISHED("ESTABLISHED"),
    BLOCKED("BLOCKED"),
    ;

    companion object {
        fun fromToken(token: String): ReplayFenceState = entries.firstOrNull { it.token == token }
            ?: error("Unknown replay fence state token")
    }
}

internal val ReplayFenceState.isRuntimeReady: Boolean
    get() = this == ReplayFenceState.CONTINUITY_INTACT || this == ReplayFenceState.ESTABLISHED

internal const val INITIAL_OPERATIONAL_GENERATION = 1L
internal const val MAX_OPERATIONAL_STORAGE_INCARNATION_ID_CHARS = 128

internal data class OperationalContinuityMarkerEvidence(
    val operationalGeneration: Long,
    val storageIncarnationId: String,
    val postCutoverWriteAt: Long?,
    val validatedAt: Long,
) {
    init {
        require(operationalGeneration == INITIAL_OPERATIONAL_GENERATION) {
            "Initial continuity evidence must describe generation 1"
        }
        validateOperationalStorageIncarnationId(storageIncarnationId)
        require(postCutoverWriteAt == null) {
            "Initial continuity cannot be asserted after an Operational write"
        }
        require(validatedAt >= 0) { "Operational marker validation time must not be negative" }
    }
}

/** Immutable evidence discriminator for an initial generation that did not lose an accepted operational ledger. */
internal enum class OperationalContinuityOrigin(val token: String) {
    FRESH_IDENTITY("FRESH_IDENTITY"),
    VERIFIED_V51_CUTOVER("VERIFIED_V51_CUTOVER"),
    ;

    companion object {
        fun fromToken(token: String): OperationalContinuityOrigin = entries.firstOrNull { it.token == token }
            ?: error("Unknown operational continuity origin token")
    }
}

internal data class BrokerAuthTokenInput(
    val wrappedToken: ByteArray,
    val encodingVersion: Int,
    val issuedAt: Long? = null,
    val expiresAt: Long? = null,
    val expectedBrokerEndpointRevision: Long,
)

internal data class BrokerAuthTokenSnapshot(
    val wrappedToken: ByteArray,
    val encodingVersion: Int,
    val issuedAt: Long?,
    val expiresAt: Long?,
    val brokerEndpointRevision: Long,
    val updatedAt: Long,
)

internal enum class BrokerAuthTokenSaveResult {
    SAVED,
    STALE_ENDPOINT,
    MISSING_TRANSPORT,
}

/** A brand-new identity has no previously accepted operational ledger to lose or replay. */
internal data class FreshIdentityTransportInitialization(
    val brokerUrl: String,
)

/**
 * Exact Operational authority identity bound into Core transport after the caller has completed origin selection,
 * target validation, and any required key self-tests. It is intentionally not an import/readiness evidence bundle.
 */
internal data class OperationalStorageBinding(
    val operationalGeneration: Long,
    val storageIncarnationId: String,
) {
    init {
        require(operationalGeneration > 0) { "Operational generation must be positive" }
        validateOperationalStorageIncarnationId(storageIncarnationId)
    }
}

internal enum class CoreTransportInitializationResult {
    INITIALIZED,
    ALREADY_INITIALIZED,
    CONFLICT,
}

internal data class CoreTransportSnapshot(
    val brokerUrl: String,
    val groupId: String?,
    val fcmRouteRef: String?,
    val routeEpoch: Long,
    val brokerEndpointRevision: Long,
    val selfEpochActivatedAt: Long?,
    val operationalGeneration: Long,
    val operationalIncarnationId: String,
    val replayFenceState: ReplayFenceState,
    val continuityOrigin: OperationalContinuityOrigin?,
    val replayFenceId: String?,
    val replayFenceEpoch: Int?,
    val updatedAt: Long,
)

internal fun validateOperationalStorageIncarnationId(value: String) {
    require(value.isNotBlank()) { "Operational storage incarnation ID must not be blank" }
    require(value.length <= MAX_OPERATIONAL_STORAGE_INCARNATION_ID_CHARS) {
        "Operational storage incarnation ID is too long"
    }
    require(
        value.all { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '_' || character == '-' || character == '.'
        },
    ) { "Operational storage incarnation ID contains unsupported characters" }
}

internal data class RouteUpdate(
    val brokerUrl: String,
    val fcmRouteRef: String?,
    val routeEpoch: Long,
    val expectedBrokerEndpointRevision: Long,
    val selfEpochActivatedAt: Long?,
)

internal data class RouteMutation(
    val brokerUrl: String,
    val fcmRouteRef: String?,
    val routeEpoch: Long,
    val expectedBrokerEndpointRevision: Long,
    val selfEpochActivatedAt: Long?,
    val updatedAt: Long,
)

internal enum class RouteAdvanceResult {
    ADVANCED,
    UNCHANGED,
    STALE,
    CONFLICT,
    STALE_ENDPOINT,
    MISSING,
}

internal enum class BrokerEndpointChangeResult {
    CHANGED,
    UNCHANGED,
    MISSING,
}

internal enum class GroupIdUpdateResult {
    UPDATED,
    UNCHANGED,
    MISSING,
}

internal enum class OperationalGenerationResult {
    ADVANCED,
    UNCHANGED,
    STALE,
    NON_SEQUENTIAL,
    MISSING,
}

internal enum class ReplayFenceResult {
    CONTINUITY_INTACT,
    ESTABLISHING,
    ESTABLISHED,
    ALREADY_ESTABLISHED,
    STALE_GENERATION,
    BLOCKED,
    MISSING,
}

internal const val MAX_CORE_GROUP_ID_CHARS = 256

internal fun validateCoreGroupId(groupId: String?) {
    if (groupId == null) return
    require(groupId.isNotBlank()) { "groupId must not be blank" }
    require(groupId.length <= MAX_CORE_GROUP_ID_CHARS) { "groupId is too long" }
    require(groupId.none(Char::isISOControl)) { "groupId must not contain control characters" }
}
