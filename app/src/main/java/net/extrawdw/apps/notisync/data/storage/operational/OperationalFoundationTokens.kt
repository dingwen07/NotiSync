package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnTypeConverter

/**
 * Persisted tokens are an explicit compatibility surface. They deliberately do not use enum names,
 * ordinals, or permissive fallbacks: an unknown durable state must stop the owning aggregate from
 * becoming ready rather than silently changing its security or lifecycle meaning.
 */
internal interface OperationalStorageToken {
    val token: String
}

internal inline fun <reified T> decodeOperationalToken(
    value: String,
    values: Array<T>,
): T where T : Enum<T>, T : OperationalStorageToken =
    values.firstOrNull { it.token == value }
        ?: throw IllegalArgumentException("Unknown ${T::class.simpleName} storage token")

internal enum class ActivityFeature(override val token: String) : OperationalStorageToken {
    NOTIFICATION("notification"),
    RUN("run"),
    SCREEN_MIRRORING("screen_mirroring"),
    SEAL("seal"),
    SSH_AGENT("ssh_agent"),
    PROFILE("profile"),
    TRUST("trust"),
    PAIRING("pairing"),
    ROUTE("route"),
    SECURITY("security");

    companion object {
        fun decode(value: String): ActivityFeature = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class ActivityAction(override val token: String) : OperationalStorageToken {
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
    CONFLICT("conflict");

    companion object {
        fun decode(value: String): ActivityAction = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class ActivityDirection(override val token: String) : OperationalStorageToken {
    LOCAL("local"),
    INBOUND("inbound"),
    OUTBOUND("outbound");

    companion object {
        fun decode(value: String): ActivityDirection = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class ActivityOutcome(override val token: String) : OperationalStorageToken {
    SUCCESS("success"),
    NO_OP("no_op"),
    DUPLICATE("duplicate"),
    SUPERSEDED("superseded"),
    REJECTED("rejected"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
    FAILED("failed"),
    SECURITY_BLOCKED("security_blocked");

    companion object {
        fun decode(value: String): ActivityOutcome = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class OperationalDeliveryMode(override val token: String) : OperationalStorageToken {
    UNKNOWN("unknown"),
    WEBSOCKET("websocket"),
    FCM_INLINE("fcm_inline"),
    FCM_RELAY_FETCH("fcm_relay_fetch"),
    RELAY_DRAIN("relay_drain");

    companion object {
        fun decode(value: String): OperationalDeliveryMode = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class NotificationOriginPlatform(override val token: String) : OperationalStorageToken {
    ANDROID_LOCAL("android_local"),
    IOS_ANCS("ios_ancs");

    companion object {
        fun decode(value: String): NotificationOriginPlatform =
            decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class AndroidPolicyScope(override val token: String) : OperationalStorageToken {
    CHANNEL("channel"),
    GROUP("group");

    companion object {
        fun decode(value: String): AndroidPolicyScope = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class MessageDedupEvidenceKind(override val token: String) : OperationalStorageToken {
    LEGACY_MESSAGE_ID_ONLY("legacy_message_id_only"),
    AUTHENTICATED_FINGERPRINT("authenticated_fingerprint");

    companion object {
        fun decode(value: String): MessageDedupEvidenceKind =
            decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class RelayBatchPresentationKind(override val token: String) : OperationalStorageToken {
    NONE("none"),
    NOTIFICATION("notification"),
    DISMISSAL("dismissal");

    companion object {
        fun decode(value: String): RelayBatchPresentationKind =
            decodeOperationalToken(value, entries.toTypedArray())
    }
}

/** Write-side format token. Room stores the raw string so a future/unknown value remains recoverable. */
internal object ProtectedBlobSchemes {
    const val ANDROID_KEYSTORE_AES_GCM = "android_keystore_aes_gcm"
}

internal enum class RunPhaseToken(override val token: String) : OperationalStorageToken {
    RUNNING("running"),
    BLOCKED("blocked"),
    COMPLETED("completed"),
    FAILED_TO_START("failed_to_start");

    companion object {
        fun decode(value: String): RunPhaseToken = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal object OperationalFoundationTypeConverters {
    @ColumnTypeConverter fun encode(value: ActivityFeature) = value.token
    @ColumnTypeConverter fun decodeActivityFeature(value: String) = ActivityFeature.decode(value)
    @ColumnTypeConverter fun encode(value: ActivityAction) = value.token
    @ColumnTypeConverter fun decodeActivityAction(value: String) = ActivityAction.decode(value)
    @ColumnTypeConverter fun encode(value: ActivityDirection) = value.token
    @ColumnTypeConverter fun decodeActivityDirection(value: String) = ActivityDirection.decode(value)
    @ColumnTypeConverter fun encode(value: ActivityOutcome) = value.token
    @ColumnTypeConverter fun decodeActivityOutcome(value: String) = ActivityOutcome.decode(value)
    @ColumnTypeConverter fun encode(value: OperationalDeliveryMode) = value.token
    @ColumnTypeConverter fun decodeOperationalDeliveryMode(value: String) = OperationalDeliveryMode.decode(value)
    @ColumnTypeConverter fun encode(value: NotificationOriginPlatform) = value.token
    @ColumnTypeConverter fun decodeNotificationOriginPlatform(value: String) = NotificationOriginPlatform.decode(value)
    @ColumnTypeConverter fun encode(value: AndroidPolicyScope) = value.token
    @ColumnTypeConverter fun decodeAndroidPolicyScope(value: String) = AndroidPolicyScope.decode(value)
    @ColumnTypeConverter fun encode(value: MessageDedupEvidenceKind) = value.token
    @ColumnTypeConverter fun decodeMessageDedupEvidenceKind(value: String) = MessageDedupEvidenceKind.decode(value)
    @ColumnTypeConverter fun encode(value: RelayBatchPresentationKind) = value.token
    @ColumnTypeConverter fun decodeRelayBatchPresentationKind(value: String) = RelayBatchPresentationKind.decode(value)
    @ColumnTypeConverter fun encode(value: RunPhaseToken) = value.token
    @ColumnTypeConverter fun decodeRunPhaseToken(value: String) = RunPhaseToken.decode(value)
}
