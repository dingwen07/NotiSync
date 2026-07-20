package net.extrawdw.notisync.daemon

import kotlinx.serialization.Serializable
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

/** Capabilities provided by the generic desktop bridge even when no local applications register. */
val INTRINSIC_DAEMON_CAPABILITIES: List<Capability> = listOf(
    Capability.FOREGROUND_CONNECTION,
    Capability.CAPABILITY_ROUTING_V1,
)

/** Persistent same-UID application declarations used to build the daemon's advertised profile. */
interface ApplicationRegistry {
    fun register(
        applicationId: String,
        registration: ApplicationRegistrationRequest,
    ): ApplicationRegistrationResult

    fun find(applicationId: String): ApplicationView?

    fun list(): ApplicationListResponse

    fun effectiveCapabilities(): List<Capability>

    /** Idempotently remove an application registration. */
    fun delete(applicationId: String): Boolean
}

data class ApplicationRegistrationResult(
    val application: ApplicationView,
    /** True only when the effective daemon capability set changed. */
    val capabilitiesChanged: Boolean,
)

/** Monotonic profile publication bookkeeping retained across daemon restarts. */
@Serializable
data class ApplicationProfilePublicationState(
    val cardCreatedAtFloorEpochMillis: Long? = null,
    val profileFingerprint: String? = null,
    val profileUpdatedAtEpochMillis: Long? = null,
    val publicationRevision: Long = 0,
    val pendingPublicationRevision: Long? = null,
)

interface ApplicationProfilePublicationStateStore {
    fun profilePublicationState(): ApplicationProfilePublicationState

    fun updateProfilePublicationState(
        transform: (ApplicationProfilePublicationState) -> ApplicationProfilePublicationState,
    ): ApplicationProfilePublicationState
}

/** A generic send after the service has resolved defaults and validated the message body. */
data class ResolvedSend(
    val applicationId: String,
    val messageType: MessageType,
    val body: ByteArray,
    val scope: Recipients,
    val urgency: Urgency,
    val signWith: SignerSelection,
    val submissionId: String? = null,
    val queuePolicy: QueuePolicy? = null,
)

/** One globally ordered, fully resolved item awaiting broker acceptance. */
@Serializable
data class PendingSend(
    val messageId: String,
    val acceptedAtEpochMillis: Long,
    val applicationId: String,
    val messageType: MessageType,
    val body: ByteArray,
    val scope: Recipients,
    val urgency: Urgency,
    val signWith: SignerSelection,
    val submissionId: String? = null,
    val queuePolicy: QueuePolicy? = null,
) {
    fun belongsToSameDispatchGroup(other: PendingSend): Boolean =
        messageType == other.messageType &&
            scope == other.scope &&
            urgency == other.urgency &&
            signWith == other.signWith
}

/** Daemon-lifetime acceptance and ordered dispatch boundary for generic local submissions. */
interface GenericSendOutbox {
    /** Atomically accept all records, or retain none if any record is invalid or conflicts. */
    fun accept(sends: List<ResolvedSend>): List<SendAccepted>

    fun accept(send: ResolvedSend): SendAccepted = accept(listOf(send)).single()

    /** Return the maximal consecutive prefix with one dispatch tuple, optionally capped by [maxItems]. */
    fun peekConsecutive(maxItems: Int = Int.MAX_VALUE): List<PendingSend>

    /** Record the broker-accepted checkpoint for one item; idempotent even if already superseded. */
    fun checkpoint(messageId: String): Boolean

    /** Remove pending sends and daemon-lifetime policy/idempotency state owned by one application. */
    fun removeApplication(applicationId: String)

    fun pendingCount(): Int
}

sealed class GenericSendRejection(message: String) : IllegalArgumentException(message)

class ApplicationNotRegisteredException(val applicationId: String) :
    GenericSendRejection("application '$applicationId' is not registered")

class SubmissionConflictException(
    val applicationId: String,
    val submissionId: String,
) : GenericSendRejection("submission '$submissionId' conflicts with its previous use by '$applicationId'")

class StaleSendSequenceException(
    val applicationId: String,
    val streamKey: String,
    val sequence: Long,
    val latestSequence: Long,
) : GenericSendRejection(
    "sequence $sequence for '$applicationId/$streamKey' is not newer than $latestSequence",
)
