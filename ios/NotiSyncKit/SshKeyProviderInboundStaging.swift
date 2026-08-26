import Foundation

/// Result of committing one authenticated SSH provider request to cross-process durable state. Callers ACK
/// only outcomes whose durable state or provider response makes replay unnecessary; storage failures remain
/// retryable, and policy conflicts are deliberately left to the foreground fallback path.
nonisolated enum SshInboundStageOutcome: Sendable {
    case staged(SshProviderRequestRecord, inserted: Bool)
    case alreadyHandled(SshProviderRequestRecord)
    case notForThisProvider
    case stale
    case keyNotFound(SshSignRequest)
    case authorizationInvalidated(SshSignRequest)
    case cancelled
    case conflict
    case rateLimited
    case storageFailure
    case unsupported
}

/// Extension-safe request ingestion shared by the NSE and the foreground runtime. The protocol codec has
/// already performed structural validation; this layer binds the authenticated envelope signer to the SSH
/// requester, applies provider targeting/freshness, persists only review metadata, and stages the actionable
/// bytes in the shared device-only Keychain.
nonisolated enum SshKeyProviderInboundStager {
    private static let clockSkewMillis: Int64 = 2 * 60_000

    static func stage(
        _ sync: SshAgentSync,
        signerId: String,
        providerClientId: String,
        requesterDisplayName: String?,
        envelopeCreatedAt: Int64,
        now: Int64 = NotiSyncEngine.nowMillis()
    ) -> SshInboundStageOutcome {
        switch sync.kind {
        case .SIGN_REQUEST:
            guard let request = sync.signRequest,
                  request.requesterClientId == signerId,
                  request.eligibleProviderClientIds.contains(providerClientId) else {
                return .notForThisProvider
            }
            guard fresh(
                requestedAt: request.requestedAt,
                expiresAt: request.expiresAt,
                envelopeCreatedAt: envelopeCreatedAt,
                now: now
            ) else { return .stale }
            guard request.authorizationEpoch > SshKeyProviderStore.authorizationFloor(
                requesterClientId: request.requesterClientId,
                generation: request.authorizationGeneration
            ) else { return .authorizationInvalidated(request) }
            guard let key = SshKeyProviderStore.key(publicKeyBlob: request.publicKeyBlob) else {
                return .keyNotFound(request)
            }
            let digest = requestDigest(sync)
            let record = SshProviderRequestRecord(
                id: request.requestId,
                kind: .sign,
                status: .pendingReview,
                requesterClientId: request.requesterClientId,
                requesterDisplayName: boundedName(requesterDisplayName),
                requestedAt: request.requestedAt,
                expiresAt: request.expiresAt,
                requestDigest: digest,
                providerKeyId: key.id,
                publicKeyBlob: request.publicKeyBlob,
                publicKeyBlobSha256: NSHash.sha256(request.publicKeyBlob),
                requestedSignatureAlgorithm: request.requestedSignatureAlgorithm.rawValue,
                flags: request.flags,
                authorizationGeneration: request.authorizationGeneration,
                authorizationEpoch: request.authorizationEpoch,
                connectionId: request.connectionId,
                confirmationRequired: request.confirmationRequired,
                processSource: request.processContext.source.rawValue,
                processLineage: request.processContext.processLineage.map {
                    SshProcessReviewItem(pid: $0.pid, executablePath: $0.executablePath, displayName: $0.displayName)
                },
                destination: SshDestinationReview(
                    provenance: request.destinationContext.provenance.rawValue,
                    connectionDirection: request.destinationContext.connectionDirection.rawValue,
                    username: request.destinationContext.username,
                    service: request.destinationContext.service,
                    authenticationMethod: request.destinationContext.authenticationMethod,
                    hostAliases: request.destinationContext.hostAliases.map(\.value),
                    serverHostKeyBlob: request.destinationContext.serverHostKeyBlob,
                    serverHostKeyBlobSha256: request.destinationContext.serverHostKeyBlobSha256
                ),
                stagedSecretAccount: stagingAccount(digest)
            )
            return commit(record: record, secret: request.data, signRequest: request)

        case .IMPORT_REQUEST:
            guard let request = sync.importRequest, request.requesterClientId == signerId else {
                return .notForThisProvider
            }
            guard fresh(
                requestedAt: request.requestedAt,
                expiresAt: request.expiresAt,
                envelopeCreatedAt: envelopeCreatedAt,
                now: now
            ) else { return .stale }
            let secret: Data
            switch request.sourceType {
            case .PRIVATE_KEY_FILE:
                guard let bytes = request.fileBytes else { return .unsupported }
                secret = bytes
            case .AGENT_IDENTITY:
                guard let bytes = request.agentIdentity else { return .unsupported }
                secret = bytes
            }
            let digest = requestDigest(sync)
            let record = SshProviderRequestRecord(
                id: request.requestId,
                kind: .importKey,
                status: .pendingReview,
                requesterClientId: request.requesterClientId,
                requesterDisplayName: boundedName(requesterDisplayName),
                requestedAt: request.requestedAt,
                expiresAt: request.expiresAt,
                requestDigest: digest,
                confirmationRequired: request.constraints?.confirmationRequired ?? false,
                importSourceType: request.sourceType.rawValue,
                importSuggestedName: request.suggestedName,
                importLifetimeSeconds: request.constraints?.lifetimeSeconds,
                stagedSecretAccount: stagingAccount(digest)
            )
            return commit(record: record, secret: secret)

        case .KEYS_REQUEST, .KEYS_SNAPSHOT, .SIGN_RESULT, .SIGN_REQUEST_CANCELLED,
             .IMPORT_RESULT, .FORGET_AUTHORIZATION, .FORGET_RESULT:
            return .unsupported
        }
    }

    private static func commit(
        record: SshProviderRequestRecord,
        secret: Data,
        signRequest: SshSignRequest? = nil
    ) -> SshInboundStageOutcome {
        switch SshKeyProviderStore.stageRequest(record, secret: secret) {
        case .inserted:
            return .staged(record, inserted: true)
        case .duplicate:
            guard let existing = SshKeyProviderStore.request(id: record.id) else {
                return .storageFailure
            }
            return existing.status == .pendingReview
                ? .staged(existing, inserted: false) : .alreadyHandled(existing)
        case .cancelled:
            return .cancelled
        case .keyNotFound:
            guard let signRequest else { return .storageFailure }
            return .keyNotFound(signRequest)
        case .authorizationInvalidated:
            guard let signRequest else { return .storageFailure }
            return .authorizationInvalidated(signRequest)
        case .conflict:
            return .conflict
        case .rateLimited:
            return .rateLimited
        case .storageFailure:
            return .storageFailure
        }
    }

    private static func requestDigest(_ sync: SshAgentSync) -> Data {
        NSHash.sha256(ProtocolCodec.encode(DataSync(kind: .SSH_AGENT, sshAgent: sync)))
    }

    private static func stagingAccount(_ digest: Data) -> String? {
        let id = digest.prefix(16).map { String(format: "%02x", $0) }.joined()
        return SshPendingSecretStore.account(requestId: id)
    }

    private static func fresh(
        requestedAt: Int64,
        expiresAt: Int64,
        envelopeCreatedAt: Int64,
        now: Int64
    ) -> Bool {
        guard requestedAt <= now + clockSkewMillis, now <= expiresAt else { return false }
        guard envelopeCreatedAt > 0 else { return true }
        let lower = requestedAt > clockSkewMillis ? requestedAt - clockSkewMillis : 0
        let upper = requestedAt <= Int64.max - clockSkewMillis
            ? requestedAt + clockSkewMillis : Int64.max
        return envelopeCreatedAt >= lower && envelopeCreatedAt <= upper
    }

    private static func boundedName(_ value: String?) -> String? {
        guard let value else { return nil }
        let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return nil }
        var bytes = Array(clean.utf8.prefix(256))
        while String(bytes: bytes, encoding: .utf8) == nil, !bytes.isEmpty { bytes.removeLast() }
        return String(bytes: bytes, encoding: .utf8)
    }
}
