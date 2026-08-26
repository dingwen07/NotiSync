import Foundation
import Security

/// Public, cross-process SSH-provider state. Private keys, passphrases, sign bytes, and inbound private-key
/// files never enter this file; actionable bytes live in short-lived Keychain staging items referenced by
/// `stagedSecretAccount`. Keeping this state in the App Group lets the Notification Service Extension commit
/// an authenticated request before it acknowledges the relay message, and lets the app drive the same sheet
/// from that durable row after launch.
nonisolated enum SshProviderRequestKind: String, Codable, Sendable {
    case sign
    case importKey
}

nonisolated enum SshProviderRequestStatus: String, Codable, Sendable {
    case pendingReview
    case responsePendingSend
    case sent
    case cancelled
    case expired

    var isTerminal: Bool { self == .sent || self == .cancelled || self == .expired }
}

nonisolated enum SshProviderRequestOutcome: String, Codable, Sendable {
    case signed
    case imported
    case alreadyPresent
    case rejected
    case failed
    case cancelled
    case expired
}

nonisolated struct SshProviderKeyRecord: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var publicKeyBlob: Data
    var publicKeyBlobSha256: Data
    var algorithm: String
    var displayName: String
    var origin: String
    var operationalProvider: String
    var securityLevel: String
    var approvalPolicy: String
    var createdAt: Int64
    /// Optional ssh-add lifetime. The app removes expired managed material from Keychain during reconciliation.
    var expiresAt: Int64? = nil

    // Public WebAuthn recovery metadata. The credential provider retains the private key.
    var relyingPartyId: String? = nil
    var credentialId: Data? = nil
    var userHandle: Data? = nil
    var cosePublicKey: Data? = nil
    var recoveryRecordJSON: String? = nil
    /// Local UX state only; false/nil means the public companion record can be saved or retried in Passwords.
    var recoveryRecordSaved: Bool? = nil
    var backupEligible: Bool? = nil
    var backupState: Bool? = nil

    var isWebAuthn: Bool { relyingPartyId != nil }
}

nonisolated struct SshProcessReviewItem: Codable, Hashable, Sendable {
    var pid: Int64
    var executablePath: String?
    var displayName: String?
}

nonisolated struct SshDestinationReview: Codable, Hashable, Sendable {
    var provenance: String
    var connectionDirection: String
    var username: String?
    var service: String?
    var authenticationMethod: String?
    var hostAliases: [String]
    /// Public SSH host-key blob retained so a manual approval can independently re-check its claimed digest.
    var serverHostKeyBlob: Data? = nil
    var serverHostKeyBlobSha256: Data?
}

nonisolated struct SshProviderRequestRecord: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var kind: SshProviderRequestKind
    var status: SshProviderRequestStatus
    var requesterClientId: String
    var requesterDisplayName: String?
    var requestedAt: Int64
    var expiresAt: Int64
    /// Digest over immutable request fields, used to reject a request-id collision without retaining secrets.
    var requestDigest: Data

    // SIGN review/correlation fields.
    var providerKeyId: String? = nil
    var publicKeyBlob: Data? = nil
    var publicKeyBlobSha256: Data? = nil
    var requestedSignatureAlgorithm: String? = nil
    var flags: Int64? = nil
    var authorizationGeneration: String? = nil
    var authorizationEpoch: Int64? = nil
    var connectionId: String? = nil
    var confirmationRequired: Bool = false
    var processSource: String? = nil
    var processLineage: [SshProcessReviewItem] = []
    var destination: SshDestinationReview? = nil

    // IMPORT review/correlation fields. The actual file/add-identity bytes stay in Keychain staging.
    var importSourceType: String? = nil
    var importSuggestedName: String? = nil
    var importLifetimeSeconds: Int64? = nil
    /// Public result metadata retained so terminal import history never needs the staged private bytes.
    var importResolvedDisplayName: String? = nil
    var importResolvedAlgorithm: String? = nil

    /// Shared-Keychain account holding pending sign/import bytes. Cleared on every terminal transition.
    var stagedSecretAccount: String? = nil
    /// Shared-Keychain account holding an encoded response until the broker accepts it.
    var responseSecretAccount: String? = nil
    var outcome: SshProviderRequestOutcome? = nil
    /// `SshRememberDisposition` raw value, retained for the local approval/history audit trail.
    var approvalDisposition: String? = nil
    var outcomeMessage: String? = nil
    var completedAt: Int64? = nil
}

nonisolated struct SshAuthorizationFloorRecord: Codable, Hashable, Sendable {
    var requesterClientId: String
    var authorizationGeneration: String
    var invalidatedThroughEpoch: Int64
    var updatedAt: Int64
}

nonisolated struct SshAuthorizationForgetOutcome: Sendable {
    var applied: Bool
    var inventoryChanged: Bool
    var cancelledRequestIds: [String]
    var stagedSecretAccounts: [String]
}

nonisolated struct SshCancellationTombstoneRecord: Codable, Hashable, Sendable {
    var requestId: String
    var requesterClientId: String
    var cancelledAt: Int64
    var expiresAt: Int64
}

nonisolated struct SshRememberedAuthorizationRecord: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var providerKeyId: String
    var requesterClientId: String
    var authorizationGeneration: String
    var authorizationEpoch: Int64
    var scope: String
    var hostKeyBlobSha256: Data?
    var createdAt: Int64
}

/// User-facing registry keyed only by a cryptographically verified server host-key digest. `hostname` is an
/// optional display label, not a DNS/security identifier; nonblank values are intentionally stored verbatim.
nonisolated struct SshKnownHostRecord: Codable, Identifiable, Hashable, Sendable {
    var hostKeyBlobSha256: Data
    var hostname: String?
    var firstApprovedAt: Int64
    var lastApprovedAt: Int64

    var id: String { hostKeyBlobSha256.base64EncodedString() }
}

nonisolated struct SshProviderPersistentState: Codable, Sendable {
    var schemaVersion = 1
    var inventoryGeneration: String
    var inventoryRevision: Int64
    var keys: [SshProviderKeyRecord]
    var requests: [SshProviderRequestRecord]
    var rememberedAuthorizations: [SshRememberedAuthorizationRecord]
    var knownHosts: [SshKnownHostRecord]? = nil
    var authorizationFloors: [SshAuthorizationFloorRecord]? = nil
    var cancellationTombstones: [SshCancellationTombstoneRecord]? = nil
    /// Managed-key IDs removed from the public catalog but still awaiting idempotent Keychain deletion.
    var pendingManagedKeyDeletions: [String]? = nil
}

nonisolated enum SshKeyProviderStore {
    enum StageResult: Sendable {
        case inserted
        case duplicate
        case cancelled
        case keyNotFound
        case authorizationInvalidated
        case conflict
        case rateLimited
        case storageFailure
    }

    enum CancellationResult: Sendable {
        case applied(changed: Bool)
        case ignored
        case storageFailure
    }

    private static let fileName = AppGroupStore.Files.sshKeyProvider
    private static let historyLimit = 500
    private static let maximumPendingRequests = 64
    private static let maximumPendingPerRequester = 16
    private static let maximumActionableRequests = 128
    private static let maximumActionablePerRequester = 32
    private static let maximumCancellationTombstones = 512
    private static let maximumKnownHosts = 1_024
    private static let maximumKnownHostLabelBytes = 1_024
    static let maximumInventoryKeys = 512
    static let maximumRememberedNamespacesPerKey = 64

    static func snapshot() -> SshProviderPersistentState {
        if let state = AppGroupStore.withRequiredLock(fileName, { loadUnlocked() }) { return state }
        // Atomic replacement makes an unlocked read safe as a last-resort snapshot, but it must not create
        // or repair state when cross-process serialization is unavailable.
        switch persistentLoadUnlocked() {
        case .valid(let state): return state
        case .missing, .invalid: return freshState()
        }
    }

    static func key(id: String) -> SshProviderKeyRecord? {
        let now = nowMillis()
        return snapshot().keys.first { $0.id == id && ($0.expiresAt == nil || $0.expiresAt! > now) }
    }

    static func key(publicKeyBlob: Data) -> SshProviderKeyRecord? {
        let digest = NSHash.sha256(publicKeyBlob)
        let now = nowMillis()
        return snapshot().keys.first {
            $0.publicKeyBlobSha256 == digest && $0.publicKeyBlob == publicKeyBlob &&
                ($0.expiresAt == nil || $0.expiresAt! > now)
        }
    }

    @discardableResult
    static func markPasskeyRecoveryRecordSaved(id: String) -> Bool {
        guard validOperationId(id) else { return false }
        return mutate { state in
            guard let index = state.keys.firstIndex(where: { $0.id == id }),
                  validKeyRecord(state.keys[index]), state.keys[index].isWebAuthn else { return false }
            if state.keys[index].recoveryRecordSaved == true { return true }
            state.keys[index].recoveryRecordSaved = true
            return true
        }
    }

    static func knownHosts() -> [SshKnownHostRecord] {
        (snapshot().knownHosts ?? []).filter(validKnownHostRecord).sorted { lhs, rhs in
            switch (lhs.hostname, rhs.hostname) {
            case let (left?, right?):
                let ordered = left.localizedCaseInsensitiveCompare(right)
                return ordered == .orderedSame
                    ? lhs.lastApprovedAt > rhs.lastApprovedAt : ordered == .orderedAscending
            case (_?, nil): return true
            case (nil, _?): return false
            case (nil, nil): return lhs.lastApprovedAt > rhs.lastApprovedAt
            }
        }
    }

    static func knownHost(hostKeyBlobSha256: Data) -> SshKnownHostRecord? {
        guard hostKeyBlobSha256.count == 32 else { return nil }
        return (snapshot().knownHosts ?? []).first {
            validKnownHostRecord($0) && $0.hostKeyBlobSha256 == hostKeyBlobSha256
        }
    }

    /// Android-compatible label semantics: blank clears the label; every other bounded string is stored
    /// exactly as entered. No hostname, IP-address, or SSH syntax validation is applied.
    @discardableResult
    static func updateKnownHostHostname(hostKeyBlobSha256: Data, hostname: String?) -> Bool {
        guard hostKeyBlobSha256.count == 32 else { return false }
        let storedName: String?
        if let hostname, !hostname.allSatisfy({ $0.isWhitespace }) {
            guard hostname.utf8.count <= maximumKnownHostLabelBytes else { return false }
            storedName = hostname
        } else {
            storedName = nil
        }
        return AppGroupStore.withRequiredLock(fileName) {
            guard var state = loadWritableUnlocked(),
                  let index = (state.knownHosts ?? []).firstIndex(where: {
                      $0.hostKeyBlobSha256 == hostKeyBlobSha256 && validKnownHostRecord($0)
                  }) else { return false }
            guard state.knownHosts?[index].hostname != storedName else { return true }
            state.knownHosts?[index].hostname = storedName
            return AppGroupStore.write(state, fileName)
        } ?? false
    }

    @discardableResult
    static func forgetKnownHost(hostKeyBlobSha256: Data) -> Bool {
        guard hostKeyBlobSha256.count == 32 else { return false }
        return AppGroupStore.withRequiredLock(fileName) {
            guard var state = loadWritableUnlocked() else { return false }
            var hosts = state.knownHosts ?? []
            guard hosts.contains(where: { $0.hostKeyBlobSha256 == hostKeyBlobSha256 }) else { return false }
            hosts.removeAll { $0.hostKeyBlobSha256 == hostKeyBlobSha256 }
            state.knownHosts = hosts
            return AppGroupStore.write(state, fileName)
        } ?? false
    }

    @discardableResult
    static func forgetRememberedAuthorization(id: String, providerKeyId: String) -> Bool {
        guard validOperationId(id), validOperationId(providerKeyId) else { return false }
        return mutate { state in
            guard let authorization = state.rememberedAuthorizations.first(where: { $0.id == id }),
                  authorization.providerKeyId == providerKeyId else { return false }
            state.rememberedAuthorizations.removeAll { $0.id == id }
            advanceInventoryRevision(&state)
            return true
        }
    }

    @discardableResult
    static func upsertKey(_ key: SshProviderKeyRecord) -> Bool {
        guard validKeyRecord(key) else { return false }
        return mutate { state in
            if let duplicate = state.keys.first(where: {
                $0.publicKeyBlobSha256 == key.publicKeyBlobSha256 && $0.id != key.id
            }) {
                // A digest collision is not enough to deduplicate; require the actual canonical blob too.
                guard duplicate.publicKeyBlob != key.publicKeyBlob else { return false }
            }
            if let index = state.keys.firstIndex(where: { $0.id == key.id }) {
                guard state.keys[index] != key else { return false }
                state.keys[index] = key
            } else {
                guard state.keys.count < maximumInventoryKeys else { return false }
                state.keys.append(key)
            }
            state.pendingManagedKeyDeletions?.removeAll { $0 == key.id }
            state.keys.sort { $0.createdAt == $1.createdAt ? $0.id < $1.id : $0.createdAt < $1.createdAt }
            advanceInventoryRevision(&state)
            return true
        }
    }

    @discardableResult
    static func removeKey(id: String) -> Bool {
        AppGroupStore.withRequiredLock(fileName) {
            guard var state = loadWritableUnlocked() else { return false }
            guard let index = state.keys.firstIndex(where: { $0.id == id }) else { return false }
            let key = state.keys.remove(at: index)
            if !key.isWebAuthn {
                var pending = state.pendingManagedKeyDeletions ?? []
                if !pending.contains(id) { pending.append(id) }
                state.pendingManagedKeyDeletions = pending
            }
            state.rememberedAuthorizations.removeAll { $0.providerKeyId == id }
            let now = nowMillis()
            var stagedAccounts: [String] = []
            for requestIndex in state.requests.indices where
                state.requests[requestIndex].status == .pendingReview &&
                state.requests[requestIndex].providerKeyId == id {
                if let account = state.requests[requestIndex].stagedSecretAccount {
                    stagedAccounts.append(account)
                }
                state.requests[requestIndex].stagedSecretAccount = nil
                state.requests[requestIndex].status = .cancelled
                state.requests[requestIndex].outcome = .cancelled
                state.requests[requestIndex].completedAt = max(now, state.requests[requestIndex].requestedAt)
            }
            trimHistory(&state.requests)
            advanceInventoryRevision(&state)
            guard AppGroupStore.write(state, fileName) else { return false }
            stagedAccounts.forEach { SshPendingSecretStore.delete(account: $0) }
            return true
        } ?? false
    }

    static func pendingManagedKeyDeletions() -> [String] {
        snapshot().pendingManagedKeyDeletions ?? []
    }

    @discardableResult
    static func completeManagedKeyDeletion(id: String) -> Bool {
        mutate { state in
            guard state.pendingManagedKeyDeletions?.contains(id) == true else { return false }
            state.pendingManagedKeyDeletions?.removeAll { $0 == id }
            return true
        }
    }

    static func stageRequest(_ request: SshProviderRequestRecord, secret: Data) -> StageResult {
        guard validOperationId(request.id), request.requestedAt > 0, request.expiresAt > request.requestedAt,
              !request.requesterClientId.isEmpty, request.requestDigest.count == 32 else { return .conflict }
        return AppGroupStore.withRequiredLock(fileName) {
            guard var state = loadWritableUnlocked() else { return .storageFailure }
            if let existing = state.requests.first(where: { $0.id == request.id }) {
                guard existing.requestDigest == request.requestDigest else { return .conflict }
                if existing.status == .pendingReview, existing.stagedSecretAccount == request.stagedSecretAccount {
                    guard let account = request.stagedSecretAccount,
                          SshPendingSecretStore.save(secret, account: account) else { return .storageFailure }
                }
                return .duplicate
            }
            let now = nowMillis()
            if (state.cancellationTombstones ?? []).contains(where: {
                $0.requestId == request.id && $0.requesterClientId == request.requesterClientId && $0.expiresAt > now
            }) {
                return .cancelled
            }
            if request.kind == .sign {
                guard let providerKeyId = request.providerKeyId,
                      let publicKeyBlob = request.publicKeyBlob,
                      let publicKeyDigest = request.publicKeyBlobSha256,
                      let key = state.keys.first(where: { $0.id == providerKeyId }),
                      (key.expiresAt ?? Int64.max) > now,
                      key.publicKeyBlob == publicKeyBlob,
                      key.publicKeyBlobSha256 == publicKeyDigest else { return .keyNotFound }
                guard let generation = request.authorizationGeneration,
                      let epoch = request.authorizationEpoch else { return .authorizationInvalidated }
                let floor = (state.authorizationFloors ?? []).first(where: {
                    $0.requesterClientId == request.requesterClientId &&
                        $0.authorizationGeneration == generation
                })?.invalidatedThroughEpoch ?? -1
                guard floor < epoch else { return .authorizationInvalidated }
            }
            var expiredAccounts: [String] = []
            for index in state.requests.indices where
                state.requests[index].status == .pendingReview && state.requests[index].expiresAt <= now {
                if let account = state.requests[index].stagedSecretAccount { expiredAccounts.append(account) }
                state.requests[index].stagedSecretAccount = nil
                state.requests[index].status = .expired
                state.requests[index].outcome = .expired
                state.requests[index].completedAt = now
            }
            let pending = state.requests.filter { $0.status == .pendingReview }
            let actionable = state.requests.filter {
                $0.status == .pendingReview || $0.status == .responsePendingSend
            }
            guard pending.count < maximumPendingRequests,
                  pending.filter({ $0.requesterClientId == request.requesterClientId }).count < maximumPendingPerRequester,
                  actionable.count < maximumActionableRequests,
                  actionable.filter({ $0.requesterClientId == request.requesterClientId }).count < maximumActionablePerRequester
            else { return .rateLimited }
            guard let account = request.stagedSecretAccount,
                  SshPendingSecretStore.save(secret, account: account) else { return .storageFailure }
            state.requests.append(request)
            trimHistory(&state.requests)
            guard AppGroupStore.write(state, fileName) else {
                SshPendingSecretStore.delete(account: account)
                return .storageFailure
            }
            expiredAccounts.forEach { SshPendingSecretStore.delete(account: $0) }
            return .inserted
        } ?? .storageFailure
    }

    static func request(id: String) -> SshProviderRequestRecord? {
        snapshot().requests.first { $0.id == id }
    }

    static func pendingRequests(now: Int64 = nowMillis()) -> [SshProviderRequestRecord] {
        expireDueRequests(now: now)
        return snapshot().requests
            .filter { $0.status == .pendingReview || $0.status == .responsePendingSend }
            .sorted { $0.requestedAt < $1.requestedAt }
    }

    static func history() -> [SshProviderRequestRecord] {
        snapshot().requests.filter(\.status.isTerminal).sorted {
            ($0.completedAt ?? $0.requestedAt) > ($1.completedAt ?? $1.requestedAt)
        }
    }

    @discardableResult
    static func markResponsePending(
        id: String,
        outcome: SshProviderRequestOutcome,
        encodedResponse: Data,
        rememberedAuthorization: SshRememberedAuthorizationRecord? = nil,
        expectedMatchedAuthorization: SshRememberedAuthorizationRecord? = nil,
        signingKeyUpdate: SshProviderKeyRecord? = nil,
        importedKey: SshProviderKeyRecord? = nil,
        existingImportKey: SshProviderKeyRecord? = nil,
        observeVerifiedKnownHost: Bool = false,
        approvalDisposition: String? = nil,
        message: String? = nil,
        completedAt: Int64 = nowMillis()
    ) -> Bool {
        let validApprovalDisposition = approvalDisposition.map {
            outcome == .signed && SshRememberDisposition(rawValue: $0) != nil
        } ?? true
        guard !encodedResponse.isEmpty, encodedResponse.count <= 256 * 1024,
              let responseAccount = responseAccount(requestId: id),
              (outcome == .imported) == (importedKey != nil),
              (outcome == .alreadyPresent) == (existingImportKey != nil),
              validApprovalDisposition,
              expectedMatchedAuthorization == nil ||
                (outcome == .signed && rememberedAuthorization == nil),
              signingKeyUpdate == nil || outcome == .signed else { return false }
        return AppGroupStore.withRequiredLock(fileName) {
            guard var state = loadWritableUnlocked() else { return false }
            guard let index = state.requests.firstIndex(where: { $0.id == id }),
                  state.requests[index].status == .pendingReview else { return false }
            let now = nowMillis()
            if state.requests[index].expiresAt <= now {
                let stagedAccount = state.requests[index].stagedSecretAccount
                state.requests[index].stagedSecretAccount = nil
                state.requests[index].status = .expired
                state.requests[index].outcome = .expired
                state.requests[index].outcomeMessage = nil
                state.requests[index].completedAt = max(now, state.requests[index].requestedAt)
                trimHistory(&state.requests)
                guard AppGroupStore.write(state, fileName) else { return false }
                if let stagedAccount { SshPendingSecretStore.delete(account: stagedAccount) }
                return false
            }
            var inventoryChanged = false
            if outcome == .signed {
                let request = state.requests[index]
                guard request.kind == .sign,
                      let providerKeyId = request.providerKeyId,
                      let publicKeyBlob = request.publicKeyBlob,
                      let publicKeyDigest = request.publicKeyBlobSha256,
                      let keyIndex = state.keys.firstIndex(where: { $0.id == providerKeyId }),
                      validKeyRecord(state.keys[keyIndex]),
                      (state.keys[keyIndex].expiresAt ?? Int64.max) > now,
                      state.keys[keyIndex].publicKeyBlob == publicKeyBlob,
                      state.keys[keyIndex].publicKeyBlobSha256 == publicKeyDigest else { return false }
                if let expected = expectedMatchedAuthorization {
                    let floor = (state.authorizationFloors ?? [])
                        .filter {
                            $0.requesterClientId == request.requesterClientId &&
                                $0.authorizationGeneration == request.authorizationGeneration
                        }
                        .map(\.invalidatedThroughEpoch)
                        .max() ?? -1
                    let expectedDisposition = expected.scope == SshRememberScope.PEER_HOST_KEY.rawValue
                        ? SshRememberDisposition.MATCHED_PEER_HOST_KEY.rawValue
                        : SshRememberDisposition.MATCHED_PEER.rawValue
                    guard !request.confirmationRequired,
                          !state.keys[keyIndex].isWebAuthn,
                          state.keys[keyIndex].approvalPolicy == SshApprovalPolicy.ALLOW_REMEMBER.rawValue,
                          let epoch = request.authorizationEpoch,
                          epoch > floor,
                          validRememberedAuthorization(expected, for: request),
                          state.rememberedAuthorizations.contains(expected),
                          approvalDisposition == expectedDisposition else { return false }
                } else if approvalDisposition == SshRememberDisposition.MATCHED_PEER.rawValue ||
                            approvalDisposition == SshRememberDisposition.MATCHED_PEER_HOST_KEY.rawValue {
                    // A remembered auto-approval must always prove the exact authorization still exists
                    // in this same locked transition. Callers cannot label an ordinary signature as matched.
                    return false
                }
                if let updated = signingKeyUpdate {
                    var allowed = state.keys[keyIndex]
                    allowed.backupEligible = updated.backupEligible
                    allowed.backupState = updated.backupState
                    guard validKeyRecord(updated), updated == allowed else { return false }
                    if state.keys[keyIndex] != updated {
                        state.keys[keyIndex] = updated
                        inventoryChanged = true
                    }
                }
            }
            if outcome == .alreadyPresent {
                guard state.requests[index].kind == .importKey,
                      let expected = existingImportKey,
                      validKeyRecord(expected),
                      let current = state.keys.first(where: { $0.id == expected.id }),
                      validKeyRecord(current), (current.expiresAt ?? Int64.max) > now,
                      current.publicKeyBlob == expected.publicKeyBlob,
                      current.publicKeyBlobSha256 == expected.publicKeyBlobSha256 else { return false }
            }
            if let authorization = rememberedAuthorization {
                guard outcome == .signed,
                      validRememberedAuthorization(authorization, for: state.requests[index]),
                      state.keys.contains(where: { $0.id == authorization.providerKeyId }) else { return false }
                var authorizations = state.rememberedAuthorizations
                authorizations.removeAll { $0.id == authorization.id }
                authorizations.append(authorization)
                let namespaces = Set(authorizations.lazy
                    .filter { $0.providerKeyId == authorization.providerKeyId }
                    .map(RememberedNamespaceIdentity.init))
                guard namespaces.count <= maximumRememberedNamespacesPerKey else { return false }
                state.rememberedAuthorizations = authorizations
                inventoryChanged = true
            }
            if observeVerifiedKnownHost, outcome == .signed,
               let destination = state.requests[index].destination,
               destination.provenance == SshDestinationProvenance.VERIFIED_SESSION_BIND.rawValue,
               let hostKeyBlob = destination.serverHostKeyBlob,
               let hostKeyDigest = destination.serverHostKeyBlobSha256,
               hostKeyDigest.count == 32,
               NSHash.sha256(hostKeyBlob) == hostKeyDigest {
                var hosts = (state.knownHosts ?? []).filter(validKnownHostRecord)
                if let hostIndex = hosts.firstIndex(where: { $0.hostKeyBlobSha256 == hostKeyDigest }) {
                    hosts[hostIndex].lastApprovedAt = max(completedAt, hosts[hostIndex].firstApprovedAt)
                } else {
                    if hosts.count >= maximumKnownHosts {
                        hosts.remove(at: hosts.indices.min(by: {
                            hosts[$0].lastApprovedAt < hosts[$1].lastApprovedAt
                        }) ?? hosts.startIndex)
                    }
                    hosts.append(SshKnownHostRecord(
                        hostKeyBlobSha256: hostKeyDigest,
                        hostname: nil,
                        firstApprovedAt: completedAt,
                        lastApprovedAt: completedAt
                    ))
                }
                state.knownHosts = hosts
            }
            if let key = importedKey {
                guard state.requests[index].kind == .importKey, outcome == .imported,
                      rememberedAuthorization == nil, validKeyRecord(key),
                      state.keys.count < maximumInventoryKeys,
                      !state.keys.contains(where: {
                          $0.id == key.id ||
                              ($0.publicKeyBlobSha256 == key.publicKeyBlobSha256 &&
                                  $0.publicKeyBlob == key.publicKeyBlob)
                      }) else { return false }
                state.keys.append(key)
                state.pendingManagedKeyDeletions?.removeAll { $0 == key.id }
                state.keys.sort {
                    $0.createdAt == $1.createdAt ? $0.id < $1.id : $0.createdAt < $1.createdAt
                }
                inventoryChanged = true
            }
            if let resolvedKey = importedKey ?? existingImportKey {
                state.requests[index].providerKeyId = resolvedKey.id
                state.requests[index].publicKeyBlob = resolvedKey.publicKeyBlob
                state.requests[index].publicKeyBlobSha256 = resolvedKey.publicKeyBlobSha256
                state.requests[index].importResolvedDisplayName = resolvedKey.displayName
                state.requests[index].importResolvedAlgorithm = resolvedKey.algorithm
            }
            guard SshPendingSecretStore.save(encodedResponse, account: responseAccount) else { return false }
            let stagedAccount = state.requests[index].stagedSecretAccount
            state.requests[index].stagedSecretAccount = nil
            state.requests[index].responseSecretAccount = responseAccount
            state.requests[index].status = .responsePendingSend
            state.requests[index].outcome = outcome
            state.requests[index].approvalDisposition = approvalDisposition
            state.requests[index].outcomeMessage = bounded(message, maximumBytes: 2_048)
            state.requests[index].completedAt = max(completedAt, state.requests[index].requestedAt)
            if inventoryChanged { advanceInventoryRevision(&state) }
            guard AppGroupStore.write(state, fileName) else {
                SshPendingSecretStore.delete(account: responseAccount)
                return false
            }
            if let stagedAccount { SshPendingSecretStore.delete(account: stagedAccount) }
            return true
        } ?? false
    }

    static func pendingResponse(id: String) -> Data? {
        guard let request = request(id: id), request.status == .responsePendingSend,
              let account = request.responseSecretAccount else { return nil }
        return SshPendingSecretStore.load(account: account)
    }

    @discardableResult
    static func markResponseSent(id: String) -> Bool {
        var responseAccount: String?
        let changed = mutate { state in
            guard let index = state.requests.firstIndex(where: { $0.id == id }),
                  state.requests[index].status == .responsePendingSend else { return false }
            responseAccount = state.requests[index].responseSecretAccount
            state.requests[index].responseSecretAccount = nil
            state.requests[index].status = .sent
            trimHistory(&state.requests)
            return true
        }
        if changed, let responseAccount { SshPendingSecretStore.delete(account: responseAccount) }
        return changed
    }

    @discardableResult
    static func cancel(
        id: String,
        requesterClientId: String,
        at: Int64 = nowMillis(),
        message: String? = nil
    ) -> CancellationResult {
        guard validOperationId(id), !requesterClientId.isEmpty, at > 0 else { return .ignored }
        return AppGroupStore.withRequiredLock(fileName) {
            guard var state = loadWritableUnlocked() else { return .storageFailure }
            let (tombstoneExpiry, overflow) = at.addingReportingOverflow(15 * 60_000)
            guard !overflow else { return .ignored }
            var tombstones = (state.cancellationTombstones ?? []).filter { $0.expiresAt > nowMillis() }
            var changed = tombstones != (state.cancellationTombstones ?? [])
            var accountsToDelete: [String] = []

            if let index = state.requests.firstIndex(where: { $0.id == id }) {
                guard state.requests[index].requesterClientId == requesterClientId else { return .ignored }
                if state.requests[index].status == .pendingReview {
                    accountsToDelete = [
                        state.requests[index].stagedSecretAccount,
                        state.requests[index].responseSecretAccount,
                    ].compactMap { $0 }
                    state.requests[index].stagedSecretAccount = nil
                    state.requests[index].responseSecretAccount = nil
                    state.requests[index].status = .cancelled
                    state.requests[index].outcome = .cancelled
                    state.requests[index].outcomeMessage = bounded(message, maximumBytes: 2_048)
                    state.requests[index].completedAt = max(at, state.requests[index].requestedAt)
                    trimHistory(&state.requests)
                    changed = true
                }
            }

            if let index = tombstones.firstIndex(where: {
                $0.requestId == id && $0.requesterClientId == requesterClientId
            }) {
                if tombstoneExpiry > tombstones[index].expiresAt {
                    tombstones[index].cancelledAt = max(tombstones[index].cancelledAt, at)
                    tombstones[index].expiresAt = tombstoneExpiry
                    changed = true
                }
            } else {
                tombstones.append(SshCancellationTombstoneRecord(
                    requestId: id,
                    requesterClientId: requesterClientId,
                    cancelledAt: at,
                    expiresAt: tombstoneExpiry
                ))
                changed = true
            }
            tombstones.sort { $0.expiresAt < $1.expiresAt }
            if tombstones.count > maximumCancellationTombstones {
                tombstones.removeFirst(tombstones.count - maximumCancellationTombstones)
                changed = true
            }
            state.cancellationTombstones = tombstones
            guard changed else { return .applied(changed: false) }
            guard AppGroupStore.write(state, fileName) else { return .storageFailure }
            accountsToDelete.forEach { SshPendingSecretStore.delete(account: $0) }
            return .applied(changed: true)
        } ?? .storageFailure
    }

    static func expireDueRequests(now: Int64 = nowMillis()) {
        _ = AppGroupStore.withRequiredLock(fileName) {
            guard var state = loadWritableUnlocked() else { return }
            var stagedAccounts: [String] = []
            var changed = false
            for index in state.requests.indices where
                state.requests[index].status == .pendingReview && state.requests[index].expiresAt <= now {
                if let account = state.requests[index].stagedSecretAccount { stagedAccounts.append(account) }
                state.requests[index].stagedSecretAccount = nil
                state.requests[index].status = .expired
                state.requests[index].outcome = .expired
                state.requests[index].completedAt = now
                changed = true
            }
            if changed { trimHistory(&state.requests) }
            guard changed, AppGroupStore.write(state, fileName) else { return }
            for account in stagedAccounts { SshPendingSecretStore.delete(account: account) }
        }
    }

    static func rememberedAuthorization(
        providerKeyId: String,
        requesterClientId: String,
        generation: String,
        epoch: Int64,
        hostKeyBlobSha256: Data?,
        destinationProvenance: String?
    ) -> SshRememberedAuthorizationRecord? {
        snapshot().rememberedAuthorizations.first {
            validRememberedAuthorizationRecord($0, providerKeyId: providerKeyId) &&
                $0.requesterClientId == requesterClientId &&
                $0.authorizationGeneration == generation && $0.authorizationEpoch == epoch &&
                ($0.scope == SshRememberScope.PEER.rawValue ||
                    ($0.scope == SshRememberScope.PEER_HOST_KEY.rawValue &&
                        destinationProvenance == SshDestinationProvenance.VERIFIED_SESSION_BIND.rawValue &&
                        $0.hostKeyBlobSha256 == hostKeyBlobSha256))
        }
    }

    static func authorizationFloor(requesterClientId: String, generation: String) -> Int64 {
        snapshot().authorizationFloors?.first {
            $0.requesterClientId == requesterClientId && $0.authorizationGeneration == generation
        }?.invalidatedThroughEpoch ?? -1
    }

    static func forgetAuthorization(
        requesterClientId: String,
        generation: String,
        through epoch: Int64,
        at: Int64 = nowMillis()
    ) -> SshAuthorizationForgetOutcome {
        return AppGroupStore.withRequiredLock(fileName) {
            guard var state = loadWritableUnlocked() else {
                return SshAuthorizationForgetOutcome(
                    applied: false,
                    inventoryChanged: false,
                    cancelledRequestIds: [],
                    stagedSecretAccounts: []
                )
            }
            var cancelledRequestIds: [String] = []
            var stagedSecretAccounts: [String] = []
            var inventoryChanged = false
            var floors = state.authorizationFloors ?? []
            if let index = floors.firstIndex(where: {
                $0.requesterClientId == requesterClientId && $0.authorizationGeneration == generation
            }) {
                if epoch > floors[index].invalidatedThroughEpoch {
                    floors[index].invalidatedThroughEpoch = epoch
                    floors[index].updatedAt = at
                    inventoryChanged = true
                }
            } else {
                floors.append(SshAuthorizationFloorRecord(
                    requesterClientId: requesterClientId,
                    authorizationGeneration: generation,
                    invalidatedThroughEpoch: epoch,
                    updatedAt: at
                ))
                inventoryChanged = true
            }
            state.authorizationFloors = floors
            let before = state.rememberedAuthorizations.count
            state.rememberedAuthorizations.removeAll {
                $0.requesterClientId == requesterClientId && $0.authorizationGeneration == generation &&
                    $0.authorizationEpoch <= epoch
            }
            inventoryChanged = inventoryChanged || before != state.rememberedAuthorizations.count
            for index in state.requests.indices {
                guard state.requests[index].status == .pendingReview,
                      state.requests[index].kind == .sign,
                      state.requests[index].requesterClientId == requesterClientId,
                      state.requests[index].authorizationGeneration == generation,
                      (state.requests[index].authorizationEpoch ?? Int64.max) <= epoch else { continue }
                cancelledRequestIds.append(state.requests[index].id)
                if let account = state.requests[index].stagedSecretAccount { stagedSecretAccounts.append(account) }
                state.requests[index].stagedSecretAccount = nil
                state.requests[index].status = .cancelled
                state.requests[index].outcome = .cancelled
                state.requests[index].completedAt = at
            }
            let changed = inventoryChanged || !cancelledRequestIds.isEmpty
            if inventoryChanged { advanceInventoryRevision(&state) }
            guard !changed || AppGroupStore.write(state, fileName) else {
                return SshAuthorizationForgetOutcome(
                    applied: false,
                    inventoryChanged: false,
                    cancelledRequestIds: [],
                    stagedSecretAccounts: []
                )
            }
            stagedSecretAccounts.forEach { SshPendingSecretStore.delete(account: $0) }
            return SshAuthorizationForgetOutcome(
                applied: true,
                inventoryChanged: inventoryChanged,
                cancelledRequestIds: cancelledRequestIds,
                stagedSecretAccounts: stagedSecretAccounts
            )
        } ?? SshAuthorizationForgetOutcome(
            applied: false,
            inventoryChanged: false,
            cancelledRequestIds: [],
            stagedSecretAccounts: []
        )
    }

    static func deleteOrphanedPendingSecrets() {
        _ = AppGroupStore.withRequiredLock(fileName) {
            guard let state = loadWritableUnlocked() else { return }
            let retainedAccounts = Set(state.requests.flatMap {
                [$0.stagedSecretAccount, $0.responseSecretAccount].compactMap { $0 }
            })
            SshPendingSecretStore.deleteAll(except: retainedAccounts)
        }
    }

    @discardableResult
    private static func mutate(_ body: (inout SshProviderPersistentState) -> Bool) -> Bool {
        return AppGroupStore.withRequiredLock(fileName) {
            guard var state = loadWritableUnlocked() else { return false }
            guard body(&state) else { return false }
            return AppGroupStore.write(state, fileName)
        } ?? false
    }

    private static func loadUnlocked() -> SshProviderPersistentState {
        switch persistentLoadUnlocked() {
        case .valid(let state):
            return state
        case .missing:
            let fresh = freshState()
            _ = AppGroupStore.write(fresh, fileName)
            return fresh
        case .invalid:
            // Never overwrite a corrupt or newer-schema catalog. Read-only callers see an empty safe view,
            // while every mutating path fails closed and preserves the original bytes for recovery/upgrade.
            return freshState()
        }
    }

    private static func loadWritableUnlocked() -> SshProviderPersistentState? {
        switch persistentLoadUnlocked() {
        case .valid(let state): state
        case .missing: freshState()
        case .invalid: nil
        }
    }

    private static func persistentLoadUnlocked() -> PersistentLoadResult {
        guard let container = AppGroupStore.containerURL else { return .invalid }
        let url = container.appendingPathComponent(fileName)
        guard FileManager.default.fileExists(atPath: url.path) else { return .missing }
        guard let state = AppGroupStore.read(SshProviderPersistentState.self, fileName),
              state.schemaVersion == 1,
              validOperationId(state.inventoryGeneration),
              state.inventoryRevision > 0 else { return .invalid }
        return .valid(state)
    }

    private static func freshState() -> SshProviderPersistentState {
        SshProviderPersistentState(
            inventoryGeneration: randomOperationId(),
            inventoryRevision: 1,
            keys: [],
            requests: [],
            rememberedAuthorizations: [],
            knownHosts: [],
            authorizationFloors: [],
            cancellationTombstones: [],
            pendingManagedKeyDeletions: []
        )
    }

    private enum PersistentLoadResult {
        case valid(SshProviderPersistentState)
        case missing
        case invalid
    }

    private static func trimHistory(_ requests: inout [SshProviderRequestRecord]) {
        let terminal = requests.indices.filter { requests[$0].status.isTerminal }
        guard terminal.count > historyLimit else { return }
        let keep = Set(terminal.sorted {
            (requests[$0].completedAt ?? requests[$0].requestedAt) >
                (requests[$1].completedAt ?? requests[$1].requestedAt)
        }.prefix(historyLimit))
        requests = requests.enumerated().compactMap { index, request in
            !request.status.isTerminal || keep.contains(index) ? request : nil
        }
    }

    private static func randomOperationId() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            return UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func advanceInventoryRevision(_ state: inout SshProviderPersistentState) {
        if state.inventoryRevision < Int64.max {
            state.inventoryRevision += 1
        } else {
            state.inventoryGeneration = randomOperationId()
            state.inventoryRevision = 1
        }
    }

    private static func responseAccount(requestId: String) -> String? {
        guard validOperationId(requestId) else { return nil }
        // A fresh account prevents a duplicate approval attempt from overwriting and then deleting the
        // already-durable response when its compare-and-transition loses the race.
        let accountId = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        return SshPendingSecretStore.account(requestId: accountId)
    }

    private static func validOperationId(_ value: String) -> Bool {
        value.utf8.count == 32 && value.utf8.allSatisfy {
            ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x66)
        }
    }

    private static func validRememberedAuthorization(
        _ authorization: SshRememberedAuthorizationRecord,
        for request: SshProviderRequestRecord
    ) -> Bool {
        guard let providerKeyId = request.providerKeyId,
              validRememberedAuthorizationRecord(authorization, providerKeyId: providerKeyId),
              authorization.requesterClientId == request.requesterClientId,
              authorization.authorizationGeneration == request.authorizationGeneration,
              authorization.authorizationEpoch == request.authorizationEpoch else { return false }
        switch authorization.scope {
        case SshRememberScope.PEER.rawValue:
            return authorization.hostKeyBlobSha256 == nil
        case SshRememberScope.PEER_HOST_KEY.rawValue:
            return authorization.hostKeyBlobSha256?.count == 32 &&
                request.destination?.provenance == SshDestinationProvenance.VERIFIED_SESSION_BIND.rawValue &&
                authorization.hostKeyBlobSha256 == request.destination?.serverHostKeyBlobSha256
        default:
            return false
        }
    }

    static func validRememberedAuthorizationRecord(
        _ authorization: SshRememberedAuthorizationRecord,
        providerKeyId: String
    ) -> Bool {
        guard validOperationId(authorization.id), validOperationId(providerKeyId),
              authorization.providerKeyId == providerKeyId,
              !authorization.requesterClientId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              authorization.requesterClientId.utf8.count <= 1_024,
              validOperationId(authorization.authorizationGeneration),
              authorization.authorizationEpoch >= 0, authorization.createdAt > 0 else { return false }
        switch authorization.scope {
        case SshRememberScope.PEER.rawValue:
            return authorization.hostKeyBlobSha256 == nil
        case SshRememberScope.PEER_HOST_KEY.rawValue:
            return authorization.hostKeyBlobSha256?.count == 32
        default:
            return false
        }
    }

    static func validKnownHostRecord(_ host: SshKnownHostRecord) -> Bool {
        host.hostKeyBlobSha256.count == 32 && host.firstApprovedAt > 0 &&
            host.lastApprovedAt >= host.firstApprovedAt &&
            (host.hostname?.utf8.count ?? 0) <= maximumKnownHostLabelBytes &&
            !(host.hostname?.allSatisfy({ $0.isWhitespace }) ?? false)
    }

    static func validKeyRecord(_ key: SshProviderKeyRecord) -> Bool {
        guard validOperationId(key.id), !key.publicKeyBlob.isEmpty, key.publicKeyBlob.count <= 16 * 1_024,
              key.publicKeyBlobSha256.count == 32,
              key.publicKeyBlobSha256 == NSHash.sha256(key.publicKeyBlob),
              key.createdAt > 0,
              !key.displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              key.displayName.utf8.count <= 256,
              !key.displayName.unicodeScalars.contains(where: {
                  CharacterSet.controlCharacters.contains($0)
              }),
              SshKeyAlgorithm(rawValue: key.algorithm) != nil,
              SshKeyOrigin(rawValue: key.origin) != nil,
              SshOperationalKeyProvider(rawValue: key.operationalProvider) != nil,
              SshStorageSecurityLevel(rawValue: key.securityLevel) != nil,
              SshApprovalPolicy(rawValue: key.approvalPolicy) != nil else { return false }
        if key.isWebAuthn {
            guard key.algorithm == SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256.rawValue,
                  key.operationalProvider ==
                    SshOperationalKeyProvider.APPLE_AUTHENTICATION_SERVICES_WEBAUTHN.rawValue,
                  key.securityLevel == SshStorageSecurityLevel.CREDENTIAL_PROVIDER.rawValue,
                  key.approvalPolicy == SshApprovalPolicy.ALWAYS_ASK.rawValue,
                  key.origin == SshKeyOrigin.WEBAUTHN_CREATED.rawValue ||
                    key.origin == SshKeyOrigin.WEBAUTHN_RECOVERED.rawValue,
                  let rpId = key.relyingPartyId, !rpId.isEmpty, rpId.utf8.count <= 1_024,
                  rpId.utf8.allSatisfy({ byte in
                      (byte >= 0x61 && byte <= 0x7a) || (byte >= 0x30 && byte <= 0x39) ||
                          byte == 0x2e || byte == 0x2d
                  }),
                  key.credentialId?.isEmpty == false, key.userHandle?.isEmpty == false,
                  key.cosePublicKey?.isEmpty == false,
                  key.recoveryRecordJSON?.isEmpty == false,
                  key.backupState != true || key.backupEligible == true else { return false }
        } else {
            guard key.algorithm != SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256.rawValue,
                  key.operationalProvider == SshOperationalKeyProvider.APPLE_KEYCHAIN.rawValue,
                  key.securityLevel == SshStorageSecurityLevel.KEYCHAIN.rawValue else { return false }
        }
        return true
    }

    private struct RememberedNamespaceIdentity: Hashable {
        var requesterClientId: String
        var authorizationGeneration: String
        var authorizationEpoch: Int64

        init(_ authorization: SshRememberedAuthorizationRecord) {
            requesterClientId = authorization.requesterClientId
            authorizationGeneration = authorization.authorizationGeneration
            authorizationEpoch = authorization.authorizationEpoch
        }
    }

    private static func bounded(_ value: String?, maximumBytes: Int) -> String? {
        guard let value else { return nil }
        let clean = String(value.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) })
        var bytes = Array(clean.utf8.prefix(maximumBytes))
        while String(bytes: bytes, encoding: .utf8) == nil, !bytes.isEmpty { bytes.removeLast() }
        return String(bytes: bytes, encoding: .utf8)
    }

    private static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }
}
