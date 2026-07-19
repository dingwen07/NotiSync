package net.extrawdw.notisync.daemon

import java.math.BigDecimal
import java.time.Clock
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.encodeToJsonElement
import net.extrawdw.notisync.localapi.MessageFilter
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRecordType
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DismissEvent
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec

/** The receive bridge needs only a membership test, not the application registry's mutation API. */
fun interface RegisteredApplicationLookup {
    fun isRegistered(applicationId: String): Boolean
}

/**
 * Converts a typed protocol body to its JSON representation for local equality filters. Returning
 * null means the body was malformed for its declared [MessageType].
 */
fun interface InboundBodyProjector {
    fun project(messageType: MessageType, body: ByteArray, decodedDataSync: DataSync?): JsonElement?
}

object ProtocolInboundBodyProjector : InboundBodyProjector {
    override fun project(
        messageType: MessageType,
        body: ByteArray,
        decodedDataSync: DataSync?,
    ): JsonElement? = runCatching {
        when (messageType) {
            MessageType.NOTIFICATION -> ProtocolCodec.json.encodeToJsonElement(
                ProtocolCodec.decodeFromCbor<CapturedNotification>(body),
            )
            MessageType.DISMISSAL -> ProtocolCodec.json.encodeToJsonElement(
                ProtocolCodec.decodeFromCbor<DismissEvent>(body),
            )
            MessageType.ACTION -> ProtocolCodec.json.encodeToJsonElement(
                ProtocolCodec.decodeFromCbor<ActionEvent>(body),
            )
            MessageType.DATA_SYNC -> ProtocolCodec.json.encodeToJsonElement(
                decodedDataSync ?: ProtocolCodec.decodeFromCbor<DataSync>(body),
            )
        }
    }.getOrNull()
}

/**
 * Memory-only fan-out from authenticated SecureChannel messages to registered local applications.
 *
 * An application's inbox owns one pending reference per envelope. Socket handles are merely views:
 * closing one detaches that stream without removing its process interest or acknowledging data.
 */
class ApplicationReceiveRouter(
    private val applications: RegisteredApplicationLookup,
    private val identityResolver: ProcessIdentityResolver,
    private val clock: Clock = Clock.systemUTC(),
    private val maximumPendingPerApplication: Int = DEFAULT_MAXIMUM_PENDING_PER_APPLICATION,
    private val projector: InboundBodyProjector = ProtocolInboundBodyProjector,
    private val processStillMatches: (LocalPeer) -> Boolean = identityResolver::stillMatches,
) {
    init {
        require(maximumPendingPerApplication > 0) {
            "maximumPendingPerApplication must be positive"
        }
    }

    internal data class ProcessLease(
        val uid: Long,
        val pid: Long,
        val startTime: String,
    ) {
        fun peer(): LocalPeer = LocalPeer(uid, pid, startTime)
    }

    private data class RegisteredInterest(
        val applicationId: String,
        val lease: ProcessLease,
        val interest: CanonicalInterest,
    )

    internal data class CompiledFilter(
        val messageType: MessageType,
        val path: String,
        val pointer: List<String>,
        val acceptedValues: Set<ScalarValue>,
    )

    internal data class CanonicalInterest(
        val messageTypes: List<MessageType>?,
        val filters: List<CompiledFilter>,
    ) {
        fun matches(message: SharedInbound): Boolean {
            if (messageTypes != null && message.messageType !in messageTypes) return false
            val targeted = filters.filter { it.messageType == message.messageType }
            if (targeted.isEmpty()) return true
            val body = message.projectedBody() ?: return false
            return targeted.all { filter ->
                resolvePointer(body, filter.pointer)?.toScalarValue() in filter.acceptedValues
            }
        }
    }

    internal sealed interface ScalarValue {
        data class Text(val value: String) : ScalarValue
        data class BooleanValue(val value: Boolean) : ScalarValue
        data class NumberValue(val value: BigDecimal) : ScalarValue

        fun sortKey(): String = when (this) {
            is Text -> "s:$value"
            is BooleanValue -> "b:$value"
            is NumberValue -> "n:$value"
        }
    }

    internal inner class SharedInbound(
        val envelopeId: String,
        val messageType: MessageType,
        val body: ByteArray,
        val senderClientId: String,
        val senderOwnDevice: Boolean,
        val signerEpoch: Int,
        val deliveryMode: String,
        val receivedAtEpochMillis: Long,
        private val decodedDataSync: DataSync?,
    ) {
        private var projectionResolved = false
        private var projection: JsonElement? = null
        private val encodedBody: String by lazy(LazyThreadSafetyMode.NONE) {
            Base64.getEncoder().encodeToString(body)
        }

        fun projectedBody(): JsonElement? {
            if (!projectionResolved) {
                projection = projector.project(messageType, body, decodedDataSync)
                projectionResolved = true
            }
            return projection
        }

        fun record(applicationId: String): ReceiveRecord = ReceiveRecord(
            recordType = ReceiveRecordType.MESSAGE,
            applicationId = applicationId,
            envelopeId = envelopeId,
            messageType = messageType,
            body = encodedBody,
            senderClientId = senderClientId,
            senderOwnDevice = senderOwnDevice,
            signerEpoch = signerEpoch,
            deliveryMode = deliveryMode,
            receivedAtEpochMillis = receivedAtEpochMillis,
        )
    }

    private val lock = ReentrantLock()
    private val interests = linkedSetOf<RegisteredInterest>()
    private val sharedMessages = linkedMapOf<String, SharedInbound>()
    private val pendingByApplication = linkedMapOf<String, LinkedHashMap<String, SharedInbound>>()
    private val handles = linkedSetOf<ReceiverHandle>()

    /**
     * Register (or reuse) this process's canonical interest, attach a new independent stream, and
     * replay every matching unacknowledged application event to that stream.
     */
    fun open(peer: LocalPeer, request: ReceiveRequest): ReceiverHandle {
        val canonical = canonicalize(request)
        val lease = peer.toVerifiedLease()
        return lock.withLock {
            cleanupDeadProcessesLocked()
            // Application deletion removes the durable registration before calling removeApplication.
            // Checking while holding this lock prevents a concurrent delete/remove from leaving a stale
            // interest behind after its cleanup pass.
            requireRegistered(request.applicationId)
            interests += RegisteredInterest(request.applicationId, lease, canonical)
            ReceiverHandle(request.applicationId, lease, canonical).also { handle ->
                handles += handle
                pendingByApplication[request.applicationId].orEmpty().values.forEach { message ->
                    if (canonical.matches(message)) handle.offerLocked(message.record(request.applicationId))
                }
            }
        }
    }

    /**
     * Offer one authenticated inbound envelope. Capacity is checked for every matching application
     * before any reference is added, so a failure leaves the entire fan-out untouched for retry.
     * Returns true when at least one live application interest matched (including a duplicate ref).
     */
    fun accept(message: InboundMessage, decodedDataSync: DataSync? = null): Boolean {
        require(message.messageId.isNotBlank()) { "inbound message requires a non-empty envelope id" }
        require(decodedDataSync == null || message.typ == MessageType.DATA_SYNC) {
            "decodedDataSync is valid only for DATA_SYNC"
        }
        return lock.withLock {
            cleanupDeadProcessesLocked()
            val shared = sharedMessages[message.messageId] ?: SharedInbound(
                envelopeId = message.messageId,
                messageType = message.typ,
                body = message.body.copyOf(),
                senderClientId = message.senderId.value,
                senderOwnDevice = message.senderOwnDevice,
                signerEpoch = message.signerEpoch,
                deliveryMode = message.deliveryMode.name,
                receivedAtEpochMillis = clock.millis(),
                decodedDataSync = decodedDataSync,
            )
            val matchingApplications = interests.asSequence()
                .groupBy(RegisteredInterest::applicationId)
                .filterValues { applicationInterests ->
                    applicationInterests.any { it.interest.matches(shared) }
                }
                .keys
            if (matchingApplications.isEmpty()) return@withLock false

            val newApplications = matchingApplications.filter { applicationId ->
                message.messageId !in pendingByApplication[applicationId].orEmpty()
            }
            newApplications.forEach { applicationId ->
                val pendingCount = pendingByApplication[applicationId]?.size ?: 0
                if (pendingCount >= maximumPendingPerApplication) {
                    throw LocalEventQueueFullException(
                        "local application '$applicationId' event queue is full",
                    )
                }
            }

            if (newApplications.isNotEmpty()) sharedMessages.putIfAbsent(message.messageId, shared)
            newApplications.forEach { applicationId ->
                pendingByApplication.getOrPut(applicationId, ::linkedMapOf)[message.messageId] = shared
                handles.asSequence()
                    .filter { it.applicationId == applicationId && it.interest.matches(shared) }
                    .forEach { it.offerLocked(shared.record(applicationId)) }
            }
            true
        }
    }

    /** Shared, idempotent application acknowledgment. */
    fun ack(applicationId: String, envelopeId: String): Boolean = lock.withLock {
        val pending = pendingByApplication[applicationId] ?: return@withLock false
        if (pending.remove(envelopeId) == null) return@withLock false
        if (pending.isEmpty()) pendingByApplication.remove(applicationId)
        handles.filter { it.applicationId == applicationId }.forEach { it.removeLocked(envelopeId) }
        releaseIfUnreferencedLocked(envelopeId)
        true
    }

    /** Preflight seam for atomic completion: response sends must not be accepted for a missing event. */
    fun hasPending(applicationId: String, envelopeId: String): Boolean = lock.withLock {
        envelopeId in pendingByApplication[applicationId].orEmpty()
    }

    /** Remove exactly this process's canonical interest and detach streams opened for it. */
    fun unregister(peer: LocalPeer, request: ReceiveRequest): Boolean {
        val canonical = canonicalize(request)
        val lease = peer.toVerifiedLease()
        return lock.withLock {
            val removed = interests.remove(RegisteredInterest(request.applicationId, lease, canonical))
            if (removed) {
                handles.filter {
                    it.applicationId == request.applicationId && it.lease == lease && it.interest == canonical
                }.toList().forEach { it.detachLocked() }
            }
            removed
        }
    }

    /** Remove dead process interests. Their already-pending application references are retained. */
    fun cleanupDeadProcesses(): Int = lock.withLock { cleanupDeadProcessesLocked() }

    /** Application deletion clears interests, attached streams, and all of its pending references. */
    fun removeApplication(applicationId: String) = lock.withLock {
        interests.removeIf { it.applicationId == applicationId }
        handles.filter { it.applicationId == applicationId }.toList().forEach { it.detachLocked() }
        val removedIds = pendingByApplication.remove(applicationId)?.keys.orEmpty()
        removedIds.forEach(::releaseIfUnreferencedLocked)
    }

    internal fun pendingCount(applicationId: String): Int = lock.withLock {
        pendingByApplication[applicationId]?.size ?: 0
    }

    internal fun interestCount(applicationId: String): Int = lock.withLock {
        interests.count { it.applicationId == applicationId }
    }

    internal fun sharedMessageCount(): Int = lock.withLock { sharedMessages.size }

    private fun requireRegistered(applicationId: String) {
        if (!applications.isRegistered(applicationId)) throw ApplicationNotRegisteredException(applicationId)
    }

    private fun cleanupDeadProcessesLocked(): Int {
        val deadLeases = interests.asSequence()
            .map(RegisteredInterest::lease)
            .distinct()
            .filterNot { processStillMatches(it.peer()) }
            .toSet()
        if (deadLeases.isEmpty()) return 0
        val removed = interests.count { it.lease in deadLeases }
        interests.removeIf { it.lease in deadLeases }
        handles.filter { it.lease in deadLeases }.toList().forEach { it.detachLocked() }
        return removed
    }

    private fun releaseIfUnreferencedLocked(envelopeId: String) {
        if (pendingByApplication.values.none { envelopeId in it }) sharedMessages.remove(envelopeId)
    }

    private fun detach(handle: ReceiverHandle) = lock.withLock { handle.detachLocked() }

    /** One close-delimited receive stream. [close] detaches only this socket view. */
    inner class ReceiverHandle internal constructor(
        val applicationId: String,
        internal val lease: ProcessLease,
        internal val interest: CanonicalInterest,
    ) : AutoCloseable {
        private val available = lock.newCondition()
        private val offered = linkedMapOf<String, ReceiveRecord>()
        private var detached = false

        /** Wait for the next uniquely offered message, returning null on timeout or detach. */
        fun awaitRecord(waitMillis: Long): ReceiveRecord? {
            require(waitMillis >= 0) { "waitMillis must not be negative" }
            return lock.withLock {
                var remaining = TimeUnit.MILLISECONDS.toNanos(waitMillis)
                while (!detached && offered.isEmpty() && remaining > 0) {
                    remaining = available.awaitNanos(remaining)
                }
                if (detached) return@withLock null
                val first = offered.entries.firstOrNull() ?: return@withLock null
                offered.remove(first.key)
                first.value
            }
        }

        fun pollRecord(): ReceiveRecord? = awaitRecord(0)

        fun isAttached(): Boolean = lock.withLock { !detached }

        /** Detach this stream without unregistering its process interest or acknowledging events. */
        override fun close() = detach(this)

        internal fun offerLocked(record: ReceiveRecord) {
            check(lock.isHeldByCurrentThread)
            val envelopeId = requireNotNull(record.envelopeId)
            if (!detached && offered.putIfAbsent(envelopeId, record) == null) available.signalAll()
        }

        internal fun removeLocked(envelopeId: String) {
            check(lock.isHeldByCurrentThread)
            offered.remove(envelopeId)
        }

        internal fun detachLocked() {
            check(lock.isHeldByCurrentThread)
            if (detached) return
            detached = true
            offered.clear()
            handles.remove(this)
            available.signalAll()
        }
    }

    private fun canonicalize(request: ReceiveRequest): CanonicalInterest {
        require(request.applicationId.isNotBlank() && request.applicationId.length <= MAXIMUM_APPLICATION_ID_LENGTH) {
            "applicationId must contain 1..$MAXIMUM_APPLICATION_ID_LENGTH characters"
        }
        val requestedTypes = request.messageTypes
        require(requestedTypes == null || requestedTypes.isNotEmpty()) {
            "messageTypes must not be empty when present"
        }
        require(request.filters.isEmpty() || requestedTypes != null) {
            "filters require messageTypes"
        }
        val allowedTypes = requestedTypes?.toSet()
        require(request.filters.all { it.messageType in allowedTypes.orEmpty() }) {
            "every filter messageType must appear in messageTypes"
        }
        val duplicate = request.filters.groupingBy { it.messageType to it.path }.eachCount()
            .entries.firstOrNull { it.value > 1 }
        require(duplicate == null) {
            "duplicate filter for ${duplicate?.key?.first}/${duplicate?.key?.second}"
        }
        val filters = request.filters.map(::compileFilter).sortedWith(
            compareBy<CompiledFilter>({ it.messageType.ordinal }, CompiledFilter::path),
        )
        return CanonicalInterest(
            messageTypes = requestedTypes?.distinct()?.sortedBy(MessageType::ordinal),
            filters = filters,
        )
    }

    private fun compileFilter(filter: MessageFilter): CompiledFilter {
        require(filter.acceptedValues.isNotEmpty()) { "filter acceptedValues must not be empty" }
        val accepted = filter.acceptedValues.map { value ->
            require(value !is JsonNull && value is JsonPrimitive) {
                "filter acceptedValues must contain only non-null JSON scalars"
            }
            requireNotNull(value.toScalarValue()) {
                "filter acceptedValues contains an invalid JSON number"
            }
        }.distinct().sortedBy(ScalarValue::sortKey).toSet()
        return CompiledFilter(
            messageType = filter.messageType,
            path = filter.path,
            pointer = compileJsonPointer(filter.path),
            acceptedValues = accepted,
        )
    }

    private fun LocalPeer.toVerifiedLease(): ProcessLease {
        val verifiedPid = pid
        val verifiedStartTime = startTime
        require(verifiedPid != null && verifiedStartTime != null) {
            "receive requires kernel-verified pid and process start time"
        }
        return ProcessLease(uid, verifiedPid, verifiedStartTime)
    }

    companion object {
        const val DEFAULT_MAXIMUM_PENDING_PER_APPLICATION = 1_024
        private const val MAXIMUM_APPLICATION_ID_LENGTH = 128

        private fun compileJsonPointer(path: String): List<String> {
            if (path.isEmpty()) return emptyList()
            require(path.startsWith('/')) { "filter path must be an RFC 6901 JSON pointer" }
            return path.substring(1).split('/').map { token ->
                buildString {
                    var index = 0
                    while (index < token.length) {
                        val character = token[index]
                        if (character != '~') {
                            append(character)
                            index++
                            continue
                        }
                        require(index + 1 < token.length) { "invalid RFC 6901 escape in filter path" }
                        when (token[index + 1]) {
                            '0' -> append('~')
                            '1' -> append('/')
                            else -> throw IllegalArgumentException("invalid RFC 6901 escape in filter path")
                        }
                        index += 2
                    }
                }
            }
        }

        private fun resolvePointer(root: JsonElement, pointer: List<String>): JsonElement? {
            var current = root
            pointer.forEach { token ->
                current = when (current) {
                    is JsonObject -> current[token] ?: return null
                    is JsonArray -> {
                        if (
                            token.isEmpty() ||
                            token.any { it !in '0'..'9' } ||
                            token.length > 1 && token.startsWith('0')
                        ) return null
                        val index = token.toIntOrNull() ?: return null
                        current.getOrNull(index) ?: return null
                    }
                    else -> return null
                }
            }
            return current
        }

        private fun JsonElement.toScalarValue(): ScalarValue? {
            if (this !is JsonPrimitive || this is JsonNull) return null
            if (isString) return ScalarValue.Text(content)
            booleanOrNull?.let { return ScalarValue.BooleanValue(it) }
            return runCatching {
                ScalarValue.NumberValue(BigDecimal(content).stripTrailingZeros())
            }.getOrNull()
        }
    }
}
