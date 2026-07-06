import Foundation
import os
import SwiftData
import UIKit
import UserNotifications

private let log = Logger(subsystem: "net.extrawdw.apps.NotiSync", category: "runtime")

private nonisolated struct OpenedEnvelopeResult: Sendable {
    var env: Envelope
    var inbound: DecodedInbound
    var dedupId: String
}

private nonisolated enum InboundOpenResult: Sendable {
    case opened(OpenedEnvelopeResult)
    case alreadyHandled
    case inFlight
}

private nonisolated struct TrustTableApplyResult: Sendable {
    var accepted: Bool
    var changed: Bool
    var cardsToOffer: [SignedBlob]
    var keyEpochsToOffer: [SignedBlob]
    var needsBroadcast: Bool

    static let rejected = TrustTableApplyResult(
        accepted: false, changed: false, cardsToOffer: [], keyEpochsToOffer: [], needsBroadcast: false
    )
}

/// Inbound delivery: foreground WebSocket / relay drain / silent-push envelopes → dedup → open → render
/// mirrors (single or per-message MESSAGING), apply DataSync (CARD/ASSET/PROFILE/TRUST), and resolve the
/// private assets (icons, avatars, attachments) that mirrors display.
extension NotiSyncRuntime {

    // MARK: Inbound delivery

    func startForegroundWebSocket() {
        guard liveTask == nil, let broker else { return }
        liveTask = Task { [weak self, broker] in
            guard let runtime = self else { return }
            await broker.liveDelivery { bytes in
                guard await runtime.shouldContinueDelivery(mode: .foregroundWebSocket) else { return false }
                return await runtime.receiveEnvelope(bytes, mode: .foregroundWebSocket)
            }
            await MainActor.run { runtime.liveTask = nil }
        }
    }

    func drainRelay(deliveryMode: DeliveryMode) async {
        guard let broker else { return }
        await withCoalescedSaves {
            await flushPendingAcks()
            let ids = await broker.pendingRelayIds()
            for id in ids {
                guard let bytes = await broker.fetchRelayMessage(id) else { continue }
                if await receiveEnvelope(bytes, mode: deliveryMode) {
                    await queueAndFlushAck(messageId: id)
                }
            }
            let s = settings()
            s.lastRelayDrainAt = .now
            s.lastDeliveryMode = deliveryMode.rawValue
            if !ids.isEmpty { addActivity(.received, .relayDrained, detail: .messageCount, detailNum: ids.count) }
        }
    }

    @discardableResult
    func receiveEnvelope(_ bytes: Data, mode: DeliveryMode, retryOnUnresolved: Bool = true) async -> Bool {
        guard let engine else { return false }
        guard shouldContinueDelivery(mode: mode) else { return false }
        let span = PerfMonitor.startSpan("inbound_envelope")
        span.attribute("delivery_mode", mode.rawValue)
        defer { span.stop() }
        let result = await Task.detached(priority: .userInitiated) {
            try Self.openInboundEnvelope(bytes, engine: engine)
        }.result
        switch result {
        case .success(.alreadyHandled):
            span.attribute("result", "already_handled")
            return true
        case .success(.inFlight):
            span.attribute("result", "in_flight")
            return false
        case .success(.opened(let opened)):
            guard shouldContinueDelivery(mode: mode) else {
                let dedupId = opened.dedupId
                await Task.detached(priority: .userInitiated) { MessageDedupStore.release(dedupId) }.value
                span.attribute("result", "cancelled")
                return false
            }
            // One SQLite commit per envelope: the handler's Inbox upsert, activity rows, and the settings
            // stamp below all land in the batch's single save (which must complete before we return true —
            // the caller acks on true, and an acked-but-unpersisted envelope would be lost for good).
            await withCoalescedSaves {
                switch opened.inbound {
                case let .notification(n):
                    span.attribute("inbound_kind", "notification")
                    await renderNotification(n, messageId: opened.env.messageId, mode: mode)
                case let .dismissal(d):
                    span.attribute("inbound_kind", "dismissal")
                    await removeRemoteDismissal(d)
                case let .dataSync(ds):
                    span.attribute("inbound_kind", "data_sync")
                    await handleDataSync(ds, from: opened.env.signerId, signerEpoch: opened.env.signerEpoch)
                }
                let s = settings()
                if s.lastDeliveryMode != mode.rawValue { s.lastDeliveryMode = mode.rawValue }
            }
            let dedupId = opened.dedupId
            // Mark handled only after the handler ran AND its writes are committed.
            await Task.detached(priority: .userInitiated) { MessageDedupStore.record(dedupId) }.value
            span.attribute("result", "handled")
            return true
        case .failure(EngineError.unresolvedSender(let id)) where retryOnUnresolved:
            span.attribute("result", "retry_unresolved")
            await refetchKeyEpoch(id)
            return await receiveEnvelope(bytes, mode: mode, retryOnUnresolved: false)
        case .failure(let e as EngineError) where e.isSilentDrop:
            // Drop quietly — an envelope from a sender we don't (fully) trust or can't currently open is
            // routine in a multi-device mesh (broadcasts from not-yet-approved peers, pre-convergence). Log
            // to the console only; surfacing one per envelope floods the activity feed. Matches Android,
            // which `log.warn`s to logcat and returns DROPPED. Convergence drops stay unacked; missing local
            // HPKE private keys are unrecoverable, so those are treated as handled and acked.
            let envelope = try? ProtocolCodec.decodeEnvelope(bytes)
            let signerId = envelope?.signerId ?? "unknown"
            let messageId = envelope?.messageId ?? "unknown"
            log.info("dropping envelope signerId=\(signerId, privacy: .public) messageId=\(messageId, privacy: .public): \(e.localizedDescription, privacy: .public)")
            span.attribute("result", "silent_drop")
            return e.ackAfterSilentDrop
        case .failure(let error):
            record(error: error, domain: .envelopeDelivery)
            // Non-silent failures (bad signature, malformed body, unsupported payload shape) will not become
            // deliverable by retrying the same relay item forever. Treat them as handled so peek fetches ack.
            span.attribute("result", "error")
            return true
        }
    }

    private nonisolated static func openInboundEnvelope(_ bytes: Data, engine: NotiSyncEngine) throws -> InboundOpenResult {
        // #3 — claim before opening so a concurrent duplicate (broker at-least-once, app restart, NSE<->WS
        // race) cannot pass the dedup gate while the first delivery is still rendering/applying.
        let dedupId = try? engine.envelopeMessageId(bytes)
        if let dedupId {
            switch MessageDedupStore.claim(dedupId) {
            case .claimed: break
            case .alreadyHandled: return .alreadyHandled
            case .inFlight: return .inFlight
            }
        }
        do {
            let span = PerfMonitor.startSpan("envelope_open")
            defer { span.stop() }
            let (env, inbound) = try engine.openEnvelope(bytes)
            return .opened(OpenedEnvelopeResult(env: env, inbound: inbound, dedupId: dedupId ?? env.messageId))
        } catch {
            if let dedupId { MessageDedupStore.release(dedupId) }
            throw error
        }
    }

    private func renderNotification(_ n: CapturedNotification, messageId: String, mode: DeliveryMode) async {
        let span = PerfMonitor.startSpan("mirror_render")
        span.attribute("style", n.style.rawValue)
        defer { span.stop() }
        let identifier = MirrorPresentation.identifier(for: n)
        // If the NSE (or an earlier path) already delivered this message, reflect ITS delivery mode in the
        // Inbox rather than the later drain/WS, and don't post a duplicate banner.
        let prior = await Task.detached(priority: .userInitiated) {
            MirrorMapStore.entries(sourceClientId: n.sourceClientId, sourceKey: n.sourceKey)
                .first { $0.messageId == messageId }
        }.value
        let displayMode = prior?.deliveryMode ?? mode.rawValue
        span.attribute("delivery_mode", displayMode)
        upsertInbox(n, messageId: messageId, identifier: identifier, deliveryMode: displayMode)
        addActivity(.received, .appLabel, titleArg: n.appLabel, detail: .deliveryMode, detailArg: displayMode)
        guard prior == nil else { span.attribute("result", "duplicate"); return }
        // Filter check + category registration touch App Group files (the iOS-origin path even writes the
        // bridged-device record) — off the main actor.
        let filtered = await Task.detached(priority: .userInitiated) {
            NotificationFilterStore.shouldFilterNotification(n)
        }.value
        guard !filtered else { span.attribute("result", "filtered"); return }
        // Register the per-channel category (carrying the Dismiss action) before posting under it (#15).
        await Task.detached(priority: .userInitiated) {
            MirrorCategoryRegistry.ensureRegistered(MirrorPresentation.categoryIdentifier(for: n))
        }.value
        if n.style == .MESSAGING, !n.messages.isEmpty {
            await postMessagingMirror(n, messageId: messageId, mode: mode)
        } else {
            await postSingleMirror(n, messageId: messageId, identifier: identifier, mode: mode)
        }
        span.attribute("result", "posted")
        recordMirrorLatency(n, deliveryMode: displayMode)
    }

    /// The product's north-star metric: source-capture → on-device-display latency (the whole pipeline —
    /// source app, broker, transport, decrypt, render). `postTime` is the SOURCE device's clock, so guard
    /// against skew/garbage (negative or absurdly large) before recording; the noise averages out in
    /// aggregate. Recorded only for mirrors this path actually posts (the NSE alert path reports its own
    /// `nse_delivery` e2e instead, since the app only upserts those into the Inbox).
    private func recordMirrorLatency(_ n: CapturedNotification, deliveryMode: String) {
        let latency = NotiSyncEngine.nowMillis() - n.postTime
        guard latency >= 0, latency <= 6 * 60 * 60 * 1000 else { return }
        PerfMonitor.recordValueTrace(
            "mirror_e2e_latency",
            attributes: ["delivery_mode": deliveryMode,
                         "origin_platform": n.originPlatform.rawValue,
                         "style": n.style.rawValue],
            metrics: ["latency_ms": latency])
    }

    /// A non-messaging mirror: one notification keyed by the (sourceClientId, sourceKey) identifier.
    private func postSingleMirror(_ n: CapturedNotification, messageId: String, identifier: String, mode: DeliveryMode) async {
        let commAppIcons = await Task.detached(priority: .userInitiated) {
            MirrorDisplayStore.preferences().communicationAppIcons
        }.value
        let attachments = await fetchAttachments(for: n)
        // The icon is needed as the sender avatar (communication styling) or, by default, as the
        // large-icon attachment — which only shows when the mirror has no graphic of its own.
        let appIcon = (commAppIcons || attachments.isEmpty) ? await appIconData(for: n) : nil
        let content = await MirrorPresentation.content(for: n, messageId: messageId, attachments: attachments,
                                                       appIcon: appIcon, communicationStyle: commAppIcons)
        try? await UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
        let entry = MirrorMapEntry(identifier: identifier, sourceClientId: n.sourceClientId,
                                   sourceKey: n.sourceKey, messageId: messageId, deliveryMode: mode.rawValue,
                                   isClearable: n.isClearable)
        await Task.detached(priority: .userInitiated) {
            MirrorMapStore.put(entry)
            ShownStore.markShowing(entry.identifier)
        }.value
    }

    /// A messaging mirror: one notification per conversation message, all sharing the conversation's
    /// `threadIdentifier` so iOS threads them (#13). Stable per-message identifiers make a re-delivery of
    /// overlapping history idempotent; only the newest message alerts (earlier ones post silently), and
    /// the user's own messages (nil sender) never post.
    private func postMessagingMirror(_ n: CapturedNotification, messageId: String, mode: DeliveryMode) async {
        let base = MirrorPresentation.identifier(for: n)
        let existing = await Task.detached(priority: .userInitiated) { MirrorMapStore.all() }.value
        // A nil sender is the user's own message (e.g. an inline reply sent from the source device).
        // Android folds those into its single MessagingStyle notification as "me" bubbles; iOS posts one
        // notification per message, where a self message would masquerade as an incoming one — skip them.
        // And when the user's own message is the conversation's newest, post the rest silently (Android
        // silences its whole self-reply update the same way).
        let incoming = n.messages.filter { $0.sender != nil }
        let newestIsSelf = n.messages.last?.sender == nil
        let lastIndex = incoming.indices.last
        for (i, message) in incoming.enumerated() {
            let id = MirrorPresentation.messageIdentifier(base: base, message: message)
            guard existing[id] == nil else { continue }   // this message is already shown — don't re-post/re-alert
            let avatar = await messageAvatar(message, fallback: n)
            let attachments = await fetchAttachments(for: message)
            let content = MirrorPresentation.messageContent(for: n, message: message, messageId: messageId,
                                                            attachments: attachments, senderImage: avatar,
                                                            alerting: i == lastIndex && !newestIsSelf)
            try? await UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: id, content: content, trigger: nil))
            let entry = MirrorMapEntry(identifier: id, sourceClientId: n.sourceClientId,
                                       sourceKey: n.sourceKey, messageId: messageId, deliveryMode: mode.rawValue,
                                       isClearable: n.isClearable)
            await Task.detached(priority: .userInitiated) {
                MirrorMapStore.put(entry)
                ShownStore.markShowing(entry.identifier)
            }.value
        }
    }

    /// Per-message avatar: the message sender's contact photo if carried, else the source app's icon.
    private func messageAvatar(_ message: ConversationMessage, fallback n: CapturedNotification) async -> Data? {
        if let ref = message.avatar, let data = await loadAsset(ref) { return data }
        return await appIconData(for: n)
    }

    /// Origin app icon (messaging avatar fallback + non-messaging large icon / avatar). Mirrors Android's
    /// icon order: public App Store icon by iOS bundle id first (crisp/canonical, and the only option for
    /// an iOS-origin mirror), else the decrypted private app/large icon (the captured launcher icon — the
    /// only source for an Android-origin app).
    private func appIconData(for n: CapturedNotification) async -> Data? {
        if let itunes = await AppIconFetcher.iconData(iosBundleId: n.iosBundleId) { return itunes }
        if let ref = n.appIcon ?? n.largeIcon { return await loadAsset(ref) }
        return nil
    }

    /// Fetch + decrypt + integrity-check a private asset, cached by content hash (App Group; shared with the
    /// NSE for its avatar lookups). Read-through: cache → broker fetch → decrypt → cache.
    func loadAsset(_ ref: PrivateAssetRef) async -> Data? {
        let span = PerfMonitor.startSpan("asset_resolve")
        span.attribute("asset_role", ref.role.rawValue)
        defer { span.stop() }
        if let cached = await Task.detached(priority: .utility, operation: {
            AssetCache.read(ref.assetHash)
        }).value {
            span.attribute("cache_hit", "true")
            span.metric("bytes", Int64(cached.count))
            return cached
        }
        span.attribute("cache_hit", "false")
        guard let broker, let engine,
              let ciphertext = await broker.fetchAsset(sourceClientId: ref.sourceClientId, assetId: ref.assetId) else {
            span.attribute("result", "fetch_failed")
            return nil
        }
        let plaintext = await Task.detached(priority: .utility) { () -> Data? in
            guard let plaintext = engine.openAsset(ref, ciphertext: ciphertext) else { return nil }
            AssetCache.write(ref.assetHash, plaintext)
            return plaintext
        }.value
        span.attribute("result", plaintext != nil ? "fetched" : "decrypt_failed")
        if let plaintext { span.metric("bytes", Int64(plaintext.count)) }
        return plaintext
    }

    private func handleDataSync(_ ds: DataSync, from signerId: String, signerEpoch: Int) async {
        switch ds.kind {
        case .CARD:
            // CARD (a self-authenticating client card and/or key-epoch relay) is own-mesh only — Android's
            // SendPolicy gate. Silent: Android logs NO activity for key-epoch convergence / card repair (#2).
            guard let engine else { return }
            let changed = await Task.detached(priority: .utility) {
                guard engine.isOwnDevice(signerId) else { return false }
                var changed = false
                if let card = ds.card?.card, engine.applyDeliveredCard(card) { changed = true }       // #3 — names for introduced peers
                if let blob = ds.card?.epochBlob, engine.applyFetchedKeyEpoch(blob) { changed = true }
                return changed
            }.value
            if changed { await refreshPeerRowsAsync() }
        case .ASSET:
            // The source re-provided assets it had dropped (our ASSET_MISSING repair request). Fetch +
            // decrypt + cache them now so the Inbox icon / avatar resolves without another round-trip.
            if ds.asset?.kind == .ASSET_READY {
                for ref in (ds.asset?.items ?? []).compactMap(\.ref) {
                    _ = await loadAsset(ref)
                    repairRequested.remove(ref.assetHash)
                }
                bumpIconRevision()   // re-provisioned assets are cached now — let monogram rows re-resolve
            }
            addActivity(.received, .assetSync, detail: .text, detailArg: ds.asset?.kind.rawValue ?? "")
        case .PROFILE:
            // #5 — a peer renamed itself (or changed platform); apply LWW and refresh its Devices row.
            guard let engine, let p = ds.profile else { return }
            let changed = await Task.detached(priority: .utility) {
                engine.applyProfile(p, from: signerId)
            }.value
            if changed {
                await refreshPeerRowsAsync()
                addActivity(.paired, .renamed, detail: .text, detailArg: p.displayName)
            }
        case .TRUST:
            // #5/#3 — an own-mesh device broadcast its roster. Fold it (propagating revocations + creating
            // pending-approval introductions), THEN converge keys for any advertised device — even one still
            // PENDING approval — so an introduced device's profile (card) + key-epoch are in place before the
            // user approves it. Gate on an own-mesh, identity-signed sender (the sender is already TRUSTED —
            // openEnvelope dropped it otherwise).
            guard let engine, let t = ds.trust, signerEpoch == 0 else { break }
            let result = await Task.detached(priority: .utility) {
                guard engine.isOwnDevice(signerId) else {
                    return TrustTableApplyResult.rejected
                }
                guard let result = engine.applyTrustTableWithRepairs(t, from: signerId, signerEpoch: signerEpoch) else {
                    return TrustTableApplyResult.rejected
                }
                return TrustTableApplyResult(
                    accepted: true, changed: result.changed,
                    cardsToOffer: result.cardsToOffer, keyEpochsToOffer: result.keyEpochsToOffer,
                    needsBroadcast: result.needsBroadcast
                )
            }.value
            guard result.accepted else { break }
            await sendTrustRepairs(to: signerId, result: result)
            await convergeFromTable(t)
            if result.needsBroadcast { await broadcastTrust() }
            if result.changed {
                await refreshPeerRowsAsync()
                addActivity(.paired, .trustUpdated, detail: .text, detailArg: signerId)
            }
        case .FILTER:
            // A peer's request to suppress notifications we send IT. This device is a consumer, not a source
            // peer's filter target, and its NSE already filters locally — so iOS ignores an inbound FILTER.
            break
        }
    }

    private func sendTrustRepairs(to recipientId: String, result: TrustTableApplyResult) async {
        guard let engine, let broker else { return }
        for card in result.cardsToOffer {
            if let env = try? engine.sealCardRepair(to: recipientId, cardBlob: card) {
                _ = try? await broker.send(env)
            }
        }
        for blob in result.keyEpochsToOffer {
            if let env = try? engine.sealKeyEpochRepair(to: recipientId, blob: blob) {
                _ = try? await broker.send(env)
            }
        }
    }

    /// #3 — pull from the broker the key-epoch of any device a roster advertises at an epoch ahead of what we
    /// hold, INCLUDING devices still pending approval (mirrors Android's onDataSync TRUST stale-convergence,
    /// `it.epoch > peerEpoch(it.clientId)`). `applyFetchedKeyEpoch` applies for any roster device regardless of
    /// trust status, so an introduced device is reachable the instant the user approves it.
    private func convergeFromTable(_ table: TrustTable) async {
        guard let engine, let broker else { return }
        let entries = table.entries.filter { $0.clientId != clientId }
        let peerEpochs = await Task.detached(priority: .utility) {
            engine.peerEpochs(entries.map(\.clientId))
        }.value
        var applied = false
        for entry in entries {
            let peerEpoch = peerEpochs[entry.clientId] ?? 0
            guard entry.epoch > peerEpoch else { continue }
            guard let blob = await broker.fetchKeyEpoch(clientId: entry.clientId, epoch: entry.epoch) else { continue }
            let didApply = await Task.detached(priority: .utility) {
                engine.applyFetchedKeyEpoch(blob)
            }.value
            if didApply {
                applied = true
            }
        }
        if applied { await refreshPeerRowsAsync() }
    }

    // MARK: Assets

    private func fetchAttachments(for n: CapturedNotification) async -> [UNNotificationAttachment] {
        var attachments: [UNNotificationAttachment] = []
        for ref in [n.largeIcon, n.bigPicture].compactMap({ $0 }) {
            guard let plaintext = await loadAsset(ref), let attachment = await MirrorPresentation.attachment(plaintext, ref: ref) else {
                scheduleAssetRepair(ref)
                continue
            }
            attachments.append(attachment)
        }
        return attachments
    }

    private func fetchAttachments(for message: ConversationMessage) async -> [UNNotificationAttachment] {
        guard let ref = message.data else { return [] }
        guard let plaintext = await loadAsset(ref),
              let attachment = await MirrorPresentation.attachment(plaintext, ref: ref) else {
            scheduleAssetRepair(ref)
            return []
        }
        return [attachment]
    }

    func scheduleAssetRepair(_ ref: PrivateAssetRef) {
        guard repairRequested.insert(ref.assetHash).inserted else { return }
        // Keep this unstructured so SwiftUI row/icon task cancellation cannot cancel the repair send.
        Task {
            if !(await requestAssetRepair(ref)) {
                repairRequested.remove(ref.assetHash)
            }
        }
    }

    @discardableResult
    func requestAssetRepair(_ ref: PrivateAssetRef) async -> Bool {
        guard let engine, let broker else { return false }
        let item = AssetSyncItem(assetHash: ref.assetHash, assetId: ref.assetId, ref: nil)
        guard let env = try? engine.sealAssetRepairRequest(to: ref.sourceClientId, items: [item]) else { return false }
        return (try? await broker.send(env)) != nil
    }

    // MARK: Local test

    func postLocalTestNotification() {
        Task {
            let n = CapturedNotification(
                sourceClientId: clientId.isEmpty ? "local" : clientId,
                sourceKey: "local-test|\(UUID().uuidString)",
                packageName: NotiSyncConfig.bundleId, appLabel: "NotiSync",
                title: String(localized: "notification.localTest.title", defaultValue: "Local notification", comment: "Title for a local test notification."),
                text: String(localized: "notification.localTest.body", defaultValue: "Posted by NotiSync without APNs.", comment: "Body for a local test notification."),
                category: .STATUS, importance: .HIGH, postTime: NotiSyncEngine.nowMillis(),
                originDeviceName: settings().deviceName
            )
            await renderNotification(n, messageId: "local.\(UUID().uuidString)", mode: .localPreview)
        }
    }
}
