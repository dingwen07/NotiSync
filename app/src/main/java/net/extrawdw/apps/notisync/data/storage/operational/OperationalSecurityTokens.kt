package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnTypeConverter

internal enum class SealEnrollmentState(override val token: String) : OperationalStorageToken {
    DISABLED("disabled"),
    ENROLLED("enrolled"),
    RECOVERY_REQUIRED("recovery_required");

    companion object {
        fun decode(value: String): SealEnrollmentState = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class SealObjectKind(override val token: String) : OperationalStorageToken {
    GIT_COMMIT("git_commit");

    companion object {
        fun decode(value: String): SealObjectKind = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class SealRequestState(override val token: String) : OperationalStorageToken {
    PENDING_REVIEW("pending_review"),
    USER_APPROVED("user_approved"),
    PROVIDER_INTERACTION("provider_interaction"),
    RESPONSE_QUEUED("response_queued"),
    SENT("sent"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
    FAILED("failed");

    companion object {
        fun decode(value: String): SealRequestState = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class SealRequestOutcome(override val token: String) : OperationalStorageToken {
    APPROVED("approved"),
    REJECTED("rejected"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
    FAILED("failed");

    companion object {
        fun decode(value: String): SealRequestOutcome = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class SealResponsePayloadFormat(override val token: String) : OperationalStorageToken {
    BODY("body"),
    PREPARED_ENVELOPE("prepared_envelope");

    companion object {
        fun decode(value: String): SealResponsePayloadFormat = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class ScreenReplayKind(override val token: String) : OperationalStorageToken {
    SESSION("session"),
    ROUTING_TOKEN("routing_token");

    companion object {
        fun decode(value: String): ScreenReplayKind = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class ScreenReplayHealth(override val token: String) : OperationalStorageToken {
    HEALTHY("healthy"),
    QUARANTINED("quarantined");

    companion object {
        fun decode(value: String): ScreenReplayHealth = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal enum class ScreenCodecToken(override val token: String) : OperationalStorageToken {
    H264("h264"),
    H265("h265"),
    AV1("av1");

    companion object {
        fun decode(value: String): ScreenCodecToken = decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal object OperationalSecurityTypeConverters {
    @ColumnTypeConverter fun encode(value: SealEnrollmentState) = value.token
    @ColumnTypeConverter fun decodeSealEnrollmentState(value: String) = SealEnrollmentState.decode(value)
    @ColumnTypeConverter fun encode(value: SealObjectKind) = value.token
    @ColumnTypeConverter fun decodeSealObjectKind(value: String) = SealObjectKind.decode(value)
    @ColumnTypeConverter fun encode(value: SealRequestState) = value.token
    @ColumnTypeConverter fun decodeSealRequestState(value: String) = SealRequestState.decode(value)
    @ColumnTypeConverter fun encode(value: SealRequestOutcome) = value.token
    @ColumnTypeConverter fun decodeSealRequestOutcome(value: String) = SealRequestOutcome.decode(value)
    @ColumnTypeConverter fun encode(value: SealResponsePayloadFormat) = value.token
    @ColumnTypeConverter fun decodeSealResponsePayloadFormat(value: String) = SealResponsePayloadFormat.decode(value)
    @ColumnTypeConverter fun encode(value: ScreenReplayKind) = value.token
    @ColumnTypeConverter fun decodeScreenReplayKind(value: String) = ScreenReplayKind.decode(value)
    @ColumnTypeConverter fun encode(value: ScreenReplayHealth) = value.token
    @ColumnTypeConverter fun decodeScreenReplayHealth(value: String) = ScreenReplayHealth.decode(value)
    @ColumnTypeConverter fun encode(value: ScreenCodecToken) = value.token
    @ColumnTypeConverter fun decodeScreenCodecToken(value: String) = ScreenCodecToken.decode(value)
}
