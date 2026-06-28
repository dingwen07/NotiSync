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

    override func didReceive(_ request: UNNotificationRequest,
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        let best = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        self.bestAttempt = best

        guard let engine = NotiSyncEngine(forExtension: true) else { finish(best); return }
        let info = request.content.userInfo

        processingTask = Task {
            // Inline ciphertext, else pull the oversized envelope from the relay. Record
            // WHICH transport the NSE used so the Inbox differentiates an inline-ciphertext push from a
            // relay-fetched (oversized) one — both are "APNs alert" (NSE-displayed), but by different paths.
            var bytes: Data?
            let inline = info["ct"] != nil
            if let ct = info["ct"] as? String { bytes = Data(base64Encoded: ct) }
            if bytes == nil, let mid = info["mid"] as? String {
                bytes = await RelayClient.fetchMessage(mid,
                                                       identitySigner: engine.identitySigner,
                                                       operationalSigner: engine.operationalSigner,
                                                       keyEpochProvider: { try engine.buildClientKeyEpochBlob() })
            }
            guard let envelopeBytes = bytes else { finish(best); return }
            let deliveryMode = inline ? "APNs alert (inline)" : "APNs alert (relay)"
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
                    finish(best)
                    return
                case .inFlight:
                    finish(best)
                    return
                }
            }

            do {
                let (env, inbound) = try engine.openEnvelope(envelopeBytes)
                guard case let .notification(n) = inbound else {
                    releaseClaim(dedupId)
                    finish(best)
                    return
                }
                let messageId = dedupId ?? env.messageId
                // Register the per-channel category (with the Dismiss action) for this push (#15).
                MirrorCategoryRegistry.ensureRegistered(MirrorPresentation.categoryIdentifier(for: n))
                // A messaging push renders its newest message as a communication notification (the alert
                // fast-path shows one message; the app posts the full thread on its next foreground, #13).
                let prepared: UNNotificationContent
                let fetch: SenderImageFetch?
                if n.style == .MESSAGING, let last = n.messages.last {
                    let senderImage = senderImage(for: n, message: last)
                    prepared = MirrorPresentation.messageContent(for: n, message: last, messageId: messageId,
                                                                 senderImage: senderImage.data, alerting: true)
                    fetch = senderImage.data == nil ? senderImage.fetch : nil
                    // Record the per-message map id of the message we just displayed so the app's multi-post
                    // path (#13) doesn't re-post it under a different id once it processes a later envelope.
                    let perMsgId = MirrorPresentation.messageIdentifier(base: MirrorPresentation.identifier(for: n), message: last)
                    MirrorMapStore.put(MirrorMapEntry(identifier: perMsgId, sourceClientId: n.sourceClientId,
                                                      sourceKey: n.sourceKey, messageId: messageId, deliveryMode: deliveryMode,
                                                      isClearable: n.isClearable))
                } else {
                    let senderImage = senderImage(for: n)
                    prepared = MirrorPresentation.content(for: n, messageId: messageId, senderImage: senderImage.data)
                    fetch = senderImage.data == nil ? senderImage.fetch : nil
                }
                bestAttempt = prepared

                // The system fixes the delivered notification's identifier to this push's request id (APNs),
                // so record the mapping under THAT id for dismissal reconciliation + remote-dismissal removal.
                MirrorMapStore.put(MirrorMapEntry(identifier: request.identifier, sourceClientId: n.sourceClientId,
                                                  sourceKey: n.sourceKey, messageId: messageId, deliveryMode: deliveryMode,
                                                  isClearable: n.isClearable))
                ShownStore.markShowing(request.identifier)
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
                        content = MirrorPresentation.messageContent(for: n, message: last, messageId: messageId,
                                                                    senderImage: data, alerting: true)
                    } else {
                        content = MirrorPresentation.content(for: n, messageId: messageId, senderImage: data)
                    }
                } else {
                    content = prepared
                }
                await RelayClient.ack([messageId],
                                      identitySigner: engine.identitySigner,
                                      operationalSigner: engine.operationalSigner,
                                      keyEpochProvider: { try engine.buildClientKeyEpochBlob() })
                finish(content)
            } catch {
                releaseClaim(dedupId)
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
            return (AssetCache.read(ref.assetHash), .privateAsset(ref))
        }
        return senderImage(for: n)
    }

    /// Source app icon for communication styling: App Store artwork for iOS-origin apps, otherwise the
    /// captured private launcher icon. Cache hits are used immediately; misses are fetched after prepare.
    private func senderImage(for n: CapturedNotification) -> (data: Data?, fetch: SenderImageFetch?) {
        if let bundleId = n.iosBundleId, !bundleId.isEmpty {
            return (AppIconFetcher.cachedIconData(iosBundleId: bundleId), .appStoreIcon(bundleId))
        }
        if let ref = n.appIcon ?? n.largeIcon {
            return (AssetCache.read(ref.assetHash), .privateAsset(ref))
        }
        return (nil, nil)
    }

    private func fetchSenderImage(_ fetch: SenderImageFetch?, engine: NotiSyncEngine) async -> Data? {
        switch fetch {
        case .appStoreIcon(let bundleId):
            return await AppIconFetcher.iconData(iosBundleId: bundleId)
        case .privateAsset(let ref):
            return await privateAsset(ref, engine: engine)
        case .none:
            return nil
        }
    }

    private func privateAsset(_ ref: PrivateAssetRef, engine: NotiSyncEngine) async -> Data? {
        if let cached = AssetCache.read(ref.assetHash) { return cached }
        guard let ciphertext = await RelayClient.fetchAsset(ref,
                                                            identitySigner: engine.identitySigner,
                                                            operationalSigner: engine.operationalSigner,
                                                            keyEpochProvider: { try engine.buildClientKeyEpochBlob() }),
              let plaintext = engine.openAsset(ref, ciphertext: ciphertext) else { return nil }
        AssetCache.write(ref.assetHash, plaintext)
        return plaintext
    }

    private func finish(_ content: UNNotificationContent) {
        guard let contentHandler else { return }
        self.contentHandler = nil
        bestAttempt = nil
        contentHandler(content)
    }

    override func serviceExtensionTimeWillExpire() {
        processingTask?.cancel()
        releaseClaim(claimedDedupId)
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
