package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnTypeConverter

internal enum class SshStorageKind(override val token: String) : OperationalStorageToken {
    DIRECT("direct"), WRAPPED("wrapped");
    companion object { fun decode(value: String): SshStorageKind = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshKeyAlgorithmToken(override val token: String) : OperationalStorageToken {
    SSH_ED25519("ssh_ed25519"), SSH_RSA("ssh_rsa"), ECDSA_NISTP256("ecdsa_nistp256");
    companion object { fun decode(value: String): SshKeyAlgorithmToken = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshKeyOriginToken(override val token: String) : OperationalStorageToken {
    GENERATED("generated"), SAF_IMPORT("saf_import"), DATA_SYNC_FILE("data_sync_file"), AGENT_ADD("agent_add");
    companion object { fun decode(value: String): SshKeyOriginToken = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshApprovalPolicyToken(override val token: String) : OperationalStorageToken {
    ALWAYS_ASK("always_ask"), ALLOW_REMEMBER("allow_remember");
    companion object { fun decode(value: String): SshApprovalPolicyToken = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshSecurityLevelToken(override val token: String) : OperationalStorageToken {
    TRUSTED_ENVIRONMENT("trusted_environment"),
    STRONGBOX("strongbox"),
    SOFTWARE("software"),
    UNKNOWN_SECURE("unknown_secure"),
    UNKNOWN("unknown");
    companion object { fun decode(value: String): SshSecurityLevelToken = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshUserVerificationToken(override val token: String) : OperationalStorageToken {
    NONE("none"), PER_USE("per_use");
    companion object { fun decode(value: String): SshUserVerificationToken = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshExportBackendToken(override val token: String) : OperationalStorageToken {
    BEST_AVAILABLE("best_available"), TEE_ONLY("tee_only");
    companion object { fun decode(value: String): SshExportBackendToken = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshExportAuthenticationToken(override val token: String) : OperationalStorageToken {
    STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL_PER_USE("strong_biometric_or_device_credential_per_use");
    companion object { fun decode(value: String): SshExportAuthenticationToken = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshKeyLifecycleState(override val token: String) : OperationalStorageToken {
    PROVISIONING("provisioning"), DELETING("deleting");
    companion object { fun decode(value: String): SshKeyLifecycleState = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshLifecycleCandidatePurpose(override val token: String) : OperationalStorageToken {
    OPERATIONAL("operational"), EXPORT("export");
    companion object { fun decode(value: String): SshLifecycleCandidatePurpose = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshResetState(override val token: String) : OperationalStorageToken {
    JOURNALED("journaled"), DELETING_ALIASES("deleting_aliases"), FINALIZING("finalizing"), BLOCKED("blocked");
    companion object { fun decode(value: String): SshResetState = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshResetAliasKind(override val token: String) : OperationalStorageToken {
    OPERATIONAL("operational"), EXPORT_COPY("export_copy"), AUDIT_WRAPPING("audit_wrapping");
    companion object { fun decode(value: String): SshResetAliasKind = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshResetAliasState(override val token: String) : OperationalStorageToken {
    PENDING("pending"), DELETED("deleted"), NOT_FOUND("not_found"), RETRY_WAIT("retry_wait"), BLOCKED("blocked");
    companion object { fun decode(value: String): SshResetAliasState = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshProviderRequestKind(override val token: String) : OperationalStorageToken {
    SIGN("sign"), IMPORT("import");
    companion object { fun decode(value: String): SshProviderRequestKind = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshProviderRequestState(override val token: String) : OperationalStorageToken {
    PENDING_REVIEW("pending_review"), RESPONSE_QUEUED("response_queued"), COMPLETED("completed"), SENT("sent"),
    CANCELLED("cancelled"), EXPIRED("expired");
    companion object { fun decode(value: String): SshProviderRequestState = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshProviderRequestOutcome(override val token: String) : OperationalStorageToken {
    SIGNED("signed"), IMPORTED("imported"), ALREADY_PRESENT("already_present"), REJECTED("rejected"),
    FAILED("failed"), CANCELLED("cancelled"), EXPIRED("expired");
    companion object { fun decode(value: String): SshProviderRequestOutcome = decodeOperationalToken(value, entries.toTypedArray()) }
}

internal enum class SshProviderResponsePayloadFormat(override val token: String) : OperationalStorageToken {
    BODY("body"), PREPARED_ENVELOPE("prepared_envelope");
    companion object {
        fun decode(value: String): SshProviderResponsePayloadFormat =
            decodeOperationalToken(value, entries.toTypedArray())
    }
}

internal object OperationalSshTypeConverters {
    @ColumnTypeConverter fun encode(value: SshStorageKind) = value.token
    @ColumnTypeConverter fun decodeSshStorageKind(value: String) = SshStorageKind.decode(value)
    @ColumnTypeConverter fun encode(value: SshKeyAlgorithmToken) = value.token
    @ColumnTypeConverter fun decodeSshKeyAlgorithmToken(value: String) = SshKeyAlgorithmToken.decode(value)
    @ColumnTypeConverter fun encode(value: SshKeyOriginToken) = value.token
    @ColumnTypeConverter fun decodeSshKeyOriginToken(value: String) = SshKeyOriginToken.decode(value)
    @ColumnTypeConverter fun encode(value: SshApprovalPolicyToken) = value.token
    @ColumnTypeConverter fun decodeSshApprovalPolicyToken(value: String) = SshApprovalPolicyToken.decode(value)
    @ColumnTypeConverter fun encode(value: SshSecurityLevelToken) = value.token
    @ColumnTypeConverter fun decodeSshSecurityLevelToken(value: String) = SshSecurityLevelToken.decode(value)
    @ColumnTypeConverter fun encode(value: SshUserVerificationToken) = value.token
    @ColumnTypeConverter fun decodeSshUserVerificationToken(value: String) = SshUserVerificationToken.decode(value)
    @ColumnTypeConverter fun encode(value: SshExportBackendToken) = value.token
    @ColumnTypeConverter fun decodeSshExportBackendToken(value: String) = SshExportBackendToken.decode(value)
    @ColumnTypeConverter fun encode(value: SshExportAuthenticationToken) = value.token
    @ColumnTypeConverter fun decodeSshExportAuthenticationToken(value: String) = SshExportAuthenticationToken.decode(value)
    @ColumnTypeConverter fun encode(value: SshKeyLifecycleState) = value.token
    @ColumnTypeConverter fun decodeSshKeyLifecycleState(value: String) = SshKeyLifecycleState.decode(value)
    @ColumnTypeConverter fun encode(value: SshLifecycleCandidatePurpose) = value.token
    @ColumnTypeConverter fun decodeSshLifecycleCandidatePurpose(value: String) = SshLifecycleCandidatePurpose.decode(value)
    @ColumnTypeConverter fun encode(value: SshResetState) = value.token
    @ColumnTypeConverter fun decodeSshResetState(value: String) = SshResetState.decode(value)
    @ColumnTypeConverter fun encode(value: SshResetAliasKind) = value.token
    @ColumnTypeConverter fun decodeSshResetAliasKind(value: String) = SshResetAliasKind.decode(value)
    @ColumnTypeConverter fun encode(value: SshResetAliasState) = value.token
    @ColumnTypeConverter fun decodeSshResetAliasState(value: String) = SshResetAliasState.decode(value)
    @ColumnTypeConverter fun encode(value: SshProviderRequestKind) = value.token
    @ColumnTypeConverter fun decodeSshProviderRequestKind(value: String) = SshProviderRequestKind.decode(value)
    @ColumnTypeConverter fun encode(value: SshProviderRequestState) = value.token
    @ColumnTypeConverter fun decodeSshProviderRequestState(value: String) = SshProviderRequestState.decode(value)
    @ColumnTypeConverter fun encode(value: SshProviderRequestOutcome) = value.token
    @ColumnTypeConverter fun decodeSshProviderRequestOutcome(value: String) = SshProviderRequestOutcome.decode(value)
    @ColumnTypeConverter fun encode(value: SshProviderResponsePayloadFormat) = value.token
    @ColumnTypeConverter fun decodeSshProviderResponsePayloadFormat(value: String) =
        SshProviderResponsePayloadFormat.decode(value)
}
