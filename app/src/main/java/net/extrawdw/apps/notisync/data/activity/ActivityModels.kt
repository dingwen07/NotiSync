package net.extrawdw.apps.notisync.data.activity

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

/**
 * The deliberately small public contract for the durable Activity projection.
 *
 * Activity is a presentation projection, not an event-sourced authority.  The domain model therefore
 * contains semantic codes and bounded numeric render arguments only; it has no dependency on Room,
 * Android, a locale, or a feature payload.
 */
object ActivityLimits {
    const val MAX_ROWS = 1_000
    const val MAX_AGE_MILLIS = 90L * 24 * 60 * 60 * 1_000
    const val MAX_RENDER_ARGS_BYTES = 4 * 1_024
    const val MAX_IDENTIFIER_CHARS = 256
    const val MAX_SEMANTIC_CODE_CHARS = 128
    const val MAX_COALESCING_TOKEN_BYTES = 32
    const val MAX_COUNT = 1_000_000
    const val MAX_DURATION_MILLIS = 365L * 24 * 60 * 60 * 1_000
}

enum class ActivityFeature(val token: String) {
    NOTIFICATION("notification"),
    RUN("run"),
    SCREEN_MIRRORING("screen_mirroring"),
    SEAL("seal"),
    SSH_AGENT("ssh_agent"),
    PROFILE("profile"),
    TRUST("trust"),
    PAIRING("pairing"),
    ROUTE("route"),
    SECURITY("security"),
}

enum class ActivityAction(val token: String) {
    CAPTURED("captured"),
    RECEIVED("received"),
    APPLIED("applied"),
    QUEUED("queued"),
    SENT("sent"),
    DISMISSED("dismissed"),
    CONTROLLED("controlled"),
    REQUESTED("requested"),
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    CONNECTED("connected"),
    ENDED("ended"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
    FAILED("failed"),
    PAIRED("paired"),
    REPAIRED("repaired"),
    CONFLICT("conflict"),
}

enum class ActivityDirection(val token: String) {
    LOCAL("local"),
    INBOUND("inbound"),
    OUTBOUND("outbound"),
}

enum class ActivityOutcome(val token: String) {
    SUCCESS("success"),
    NO_OP("no_op"),
    DUPLICATE("duplicate"),
    SUPERSEDED("superseded"),
    REJECTED("rejected"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
    FAILED("failed"),
    SECURITY_BLOCKED("security_blocked"),
}

enum class ActivityDeliveryMode(val token: String) {
    UNKNOWN("unknown"),
    WEBSOCKET("websocket"),
    FCM_INLINE("fcm_inline"),
    FCM_RELAY_FETCH("fcm_relay_fetch"),
    RELAY_DRAIN("relay_drain"),
}

/**
 * Closed render-argument schema.  V1 intentionally permits only bounded numeric values.  In particular,
 * it cannot represent localized prose, notification bodies, hostnames, paths, commands, key material, or
 * provider responses.  New fields require a reviewed codec version rather than a generic map/BLOB escape hatch.
 */
sealed interface ActivityRenderArgs {
    val version: Int

    data class V1(
        val count: Int? = null,
        val revision: Long? = null,
        val durationMillis: Long? = null,
    ) : ActivityRenderArgs {
        override val version: Int = 1

        init {
            require(count == null || count in 0..ActivityLimits.MAX_COUNT) {
                "activity render count is outside the reviewed bound"
            }
            require(revision == null || revision >= 0) {
                "activity render revision must not be negative"
            }
            require(durationMillis == null || durationMillis in 0..ActivityLimits.MAX_DURATION_MILLIS) {
                "activity render duration is outside the reviewed bound"
            }
        }
    }

    /** Safe semantic state used when a persisted version is newer than this binary. */
    data class Unsupported(val storedVersion: Int) : ActivityRenderArgs {
        override val version: Int = storedVersion
    }

    /** Safe semantic state used when a known-version payload is malformed or non-canonical. */
    data class Corrupt(
        val storedVersion: Int,
        val reason: CorruptReason,
    ) : ActivityRenderArgs {
        override val version: Int = storedVersion
    }

    enum class CorruptReason {
        OVERSIZE,
        MALFORMED,
        NON_CANONICAL,
    }
}

/** An opaque keyed equality token used only for reviewed Activity coalescing policies. */
class ActivityCoalescingKeyToken private constructor(private val bytes: ByteArray) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ActivityCoalescingKeyToken && MessageDigest.isEqual(bytes, other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ActivityCoalescingKeyToken(${bytes.size} bytes)"

    companion object {
        fun of(bytes: ByteArray): ActivityCoalescingKeyToken {
            require(bytes.size == ActivityLimits.MAX_COALESCING_TOKEN_BYTES) {
                "activity coalescing token must be exactly ${ActivityLimits.MAX_COALESCING_TOKEN_BYTES} bytes"
            }
            return ActivityCoalescingKeyToken(bytes.copyOf())
        }
    }
}

/** Persisted Activity row exposed to the UI/repositories. */
data class ActivityEvent(
    val eventId: String,
    val occurredAt: Long,
    val recordedAt: Long,
    val feature: ActivityFeature,
    val semanticAction: ActivityAction,
    val direction: ActivityDirection,
    val outcome: ActivityOutcome,
    val peerClientId: String?,
    val correlationId: String?,
    val deliveryMode: ActivityDeliveryMode?,
    val renderArgs: ActivityRenderArgs,
    val coalescingKeyToken: ActivityCoalescingKeyToken?,
    val coalescedCount: Int,
)

/** Typed producer input.  Unsupported/corrupt render states can never be written through this API. */
data class ActivityEventDraft(
    val eventId: String,
    val occurredAt: Long,
    val recordedAt: Long,
    val feature: ActivityFeature,
    val semanticAction: ActivityAction,
    val direction: ActivityDirection,
    val outcome: ActivityOutcome,
    val peerClientId: String? = null,
    val correlationId: String? = null,
    val deliveryMode: ActivityDeliveryMode? = null,
    val renderArgs: ActivityRenderArgs.V1 = ActivityRenderArgs.V1(),
    val coalescingKeyToken: ActivityCoalescingKeyToken? = null,
    val coalescedCount: Int = 1,
) {
    init {
        requireIdentifier(eventId, "activity event id")
        requireTimestamp(occurredAt, "activity occurred time")
        requireTimestamp(recordedAt, "activity recorded time")
        peerClientId?.let { requireIdentifier(it, "activity peer id") }
        correlationId?.let { requireIdentifier(it, "activity correlation id") }
        require(coalescedCount in 1..ActivityLimits.MAX_COUNT) {
            "activity coalesced count is outside the reviewed bound"
        }
    }
}

/** Storage-independent observation and one-shot command boundary. */
interface ActivityRepository {
    fun observeNewest(limit: Int = ActivityLimits.MAX_ROWS): Flow<List<ActivityEvent>>

    suspend fun insert(event: ActivityEventDraft): Boolean

    suspend fun prune(now: Long): Int
}

private fun requireIdentifier(value: String, name: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value.length <= ActivityLimits.MAX_IDENTIFIER_CHARS) { "$name is too long" }
    require(value.none(Char::isISOControl)) { "$name contains a control character" }
    require(value.none(Char::isWhitespace)) { "$name must be a compact identifier" }
}

private fun requireTimestamp(value: Long, name: String) {
    require(value > 0) { "$name must be positive" }
}

/** UI-independent reducer primitive for lifecycle-aware screens. */
data class ActivityTimelineState(
    val events: List<ActivityEvent> = emptyList(),
    val isLoading: Boolean = true,
    val error: ActivityTimelineError? = null,
)

sealed interface ActivityTimelineError {
    data object ObservationFailed : ActivityTimelineError
}

sealed interface ActivityTimelineAction {
    data object Started : ActivityTimelineAction
    data class Observed(val events: List<ActivityEvent>) : ActivityTimelineAction
    data object ObservationFailed : ActivityTimelineAction
}

fun reduceActivityTimeline(
    state: ActivityTimelineState,
    action: ActivityTimelineAction,
): ActivityTimelineState = when (action) {
    ActivityTimelineAction.Started -> state.copy(isLoading = true, error = null)
    is ActivityTimelineAction.Observed -> state.copy(
        events = action.events.take(ActivityLimits.MAX_ROWS),
        isLoading = false,
        error = null,
    )
    ActivityTimelineAction.ObservationFailed -> state.copy(
        isLoading = false,
        error = ActivityTimelineError.ObservationFailed,
    )
}
