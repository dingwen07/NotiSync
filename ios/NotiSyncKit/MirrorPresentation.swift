import Foundation
import Intents
import UIKit
import UserNotifications

/// Builds the local notification(s) for a decoded mirror — shared by the app process (foreground / relay
/// drain) and the Notification Service Extension (alert push). Centralizes the identifier scheme, the
/// dismissal category, alerting (importance → interruption level + sound), grouping (Android group →
/// iOS `threadIdentifier`, Android channel → iOS `categoryIdentifier`), and origin-app branding: a
/// messaging mirror renders as a **Communication Notification** (message style + sender avatar); any other
/// mirror attaches the origin app icon as its thumbnail — Android's large-icon fallback — unless the
/// communication-app-icons preference (`MirrorDisplayStore`, "Improve Watch Compatibility") opts it into
/// communication styling too.
nonisolated enum MirrorPresentation {
    private struct AttachmentFile: Sendable {
        var identifier: String
        var url: URL
    }

    static let baseCategoryId = "notisync.mirror"
    static let dismissActionId = "notisync.dismiss"
    static let openActionId = "notisync.open"
    private static let dismissActionIcon = UNNotificationActionIcon(systemImageName: "xmark.circle")
    private static let openActionIcon = UNNotificationActionIcon(systemImageName: "arrow.up.forward.square")
    /// Prefix of a mirrored-action identifier; the suffix is the origin-scoped `NotificationAction.index`
    /// ("notisync.act.2"), parsed back out in `handleNotificationResponse` to build the `ActionEvent`.
    static let performActionPrefix = "notisync.act."
    /// Static category family listed in the content extension's Info.plist. The base value is also the APNs
    /// placeholder category because the broker cannot know plaintext action rows before the NSE decrypts.
    static let contentExtensionCategoryId = "notisync.mirror"
    private static let contentExtensionCategoryPoolSize = 32
    static let contentExtensionCategoryIds: [String] =
        (0..<contentExtensionCategoryPoolSize).map { "\(contentExtensionCategoryId).\($0)" }

    /// Stable, reversible identifier per (sourceClientId, sourceKey) — plan §D.3. This is the unique mirror
    /// request id used for dismissal reconciliation; it is independent of the grouping `threadIdentifier`.
    static func identifier(for n: CapturedNotification) -> String {
        MirrorMapStore.identifier(sourceClientId: n.sourceClientId, sourceKey: n.sourceKey)
    }

    /// Per-message request identifier (messaging multi-post): stable across re-deliveries of overlapping
    /// history so iOS replaces in place instead of duplicating. Shares the conversation's (sourceClientId,
    /// sourceKey) so a dismissal still clears every message of the conversation.
    static func messageIdentifier(base: String, message: ConversationMessage) -> String {
        let token = stableToken("\(message.sender ?? "")\u{1f}\(message.text)\u{1f}\(message.timestamp)\u{1f}\(message.data?.assetHash ?? "")")
        return "\(base)#m.\(token)"
    }

    /// #15 — grouping: the Android notification group maps to the iOS `threadIdentifier`, so notifications in
    /// the same source group thread together. Falls back to a per-(source, origin, app) bucket when the
    /// source supplied no group, so same-app mirrors still group instead of each being its own thread.
    static func threadIdentifier(for n: CapturedNotification) -> String {
        let origin = n.originDeviceId?.nonBlank ?? "_local"
        if let group = n.groupKey?.nonBlank {
            return "g.\(stableToken("\(n.sourceClientId)\u{1f}\(origin)\u{1f}\(group)"))"
        }
        return "a.\(stableToken("\(n.sourceClientId)\u{1f}\(origin)\u{1f}\(n.packageName)"))"
    }

    /// #15 — the Android notification channel maps to the iOS `categoryIdentifier`. Namespaced under our base
    /// category id (so we own it) and registered with the Dismiss action via `MirrorCategoryRegistry`. Falls
    /// back to the base category when the source carried no channel. A mirror carrying ACTIONS instead gets a
    /// category per distinct action-row signature — iOS actions live on the category, so two mirrors share one
    /// exactly when their buttons are identical; the channel plays no role there (it carries no behaviour).
    /// The explicit "Open on <device>" TAP action also participates in the category identity because iOS
    /// stores action titles on the category, not on each delivered notification.
    static func categoryIdentifier(for n: CapturedNotification) -> String {
        let openTitle = openActionTitle(for: n)
        let openForegroundsApp = openActionForegroundsApp(for: n)
        return categoryIdentifier(
            for: n,
            openActionTitle: openTitle,
            openActionForegroundsApp: openForegroundsApp
        )
    }

    static func categoryIdentifier(
        for n: CapturedNotification,
        openActionTitle openTitle: String?,
        openActionForegroundsApp openForegroundsApp: Bool
    ) -> String {
        if !n.actions.isEmpty {
            let signature = actionSignature(
                n.actions,
                openActionTitle: openTitle,
                openActionForegroundsApp: openForegroundsApp
            )
            return "\(baseCategoryId).ax.\(stableToken(signature))"
        }
        if let openTitle {
            let channel = n.channelId?.nonBlank ?? "_default"
            let signature = "\(n.packageName)\u{1f}\(channel)\u{1f}\(openTitle)\u{1f}\(openForegroundsApp ? 1 : 0)"
            return "\(baseCategoryId).op.\(stableToken(signature))"
        }
        guard let channel = n.channelId?.nonBlank else { return baseCategoryId }
        return "\(baseCategoryId).ch.\(stableToken("\(n.packageName)\u{1f}\(channel)"))"
    }

    /// Everything that shapes the rendered action row — index (round-trip target), title, text-input
    /// flag + placeholder, semantic hint, and the optional explicit TAP action. Same signature ⇒ same category.
    static func actionSignature(
        _ actions: [NotificationAction],
        openActionTitle: String? = nil,
        openActionForegroundsApp: Bool = false
    ) -> String {
        var parts = actions.map {
            "\($0.index)\u{1f}\($0.title)\u{1f}\($0.remoteInput ? 1 : 0)\u{1f}\($0.remoteInputLabel ?? "")\u{1f}\($0.semanticAction)"
        }
        if let openActionTitle {
            parts.append("open\u{1f}\(openActionTitle)\u{1f}\(openActionForegroundsApp ? 1 : 0)")
        }
        return parts.joined(separator: "\u{1e}")
    }

    static func openActionTitle(for n: CapturedNotification) -> String? {
        guard n.hasContentIntent else { return nil }
        return openActionTitle(deviceName: originDeviceName(for: n))
    }

    static func openActionForegroundsApp(for n: CapturedNotification) -> Bool {
        guard n.hasContentIntent,
              let selfRecord = SelfRecord.load() else { return false }
        let store = TrustStore.load(
            selfClientId: selfRecord.clientId,
            selfIdentitySpki: selfRecord.identitySpki
        )
        guard let peer = store.peers[n.sourceClientId] else { return false }
        return ScreenMirrorSourceRecord.supports(peer)
    }

    private static func openActionTitle(deviceName: String) -> String {
        String(localized: "notification.action.openOnDevice",
               defaultValue: "Open on \(deviceName)",
               comment: "Action title that opens the original notification on its source device.")
    }

    /// A notification category carrying the mirrored action row (if any) plus the Dismiss action. Every
    /// mirror category includes `.customDismissAction` so a swipe is reported for dismissal sync.
    /// A remote-input action renders as a `UNTextInputNotificationAction` (iOS's inline reply). The explicit
    /// Open action foregrounds this app only when its posting peer can also supply a mirrored screen; every
    /// other action stays background-only. Android's `SEMANTIC_ACTION_DELETE` maps to `.destructive`.
    static func category(
        id: String = baseCategoryId,
        actions: [NotificationAction] = [],
        openActionTitle: String? = nil,
        openActionForegroundsApp: Bool = false
    ) -> UNNotificationCategory {
        let sendTitle = String(localized: "notification.action.send", defaultValue: "Send",
                               comment: "Button that sends the typed reply to the origin device.")
        var unActions: [UNNotificationAction] = actions.map { action in
            let identifier = "\(performActionPrefix)\(action.index)"
            var options: UNNotificationActionOptions = []
            if action.semanticAction == semanticActionDelete { options.insert(.destructive) }
            if action.remoteInput {
                return UNTextInputNotificationAction(
                    identifier: identifier, title: action.title, options: options,
                    textInputButtonTitle: sendTitle,
                    textInputPlaceholder: action.remoteInputLabel ?? ""
                )
            }
            return UNNotificationAction(identifier: identifier, title: action.title, options: options)
        }
        if let openActionTitle {
            let options: UNNotificationActionOptions = openActionForegroundsApp ? [.foreground] : []
            unActions.append(UNNotificationAction(identifier: openActionId, title: openActionTitle,
                                                  options: options, icon: openActionIcon))
        }
        let title = String(localized: "notification.action.dismiss", defaultValue: "Dismiss", comment: "Action title that dismisses a mirrored notification.")
        unActions.append(UNNotificationAction(identifier: dismissActionId, title: title,
                                              options: [], icon: dismissActionIcon))
        return UNNotificationCategory(identifier: id, actions: unActions, intentIdentifiers: [],
                                      options: [.customDismissAction])
    }

    /// Android `Notification.Action.SEMANTIC_ACTION_DELETE`.
    private static let semanticActionDelete = 4

    private static func originDeviceName(for n: CapturedNotification) -> String {
        n.originDeviceName?.nonBlank
            ?? trustedPeerName(clientId: n.sourceClientId)
            ?? shortDeviceName(clientId: n.sourceClientId)
    }

    private static func trustedPeerName(clientId: String) -> String? {
        guard let selfRecord = SelfRecord.load() else { return nil }
        let store = TrustStore.load(selfClientId: selfRecord.clientId,
                                    selfIdentitySpki: selfRecord.identitySpki)
        return store.peers[clientId]?.displayName.nonBlank
    }

    private static func shortDeviceName(clientId: String) -> String {
        let trimmed = clientId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "device" }
        return String(trimmed.prefix(8))
    }

    // MARK: Single (non-messaging) content

    /// The notification content to deliver for a non-messaging mirror. By default plain content with the
    /// origin app icon attached as the thumbnail — matching the Android mirror's large-icon fallback — when
    /// the mirror carries no graphic of its own. With `communicationStyle` (the communication-app-icons
    /// preference) it instead renders as a Communication Notification (app icon as the sender avatar),
    /// falling back to the plain content if the Communication Notifications entitlement isn't provisioned.
    static func content(for n: CapturedNotification, messageId: String,
                        attachments: [UNNotificationAttachment] = [], appIcon: Data? = nil,
                        communicationStyle: Bool, categoryIdentifier: String? = nil) async -> UNNotificationContent {
        let categoryId = categoryIdentifier ?? Self.categoryIdentifier(for: n)
        if communicationStyle {
            let base = baseContent(for: n, messageId: messageId, attachments: attachments,
                                   categoryIdentifier: categoryId)
            return communicationStyled(base, for: n, senderName: communicationSenderName(for: n),
                                       senderImage: appIcon) ?? base
        }
        var attachments = attachments
        if attachments.isEmpty, let appIcon, let icon = await appIconAttachment(appIcon) {
            attachments = [icon]
        }
        return baseContent(for: n, messageId: messageId, attachments: attachments,
                           categoryIdentifier: categoryId)
    }

    /// The communication-styled sender string: "notification title · origin app name" (just one of them
    /// when the other is blank or they match). A communication notification renders only "sender + body" —
    /// no app header, and the content subtitle is dropped — so the sender string alone must carry the app
    /// identity. The plain rendering names the app in its subtitle instead (see `baseContent`).
    private static func communicationSenderName(for n: CapturedNotification) -> String {
        let title = n.title?.nonBlank
        if let title, let app = n.appLabel.nonBlank, title != app {
            return String(localized: "notification.mirror.titleWithApp",
                          defaultValue: "\(title) · \(app)",
                          comment: "Title of a communication-styled mirrored notification: original title · source app name.")
        }
        return title ?? n.appLabel
    }

    private static func baseContent(for n: CapturedNotification, messageId: String,
                                    attachments: [UNNotificationAttachment],
                                    categoryIdentifier: String) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        let title = n.title?.nonBlank ?? n.appLabel
        content.title = title
        content.body = (n.bigText?.nonBlank ?? n.text?.nonBlank) ?? n.appLabel
        // The origin app name, in the subtitle — Android's "via <app>" subText parity, without the "via"
        // since iOS renders the subtitle on its own dedicated line. Only the plain rendering shows it:
        // communication styling drops the subtitle and carries the app name in its sender string instead
        // (see `communicationSenderName`). When the app name is already the title, fall back to the
        // source's own subText / origin device name as before.
        if let app = n.appLabel.nonBlank, title != app {
            content.subtitle = app
        } else if let sub = n.subText?.nonBlank ?? n.originDeviceName?.nonBlank {
            content.subtitle = sub
        }
        content.categoryIdentifier = categoryIdentifier
        content.threadIdentifier = threadIdentifier(for: n)
        content.attachments = attachments
        content.userInfo = userInfo(for: n, messageId: messageId)
        applyAlerting(content, importance: n.effectiveImportance)
        return content
    }

    // MARK: Messaging (per-message) content

    /// One communication notification for a single conversation message (messaging multi-post, #13). All
    /// messages of a conversation share the same `threadIdentifier`; only `alerting` messages play a sound,
    /// so backfilled history posts silently while the newest message alerts.
    static func messageContent(for n: CapturedNotification, message: ConversationMessage,
                               messageId: String, attachments: [UNNotificationAttachment] = [],
                               senderImage: Data?, alerting: Bool,
                               categoryIdentifier: String? = nil) -> UNNotificationContent {
        let categoryId = categoryIdentifier ?? Self.categoryIdentifier(for: n)
        let content = UNMutableNotificationContent()
        // A nil sender is the user's own message: label it "You" (Android's mirror_self_name parity)
        // instead of letting it masquerade under the conversation or app name. Rendered only by the NSE's
        // silent self-echo — the app's multi-post skips self messages entirely.
        let senderName: String = if message.sender == nil {
            String(localized: "notification.message.selfSender", defaultValue: "You",
                   comment: "Sender label for the user's own message in a mirrored conversation.")
        } else {
            message.sender?.nonBlank ?? n.conversationTitle?.nonBlank ?? n.title?.nonBlank ?? n.appLabel
        }
        content.title = senderName
        content.body = message.text
        if n.isGroupConversation, let convTitle = n.conversationTitle?.nonBlank { content.subtitle = convTitle }
        content.categoryIdentifier = categoryId
        content.threadIdentifier = threadIdentifier(for: n)
        content.attachments = attachments
        content.userInfo = userInfo(for: n, messageId: messageId)
        applyAlerting(content, importance: alerting ? n.effectiveImportance : .MIN)
        return communicationStyled(content, for: n, senderName: senderName, senderImage: senderImage) ?? content
    }

    /// Whether a messaging mirror should make noise. `sender == nil` is Android's explicit self-message
    /// marker. Some apps repost self messages as a named Person instead, so treat localized "You" labels as
    /// self echoes too. Do not use `onlyAlertOnce` here: on Android it is only the source key's update flag,
    /// not proof that this iOS per-message alert is a self echo.
    static func messageShouldAlert(_ message: ConversationMessage) -> Bool {
        !senderLooksSelf(message.sender)
    }

    private static func senderLooksSelf(_ sender: String?) -> Bool {
        guard let trimmed = sender?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else {
            return sender == nil
        }
        return trimmed == "你" || trimmed.caseInsensitiveCompare("You") == .orderedSame
    }

    // MARK: Alerting (#11)

    /// Map a mirror importance to an iOS interruption level + sound. HIGH/DEFAULT alert as a regular
    /// banner with sound (NOT `.timeSensitive` — a mirrored notification is not time-sensitive and that
    /// level needs a dedicated entitlement). LOW/MIN/NONE deliver silently into the notification list.
    static func applyAlerting(_ content: UNMutableNotificationContent, importance: MirrorImportance) {
        switch importance {
        case .HIGH, .DEFAULT:
            content.interruptionLevel = .active
            content.sound = .default
        case .LOW, .MIN, .NONE:
            content.interruptionLevel = .passive
            content.sound = nil
        }
    }

    /// Demote a notification that must still complete an APNs alert delivery (NSE path) but should not alert.
    static func passiveContent(_ content: UNNotificationContent, removeActions: Bool = false) -> UNNotificationContent {
        guard let mutable = content.mutableCopy() as? UNMutableNotificationContent else { return content }
        mutable.interruptionLevel = .passive
        mutable.sound = nil
        if removeActions { mutable.categoryIdentifier = "" }
        return mutable
    }

    // MARK: userInfo

    private static func userInfo(for n: CapturedNotification, messageId: String) -> [String: Any] {
        let info: [String: Any] = [
            "sourceClientId": n.sourceClientId,
            "sourceKey": n.sourceKey,
            "messageId": messageId,
            "packageName": n.packageName,
            "iosBundleId": n.iosBundleId ?? "",
            "originPlatform": n.originPlatform.rawValue,
            "originDeviceName": n.originDeviceName ?? "",
            "originDeviceId": n.originDeviceId ?? "",
            "channelId": n.channelId ?? "",
            "channelName": n.channelName ?? "",
            "appLabel": n.appLabel,
            "isClearable": n.isClearable,   // #14 — a swipe of a non-clearable mirror must not sync-dismiss
            // Action round-trip: whether a tap should be replayed on the origin, and each mirrored
            // action keyed by index (title = the echo the origin verifies before firing; label + semantic
            // rebuild the native iOS category row and replay text-input responses).
            "hasContentIntent": n.hasContentIntent,
            "actions": n.actions.map { action -> [String: Any] in
                var item: [String: Any] =
                    ["index": action.index, "title": action.title, "remoteInput": action.remoteInput,
                     "remoteInputLabel": action.remoteInputLabel ?? "", "semanticAction": action.semanticAction]
                if let generation = action.actionGeneration { item["actionGeneration"] = generation }
                if let token = action.actionToken { item["actionToken"] = token }
                return item
            },
        ]
        return info
    }

    // MARK: Communication styling

    /// Wrap as an incoming INSendMessageIntent so iOS renders a communication notification with the
    /// sender's avatar. Used by every MESSAGING mirror, and by non-messaging ones only when the
    /// communication-app-icons preference opts in. Returns nil if the entitlement is missing
    /// (`updating(from:)` throws) → caller uses base.
    private static func communicationStyled(_ base: UNMutableNotificationContent, for n: CapturedNotification,
                                            senderName: String, senderImage: Data?) -> UNNotificationContent? {
        let handle = INPersonHandle(value: n.sourceClientId, type: .unknown)
        let image = senderImage.flatMap { INImage(imageData: normalizedImageData($0)) }
        let sender = INPerson(personHandle: handle, nameComponents: nil, displayName: senderName,
                              image: image, contactIdentifier: nil, customIdentifier: n.sourceClientId)
        let intent = INSendMessageIntent(
            recipients: nil,
            outgoingMessageType: .outgoingMessageText,
            content: base.body,
            speakableGroupName: n.appLabel.isEmpty ? nil : INSpeakableString(spokenPhrase: n.appLabel),
            conversationIdentifier: threadIdentifier(for: n),
            serviceName: nil,
            sender: sender,
            attachments: nil
        )
        let interaction = INInteraction(intent: intent, response: nil)
        interaction.direction = .incoming
        interaction.donate(completion: nil)
        guard let updated = try? base.updating(from: intent),
              let mutable = updated.mutableCopy() as? UNMutableNotificationContent else { return nil }
        // `updating(from:)` can rebuild the content around the intent. Keep the mirror metadata that
        // drives notification actions and dismissal sync.
        mutable.categoryIdentifier = base.categoryIdentifier
        mutable.threadIdentifier = base.threadIdentifier
        mutable.userInfo = base.userInfo
        mutable.interruptionLevel = base.interruptionLevel
        mutable.sound = base.sound
        mutable.attachments = base.attachments
        return mutable
    }

    // MARK: Image normalization (#12)

    /// Bytes safe to hand to `INImage` / `UNNotificationAttachment`, which only accept PNG/JPEG/GIF.
    /// Android delivers all private graphics as WebP, which those APIs reject — decode + re-encode to PNG.
    static func normalizedImageData(_ data: Data) -> Data {
        if data.starts(with: [0x89, 0x50, 0x4E, 0x47]) || data.starts(with: [0xFF, 0xD8, 0xFF]) { return data }
        return UIImage(data: data)?.pngData() ?? data
    }

    /// The origin app icon as an attachment — iOS's closest analog to Android's mirror large icon. Only
    /// used when the mirror carries no graphic of its own: iOS renders just the first attachment, so a
    /// mirrored large icon / big picture beats the app icon.
    private static func appIconAttachment(_ data: Data) async -> UNNotificationAttachment? {
        let file = await Task.detached(priority: .utility) { () -> AttachmentFile? in
            let normalized = normalizedImageData(data)
            let ext = normalized.starts(with: [0xFF, 0xD8, 0xFF]) ? "jpg" : "png"
            // Unique path per post: attachment creation MOVES the file, so a shared name could race a
            // concurrent post of another mirror from the same app.
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("appicon-\(UUID().uuidString).\(ext)")
            guard (try? normalized.write(to: url)) != nil else { return nil }
            return AttachmentFile(identifier: "notisync.appicon", url: url)
        }.value
        guard let file else { return nil }
        return try? UNNotificationAttachment(identifier: file.identifier, url: file.url, options: nil)
    }

    static func attachment(_ data: Data, ref: PrivateAssetRef) async -> UNNotificationAttachment? {
        guard let file = await Task.detached(priority: .utility, operation: {
            prepareAttachmentFile(data, ref: ref)
        }).value else { return nil }
        return try? UNNotificationAttachment(identifier: file.identifier, url: file.url, options: nil)
    }

    private static func prepareAttachmentFile(_ data: Data, ref: PrivateAssetRef) -> AttachmentFile? {
        // UNNotificationAttachment only accepts PNG/JPEG/GIF. Android delivers graphics as WebP, which it
        // rejects, so decode + re-encode those (and any unknown type) to PNG so the image still renders (#12).
        let mime = ref.mimeType.lowercased()
        let writeData: Data
        let ext: String
        if mime.contains("png") {
            writeData = data; ext = "png"
        } else if mime.contains("jpeg") || mime.contains("jpg") {
            writeData = data; ext = "jpg"
        } else if mime.contains("gif") {
            writeData = data; ext = "gif"
        } else {
            guard let png = UIImage(data: data)?.pngData() else { return nil }
            writeData = png; ext = "png"
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(ref.assetId).\(ext)")
        guard (try? writeData.write(to: url)) != nil else { return nil }
        return AttachmentFile(identifier: ref.assetId, url: url)
    }

    // MARK: Helpers

    /// Short, stable, URL-safe token (base64url of SHA-256 prefix) — mirrors Android's `stableToken`.
    static func stableToken(_ value: String) -> String {
        NSBase64URL.encode(NSHash.sha256(Data(value.utf8)).prefix(12))
    }
}

/// Persisted set of mirror notification categories (App Group), so both the app and the NSE can
/// (re)register the full set with `setNotificationCategories` — which replaces the whole category set on
/// each call. Two families: per-channel ids (Dismiss action only — just the id needs persisting) and
/// action categories (the id alone can't rebuild the buttons, so the action definitions persist as JSON).
/// Every registered category carries the Dismiss action, so a swipe is still reported regardless of which
/// category a notification used. Eventually consistent across the two processes.
nonisolated enum MirrorCategoryRegistry {
    private static let key = "notisync.mirrorCategoryIds"
    private static let actionKey = "notisync.mirrorActionCategories"
    /// Distinct action-row signatures are few (apps reuse Reply / Mark as read); the cap only guards
    /// against pathological growth. Eviction is oldest-first (insertion order).
    private static let maxActionCategories = 128
    private static var defaults: UserDefaults? { UserDefaults(suiteName: NotiSyncConfig.appGroup) }

    /// A persisted action category — enough to rebuild its `UNNotificationCategory` after a relaunch.
    private struct StoredActionCategory: Codable {
        struct StoredAction: Codable {
            var index: Int
            var title: String
            var remoteInput: Bool
            var remoteInputLabel: String?
            var semanticAction: Int
        }

        var id: String
        var actions: [StoredAction]
        var openActionTitle: String?
        /// Optional so categories persisted by older app versions still decode as background-only.
        var openActionForegroundsApp: Bool?

        var notificationActions: [NotificationAction] {
            actions.map {
                NotificationAction(index: $0.index, title: $0.title, remoteInput: $0.remoteInput,
                                   remoteInputLabel: $0.remoteInputLabel, semanticAction: $0.semanticAction)
            }
        }

        var behaviorSignature: String {
            MirrorPresentation.actionSignature(
                notificationActions,
                openActionTitle: openActionTitle,
                openActionForegroundsApp: openActionForegroundsApp ?? false
            )
        }
    }

    /// Ensure the category this mirror posts under is registered (idempotent), including its action row,
    /// and return the id the notification content must use. Text-input rows, plus app-icon-only notifications
    /// that opt into the content extension, use a fixed pool declared in the content extension's Info.plist;
    /// each occupied pool category still carries its exact native action row.
    @discardableResult
    static func ensureRegistered(for n: CapturedNotification, contentExtension: Bool = false) -> String {
        let openTitle = MirrorPresentation.openActionTitle(for: n)
        let openForegroundsApp = MirrorPresentation.openActionForegroundsApp(for: n)
        if contentExtension {
            if n.actions.isEmpty, openTitle == nil {
                registerAll()
                return MirrorPresentation.baseCategoryId
            }
            return ensureContentExtensionActionCategory(
                actions: n.actions,
                openActionTitle: openTitle,
                openActionForegroundsApp: openForegroundsApp
            )
        }
        let id = MirrorPresentation.categoryIdentifier(
            for: n,
            openActionTitle: openTitle,
            openActionForegroundsApp: openForegroundsApp
        )
        if n.actions.isEmpty, openTitle == nil {
            ensureRegistered(id)
            return id
        }
        if n.actions.contains(where: { $0.remoteInput }) {
            return ensureContentExtensionActionCategory(
                actions: n.actions,
                openActionTitle: openTitle,
                openActionForegroundsApp: openForegroundsApp
            )
        } else {
            ensureActionCategory(
                id: id,
                actions: n.actions,
                openActionTitle: openTitle,
                openActionForegroundsApp: openForegroundsApp
            )
            return id
        }
    }

    /// Ensure `id`'s (actionless, per-channel) category is registered. No-op for the base category.
    static func ensureRegistered(_ id: String) {
        guard id != MirrorPresentation.baseCategoryId else { registerAll(); return }
        let snapshot = AppGroupStore.withLock(key) { () -> (Set<String>, [StoredActionCategory])? in
            var ids = Set(defaults?.stringArray(forKey: key) ?? [])
            guard !ids.contains(id) else { return nil }
            ids.insert(id)
            defaults?.set(Array(ids), forKey: key)
            return (ids, loadActionCategories())
        }
        if let snapshot { register(channelIds: snapshot.0, actionCategories: snapshot.1) }
    }

    private static func ensureActionCategory(
        id: String,
        actions: [NotificationAction],
        openActionTitle: String?,
        openActionForegroundsApp: Bool
    ) {
        let stored = StoredActionCategory(
            id: id,
            actions: actions.map {
                .init(index: $0.index, title: $0.title, remoteInput: $0.remoteInput,
                      remoteInputLabel: $0.remoteInputLabel, semanticAction: $0.semanticAction)
            },
            openActionTitle: openActionTitle,
            openActionForegroundsApp: openActionForegroundsApp
        )
        // Same lock as the channel-id family: both mutations replace the one registered category set.
        let snapshot = AppGroupStore.withLock(key) { () -> (Set<String>, [StoredActionCategory])? in
            var stack = loadActionCategories()
            guard !stack.contains(where: { $0.id == id }) else { return nil }
            stack.append(stored)
            if stack.count > maxActionCategories { stack.removeFirst(stack.count - maxActionCategories) }
            saveActionCategories(stack)
            return (Set(defaults?.stringArray(forKey: key) ?? []), stack)
        }
        if let snapshot { register(channelIds: snapshot.0, actionCategories: snapshot.1) }
    }

    private static func ensureContentExtensionActionCategory(
        actions: [NotificationAction],
        openActionTitle: String?,
        openActionForegroundsApp: Bool
    ) -> String {
        let signature = MirrorPresentation.actionSignature(
            actions,
            openActionTitle: openActionTitle,
            openActionForegroundsApp: openActionForegroundsApp
        )
        let storedActions = actions.map {
            StoredActionCategory.StoredAction(index: $0.index, title: $0.title, remoteInput: $0.remoteInput,
                                              remoteInputLabel: $0.remoteInputLabel,
                                              semanticAction: $0.semanticAction)
        }
        let snapshot = AppGroupStore.withLock(key) { () -> (String, Set<String>, [StoredActionCategory], Bool) in
            var stack = loadActionCategories()
            if let existing = stack.first(where: {
                isContentExtensionPoolCategory($0.id)
                    && $0.behaviorSignature == signature
            }) {
                return (existing.id, Set(defaults?.stringArray(forKey: key) ?? []), stack, true)
            }

            let used = Set(stack.filter { isContentExtensionPoolCategory($0.id) }.map(\.id))
            let id: String
            if let free = MirrorPresentation.contentExtensionCategoryIds.first(where: { !used.contains($0) }) {
                id = free
            } else if let evict = stack.firstIndex(where: { isContentExtensionPoolCategory($0.id) }) {
                id = stack[evict].id
                stack.remove(at: evict)
            } else {
                id = MirrorPresentation.contentExtensionCategoryIds[0]
            }
            stack.append(StoredActionCategory(
                id: id,
                actions: storedActions,
                openActionTitle: openActionTitle,
                openActionForegroundsApp: openActionForegroundsApp
            ))
            if stack.count > maxActionCategories { stack.removeFirst(stack.count - maxActionCategories) }
            saveActionCategories(stack)
            return (id, Set(defaults?.stringArray(forKey: key) ?? []), stack, true)
        }
        if snapshot.3 { register(channelIds: snapshot.1, actionCategories: snapshot.2) }
        return snapshot.0
    }

    /// Re-register the base category plus all known channel + action categories (call on app launch).
    static func registerAll() {
        register(channelIds: Set(defaults?.stringArray(forKey: key) ?? []),
                 actionCategories: loadActionCategories())
    }

    private static func register(channelIds: Set<String>, actionCategories: [StoredActionCategory]) {
        var categories: [String: UNNotificationCategory] = [
            MirrorPresentation.baseCategoryId: MirrorPresentation.category(),
        ]
        for id in channelIds { categories[id] = MirrorPresentation.category(id: id) }
        for stored in actionCategories {
            categories[stored.id] = MirrorPresentation.category(id: stored.id,
                                                                actions: stored.notificationActions,
                                                                openActionTitle: stored.openActionTitle,
                                                                openActionForegroundsApp:
                                                                    stored.openActionForegroundsApp ?? false)
        }
        UNUserNotificationCenter.current().setNotificationCategories(Set(categories.values))
    }

    private static func isContentExtensionPoolCategory(_ id: String) -> Bool {
        MirrorPresentation.contentExtensionCategoryIds.contains(id)
    }

    private static func loadActionCategories() -> [StoredActionCategory] {
        (defaults?.stringArray(forKey: actionKey) ?? []).compactMap {
            guard let data = $0.data(using: .utf8) else { return nil }
            return try? JSONDecoder().decode(StoredActionCategory.self, from: data)
        }
    }

    private static func saveActionCategories(_ stack: [StoredActionCategory]) {
        let entries = stack.compactMap { item -> String? in
            guard let data = try? JSONEncoder().encode(item) else { return nil }
            return String(data: data, encoding: .utf8)
        }
        defaults?.set(entries, forKey: actionKey)
    }
}

private nonisolated extension String {
    var nonBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}
