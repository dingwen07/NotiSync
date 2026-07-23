package net.extrawdw.notisync.localapi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SignerSelection
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.Urgency

/** JSON codec shared by the daemon and local clients. Unknown fields are ignored for rolling upgrades. */
val LocalApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
)

@Serializable
enum class DaemonConnectionState { STARTING, CONNECTING, CONNECTED, BACKING_OFF, UNSUPPORTED_INTEGRITY, STOPPED }

@Serializable
data class DaemonStatus(
    val version: String,
    val clientId: String? = null,
    val deviceName: String? = null,
    val connectionState: DaemonConnectionState,
    val brokerUrl: String? = null,
    val capabilities: Set<String> = emptySet(),
    val trustStoreQuarantined: Boolean = false,
    val message: String? = null,
)

@Serializable
data class DaemonConfigView(
    val brokerUrl: String,
    val deviceName: String,
    val platformName: String,
    val automaticallyApplyTrustedDeviceTables: Boolean,
    val logLevel: String,
    val websocketPingSeconds: Int,
)

@Serializable
data class DaemonConfigPatch(
    val brokerUrl: String? = null,
    val deviceName: String? = null,
    val automaticallyApplyTrustedDeviceTables: Boolean? = null,
    val logLevel: String? = null,
    val websocketPingSeconds: Int? = null,
)

@Serializable
enum class DeviceClassification { OWN, OTHER }

@Serializable
enum class DeviceTrustStatus { PENDING, TRUSTED, REVOKE_PENDING, REVOKED, QUARANTINED }

@Serializable
data class DeviceView(
    val clientId: String,
    val name: String,
    val classification: DeviceClassification,
    val trustStatus: DeviceTrustStatus,
    val platform: String? = null,
    val capabilities: Set<String> = emptySet(),
    val identityFingerprint: String,
    /** Whether a verified immutable identity card is currently pinned for this device. */
    val keyAvailable: Boolean = false,
    /** Whether the pinned card and newest operational key epoch pass their cryptographic checks. */
    val verified: Boolean = false,
    /** Highest verified operational key epoch held locally; zero means unavailable. */
    val currentEpoch: Int = 0,
    val signingKeyFingerprint: String? = null,
    val hpkeKeyFingerprint: String? = null,
    val introducedBy: String? = null,
    val allowedActions: Set<DeviceAction> = emptySet(),
)

@Serializable
enum class DeviceAction { APPROVE, REJECT, REVOKE, CONFIRM_REVOKE, DECLINE_REVOKE, RESTORE, KEEP, PURGE }

@Serializable
data class DeviceActionRequest(val action: DeviceAction)

@Serializable
data class DeviceListResponse(val devices: List<DeviceView>)

@Serializable
data class PairingPayloadResponse(val payload: String, val deepLink: String)

@Serializable
data class PairingInspectRequest(val payload: String)

@Serializable
data class PairingCandidate(
    val clientId: String,
    val name: String,
    val identityFingerprint: String,
    val capabilities: Set<String> = emptySet(),
)

@Serializable
data class PairingAcceptRequest(
    val payload: String,
    val classification: DeviceClassification,
)

@Serializable
data class QuarantineActionRequest(val action: QuarantineAction)

@Serializable
enum class QuarantineAction { APPROVE_AND_RESIGN, CLEAR }

/** Persistent declaration made by one same-UID local application. */
@Serializable
data class ApplicationRegistrationRequest(
    val displayName: String,
    val version: String? = null,
    val capabilities: Set<Capability> = emptySet(),
)

@Serializable
data class ApplicationView(
    val applicationId: String,
    val displayName: String,
    val version: String? = null,
    val capabilities: List<Capability> = emptyList(),
    val updatedAtEpochMillis: Long,
)

@Serializable
data class ApplicationListResponse(
    val applications: List<ApplicationView>,
    val effectiveCapabilities: List<Capability>,
)

/** Sender-side queue behavior. It never alters an item already accepted by the broker. */
@Serializable
data class QueuePolicy(
    val streamKey: String? = null,
    val sequence: Long? = null,
    val coalesceKey: String? = null,
    val supersedeKeys: Set<String> = emptySet(),
)

/** One generic SecureChannel submission. [body] is base64-encoded CBOR. */
@Serializable
data class SendRequest(
    val applicationId: String,
    val messageType: MessageType,
    val body: String,
    val scope: Recipients? = null,
    val urgency: Urgency? = null,
    val signWith: SignerSelection? = null,
    val submissionId: String? = null,
    val queuePolicy: QueuePolicy? = null,
)

@Serializable
data class SendAccepted(
    val messageId: String,
    val acceptedAtEpochMillis: Long,
    val submissionId: String? = null,
)

/** One scalar-equality predicate applied only to [messageType]. */
@Serializable
data class MessageFilter(
    val messageType: MessageType,
    val path: String,
    val acceptedValues: List<JsonElement>,
)

/** A canonical interest is associated with the caller's OS process lease, not a client-supplied id. */
@Serializable
data class ReceiveRequest(
    val applicationId: String,
    val messageTypes: List<MessageType>? = null,
    val filters: List<MessageFilter> = emptyList(),
)

@Serializable
enum class ReceiveRecordType { MESSAGE, HEARTBEAT }

/** One line of the `/v1/receive` NDJSON response. [body] is the original base64 CBOR. */
@Serializable
data class ReceiveRecord(
    val recordType: ReceiveRecordType,
    val applicationId: String,
    val envelopeId: String? = null,
    val messageType: MessageType? = null,
    val body: String? = null,
    val senderClientId: String? = null,
    val senderOwnDevice: Boolean? = null,
    val signerEpoch: Int? = null,
    val deliveryMode: String? = null,
    val receivedAtEpochMillis: Long,
    /** Sender-signed Envelope.createdAt; null only for heartbeats or a pre-field daemon. */
    val envelopeCreatedAtEpochMillis: Long? = null,
)

@Serializable
data class ApplicationEventAckRequest(val applicationId: String)

@Serializable
data class ApplicationEventCompletionRequest(
    val applicationId: String,
    val sends: List<SendRequest> = emptyList(),
)
