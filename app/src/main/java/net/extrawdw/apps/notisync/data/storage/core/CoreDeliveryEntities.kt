package net.extrawdw.apps.notisync.data.storage.core

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverter
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.security.MessageDigest

@Entity(
    tableName = "core_command_applied",
    indices = [
        Index(value = ["applied_at", "command_id"]),
    ],
)
internal data class CoreCommandAppliedEntity(
    @PrimaryKey
    @ColumnInfo(name = "command_id")
    val commandId: String,
    @ColumnInfo(name = "authenticated_request_id")
    val authenticatedRequestId: String,
    @ColumnInfo(name = "command_digest", typeAffinity = ColumnInfo.BLOB)
    val commandDigest: ByteArray,
    @ColumnInfo(name = "command_type")
    val commandType: String,
    @ColumnInfo(name = "outcome")
    val outcome: CoreCommandOutcome,
    @ColumnInfo(name = "core_revision")
    val coreRevision: Long,
    @ColumnInfo(name = "applied_at")
    val appliedAt: Long,
)

@Entity(
    tableName = "core_activity_outbox",
    foreignKeys = [
        ForeignKey(
            entity = CoreCommandAppliedEntity::class,
            parentColumns = ["command_id"],
            childColumns = ["command_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["event_id"], unique = true),
    ],
)
internal data class CoreActivityOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "command_id")
    val commandId: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "operational_generation")
    val operationalGeneration: Long,
    @ColumnInfo(name = "feature")
    val feature: String,
    @ColumnInfo(name = "semantic_action")
    val semanticAction: String,
    @ColumnInfo(name = "direction")
    val direction: String,
    @ColumnInfo(name = "outcome")
    val outcome: String,
    @ColumnInfo(name = "peer_client_id")
    val peerClientId: String? = null,
    @ColumnInfo(name = "correlation_id")
    val correlationId: String? = null,
    @ColumnInfo(name = "delivery_mode")
    val deliveryMode: String? = null,
    @ColumnInfo(name = "args_version")
    val argsVersion: Int,
    /** Versioned, bounded, privacy-safe render arguments; never raw payload or localized text. */
    @ColumnInfo(name = "render_args", typeAffinity = ColumnInfo.BLOB)
    val renderArgs: ByteArray,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

internal fun CoreCommandAppliedEntity.toSnapshot(): CoreCommandSnapshot = CoreCommandSnapshot(
    commandId = commandId,
    authenticatedRequestId = authenticatedRequestId,
    commandDigest = commandDigest.copyOf(),
    commandType = commandType,
    outcome = outcome,
    coreRevision = coreRevision,
    appliedAt = appliedAt,
)

internal fun CoreCommandAppliedEntity.matchesIdentity(identity: PreparedCoreCommandIdentity): Boolean =
    authenticatedRequestId == identity.authenticatedRequestId &&
        commandType == identity.commandType &&
        MessageDigest.isEqual(commandDigest, identity.commandDigest)

internal fun CoreActivityOutboxEntity.toSnapshot(): CoreActivitySnapshot = CoreActivitySnapshot(
    commandId = commandId,
    eventId = eventId,
    operationalGeneration = operationalGeneration,
    feature = feature,
    semanticAction = semanticAction,
    direction = direction,
    outcome = outcome,
    peerClientId = peerClientId,
    correlationId = correlationId,
    deliveryMode = deliveryMode,
    argsVersion = argsVersion,
    renderArgs = renderArgs.copyOf(),
    occurredAt = occurredAt,
    createdAt = createdAt,
)

internal object CoreCommandOutcomeConverter {
    @ColumnTypeConverter fun encode(value: CoreCommandOutcome): String = value.token
    @ColumnTypeConverter fun decode(value: String): CoreCommandOutcome = CoreCommandOutcome.fromToken(value)
}
