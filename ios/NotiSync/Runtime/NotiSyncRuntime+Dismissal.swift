import Foundation
import SwiftData
import UserNotifications

/// Dismissal sync: a local swipe (or Dismiss action) clears the mirror, acks the message, and — for a
/// clearable source — seals a DismissEvent to the mesh; a remote DismissEvent clears the local mirror. The
/// Inbox's "Read All" runs the same flow batched (`markAllAsRead`), with the mesh sends capped to recent
/// sources. With no swipe callback from iOS, `reconcileDismissals` diffs delivered notifications across
/// two polls to infer user dismissals.
extension NotiSyncRuntime {

    // MARK: Dismissal

    func locallyDismiss(sourceClientId: String, sourceKey: String) async {
        await withCoalescedSaves {
            let pair = DismissedSourcePair(sourceClientId: sourceClientId, sourceKey: sourceKey)
            let dismissedAt = NotiSyncEngine.nowMillis()
            let entries = await Task.detached(priority: .userInitiated) {
                MirrorMapStore.entries(sourceClientId: sourceClientId, sourceKey: sourceKey)
            }.value
            // #14 — a non-clearable (ongoing) source notification must not be cleared on the source by a
            // local swipe: remove the local copy but skip the outbound DismissEvent.
            let clearable = entries.allSatisfy { $0.isClearable ?? true }
            let markedInbox = await removeMirrors(entries, sourceClientId: sourceClientId, sourceKey: sourceKey)
            for entry in entries {
                await queueAndFlushAck(messageId: entry.messageId)
            }
            await clearMirrors(entries)
            // Tombstone the pair so a still-queued/replayed copy of this mirror renders suppressed instead
            // of resurrecting what the user just swiped (same guard a remote dismissal gets).
            await Task.detached(priority: .userInitiated) {
                DismissalTombstoneStore.record(pair, dismissedAt: dismissedAt)
                if !markedInbox {
                    PendingDismissalStore.append(PendingDismissalItem(
                        sourceClientId: sourceClientId, sourceKey: sourceKey, dismissedAt: dismissedAt))
                }
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

    // MARK: Read All (batched local dismissal + capped mesh sync)

    /// Cross-device policy for "Read All": a DismissEvent goes to the mesh only for the newest
    /// `readAllSyncMaxDismissals` unread sources received within `readAllSyncWindow`. Dismissing a stale
    /// backlog shouldn't flood the relay (and every peer) with envelopes for notifications the source
    /// devices almost certainly cleared long ago — older rows are still marked read + cleared locally.
    static let readAllSyncMaxDismissals = 50
    static let readAllSyncWindow: TimeInterval = 48 * 60 * 60
    /// How many DISMISSAL sends run concurrently (one envelope per broker request).
    private static let readAllSendWidth = 4

    /// Mark every Inbox row matching `predicate` as read (the Inbox menu's "Mark … as Read") as ONE
    /// batched dismissal instead of a `locallyDismiss` round-trip per source: a single local pass (one
    /// mirror-map read, one notification-center removal, one echo/tombstone write, one SwiftData commit,
    /// one relay-ack flush) dims the list immediately, then the capped sync set's DismissEvents are
    /// sealed in one trust-roster load and sent concurrently. Non-clearable (ongoing) sources keep their
    /// local-only handling (#14).
    func markAllAsRead(matching predicate: Predicate<InboxNotification>?) async {
        guard let modelContext else { return }
        let started = Date()
        let fetched = (try? modelContext.fetch(FetchDescriptor<InboxNotification>(
            predicate: predicate,
            sortBy: [SortDescriptor(\.receivedAt, order: .reverse)]))) ?? []
        let rows = fetched.filter { !$0.isDismissed }
        guard !rows.isEmpty else { return }
        // Distinct sources, newest first (the fetch is sorted) — a pair's first row is its latest arrival.
        var seen = Set<DismissedSourcePair>()
        var sources: [(pair: DismissedSourcePair, receivedAt: Date)] = []
        for row in rows {
            let pair = DismissedSourcePair(sourceClientId: row.sourceClientId, sourceKey: row.sourceKey)
            if seen.insert(pair).inserted { sources.append((pair, row.receivedAt)) }
        }

        let dismissedAt = NotiSyncEngine.nowMillis()
        // One mirror-map read for the whole pass; the per-pair entries decide clearability and which
        // delivered mirrors to remove.
        let entriesByPair = await Task.detached(priority: .userInitiated) { [seen] in
            Dictionary(grouping: MirrorMapStore.all().values) {
                DismissedSourcePair(sourceClientId: $0.sourceClientId, sourceKey: $0.sourceKey)
            }.filter { seen.contains($0.key) }
        }.value
        let entries = sources.flatMap { entriesByPair[$0.pair] ?? [] }
        let identifiers = entries.map(\.identifier)
        // Tombstones only guard against a still-replayable copy resurrecting the pair — recording pairs
        // older than the relay TTL (with nothing on screen) would just churn the capped store.
        let replayFloor = Date(timeIntervalSinceNow: -TimeInterval(DismissalTombstoneStore.ttlMillis) / 1000)
        let tombstonePairs = sources
            .filter { $0.receivedAt >= replayFloor || entriesByPair[$0.pair] != nil }
            .map(\.pair)
        await Task.detached(priority: .userInitiated) {
            ShownStore.markEchoRemoved(identifiers)
            MirrorMapStore.removeAll(identifiers: identifiers)
            DismissalTombstoneStore.recordAll(tombstonePairs, dismissedAt: dismissedAt)
        }.value
        if !identifiers.isEmpty {
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: identifiers)
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: identifiers)
        }

        // Mark read + queue the batch's relay acks, then commit ONCE — the list refreshes a single time,
        // before any network round-trip.
        for row in rows { row.isDismissed = true }
        queueAcks(messageIds: entries.map(\.messageId))
        saveModelContext()
        bumpInboxRevision()
        await flushPendingAcks()

        let synced = await syncReadAllDismissals(sources: sources, entriesByPair: entriesByPair)
        addActivity(.dismissed, .readAll, detail: .readAllCounts,
                    detailArg: String(synced), detailNum: rows.count)
        PerfMonitor.recordValueTrace(
            "inbox_read_all",
            metrics: ["rows": Int64(rows.count), "sources": Int64(sources.count), "synced": Int64(synced),
                      "duration_ms": Int64(Date().timeIntervalSince(started) * 1000)])
    }

    /// Seal + send the capped Read All sync set (newest-first, within the window, clearable only).
    /// Returns how many DismissEvents the broker accepted.
    private func syncReadAllDismissals(
        sources: [(pair: DismissedSourcePair, receivedAt: Date)],
        entriesByPair: [DismissedSourcePair: [MirrorMapEntry]]
    ) async -> Int {
        guard let engine, let broker else { return 0 }
        let cutoff = Date(timeIntervalSinceNow: -Self.readAllSyncWindow)
        let pairs = Array(
            sources.lazy
                .filter { $0.receivedAt >= cutoff }
                // #14 — an ongoing source notification must not be cleared on the source device.
                .filter { source in (entriesByPair[source.pair] ?? []).allSatisfy { $0.isClearable ?? true } }
                .prefix(Self.readAllSyncMaxDismissals)
                .map(\.pair))
        guard !pairs.isEmpty else { return 0 }
        // #4 — the same send-initiated key-epoch repair as a single dismissal, once for the whole batch.
        await repairUnsealablePeers()
        let envelopes: [Envelope]
        do {
            envelopes = try await Task.detached(priority: .userInitiated) {
                try engine.sealDismissals(pairs)
            }.value
        } catch {
            record(error: error, domain: .dismissSync)
            return 0
        }
        guard !envelopes.isEmpty else { return 0 }   // no sealable peers
        var sent = 0
        var firstError: Error?
        // Sliding window of concurrent sends: overlaps the broker round-trips without stampeding it.
        await withTaskGroup(of: Result<Bool, Error>.self) { group in
            var pending = envelopes.makeIterator()
            var inFlight = 0
            func startNext() {
                guard let envelope = pending.next() else { return }
                inFlight += 1
                group.addTask {
                    do { return .success(try await broker.send(envelope)) } catch { return .failure(error) }
                }
            }
            for _ in 0..<Self.readAllSendWidth { startNext() }
            while inFlight > 0, let result = await group.next() {
                inFlight -= 1
                switch result {
                case .success(let accepted): if accepted { sent += 1 }
                case .failure(let error): if firstError == nil { firstError = error }
                }
                startNext()
            }
        }
        // One error row for the batch (not one per failed send); the local read-marking already stuck.
        if let firstError { record(error: firstError, domain: .dismissSync) }
        return sent
    }

    func removeRemoteDismissal(_ d: DismissEvent) async {
        // The shared helper (also the NSE drain's path) removes + echo-marks the mirrors, clears the map,
        // and tombstones the pair so a late-arriving copy of the dismissed notification renders suppressed.
        _ = await Task.detached(priority: .userInitiated) {
            MirrorRemoval.applyRemoteDismissal(
                DismissedSourcePair(sourceClientId: d.sourceClientId, sourceKey: d.sourceKey),
                dismissedAt: d.dismissedAt, queueForApp: false)
        }.value
        if !markDismissed(sourceClientId: d.sourceClientId, sourceKey: d.sourceKey) {
            await Task.detached(priority: .userInitiated) {
                PendingDismissalStore.append(PendingDismissalItem(
                    sourceClientId: d.sourceClientId, sourceKey: d.sourceKey, dismissedAt: d.dismissedAt))
            }.value
        }
        addActivity(.dismissed, .remoteDismissal, detail: .text, detailArg: d.sourceClientId)
    }

    // MARK: NSE hand-off (the NSE applies drained dismissals; SwiftData catches up here)

    /// Reflect dismissals the NSE applied (its piggyback relay drain) into SwiftData: mark the Inbox rows
    /// dismissed and log the activity. The dismissal analog of `drainPendingInbox` — run right after it so
    /// a suppressed mirror's freshly-drained Inbox row lands already-dismissed.
    func drainPendingDismissals() async {
        guard modelContext != nil else { return }
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
    private func removeMirrors(_ entries: [MirrorMapEntry], sourceClientId: String, sourceKey: String) async -> Bool {
        let ids = entries.map(\.identifier)
        if !ids.isEmpty {
            await Task.detached(priority: .userInitiated) {
                for id in ids { ShownStore.markEchoRemoved(id) }
            }.value
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
        }
        return markDismissed(sourceClientId: sourceClientId, sourceKey: sourceKey)
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
