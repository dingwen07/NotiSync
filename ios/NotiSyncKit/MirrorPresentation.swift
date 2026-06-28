import Foundation
import Intents
import UIKit
import UserNotifications

/// Builds the local notification(s) for a decoded mirror — shared by the app process (foreground / relay
/// drain) and the Notification Service Extension (alert push). Centralizes the identifier scheme, the
/// dismissal category, alerting (importance → interruption level + sound), grouping (Android group →
/// iOS `threadIdentifier`, Android channel → iOS `categoryIdentifier`), and rendering as a
/// **Communication Notification** (message style + sender avatar).
nonisolated enum MirrorPresentation {
    static let baseCategoryId = "notisync.mirror"
    static let dismissActionId = "notisync.dismiss"

    /// Stable, reversible identifier per (sourceClientId, sourceKey) — plan §D.3. This is the unique mirror
    /// request id used for dismissal reconciliation; it is independent of the grouping `threadIdentifier`.
    static func identifier(for n: CapturedNotification) -> String {
        MirrorMapStore.identifier(sourceClientId: n.sourceClientId, sourceKey: n.sourceKey)
    }

    /// Per-message request identifier (messaging multi-post): stable across re-deliveries of overlapping
    /// history so iOS replaces in place instead of duplicating. Shares the conversation's (sourceClientId,
    /// sourceKey) so a dismissal still clears every message of the conversation.
    static func messageIdentifier(base: String, message: ConversationMessage) -> String {
        let token = stableToken("\(message.sender ?? "")\u{1f}\(message.text)\u{1f}\(message.timestamp)")
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
    /// back to the base category when the source carried no channel.
    static func categoryIdentifier(for n: CapturedNotification) -> String {
        guard let channel = n.channelId?.nonBlank else { return baseCategoryId }
        return "\(baseCategoryId).ch.\(stableToken("\(n.packageName)\u{1f}\(channel)"))"
    }

    /// A notification category (base or per-channel) carrying the Dismiss action. Every mirror category
    /// includes `.customDismissAction` so a swipe is reported for dismissal sync.
    static func category(id: String = baseCategoryId) -> UNNotificationCategory {
        let title = String(localized: "notification.action.dismiss", defaultValue: "Dismiss", comment: "Action title that dismisses a mirrored notification.")
        let dismiss = UNNotificationAction(identifier: dismissActionId, title: title, options: [])
        return UNNotificationCategory(identifier: id, actions: [dismiss], intentIdentifiers: [],
                                      options: [.customDismissAction])
    }

    // MARK: Single (non-messaging) content

    /// The notification content to deliver for a non-messaging mirror. Tries a Communication Notification
    /// (so the source app icon / contact avatar shows instead of NotiSync's icon); falls back to the plain
    /// content if the Communication Notifications entitlement isn't provisioned.
    static func content(for n: CapturedNotification, messageId: String,
                        attachments: [UNNotificationAttachment] = [], senderImage: Data? = nil) -> UNNotificationContent {
        let base = baseContent(for: n, messageId: messageId, attachments: attachments)
        let senderName = n.title?.nonBlank ?? n.appLabel
        return communicationStyled(base, for: n, senderName: senderName, senderImage: senderImage) ?? base
    }

    private static func baseContent(for n: CapturedNotification, messageId: String,
                                    attachments: [UNNotificationAttachment]) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = n.title?.nonBlank ?? n.appLabel
        content.body = (n.bigText?.nonBlank ?? n.text?.nonBlank) ?? n.appLabel
        if let sub = n.subText?.nonBlank ?? n.originDeviceName?.nonBlank { content.subtitle = sub }
        content.categoryIdentifier = categoryIdentifier(for: n)
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
                               messageId: String, senderImage: Data?, alerting: Bool) -> UNNotificationContent {
        let content = UNMutableNotificationContent()
        let senderName = message.sender?.nonBlank ?? n.conversationTitle?.nonBlank ?? n.title?.nonBlank ?? n.appLabel
        content.title = senderName
        content.body = message.text
        if n.isGroupConversation, let convTitle = n.conversationTitle?.nonBlank { content.subtitle = convTitle }
        content.categoryIdentifier = categoryIdentifier(for: n)
        content.threadIdentifier = threadIdentifier(for: n)
        content.userInfo = userInfo(for: n, messageId: messageId)
        applyAlerting(content, importance: alerting ? n.effectiveImportance : .MIN)
        return communicationStyled(content, for: n, senderName: senderName, senderImage: senderImage) ?? content
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
        [
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
        ]
    }

    // MARK: Communication styling

    /// Wrap as an incoming INSendMessageIntent so iOS renders a communication notification with the
    /// sender's avatar. Returns nil if the entitlement is missing (`updating(from:)` throws) → caller uses base.
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
        return try? base.updating(from: intent)
    }

    // MARK: Image normalization (#12)

    /// Bytes safe to hand to `INImage` / `UNNotificationAttachment`, which only accept PNG/JPEG/GIF.
    /// Android delivers all private graphics as WebP, which those APIs reject — decode + re-encode to PNG.
    static func normalizedImageData(_ data: Data) -> Data {
        if data.starts(with: [0x89, 0x50, 0x4E, 0x47]) || data.starts(with: [0xFF, 0xD8, 0xFF]) { return data }
        return UIImage(data: data)?.pngData() ?? data
    }

    // MARK: Helpers

    /// Short, stable, URL-safe token (base64url of SHA-256 prefix) — mirrors Android's `stableToken`.
    static func stableToken(_ value: String) -> String {
        NSBase64URL.encode(NSHash.sha256(Data(value.utf8)).prefix(12))
    }
}

/// Persisted set of per-channel notification category ids (App Group), so both the app and the NSE can
/// (re)register the full set with `setNotificationCategories` — which replaces the whole category set on
/// each call. Every registered category carries the Dismiss action, so a swipe is still reported regardless
/// of which channel a notification used. Eventually consistent across the two processes.
nonisolated enum MirrorCategoryRegistry {
    private static let key = "notisync.mirrorCategoryIds"
    private static var defaults: UserDefaults? { UserDefaults(suiteName: NotiSyncConfig.appGroup) }

    /// Ensure `id`'s category is registered (idempotent). No-op for the base category (always registered).
    static func ensureRegistered(_ id: String) {
        guard id != MirrorPresentation.baseCategoryId else { registerAll(); return }
        let channelIds = AppGroupStore.withLock(key) { () -> Set<String>? in
            var ids = Set(defaults?.stringArray(forKey: key) ?? [])
            guard !ids.contains(id) else { return nil }
            ids.insert(id)
            defaults?.set(Array(ids), forKey: key)
            return ids
        }
        if let channelIds { register(channelIds: channelIds) }
    }

    /// Re-register the base category plus all known channel categories (call on app launch).
    static func registerAll() {
        register(channelIds: Set(defaults?.stringArray(forKey: key) ?? []))
    }

    private static func register(channelIds: Set<String>) {
        var categories: [String: UNNotificationCategory] = [
            MirrorPresentation.baseCategoryId: MirrorPresentation.category(),
        ]
        for id in channelIds { categories[id] = MirrorPresentation.category(id: id) }
        UNUserNotificationCenter.current().setNotificationCategories(Set(categories.values))
    }
}

private nonisolated extension String {
    var nonBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}
