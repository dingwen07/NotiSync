import Foundation
import SwiftData

private enum ExperienceModeError: LocalizedError {
    case runtimeNotReady
    case missingPairingURL

    var errorDescription: String? {
        switch self {
        case .runtimeNotReady:
            return String(localized: "error.experience.runtimeNotReady", defaultValue: "NotiSync is still starting up.", comment: "Error shown when Experience Mode starts before the runtime is ready.")
        case .missingPairingURL:
            return String(localized: "error.experience.missingPairingURL", defaultValue: "Could not build this device's pairing link.", comment: "Error shown when Experience Mode cannot build a local pairing link.")
        }
    }
}

/// Mesh identity & keys: self-heal key-epochs we can't seal to, run the key-rotation lifecycle (#8 — mint →
/// pre-warm → activate → retire), and drive pairing + trust (accept/approve/reject/revoke, roster + profile
/// broadcast). All of this is own-mesh convergence: keeping the user's devices mutually reachable and named.
extension NotiSyncRuntime {

    // MARK: Key-epoch convergence (self-heal peers we can't seal to)

    func convergeKeyEpochs() async {
        guard let engine else { return }
        let ids = await Task.detached(priority: .utility) {
            engine.peersNeedingKeyEpoch()
        }.value
        for id in ids { await refetchKeyEpoch(id) }
    }

    func refetchKeyEpoch(_ clientId: String) async {
        guard let engine, let broker, let blob = await broker.fetchKeyEpoch(clientId: clientId) else { return }
        let applied = await Task.detached(priority: .utility) {
            engine.applyFetchedKeyEpoch(blob)
        }.value
        if applied { refreshPeerRows() }
    }

    // MARK: Key rotation (#8 — mint → pre-warm → activate → retire)

    /// Periodic upkeep (foreground + background): initiate a rotation when the live epoch is older than the
    /// cadence, advance any in-flight rotation across its boundaries, and GC stale (retired) keys.
    func rotationMaintenance() async {
        guard let engine else { return }
        let state = await Task.detached(priority: .utility) {
            (activatedAt: engine.selfActivatedAt(), hasPending: engine.pendingRotation() != nil)
        }.value
        if state.activatedAt > 0,
           NotiSyncEngine.nowMillis() - state.activatedAt >= NotiSyncConfig.Rotation.intervalMs,
           !state.hasPending {
            _ = await beginRotation()
        }
        await tickRotation()
        await Task.detached(priority: .utility) {
            engine.gcStaleEpochs()
        }.value
        await refreshRotationInfoAsync()
    }

    /// Begin a rotation if none is pending: mint N+1, re-publish N with a finite `notAfter` (retire after the
    /// overlap), publish + pre-warm N+1 (future `notBefore`, floor still N), persist the pending state.
    @discardableResult
    func beginRotation(leadMillis: Int64? = nil) async -> Int? {
        guard let engine, let broker, engine.pendingRotation() == nil else { return nil }
        let n = engine.epoch
        let target = n + 1
        do {
            try engine.ensureEpochKeys(n)
            try engine.ensureEpochKeys(target)
            let now = NotiSyncEngine.nowMillis()
            let nb = now + (leadMillis ?? NotiSyncConfig.Rotation.leadMs)
            let retireNotAfter = nb + NotiSyncConfig.Rotation.overlapMs
            let targetNotAfter = retireNotAfter + NotiSyncConfig.Rotation.lifetimeMs
            // Re-publish N with a finite notAfter (floor stays N: minEpoch = n) so the broker GC + peers retire it.
            try await broker.publishKeyEpoch(engine.buildKeyEpochBlob(epoch: n, notBefore: 0, notAfter: retireNotAfter, minEpoch: n))
            // Publish + pre-warm N+1 (future notBefore; minEpoch still n so it doesn't yet raise the floor).
            let nextBlob = try engine.buildKeyEpochBlob(epoch: target, notBefore: nb, notAfter: targetNotAfter, minEpoch: n)
            try await broker.publishKeyEpoch(nextBlob)
            if let env = try engine.sealKeyEpochAnnounce(nextBlob) { _ = try? await broker.send(env) }
            engine.setPendingRotation(SelfPendingRotation(
                targetEpoch: target, notBefore: nb, notAfter: targetNotAfter,
                retiredEpoch: n, retireRetiredAt: retireNotAfter + NotiSyncConfig.Rotation.graceMs))
            addActivity(.route, .rotationStarted, detail: .toEpoch, detailNum: Int(target))
            return target
        } catch {
            record(error: error, domain: .rotation)
            return nil
        }
    }

    /// Advance an in-flight rotation as wall-clock crosses each boundary (idempotent). ACTIVATE at `notBefore`
    /// (start signing with N+1; N still accepted). RETIRE at `retireRetiredAt` (raise the floor to N+1 and
    /// destroy N's keys).
    func tickRotation() async {
        guard let engine, let broker, let p = engine.pendingRotation() else { return }
        let now = NotiSyncEngine.nowMillis()
        if now >= p.notBefore, engine.epoch < p.targetEpoch {
            engine.advanceSelfEpoch(to: p.targetEpoch)
            _ = try? await broker.publishKeyEpoch(engine.buildKeyEpochBlob(
                epoch: p.targetEpoch, notBefore: p.notBefore, notAfter: p.notAfter, minEpoch: p.retiredEpoch))
            addActivity(.route, .rotationActivated, detail: .epoch, detailNum: Int(p.targetEpoch))
        }
        if now >= p.retireRetiredAt {
            _ = try? await broker.publishKeyEpoch(engine.buildKeyEpochBlob(
                epoch: p.targetEpoch, notBefore: p.notBefore, notAfter: p.notAfter, minEpoch: p.targetEpoch))
            engine.destroyEpoch(p.retiredEpoch)
            engine.setPendingRotation(nil)
            addActivity(.route, .rotationRetired, detail: .epoch, detailNum: Int(p.retiredEpoch))
        }
    }

    /// Diagnostics: force a rotation now (lead 0 → activate immediately on the following tick). Retirement of
    /// the old epoch still waits the full overlap + grace, so in-flight notifications aren't dropped.
    func rotateNow() {
        Task {
            guard let target = await beginRotation(leadMillis: 0) else { await refreshRotationInfoAsync(); return }
            await tickRotation()
            addActivity(.route, .rotatedDebug, detail: .nowEpoch, detailNum: Int(target))
            await refreshRotationInfoAsync()
        }
    }

    func refreshRotationInfo() {
        Task { await refreshRotationInfoAsync() }
    }

    func refreshRotationInfoAsync() async {
        guard let engine else {
            rotationInfo = nil
            return
        }
        rotationInfo = await Task.detached(priority: .utility) {
            engine.rotationKeyInfo()
        }.value
    }

    // MARK: Pairing

    func makePairingPayload() {
        Task { await makePairingPayloadAsync() }
    }

    func makePairingPayloadAsync() async {
        guard let engine else { return }
        let displayName = settings().deviceName
        if let payload = await Task.detached(priority: .userInitiated, operation: {
            try? engine.pairingPayload(displayName: displayName)
        }).value {
            pairingPayload = PairingLinks.link(payload: payload)
        }
    }

    func inspectPairing(_ scanned: String) -> PairingCandidate? {
        try? engine?.inspectPairing(scanned)
    }

    func inspectPairingAsync(_ scanned: String) async -> PairingCandidate? {
        guard let engine else { return nil }
        return await Task.detached(priority: .userInitiated) {
            try? engine.inspectPairing(scanned)
        }.value
    }

    /// Handle a pairing deep link (the custom `notisync://pair` scheme or the universal `/pair` link). Verifies
    /// the payload and surfaces the candidate for the user to confirm — pairing is never automatic.
    func handlePairingURL(_ url: URL) {
        guard PairingLinks.isPairing(url) else { return }
        Task {
            incomingPairing = await inspectPairingAsync(url.absoluteString)
        }
    }

    func acceptPairing(_ scanned: String, ownDevice: Bool = true) {
        Task {
            do {
                _ = try await acceptPairingAndSync(scanned, ownDevice: ownDevice)
            } catch {
                record(error: error, domain: .pairing)
            }
        }
    }

    func startExperienceMode() async -> Bool {
        guard let broker else {
            record(error: ExperienceModeError.runtimeNotReady, domain: .pairing)
            return false
        }
        if pairingPayload == nil { await makePairingPayloadAsync() }
        guard let pairingUrl = pairingPayload else {
            record(error: ExperienceModeError.missingPairingURL, domain: .pairing)
            return false
        }
        do {
            let demoPairingUrl = try await broker.startDemoExperience(pairingUrl: pairingUrl)
            _ = try await acceptPairingAndSync(demoPairingUrl, ownDevice: true)
            startForegroundWebSocket()
            return true
        } catch {
            record(error: error, domain: .pairing)
            return false
        }
    }

    @discardableResult
    private func acceptPairingAndSync(_ scanned: String, ownDevice: Bool) async throws -> String {
        guard let engine else { throw ExperienceModeError.runtimeNotReady }
        let name = try await Task.detached(priority: .userInitiated) {
            try engine.acceptPairing(scanned, ownDevice: ownDevice)
        }.value
        addActivity(.paired, .paired, detail: .text, detailArg: name)
        refreshPeerRows()
        settings().pairingStatusValue = .paired
        try? modelContext?.save()
        await publishCurrentRoute()
        await broadcastTrust()   // #5 — announce the new device to the rest of the own-mesh
        return name
    }

    /// #6 — locally revoke a peer, mark its Devices row, and broadcast the revocation to the own-mesh so it
    /// propagates (a revoked device stops being trusted / delivered to across all of the user's devices).
    func revokePeer(clientId: String) {
        guard let engine, engine.revoke(clientId) else { return }
        if let row = fetchDevice(clientId: clientId) {
            row.status = .revoked
            row.updatedAt = .now
            try? modelContext?.save()
        }
        addActivity(.paired, .revoked, detail: .text, detailArg: clientId)
        Task { await broadcastTrust() }
    }

    /// #5/#3 — broadcast this device's trust roster to its own-mesh (identity-signed), plus the signed cards
    /// of every trusted device so a peer can NAME (and then approve) a device this device introduces.
    func broadcastTrust() async {
        guard let engine, let broker else { return }
        do {
            if let env = try engine.sealTrustTable() { try await broker.send(env) }
            for env in try engine.sealTrustCards() { _ = try? await broker.send(env) }
        } catch {
            record(error: error, domain: .trustBroadcast)
        }
    }

    /// #3 — approve a mesh-introduced pending device (→ TRUSTED), then converge its keys so it becomes
    /// reachable, and re-broadcast our roster so the rest of the mesh learns our decision.
    func approveTrust(clientId: String) {
        guard let engine, engine.approveTrust(clientId) else { return }
        addActivity(.paired, .approved, detail: .text,
                    detailArg: engine.trustedPeers().first { $0.clientId == clientId }?.displayName ?? clientId)
        refreshPeerRows()
        Task {
            await refetchKeyEpoch(clientId)   // pull the now-trusted device's key-epoch so it's reachable
            await broadcastTrust()
        }
    }

    /// #3 — reject a pending introduction (→ REVOKED) and propagate the overturn.
    func rejectTrust(clientId: String) {
        guard let engine, engine.rejectTrust(clientId) else { return }
        if let row = fetchDevice(clientId: clientId) { row.status = .revoked; row.updatedAt = .now; try? modelContext?.save() }
        addActivity(.paired, .rejected, detail: .text, detailArg: clientId)
        refreshPeerRows()
        Task { await broadcastTrust() }
    }

    /// #3 — confirm a peer's revoke of a device we trusted (→ REVOKED). Silent (anti-entropy carries it).
    func confirmRevoke(clientId: String) {
        guard let engine, engine.confirmRevoke(clientId) else { return }
        if let row = fetchDevice(clientId: clientId) { row.status = .revoked; row.updatedAt = .now; try? modelContext?.save() }
        addActivity(.paired, .revokeConfirmed, detail: .text, detailArg: clientId)
        refreshPeerRows()
    }

    /// #3 — keep a device whose revoke (suggested by a peer) we reject (→ TRUSTED). Propagate the overturn.
    func keepTrusted(clientId: String) {
        guard let engine, engine.keepTrusted(clientId) else { return }
        addActivity(.paired, .keptTrusted, detail: .text, detailArg: clientId)
        refreshPeerRows()
        Task { await broadcastTrust() }
    }

    /// #5 — broadcast this device's profile (name) to every trusted device so peers converge the rename.
    func broadcastProfile(displayName: String? = nil, updatedAt: Int64 = NotiSyncEngine.nowMillis()) async {
        guard let engine, let broker else { return }
        let name = displayName ?? settings().deviceName
        let result = await Task.detached(priority: .utility) {
            try await Self.sendProfile(displayName: name, updatedAt: updatedAt, engine: engine, broker: broker)
        }.result
        if case let .failure(error) = result {
            record(error: error, domain: .profileBroadcast)
        }
    }

    private nonisolated static func sendProfile(
        displayName: String,
        updatedAt: Int64,
        engine: NotiSyncEngine,
        broker: BrokerClient
    ) async throws {
        if let env = try engine.sealProfileUpdate(displayName: displayName, updatedAt: updatedAt) {
            try await broker.send(env)
        }
    }
}
