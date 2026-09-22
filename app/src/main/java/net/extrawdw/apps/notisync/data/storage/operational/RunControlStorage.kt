package net.extrawdw.apps.notisync.data.storage.operational

import android.content.ContentValues
import android.database.Cursor
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind

/** Exact control fields shared by the durable outbox and its legacy migration. */
internal object RunControlStorage {
    val columns = listOf(
        "request_id", "host_client", "run_id", "kind", "requested_at",
        "interaction_generation", "input_text", "signal",
    )

    fun values(control: RunControl): LinkedHashMap<String, Any?> = linkedMapOf(
        "request_id" to control.requestId,
        "host_client" to control.hostClientId.value,
        "run_id" to control.runId,
        "kind" to control.kind.name,
        "requested_at" to control.requestedAt,
        "interaction_generation" to control.interactionGeneration,
        "input_text" to control.inputText,
        "signal" to control.signal,
    )

    fun contentValues(control: RunControl): ContentValues = ContentValues().apply {
        values(control).forEach { (column, value) ->
            when (value) {
                null -> putNull(column)
                is String -> put(column, value)
                is Long -> put(column, value)
                else -> error("Unsupported Run control column type")
            }
        }
    }

    fun read(cursor: Cursor): RunControl = reconstruct(columns.associateWith { column ->
        val index = cursor.getColumnIndexOrThrow(column)
        when {
            cursor.isNull(index) -> null
            column == "requested_at" || column == "interaction_generation" -> cursor.getLong(index)
            else -> cursor.getString(index)
        }
    })

    fun reconstruct(row: Map<String, Any?>): RunControl = RunControl(
        requestId = row.getValue("request_id") as String,
        hostClientId = ClientId(row.getValue("host_client") as String),
        runId = row.getValue("run_id") as String,
        kind = RunControlKind.valueOf(row.getValue("kind") as String),
        requestedAt = (row.getValue("requested_at") as Number).toLong(),
        interactionGeneration = (row["interaction_generation"] as Number?)?.toLong(),
        inputText = row["input_text"] as String?,
        signal = row["signal"] as String?,
    )
}
