package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/** Local presentation state and pointer to the latest received authoritative revision. */
@Entity(
    tableName = "runs",
    primaryKeys = ["host_client", "run_id"],
    indices = [
        Index(
            value = ["active", "updated_at", "host_client", "run_id"],
            orders = [Index.Order.DESC, Index.Order.DESC, Index.Order.ASC, Index.Order.ASC],
            name = "runs_order_idx",
        ),
        Index(value = ["active", "received_at"], name = "runs_retention_idx"),
    ],
)
internal data class RunEntity(
    @ColumnInfo(name = "host_client") val hostClient: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "current_revision") val currentRevision: Long,
    @ColumnInfo(name = "presented_revision") val presentedRevision: Long,
    @ColumnInfo(name = "active") val active: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

/** One complete received RunState. Collection order is meaningful only for argv. */
@Entity(
    tableName = "run_revisions",
    primaryKeys = ["host_client", "run_id", "revision"],
    foreignKeys = [ForeignKey(
        entity = RunEntity::class,
        parentColumns = ["host_client", "run_id"],
        childColumns = ["host_client", "run_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class RunRevisionEntity(
    @ColumnInfo(name = "host_client") val hostClient: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "phase") val phase: String,
    @ColumnInfo(name = "update_reason") val updateReason: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "argv_json") val argvJson: String,
    @ColumnInfo(name = "cwd") val cwd: String,
    @ColumnInfo(name = "uses_pty") val usesPty: Boolean,
    @ColumnInfo(name = "terminal_text") val terminalText: String,
    @ColumnInfo(name = "terminal_truncated") val terminalTruncated: Boolean,
    @ColumnInfo(name = "terminal_raw_bytes_seen") val terminalRawBytesSeen: Long,
    @ColumnInfo(name = "interaction_generation") val interactionGeneration: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "blocked_reason") val blockedReason: String?,
    @ColumnInfo(name = "prompt") val prompt: String?,
    @ColumnInfo(name = "progress_current") val progressCurrent: Long?,
    @ColumnInfo(name = "progress_total") val progressTotal: Long?,
    @ColumnInfo(name = "progress_indeterminate") val progressIndeterminate: Boolean?,
    @ColumnInfo(name = "exit_code") val exitCode: Int?,
    @ColumnInfo(name = "failure_message") val failureMessage: String?,
    @ColumnInfo(name = "llm_title") val llmTitle: String?,
    @ColumnInfo(name = "llm_text") val llmText: String?,
    @ColumnInfo(name = "llm_expanded_text") val llmExpandedText: String?,
    @ColumnInfo(name = "response_to_request_id") val responseToRequestId: String?,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

@Entity(
    tableName = "run_controls",
    primaryKeys = ["request_id"],
    indices = [Index(value = ["requested_at", "request_id"], name = "run_controls_order_idx")],
)
internal data class RunControlEntity(
    @ColumnInfo(name = "request_id") val requestId: String,
    @ColumnInfo(name = "host_client") val hostClient: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "requested_at") val requestedAt: Long,
    @ColumnInfo(name = "interaction_generation") val interactionGeneration: Long?,
    @ColumnInfo(name = "input_text") val inputText: String?,
    @ColumnInfo(name = "signal") val signal: String?,
)
