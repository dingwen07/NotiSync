package net.extrawdw.apps.notisync.sign

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpRejectReason
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import net.extrawdw.notisync.protocol.ProtocolCodec

enum class OpenPgpRequestState {
    PENDING_REVIEW,
    USER_APPROVED,
    PROVIDER_INTERACTION,
    SIGNED_PENDING_SEND,
    REJECTED_PENDING_SEND,
    SENT,
    CANCELLED,
    EXPIRED,
    FAILED,
}

data class StoredOpenPgpRequest(
    val request: OpenPgpSignSync,
    val senderClientId: ClientId,
    val state: OpenPgpRequestState,
    val encodedResponse: ByteArray? = null,
    val updatedAt: Long,
)

enum class OpenPgpAcceptResult { STORED, DUPLICATE, CONFLICT, RATE_LIMITED }

class OpenPgpSignStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, VERSION) {
    private val _requests = MutableStateFlow<List<StoredOpenPgpRequest>>(emptyList())
    val requests: StateFlow<List<StoredOpenPgpRequest>> = _requests.asStateFlow()

    init {
        refresh()
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sign_requests (" +
                "request_id TEXT PRIMARY KEY," +
                "requester_client_id TEXT NOT NULL," +
                "sender_client_id TEXT NOT NULL," +
                "primary_key_id TEXT NOT NULL," +
                "issued_at INTEGER NOT NULL," +
                "expires_at INTEGER NOT NULL," +
                "payload_sha256 BLOB NOT NULL," +
                "object_kind TEXT NOT NULL," +
                "payload BLOB," +
                "state TEXT NOT NULL," +
                "encoded_response BLOB," +
                "updated_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX sign_requests_state_idx ON sign_requests(state, updated_at)")
        db.execSQL("CREATE INDEX sign_requests_sender_idx ON sign_requests(sender_client_id, state)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS sign_requests")
        onCreate(db)
    }

    @Synchronized
    fun accept(request: OpenPgpSignSync, senderClientId: ClientId, now: Long): OpenPgpAcceptResult {
        find(request.requestId)?.let { existing ->
            return if (existing.sameContext(request, senderClientId)) {
                OpenPgpAcceptResult.DUPLICATE
            } else OpenPgpAcceptResult.CONFLICT
        }
        expireDue(now)
        prune(now)
        if (countPending() >= MAX_PENDING_GLOBAL || countPending(senderClientId) >= MAX_PENDING_PER_SENDER) {
            return OpenPgpAcceptResult.RATE_LIMITED
        }
        val inserted = writableDatabase.insertOrThrow(
            TABLE,
            null,
            requestValues(request, senderClientId, OpenPgpRequestState.PENDING_REVIEW, now),
        )
        check(inserted >= 0) { "could not persist OpenPGP signing request" }
        prune(now)
        refresh()
        return OpenPgpAcceptResult.STORED
    }

    @Synchronized
    fun find(requestId: String): StoredOpenPgpRequest? = readableDatabase.rawQuery(
        "SELECT $COLUMNS FROM $TABLE WHERE request_id = ?",
        arrayOf(requestId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.readRequest() else null }

    @Synchronized
    fun approve(requestId: String, now: Long): Boolean = transition(
        requestId,
        from = setOf(OpenPgpRequestState.PENDING_REVIEW),
        to = OpenPgpRequestState.USER_APPROVED,
        now = now,
        requireUnexpired = true,
    )

    @Synchronized
    fun markProviderInteraction(requestId: String, now: Long): Boolean = transition(
        requestId,
        from = setOf(OpenPgpRequestState.USER_APPROVED, OpenPgpRequestState.PROVIDER_INTERACTION),
        to = OpenPgpRequestState.PROVIDER_INTERACTION,
        now = now,
        requireUnexpired = true,
    )

    @Synchronized
    fun storeResult(requestId: String, signatureArmor: String, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        if (stored.state !in setOf(OpenPgpRequestState.USER_APPROVED, OpenPgpRequestState.PROVIDER_INTERACTION) ||
            now > stored.request.expiresAt
        ) return false
        val response = stored.request.copy(
            action = OpenPgpSignAction.RESULT,
            payload = null,
            signatureArmor = signatureArmor,
            rejectReason = null,
            actionAt = now,
        )
        if (response.validationError(::sha256) != null) return false
        return storeTerminal(stored, OpenPgpRequestState.SIGNED_PENDING_SEND, response, now)
    }

    @Synchronized
    fun storeReject(requestId: String, reason: OpenPgpRejectReason, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        if (stored.state !in setOf(
                OpenPgpRequestState.PENDING_REVIEW,
                OpenPgpRequestState.USER_APPROVED,
                OpenPgpRequestState.PROVIDER_INTERACTION,
            ) || now > stored.request.expiresAt
        ) return false
        val response = stored.request.copy(
            action = OpenPgpSignAction.REJECT,
            payload = null,
            signatureArmor = null,
            rejectReason = reason,
            actionAt = now,
        )
        if (response.validationError(::sha256) != null) return false
        return storeTerminal(stored, OpenPgpRequestState.REJECTED_PENDING_SEND, response, now)
    }

    @Synchronized
    fun cancel(requestId: String, senderClientId: ClientId, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        if (stored.senderClientId != senderClientId || stored.state !in ACTIVE_STATES) return false
        val values = ContentValues().apply {
            put("state", OpenPgpRequestState.CANCELLED.name)
            putNull("payload")
            putNull("encoded_response")
            put("updated_at", now)
        }
        val changed = writableDatabase.update(TABLE, values, "request_id = ?", arrayOf(requestId)) == 1
        if (changed) refresh()
        return changed
    }

    @Synchronized
    fun markSent(requestId: String, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        if (stored.state !in OUTBOX_STATES) return false
        val values = ContentValues().apply {
            put("state", OpenPgpRequestState.SENT.name)
            putNull("payload")
            putNull("encoded_response")
            put("updated_at", now)
        }
        val changed = writableDatabase.update(TABLE, values, "request_id = ?", arrayOf(requestId)) == 1
        if (changed) refresh()
        return changed
    }

    @Synchronized
    fun markExpired(requestId: String, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        if (stored.state !in ACTIVE_STATES + OUTBOX_STATES) return false
        val values = ContentValues().apply {
            put("state", OpenPgpRequestState.EXPIRED.name)
            putNull("payload")
            putNull("encoded_response")
            put("updated_at", now)
        }
        val changed = writableDatabase.update(TABLE, values, "request_id = ?", arrayOf(requestId)) == 1
        if (changed) refresh()
        return changed
    }

    @Synchronized
    fun pendingResponses(): List<StoredOpenPgpRequest> = queryByStates(OUTBOX_STATES)

    @Synchronized
    fun expireDue(now: Long): List<String> {
        val due = queryByStates(ACTIVE_STATES).filter { now > it.request.expiresAt }.map { it.request.requestId }
        due.forEach { markExpired(it, now) }
        return due
    }

    private fun storeTerminal(
        stored: StoredOpenPgpRequest,
        state: OpenPgpRequestState,
        response: OpenPgpSignSync,
        now: Long,
    ): Boolean {
        val encoded = ProtocolCodec.encodeToCbor(response)
        val values = ContentValues().apply {
            put("state", state.name)
            put("encoded_response", encoded)
            put("updated_at", now)
        }
        val changed = writableDatabase.update(
            TABLE,
            values,
            "request_id = ? AND state = ?",
            arrayOf(stored.request.requestId, stored.state.name),
        ) == 1
        if (changed) refresh()
        return changed
    }

    private fun transition(
        requestId: String,
        from: Set<OpenPgpRequestState>,
        to: OpenPgpRequestState,
        now: Long,
        requireUnexpired: Boolean,
    ): Boolean {
        val stored = find(requestId) ?: return false
        if (stored.state !in from || (requireUnexpired && now > stored.request.expiresAt)) return false
        val values = ContentValues().apply {
            put("state", to.name)
            put("updated_at", now)
        }
        val changed = writableDatabase.update(
            TABLE,
            values,
            "request_id = ? AND state = ?",
            arrayOf(requestId, stored.state.name),
        ) == 1
        if (changed) refresh()
        return changed
    }

    private fun requestValues(
        request: OpenPgpSignSync,
        senderClientId: ClientId,
        state: OpenPgpRequestState,
        now: Long,
    ) = ContentValues().apply {
        put("request_id", request.requestId)
        put("requester_client_id", request.requesterClientId.value)
        put("sender_client_id", senderClientId.value)
        put("primary_key_id", request.primaryKeyId)
        put("issued_at", request.issuedAt)
        put("expires_at", request.expiresAt)
        put("payload_sha256", request.payloadSha256)
        put("object_kind", request.objectKind.name)
        put("payload", request.payload)
        put("state", state.name)
        put("updated_at", now)
    }

    private fun queryByStates(states: Set<OpenPgpRequestState>): List<StoredOpenPgpRequest> {
        if (states.isEmpty()) return emptyList()
        val placeholders = states.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM $TABLE WHERE state IN ($placeholders) ORDER BY updated_at DESC",
            states.map(OpenPgpRequestState::name).toTypedArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readRequest()) } }
    }

    private fun refresh() {
        _requests.value = readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM $TABLE ORDER BY updated_at DESC LIMIT $MAX_HISTORY_ROWS",
            emptyArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readRequest()) } }
    }

    private fun prune(now: Long) {
        writableDatabase.delete(
            TABLE,
            "state NOT IN (${ACTIVE_STATES.joinToString(",") { "'${it.name}'" }}," +
                "${OUTBOX_STATES.joinToString(",") { "'${it.name}'" }}) AND updated_at < ?",
            arrayOf((now - DECISION_RETENTION_MILLIS).toString()),
        )
        val rowCount = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        val overflow = (rowCount - MAX_HISTORY_ROWS).coerceAtLeast(0)
        if (overflow > 0) {
            writableDatabase.execSQL(
                "DELETE FROM $TABLE WHERE request_id IN (SELECT request_id FROM $TABLE " +
                    "WHERE state NOT IN (${ACTIVE_STATES.joinToString(",") { "'${it.name}'" }}," +
                    "${OUTBOX_STATES.joinToString(",") { "'${it.name}'" }}) " +
                    "ORDER BY updated_at ASC LIMIT $overflow)"
            )
        }
    }

    private fun countPending(sender: ClientId? = null): Int {
        val where = if (sender == null) {
            "state IN (${ACTIVE_STATES.joinToString(",") { "'${it.name}'" }})"
        } else {
            "sender_client_id = ? AND state IN (${ACTIVE_STATES.joinToString(",") { "'${it.name}'" }})"
        }
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $where",
            sender?.let { arrayOf(it.value) } ?: emptyArray(),
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
    }

    private fun Cursor.readRequest(): StoredOpenPgpRequest {
        val payload = getBlobOrNull(8)
        val response = getBlobOrNull(10)
        val base = OpenPgpSignSync(
            action = OpenPgpSignAction.REQUEST,
            requestId = getString(0),
            requesterClientId = ClientId(getString(1)),
            issuedAt = getLong(4),
            expiresAt = getLong(5),
            primaryKeyId = getString(3),
            payloadSha256 = getBlob(6),
            objectKind = OpenPgpObjectKind.valueOf(getString(7)),
            payload = payload,
        )
        return StoredOpenPgpRequest(
            request = base,
            senderClientId = ClientId(getString(2)),
            state = OpenPgpRequestState.valueOf(getString(9)),
            encodedResponse = response,
            updatedAt = getLong(11),
        )
    }

    private fun Cursor.getBlobOrNull(index: Int): ByteArray? = if (isNull(index)) null else getBlob(index)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun StoredOpenPgpRequest.sameContext(request: OpenPgpSignSync, sender: ClientId): Boolean =
        senderClientId == sender &&
            this.request.requesterClientId == request.requesterClientId &&
            this.request.issuedAt == request.issuedAt &&
            this.request.expiresAt == request.expiresAt &&
            this.request.primaryKeyId == request.primaryKeyId &&
            this.request.objectKind == request.objectKind &&
            MessageDigest.isEqual(this.request.payloadSha256, request.payloadSha256) &&
            // Terminal rows deliberately erase the sensitive raw commit. The retained authenticated
            // metadata and SHA-256 decision ledger are sufficient to recognize a later relay replay.
            (this.request.payload == null ||
                this.request.payload.contentEquals(request.payload ?: ByteArray(0)))

    private companion object {
        const val DB_NAME = "openpgp_signing.db"
        const val VERSION = 1
        const val TABLE = "sign_requests"
        const val COLUMNS = "request_id,requester_client_id,sender_client_id,primary_key_id," +
            "issued_at,expires_at,payload_sha256,object_kind,payload,state,encoded_response,updated_at"
        const val MAX_PENDING_PER_SENDER = 3
        const val MAX_PENDING_GLOBAL = 10
        const val MAX_HISTORY_ROWS = 500
        const val DECISION_RETENTION_MILLIS = 72L * 60 * 60 * 1_000
        val ACTIVE_STATES = setOf(
            OpenPgpRequestState.PENDING_REVIEW,
            OpenPgpRequestState.USER_APPROVED,
            OpenPgpRequestState.PROVIDER_INTERACTION,
        )
        val OUTBOX_STATES = setOf(
            OpenPgpRequestState.SIGNED_PENDING_SEND,
            OpenPgpRequestState.REJECTED_PENDING_SEND,
        )
    }
}
