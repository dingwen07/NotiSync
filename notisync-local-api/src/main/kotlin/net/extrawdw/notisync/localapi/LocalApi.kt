package net.extrawdw.notisync.localapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val platformName: String? = null,
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

/**
 * A daemon-owned source namespace. The server binds it to the accepted socket's (PID,starttime)
 * and returns an unguessable bearer for platforms where peer credentials cannot be verified.
 */
@Serializable
data class CreateSessionRequest(
    val clientName: String,
    val requestedSourceName: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class SessionResponse(
    val sessionId: String,
    val sourceKey: String,
    val bearerToken: String,
    val peerIdentityVerified: Boolean,
)

@Serializable
data class CloseSessionRequest(val sessionId: String)

@Serializable
enum class NotificationPhase { INITIAL, PERIODIC, BLOCKED, RESUMED, COMPLETED, FAILED }

@Serializable
enum class NotificationActionKind { REMOTE_INPUT, WRITE_INPUT, SIGNAL }

/**
 * How long a displayed action remains valid at the local source.
 *
 * [GENERATION] is the safe default for contextual actions: replacing the notification invalidates
 * every PendingIntent from the previous generation. [SESSION] is for process-control actions whose
 * meaning does not change while the process-owned session is alive (for example SIGINT/SIGTERM).
 */
@Serializable
enum class NotificationActionLifetime { GENERATION, SESSION }

@Serializable
data class LocalNotificationAction(
    /**
     * Stable within [generation], or for the process-owned session when [lifetime] is
     * [NotificationActionLifetime.SESSION].
     */
    val id: String,
    val title: String,
    val kind: NotificationActionKind,
    val generation: Long,
    val inputText: String? = null,
    val remoteInputLabel: String? = null,
    val signal: String? = null,
    val lifetime: NotificationActionLifetime = NotificationActionLifetime.GENERATION,
)

@Serializable
data class LocalProgress(
    val current: Long? = null,
    val total: Long? = null,
    val indeterminate: Boolean = false,
) {
    init {
        require(indeterminate || (current != null && total != null && total > 0)) {
            "determinate progress requires current and a positive total"
        }
    }
}

/**
 * General local notification request. Recipient filtering is expressed by phase so policy remains
 * centralized in the daemon: PERIODIC requires DISPLAY + routing + push filtering + update support.
 */
@Serializable
data class NotificationRequest(
    val sessionId: String,
    val generation: Long,
    val phase: NotificationPhase,
    val title: String,
    val text: String,
    val expandedText: String? = null,
    val shortCriticalText: String? = null,
    val progress: LocalProgress? = null,
    val silent: Boolean,
    val ongoing: Boolean,
    val clearable: Boolean,
    val requestPromotedOngoing: Boolean = ongoing,
    val actions: List<LocalNotificationAction> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
enum class LocalRunPhase { RUNNING, BLOCKED, COMPLETED, FAILED_TO_START }

@Serializable
enum class LocalRunUpdateReason { INITIAL, PERIODIC, BLOCKED, RESUMED, COMPLETED, FAILED, LLM_SUMMARY, REFRESH }

@Serializable
enum class LocalRunBlockedReason { TERMINAL_INPUT, OUTPUT_AND_CPU_IDLE }

@Serializable
enum class LocalRunPromptKind { YES_NO, TEXT }

@Serializable
data class LocalRunProgress(
    val current: Long? = null,
    val total: Long? = null,
    val indeterminate: Boolean = false,
) {
    init {
        require(indeterminate || (current != null && total != null && total > 0)) {
            "determinate progress requires current and a positive total"
        }
    }
}

@Serializable
data class LocalRunTerminalSnapshot(
    val text: String,
    val truncated: Boolean,
    val rawBytesSeen: Long,
)

@Serializable
data class LocalRunLlmSummary(
    val title: String,
    val text: String,
    val expandedText: String? = null,
)

/**
 * A complete local Run snapshot. The daemon binds [hostClientId] on the authenticated mesh envelope,
 * so the process-owned local source cannot claim another host identity.
 */
@Serializable
data class RunStateRequest(
    val sessionId: String,
    val runId: String,
    val revision: Long,
    val phase: LocalRunPhase,
    val updateReason: LocalRunUpdateReason,
    val startedAt: Long,
    val updatedAt: Long,
    val argv: List<String>,
    val cwd: String,
    val usesPty: Boolean,
    val terminal: LocalRunTerminalSnapshot,
    val interactionGeneration: Long = 0,
    val endedAt: Long? = null,
    val blockedReason: LocalRunBlockedReason? = null,
    val prompt: LocalRunPromptKind? = null,
    val progress: LocalRunProgress? = null,
    val exitCode: Int? = null,
    val failureMessage: String? = null,
    val llmSummary: LocalRunLlmSummary? = null,
    val responseToRequestId: String? = null,
)

@Serializable
data class AcceptedResponse(val id: String, val acceptedAtEpochMillis: Long)

@Serializable
enum class LocalEventType { ACTION, RUN_CONTROL, DISMISSAL, DAEMON_SHUTDOWN, SESSION_EXPIRED, HEARTBEAT }

@Serializable
enum class LocalRunControlKind { REFRESH, WRITE_INPUT, SIGNAL }

@Serializable
enum class LocalRunControlStatus { APPLIED, REJECTED, NOT_ACTIVE, STALE, FAILED }

/** One line on GET /v1/events. Payload fields are intentionally narrow and source-scoped. */
@Serializable
data class LocalEvent(
    val id: String,
    val type: LocalEventType,
    val sessionId: String,
    val createdAtEpochMillis: Long,
    val generation: Long? = null,
    val actionId: String? = null,
    val inputText: String? = null,
    val senderClientId: String? = null,
    val requestId: String? = null,
    val runId: String? = null,
    val runControlKind: LocalRunControlKind? = null,
    val interactionGeneration: Long? = null,
    val signal: String? = null,
)

@Serializable
data class EventAckRequest(val sessionId: String)

@Serializable
data class EventCompletionRequest(
    val sessionId: String,
    val status: LocalRunControlStatus,
    val message: String? = null,
)

@Serializable
data class DismissalRequest(
    val sessionId: String,
    val generation: Long,
)

@Serializable
data class ActionSendRequest(
    val sessionId: String,
    /** Remote notification origin. The daemon will only resolve this to a trusted own device. */
    val sourceClientId: String,
    val sourceKey: String,
    /** Origin-scoped wire index and echoed title supplied with the remote notification event. */
    val actionIndex: Int,
    val actionTitle: String,
    /** Opaque capability fields copied exactly from the displayed wire action. */
    val actionGeneration: Long? = null,
    val actionToken: String? = null,
    val inputText: String? = null,
)
