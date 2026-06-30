import UserNotifications

/// Notification Service Extension. For a NOTIFICATION alert push it decrypts on-device (HPKE + AES-GCM
/// via the shared NotiSyncKit) and replaces the placeholder with a Communication Notification before
/// display. The broker only ever holds ciphertext. Handles both inline ciphertext (`ct`) and oversized
/// notifications (the broker sent only a relay pointer `mid` → the NSE pulls it with the operational
/// request key). After displaying, it acks the message so the app doesn't re-deliver it on WS connect.
final class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var bestAttempt: UNNotificationContent?
    private var processingTask: Task<Void, Never>?
    private var claimedDedupId: String?

    // Perf instrumentation. The NSE deliberately does not link Firebase (configuring it would add tens of ms
    // to this time-boxed path and extension uploads are unreliable), so it measures the outcome + latency and
    // hands them to the app via the App Group; the app replays them into Performance (see PerfEventStore).
    private let startedAt = Date()
    private var perfTransport = "inline"
    private var perfStyle = "?"
    private var perfReported = false
    // Per-push asset accounting, split by source and deduped by asset key (the second render pass re-reads
    // already-fetched assets from cache — those aren't recounted). "cached" = served from the App Group
    // cache; "fetched" = network fetch + decrypt (cache miss).
    private var perfAssetSeen = Set<String>()
    private var perfAssetCachedCount = 0
    private var perfAssetCachedMs: Int64 = 0
    private var perfAssetFetchedCount = 0
    private var perfAssetFetchedMs: Int64 = 0

    override func didReceive(_ request: UNNotificationRequest,
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        let best = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        self.bestAttempt = best

        guard let engine = NotiSyncEngine(forExtension: true) else { reportPerf("no_engine"); finish(best); return }
        let info = request.content.userInfo

        processingTask = Task {
            // Inline ciphertext, else pull the oversized envelope from the relay. Record
            // WHICH transport the NSE used so the Inbox differentiates an inline-ciphertext push from a
            // relay-fetched (oversized) one — both are "APNs alert" (NSE-displayed), but by different paths.
            var bytes: Data?
            let inline = info["ct"] != nil
            perfTransport = inline ? "inline" : "relay"
            if let ct = info["ct"] as? String { bytes = Data(base64Encoded: ct) }
            if bytes == nil, let mid = info["mid"] as? String {
                bytes = await RelayClient.fetchMessage(mid,
                                                       identitySigner: engine.identitySigner,
                                                       operationalSigner: engine.operationalSigner,
                                                       keyEpochProvider: { try engine.buildClientKeyEpochBlob() })
            }
            guard let envelopeBytes = bytes else { reportPerf("no_bytes"); finish(best); return }
            let deliveryMode = (inline ? DeliveryMode.apnsAlertInline : .apnsAlertRelay).rawValue
            let dedupId = (info["mid"] as? String) ?? (try? engine.envelopeMessageId(envelopeBytes))
            if let dedupId {
                switch MessageDedupStore.claim(dedupId) {
                case .claimed:
                    claimedDedupId = dedupId
                    break
                case .alreadyHandled:
                    await RelayClient.ack([dedupId],
                                          identitySigner: engine.identitySigner,
                                          operationalSigner: engine.operationalSigner,
                                          keyEpochProvider: { try engine.buildClientKeyEpochBlob() })
                    reportPerf("dup")
                    finish(best)
                    return
                case .inFlight:
                    reportPerf("inflight")
                    finish(best)
                    return
                }
            }

            do {
                let (env, inbound) = try engine.openEnvelope(envelopeBytes)
                guard case let .notification(n) = inbound else {
                    releaseClaim(dedupId)
                    reportPerf("not_notification")
                    finish(best)
                    return
                }
                perfStyle = n.style.rawValue
                let messageId = dedupId ?? env.messageId
                let filterAlert = NotificationFilterStore.shouldFilterNotification(n)
                // Register the per-channel category (with the Dismiss action) for this push (#15).
                if !filterAlert {
                    MirrorCategoryRegistry.ensureRegistered(MirrorPresentation.categoryIdentifier(for: n))
                }
                // A messaging push renders its newest message as a communication notification (the alert
                // fast-path shows one message; the app posts the full thread on its next foreground, #13).
                let prepared: UNNotificationContent
                let fetch: SenderImageFetch?
                if n.style == .MESSAGING, let last = n.messages.last {
                    let attachments = await fetchAttachments(for: last, engine: engine)
                    let senderImage = senderImage(for: n, message: last)
                    prepared = MirrorPresentation.messageContent(for: n, message: last, messageId: messageId,
                                                                 attachments: attachments, senderImage: senderImage.data,
                                                                 alerting: true)
                    fetch = senderImage.data == nil ? senderImage.fetch : nil
                    // Record the per-message map id of the message we just displayed so the app's multi-post
                    // path (#13) doesn't re-post it under a different id once it processes a later envelope.
                    if !filterAlert {
                        let perMsgId = MirrorPresentation.messageIdentifier(base: MirrorPresentation.identifier(for: n), message: last)
                        MirrorMapStore.put(MirrorMapEntry(identifier: perMsgId, sourceClientId: n.sourceClientId,
                                                          sourceKey: n.sourceKey, messageId: messageId, deliveryMode: deliveryMode,
                                                          isClearable: n.isClearable))
                    }
                } else {
                    let attachments = await fetchAttachments(for: n, engine: engine)
                    let senderImage = senderImage(for: n)
                    prepared = MirrorPresentation.content(for: n, messageId: messageId,
                                                          attachments: attachments, senderImage: senderImage.data)
                    fetch = senderImage.data == nil ? senderImage.fetch : nil
                }
                bestAttempt = filterAlert ? MirrorPresentation.passiveContent(prepared, removeActions: true) : prepared

                // The system fixes the delivered notification's identifier to this push's request id (APNs),
                // so record the mapping under THAT id for dismissal reconciliation + remote-dismissal removal.
                if !filterAlert {
                    MirrorMapStore.put(MirrorMapEntry(identifier: request.identifier, sourceClientId: n.sourceClientId,
                                                      sourceKey: n.sourceKey, messageId: messageId, deliveryMode: deliveryMode,
                                                      isClearable: n.isClearable))
                    ShownStore.markShowing(request.identifier)
                }
                // Hand the mirror to the app's Inbox: we're about to ack (below), so the app will never see
                // this message again over WS/drain — it can only reach the SwiftData Inbox via this queue.
                PendingInboxStore.append(PendingInboxItem(from: n, messageId: messageId,
                                                          identifier: request.identifier, deliveryMode: deliveryMode))
                // Record in the shared dedup so the app's WS/drain path treats this as already handled and
                // never double-posts it (#3), then ack so the broker drops the relay copy.
                MessageDedupStore.record(messageId)
                clearClaim(dedupId)
                let content: UNNotificationContent
                if let data = await fetchSenderImage(fetch, engine: engine) {
                    if n.style == .MESSAGING, let last = n.messages.last {
                        let attachments = await fetchAttachments(for: last, engine: engine)
                        content = MirrorPresentation.messageContent(for: n, message: last, messageId: messageId,
                                                                    attachments: attachments, senderImage: data,
                                                                    alerting: true)
                    } else {
                        let attachments = await fetchAttachments(for: n, engine: engine)
                        content = MirrorPresentation.content(for: n, messageId: messageId,
                                                             attachments: attachments, senderImage: data)
                    }
                } else {
                    content = prepared
                }
                await RelayClient.ack([messageId],
                                      identitySigner: engine.identitySigner,
                                      operationalSigner: engine.operationalSigner,
                                      keyEpochProvider: { try engine.buildClientKeyEpochBlob() })
                reportPerf(filterAlert ? "filtered" : "delivered", postTime: n.postTime)
                finish(filterAlert ? MirrorPresentation.passiveContent(content, removeActions: true) : content)
            } catch {
                releaseClaim(dedupId)
                reportPerf("error")
                finish(best) // placeholder; the app recovers it on next foreground / relay drain
            }
        }
    }

    private enum SenderImageFetch {
        case appStoreIcon(String)
        case privateAsset(PrivateAssetRef)
    }

    /// Image for a messaging notification: if a sender avatar is present, use only that avatar source.
    /// A sender-avatar cache miss deliberately does not fall back to the app icon.
    private func senderImage(for n: CapturedNotification, message: ConversationMessage) -> (data: Data?, fetch: SenderImageFetch?) {
        if let ref = message.avatar {
            return (cachedAssetRead(key: ref.assetHash, read: { AssetCache.read(ref.assetHash) }), .privateAsset(ref))
        }
        return senderImage(for: n)
    }

    /// Source app icon for communication styling: App Store artwork for iOS-origin apps, otherwise the
    /// captured private launcher icon. Cache hits are used immediately; misses are fetched after prepare.
    private func senderImage(for n: CapturedNotification) -> (data: Data?, fetch: SenderImageFetch?) {
        if let bundleId = n.iosBundleId, !bundleId.isEmpty {
            let data = cachedAssetRead(key: "icon:\(bundleId)", read: { AppIconFetcher.cachedIconData(iosBundleId: bundleId) })
            return (data, .appStoreIcon(bundleId))
        }
        if let ref = n.appIcon ?? n.largeIcon {
            return (cachedAssetRead(key: ref.assetHash, read: { AssetCache.read(ref.assetHash) }), .privateAsset(ref))
        }
        return (nil, nil)
    }

    private func fetchSenderImage(_ fetch: SenderImageFetch?, engine: NotiSyncEngine) async -> Data? {
        switch fetch {
        case .appStoreIcon(let bundleId):
            // Reached only after the synchronous cache check missed, so this is a real iTunes network fetch.
            let start = Date()
            let data = await AppIconFetcher.iconData(iosBundleId: bundleId)
            recordAssetTiming(key: "icon:\(bundleId)", cached: false, ms: Int64(Date().timeIntervalSince(start) * 1000))
            return data
        case .privateAsset(let ref):
            return await privateAsset(ref, engine: engine)
        case .none:
            return nil
        }
    }

    private func fetchAttachments(for n: CapturedNotification, engine: NotiSyncEngine) async -> [UNNotificationAttachment] {
        var attachments: [UNNotificationAttachment] = []
        for ref in [n.largeIcon, n.bigPicture].compactMap({ $0 }) {
            guard let plaintext = await privateAsset(ref, engine: engine),
                  let attachment = await MirrorPresentation.attachment(plaintext, ref: ref) else { continue }
            attachments.append(attachment)
        }
        return attachments
    }

    private func fetchAttachments(for message: ConversationMessage, engine: NotiSyncEngine) async -> [UNNotificationAttachment] {
        guard let ref = message.data,
              let plaintext = await privateAsset(ref, engine: engine),
              let attachment = await MirrorPresentation.attachment(plaintext, ref: ref) else { return [] }
        return [attachment]
    }

    private func privateAsset(_ ref: PrivateAssetRef, engine: NotiSyncEngine) async -> Data? {
        if let cached = cachedAssetRead(key: ref.assetHash, read: { AssetCache.read(ref.assetHash) }) { return cached }
        let start = Date()
        let ciphertext = await RelayClient.fetchAsset(ref,
                                                      identitySigner: engine.identitySigner,
                                                      operationalSigner: engine.operationalSigner,
                                                      keyEpochProvider: { try engine.buildClientKeyEpochBlob() })
        let plaintext = ciphertext.flatMap { engine.openAsset(ref, ciphertext: $0) }
        recordAssetTiming(key: ref.assetHash, cached: false, ms: Int64(Date().timeIntervalSince(start) * 1000))
        guard let plaintext else { return nil }
        AssetCache.write(ref.assetHash, plaintext)
        return plaintext
    }

    /// Time a synchronous cache read; on a hit, attribute it to the "cached" asset bucket. A miss isn't
    /// recorded here (the caller's network fetch records it as "fetched").
    private func cachedAssetRead(key: String, read: () -> Data?) -> Data? {
        let start = Date()
        let data = read()
        if data != nil { recordAssetTiming(key: key, cached: true, ms: Int64(Date().timeIntervalSince(start) * 1000)) }
        return data
    }

    /// Attribute one asset access to the cached or fetched bucket, counting each asset at most once per push
    /// (the second render pass re-reads already-fetched assets from cache — don't double-count those).
    private func recordAssetTiming(key: String, cached: Bool, ms: Int64) {
        guard perfAssetSeen.insert(key).inserted else { return }
        if cached {
            perfAssetCachedCount += 1
            perfAssetCachedMs += ms
        } else {
            perfAssetFetchedCount += 1
            perfAssetFetchedMs += ms
        }
    }

    private func finish(_ content: UNNotificationContent) {
        guard let contentHandler else { return }
        self.contentHandler = nil
        bestAttempt = nil
        contentHandler(content)
    }

    /// Hand the NSE's outcome + latency to the app for replay into Performance Monitoring. The `result`
    /// includes "expired" — the user saw the placeholder instead of the decrypted mirror — the most important
    /// health signal for the alert fast-path. Idempotent: reports once per push, whichever exit (or the
    /// timeout) fires first.
    ///
    /// This is a REPLAYED trace (the NSE has no Firebase), so the trace's own duration is ~0 — read the
    /// metrics: `duration_ms` is the NSE's own processing (didReceive → display); `e2e_ms` is source-capture →
    /// display (`postTime` → now, the user-perceived latency, a superset of `duration_ms`). This is the alert
    /// path's e2e home — the app-render `mirror_e2e_latency` deliberately does NOT cover the NSE (no dup).
    ///
    /// Attributes separate the alert variants: `transport` is the envelope source (inline `ct` vs oversized
    /// relay `mid` fetch), and `asset_fetch` is whether rendering the mirror touched the network for an
    /// icon/avatar/attachment (`network`), served them all from cache (`cache`), or needed none (`none`). The
    /// `asset_cached_*` / `asset_fetched_*` metrics split count + time by source (each asset counted once).
    private func reportPerf(_ result: String, postTime: Int64? = nil) {
        guard !perfReported else { return }
        perfReported = true
        var metrics: [String: Int64] = ["duration_ms": Int64(Date().timeIntervalSince(startedAt) * 1000)]
        if perfAssetCachedCount > 0 {
            metrics["asset_cached_ms"] = perfAssetCachedMs
            metrics["asset_cached_count"] = Int64(perfAssetCachedCount)
        }
        if perfAssetFetchedCount > 0 {
            metrics["asset_fetched_ms"] = perfAssetFetchedMs
            metrics["asset_fetched_count"] = Int64(perfAssetFetchedCount)
        }
        if let postTime {
            let value = Int64(Date().timeIntervalSince1970 * 1000) - postTime
            if value >= 0, value <= 6 * 60 * 60 * 1000 { metrics["e2e_ms"] = value }
        }
        let assetFetch = perfAssetFetchedCount > 0 ? "network" : (perfAssetCachedCount > 0 ? "cache" : "none")
        PerfEventStore.append(DeferredPerfTrace(
            name: "nse_delivery",
            attributes: ["result": result, "transport": perfTransport, "style": perfStyle,
                         "asset_fetch": assetFetch],
            metrics: metrics))
    }

    override func serviceExtensionTimeWillExpire() {
        processingTask?.cancel()
        releaseClaim(claimedDedupId)
        reportPerf("expired")
        if let bestAttempt { finish(bestAttempt) }
    }

    private func clearClaim(_ id: String?) {
        if claimedDedupId == id { claimedDedupId = nil }
    }

    private func releaseClaim(_ id: String?) {
        guard let id else { return }
        MessageDedupStore.release(id)
        clearClaim(id)
    }
}
