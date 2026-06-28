import Foundation
import UserNotifications

/// Dismissal sync: a local swipe (or Dismiss action) clears the mirror, acks the message, and — for a
/// clearable source — seals a DismissEvent to the mesh; a remote DismissEvent clears the local mirror. With
/// no swipe callback from iOS, `reconcileDismissals` diffs delivered notifications across two polls to infer
/// user dismissals.
extension NotiSyncRuntime {

    // MARK: Dismissal

    func locallyDismiss(sourceClientId: String, sourceKey: String) async {
        let entries = MirrorMapStore.entries(sourceClientId: sourceClientId, sourceKey: sourceKey)
        // #14 — a non-clearable (ongoing) source notification must not be cleared on the source by a local
        // swipe: remove the local copy but skip the outbound DismissEvent.
        let clearable = entries.allSatisfy { $0.isClearable ?? true }
        removeMirrors(sourceClientId: sourceClientId, sourceKey: sourceKey)
        for entry in entries {
            await queueAndFlushAck(messageId: entry.messageId)
        }
        clearMirrors(sourceClientId: sourceClientId, sourceKey: sourceKey)
        guard clearable else {
            addActivity(.dismissed, .dismissedLocally, detail: .ongoingNotSynced)
            return
        }
        guard let engine, let broker else { return }
        // #4 — send-initiated key-epoch repair: pull a current key-epoch for any own-mesh peer we can't
        // currently seal to (e.g. it just rotated), so the dismissal reaches it instead of black-holing.
        await repairUnsealablePeers()
        do {
            if let env = try engine.sealDismissal(sourceClientId: sourceClientId, sourceKey: sourceKey) {
                try await broker.send(env)
                addActivity(.dismissed, .dismissed, detail: .syncedToMesh)
            } else {
                addActivity(.dismissed, .dismissed, detail: .noPeers)
            }
        } catch {
            record(error: error, domain: .dismissSync)
        }
    }

    /// #4 — refetch the current key-epoch for every trusted peer we hold no usable epoch for, so an
    /// attempted send (e.g. a dismissal) can reach a peer that has rotated since we last converged.
    private func repairUnsealablePeers() async {
        guard let engine else { return }
        for id in engine.peersNeedingKeyEpoch() { await refetchKeyEpoch(id) }
    }

    func removeRemoteDismissal(_ d: DismissEvent) {
        removeMirrors(sourceClientId: d.sourceClientId, sourceKey: d.sourceKey)
        clearMirrors(sourceClientId: d.sourceClientId, sourceKey: d.sourceKey)
        addActivity(.dismissed, .remoteDismissal, detail: .text, detailArg: d.sourceClientId)
    }

    /// Remove every delivered/pending mirror for a source (NSE- and app-posted), echo-marking each so the
    /// reconciler doesn't read the removal back as a user dismissal.
    private func removeMirrors(sourceClientId: String, sourceKey: String) {
        let ids = MirrorMapStore.entries(sourceClientId: sourceClientId, sourceKey: sourceKey).map(\.identifier)
        for id in ids { ShownStore.markEchoRemoved(id) }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
        markDismissed(sourceClientId: sourceClientId, sourceKey: sourceKey)
    }

    private func clearMirrors(sourceClientId: String, sourceKey: String) {
        for entry in MirrorMapStore.entries(sourceClientId: sourceClientId, sourceKey: sourceKey) {
            MirrorMapStore.remove(identifier: entry.identifier)
        }
    }

    // MARK: Dismissal reconciliation (no swipe callback — diff getDeliveredNotifications)

    func reconcileDismissals() async {
        // Two-poll confirmation relies on time between passes; skip a pass that fires too soon after the last
        // (e.g. cold-launch configure() + the scenePhase .active both calling appBecameActive).
        guard Date().timeIntervalSince(lastReconcileAt) > 3 else { return }
        lastReconcileAt = .now
        let delivered = await UNUserNotificationCenter.current().deliveredNotifications()
        let present = Set(delivered.map(\.request.identifier))
        let toDismiss = ShownStore.update { state in
            var toDismiss: [String] = []
            for id in state.showing where !present.contains(id) {
                if state.echo.contains(id) { continue }
                if state.suspected.contains(id) {
                    toDismiss.append(id)                 // absent across two polls → user-dismissed
                } else {
                    state.suspected.insert(id)           // first absence — confirm next poll
                }
            }
            // Anything present again clears its suspicion; anything still showing stays tracked.
            state.suspected = state.suspected.intersection(state.showing).subtracting(present)
            return toDismiss
        }
        for id in toDismiss {
            guard let entry = MirrorMapStore.all()[id] else { ShownStore.clear(id); continue }
            await locallyDismiss(sourceClientId: entry.sourceClientId, sourceKey: entry.sourceKey)
        }
    }
}
