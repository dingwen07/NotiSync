import Foundation
import NotiSyncProtocol

// Native Swift adapters for the shared SSH Agent protocol. Keep these names and fields aligned with
// `protocol/SshAgentMessages.kt`; ProtocolCodec is the only wire encoder/decoder.

nonisolated enum SshAgentSyncKind: String, Codable, Sendable {
    case KEYS_REQUEST, KEYS_SNAPSHOT, SIGN_REQUEST, SIGN_RESULT, SIGN_REQUEST_CANCELLED
    case IMPORT_REQUEST, IMPORT_RESULT, FORGET_AUTHORIZATION, FORGET_RESULT
}

nonisolated enum SshKeyAlgorithm: String, Codable, Sendable {
    case SSH_ED25519, SSH_RSA, ECDSA_NISTP256, WEBAUTHN_SK_ECDSA_NISTP256
}

nonisolated enum SshSignatureAlgorithm: String, Codable, Sendable {
    case SSH_ED25519, RSA_SHA2_256, RSA_SHA2_512, ECDSA_NISTP256
    case WEBAUTHN_SK_ECDSA_NISTP256, RSA_SHA1_LEGACY
}

nonisolated enum SshKeyOrigin: String, Codable, Sendable {
    case GENERATED, SAF_IMPORT, DATA_SYNC_FILE, AGENT_ADD, WEBAUTHN_CREATED, WEBAUTHN_RECOVERED
}

nonisolated enum SshOperationalKeyProvider: String, Codable, Sendable {
    case ANDROID_KEYSTORE_PRIVATE_KEY, ANDROID_KEYSTORE_AES_WRAPPED, CREDENTIAL_MANAGER_WEBAUTHN
    case APPLE_KEYCHAIN, APPLE_AUTHENTICATION_SERVICES_WEBAUTHN
}

nonisolated enum SshStorageSecurityLevel: String, Codable, Sendable {
    case STRONGBOX, TRUSTED_ENVIRONMENT, CREDENTIAL_PROVIDER, KEYCHAIN
}

nonisolated enum SshExportCopyBackendPolicy: String, Codable, Sendable { case BEST_AVAILABLE, TEE_ONLY }
nonisolated enum SshExportCopyAuthentication: String, Codable, Sendable {
    case STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL_PER_USE
}
nonisolated enum SshApprovalPolicy: String, Codable, Sendable { case ALWAYS_ASK, ALLOW_REMEMBER }
nonisolated enum SshUserVerificationPolicy: String, Codable, Sendable { case NONE, PER_USE }
nonisolated enum SshRememberScope: String, Codable, Sendable { case PEER, PEER_HOST_KEY, APPLICATION_PROCESS }
nonisolated enum SshProviderHealth: String, Codable, Sendable { case HEALTHY, DEGRADED, DISABLED }
nonisolated enum SshDestinationProvenance: String, Codable, Sendable {
    case VERIFIED_SESSION_BIND, SIGNED_USERAUTH, KNOWN_HOSTS_MATCH, PROCESS_HINT, UNKNOWN
}
nonisolated enum SshConnectionDirection: String, Codable, Sendable { case DIRECT, FORWARDED, UNKNOWN }
nonisolated enum SshHostAliasSource: String, Codable, Sendable {
    case KNOWN_HOSTS_PLAIN, KNOWN_HOSTS_HASH_CONFIRMED, PROCESS_ARGUMENT
}
nonisolated enum SshRememberDisposition: String, Codable, Sendable {
    case NONE, MATCHED_PEER, MATCHED_PEER_HOST_KEY, MATCHED_APPLICATION_PROCESS
    case CREATED_PEER, CREATED_PEER_HOST_KEY, CREATED_APPLICATION_PROCESS, NOT_ALLOWED_FOR_KEY
}
nonisolated enum SshSignResultKind: String, Codable, Sendable { case SIGNED, REJECTED_BY_USER, PROVIDER_FAILURE }
nonisolated enum SshUserRejectionReason: String, Codable, Sendable { case USER_TAPPED_REJECT }
nonisolated enum SshProviderFailureCode: String, Codable, Sendable {
    case NOT_OWNER, KEY_NOT_FOUND, UNSUPPORTED_ALGORITHM, UNSUPPORTED_FLAGS, KEY_INVALIDATED
    case USER_VERIFICATION_CANCELLED, USER_VERIFICATION_LOCKOUT, REQUEST_EXPIRED, PROVIDER_BUSY
    case INTERNAL_FAILURE
}
nonisolated enum SshSignCancellationReason: String, Codable, Sendable {
    case SIGNED_ELSEWHERE, REJECTED_ELSEWHERE, REQUEST_TIMEOUT, AGENT_LOCKED
    case CALLER_DISCONNECTED, AGENT_SHUTDOWN
}
nonisolated enum SshImportSourceType: String, Codable, Sendable { case PRIVATE_KEY_FILE, AGENT_IDENTITY }
nonisolated enum SshImportResultKind: String, Codable, Sendable {
    case IMPORTED, ALREADY_PRESENT, USER_DECLINED, UNSUPPORTED, EXPIRED, FAILED
}
nonisolated enum SshForgetResultKind: String, Codable, Sendable { case APPLIED, EXPIRED, FAILED }

nonisolated enum DesktopProcessContextSource: String, Codable, Sendable {
    case PEER_CREDENTIALS, NAMED_PIPE_CLIENT_PID, CURRENT_PROCESS, BRIDGE_REPORTED, UNAVAILABLE
}

nonisolated struct DesktopProcessIdentity: Codable, Sendable {
    var pid: Int64
    var executablePath: String?
    var displayName: String?
}

nonisolated struct DesktopProcessContext: Codable, Sendable {
    var source: DesktopProcessContextSource
    var processLineage: [DesktopProcessIdentity] = []
    var bootId: String?
}

nonisolated struct SshRememberedNamespace: Codable, Sendable {
    var requesterClientId: String
    var authorizationGeneration: String
    var authorizationEpoch: Int64
    var scopes: [SshRememberScope]
}

nonisolated struct SshOperationalKeyProtection: Codable, Sendable {
    var provider: SshOperationalKeyProvider
    var securityLevel: SshStorageSecurityLevel
    var userVerificationPolicy: SshUserVerificationPolicy
    var strongBoxAttempted: Bool = false
    var strongBoxFallback: Bool = false
}

nonisolated struct SshExportCopyProtection: Codable, Sendable {
    var securityLevel: SshStorageSecurityLevel
    var backendPolicy: SshExportCopyBackendPolicy
    var authentication: SshExportCopyAuthentication
    var strongBoxAttempted: Bool
    var strongBoxFallback: Bool
}

nonisolated struct SshWebAuthnCredentialProtection: Codable, Sendable {
    var rpId: String
    var backupEligible: Bool
    var backupState: Bool
}

nonisolated struct SshKeyDescriptor: Codable, Sendable {
    var providerKeyId: String
    var publicKeyBlob: Data
    var publicKeyBlobSha256: Data
    var algorithm: SshKeyAlgorithm
    var displayName: String
    var origin: SshKeyOrigin
    var operationalKey: SshOperationalKeyProtection
    var exportCopy: SshExportCopyProtection?
    var approvalPolicy: SshApprovalPolicy
    var rememberedNamespaces: [SshRememberedNamespace] = []
    var createdAt: Int64
    var webAuthn: SshWebAuthnCredentialProtection?
}

nonisolated struct SshKeysRequest: Codable, Sendable {
    var requestId: String
    var requesterClientId: String
    var requestedAt: Int64
    var expiresAt: Int64
    var startup: Bool
    var targetProviderClientIds: [String]
    var requesterInventoryNonce: Data
}

nonisolated struct SshKeysSnapshot: Codable, Sendable {
    var providerClientId: String
    var inventoryGeneration: String
    var revision: Int64
    var generatedAt: Int64
    var respondingToRequestId: String?
    var keys: [SshKeyDescriptor]
    var providerHealth: SshProviderHealth
}

nonisolated struct SshHostAlias: Codable, Sendable {
    var value: String
    var source: SshHostAliasSource
}

nonisolated struct SshVerifiedBinding: Codable, Sendable {
    var hostKeyBlobSha256: Data
    var forwarded: Bool
}

nonisolated struct SshDestinationContext: Codable, Sendable {
    var provenance: SshDestinationProvenance
    var connectionDirection: SshConnectionDirection
    var username: String?
    var service: String?
    var authenticationMethod: String?
    var sessionIdSha256: Data?
    var serverHostKeyBlob: Data?
    var serverHostKeyBlobSha256: Data?
    var hostAliases: [SshHostAlias] = []
    var bindingChain: [SshVerifiedBinding] = []
}

nonisolated struct SshSignRequest: Codable, Sendable {
    var requestId: String
    var requesterClientId: String
    var requestedAt: Int64
    var expiresAt: Int64
    var publicKeyBlob: Data
    var data: Data
    var flags: Int64
    var requestedSignatureAlgorithm: SshSignatureAlgorithm
    var eligibleProviderClientIds: [String]
    var authorizationGeneration: String
    var authorizationEpoch: Int64
    var processContext: DesktopProcessContext
    var destinationContext: SshDestinationContext
    var connectionId: String
    var confirmationRequired: Bool = false
}

nonisolated struct SshSignatureResult: Codable, Sendable {
    var signatureBlob: Data
    var rememberDisposition: SshRememberDisposition
    var authorizationGeneration: String
    var authorizationEpoch: Int64
}

nonisolated struct SshUserRejection: Codable, Sendable { var reason: SshUserRejectionReason }

nonisolated struct SshProviderFailure: Codable, Sendable {
    var code: SshProviderFailureCode
    var retryable: Bool = false
    var message: String?
}

nonisolated struct SshSignResult: Codable, Sendable {
    var requestId: String
    var requesterClientId: String
    var publicKeyBlobSha256: Data
    var kind: SshSignResultKind
    var resultAt: Int64
    var providerClientId: String
    var signature: SshSignatureResult?
    var rejection: SshUserRejection?
    var failure: SshProviderFailure?
}

nonisolated struct SshSignRequestCancelled: Codable, Sendable {
    var requestId: String
    var requesterClientId: String
    var cancelledAt: Int64
    var reason: SshSignCancellationReason
    var targetProviderClientIds: [String]
}

nonisolated struct SshImportConstraints: Codable, Sendable {
    var lifetimeSeconds: Int64?
    var confirmationRequired: Bool
}

nonisolated struct SshImportRequest: Codable, Sendable {
    var requestId: String
    var requesterClientId: String
    var requestedAt: Int64
    var expiresAt: Int64
    var sourceType: SshImportSourceType
    var fileBytes: Data?
    var agentIdentity: Data?
    var constraints: SshImportConstraints?
    var suggestedName: String?
}

nonisolated struct SshImportResult: Codable, Sendable {
    var requestId: String
    var requesterClientId: String
    var providerClientId: String
    var resultAt: Int64
    var kind: SshImportResultKind
    var providerKeyId: String?
    var publicKeyBlob: Data?
    var message: String?
}

nonisolated struct SshForgetAuthorization: Codable, Sendable {
    var requestId: String
    var requesterClientId: String
    var authorizationGeneration: String
    var invalidatedThroughEpoch: Int64
    var requestedAt: Int64
    var expiresAt: Int64
    var targetProviderClientIds: [String]
}

nonisolated struct SshForgetResult: Codable, Sendable {
    var requestId: String
    var requesterClientId: String
    var providerClientId: String
    var resultAt: Int64
    var kind: SshForgetResultKind
    var invalidatedThroughEpoch: Int64
}

/// Flat discriminated union. Exactly one payload must be populated and match `kind`.
nonisolated struct SshAgentSync: Codable, Sendable {
    var protocolVersion: Int = 1
    var kind: SshAgentSyncKind
    var keysRequest: SshKeysRequest?
    var keysSnapshot: SshKeysSnapshot?
    var signRequest: SshSignRequest?
    var signResult: SshSignResult?
    var signRequestCancelled: SshSignRequestCancelled?
    var importRequest: SshImportRequest?
    var importResult: SshImportResult?
    var forgetAuthorization: SshForgetAuthorization?
    var forgetResult: SshForgetResult?

    init(
        protocolVersion: Int = 1,
        kind: SshAgentSyncKind,
        keysRequest: SshKeysRequest? = nil,
        keysSnapshot: SshKeysSnapshot? = nil,
        signRequest: SshSignRequest? = nil,
        signResult: SshSignResult? = nil,
        signRequestCancelled: SshSignRequestCancelled? = nil,
        importRequest: SshImportRequest? = nil,
        importResult: SshImportResult? = nil,
        forgetAuthorization: SshForgetAuthorization? = nil,
        forgetResult: SshForgetResult? = nil
    ) {
        self.protocolVersion = protocolVersion
        self.kind = kind
        self.keysRequest = keysRequest
        self.keysSnapshot = keysSnapshot
        self.signRequest = signRequest
        self.signResult = signResult
        self.signRequestCancelled = signRequestCancelled
        self.importRequest = importRequest
        self.importResult = importResult
        self.forgetAuthorization = forgetAuthorization
        self.forgetResult = forgetResult
    }
}

// MARK: - KMP bridge

nonisolated extension KMPProtocolBridge {
    private static func strictKmpEnum<Native: RawRepresentable, KMP>(
        _ value: Native,
        entries: [KMP],
        field: String,
        name: (KMP) -> String
    ) -> KMP where Native.RawValue == String {
        guard let result = entries.first(where: { name($0) == value.rawValue }) else {
            preconditionFailure("KMP protocol is missing native SSH enum \(field)=\(value.rawValue)")
        }
        return result
    }

    private static func strictNativeEnum<Native: RawRepresentable>(
        _ name: String,
        as _: Native.Type,
        field: String
    ) throws -> Native where Native.RawValue == String {
        guard let result = Native(rawValue: name) else {
            throw CodecError.typeMismatch("\(field)=\(name)")
        }
        return result
    }

    private static func sshValidationError(_ value: NotiSyncProtocol.SshAgentSync) -> String? {
        value.validationError(sha256: { bytes in
            kotlinBytes(NSHash.sha256(data(bytes)))
        })
    }

    // MARK: Swift -> KMP

    static func toKmp(_ value: DesktopProcessIdentity) -> NotiSyncProtocol.DesktopProcessIdentity {
        NotiSyncProtocol.DesktopProcessIdentity(
            pid: value.pid,
            executablePath: value.executablePath,
            displayName: value.displayName
        )
    }

    static func toKmp(_ value: DesktopProcessContext) -> NotiSyncProtocol.DesktopProcessContext {
        NotiSyncProtocol.DesktopProcessContext(
            source: strictKmpEnum(
                value.source,
                entries: NotiSyncProtocol.DesktopProcessContextSource.entries,
                field: "processContext.source",
                name: { $0.name }
            ),
            processLineage: value.processLineage.map(toKmp),
            bootId: value.bootId
        )
    }

    static func toKmp(_ value: SshRememberedNamespace) -> NotiSyncProtocol.SshRememberedNamespace {
        NotiSyncProtocol.SshRememberedNamespace(
            requesterClientId: clientId(value.requesterClientId),
            authorizationGeneration: value.authorizationGeneration,
            authorizationEpoch: value.authorizationEpoch,
            scopes: value.scopes.map {
                strictKmpEnum(
                    $0,
                    entries: NotiSyncProtocol.SshRememberScope.entries,
                    field: "rememberedNamespace.scope",
                    name: { $0.name }
                )
            }
        )
    }

    static func toKmp(_ value: SshOperationalKeyProtection) -> NotiSyncProtocol.SshOperationalKeyProtection {
        NotiSyncProtocol.SshOperationalKeyProtection(
            provider: strictKmpEnum(
                value.provider,
                entries: NotiSyncProtocol.SshOperationalKeyProvider.entries,
                field: "operationalKey.provider",
                name: { $0.name }
            ),
            securityLevel: strictKmpEnum(
                value.securityLevel,
                entries: NotiSyncProtocol.SshStorageSecurityLevel.entries,
                field: "operationalKey.securityLevel",
                name: { $0.name }
            ),
            userVerificationPolicy: strictKmpEnum(
                value.userVerificationPolicy,
                entries: NotiSyncProtocol.SshUserVerificationPolicy.entries,
                field: "operationalKey.userVerificationPolicy",
                name: { $0.name }
            ),
            strongBoxAttempted: value.strongBoxAttempted,
            strongBoxFallback: value.strongBoxFallback
        )
    }

    static func toKmp(_ value: SshExportCopyProtection) -> NotiSyncProtocol.SshExportCopyProtection {
        NotiSyncProtocol.SshExportCopyProtection(
            securityLevel: strictKmpEnum(
                value.securityLevel,
                entries: NotiSyncProtocol.SshStorageSecurityLevel.entries,
                field: "exportCopy.securityLevel",
                name: { $0.name }
            ),
            backendPolicy: strictKmpEnum(
                value.backendPolicy,
                entries: NotiSyncProtocol.SshExportCopyBackendPolicy.entries,
                field: "exportCopy.backendPolicy",
                name: { $0.name }
            ),
            authentication: strictKmpEnum(
                value.authentication,
                entries: NotiSyncProtocol.SshExportCopyAuthentication.entries,
                field: "exportCopy.authentication",
                name: { $0.name }
            ),
            strongBoxAttempted: value.strongBoxAttempted,
            strongBoxFallback: value.strongBoxFallback
        )
    }

    static func toKmp(_ value: SshWebAuthnCredentialProtection) -> NotiSyncProtocol.SshWebAuthnCredentialProtection {
        NotiSyncProtocol.SshWebAuthnCredentialProtection(
            rpId: value.rpId,
            backupEligible: value.backupEligible,
            backupState: value.backupState
        )
    }

    static func toKmp(_ value: SshKeyDescriptor) -> NotiSyncProtocol.SshKeyDescriptor {
        NotiSyncProtocol.SshKeyDescriptor(
            providerKeyId: value.providerKeyId,
            publicKeyBlob: kotlinBytes(value.publicKeyBlob),
            publicKeyBlobSha256: kotlinBytes(value.publicKeyBlobSha256),
            algorithm: strictKmpEnum(
                value.algorithm,
                entries: NotiSyncProtocol.SshKeyAlgorithm.entries,
                field: "key.algorithm",
                name: { $0.name }
            ),
            displayName: value.displayName,
            origin: strictKmpEnum(
                value.origin,
                entries: NotiSyncProtocol.SshKeyOrigin.entries,
                field: "key.origin",
                name: { $0.name }
            ),
            operationalKey: toKmp(value.operationalKey),
            exportCopy: value.exportCopy.map(toKmp),
            approvalPolicy: strictKmpEnum(
                value.approvalPolicy,
                entries: NotiSyncProtocol.SshApprovalPolicy.entries,
                field: "key.approvalPolicy",
                name: { $0.name }
            ),
            rememberedNamespaces: value.rememberedNamespaces.map(toKmp),
            createdAt: value.createdAt,
            webAuthn: value.webAuthn.map(toKmp)
        )
    }

    static func toKmp(_ value: SshKeysRequest) -> NotiSyncProtocol.SshKeysRequest {
        NotiSyncProtocol.SshKeysRequest(
            requestId: value.requestId,
            requesterClientId: clientId(value.requesterClientId),
            requestedAt: value.requestedAt,
            expiresAt: value.expiresAt,
            startup: value.startup,
            targetProviderClientIds: clientIds(value.targetProviderClientIds),
            requesterInventoryNonce: kotlinBytes(value.requesterInventoryNonce)
        )
    }

    static func toKmp(_ value: SshKeysSnapshot) -> NotiSyncProtocol.SshKeysSnapshot {
        NotiSyncProtocol.SshKeysSnapshot(
            providerClientId: clientId(value.providerClientId),
            inventoryGeneration: value.inventoryGeneration,
            revision: value.revision,
            generatedAt: value.generatedAt,
            respondingToRequestId: value.respondingToRequestId,
            keys: value.keys.map(toKmp),
            providerHealth: strictKmpEnum(
                value.providerHealth,
                entries: NotiSyncProtocol.SshProviderHealth.entries,
                field: "snapshot.providerHealth",
                name: { $0.name }
            )
        )
    }

    static func toKmp(_ value: SshHostAlias) -> NotiSyncProtocol.SshHostAlias {
        NotiSyncProtocol.SshHostAlias(
            value: value.value,
            source: strictKmpEnum(
                value.source,
                entries: NotiSyncProtocol.SshHostAliasSource.entries,
                field: "hostAlias.source",
                name: { $0.name }
            )
        )
    }

    static func toKmp(_ value: SshVerifiedBinding) -> NotiSyncProtocol.SshVerifiedBinding {
        NotiSyncProtocol.SshVerifiedBinding(
            hostKeyBlobSha256: kotlinBytes(value.hostKeyBlobSha256),
            forwarded: value.forwarded
        )
    }

    static func toKmp(_ value: SshDestinationContext) -> NotiSyncProtocol.SshDestinationContext {
        NotiSyncProtocol.SshDestinationContext(
            provenance: strictKmpEnum(
                value.provenance,
                entries: NotiSyncProtocol.SshDestinationProvenance.entries,
                field: "destination.provenance",
                name: { $0.name }
            ),
            connectionDirection: strictKmpEnum(
                value.connectionDirection,
                entries: NotiSyncProtocol.SshConnectionDirection.entries,
                field: "destination.connectionDirection",
                name: { $0.name }
            ),
            username: value.username,
            service: value.service,
            authenticationMethod: value.authenticationMethod,
            sessionIdSha256: value.sessionIdSha256.map(kotlinBytes),
            serverHostKeyBlob: value.serverHostKeyBlob.map(kotlinBytes),
            serverHostKeyBlobSha256: value.serverHostKeyBlobSha256.map(kotlinBytes),
            hostAliases: value.hostAliases.map(toKmp),
            bindingChain: value.bindingChain.map(toKmp)
        )
    }

    static func toKmp(_ value: SshSignRequest) -> NotiSyncProtocol.SshSignRequest {
        NotiSyncProtocol.SshSignRequest(
            requestId: value.requestId,
            requesterClientId: clientId(value.requesterClientId),
            requestedAt: value.requestedAt,
            expiresAt: value.expiresAt,
            publicKeyBlob: kotlinBytes(value.publicKeyBlob),
            data: kotlinBytes(value.data),
            flags: value.flags,
            requestedSignatureAlgorithm: strictKmpEnum(
                value.requestedSignatureAlgorithm,
                entries: NotiSyncProtocol.SshSignatureAlgorithm.entries,
                field: "signRequest.requestedSignatureAlgorithm",
                name: { $0.name }
            ),
            eligibleProviderClientIds: clientIds(value.eligibleProviderClientIds),
            authorizationGeneration: value.authorizationGeneration,
            authorizationEpoch: value.authorizationEpoch,
            processContext: toKmp(value.processContext),
            destinationContext: toKmp(value.destinationContext),
            connectionId: value.connectionId,
            confirmationRequired: value.confirmationRequired
        )
    }

    static func toKmp(_ value: SshSignatureResult) -> NotiSyncProtocol.SshSignatureResult {
        NotiSyncProtocol.SshSignatureResult(
            signatureBlob: kotlinBytes(value.signatureBlob),
            rememberDisposition: strictKmpEnum(
                value.rememberDisposition,
                entries: NotiSyncProtocol.SshRememberDisposition.entries,
                field: "signature.rememberDisposition",
                name: { $0.name }
            ),
            authorizationGeneration: value.authorizationGeneration,
            authorizationEpoch: value.authorizationEpoch
        )
    }

    static func toKmp(_ value: SshUserRejection) -> NotiSyncProtocol.SshUserRejection {
        NotiSyncProtocol.SshUserRejection(
            reason: strictKmpEnum(
                value.reason,
                entries: NotiSyncProtocol.SshUserRejectionReason.entries,
                field: "rejection.reason",
                name: { $0.name }
            )
        )
    }

    static func toKmp(_ value: SshProviderFailure) -> NotiSyncProtocol.SshProviderFailure {
        NotiSyncProtocol.SshProviderFailure(
            code: strictKmpEnum(
                value.code,
                entries: NotiSyncProtocol.SshProviderFailureCode.entries,
                field: "failure.code",
                name: { $0.name }
            ),
            retryable: value.retryable,
            message: value.message
        )
    }

    static func toKmp(_ value: SshSignResult) -> NotiSyncProtocol.SshSignResult {
        NotiSyncProtocol.SshSignResult(
            requestId: value.requestId,
            requesterClientId: clientId(value.requesterClientId),
            publicKeyBlobSha256: kotlinBytes(value.publicKeyBlobSha256),
            kind: strictKmpEnum(
                value.kind,
                entries: NotiSyncProtocol.SshSignResultKind.entries,
                field: "signResult.kind",
                name: { $0.name }
            ),
            resultAt: value.resultAt,
            providerClientId: clientId(value.providerClientId),
            signature: value.signature.map(toKmp),
            rejection: value.rejection.map(toKmp),
            failure: value.failure.map(toKmp)
        )
    }

    static func toKmp(_ value: SshSignRequestCancelled) -> NotiSyncProtocol.SshSignRequestCancelled {
        NotiSyncProtocol.SshSignRequestCancelled(
            requestId: value.requestId,
            requesterClientId: clientId(value.requesterClientId),
            cancelledAt: value.cancelledAt,
            reason: strictKmpEnum(
                value.reason,
                entries: NotiSyncProtocol.SshSignCancellationReason.entries,
                field: "signCancellation.reason",
                name: { $0.name }
            ),
            targetProviderClientIds: clientIds(value.targetProviderClientIds)
        )
    }

    static func toKmp(_ value: SshImportConstraints) -> NotiSyncProtocol.SshImportConstraints {
        NotiSyncProtocol.SshImportConstraints(
            lifetimeSeconds: value.lifetimeSeconds.map { KotlinLong(longLong: $0) },
            confirmationRequired: value.confirmationRequired
        )
    }

    static func toKmp(_ value: SshImportRequest) -> NotiSyncProtocol.SshImportRequest {
        NotiSyncProtocol.SshImportRequest(
            requestId: value.requestId,
            requesterClientId: clientId(value.requesterClientId),
            requestedAt: value.requestedAt,
            expiresAt: value.expiresAt,
            sourceType: strictKmpEnum(
                value.sourceType,
                entries: NotiSyncProtocol.SshImportSourceType.entries,
                field: "importRequest.sourceType",
                name: { $0.name }
            ),
            fileBytes: value.fileBytes.map(kotlinBytes),
            agentIdentity: value.agentIdentity.map(kotlinBytes),
            constraints: value.constraints.map(toKmp),
            suggestedName: value.suggestedName
        )
    }

    static func toKmp(_ value: SshImportResult) -> NotiSyncProtocol.SshImportResult {
        NotiSyncProtocol.SshImportResult(
            requestId: value.requestId,
            requesterClientId: clientId(value.requesterClientId),
            providerClientId: clientId(value.providerClientId),
            resultAt: value.resultAt,
            kind: strictKmpEnum(
                value.kind,
                entries: NotiSyncProtocol.SshImportResultKind.entries,
                field: "importResult.kind",
                name: { $0.name }
            ),
            providerKeyId: value.providerKeyId,
            publicKeyBlob: value.publicKeyBlob.map(kotlinBytes),
            message: value.message
        )
    }

    static func toKmp(_ value: SshForgetAuthorization) -> NotiSyncProtocol.SshForgetAuthorization {
        NotiSyncProtocol.SshForgetAuthorization(
            requestId: value.requestId,
            requesterClientId: clientId(value.requesterClientId),
            authorizationGeneration: value.authorizationGeneration,
            invalidatedThroughEpoch: value.invalidatedThroughEpoch,
            requestedAt: value.requestedAt,
            expiresAt: value.expiresAt,
            targetProviderClientIds: clientIds(value.targetProviderClientIds)
        )
    }

    static func toKmp(_ value: SshForgetResult) -> NotiSyncProtocol.SshForgetResult {
        NotiSyncProtocol.SshForgetResult(
            requestId: value.requestId,
            requesterClientId: clientId(value.requesterClientId),
            providerClientId: clientId(value.providerClientId),
            resultAt: value.resultAt,
            kind: strictKmpEnum(
                value.kind,
                entries: NotiSyncProtocol.SshForgetResultKind.entries,
                field: "forgetResult.kind",
                name: { $0.name }
            ),
            invalidatedThroughEpoch: value.invalidatedThroughEpoch
        )
    }

    static func toKmp(_ value: SshAgentSync) -> NotiSyncProtocol.SshAgentSync {
        let result = NotiSyncProtocol.SshAgentSync(
            protocolVersion: Int32(value.protocolVersion),
            kind: strictKmpEnum(
                value.kind,
                entries: NotiSyncProtocol.SshAgentSyncKind.entries,
                field: "sshAgent.kind",
                name: { $0.name }
            ),
            keysRequest: value.keysRequest.map(toKmp),
            keysSnapshot: value.keysSnapshot.map(toKmp),
            signRequest: value.signRequest.map(toKmp),
            signResult: value.signResult.map(toKmp),
            signRequestCancelled: value.signRequestCancelled.map(toKmp),
            importRequest: value.importRequest.map(toKmp),
            importResult: value.importResult.map(toKmp),
            forgetAuthorization: value.forgetAuthorization.map(toKmp),
            forgetResult: value.forgetResult.map(toKmp)
        )
        if let error = sshValidationError(result) {
            preconditionFailure("Refusing to encode malformed SSH Agent sync: \(error)")
        }
        return result
    }

    // MARK: KMP -> Swift

    static func fromKmp(_ value: NotiSyncProtocol.DesktopProcessIdentity) -> DesktopProcessIdentity {
        DesktopProcessIdentity(pid: value.pid, executablePath: value.executablePath, displayName: value.displayName)
    }

    static func fromKmp(_ value: NotiSyncProtocol.DesktopProcessContext) throws -> DesktopProcessContext {
        DesktopProcessContext(
            source: try strictNativeEnum(value.source.name, as: DesktopProcessContextSource.self, field: "processContext.source"),
            processLineage: value.processLineage.map(fromKmp),
            bootId: value.bootId
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshRememberedNamespace) throws -> SshRememberedNamespace {
        SshRememberedNamespace(
            requesterClientId: string(value.requesterClientId),
            authorizationGeneration: value.authorizationGeneration,
            authorizationEpoch: value.authorizationEpoch,
            scopes: try value.scopes.map {
                try strictNativeEnum($0.name, as: SshRememberScope.self, field: "rememberedNamespace.scope")
            }
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshOperationalKeyProtection) throws -> SshOperationalKeyProtection {
        SshOperationalKeyProtection(
            provider: try strictNativeEnum(value.provider.name, as: SshOperationalKeyProvider.self, field: "operationalKey.provider"),
            securityLevel: try strictNativeEnum(value.securityLevel.name, as: SshStorageSecurityLevel.self, field: "operationalKey.securityLevel"),
            userVerificationPolicy: try strictNativeEnum(value.userVerificationPolicy.name, as: SshUserVerificationPolicy.self, field: "operationalKey.userVerificationPolicy"),
            strongBoxAttempted: value.strongBoxAttempted,
            strongBoxFallback: value.strongBoxFallback
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshExportCopyProtection) throws -> SshExportCopyProtection {
        SshExportCopyProtection(
            securityLevel: try strictNativeEnum(value.securityLevel.name, as: SshStorageSecurityLevel.self, field: "exportCopy.securityLevel"),
            backendPolicy: try strictNativeEnum(value.backendPolicy.name, as: SshExportCopyBackendPolicy.self, field: "exportCopy.backendPolicy"),
            authentication: try strictNativeEnum(value.authentication.name, as: SshExportCopyAuthentication.self, field: "exportCopy.authentication"),
            strongBoxAttempted: value.strongBoxAttempted,
            strongBoxFallback: value.strongBoxFallback
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshWebAuthnCredentialProtection) -> SshWebAuthnCredentialProtection {
        SshWebAuthnCredentialProtection(rpId: value.rpId, backupEligible: value.backupEligible, backupState: value.backupState)
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshKeyDescriptor) throws -> SshKeyDescriptor {
        SshKeyDescriptor(
            providerKeyId: value.providerKeyId,
            publicKeyBlob: data(value.publicKeyBlob),
            publicKeyBlobSha256: data(value.publicKeyBlobSha256),
            algorithm: try strictNativeEnum(value.algorithm.name, as: SshKeyAlgorithm.self, field: "key.algorithm"),
            displayName: value.displayName,
            origin: try strictNativeEnum(value.origin.name, as: SshKeyOrigin.self, field: "key.origin"),
            operationalKey: try fromKmp(value.operationalKey),
            exportCopy: try value.exportCopy.map(fromKmp),
            approvalPolicy: try strictNativeEnum(value.approvalPolicy.name, as: SshApprovalPolicy.self, field: "key.approvalPolicy"),
            rememberedNamespaces: try value.rememberedNamespaces.map(fromKmp),
            createdAt: value.createdAt,
            webAuthn: value.webAuthn.map(fromKmp)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshKeysRequest) -> SshKeysRequest {
        SshKeysRequest(
            requestId: value.requestId,
            requesterClientId: string(value.requesterClientId),
            requestedAt: value.requestedAt,
            expiresAt: value.expiresAt,
            startup: value.startup,
            targetProviderClientIds: value.targetProviderClientIds.map(string),
            requesterInventoryNonce: data(value.requesterInventoryNonce)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshKeysSnapshot) throws -> SshKeysSnapshot {
        SshKeysSnapshot(
            providerClientId: string(value.providerClientId),
            inventoryGeneration: value.inventoryGeneration,
            revision: value.revision,
            generatedAt: value.generatedAt,
            respondingToRequestId: value.respondingToRequestId,
            keys: try value.keys.map(fromKmp),
            providerHealth: try strictNativeEnum(value.providerHealth.name, as: SshProviderHealth.self, field: "snapshot.providerHealth")
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshHostAlias) throws -> SshHostAlias {
        SshHostAlias(
            value: value.value,
            source: try strictNativeEnum(value.source.name, as: SshHostAliasSource.self, field: "hostAlias.source")
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshVerifiedBinding) -> SshVerifiedBinding {
        SshVerifiedBinding(hostKeyBlobSha256: data(value.hostKeyBlobSha256), forwarded: value.forwarded)
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshDestinationContext) throws -> SshDestinationContext {
        SshDestinationContext(
            provenance: try strictNativeEnum(value.provenance.name, as: SshDestinationProvenance.self, field: "destination.provenance"),
            connectionDirection: try strictNativeEnum(value.connectionDirection.name, as: SshConnectionDirection.self, field: "destination.connectionDirection"),
            username: value.username,
            service: value.service,
            authenticationMethod: value.authenticationMethod,
            sessionIdSha256: value.sessionIdSha256.map(data),
            serverHostKeyBlob: value.serverHostKeyBlob.map(data),
            serverHostKeyBlobSha256: value.serverHostKeyBlobSha256.map(data),
            hostAliases: try value.hostAliases.map(fromKmp),
            bindingChain: value.bindingChain.map(fromKmp)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshSignRequest) throws -> SshSignRequest {
        SshSignRequest(
            requestId: value.requestId,
            requesterClientId: string(value.requesterClientId),
            requestedAt: value.requestedAt,
            expiresAt: value.expiresAt,
            publicKeyBlob: data(value.publicKeyBlob),
            data: data(value.data),
            flags: value.flags,
            requestedSignatureAlgorithm: try strictNativeEnum(value.requestedSignatureAlgorithm.name, as: SshSignatureAlgorithm.self, field: "signRequest.requestedSignatureAlgorithm"),
            eligibleProviderClientIds: value.eligibleProviderClientIds.map(string),
            authorizationGeneration: value.authorizationGeneration,
            authorizationEpoch: value.authorizationEpoch,
            processContext: try fromKmp(value.processContext),
            destinationContext: try fromKmp(value.destinationContext),
            connectionId: value.connectionId,
            confirmationRequired: value.confirmationRequired
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshSignatureResult) throws -> SshSignatureResult {
        SshSignatureResult(
            signatureBlob: data(value.signatureBlob),
            rememberDisposition: try strictNativeEnum(value.rememberDisposition.name, as: SshRememberDisposition.self, field: "signature.rememberDisposition"),
            authorizationGeneration: value.authorizationGeneration,
            authorizationEpoch: value.authorizationEpoch
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshUserRejection) throws -> SshUserRejection {
        SshUserRejection(
            reason: try strictNativeEnum(value.reason.name, as: SshUserRejectionReason.self, field: "rejection.reason")
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshProviderFailure) throws -> SshProviderFailure {
        SshProviderFailure(
            code: try strictNativeEnum(value.code.name, as: SshProviderFailureCode.self, field: "failure.code"),
            retryable: value.retryable,
            message: value.message
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshSignResult) throws -> SshSignResult {
        SshSignResult(
            requestId: value.requestId,
            requesterClientId: string(value.requesterClientId),
            publicKeyBlobSha256: data(value.publicKeyBlobSha256),
            kind: try strictNativeEnum(value.kind.name, as: SshSignResultKind.self, field: "signResult.kind"),
            resultAt: value.resultAt,
            providerClientId: string(value.providerClientId),
            signature: try value.signature.map(fromKmp),
            rejection: try value.rejection.map(fromKmp),
            failure: try value.failure.map(fromKmp)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshSignRequestCancelled) throws -> SshSignRequestCancelled {
        SshSignRequestCancelled(
            requestId: value.requestId,
            requesterClientId: string(value.requesterClientId),
            cancelledAt: value.cancelledAt,
            reason: try strictNativeEnum(value.reason.name, as: SshSignCancellationReason.self, field: "signCancellation.reason"),
            targetProviderClientIds: value.targetProviderClientIds.map(string)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshImportConstraints) -> SshImportConstraints {
        SshImportConstraints(
            lifetimeSeconds: value.lifetimeSeconds?.int64Value,
            confirmationRequired: value.confirmationRequired
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshImportRequest) throws -> SshImportRequest {
        SshImportRequest(
            requestId: value.requestId,
            requesterClientId: string(value.requesterClientId),
            requestedAt: value.requestedAt,
            expiresAt: value.expiresAt,
            sourceType: try strictNativeEnum(value.sourceType.name, as: SshImportSourceType.self, field: "importRequest.sourceType"),
            fileBytes: value.fileBytes.map(data),
            agentIdentity: value.agentIdentity.map(data),
            constraints: value.constraints.map(fromKmp),
            suggestedName: value.suggestedName
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshImportResult) throws -> SshImportResult {
        SshImportResult(
            requestId: value.requestId,
            requesterClientId: string(value.requesterClientId),
            providerClientId: string(value.providerClientId),
            resultAt: value.resultAt,
            kind: try strictNativeEnum(value.kind.name, as: SshImportResultKind.self, field: "importResult.kind"),
            providerKeyId: value.providerKeyId,
            publicKeyBlob: value.publicKeyBlob.map(data),
            message: value.message
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshForgetAuthorization) -> SshForgetAuthorization {
        SshForgetAuthorization(
            requestId: value.requestId,
            requesterClientId: string(value.requesterClientId),
            authorizationGeneration: value.authorizationGeneration,
            invalidatedThroughEpoch: value.invalidatedThroughEpoch,
            requestedAt: value.requestedAt,
            expiresAt: value.expiresAt,
            targetProviderClientIds: value.targetProviderClientIds.map(string)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshForgetResult) throws -> SshForgetResult {
        SshForgetResult(
            requestId: value.requestId,
            requesterClientId: string(value.requesterClientId),
            providerClientId: string(value.providerClientId),
            resultAt: value.resultAt,
            kind: try strictNativeEnum(value.kind.name, as: SshForgetResultKind.self, field: "forgetResult.kind"),
            invalidatedThroughEpoch: value.invalidatedThroughEpoch
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.SshAgentSync?) throws -> SshAgentSync? {
        guard let value else { return nil }
        if let error = sshValidationError(value) {
            throw CodecError.typeMismatch("malformed sshAgent: \(error)")
        }
        return SshAgentSync(
            protocolVersion: Int(value.protocolVersion),
            kind: try strictNativeEnum(value.kind.name, as: SshAgentSyncKind.self, field: "sshAgent.kind"),
            keysRequest: value.keysRequest.map(fromKmp),
            keysSnapshot: try value.keysSnapshot.map(fromKmp),
            signRequest: try value.signRequest.map(fromKmp),
            signResult: try value.signResult.map(fromKmp),
            signRequestCancelled: try value.signRequestCancelled.map(fromKmp),
            importRequest: try value.importRequest.map(fromKmp),
            importResult: try value.importResult.map(fromKmp),
            forgetAuthorization: value.forgetAuthorization.map(fromKmp),
            forgetResult: try value.forgetResult.map(fromKmp)
        )
    }
}
