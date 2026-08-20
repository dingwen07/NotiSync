package net.extrawdw.apps.notisync.data.storage.core

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverter
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "broker_auth_token",
    indices = [Index(value = ["singleton", "broker_endpoint_revision"])],
    foreignKeys = [
        ForeignKey(
            entity = CoreTransportStateEntity::class,
            parentColumns = ["singleton", "broker_endpoint_revision"],
            childColumns = ["singleton", "broker_endpoint_revision"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class BrokerAuthTokenEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton", defaultValue = "1")
    val singleton: Int = 1,
    @ColumnInfo(name = "wrapped_token", typeAffinity = ColumnInfo.BLOB)
    val wrappedToken: ByteArray,
    @ColumnInfo(name = "encoding_version")
    val encodingVersion: Int,
    @ColumnInfo(name = "issued_at")
    val issuedAt: Long? = null,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long? = null,
    /**
     * Intentional ABA fence. This duplicates the owning transport revision, but the composite foreign key and
     * conditional transaction prevent a token minted for an earlier visit to the same URL from becoming current.
     */
    @ColumnInfo(name = "broker_endpoint_revision")
    val brokerEndpointRevision: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "core_transport_state",
    indices = [Index(value = ["singleton", "broker_endpoint_revision"], unique = true)],
)
internal data class CoreTransportStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton", defaultValue = "1")
    val singleton: Int = 1,
    @ColumnInfo(name = "broker_url")
    val brokerUrl: String,
    @ColumnInfo(name = "group_id")
    val groupId: String? = null,
    @ColumnInfo(name = "fcm_route_ref")
    val fcmRouteRef: String? = null,
    @ColumnInfo(name = "route_epoch")
    val routeEpoch: Long,
    @ColumnInfo(name = "broker_endpoint_revision", defaultValue = "0")
    val brokerEndpointRevision: Long = 0,
    @ColumnInfo(name = "self_epoch_activated_at")
    val selfEpochActivatedAt: Long? = null,
    @ColumnInfo(name = "operational_generation")
    val operationalGeneration: Long,
    @ColumnInfo(name = "operational_incarnation_id")
    val operationalIncarnationId: String,
    @ColumnInfo(name = "replay_fence_state")
    val replayFenceState: ReplayFenceState,
    @ColumnInfo(name = "continuity_origin")
    val continuityOrigin: OperationalContinuityOrigin? = null,
    @ColumnInfo(name = "replay_fence_id")
    val replayFenceId: String? = null,
    @ColumnInfo(name = "replay_fence_epoch")
    val replayFenceEpoch: Int? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

internal fun BrokerAuthTokenEntity.toSnapshot(): BrokerAuthTokenSnapshot = BrokerAuthTokenSnapshot(
    wrappedToken = wrappedToken.copyOf(),
    encodingVersion = encodingVersion,
    issuedAt = issuedAt,
    expiresAt = expiresAt,
    brokerEndpointRevision = brokerEndpointRevision,
    updatedAt = updatedAt,
)

internal fun CoreTransportStateEntity.toSnapshot(): CoreTransportSnapshot {
    check(operationalGeneration > 0) { "Stored operational generation must be positive" }
    validateOperationalStorageIncarnationId(operationalIncarnationId)
    when (replayFenceState) {
        ReplayFenceState.CONTINUITY_INTACT -> {
            check(continuityOrigin != null) { "Intact continuity requires its immutable origin" }
            check(replayFenceId == null && replayFenceEpoch == null) {
                "Intact continuity cannot claim cryptographic fence evidence"
            }
        }
        ReplayFenceState.ESTABLISHED -> {
            check(continuityOrigin == null) { "A reset generation cannot retain initial continuity origin" }
            check(!replayFenceId.isNullOrBlank() && replayFenceEpoch != null && replayFenceEpoch > 0) {
                "An established replay fence requires complete evidence"
            }
        }
        ReplayFenceState.FENCE_REQUIRED,
        ReplayFenceState.ESTABLISHING,
        ReplayFenceState.BLOCKED,
        -> {
            check(continuityOrigin == null) { "A reset generation cannot retain initial continuity origin" }
            check(replayFenceId == null && replayFenceEpoch == null) {
                "An incomplete replay fence cannot retain evidence"
            }
        }
    }
    return CoreTransportSnapshot(
        brokerUrl = brokerUrl,
        groupId = groupId,
        fcmRouteRef = fcmRouteRef,
        routeEpoch = routeEpoch,
        brokerEndpointRevision = brokerEndpointRevision,
        selfEpochActivatedAt = selfEpochActivatedAt,
        operationalGeneration = operationalGeneration,
        operationalIncarnationId = operationalIncarnationId,
        replayFenceState = replayFenceState,
        continuityOrigin = continuityOrigin,
        replayFenceId = replayFenceId,
        replayFenceEpoch = replayFenceEpoch,
        updatedAt = updatedAt,
    )
}

internal object ReplayFenceStateConverter {
    @ColumnTypeConverter
    fun encode(value: ReplayFenceState): String = value.token

    @ColumnTypeConverter
    fun decode(value: String): ReplayFenceState = ReplayFenceState.fromToken(value)
}

internal object OperationalContinuityOriginConverter {
    @ColumnTypeConverter
    fun encode(value: OperationalContinuityOrigin): String = value.token

    @ColumnTypeConverter
    fun decode(value: String): OperationalContinuityOrigin = OperationalContinuityOrigin.fromToken(value)
}
