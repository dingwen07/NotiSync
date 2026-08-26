import Foundation
import UserNotifications

/// Shared app/NSE presentation contract for SSH requests. A pending approval is intentionally not a notification
/// action: the user opens the app to inspect the complete context in the reusable sheet. A remembered managed-key
/// approval instead gets an alerting audit result, while passkeys always require foreground Authentication Services.
nonisolated enum SshKeyProviderNotificationPresentation {
    static let categoryIdentifier = "notisync.ssh.request"
    static let reviewActionIdentifier = "notisync.ssh.review"
    static let requestIdUserInfoKey = "notisyncSshRequestId"

    /// Built alongside the mirror categories because `setNotificationCategories` replaces the complete set.
    /// Keeping one synchronous factory avoids Sendable captures and prevents later mirror registrations from
    /// accidentally dropping the SSH review action.
    static func category() -> UNNotificationCategory {
        let review = UNNotificationAction(
            identifier: reviewActionIdentifier,
            title: String(
                localized: "ssh.notification.review",
                defaultValue: "Review",
                comment: "Notification action opening an SSH signing or import request for review."
            ),
            options: [.foreground]
        )
        return UNNotificationCategory(
            identifier: categoryIdentifier,
            actions: [review],
            intentIdentifiers: [],
            options: []
        )
    }

    static func content(for request: SshProviderRequestRecord) -> UNNotificationContent {
        let content = UNMutableNotificationContent()
        switch request.kind {
        case .sign:
            content.title = String(
                localized: "ssh.notification.sign.title",
                defaultValue: "SSH signing request",
                comment: "Notification title for a pending SSH signature approval."
            )
            let destination = destinationName(request)
            content.body = destination.map {
                String(
                    format: String(
                        localized: "ssh.notification.sign.body.destination",
                        defaultValue: "%@ wants to sign in to %@.",
                        comment: "Pending SSH signature notification. First placeholder is requesting device; second is destination."
                    ),
                    requesterName(request), $0
                )
            } ?? String(
                format: String(
                    localized: "ssh.notification.sign.body",
                    defaultValue: "%@ wants to use an SSH key.",
                    comment: "Pending SSH signature notification with requesting device."
                ),
                requesterName(request)
            )
        case .importKey:
            content.title = String(
                localized: "ssh.notification.import.title",
                defaultValue: "SSH key import request",
                comment: "Notification title for a pending remote SSH key import."
            )
            content.body = String(
                format: String(
                    localized: "ssh.notification.import.body",
                    defaultValue: "%@ wants to add a key to this device.",
                    comment: "Pending SSH key import notification with requesting device."
                ),
                requesterName(request)
            )
        }
        content.categoryIdentifier = categoryIdentifier
        content.threadIdentifier = "notisync.ssh"
        content.sound = .default
        content.userInfo = [requestIdUserInfoKey: request.id]
        return content
    }

    /// Remembered managed-key approvals still produce an alert for transparency. Tapping the notification
    /// opens the same sheet in its terminal history state; there is deliberately no stale Review action.
    static func autoApprovedContent(
        for request: SshProviderRequestRecord,
        key: SshProviderKeyRecord
    ) -> UNNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = String(
            localized: "ssh.notification.autoApproved.title",
            defaultValue: "SSH signature auto approved",
            comment: "Alert title after an SSH signature is automatically approved by remembered authorization."
        )
        content.body = String(
            format: String(
                localized: "ssh.notification.autoApproved.body",
                defaultValue: "%@ used %@ through a remembered authorization.",
                comment: "Alert body after remembered SSH approval. First placeholder is requesting device; second is key name."
            ),
            requesterName(request),
            key.displayName
        )
        content.threadIdentifier = "notisync.ssh"
        content.sound = .default
        content.userInfo = [requestIdUserInfoKey: request.id]
        return content
    }

    /// An alert push has already been committed by APNs, so an out-of-order cancellation cannot be made
    /// completely invisible. Replace the obsolete approval prompt with a quiet, action-free status instead.
    static func cancelledContent() -> UNNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = String(
            localized: "ssh.notification.cancelled.title",
            defaultValue: "SSH request cancelled",
            comment: "Quiet notification replacing an SSH approval request that was cancelled before it arrived."
        )
        content.threadIdentifier = "notisync.ssh"
        content.interruptionLevel = .passive
        content.sound = nil
        return content
    }

    static func unavailableContent() -> UNNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = String(
            localized: "ssh.notification.unavailable.title",
            defaultValue: "SSH request unavailable",
            comment: "Quiet notification replacing an SSH approval request that can no longer be completed."
        )
        content.threadIdentifier = "notisync.ssh"
        content.interruptionLevel = .passive
        content.sound = nil
        return content
    }

    private static func requesterName(_ request: SshProviderRequestRecord) -> String {
        let value = request.requesterDisplayName?.trimmingCharacters(in: .whitespacesAndNewlines)
        return value?.isEmpty == false ? value! : String(
            localized: "ssh.request.unknownDevice",
            defaultValue: "Another trusted device",
            comment: "Fallback name for the device requesting an SSH operation."
        )
    }

    private static func destinationName(_ request: SshProviderRequestRecord) -> String? {
        if let digest = request.destination?.serverHostKeyBlobSha256,
           let hostname = SshKeyProviderStore.knownHost(hostKeyBlobSha256: digest)?.hostname {
            return hostname
        }
        return request.destination?.hostAliases.first ?? request.destination?.username
    }
}
