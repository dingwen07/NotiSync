import Foundation
import UserNotifications

/// A dismissed (sourceClientId, sourceKey) pair — the identity a `DismissEvent` carries.
nonisolated struct DismissedSourcePair: Sendable, Hashable {
    var sourceClientId: String
    var sourceKey: String
}

/// Tombstones of applied dismissals (App Group JSON, shared by the app and the NSE).
///
/// Neither APNs nor the relay orders a dismissal against the notification it targets: the quiet dismissal
/// can be applied first (NSE piggyback drain, silent push) while the notification's own alert push or relay
/// copy is still in flight, and the broker redelivers at-least-once. A tombstone records "this pair was
/// dismissed at T" so a copy posted at-or-before T renders suppressed instead of resurrecting a mirror the
/// user already swiped away on the source. A source may legitimately RE-post the same key later (Android
/// notification keys are stable per slot), so a mirror newer than its tombstone clears it and renders
/// normally — the pair is alive again.
nonisolated enum DismissalTombstoneStore {
    private static let name = AppGroupStore.Files.dismissTombstones
    /// Matches the broker's default relay TTL — nothing older than this can still be replayed at us.
    /// Internal so batch dismissers (the Inbox's Read All) can skip tombstoning pairs past any replay.
    static let ttlMillis: Int64 = 7 * 24 * 60 * 60 * 1000
    private static let cap = 512

    static func pairKey(_ pair: DismissedSourcePair) -> String {
        "\(pair.sourceClientId)\u{1f}\(pair.sourceKey)"
    }

    private static func pair(fromKey key: String) -> DismissedSourcePair? {
        let parts = key.split(separator: "\u{1f}", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        return DismissedSourcePair(sourceClientId: String(parts[0]), sourceKey: String(parts[1]))
    }

    private static func load() -> [String: Int64] {
        AppGroupStore.read([String: Int64].self, name) ?? [:]
    }

    /// Record a dismissal of [pair] at [dismissedAt] (keeps the newest time on repeats — DismissEvents are
    /// idempotent and may be replayed).
    static func record(_ pair: DismissedSourcePair, dismissedAt: Int64) {
        recordAll([pair], dismissedAt: dismissedAt)
    }

    /// Batch form of `record` — one load/prune/write for the whole set (the Inbox's Read All).
    static func recordAll(_ pairs: [DismissedSourcePair], dismissedAt: Int64) {
        guard !pairs.isEmpty else { return }
        AppGroupStore.withLock(name) {
            var map = load()
            for pair in pairs {
                map[pairKey(pair)] = max(dismissedAt, map[pairKey(pair)] ?? 0)
            }
            prune(&map)
            AppGroupStore.write(map, name)
        }
    }

    /// Whether a mirror of [pair] posted at [postTime] should render suppressed. A mirror NEWER than its
    /// tombstone means the source re-posted the key after the dismissal: the tombstone is cleared and the
    /// mirror renders normally. `postTime` and `dismissedAt` usually come from the same source device's
    /// clock (the capturing hub also observes the swipe / ANCS removal); a cross-device dismissal can skew,
    /// which at worst re-shows a re-posted mirror — never loses one.
    static func shouldSuppress(_ pair: DismissedSourcePair, postTime: Int64) -> Bool {
        AppGroupStore.withLock(name) {
            var map = load()
            guard let dismissedAt = map[pairKey(pair)] else { return false }
            if postTime > dismissedAt {
                map.removeValue(forKey: pairKey(pair))
                AppGroupStore.write(map, name)
                return false
            }
            return true
        }
    }

    /// Every live tombstoned pair — the sweep re-applies these against the mirror map.
    static func allPairs() -> [DismissedSourcePair] {
        AppGroupStore.withLock(name) {
            var map = load()
            let before = map.count
            prune(&map)
            if map.count != before { AppGroupStore.write(map, name) }
            return map.keys.compactMap(pair(fromKey:))
        }
    }

    private static func prune(_ map: inout [String: Int64]) {
        let floor = Int64(Date().timeIntervalSince1970 * 1000) - ttlMillis
        map = map.filter { $0.value >= floor }
        if map.count > cap {
            let keep = Set(map.sorted { $0.value > $1.value }.prefix(cap).map(\.key))
            map = map.filter { keep.contains($0.key) }
        }
    }
}

/// A dismissal applied while the matching Inbox row may not exist in SwiftData yet (for example, the NSE
/// or a cold notification action before the app drains its pending Inbox handoff). The app drains these on
/// foreground/BG refresh and marks the rows dismissed.
nonisolated struct PendingDismissalItem: Codable, Sendable {
    var sourceClientId: String
    var sourceKey: String
    var dismissedAt: Int64
}

nonisolated enum PendingDismissalStore {
    private static let name = AppGroupStore.Files.pendingDismissals
    private static let cap = 256

    static func append(_ item: PendingDismissalItem) {
        AppGroupStore.withLock(name) {
            var items = AppGroupStore.read([PendingDismissalItem].self, name) ?? []
            items.removeAll { $0.sourceClientId == item.sourceClientId && $0.sourceKey == item.sourceKey }
            items.append(item)
            if items.count > cap { items.removeFirst(items.count - cap) }
            AppGroupStore.write(items, name)
        }
    }

    /// Return all queued items and clear the queue (the app persists them into SwiftData immediately after).
    static func drainAll() -> [PendingDismissalItem] {
        AppGroupStore.withLock(name) {
            let items = AppGroupStore.read([PendingDismissalItem].self, name) ?? []
            if !items.isEmpty { AppGroupStore.write([PendingDismissalItem](), name) }
            return items
        }
    }
}

/// Shared removal of every delivered/pending mirror of a source pair — the app's inbound-dismissal path
/// and the NSE's piggyback drain both go through here so echo-marking (the reconciler must not read our
/// own removal back as a user dismissal), map cleanup, and tombstoning stay identical in both processes.
nonisolated enum MirrorRemoval {

    /// Apply a remote DismissEvent. Removes the pair's mirrors, echo-marks their ids, clears the map
    /// entries, and records the tombstone. With [queueForApp] (the NSE, which has no SwiftData) the event
    /// is also queued for the app to mark the Inbox row dismissed. Returns the removed identifiers.
    ///
    /// `removeDeliveredNotifications` is asynchronous (it returns before the removal lands) — callers that
    /// may exit right after (the NSE) must `await settle()` before handing back their content.
    @discardableResult
    static func applyRemoteDismissal(_ pair: DismissedSourcePair, dismissedAt: Int64, queueForApp: Bool) -> [String] {
        let ids = removeMirrors(of: pair)
        DismissalTombstoneStore.record(pair, dismissedAt: dismissedAt)
        if queueForApp {
            PendingDismissalStore.append(PendingDismissalItem(
                sourceClientId: pair.sourceClientId, sourceKey: pair.sourceKey, dismissedAt: dismissedAt))
        }
        return ids
    }

    /// Re-apply live tombstones to any mirror that slipped in after its dismissal (a race-delivered copy
    /// rendered passively by the NSE). Returns the pairs actually swept. A pair whose mirror was posted
    /// after the dismissal never gets here — its render cleared the tombstone (`shouldSuppress`).
    static func sweepTombstoned(queueForApp: Bool) -> [DismissedSourcePair] {
        let tombstoned = DismissalTombstoneStore.allPairs()
        guard !tombstoned.isEmpty else { return [] }
        let byPair = Dictionary(grouping: MirrorMapStore.all().values) {
            DismissedSourcePair(sourceClientId: $0.sourceClientId, sourceKey: $0.sourceKey)
        }
        var swept: [DismissedSourcePair] = []
        for pair in tombstoned where byPair[pair] != nil {
            let ids = removeMirrors(of: pair)
            guard !ids.isEmpty else { continue }
            if queueForApp {
                PendingDismissalStore.append(PendingDismissalItem(
                    sourceClientId: pair.sourceClientId, sourceKey: pair.sourceKey,
                    dismissedAt: Int64(Date().timeIntervalSince1970 * 1000)))
            }
            swept.append(pair)
        }
        return swept
    }

    /// Round-trip the notification-center queue so previously issued removals have landed before the
    /// caller exits (the DTS-documented pattern — a removal issued and immediately abandoned can be lost).
    static func settle() async {
        _ = await UNUserNotificationCenter.current().deliveredNotifications()
    }

    private static func removeMirrors(of pair: DismissedSourcePair) -> [String] {
        let ids = MirrorMapStore.entries(sourceClientId: pair.sourceClientId, sourceKey: pair.sourceKey)
            .map(\.identifier)
        guard !ids.isEmpty else { return [] }
        for id in ids { ShownStore.markEchoRemoved(id) }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
        for id in ids { MirrorMapStore.remove(identifier: id) }
        return ids
    }
}

/// The NSE's piggyback dismissal drain. A dismissal must never ride its own alert push (an NSE miss —
/// BFU, memory kill, timeout — would DISPLAY it), so the broker keeps dismissals as quiet envelopes and
/// this drain pulls them from the relay while the NSE is already legitimately awake for a real
/// notification. The broker's `pnc` hint on the alert push says whether there is anything to pull, so
/// the common no-dismissals push skips the network entirely.
nonisolated enum RemoteDismissalDrain {

    struct Outcome: Sendable {
        var applied = 0            // dismissal envelopes decrypted + applied (then acked)
        var swept = 0              // tombstoned stragglers removed by the sweep
        var removedAny: Bool { applied > 0 || swept > 0 }
    }

    /// Pull queued DISMISSAL envelopes (the broker lists exactly those), apply + ack them, then sweep
    /// tombstoned stragglers. Anything else that slips through — a mis-filtered id from an older broker,
    /// or a dismissal we can't open right now (unresolved sender epoch) — stays queued for the app's own
    /// drain paths. Time-boxed by [deadline] and capped at [maxFetches] fetches — the drain shares the
    /// alert push's NSE budget with the render itself.
    static func run(engine: NotiSyncEngine, deadline: Date, maxFetches: Int = 10) async -> Outcome {
        var outcome = Outcome()
        let ids = await RelayClient.pendingIds(
            typ: "DISMISSAL",
            identitySigner: engine.identitySigner,
            operationalSigner: engine.operationalSigner,
            keyEpochProvider: { try engine.buildClientKeyEpochBlob() })
        var ackIds: [String] = []
        var fetches = 0
        for mid in ids {
            guard !Task.isCancelled, Date() < deadline, fetches < maxFetches else { break }
            switch MessageDedupStore.claim(mid) {
            case .alreadyHandled:
                ackIds.append(mid)   // handled earlier but a prior ack didn't land — just drop it
                continue
            case .inFlight:
                continue             // the app is on it right now
            case .claimed:
                break
            }
            fetches += 1
            guard let bytes = await RelayClient.fetchMessage(
                mid,
                identitySigner: engine.identitySigner,
                operationalSigner: engine.operationalSigner,
                keyEpochProvider: { try engine.buildClientKeyEpochBlob() }) else {
                MessageDedupStore.release(mid)
                continue
            }
            // Only consume dismissals here. The wrapper's `typ` is authenticated by the envelope signature,
            // which `openEnvelope` verifies before we act on the decoded event.
            guard let wrapper = try? ProtocolCodec.decodeEnvelope(bytes), wrapper.typ == .DISMISSAL,
                  let (env, inbound) = try? engine.openEnvelope(bytes),
                  case let .dismissal(d) = inbound else {
                MessageDedupStore.release(mid)
                continue
            }
            MirrorRemoval.applyRemoteDismissal(
                DismissedSourcePair(sourceClientId: d.sourceClientId, sourceKey: d.sourceKey),
                dismissedAt: d.dismissedAt, queueForApp: true)
            MessageDedupStore.record(env.messageId)
            ackIds.append(mid)
            outcome.applied += 1
        }
        outcome.swept = MirrorRemoval.sweepTombstoned(queueForApp: true).count
        await RelayClient.ack(ackIds,
                              identitySigner: engine.identitySigner,
                              operationalSigner: engine.operationalSigner,
                              keyEpochProvider: { try engine.buildClientKeyEpochBlob() })
        return outcome
    }
}
