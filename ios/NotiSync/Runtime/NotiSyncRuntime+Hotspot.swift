import Foundation

extension NotiSyncRuntime {
    func replaceHotspotProviderIds(_ ids: Set<String>) {
        hotspotProviderIds = ids
        remoteHotspots = remoteHotspots.filter { ids.contains($0.key) }
        for id in Array(hotspotRequestTasks.keys) where !ids.contains(id) {
            hotspotRequestTasks.removeValue(forKey: id)?.cancel()
        }
        // Credentials are intentionally retained; permission revocation cannot erase a shared password.
    }

    func openHotspotControls(_ deviceId: String) async {
        do {
            let saved = try await HotspotCredentialStore.shared.load(deviceId)
            hotspotStorageFailures.remove(deviceId)
            if let saved, saved.updatedAt >= (savedHotspots[deviceId]?.updatedAt ?? 0) {
                savedHotspots[deviceId] = saved
            }
        } catch {
            hotspotStorageFailures.insert(deviceId)
        }
        let state = remoteHotspots[deviceId] ?? RemoteHotspotState()
        if state.needsRefresh(now: Self.hotspotNow) {
            requestHotspot(deviceId, action: .REFRESH)
        }
    }

    func refreshHotspot(_ deviceId: String) { requestHotspot(deviceId, action: .QUERY) }

    func setHotspotEnabled(_ enabled: Bool, deviceId: String) {
        requestHotspot(deviceId, action: .SET_ENABLED, enabled: enabled)
    }

    private func requestHotspot(_ deviceId: String, action: HotspotAction, enabled: Bool? = nil) {
        guard hotspotProviderIds.contains(deviceId), let engine, let broker else { return }
        var state = remoteHotspots[deviceId] ?? RemoteHotspotState()
        guard state.pendingRequest == nil else { return }
        let waitForReply = action != .REFRESH
        let id = UUID().uuidString
        if waitForReply {
            state.pendingRequest = id
            state.failure = nil
            remoteHotspots[deviceId] = state
        }
        lastHotspotIssuedAt = max(Self.hotspotNow, lastHotspotIssuedAt + 1)
        let sync = HotspotSync(action: action, hotspotDeviceId: deviceId,
                               issuedAt: lastHotspotIssuedAt, expiresAt: lastHotspotIssuedAt + 60_000,
                               requestId: id, enabled: enabled)
        let task = Task { [weak self] in
            guard let self else { return }
            let sent: Bool
            do {
                let envelope = try await Task.detached(priority: .userInitiated) {
                    try engine.sealHotspotSync(sync)
                }.value
                guard !Task.isCancelled, self.hotspotProviderIds.contains(deviceId) else { return }
                if let envelope { sent = try await broker.send(envelope, urgency: .HIGH) }
                else { sent = false }
            } catch { sent = false }
            guard !Task.isCancelled else { return }
            if sent {
                let title: ActivityTitleToken = action == .SET_ENABLED
                    ? (enabled == true ? .hotspotOn : .hotspotOff) : .hotspotRefresh
                self.addActivity(.sent, title, detail: .text, detailArg: self.peerDisplayName(deviceId))
            }
            // REFRESH is a hint. The provider may suppress it during its 10-second broadcast cooldown.
            guard waitForReply else { return }
            if sent {
                do { try await Task.sleep(for: .seconds(45)) } catch { return }
            }
            guard var current = self.remoteHotspots[deviceId],
                  current.finish(requestId: id, failure: sent ? .TIMEOUT : .UNAVAILABLE) else { return }
            self.remoteHotspots[deviceId] = current
            self.hotspotRequestTasks.removeValue(forKey: deviceId)
            self.addActivity(.error, .hotspotRequestFailed, detail: .text, detailArg: self.peerDisplayName(deviceId))
        }
        if waitForReply { hotspotRequestTasks[deviceId] = task }
    }

    /// Called only after envelope authentication. Commit credentials before the delivery is acknowledged.
    func handleHotspotStatus(_ sync: HotspotSync, from signerId: String, envelopeCreatedAt: Int64) async -> Bool {
        guard sync.action == .STATUS, sync.hotspotDeviceId == signerId,
              sync.isValid(now: Self.hotspotNow, envelopeCreatedAt: envelopeCreatedAt), let engine else { return true }
        let authorized = await Task.detached(priority: .utility) { engine.isHotspotProvider(signerId) }.value
        guard authorized else { return true }
        do {
            if let snapshot = sync.snapshot, let saved = SavedHotspot(snapshot: snapshot, updatedAt: sync.issuedAt) {
                let persisted = try await HotspotCredentialStore.shared.save(saved, for: signerId)
                if persisted.updatedAt >= (savedHotspots[signerId]?.updatedAt ?? 0) {
                    savedHotspots[signerId] = persisted
                }
                hotspotStorageFailures.remove(signerId)
            }
        } catch {
            hotspotStorageFailures.insert(signerId)
            return false
        }
        // Trust may change while Keychain I/O is in flight. Do not restore a removed controller row.
        guard hotspotProviderIds.contains(signerId) else { return true }
        var state = remoteHotspots[signerId] ?? RemoteHotspotState()
        guard state.receive(sync) else { return true }
        remoteHotspots[signerId] = state
        if state.pendingRequest == nil { hotspotRequestTasks.removeValue(forKey: signerId)?.cancel() }
        addActivity(sync.result == .OK ? .received : .error, .hotspotStatus,
                    detail: .text, detailArg: peerDisplayName(signerId))
        return true
    }

    private static var hotspotNow: Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

    private func peerDisplayName(_ deviceId: String) -> String {
        fetchDevice(clientId: deviceId)?.displayName ?? deviceId
    }
}
