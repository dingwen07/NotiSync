import AuthenticationServices
import Foundation
import UIKit
import UserNotifications

private nonisolated enum SshRuntimeError: Error, LocalizedError, Sendable {
    case message(String)

    var errorDescription: String? {
        guard case .message(let value) = self else { return nil }
        return value
    }
}

/// Runtime failures are surfaced directly in SSH sheets, so keep their text in the String Catalog rather
/// than leaking English-only `LocalizedError` descriptions into an otherwise localized flow.
private nonisolated enum SshRuntimeErrorText {
    static let providerUnavailable = String(
        localized: "NotiSync cannot present the passkey provider right now.",
        comment: "SSH error shown when Authentication Services has no presentation window."
    )
    static let notReady = String(
        localized: "NotiSync is not ready.",
        comment: "SSH error shown when the runtime has not finished starting."
    )
    static let pasteRecovery = String(
        localized: "Paste a NotiSync SSH passkey recovery record first.",
        comment: "SSH passkey recovery validation error."
    )
    static let knownHostRemove = String(
        localized: "The Known Host entry could not be removed.",
        comment: "SSH Known Hosts removal failure."
    )
    static let knownHostUpdate = String(
        localized: "The Known Host entry could not be updated.",
        comment: "SSH Known Hosts update failure."
    )
    static let knownHostLabelLong = String(
        localized: "The Known Host label is too long.",
        comment: "SSH Known Host label validation error."
    )
    static let catalogSave = String(
        localized: "The SSH key catalog could not be saved.",
        comment: "SSH key catalog persistence failure."
    )
    static let catalogUpdate = String(
        localized: "The SSH key catalog could not be updated.",
        comment: "SSH key catalog update failure."
    )
    static let invalidKeyName = String(
        localized: "The SSH key name must contain 1–256 bytes without control characters.",
        comment: "SSH key name validation error."
    )
    static let keyMissing = String(
        localized: "The SSH key no longer exists.",
        comment: "SSH key missing error."
    )
    static let recoveryIncomplete = String(
        localized: "The SSH passkey recovery metadata is incomplete.",
        comment: "SSH passkey recovery metadata validation error."
    )
    static let recoveryUnavailable = String(
        localized: "The SSH passkey recovery metadata is unavailable.",
        comment: "SSH passkey recovery metadata missing error."
    )
    static let requestDataMissing = String(
        localized: "The SSH request data is no longer available.",
        comment: "SSH signing request secret-data expiry error."
    )
    static let responseSave = String(
        localized: "The SSH response could not be saved for delivery.",
        comment: "SSH durable response persistence failure."
    )
    static let passkeyAdd = String(
        localized: "The passkey SSH key could not be added to this device.",
        comment: "SSH passkey catalog insertion failure."
    )
    static let privateKeyDataMissing = String(
        localized: "The private-key data is no longer available.",
        comment: "SSH private-key import staging expiry error."
    )
    static let recoveredPasskeySave = String(
        localized: "The recovered passkey SSH key could not be saved.",
        comment: "Recovered SSH passkey catalog persistence failure."
    )
    static let recoveryStatusUpdate = String(
        localized: "The recovery record was saved to Passwords, but local status could not be updated.",
        comment: "Partial SSH passkey recovery-record save failure."
    )
    static let authorizationRemove = String(
        localized: "The remembered SSH authorization could not be removed.",
        comment: "Remembered SSH authorization removal failure."
    )
    static let constraintMismatch = String(
        localized: "The ssh-add constraints do not match the authenticated request.",
        comment: "Authenticated remote ssh-add import constraint mismatch."
    )
    static let authorizationRevoked = String(
        localized: "This SSH authorization was revoked.",
        comment: "SSH request invalidated by an authorization floor."
    )
    static let keyAlreadyPresent = String(
        localized: "This SSH key is already present on this device.",
        comment: "Duplicate SSH key import error."
    )
    static let requestNotPending = String(
        localized: "This SSH request is no longer waiting for approval.",
        comment: "SSH approval error after a request became terminal."
    )
    static let unsupportedRemoteImport = String(
        localized: "This remote SSH import type is unsupported.",
        comment: "Unsupported authenticated remote SSH import kind."
    )
}

private nonisolated struct SshRememberedNamespaceIdentity: Hashable {
    var requesterClientId: String
    var authorizationGeneration: String
    var authorizationEpoch: Int64

    init(_ authorization: SshRememberedAuthorizationRecord) {
        requesterClientId = authorization.requesterClientId
        authorizationGeneration = authorization.authorizationGeneration
        authorizationEpoch = authorization.authorizationEpoch
    }
}

extension NotiSyncRuntime {
    // MARK: Inbound provider protocol

    func handleSshKeyProviderSync(
        _ sync: SshAgentSync,
        from signerId: String,
        envelopeCreatedAt: Int64
    ) async -> Bool {
        guard let engine, engine.isOwnDevice(signerId) else { return true }
        let requesterName = engine.trustedPeers().first { $0.clientId == signerId }?.displayName
        switch sync.kind {
        case .KEYS_REQUEST:
            guard let request = sync.keysRequest,
                  request.requesterClientId == signerId,
                  request.targetProviderClientIds.contains(engine.selfClientId),
                  sshFresh(requestedAt: request.requestedAt, expiresAt: request.expiresAt,
                           envelopeCreatedAt: envelopeCreatedAt) else { return true }
            return await sendSshSnapshot(to: signerId, respondingTo: request.requestId)

        case .SIGN_REQUEST, .IMPORT_REQUEST:
            let outcome = await Task.detached(priority: .userInitiated) {
                SshKeyProviderInboundStager.stage(
                    sync,
                    signerId: signerId,
                    providerClientId: engine.selfClientId,
                    requesterDisplayName: requesterName,
                    envelopeCreatedAt: envelopeCreatedAt
                )
            }.value
            switch outcome {
            case .staged(let request, _):
                bumpSshKeyProviderRevision()
                if request.kind == .sign, await autoApproveRememberedSshRequest(request) {
                    // The response is durable before this returns, even if broker delivery must retry later.
                } else {
                    let presentForegroundSheet = UIApplication.shared.applicationState == .active
                    // Notification Center is the durable user-visible owner even when the foreground sheet
                    // opens immediately. The application-state snapshot controls only whether this copy is
                    // silent, never whether the notification exists; this closes the active->background race.
                    await postSshKeyProviderReviewNotification(
                        for: request,
                        foregroundSheetPresented: presentForegroundSheet
                    )
                    if presentForegroundSheet { presentSshKeyProviderRequest(request.id) }
                }
                return true
            case .alreadyHandled(let request):
                if request.status == .responsePendingSend {
                    _ = await sendPersistedSshResponse(id: request.id)
                }
                return true
            case .keyNotFound(let request):
                return await sendImmediateSshSignFailure(request, code: .KEY_NOT_FOUND)
            case .authorizationInvalidated(let request):
                return await sendImmediateSshSignFailure(request, code: .REQUEST_EXPIRED)
            case .storageFailure:
                return false
            case .notForThisProvider, .stale, .cancelled,
                 .conflict, .rateLimited, .unsupported:
                return true
            }

        case .SIGN_REQUEST_CANCELLED:
            guard let cancellation = sync.signRequestCancelled,
                  cancellation.requesterClientId == signerId,
                  cancellation.targetProviderClientIds.contains(engine.selfClientId),
                  sshEventFresh(at: cancellation.cancelledAt, envelopeCreatedAt: envelopeCreatedAt) else { return true }
            switch SshKeyProviderStore.cancel(
                id: cancellation.requestId,
                requesterClientId: signerId,
                at: cancellation.cancelledAt
            ) {
            case .applied(let changed):
                if changed {
                    bumpSshKeyProviderRevision()
                    await clearSshReviewNotification(id: cancellation.requestId)
                }
                return true
            case .ignored:
                return true
            case .storageFailure:
                return false
            }

        case .FORGET_AUTHORIZATION:
            guard let request = sync.forgetAuthorization,
                  request.requesterClientId == signerId,
                  request.targetProviderClientIds.contains(engine.selfClientId),
                  sshFresh(requestedAt: request.requestedAt, expiresAt: request.expiresAt,
                           envelopeCreatedAt: envelopeCreatedAt) else { return true }
            let outcome = SshKeyProviderStore.forgetAuthorization(
                requesterClientId: request.requesterClientId,
                generation: request.authorizationGeneration,
                through: request.invalidatedThroughEpoch
            )
            guard outcome.applied else { return false }
            if !outcome.cancelledRequestIds.isEmpty || outcome.inventoryChanged { bumpSshKeyProviderRevision() }
            let result = SshForgetResult(
                requestId: request.requestId,
                requesterClientId: request.requesterClientId,
                providerClientId: engine.selfClientId,
                resultAt: NotiSyncEngine.nowMillis(),
                kind: .APPLIED,
                invalidatedThroughEpoch: request.invalidatedThroughEpoch
            )
            let sent = await sendSshSync(
                SshAgentSync(kind: .FORGET_RESULT, forgetResult: result),
                to: signerId
            )
            if outcome.inventoryChanged { await broadcastSshSnapshot() }
            return sent

        case .KEYS_SNAPSHOT, .SIGN_RESULT, .IMPORT_RESULT, .FORGET_RESULT:
            // This app is a key provider, not an SSH-agent consumer.
            return true
        }
    }

    // MARK: User actions

    func generateManagedSshKey(
        displayName: String,
        algorithm: SshManagedGenerationAlgorithm,
        rsaBits: Int
    ) async throws {
        let name = try validatedSshDisplayName(displayName)
        let managedAlgorithm: SshManagedKeyAlgorithm
        switch algorithm {
        case .p256: managedAlgorithm = .ecdsaNistP256
        case .ed25519: managedAlgorithm = .ed25519
        case .rsa: managedAlgorithm = .rsa
        }
        let material = try await Task.detached(priority: .userInitiated) {
            try SshManagedKeyProvider.generate(algorithm: managedAlgorithm, rsaBits: rsaBits)
        }.value
        try persistManagedSshKey(material, displayName: name, origin: .GENERATED)
        bumpSshKeyProviderRevision()
        await broadcastSshSnapshot()
    }

    func importManagedSshKey(data: Data, passphrase: String?, displayName: String) async throws {
        let name = try validatedSshDisplayName(displayName)
        let material = try await Task.detached(priority: .userInitiated) {
            try SshPrivateKeyFileParser.parse(data, passphrase: passphrase)
        }.value
        if SshKeyProviderStore.key(publicKeyBlob: material.publicKeyBlob) != nil {
            throw SshRuntimeError.message(SshRuntimeErrorText.keyAlreadyPresent)
        }
        try persistManagedSshKey(material, displayName: name, origin: .SAF_IMPORT)
        bumpSshKeyProviderRevision()
        await broadcastSshSnapshot()
    }

    func previewManagedSshKey(data: Data, passphrase: String?) async throws -> SshPrivateKeyImportPreview {
        try await Task.detached(priority: .userInitiated) {
            try SshPrivateKeyFileParser.preview(data, passphrase: passphrase)
        }.value
    }

    func previewSshImportRequest(id: String, passphrase: String?) async throws -> SshPrivateKeyImportPreview {
        guard let request = SshKeyProviderStore.request(id: id), request.status == .pendingReview,
              request.kind == .importKey,
              let account = request.stagedSecretAccount,
              let secret = SshPendingSecretStore.load(account: account) else {
            throw SshRuntimeError.message(SshRuntimeErrorText.privateKeyDataMissing)
        }
        switch request.importSourceType {
        case SshImportSourceType.PRIVATE_KEY_FILE.rawValue:
            return try await Task.detached(priority: .userInitiated) {
                try SshPrivateKeyFileParser.preview(secret, passphrase: passphrase)
            }.value
        case SshImportSourceType.AGENT_IDENTITY.rawValue:
            let constrained = request.importLifetimeSeconds != nil || request.confirmationRequired
            let preview = try await Task.detached(priority: .userInitiated) {
                try SshPrivateKeyFileParser.previewAgentIdentity(secret, constrained: constrained)
            }.value
            guard preview.agentConstraints?.lifetimeSeconds.map(Int64.init) == request.importLifetimeSeconds,
                  preview.agentConstraints?.confirmationRequired == request.confirmationRequired else {
                throw SshRuntimeError.message(SshRuntimeErrorText.constraintMismatch)
            }
            return preview
        default:
            throw SshRuntimeError.message(SshRuntimeErrorText.unsupportedRemoteImport)
        }
    }

    /// Returns whether the companion public recovery record was saved to Passwords. A false result is a
    /// partial success: the passkey-backed SSH key is already durable and must not be created again.
    func createSshPasskey(displayName: String) async throws -> Bool {
        let name = try validatedSshDisplayName(displayName)
        let exclusions = SshKeyProviderStore.snapshot().keys.compactMap(\.credentialId)
        let result = try await sshPasskeyProvider.register(
            displayName: name,
            excludedCredentialIDs: exclusions,
            presentationAnchor: try sshPresentationAnchor()
        )
        let record = try passkeyRecord(
            result.credential,
            displayName: name,
            origin: .WEBAUTHN_CREATED,
            recoveryRecordJSON: result.recoveryRecordJSON
        )
        guard SshKeyProviderStore.key(publicKeyBlob: record.publicKeyBlob) == nil,
              SshKeyProviderStore.upsertKey(record) else {
            throw SshRuntimeError.message(SshRuntimeErrorText.passkeyAdd)
        }
        let recoverySaved: Bool
        do {
            _ = try await sshPasskeyProvider.saveRecoveryRecord(
                result.recoveryRecordJSON,
                for: result.credential,
                presentationAnchor: try sshPresentationAnchor()
            )
            // Passwords is authoritative for this outcome. A local marker write failure must not invite the
            // user to create a duplicate passkey after the external save already succeeded.
            _ = SshKeyProviderStore.markPasskeyRecoveryRecordSaved(id: record.id)
            recoverySaved = true
        } catch {
            recoverySaved = false
        }
        bumpSshKeyProviderRevision()
        await broadcastSshSnapshot()
        return recoverySaved
    }

    func saveSshPasskeyRecoveryRecord(id: String) async throws {
        guard let key = SshKeyProviderStore.key(id: id), key.isWebAuthn,
              let recoveryRecord = key.recoveryRecordJSON else {
            throw SshRuntimeError.message(SshRuntimeErrorText.recoveryUnavailable)
        }
        _ = try await sshPasskeyProvider.saveRecoveryRecord(
            recoveryRecord,
            for: try passkeyCredential(from: key),
            presentationAnchor: try sshPresentationAnchor()
        )
        guard SshKeyProviderStore.markPasskeyRecoveryRecordSaved(id: id) else {
            throw SshRuntimeError.message(SshRuntimeErrorText.recoveryStatusUpdate)
        }
        bumpSshKeyProviderRevision()
    }

    func beginSshPasskeyRecovery() async throws -> SshPasskeyRecoverySelection {
        try await sshPasskeyProvider.beginRecovery(presentationAnchor: try sshPresentationAnchor())
    }

    func lookupSshPasskeyRecoveryRecord(
        for selection: SshPasskeyRecoverySelection
    ) async throws -> (record: String, suggestedName: String) {
        let record = try await sshPasskeyProvider.lookupRecoveryRecord(
            for: selection,
            presentationAnchor: try sshPresentationAnchor()
        )
        let credential = try sshPasskeyProvider.decodeRecoveryRecord(record)
        return (record, credential.displayName)
    }

    func recoverSshPasskey(
        selection: SshPasskeyRecoverySelection,
        recoveryRecordJSON: String,
        displayName: String
    ) async throws {
        let result = try sshPasskeyProvider.completeRecovery(
            selection,
            recoveryRecordJSON: recoveryRecordJSON
        )
        try await persistRecoveredSshPasskey(result, displayName: displayName)
    }

    func recoverSshPasskey(recoveryRecordJSON: String, displayName: String) async throws {
        guard !recoveryRecordJSON.isEmpty else {
            throw SshRuntimeError.message(SshRuntimeErrorText.pasteRecovery)
        }
        let result = try await sshPasskeyProvider.recover(
            recoveryRecordJSON: recoveryRecordJSON,
            presentationAnchor: try sshPresentationAnchor()
        )
        try await persistRecoveredSshPasskey(result, displayName: displayName)
    }

    private func persistRecoveredSshPasskey(
        _ result: SshPasskeyRecoveryResult,
        displayName: String
    ) async throws {
        let name = try validatedSshDisplayName(displayName)
        let canonicalRecovery = try sshPasskeyProvider.encodeRecoveryRecord(result.credential)
        let record = try passkeyRecord(
            result.credential,
            displayName: name,
            origin: .WEBAUTHN_RECOVERED,
            recoveryRecordJSON: canonicalRecovery
        )
        if let existing = SshKeyProviderStore.key(publicKeyBlob: record.publicKeyBlob) {
            var updated = existing
            updated.displayName = name
            updated.recoveryRecordJSON = canonicalRecovery
            updated.backupEligible = record.backupEligible
            updated.backupState = record.backupState
            guard SshKeyProviderStore.upsertKey(updated) else { return }
        } else {
            guard SshKeyProviderStore.upsertKey(record) else {
                throw SshRuntimeError.message(SshRuntimeErrorText.recoveredPasskeySave)
            }
        }
        bumpSshKeyProviderRevision()
        await broadcastSshSnapshot()
    }

    func deleteSshKey(id: String) async throws {
        guard let key = SshKeyProviderStore.snapshot().keys.first(where: { $0.id == id }) else {
            throw SshRuntimeError.message(SshRuntimeErrorText.keyMissing)
        }
        guard SshKeyProviderStore.removeKey(id: id) else {
            throw SshRuntimeError.message(SshRuntimeErrorText.catalogUpdate)
        }
        if !key.isWebAuthn, SshManagedKeyProvider.delete(keyId: key.id) {
            _ = SshKeyProviderStore.completeManagedKeyDeletion(id: key.id)
        }
        bumpSshKeyProviderRevision()
        await clearObsoleteSshReviewNotifications()
        await broadcastSshSnapshot()
    }

    func updateSshKnownHostHostname(hostKeyBlobSha256: Data, hostname: String) async throws {
        guard hostname.utf8.count <= 1_024 else {
            throw SshRuntimeError.message(SshRuntimeErrorText.knownHostLabelLong)
        }
        guard SshKeyProviderStore.updateKnownHostHostname(
            hostKeyBlobSha256: hostKeyBlobSha256,
            hostname: hostname
        ) else {
            throw SshRuntimeError.message(SshRuntimeErrorText.knownHostUpdate)
        }
        bumpSshKeyProviderRevision()
    }

    func forgetSshKnownHost(hostKeyBlobSha256: Data) async throws {
        guard SshKeyProviderStore.forgetKnownHost(hostKeyBlobSha256: hostKeyBlobSha256) else {
            throw SshRuntimeError.message(SshRuntimeErrorText.knownHostRemove)
        }
        // This registry is display-only. Deliberately do not revoke any key authorization here.
        bumpSshKeyProviderRevision()
    }

    func forgetSshRememberedAuthorization(id: String, providerKeyId: String) async throws {
        guard SshKeyProviderStore.forgetRememberedAuthorization(id: id, providerKeyId: providerKeyId) else {
            throw SshRuntimeError.message(SshRuntimeErrorText.authorizationRemove)
        }
        bumpSshKeyProviderRevision()
        await broadcastSshSnapshot()
    }

    func rejectSshRequest(id: String) async throws {
        guard let engine else { throw SshRuntimeError.message(SshRuntimeErrorText.notReady) }
        guard let request = SshKeyProviderStore.request(id: id), request.status == .pendingReview else {
            throw SshRuntimeError.message(SshRuntimeErrorText.requestNotPending)
        }
        let now = NotiSyncEngine.nowMillis()
        let sync: SshAgentSync
        switch request.kind {
        case .sign:
            let result = SshSignResult(
                requestId: request.id,
                requesterClientId: request.requesterClientId,
                publicKeyBlobSha256: request.publicKeyBlobSha256 ?? Data(repeating: 0, count: 32),
                kind: .REJECTED_BY_USER,
                resultAt: now,
                providerClientId: engine.selfClientId,
                signature: nil,
                rejection: SshUserRejection(reason: .USER_TAPPED_REJECT),
                failure: nil
            )
            sync = SshAgentSync(kind: .SIGN_RESULT, signResult: result)
        case .importKey:
            let result = SshImportResult(
                requestId: request.id,
                requesterClientId: request.requesterClientId,
                providerClientId: engine.selfClientId,
                resultAt: now,
                kind: .USER_DECLINED,
                providerKeyId: nil,
                publicKeyBlob: nil,
                message: nil
            )
            sync = SshAgentSync(kind: .IMPORT_RESULT, importResult: result)
        }
        try queueSshResponse(request: request, sync: sync, outcome: .rejected)
        bumpSshKeyProviderRevision()
        await clearSshReviewNotification(id: request.id)
        _ = await sendPersistedSshResponse(id: request.id)
    }

    func approveSshRequest(
        id: String,
        remember: SshApprovalRememberChoice,
        importDisplayName: String,
        importPassphrase: String? = nil
    ) async throws {
        SshKeyProviderStore.expireDueRequests()
        guard let request = SshKeyProviderStore.request(id: id), request.status == .pendingReview else {
            bumpSshKeyProviderRevision()
            throw SshRuntimeError.message(SshRuntimeErrorText.requestNotPending)
        }
        switch request.kind {
        case .sign:
            try await approveSshSign(request, remember: remember, matchedAuthorization: nil)
        case .importKey:
            try await approveSshImport(
                request,
                displayName: try validatedSshDisplayName(importDisplayName),
                passphrase: importPassphrase
            )
        }
    }

    // MARK: Reconciliation and durable response delivery

    func reconcileSshKeyProvider() async {
        SshKeyProviderStore.expireDueRequests()
        settlePendingManagedSshKeyDeletions()
        var inventoryChanged = reconcileManagedSshKeyCatalog()
        let now = NotiSyncEngine.nowMillis()
        let expiredKeys = SshKeyProviderStore.snapshot().keys.filter { ($0.expiresAt ?? Int64.max) <= now }
        for key in expiredKeys {
            inventoryChanged = SshKeyProviderStore.removeKey(id: key.id) || inventoryChanged
        }
        settlePendingManagedSshKeyDeletions()
        SshKeyProviderStore.deleteOrphanedPendingSecrets()
        let pending = SshKeyProviderStore.pendingRequests()
        if UIApplication.shared.isProtectedDataAvailable {
            for request in pending where request.status == .pendingReview && request.kind == .sign {
                _ = await autoApproveRememberedSshRequest(request)
            }
        }
        for request in SshKeyProviderStore.pendingRequests() where request.status == .responsePendingSend {
            _ = await sendPersistedSshResponse(id: request.id)
        }
        await clearObsoleteSshReviewNotifications()
        if inventoryChanged { await broadcastSshSnapshot() }
        bumpSshKeyProviderRevision()
    }

    private func settlePendingManagedSshKeyDeletions() {
        for keyId in SshKeyProviderStore.pendingManagedKeyDeletions()
        where SshManagedKeyProvider.delete(keyId: keyId) {
            _ = SshKeyProviderStore.completeManagedKeyDeletion(id: keyId)
        }
    }

    /// Device-only managed Keychain items do not migrate with an App Group restore. Only a successful,
    /// unlocked enumeration is authoritative enough to remove stale public rows. Keychain-only records are
    /// retained for recovery: deleting them after public-state loss/corruption would destroy the sole key copy.
    private func reconcileManagedSshKeyCatalog() -> Bool {
        guard UIApplication.shared.isProtectedDataAvailable else { return false }
        guard case .available(let storedKeyIds) = SshManagedKeyProvider.storedKeyIds() else { return false }
        let stored = Set(storedKeyIds)
        let state = SshKeyProviderStore.snapshot()
        var changed = false

        for key in state.keys where !SshKeyProviderStore.validKeyRecord(key) ||
            (!key.isWebAuthn && !stored.contains(key.id)) {
            changed = SshKeyProviderStore.removeKey(id: key.id) || changed
        }
        return changed
    }

    @discardableResult
    func sendPersistedSshResponse(id: String) async -> Bool {
        guard let request = SshKeyProviderStore.request(id: id), request.status == .responsePendingSend,
              let encoded = SshKeyProviderStore.pendingResponse(id: id),
              let dataSync = try? ProtocolCodec.decodeDataSync(encoded),
              dataSync.kind == .SSH_AGENT, let sync = dataSync.sshAgent else { return false }
        let accepted = await sendSshSync(sync, to: request.requesterClientId)
        if accepted {
            _ = SshKeyProviderStore.markResponseSent(id: id)
            bumpSshKeyProviderRevision()
            if sshResponseChangesInventory(sync) { await broadcastSshSnapshot() }
        }
        return accepted
    }

    private func sshResponseChangesInventory(_ sync: SshAgentSync) -> Bool {
        switch sync.kind {
        case .SIGN_RESULT:
            return sync.signResult?.kind == .SIGNED
        case .IMPORT_RESULT:
            return sync.importResult?.kind == .IMPORTED
        default:
            return false
        }
    }

    private func postSshKeyProviderReviewNotification(
        for request: SshProviderRequestRecord,
        foregroundSheetPresented: Bool
    ) async {
        let notification = UNNotificationRequest(
            identifier: "notisync.ssh.\(request.id)",
            content: SshKeyProviderNotificationPresentation.content(
                for: request,
                foregroundSheetPresented: foregroundSheetPresented
            ),
            trigger: nil
        )
        try? await UNUserNotificationCenter.current().add(notification)
    }

    private func postSshAutoApprovedNotification(
        for request: SshProviderRequestRecord,
        key: SshProviderKeyRecord
    ) async {
        let notification = UNNotificationRequest(
            identifier: "notisync.ssh.audit.\(request.id)",
            content: SshKeyProviderNotificationPresentation.autoApprovedContent(for: request, key: key),
            trigger: nil
        )
        try? await UNUserNotificationCenter.current().add(notification)
    }

    private func clearSshReviewNotification(id: String) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ["notisync.ssh.\(id)"])
        let delivered = await center.deliveredNotifications()
        let identifiers = delivered.compactMap { notification -> String? in
            guard notification.request.content.categoryIdentifier ==
                    SshKeyProviderNotificationPresentation.categoryIdentifier,
                  notification.request.content.userInfo[
                      SshKeyProviderNotificationPresentation.requestIdUserInfoKey
                  ] as? String == id else { return nil }
            return notification.request.identifier
        }
        if !identifiers.isEmpty { center.removeDeliveredNotifications(withIdentifiers: identifiers) }
    }

    private func clearObsoleteSshReviewNotifications() async {
        let reviewIds = Set(SshKeyProviderStore.snapshot().requests.compactMap { request in
            request.status == .pendingReview ? request.id : nil
        })
        let center = UNUserNotificationCenter.current()
        let delivered = await center.deliveredNotifications()
        let deliveredIds = delivered.compactMap { notification -> String? in
            guard notification.request.content.categoryIdentifier ==
                    SshKeyProviderNotificationPresentation.categoryIdentifier,
                  let requestId = notification.request.content.userInfo[
                      SshKeyProviderNotificationPresentation.requestIdUserInfoKey
                  ] as? String,
                  !reviewIds.contains(requestId) else { return nil }
            return notification.request.identifier
        }
        if !deliveredIds.isEmpty { center.removeDeliveredNotifications(withIdentifiers: deliveredIds) }

        let pending = await center.pendingNotificationRequests()
        let pendingIds = pending.compactMap { request -> String? in
            guard request.content.categoryIdentifier == SshKeyProviderNotificationPresentation.categoryIdentifier,
                  let requestId = request.content.userInfo[
                      SshKeyProviderNotificationPresentation.requestIdUserInfoKey
                  ] as? String,
                  !reviewIds.contains(requestId) else { return nil }
            return request.identifier
        }
        if !pendingIds.isEmpty { center.removePendingNotificationRequests(withIdentifiers: pendingIds) }
    }

    // MARK: Approval implementation

    @discardableResult
    private func approveSshSign(
        _ request: SshProviderRequestRecord,
        remember: SshApprovalRememberChoice,
        matchedAuthorization: SshRememberedAuthorizationRecord?
    ) async throws -> Bool {
        guard let engine, let keyId = request.providerKeyId,
              let key = SshKeyProviderStore.key(id: keyId),
              let account = request.stagedSecretAccount,
              let signData = SshPendingSecretStore.load(account: account),
              let algorithmRaw = request.requestedSignatureAlgorithm,
              let flags = request.flags,
              let generation = request.authorizationGeneration,
              let epoch = request.authorizationEpoch else {
            throw SshRuntimeError.message(SshRuntimeErrorText.requestDataMissing)
        }
        guard epoch > SshKeyProviderStore.authorizationFloor(
            requesterClientId: request.requesterClientId,
            generation: generation
        ) else {
            throw SshRuntimeError.message(SshRuntimeErrorText.authorizationRevoked)
        }

        let expectedAlgorithm: SshSignatureAlgorithm
        switch SshKeyAlgorithm(rawValue: key.algorithm) {
        case .SSH_ED25519:
            guard flags == 0 else {
                try await failSshSign(request, code: .UNSUPPORTED_FLAGS)
                return false
            }
            expectedAlgorithm = .SSH_ED25519
        case .ECDSA_NISTP256:
            guard flags == 0 else {
                try await failSshSign(request, code: .UNSUPPORTED_FLAGS)
                return false
            }
            expectedAlgorithm = .ECDSA_NISTP256
        case .WEBAUTHN_SK_ECDSA_NISTP256:
            guard flags == 0 else {
                try await failSshSign(request, code: .UNSUPPORTED_FLAGS)
                return false
            }
            expectedAlgorithm = .WEBAUTHN_SK_ECDSA_NISTP256
        case .SSH_RSA:
            switch flags {
            case 2: expectedAlgorithm = .RSA_SHA2_256
            case 4: expectedAlgorithm = .RSA_SHA2_512
            default:
                try await failSshSign(request, code: .UNSUPPORTED_FLAGS)
                return false
            }
        case nil:
            try await failSshSign(request, code: .UNSUPPORTED_ALGORITHM)
            return false
        }
        guard algorithmRaw == expectedAlgorithm.rawValue else {
            try await failSshSign(request, code: .UNSUPPORTED_ALGORITHM)
            return false
        }

        let signatureBlob: Data
        var disposition: SshRememberDisposition
        if let matchedAuthorization {
            disposition = matchedAuthorization.scope == SshRememberScope.PEER_HOST_KEY.rawValue
                ? .MATCHED_PEER_HOST_KEY : .MATCHED_PEER
        } else {
            disposition = .NONE
        }
        var rememberedAuthorization: SshRememberedAuthorizationRecord?
        var signingKeyUpdate: SshProviderKeyRecord?
        if key.isWebAuthn {
            let credential = try passkeyCredential(from: key)
            let assertion = try await sshPasskeyProvider.sign(
                credential: credential,
                challenge: signData,
                presentationAnchor: try sshPresentationAnchor()
            )
            signatureBlob = assertion.signatureBlob
            var updated = key
            updated.backupEligible = assertion.backupEligible
            updated.backupState = assertion.backupState
            signingKeyUpdate = updated
            disposition = .NONE
        } else {
            guard let algorithm = SshManagedSignatureAlgorithm(rawValue: expectedAlgorithm.rawValue),
                  algorithm != .rsaSHA1Legacy else {
                try await failSshSign(request, code: .UNSUPPORTED_ALGORITHM)
                return false
            }
            do {
                signatureBlob = try await Task.detached(priority: .userInitiated) {
                    try SshManagedKeyProvider.sign(keyId: key.id, algorithm: algorithm, data: signData)
                }.value
            } catch SshManagedKeyProviderError.keyNotFound {
                try await failSshSign(request, code: .KEY_INVALIDATED)
                return false
            } catch SshManagedKeyProviderError.storageUnavailable {
                try await failSshSign(request, code: .PROVIDER_BUSY, retryable: true)
                return false
            } catch {
                try await failSshSign(request, code: .INTERNAL_FAILURE)
                return false
            }
            if matchedAuthorization == nil, !request.confirmationRequired {
                let prepared = preparedRememberedAuthorization(for: request, key: key, choice: remember)
                disposition = prepared.disposition
                rememberedAuthorization = prepared.authorization
            }
        }

        let result = SshSignResult(
            requestId: request.id,
            requesterClientId: request.requesterClientId,
            publicKeyBlobSha256: request.publicKeyBlobSha256 ?? NSHash.sha256(key.publicKeyBlob),
            kind: .SIGNED,
            resultAt: NotiSyncEngine.nowMillis(),
            providerClientId: engine.selfClientId,
            signature: SshSignatureResult(
                signatureBlob: signatureBlob,
                rememberDisposition: disposition,
                authorizationGeneration: generation,
                authorizationEpoch: epoch
            ),
            rejection: nil,
            failure: nil
        )
        try queueSshResponse(
            request: request,
            sync: SshAgentSync(kind: .SIGN_RESULT, signResult: result),
            outcome: .signed,
            rememberedAuthorization: rememberedAuthorization,
            expectedMatchedAuthorization: matchedAuthorization,
            signingKeyUpdate: signingKeyUpdate,
            observeVerifiedKnownHost: matchedAuthorization == nil,
            approvalDisposition: disposition.rawValue
        )
        bumpSshKeyProviderRevision()
        await clearSshReviewNotification(id: request.id)
        _ = await sendPersistedSshResponse(id: request.id)
        return true
    }

    private func approveSshImport(
        _ request: SshProviderRequestRecord,
        displayName: String,
        passphrase: String?
    ) async throws {
        guard let engine, let account = request.stagedSecretAccount,
              let secret = SshPendingSecretStore.load(account: account) else {
            throw SshRuntimeError.message(SshRuntimeErrorText.privateKeyDataMissing)
        }
        let material: SshManagedKeyMaterial
        let origin: SshKeyOrigin
        switch request.importSourceType {
        case SshImportSourceType.PRIVATE_KEY_FILE.rawValue:
            material = try await Task.detached(priority: .userInitiated) {
                try SshPrivateKeyFileParser.parse(secret, passphrase: passphrase)
            }.value
            origin = .DATA_SYNC_FILE
        case SshImportSourceType.AGENT_IDENTITY.rawValue:
            let constrained = request.importLifetimeSeconds != nil || request.confirmationRequired
            let parsed = try await Task.detached(priority: .userInitiated) {
                try SshPrivateKeyFileParser.parseAgentIdentity(secret, constrained: constrained)
            }.value
            guard parsed.constraints.lifetimeSeconds.map(Int64.init) == request.importLifetimeSeconds,
                  parsed.constraints.confirmationRequired == request.confirmationRequired else {
                throw SshRuntimeError.message(SshRuntimeErrorText.constraintMismatch)
            }
            material = parsed.material
            origin = .AGENT_ADD
        default:
            throw SshRuntimeError.message(SshRuntimeErrorText.unsupportedRemoteImport)
        }

        let resultKind: SshImportResultKind
        let keyId: String
        var importedKey: SshProviderKeyRecord?
        var existingImportKey: SshProviderKeyRecord?
        if let existing = SshKeyProviderStore.key(publicKeyBlob: material.publicKeyBlob) {
            resultKind = .ALREADY_PRESENT
            keyId = existing.id
            existingImportKey = existing
        } else {
            let expiresAt = request.importLifetimeSeconds.map {
                NotiSyncEngine.nowMillis() + min($0, 7 * 24 * 60 * 60) * 1_000
            }
            let record = try prepareManagedSshKey(
                material,
                displayName: displayName,
                origin: origin,
                approvalPolicy: request.confirmationRequired ? .ALWAYS_ASK : .ALLOW_REMEMBER,
                expiresAt: expiresAt
            )
            importedKey = record
            keyId = record.id
            resultKind = .IMPORTED
        }
        let result = SshImportResult(
            requestId: request.id,
            requesterClientId: request.requesterClientId,
            providerClientId: engine.selfClientId,
            resultAt: NotiSyncEngine.nowMillis(),
            kind: resultKind,
            providerKeyId: keyId,
            publicKeyBlob: material.publicKeyBlob,
            message: nil
        )
        do {
            try queueSshResponse(
                request: request,
                sync: SshAgentSync(kind: .IMPORT_RESULT, importResult: result),
                outcome: resultKind == .IMPORTED ? .imported : .alreadyPresent,
                importedKey: importedKey,
                existingImportKey: existingImportKey
            )
        } catch {
            if let importedKey { _ = SshManagedKeyProvider.delete(keyId: importedKey.id) }
            throw error
        }
        bumpSshKeyProviderRevision()
        await clearSshReviewNotification(id: request.id)
        _ = await sendPersistedSshResponse(id: request.id)
    }

    private func failSshSign(
        _ request: SshProviderRequestRecord,
        code: SshProviderFailureCode,
        retryable: Bool = false
    ) async throws {
        guard let providerClientId = engine?.selfClientId else {
            throw SshRuntimeError.message(SshRuntimeErrorText.notReady)
        }
        let result = SshSignResult(
            requestId: request.id,
            requesterClientId: request.requesterClientId,
            publicKeyBlobSha256: request.publicKeyBlobSha256 ?? Data(repeating: 0, count: 32),
            kind: .PROVIDER_FAILURE,
            resultAt: NotiSyncEngine.nowMillis(),
            providerClientId: providerClientId,
            signature: nil,
            rejection: nil,
            failure: SshProviderFailure(code: code, retryable: retryable, message: nil)
        )
        try queueSshResponse(
            request: request,
            sync: SshAgentSync(kind: .SIGN_RESULT, signResult: result),
            outcome: .failed
        )
        bumpSshKeyProviderRevision()
        await clearSshReviewNotification(id: request.id)
        _ = await sendPersistedSshResponse(id: request.id)
    }

    private func autoApproveRememberedSshRequest(_ request: SshProviderRequestRecord) async -> Bool {
        guard UIApplication.shared.isProtectedDataAvailable,
              !request.confirmationRequired, let keyId = request.providerKeyId,
              let key = SshKeyProviderStore.key(id: keyId), !key.isWebAuthn,
              key.approvalPolicy == SshApprovalPolicy.ALLOW_REMEMBER.rawValue,
              let generation = request.authorizationGeneration,
              let epoch = request.authorizationEpoch,
              let authorization = SshKeyProviderStore.rememberedAuthorization(
                  providerKeyId: keyId,
                  requesterClientId: request.requesterClientId,
                  generation: generation,
                  epoch: epoch,
                  hostKeyBlobSha256: request.destination?.serverHostKeyBlobSha256,
                  destinationProvenance: request.destination?.provenance
              ) else { return false }
        do {
            guard try await approveSshSign(request, remember: .none, matchedAuthorization: authorization) else {
                return false
            }
            await postSshAutoApprovedNotification(for: request, key: key)
            return true
        } catch {
            return false
        }
    }

    private func preparedRememberedAuthorization(
        for request: SshProviderRequestRecord,
        key: SshProviderKeyRecord,
        choice: SshApprovalRememberChoice
    ) -> (disposition: SshRememberDisposition, authorization: SshRememberedAuthorizationRecord?) {
        guard key.approvalPolicy == SshApprovalPolicy.ALLOW_REMEMBER.rawValue,
              let generation = request.authorizationGeneration,
              let epoch = request.authorizationEpoch else { return (.NOT_ALLOWED_FOR_KEY, nil) }
        let scope: SshRememberScope
        let disposition: SshRememberDisposition
        let hostDigest: Data?
        switch choice {
        case .none:
            return (.NONE, nil)
        case .peer:
            scope = .PEER
            disposition = .CREATED_PEER
            hostDigest = nil
        case .peerAndHost:
            guard request.destination?.provenance == SshDestinationProvenance.VERIFIED_SESSION_BIND.rawValue,
                  let digest = request.destination?.serverHostKeyBlobSha256 else { return (.NONE, nil) }
            scope = .PEER_HOST_KEY
            disposition = .CREATED_PEER_HOST_KEY
            hostDigest = digest
        }
        let hostToken = hostDigest?.base64EncodedString() ?? "-"
        let idDigest = NSHash.sha256(Data(
            "\(key.id)|\(request.requesterClientId)|\(generation)|\(epoch)|\(scope.rawValue)|\(hostToken)".utf8
        ))
        let id = idDigest.prefix(16).map { String(format: "%02x", $0) }.joined()
        let authorization = SshRememberedAuthorizationRecord(
            id: id,
            providerKeyId: key.id,
            requesterClientId: request.requesterClientId,
            authorizationGeneration: generation,
            authorizationEpoch: epoch,
            scope: scope.rawValue,
            hostKeyBlobSha256: hostDigest,
            createdAt: NotiSyncEngine.nowMillis()
        )
        return (disposition, authorization)
    }

    private func queueSshResponse(
        request: SshProviderRequestRecord,
        sync: SshAgentSync,
        outcome: SshProviderRequestOutcome,
        rememberedAuthorization: SshRememberedAuthorizationRecord? = nil,
        expectedMatchedAuthorization: SshRememberedAuthorizationRecord? = nil,
        signingKeyUpdate: SshProviderKeyRecord? = nil,
        importedKey: SshProviderKeyRecord? = nil,
        existingImportKey: SshProviderKeyRecord? = nil,
        observeVerifiedKnownHost: Bool = false,
        approvalDisposition: String? = nil
    ) throws {
        let encoded = ProtocolCodec.encode(DataSync(kind: .SSH_AGENT, sshAgent: sync))
        guard SshKeyProviderStore.markResponsePending(
            id: request.id,
            outcome: outcome,
            encodedResponse: encoded,
            rememberedAuthorization: rememberedAuthorization,
            expectedMatchedAuthorization: expectedMatchedAuthorization,
            signingKeyUpdate: signingKeyUpdate,
            importedKey: importedKey,
            existingImportKey: existingImportKey,
            observeVerifiedKnownHost: observeVerifiedKnownHost,
            approvalDisposition: approvalDisposition
        ) else {
            throw SshRuntimeError.message(SshRuntimeErrorText.responseSave)
        }
    }

    // MARK: Inventory

    private func sendSshSnapshot(to recipientId: String, respondingTo requestId: String?) async -> Bool {
        guard let engine else { return false }
        let state = SshKeyProviderStore.snapshot()
        let now = NotiSyncEngine.nowMillis()
        let candidates = state.keys
            .filter { ($0.expiresAt ?? Int64.max) > now }
            .sorted { $0.createdAt == $1.createdAt ? $0.id < $1.id : $0.createdAt < $1.createdAt }
        var descriptors: [SshKeyDescriptor] = []
        var providerKeyIds = Set<String>()
        var publicKeyDigests = Set<Data>()
        for key in candidates {
            guard descriptors.count < SshKeyProviderStore.maximumInventoryKeys,
                  !providerKeyIds.contains(key.id),
                  !publicKeyDigests.contains(key.publicKeyBlobSha256),
                  let descriptor = sshDescriptor(for: key, state: state) else { continue }
            providerKeyIds.insert(key.id)
            publicKeyDigests.insert(key.publicKeyBlobSha256)
            descriptors.append(descriptor)
        }
        let snapshot = SshKeysSnapshot(
            providerClientId: engine.selfClientId,
            inventoryGeneration: state.inventoryGeneration,
            revision: state.inventoryRevision,
            generatedAt: now,
            respondingToRequestId: requestId,
            keys: descriptors,
            providerHealth: .HEALTHY
        )
        return await sendSshSync(
            SshAgentSync(kind: .KEYS_SNAPSHOT, keysSnapshot: snapshot),
            to: recipientId
        )
    }

    private func broadcastSshSnapshot() async {
        guard let engine else { return }
        let recipients = engine.trustedPeers().filter {
            $0.isTrusted && $0.ownDevice && sshConsumerPeer($0.clientId, engine: engine)
        }.map(\.clientId)
        for recipient in recipients { _ = await sendSshSnapshot(to: recipient, respondingTo: nil) }
    }

    private func sshDescriptor(
        for key: SshProviderKeyRecord,
        state: SshProviderPersistentState
    ) -> SshKeyDescriptor? {
        guard SshKeyProviderStore.validKeyRecord(key),
              let algorithm = SshKeyAlgorithm(rawValue: key.algorithm),
              let origin = SshKeyOrigin(rawValue: key.origin),
              let provider = SshOperationalKeyProvider(rawValue: key.operationalProvider),
              let security = SshStorageSecurityLevel(rawValue: key.securityLevel),
              let approval = SshApprovalPolicy(rawValue: key.approvalPolicy) else { return nil }
        let groups = Dictionary(
            grouping: state.rememberedAuthorizations.filter {
                SshKeyProviderStore.validRememberedAuthorizationRecord($0, providerKeyId: key.id)
            },
            by: SshRememberedNamespaceIdentity.init
        )
        let remembered = groups.values.compactMap { rows -> SshRememberedNamespace? in
            guard let first = rows.first else { return nil }
            let scopes = rows.compactMap { SshRememberScope(rawValue: $0.scope) }
            guard !scopes.isEmpty else { return nil }
            return SshRememberedNamespace(
                requesterClientId: first.requesterClientId,
                authorizationGeneration: first.authorizationGeneration,
                authorizationEpoch: first.authorizationEpoch,
                scopes: Array(Set(scopes.map(\.rawValue))).compactMap(SshRememberScope.init(rawValue:)).sorted {
                    $0.rawValue < $1.rawValue
                }
            )
        }.sorted { lhs, rhs in
            (lhs.requesterClientId, lhs.authorizationGeneration, lhs.authorizationEpoch) <
                (rhs.requesterClientId, rhs.authorizationGeneration, rhs.authorizationEpoch)
        }.prefix(SshKeyProviderStore.maximumRememberedNamespacesPerKey)
        let webAuthn = key.isWebAuthn ? SshWebAuthnCredentialProtection(
            rpId: key.relyingPartyId ?? NotiSyncConfig.sshPasskeyRelyingPartyIdentifier,
            backupEligible: key.backupEligible ?? false,
            backupState: key.backupState ?? false
        ) : nil
        return SshKeyDescriptor(
            providerKeyId: key.id,
            publicKeyBlob: key.publicKeyBlob,
            publicKeyBlobSha256: key.publicKeyBlobSha256,
            algorithm: algorithm,
            displayName: key.displayName,
            origin: origin,
            operationalKey: SshOperationalKeyProtection(
                provider: provider,
                securityLevel: security,
                userVerificationPolicy: key.isWebAuthn ? .PER_USE : .NONE
            ),
            exportCopy: nil,
            approvalPolicy: approval,
            rememberedNamespaces: key.isWebAuthn ? [] : Array(remembered),
            createdAt: key.createdAt,
            webAuthn: webAuthn
        )
    }

    // MARK: Key records and protocol helpers

    @discardableResult
    private func persistManagedSshKey(
        _ material: SshManagedKeyMaterial,
        displayName: String,
        origin: SshKeyOrigin,
        approvalPolicy: SshApprovalPolicy = .ALLOW_REMEMBER,
        expiresAt: Int64? = nil
    ) throws -> String {
        if let existing = SshKeyProviderStore.key(publicKeyBlob: material.publicKeyBlob) { return existing.id }
        let record = try prepareManagedSshKey(
            material,
            displayName: displayName,
            origin: origin,
            approvalPolicy: approvalPolicy,
            expiresAt: expiresAt
        )
        guard SshKeyProviderStore.upsertKey(record) else {
            _ = SshManagedKeyProvider.delete(keyId: record.id)
            throw SshRuntimeError.message(SshRuntimeErrorText.catalogSave)
        }
        return record.id
    }

    private func prepareManagedSshKey(
        _ material: SshManagedKeyMaterial,
        displayName: String,
        origin: SshKeyOrigin,
        approvalPolicy: SshApprovalPolicy,
        expiresAt: Int64?
    ) throws -> SshProviderKeyRecord {
        let keyId = try SshManagedKeyProvider.randomKeyId()
        try SshManagedKeyProvider.store(material, keyId: keyId)
        return SshProviderKeyRecord(
            id: keyId,
            publicKeyBlob: material.publicKeyBlob,
            publicKeyBlobSha256: NSHash.sha256(material.publicKeyBlob),
            algorithm: material.algorithm.rawValue,
            displayName: displayName,
            origin: origin.rawValue,
            operationalProvider: SshOperationalKeyProvider.APPLE_KEYCHAIN.rawValue,
            securityLevel: SshStorageSecurityLevel.KEYCHAIN.rawValue,
            approvalPolicy: approvalPolicy.rawValue,
            createdAt: NotiSyncEngine.nowMillis(),
            expiresAt: expiresAt
        )
    }

    private func passkeyRecord(
        _ credential: SshPasskeyCredentialRecord,
        displayName: String,
        origin: SshKeyOrigin,
        recoveryRecordJSON: String
    ) throws -> SshProviderKeyRecord {
        SshProviderKeyRecord(
            id: try SshManagedKeyProvider.randomKeyId(),
            publicKeyBlob: credential.publicKeyBlob,
            publicKeyBlobSha256: NSHash.sha256(credential.publicKeyBlob),
            algorithm: SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256.rawValue,
            displayName: displayName,
            origin: origin.rawValue,
            operationalProvider: SshOperationalKeyProvider.APPLE_AUTHENTICATION_SERVICES_WEBAUTHN.rawValue,
            securityLevel: SshStorageSecurityLevel.CREDENTIAL_PROVIDER.rawValue,
            approvalPolicy: SshApprovalPolicy.ALWAYS_ASK.rawValue,
            createdAt: credential.createdAt,
            relyingPartyId: credential.relyingPartyID,
            credentialId: credential.credentialID,
            userHandle: credential.userHandle,
            cosePublicKey: credential.cosePublicKey,
            recoveryRecordJSON: recoveryRecordJSON,
            recoveryRecordSaved: false,
            backupEligible: credential.backupEligible,
            backupState: credential.backupState
        )
    }

    private func passkeyCredential(from key: SshProviderKeyRecord) throws -> SshPasskeyCredentialRecord {
        guard let credentialID = key.credentialId, let userHandle = key.userHandle,
              let relyingPartyID = key.relyingPartyId, let cosePublicKey = key.cosePublicKey else {
            throw SshRuntimeError.message(SshRuntimeErrorText.recoveryIncomplete)
        }
        return SshPasskeyCredentialRecord(
            credentialID: credentialID,
            userHandle: userHandle,
            relyingPartyID: relyingPartyID,
            cosePublicKey: cosePublicKey,
            publicKeyBlob: key.publicKeyBlob,
            displayName: key.displayName,
            createdAt: key.createdAt,
            backupEligible: key.backupEligible ?? false,
            backupState: key.backupState ?? false
        )
    }

    private func sendImmediateSshSignFailure(
        _ request: SshSignRequest,
        code: SshProviderFailureCode
    ) async -> Bool {
        guard let providerClientId = engine?.selfClientId else { return false }
        let result = SshSignResult(
            requestId: request.requestId,
            requesterClientId: request.requesterClientId,
            publicKeyBlobSha256: NSHash.sha256(request.publicKeyBlob),
            kind: .PROVIDER_FAILURE,
            resultAt: NotiSyncEngine.nowMillis(),
            providerClientId: providerClientId,
            signature: nil,
            rejection: nil,
            failure: SshProviderFailure(code: code, retryable: false, message: nil)
        )
        return await sendSshSync(
            SshAgentSync(kind: .SIGN_RESULT, signResult: result),
            to: request.requesterClientId
        )
    }

    private func sendSshSync(_ sync: SshAgentSync, to recipientId: String) async -> Bool {
        guard let engine, let broker,
              let envelope = try? engine.sealSshAgentSync(sync, to: recipientId) else { return false }
        return (try? await broker.send(envelope, urgency: .NORMAL)) == true
    }

    private func sshConsumerPeer(_ clientId: String, engine: NotiSyncEngine) -> Bool {
        guard let peer = engine.trustedPeers().first(where: { $0.clientId == clientId }) else { return false }
        let capabilities = Set(peer.announcedCapabilities)
        return peer.isTrusted && peer.ownDevice && capabilities.contains(.CAPABILITY_ROUTING_V1) &&
            capabilities.contains(.SSH_AGENT_V1)
    }

    private func sshFresh(requestedAt: Int64, expiresAt: Int64, envelopeCreatedAt: Int64) -> Bool {
        let now = NotiSyncEngine.nowMillis()
        return requestedAt <= now + 120_000 && now <= expiresAt &&
            (envelopeCreatedAt <= 0 || sshTimestampsWithinSkew(envelopeCreatedAt, requestedAt))
    }

    private func sshEventFresh(at: Int64, envelopeCreatedAt: Int64) -> Bool {
        let now = NotiSyncEngine.nowMillis()
        return at > 0 && at <= now + 120_000 &&
            (envelopeCreatedAt <= 0 || sshTimestampsWithinSkew(envelopeCreatedAt, at))
    }

    private func sshTimestampsWithinSkew(_ lhs: Int64, _ rhs: Int64) -> Bool {
        let lower = rhs > 120_000 ? rhs - 120_000 : 0
        let upper = rhs <= Int64.max - 120_000 ? rhs + 120_000 : Int64.max
        return lhs >= lower && lhs <= upper
    }

    private func validatedSshDisplayName(_ value: String) throws -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= 256,
              !trimmed.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw SshRuntimeError.message(SshRuntimeErrorText.invalidKeyName)
        }
        return trimmed
    }

    private func sshPresentationAnchor() throws -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        if let window = scenes.lazy.flatMap(\.windows).first(where: \.isKeyWindow) ??
            scenes.lazy.flatMap(\.windows).first {
            return window
        }
        throw SshRuntimeError.message(SshRuntimeErrorText.providerUnavailable)
    }
}
