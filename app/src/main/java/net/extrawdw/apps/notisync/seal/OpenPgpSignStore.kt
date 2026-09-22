package net.extrawdw.apps.notisync.seal

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.GitCommitPayloadParser
import net.extrawdw.notisync.protocol.GitTagPayloadParser
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpRejectReason
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignLimits
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.apps.notisync.data.storage.operational.OperationalSQLiteOpenHelper
import net.extrawdw.apps.notisync.data.HistoryPage
import net.extrawdw.apps.notisync.data.HistoryDirection

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

/** User-visible outcome retained after the transport state advances to [OpenPgpRequestState.SENT]. */
enum class OpenPgpRequestResult { APPROVED, REJECTED, CANCELED, EXPIRED, FAILED }

/** Complete parsed commit fields. Only migrated records may already have been truncated by v4. */
@Serializable
data class GitCommitDetails(
    val treeId: String,
    val parentIds: List<String>,
    val author: String,
    val committer: String,
    val message: String,
    val extraHeaders: List<GitCommitHeader>,
    val payloadBytes: Int,
    val legacyTruncated: Boolean = false,
)

@Serializable
data class GitCommitHeader(val name: String, val value: String)

/** Complete parsed annotated-tag fields; the byte-exact payload is retained as well. */
@Serializable
data class GitTagDetails(
    val objectId: String,
    val objectType: String,
    val tagName: String,
    val tagger: String,
    val message: String,
    val payloadBytes: Int,
    val legacyTruncated: Boolean = false,
)

/** Lightweight list/notification projection; full fields are loaded from the same record on demand. */
data class OpenPgpRequestSummary(val title: String?, val reference: String?, val identity: String?)

data class StoredOpenPgpRequest(
    val request: OpenPgpSignSync,
    val senderClientId: ClientId,
    val state: OpenPgpRequestState,
    val response: OpenPgpSignSync? = null,
    val updatedAt: Long,
    val commit: GitCommitDetails? = null,
    val tag: GitTagDetails? = null,
    val result: OpenPgpRequestResult? = null,
    val summary: OpenPgpRequestSummary? = null,
) {
    internal val expiryDeadline: Long?
        get() = when (state) {
            OpenPgpRequestState.PENDING_REVIEW,
            OpenPgpRequestState.USER_APPROVED,
            OpenPgpRequestState.PROVIDER_INTERACTION -> request.expiresAt
            OpenPgpRequestState.SIGNED_PENDING_SEND,
            OpenPgpRequestState.REJECTED_PENDING_SEND -> request.expiresAt + OpenPgpSignLimits.CLOCK_SKEW_MILLIS
            else -> null
        }
}

enum class OpenPgpAcceptResult { STORED, DUPLICATE, CONFLICT, RATE_LIMITED }

data class OpenPgpHistoryCursor(val updatedAt: Long, val requestId: String)

class OpenPgpSignStore(context: Context) : OperationalSQLiteOpenHelper(context) {
    private val _requests = MutableStateFlow<List<StoredOpenPgpRequest>>(emptyList())
    /** All live requests and the first terminal page, for notifications and existing observers. */
    val requests: StateFlow<List<StoredOpenPgpRequest>> = _requests.asStateFlow()
    private val _changeVersion = MutableStateFlow(0L)
    val changeVersion: StateFlow<Long> = _changeVersion.asStateFlow()

    init {
        setWriteAheadLoggingEnabled(true)
        refresh()
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase): Nothing = roomMustOwnOpenPgpSchema()

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Nothing =
        roomMustOwnOpenPgpSchema()

    @Synchronized
    fun accept(request: OpenPgpSignSync, senderClientId: ClientId, now: Long): OpenPgpAcceptResult {
        find(request.requestId)?.let { existing ->
            return if (existing.sameContext(request, senderClientId)) {
                OpenPgpAcceptResult.DUPLICATE
            } else OpenPgpAcceptResult.CONFLICT
        }
        expireDue(now)
        if (countPending() >= MAX_PENDING_GLOBAL || countPending(senderClientId) >= MAX_PENDING_PER_SENDER) {
            return OpenPgpAcceptResult.RATE_LIMITED
        }
        // Parse before the single atomic row insert. The exact signing bytes remain authoritative.
        val values = requestValues(request, senderClientId, OpenPgpRequestState.PENDING_REVIEW, now)
        writableDatabase.insertOrThrow(TABLE, null, values)
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
            workingDirectory = null,
        )
        if (response.validationError(::sha256) != null) return false
        return storeTerminal(
            stored,
            OpenPgpRequestState.SIGNED_PENDING_SEND,
            OpenPgpRequestResult.APPROVED,
            response,
            now,
        )
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
            workingDirectory = null,
        )
        if (response.validationError(::sha256) != null) return false
        return storeTerminal(
            stored,
            OpenPgpRequestState.REJECTED_PENDING_SEND,
            resultFor(reason),
            response,
            now,
        )
    }

    @Synchronized
    fun cancel(requestId: String, senderClientId: ClientId, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        if (stored.senderClientId != senderClientId || stored.state !in ACTIVE_STATES) return false
        val values = ContentValues().apply {
            put("state", OpenPgpRequestState.CANCELLED.name)
            put("result", OpenPgpRequestResult.CANCELED.name)
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
            put("result", OpenPgpRequestResult.EXPIRED.name)
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
        val active = ACTIVE_STATES.joinToString(",") { "'${it.name}'" }
        val outbox = OUTBOX_STATES.joinToString(",") { "'${it.name}'" }
        val where = "(state IN ($active) AND expires_at < ?) OR (state IN ($outbox) AND expires_at < ?)"
        val args = arrayOf(now.toString(), (now - OpenPgpSignLimits.CLOCK_SKEW_MILLIS).toString())
        val db = writableDatabase
        val due: List<String>
        db.beginTransaction()
        try {
            due = db.rawQuery("SELECT request_id FROM $TABLE WHERE $where ORDER BY request_id", args)
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            if (due.isNotEmpty()) {
                db.update(TABLE, ContentValues().apply {
                    put("state", OpenPgpRequestState.EXPIRED.name)
                    put("result", OpenPgpRequestResult.EXPIRED.name)
                    put("updated_at", now)
                }, where, args)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (due.isNotEmpty()) refresh()
        return due
    }

    private fun storeTerminal(
        stored: StoredOpenPgpRequest,
        state: OpenPgpRequestState,
        result: OpenPgpRequestResult,
        response: OpenPgpSignSync,
        now: Long,
    ): Boolean {
        val values = ContentValues().apply {
            put("state", state.name)
            put("result", result.name)
            put("response_action", response.action.name)
            put("response_signature_armor", response.signatureArmor)
            put("response_reject_reason", response.rejectReason?.name)
            put("response_action_at", response.actionAt)
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
        val payload = requireNotNull(request.payload)
        val summary = when (request.objectKind) {
            OpenPgpObjectKind.GIT_COMMIT -> payload.toCommitDetails().toSummary()
            OpenPgpObjectKind.GIT_TAG -> payload.toTagDetails().toSummary()
        }
        put("request_id", request.requestId)
        put("protocol_version", request.protocolVersion)
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
        put("working_directory", request.workingDirectory)
        put("summary_title", summary.title)
        put("summary_reference", summary.reference)
        put("summary_identity", summary.identity)
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
        val live = readableDatabase.rawQuery(
            "SELECT $SUMMARY_COLUMNS FROM $TABLE WHERE state IN ($LIVE_STATE_SQL)",
            emptyArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readRequest(summaryOnly = true)) } }
        _requests.value = (live + historyPage().items).sortedWith(
            compareByDescending<StoredOpenPgpRequest> { it.updatedAt }.thenByDescending { it.request.requestId },
        )
        // An update to an older page must invalidate that page even when the first page is unchanged.
        _changeVersion.value += 1
    }

    /** A bounded terminal summary page; exact payloads and responses are loaded only by [find]. */
    @Synchronized
    fun historyPage(
        limit: Int = HISTORY_PAGE_SIZE,
        cursor: OpenPgpHistoryCursor? = null,
        direction: HistoryDirection = HistoryDirection.OLDER,
        includeCursor: Boolean = false,
    ): HistoryPage<StoredOpenPgpRequest, OpenPgpHistoryCursor> {
        require(limit in 1..1_000)
        val newer = direction == HistoryDirection.NEWER
        val comparison = (if (newer) ">" else "<") + if (includeCursor) "=" else ""
        val position = if (cursor == null) "" else " AND (updated_at,request_id) $comparison (?,?)"
        val order = if (newer) "ASC" else "DESC"
        val arguments = buildList {
            cursor?.let { add(it.updatedAt.toString()); add(it.requestId) }
            add((limit + 1).toString())
        }.toTypedArray()
        val rows = readableDatabase.rawQuery(
            "SELECT $HISTORY_SUMMARY_COLUMNS FROM $TABLE WHERE state NOT IN ($LIVE_STATE_SQL)$position " +
                "ORDER BY updated_at $order, request_id $order LIMIT ?",
            arguments,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readRequest(summaryOnly = true)) } }
        val closest = rows.take(limit)
        val items = if (newer) closest.asReversed() else closest
        val nextCursor = closest.lastOrNull()?.takeIf { rows.size > limit }?.let {
            OpenPgpHistoryCursor(it.updatedAt, it.request.requestId)
        }
        return HistoryPage(items, nextCursor)
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

    private fun Cursor.readRequest(summaryOnly: Boolean = false): StoredOpenPgpRequest {
        val requestId = getString(0)
        val objectKind = OpenPgpObjectKind.valueOf(getString(8))
        val base = OpenPgpSignSync(
            action = OpenPgpSignAction.REQUEST,
            protocolVersion = getInt(1),
            requestId = requestId,
            requesterClientId = ClientId(getString(2)),
            issuedAt = getLong(5),
            expiresAt = getLong(6),
            primaryKeyId = getString(4),
            payloadSha256 = if (summaryOnly) getBlobOrNull(7) ?: ByteArray(0) else getBlob(7),
            objectKind = objectKind,
            payload = getBlobOrNull(9),
            workingDirectory = getStringOrNull(17),
        )
        val response = getStringOrNull(11)?.let { action ->
            base.copy(
                action = OpenPgpSignAction.valueOf(action),
                payload = null,
                signatureArmor = getStringOrNull(12),
                rejectReason = getStringOrNull(13)?.let(OpenPgpRejectReason::valueOf),
                actionAt = if (isNull(14)) null else getLong(14),
                workingDirectory = null,
            )
        }
        // No parsed-field tables: selected records are rendered directly from the retained payload.
        // Legacy JSON is used only where an older release had already erased those bytes.
        val legacyDetails = if (summaryOnly) null else getStringOrNull(21)
        val commit = if (!summaryOnly && objectKind == OpenPgpObjectKind.GIT_COMMIT) {
            base.payload?.toCommitDetails()
                ?: legacyDetails?.let { ProtocolCodec.decodeFromJson<GitCommitDetails>(it) }
        } else null
        val tag = if (!summaryOnly && objectKind == OpenPgpObjectKind.GIT_TAG) {
            base.payload?.toTagDetails()
                ?: legacyDetails?.let { ProtocolCodec.decodeFromJson<GitTagDetails>(it) }
        } else null
        return StoredOpenPgpRequest(
            request = base,
            senderClientId = ClientId(getString(3)),
            state = OpenPgpRequestState.valueOf(getString(10)),
            response = response,
            updatedAt = getLong(15),
            commit = commit,
            tag = tag,
            result = getStringOrNull(16)?.let(OpenPgpRequestResult::valueOf),
            summary = if (summaryOnly) OpenPgpRequestSummary(
                title = getStringOrNull(18),
                reference = getStringOrNull(19),
                identity = getStringOrNull(20),
            ) else null,
        )
    }

    private fun Cursor.getBlobOrNull(index: Int): ByteArray? = if (isNull(index)) null else getBlob(index)
    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun StoredOpenPgpRequest.sameContext(request: OpenPgpSignSync, sender: ClientId): Boolean =
        senderClientId == sender &&
            this.request.protocolVersion == request.protocolVersion &&
            this.request.requesterClientId == request.requesterClientId &&
            this.request.issuedAt == request.issuedAt &&
            this.request.expiresAt == request.expiresAt &&
            this.request.primaryKeyId == request.primaryKeyId &&
            this.request.objectKind == request.objectKind &&
            this.request.workingDirectory == request.workingDirectory &&
            MessageDigest.isEqual(this.request.payloadSha256, request.payloadSha256) &&
            // Only pre-v5 terminal rows can lack the raw payload. Their authenticated digest still
            // identifies replays without inventing the bytes erased by an older release.
            (this.request.payload == null ||
                this.request.payload.contentEquals(request.payload ?: ByteArray(0)))

    private companion object {
        const val TABLE = "seal_requests"
        const val COLUMNS = "request_id,protocol_version,requester_client_id,sender_client_id,primary_key_id," +
            "issued_at,expires_at,payload_sha256,object_kind,payload,state,response_action," +
            "response_signature_armor,response_reject_reason,response_action_at,updated_at,result,working_directory," +
            "summary_title,summary_reference,summary_identity,legacy_details_json"
        // Live notifications display the short digest; terminal list pages do not need it.
        val SUMMARY_COLUMNS = summaryColumns(includeDigest = true)
        val HISTORY_SUMMARY_COLUMNS = summaryColumns(includeDigest = false)
        fun summaryColumns(includeDigest: Boolean) = COLUMNS.split(',').joinToString(",") { column ->
            when {
                column == "payload_sha256" && !includeDigest -> "NULL"
                column == "payload" || column.startsWith("response_") ||
                    column == "legacy_details_json" -> "NULL"
                column.startsWith("summary_") -> "substr($column,1,1024)"
                else -> column
            }
        }
        const val MAX_PENDING_PER_SENDER = 3
        const val MAX_PENDING_GLOBAL = 10
        const val HISTORY_PAGE_SIZE = 50
        val ACTIVE_STATES = setOf(
            OpenPgpRequestState.PENDING_REVIEW,
            OpenPgpRequestState.USER_APPROVED,
            OpenPgpRequestState.PROVIDER_INTERACTION,
        )
        val OUTBOX_STATES = setOf(
            OpenPgpRequestState.SIGNED_PENDING_SEND,
            OpenPgpRequestState.REJECTED_PENDING_SEND,
        )
        val LIVE_STATE_SQL = (ACTIVE_STATES + OUTBOX_STATES).joinToString(",") { "'${it.name}'" }
    }
}

internal fun ByteArray.toCommitDetails(): GitCommitDetails {
    val parsed = GitCommitPayloadParser.parse(this)
    return GitCommitDetails(
        treeId = parsed.treeId,
        parentIds = parsed.parentIds,
        author = parsed.author,
        committer = parsed.committer,
        message = parsed.message,
        extraHeaders = parsed.headers
            .filterNot { it.name in setOf("tree", "parent", "author", "committer") }
            .map { GitCommitHeader(it.name, it.value) },
        payloadBytes = size,
    )
}

internal fun ByteArray.toTagDetails(): GitTagDetails {
    val parsed = GitTagPayloadParser.parse(this)
    return GitTagDetails(
        objectId = parsed.objectId,
        objectType = parsed.objectType,
        tagName = parsed.tagName,
        tagger = parsed.tagger,
        message = parsed.message,
        payloadBytes = size,
    )
}

internal fun GitCommitDetails.toSummary() = OpenPgpRequestSummary(
    title = message.commitSubject().take(1_024),
    reference = parentIds.firstOrNull() ?: treeId,
    identity = author.take(1_024),
)

internal fun GitTagDetails.toSummary() = OpenPgpRequestSummary(
    title = tagName.take(1_024), reference = objectId, identity = tagger.take(1_024),
)

private fun resultFor(reason: OpenPgpRejectReason): OpenPgpRequestResult = when (reason) {
    OpenPgpRejectReason.USER_REJECTED -> OpenPgpRequestResult.REJECTED
    OpenPgpRejectReason.EXPIRED -> OpenPgpRequestResult.EXPIRED
    OpenPgpRejectReason.PROVIDER_CANCELLED -> OpenPgpRequestResult.CANCELED
    OpenPgpRejectReason.PROVIDER_UNAVAILABLE,
    OpenPgpRejectReason.UNSUPPORTED_KEY,
    OpenPgpRejectReason.PROVIDER_FAILURE -> OpenPgpRequestResult.FAILED
}

private fun roomMustOwnOpenPgpSchema(): Nothing =
    error("Room must create and migrate Operational storage before OpenPgpSignStore opens")
