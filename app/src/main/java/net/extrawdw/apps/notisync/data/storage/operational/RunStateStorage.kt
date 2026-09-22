package net.extrawdw.apps.notisync.data.storage.operational

import android.content.ContentValues
import android.database.Cursor
import net.extrawdw.apps.notisync.run.StoredRun
import net.extrawdw.apps.notisync.run.StoredRunRevision
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunBlockedReason
import net.extrawdw.notisync.protocol.RunLlmSummary
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunProgress
import net.extrawdw.notisync.protocol.RunPromptKind
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason

/** Complete received revisions use columns; only the ordered command arguments need JSON. */
internal object RunStateStorage {
    fun revisionValues(state: RunState, receivedAt: Long): Map<String, Any?> = linkedMapOf(
        "host_client" to state.hostClientId.value,
        "run_id" to state.runId,
        "revision" to state.revision,
        "phase" to state.phase.name,
        "update_reason" to state.updateReason.name,
        "started_at" to state.startedAt,
        "updated_at" to state.updatedAt,
        "argv_json" to ProtocolCodec.encodeToJson(state.argv),
        "cwd" to state.cwd,
        "uses_pty" to if (state.usesPty) 1 else 0,
        "terminal_text" to state.terminal.text,
        "terminal_truncated" to if (state.terminal.truncated) 1 else 0,
        "terminal_raw_bytes_seen" to state.terminal.rawBytesSeen,
        "interaction_generation" to state.interactionGeneration,
        "ended_at" to state.endedAt,
        "duration_ms" to state.durationMs,
        "blocked_reason" to state.blockedReason?.name,
        "prompt" to state.prompt?.name,
        "progress_current" to state.progress?.current,
        "progress_total" to state.progress?.total,
        // NULL distinguishes absent progress from an indeterminate progress with no counts.
        "progress_indeterminate" to state.progress?.let { if (it.indeterminate) 1 else 0 },
        "exit_code" to state.exitCode,
        "failure_message" to state.failureMessage,
        "llm_title" to state.llmSummary?.title,
        "llm_text" to state.llmSummary?.text,
        "llm_expanded_text" to state.llmSummary?.expandedText,
        "response_to_request_id" to state.responseToRequestId,
        "received_at" to receivedAt,
    )

    fun sessionValues(stored: StoredRun): Map<String, Any?> = linkedMapOf(
        "host_client" to stored.state.hostClientId.value,
        "run_id" to stored.state.runId,
        "current_revision" to stored.state.revision,
        "presented_revision" to stored.presentedRevision,
        "active" to if (stored.active) 1 else 0,
        "updated_at" to stored.state.updatedAt,
        "received_at" to stored.receivedAt,
    )

    fun reconstructRevision(row: Map<String, Any?>): StoredRunRevision {
        fun text(name: String): String? = row[name] as String?
        fun number(name: String): Long? = (row[name] as Number?)?.toLong()
        fun boolean(name: String): Boolean? = number(name)?.let {
            require(it == 0L || it == 1L) { "Invalid Run boolean column: $name" }
            it == 1L
        }
        val indeterminate = boolean("progress_indeterminate")
        val progress = indeterminate?.let {
            RunProgress(number("progress_current"), number("progress_total"), it)
        }
        require(indeterminate != null || (number("progress_current") == null && number("progress_total") == null)) {
            "Run progress counts require a progress mode"
        }
        val title = text("llm_title")
        val summary = title?.let {
            RunLlmSummary(it, requireNotNull(text("llm_text")), text("llm_expanded_text"))
        }
        require(title != null || (text("llm_text") == null && text("llm_expanded_text") == null)) {
            "Run summary text requires a title"
        }
        val exitCode = number("exit_code")?.let {
            require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Invalid Run exit code" }
            it.toInt()
        }
        return StoredRunRevision(
            state = RunState(
                hostClientId = ClientId(requireNotNull(text("host_client"))),
                runId = requireNotNull(text("run_id")),
                revision = requireNotNull(number("revision")),
                phase = RunPhase.valueOf(requireNotNull(text("phase"))),
                updateReason = RunUpdateReason.valueOf(requireNotNull(text("update_reason"))),
                startedAt = requireNotNull(number("started_at")),
                updatedAt = requireNotNull(number("updated_at")),
                argv = ProtocolCodec.decodeFromJson<List<String>>(requireNotNull(text("argv_json"))),
                cwd = requireNotNull(text("cwd")),
                usesPty = requireNotNull(boolean("uses_pty")),
                terminal = RunTerminalSnapshot(
                    requireNotNull(text("terminal_text")),
                    requireNotNull(boolean("terminal_truncated")),
                    requireNotNull(number("terminal_raw_bytes_seen")),
                ),
                interactionGeneration = requireNotNull(number("interaction_generation")),
                endedAt = number("ended_at"),
                durationMs = number("duration_ms"),
                blockedReason = text("blocked_reason")?.let(RunBlockedReason::valueOf),
                prompt = text("prompt")?.let(RunPromptKind::valueOf),
                progress = progress,
                exitCode = exitCode,
                failureMessage = text("failure_message"),
                llmSummary = summary,
                responseToRequestId = text("response_to_request_id"),
            ),
            receivedAt = requireNotNull(number("received_at")),
        )
    }

    fun readRevision(cursor: Cursor): StoredRunRevision = reconstructRevision(cursor.row())

    fun readCurrent(cursor: Cursor): StoredRun {
        val row = cursor.row()
        val revision = reconstructRevision(row)
        val active = requireNotNull(row["active"] as Number?).toLong()
        require(active == 0L || active == 1L) { "Invalid Run activity state" }
        return StoredRun(
            state = revision.state,
            receivedAt = revision.receivedAt,
            presentedRevision = requireNotNull(row["presented_revision"] as Number?).toLong(),
            active = active == 1L,
        )
    }

    fun contentValues(values: Map<String, Any?>): ContentValues = ContentValues().apply {
        values.forEach { (name, value) ->
            when (value) {
                null -> putNull(name)
                is String -> put(name, value)
                is Long -> put(name, value)
                is Int -> put(name, value)
                else -> error("Unsupported Run SQLite column: $name")
            }
        }
    }

    private fun Cursor.row(): Map<String, Any?> = columnNames.mapIndexed { index, name ->
        name to when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            Cursor.FIELD_TYPE_STRING -> getString(index)
            else -> error("Unexpected Run SQLite column type: $name")
        }
    }.toMap()
}
