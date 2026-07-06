import Foundation
import UserNotifications

/// Dismissal sync: a local swipe (or Dismiss action) clears the mirror, acks the message, and — for a
/// clearable source — seals a DismissEvent to the mesh; a remote DismissEvent clears the local mirror. With
/// no swipe callback from iOS, `reconcileDismissals` diffs delivered notifications across two polls to infer
/// user dismissals.
extension NotiSyncRuntime {

    // MARK: Dismissal

    func locallyDismiss(sourceClientId: String, sourceKey: String) async {
        await withCoalescedSaves {
            let entries = await Task.detached(priority: .userInitiated) {
                MirrorMapStore.entries(sourceClientId: sourceClientId, sourceKey: sourceKey)
            }.value
            // #14 — a non-clearable (ongoing) source notification must not be cleared on the source by a
            // local swipe: remove the local copy but skip the outbound DismissEvent.
            let clearable = entries.allSatisfy { $0.isClearable ?? true }
            await removeMirrors(entries, sourceClientId: sourceClientId, sourceKey: sourceKey)
            for entry in entries {
                await queueAndFlushAck(messageId: entry.messageId)
            }
            await clearMirrors(entries)
            // Tombstone the pair so a still-queued/replayed copy of this mirror renders suppressed instead
            // of resurrecting what the user just swiped (same guard a remote dismissal gets).
            await Task.detached(priority: .userInitiated) {
                DismissalTombstoneStore.record(
                    DismissedSourcePair(sourceClientId: sourceClientId, sourceKey: sourceKey),
                    dismissedAt: NotiSyncEngine.nowMillis())
            }.value
            guard clearable else {
                addActivity(.dismissed, .dismissedLocally, detail: .ongoingNotSynced)
                return
            }
            guard let engine, let broker else { return }
            // #4 — send-initiated key-epoch repair: pull a current key-epoch for any own-mesh peer we can't
            // currently seal to (e.g. it just rotated), so the dismissal reaches it instead of black-holing.
            await repairUnsealablePeers()
            do {
                // Sealing loads + signature-verifies the trust roster and signs in the Secure Enclave —
                // off the main actor.
                if let env = try await Task.detached(priority: .userInitiated, operation: {
                    try engine.sealDismissal(sourceClientId: sourceClientId, sourceKey: sourceKey)
                }).value {
                    try await broker.send(env)
                    addActivity(.dismissed, .dismissed, detail: .syncedToMesh)
                } else {
                    addActivity(.dismissed, .dismissed, detail: .noPeers)
                }
            } catch {
                record(error: error, domain: .dismissSync)
            }
        }
    }

    /// #4 — refetch the current key-epoch for every trusted peer we hold no usable epoch for, so an
    /// attempted send (e.g. a dismissal) can reach a peer that has rotated since we last converged.
    private func repairUnsealablePeers() async {
        guard let engine else { return }
        let ids = await Task.detached(priority: .userInitiated) { engine.peersNeedingKeyEpoch() }.value
        for id in ids { await refetchKeyEpoch(id) }
    }

    func removeRemoteDismissal(_ d: DismissEvent) async {
        // The shared helper (also the NSE drain's path) removes + echo-marks the mirrors, clears the map,
        // and tombstones the pair so a late-arriving copy of the dismissed notification renders suppressed.
        _ = await Task.detached(priority: .userInitiated) {
            MirrorRemoval.applyRemoteDismissal(
                DismissedSourcePair(sourceClientId: d.sourceClientId, sourceKey: d.sourceKey),
                dismissedAt: d.dismissedAt, queueForApp: false)
        }.value
        markDismissed(sourceClientId: d.sourceClientId, sourceKey: d.sourceKey)
        addActivity(.dismissed, .remoteDismissal, detail: .text, detailArg: d.sourceClientId)
    }

    // MARK: NSE hand-off (the NSE applies drained dismissals; SwiftData catches up here)

    /// Reflect dismissals the NSE applied (its piggyback relay drain) into SwiftData: mark the Inbox rows
    /// dismissed and log the activity. The dismissal analog of `drainPendingInbox` — run right after it so
    /// a suppressed mirror's freshly-drained Inbox row lands already-dismissed.
    func drainPendingDismissals() async {
        let items = await Task.detached(priority: .utility) { PendingDismissalStore.drainAll() }.value
        guard !items.isEmpty else { return }
        await withCoalescedSaves {
            for item in items {
                markDismissed(sourceClientId: item.sourceClientId, sourceKey: item.sourceKey)
                addActivity(.dismissed, .remoteDismissal, detail: .text, detailArg: item.sourceClientId)
            }
        }
    }

    /// Remove any mirror that slipped in after its dismissal — a tombstoned copy the NSE had to deliver
    /// passively (an alert push cannot be suppressed without the filtering entitlement). Echo-marked
    /// inside the helper, so the reconciler doesn't read the removal back as a user dismissal.
    func sweepTombstonedMirrors() async {
        let swept = await Task.detached(priority: .utility) { MirrorRemoval.sweepTombstoned(queueForApp: false) }.value
        guard !swept.isEmpty else { return }
        await withCoalescedSaves {
            for pair in swept {
                markDismissed(sourceClientId: pair.sourceClientId, sourceKey: pair.sourceKey)
            }
        }
    }

    /// Remove every delivered/pending mirror for a source (NSE- and app-posted), echo-marking each so the
    /// reconciler doesn't read the removal back as a user dismissal.
    private func removeMirrors(_ entries: [MirrorMapEntry], sourceClientId: String, sourceKey: String) async {
        let ids = entries.map(\.identifier)
        if !ids.isEmpty {
            await Task.detached(priority: .userInitiated) {
                for id in ids { ShownStore.markEchoRemoved(id) }
            }.value
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
        }
        markDismissed(sourceClientId: sourceClientId, sourceKey: sourceKey)
    }

    private func clearMirrors(_ entries: [MirrorMapEntry]) async {
        let ids = entries.map(\.identifier)
        guard !ids.isEmpty else { return }
        await Task.detached(priority: .userInitiated) {
            for id in ids { MirrorMapStore.remove(identifier: id) }
        }.value
    }

    // MARK: Dismissal reconciliation (no swipe callback — diff getDeliveredNotifications)

    func reconcileDismissals() async {
        // Two-poll confirmation relies on time between passes; skip a pass that fires too soon after the last
        // (e.g. cold-launch configure() + the scenePhase .active both calling appBecameActive).
        guard Date().timeIntervalSince(lastReconcileAt) > 3 else { return }
        lastReconcileAt = .now
        let delivered = await UNUserNotificationCenter.current().deliveredNotifications()
        let present = Set(delivered.map(\.request.identifier))
        let toDismiss = await Task.detached(priority: .userInitiated) {
            ShownStore.update { state in
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
        }.value
        guard !toDismiss.isEmpty else { return }
        // One map read for the whole pass; a source with several mirror ids is dismissed once (its
        // locallyDismiss echo-marks and clears every id of the pair, covering the ids skipped here).
        let entriesById = await Task.detached(priority: .userInitiated) { MirrorMapStore.all() }.value
        var dismissedSources = Set<String>()
        var orphaned: [String] = []
        for id in toDismiss {
            guard let entry = entriesById[id] else { orphaned.append(id); continue }
            guard dismissedSources.insert("\(entry.sourceClientId)\u{1f}\(entry.sourceKey)").inserted else { continue }
            await locallyDismiss(sourceClientId: entry.sourceClientId, sourceKey: entry.sourceKey)
        }
        if !orphaned.isEmpty {
            await Task.detached(priority: .userInitiated) {
                for id in orphaned { ShownStore.clear(id) }
            }.value
        }
    }
}
