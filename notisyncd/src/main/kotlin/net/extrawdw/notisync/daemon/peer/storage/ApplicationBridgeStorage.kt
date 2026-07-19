package net.extrawdw.notisync.daemon.peer.storage

import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.daemon.ApplicationNotRegisteredException
import net.extrawdw.notisync.daemon.ApplicationProfilePublicationState
import net.extrawdw.notisync.daemon.ApplicationProfilePublicationStateStore
import net.extrawdw.notisync.daemon.ApplicationRegistrationResult
import net.extrawdw.notisync.daemon.ApplicationRegistry
import net.extrawdw.notisync.daemon.GenericSendOutbox
import net.extrawdw.notisync.daemon.INTRINSIC_DAEMON_CAPABILITIES
import net.extrawdw.notisync.daemon.PendingSend
import net.extrawdw.notisync.daemon.ResolvedSend
import net.extrawdw.notisync.daemon.StaleSendSequenceException
import net.extrawdw.notisync.daemon.SubmissionConflictException
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.QueuePolicy
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SignerSelection
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.Urgency

/**
 * Persistent application declarations and profile-publication bookkeeping.
 *
 * Generic sends intentionally do not live here: an accepted local send, its submission idempotency
 * marker, and queue-policy sequence state last only for the current daemon process.
 */
class PersistentApplicationBridgeStore(
    private val database: DaemonDatabaseRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ApplicationRegistry, ApplicationProfilePublicationStateStore {
    override fun register(
        applicationId: String,
        registration: ApplicationRegistrationRequest,
    ): ApplicationRegistrationResult {
        validateApplicationId(applicationId)
        validateRegistration(registration)
        var result: ApplicationRegistrationResult? = null
        database.update { current ->
            val beforeCapabilities = effectiveCapabilities(current.applications.values)
            val old = current.applications[applicationId]
            val capabilities = Capability.entries.filter { it in registration.capabilities }
            val unchanged = old?.let {
                it.displayName == registration.displayName &&
                    it.version == registration.version &&
                    it.capabilities == capabilities
            } == true
            val stored = if (unchanged) {
                old!!
            } else {
                StoredApplicationRegistration(
                    applicationId = applicationId,
                    displayName = registration.displayName,
                    version = registration.version,
                    capabilities = capabilities,
                    updatedAtEpochMillis = clock.millis(),
                )
            }
            val applications = current.applications + (applicationId to stored)
            val afterCapabilities = effectiveCapabilities(applications.values)
            result = ApplicationRegistrationResult(
                application = stored.toView(),
                capabilitiesChanged = beforeCapabilities != afterCapabilities,
            )
            if (unchanged) current else current.copy(applications = applications)
        }
        return checkNotNull(result)
    }

    override fun find(applicationId: String): ApplicationView? {
        validateApplicationId(applicationId)
        return database.load().applications[applicationId]?.toView()
    }

    override fun list(): ApplicationListResponse {
        val applications = database.load().applications
        return ApplicationListResponse(
            applications = applications.values.sortedBy { it.applicationId }.map { it.toView() },
            effectiveCapabilities = effectiveCapabilities(applications.values),
        )
    }

    override fun effectiveCapabilities(): List<Capability> =
        effectiveCapabilities(database.load().applications.values)

    override fun delete(applicationId: String): Boolean {
        validateApplicationId(applicationId)
        var existed = false
        database.update { current ->
            existed = applicationId in current.applications
            if (existed) current.copy(applications = current.applications - applicationId) else current
        }
        return existed
    }

    override fun profilePublicationState(): ApplicationProfilePublicationState =
        database.load().profilePublication

    override fun updateProfilePublicationState(
        transform: (ApplicationProfilePublicationState) -> ApplicationProfilePublicationState,
    ): ApplicationProfilePublicationState {
        var updated: ApplicationProfilePublicationState? = null
        database.update { current ->
            transform(current.profilePublication).also {
                it.validateStored()
                it.validateTransitionFrom(current.profilePublication)
                updated = it
            }.let { current.copy(profilePublication = it) }
        }
        return checkNotNull(updated)
    }
}

/**
 * One globally ordered generic-send queue whose entire state belongs to this daemon process.
 *
 * Pending bodies, submission-id idempotency, and latest stream sequences deliberately disappear
 * on daemon restart. A batch is prepared against private copies and committed under one lock so a
 * conflict, stale sequence, invalid record, or message-id failure leaves every structure unchanged.
 */
class InMemoryGenericSendOutbox(
    private val applications: ApplicationRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val messageIdFactory: () -> String = { UUID.randomUUID().toString() },
) : GenericSendOutbox {
    private val lock = ReentrantLock()
    private val pending = mutableListOf<PendingSend>()
    private val submissions = mutableMapOf<String, MutableMap<String, AcceptedSubmission>>()
    private val streamSequences = mutableMapOf<String, MutableMap<String, Long>>()

    override fun accept(sends: List<ResolvedSend>): List<SendAccepted> {
        require(sends.isNotEmpty()) { "send batch must not be empty" }
        sends.forEach(::validateResolvedSend)
        val applicationId = sends.first().applicationId
        require(sends.all { it.applicationId == applicationId }) {
            "all records in one send batch must use the same applicationId"
        }

        return lock.withLock {
            if (applications.find(applicationId) == null) {
                throw ApplicationNotRegisteredException(applicationId)
            }

            val updatedPending = pending.toMutableList()
            val applicationSubmissions = submissions[applicationId].orEmpty().toMutableMap()
            val applicationSequences = streamSequences[applicationId].orEmpty().toMutableMap()
            val usedMessageIds = buildSet {
                pending.forEach { add(it.messageId) }
                submissions.values.forEach { records ->
                    records.values.forEach { add(it.messageId) }
                }
            }.toMutableSet()
            val accepted = ArrayList<SendAccepted>(sends.size)

            sends.forEach { send ->
                val existing = send.submissionId?.let(applicationSubmissions::get)
                if (existing != null) {
                    if (!existing.matches(send)) {
                        throw SubmissionConflictException(applicationId, checkNotNull(send.submissionId))
                    }
                    accepted += SendAccepted(
                        messageId = existing.messageId,
                        acceptedAtEpochMillis = existing.acceptedAtEpochMillis,
                        submissionId = send.submissionId,
                    )
                    return@forEach
                }

                send.queuePolicy?.streamKey?.let { streamKey ->
                    val sequence = checkNotNull(send.queuePolicy.sequence)
                    applicationSequences[streamKey]?.let { latest ->
                        if (sequence <= latest) {
                            throw StaleSendSequenceException(applicationId, streamKey, sequence, latest)
                        }
                    }
                    applicationSequences[streamKey] = sequence
                }

                val replaceableKeys = buildSet {
                    send.queuePolicy?.coalesceKey?.let(::add)
                    send.queuePolicy?.supersedeKeys?.let(::addAll)
                }
                if (replaceableKeys.isNotEmpty()) {
                    updatedPending.removeAll { item ->
                        item.applicationId == applicationId &&
                            item.queuePolicy?.coalesceKey in replaceableKeys
                    }
                }

                val messageId = nextUniqueMessageId(usedMessageIds)
                val acceptedAt = clock.millis()
                val item = PendingSend(
                    messageId = messageId,
                    acceptedAtEpochMillis = acceptedAt,
                    applicationId = applicationId,
                    messageType = send.messageType,
                    body = send.body.copyOf(),
                    scope = send.scope,
                    urgency = send.urgency,
                    signWith = send.signWith,
                    submissionId = send.submissionId,
                    queuePolicy = send.queuePolicy,
                )
                updatedPending += item
                send.submissionId?.let { submissionId ->
                    applicationSubmissions[submissionId] = AcceptedSubmission.from(item)
                }
                accepted += SendAccepted(messageId, acceptedAt, send.submissionId)
            }

            pending.clear()
            pending.addAll(updatedPending)
            submissions[applicationId] = applicationSubmissions
            if (applicationSequences.isEmpty()) {
                streamSequences.remove(applicationId)
            } else {
                streamSequences[applicationId] = applicationSequences
            }
            accepted
        }
    }

    override fun peekConsecutive(maxItems: Int): List<PendingSend> {
        require(maxItems > 0) { "maxItems must be positive" }
        return lock.withLock {
            val first = pending.firstOrNull() ?: return@withLock emptyList()
            pending.asSequence()
                .takeWhile { first.belongsToSameDispatchGroup(it) }
                .take(maxItems)
                .map { it.copy(body = it.body.copyOf()) }
                .toList()
        }
    }

    override fun checkpoint(messageId: String): Boolean {
        validateMessageId(messageId)
        lock.withLock {
            pending.removeAll { it.messageId == messageId }
        }
        return true
    }

    override fun removeApplication(applicationId: String) {
        validateApplicationId(applicationId)
        lock.withLock {
            pending.removeAll { it.applicationId == applicationId }
            submissions.remove(applicationId)
            streamSequences.remove(applicationId)
        }
    }

    override fun pendingCount(): Int = lock.withLock { pending.size }

    private fun nextUniqueMessageId(used: MutableSet<String>): String {
        repeat(MAXIMUM_MESSAGE_ID_ATTEMPTS) {
            val candidate = messageIdFactory()
            validateMessageId(candidate)
            if (used.add(candidate)) return candidate
        }
        throw IllegalStateException("message id generator repeatedly returned an existing id")
    }
}

@Serializable
data class StoredApplicationRegistration(
    val applicationId: String,
    val displayName: String,
    val version: String? = null,
    val capabilities: List<Capability>,
    val updatedAtEpochMillis: Long,
) {
    fun validate() {
        validateApplicationId(applicationId)
        require(displayName.isNotBlank() && displayName.length <= MAXIMUM_DISPLAY_NAME_LENGTH) {
            "invalid application display name"
        }
        require(version == null || (version.isNotBlank() && version.length <= MAXIMUM_VERSION_LENGTH)) {
            "invalid application version"
        }
        require(capabilities == Capability.entries.filter { it in capabilities.toSet() }) {
            "application capabilities must be unique and in protocol order"
        }
        require(updatedAtEpochMillis >= 0) { "invalid application update time" }
    }

    fun toView(): ApplicationView = ApplicationView(
        applicationId = applicationId,
        displayName = displayName,
        version = version,
        capabilities = capabilities,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

private data class AcceptedSubmission(
    val messageId: String,
    val acceptedAtEpochMillis: Long,
    val messageType: MessageType,
    val bodySize: Int,
    val bodySha256: String,
    val scope: Recipients,
    val urgency: Urgency,
    val signWith: SignerSelection,
    val queuePolicy: QueuePolicy? = null,
) {
    fun matches(send: ResolvedSend): Boolean =
        messageType == send.messageType &&
            bodySize == send.body.size &&
            bodySha256 == send.body.sha256Base64() &&
            scope == send.scope &&
            urgency == send.urgency &&
            signWith == send.signWith &&
            queuePolicy == send.queuePolicy

    companion object {
        fun from(item: PendingSend): AcceptedSubmission = AcceptedSubmission(
            messageId = item.messageId,
            acceptedAtEpochMillis = item.acceptedAtEpochMillis,
            messageType = item.messageType,
            bodySize = item.body.size,
            bodySha256 = item.body.sha256Base64(),
            scope = item.scope,
            urgency = item.urgency,
            signWith = item.signWith,
            queuePolicy = item.queuePolicy,
        )
    }
}

internal fun ApplicationProfilePublicationState.validateStored() {
    require(cardCreatedAtFloorEpochMillis == null || cardCreatedAtFloorEpochMillis >= 0) {
        "invalid profile card creation floor"
    }
    require(profileFingerprint == null || (profileFingerprint.isNotBlank() && profileFingerprint.length <= 512)) {
        "invalid profile fingerprint"
    }
    require(profileUpdatedAtEpochMillis == null || profileUpdatedAtEpochMillis >= 0) {
        "invalid profile update time"
    }
    require(publicationRevision >= 0) { "invalid profile publication revision" }
    require(
        pendingPublicationRevision == null ||
            pendingPublicationRevision in 1..publicationRevision,
    ) { "invalid pending profile publication revision" }
}

private fun ApplicationProfilePublicationState.validateTransitionFrom(
    previous: ApplicationProfilePublicationState,
) {
    previous.cardCreatedAtFloorEpochMillis?.let { old ->
        require(cardCreatedAtFloorEpochMillis != null && cardCreatedAtFloorEpochMillis >= old) {
            "profile card creation floor must not move backwards"
        }
    }
    previous.profileUpdatedAtEpochMillis?.let { old ->
        require(profileUpdatedAtEpochMillis != null && profileUpdatedAtEpochMillis >= old) {
            "profile updatedAt must not move backwards"
        }
    }
    require(publicationRevision >= previous.publicationRevision) {
        "profile publication revision must not move backwards"
    }
    if (previous.pendingPublicationRevision != null && pendingPublicationRevision != null) {
        require(pendingPublicationRevision >= previous.pendingPublicationRevision) {
            "pending profile publication revision must not move backwards"
        }
    }
}

private fun validateResolvedSend(send: ResolvedSend) {
    validateApplicationId(send.applicationId)
    require(send.body.size <= MAXIMUM_SEND_BODY_BYTES) { "send body is too large" }
    send.submissionId?.let(::validateSubmissionId)
    send.queuePolicy?.validateStored()
}

private fun validateRegistration(registration: ApplicationRegistrationRequest) {
    val version = registration.version
    require(
        registration.displayName.isNotBlank() &&
            registration.displayName.length <= MAXIMUM_DISPLAY_NAME_LENGTH,
    ) { "invalid application display name" }
    require(
        version == null || (version.isNotBlank() && version.length <= MAXIMUM_VERSION_LENGTH),
    ) { "invalid application version" }
}

private fun QueuePolicy.validateStored() {
    val sequenceValue = sequence
    require((streamKey == null) == (sequenceValue == null)) {
        "queue policy streamKey and sequence must be provided together"
    }
    streamKey?.let(::validateQueueKey)
    require(sequenceValue == null || sequenceValue >= 0) { "queue policy sequence must be non-negative" }
    coalesceKey?.let(::validateQueueKey)
    require(supersedeKeys.size <= MAXIMUM_SUPERSEDE_KEYS) { "too many queue policy supersede keys" }
    supersedeKeys.forEach(::validateQueueKey)
}

private fun effectiveCapabilities(
    applications: Collection<StoredApplicationRegistration>,
): List<Capability> {
    val declared = buildSet {
        addAll(INTRINSIC_DAEMON_CAPABILITIES)
        applications.forEach { addAll(it.capabilities) }
    }
    return Capability.entries.filter { it in declared }
}

private fun validateApplicationId(applicationId: String) {
    require(APPLICATION_ID.matches(applicationId)) { "invalid application id" }
}

private fun validateSubmissionId(submissionId: String) {
    require(submissionId.isNotBlank() && submissionId.length <= MAXIMUM_SUBMISSION_ID_LENGTH) {
        "invalid submission id"
    }
}

private fun validateQueueKey(key: String) {
    require(key.isNotBlank() && key.length <= MAXIMUM_QUEUE_KEY_LENGTH) { "invalid queue policy key" }
}

private fun validateMessageId(messageId: String) {
    require(messageId.isNotBlank() && messageId.length <= MAXIMUM_MESSAGE_ID_LENGTH) {
        "invalid message id"
    }
}

private fun ByteArray.sha256Base64(): String = Base64.getEncoder().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(this),
)

private val APPLICATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private const val MAXIMUM_DISPLAY_NAME_LENGTH = 256
private const val MAXIMUM_VERSION_LENGTH = 128
private const val MAXIMUM_SUBMISSION_ID_LENGTH = 256
private const val MAXIMUM_QUEUE_KEY_LENGTH = 256
private const val MAXIMUM_MESSAGE_ID_LENGTH = 512
private const val MAXIMUM_SUPERSEDE_KEYS = 128
private const val MAXIMUM_SEND_BODY_BYTES = 1024 * 1024
private const val MAXIMUM_MESSAGE_ID_ATTEMPTS = 16
