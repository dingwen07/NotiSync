import Foundation
import UserNotifications

/// Shared app/NSE presentation contract for SSH requests. Pending requests expose native Reject and Approve actions;
/// approval foregrounds the app and continues through the reusable review sheet and its normal authentication path.
/// Remembered managed-key approval gets an action-free alerting audit result, while passkeys always require foreground
/// Authentication Services.
nonisolated enum SshKeyProviderNotificationPresentation {
    static let categoryIdentifier = "notisync.ssh.request"
    static let auditCategoryIdentifier = "notisync.ssh.audit"
    static let reviewActionIdentifier = "notisync.ssh.review"
    static let approveActionIdentifier = "notisync.ssh.approve"
    static let rejectActionIdentifier = "notisync.ssh.reject"
    static let requestIdUserInfoKey = "notisyncSshRequestId"
    static let expandedDetailsUserInfoKey = "notisyncSshExpandedDetails"
    static let foregroundSheetPresentedUserInfoKey = "notisyncSshForegroundSheetPresented"
    private static let maximumContextCharacters = 160

    /// Built alongside the mirror categories because `setNotificationCategories` replaces the complete set.
    /// Keeping synchronous factories avoids Sendable captures and prevents later mirror registrations from
    /// accidentally dropping the SSH actions.
    static func category() -> UNNotificationCategory {
        let reject = UNNotificationAction(
            identifier: rejectActionIdentifier,
            title: String(
                localized: "ssh.notification.reject",
                defaultValue: "Reject",
                comment: "Destructive notification action rejecting an SSH signing or import request."
            ),
            options: [.destructive]
        )
        let approve = UNNotificationAction(
            identifier: approveActionIdentifier,
            title: String(
                localized: "ssh.notification.approve",
                defaultValue: "Approve",
                comment: "Authenticated notification action approving an SSH signing or import request."
            ),
            options: [.authenticationRequired, .foreground]
        )
        return UNNotificationCategory(
            identifier: categoryIdentifier,
            actions: [approve, reject],
            intentIdentifiers: [],
            options: []
        )
    }

    static func auditCategory() -> UNNotificationCategory {
        UNNotificationCategory(
            identifier: auditCategoryIdentifier,
            actions: [],
            intentIdentifiers: [],
            options: []
        )
    }

    static func content(
        for request: SshProviderRequestRecord,
        foregroundSheetPresented: Bool = false
    ) -> UNNotificationContent {
        let content = UNMutableNotificationContent()
        let requester = requesterName(request)
        let key = keyName(request)
        var expandedDetails: [String] = []
        switch request.kind {
        case .sign:
            let destination = destinationName(request)
            content.title = destination.map {
                String(
                    format: String(
                        localized: "ssh.notification.sign.title.destination",
                        defaultValue: "SSH Signing · %@",
                        comment: "Pending SSH signing notification title. The placeholder is the destination."
                    ),
                    $0
                )
            } ?? String(
                localized: "ssh.notification.sign.title",
                defaultValue: "SSH Signing Request",
                comment: "Notification title for a pending SSH signature approval."
            )
            content.body = String(
                format: String(
                    localized: "ssh.notification.sign.body",
                    defaultValue: "%@ wants to create an SSH signature using %@",
                    comment: "Pending SSH signature notification. First placeholder is the requesting device; second is the key name."
                ),
                requester,
                key
            )
            if let destination {
                expandedDetails.append(detail(String(
                    localized: "ssh.notification.detail.destination",
                    defaultValue: "Destination: %@",
                    comment: "Expanded SSH notification destination detail."
                ), destination))
            }
            if let process = processName(request) {
                expandedDetails.append(detail(String(
                    localized: "ssh.notification.detail.process",
                    defaultValue: "Process: %@",
                    comment: "Expanded SSH notification calling-process detail."
                ), process))
            }
            expandedDetails.append(detail(String(
                localized: "ssh.notification.detail.key",
                defaultValue: "SSH Key: %@",
                comment: "Expanded SSH notification key-name detail."
            ), key))
        case .importKey:
            content.title = String(
                format: String(
                    localized: "ssh.notification.import.title",
                    defaultValue: "SSH Key Import · %@",
                    comment: "Pending SSH key import notification title. The placeholder is the suggested key name."
                ),
                key
            )
            content.body = String(
                format: String(
                    localized: "ssh.notification.import.body",
                    defaultValue: "%@ wants to import the SSH key %@",
                    comment: "Pending SSH key import notification. First placeholder is the requesting device; second is the key name."
                ),
                requester,
                key
            )
            if let source = importSourceName(request) {
                expandedDetails.append(detail(String(
                    localized: "ssh.notification.detail.source",
                    defaultValue: "Source: %@",
                    comment: "Expanded SSH import notification source detail."
                ), source))
            }
        }
        expandedDetails.append(detail(String(
            localized: "ssh.notification.detail.request",
            defaultValue: "Request: %@",
            comment: "Expanded SSH notification abbreviated request identifier."
        ), String(request.id.prefix(8))))
        content.categoryIdentifier = categoryIdentifier
        content.threadIdentifier = "notisync.ssh"
        content.sound = foregroundSheetPresented ? nil : .default
        if foregroundSheetPresented { content.interruptionLevel = .passive }
        var userInfo: [AnyHashable: Any] = [
            requestIdUserInfoKey: request.id,
            expandedDetailsUserInfoKey: expandedDetails.joined(separator: "\n"),
        ]
        if foregroundSheetPresented { userInfo[foregroundSheetPresentedUserInfoKey] = true }
        content.userInfo = userInfo
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
                defaultValue: "%@ used %@ through a remembered authorization",
                comment: "Alert body after remembered SSH approval. First placeholder is requesting device; second is key name."
            ),
            requesterName(request),
            key.displayName
        )
        var expandedDetails: [String] = []
        if let destination = destinationName(request) {
            expandedDetails.append(detail(String(
                localized: "ssh.notification.detail.destination",
                defaultValue: "Destination: %@",
                comment: "Expanded SSH notification destination detail."
            ), destination))
        }
        if let process = processName(request) {
            expandedDetails.append(detail(String(
                localized: "ssh.notification.detail.process",
                defaultValue: "Process: %@",
                comment: "Expanded SSH notification calling-process detail."
            ), process))
        }
        expandedDetails.append(detail(String(
            localized: "ssh.notification.detail.key",
            defaultValue: "SSH Key: %@",
            comment: "Expanded SSH notification key-name detail."
        ), bounded(key.displayName)))
        expandedDetails.append(detail(String(
            localized: "ssh.notification.detail.request",
            defaultValue: "Request: %@",
            comment: "Expanded SSH notification abbreviated request identifier."
        ), String(request.id.prefix(8))))
        content.categoryIdentifier = auditCategoryIdentifier
        content.threadIdentifier = "notisync.ssh"
        content.sound = .default
        content.userInfo = [
            requestIdUserInfoKey: request.id,
            expandedDetailsUserInfoKey: expandedDetails.joined(separator: "\n"),
        ]
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
        return bounded(value?.isEmpty == false ? value! : String(
            localized: "ssh.request.unknownDevice",
            defaultValue: "Another trusted device",
            comment: "Fallback name for the device requesting an SSH operation."
        ))
    }

    private static func destinationName(_ request: SshProviderRequestRecord) -> String? {
        guard let destination = request.destination else { return nil }
        let knownHostname = destination.serverHostKeyBlobSha256.flatMap {
            SshKeyProviderStore.knownHost(hostKeyBlobSha256: $0)?.hostname
        }
        let host = knownHostname ?? destination.hostAliases.first
        let username = destination.username?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let host, !host.isEmpty {
            return bounded(username?.isEmpty == false ? "\(username!)@\(host)" : host)
        }
        return username.flatMap { $0.isEmpty ? nil : bounded($0) }
    }

    private static func keyName(_ request: SshProviderRequestRecord) -> String {
        let candidate: String? = switch request.kind {
        case .sign:
            request.providerKeyId.flatMap { SshKeyProviderStore.key(id: $0)?.displayName }
        case .importKey:
            request.importSuggestedName ?? request.importResolvedDisplayName
        }
        if let candidate = candidate?.trimmingCharacters(in: .whitespacesAndNewlines), !candidate.isEmpty {
            return bounded(candidate)
        }
        let fallback: String = switch request.kind {
        case .sign:
            String(
                localized: "ssh.notification.unknownKey",
                defaultValue: "an SSH key",
                comment: "Fallback key name in an SSH signing notification."
            )
        case .importKey:
            String(
                localized: "ssh.notification.import.defaultKeyName",
                defaultValue: "Imported SSH key",
                comment: "Fallback suggested key name in an SSH import notification."
            )
        }
        return bounded(fallback)
    }

    private static func importSourceName(_ request: SshProviderRequestRecord) -> String? {
        switch request.importSourceType {
        case SshImportSourceType.AGENT_IDENTITY.rawValue:
            String(
                localized: "ssh.notification.import.source.agent",
                defaultValue: "Remote ssh-add identity",
                comment: "Expanded SSH import notification source for a remote ssh-add identity."
            )
        case SshImportSourceType.PRIVATE_KEY_FILE.rawValue:
            String(
                localized: "ssh.notification.import.source.file",
                defaultValue: "Remote private-key file",
                comment: "Expanded SSH import notification source for a remote private-key file."
            )
        default:
            nil
        }
    }

    private static func processName(_ request: SshProviderRequestRecord) -> String? {
        guard let leaf = request.processLineage.first else { return nil }
        let process = isTrivialProcess(leaf)
            ? request.processLineage.dropFirst().first ?? leaf
            : leaf
        return bounded(shortProcessName(process))
    }

    private static func isTrivialProcess(_ process: SshProcessReviewItem) -> Bool {
        let name = executableFileName(process)
            ?? process.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.map { ["ssh", "ssh.exe"].contains($0.lowercased()) } ?? false
    }

    private static func shortProcessName(_ process: SshProcessReviewItem) -> String {
        if let displayName = process.displayName?.trimmingCharacters(in: .whitespacesAndNewlines),
           !displayName.isEmpty {
            return displayName
        }
        if let executable = executableFileName(process) { return executable }
        return "PID \(process.pid)"
    }

    private static func executableFileName(_ process: SshProcessReviewItem) -> String? {
        guard let path = process.executablePath?.trimmingCharacters(in: .whitespacesAndNewlines), !path.isEmpty else {
            return nil
        }
        let slashName = path.split(separator: "/", omittingEmptySubsequences: true).last.map(String.init) ?? path
        return slashName.split(separator: "\\", omittingEmptySubsequences: true).last.map(String.init) ?? slashName
    }

    private static func detail(_ format: String, _ value: String) -> String {
        String(format: format, value)
    }

    private static func bounded(_ value: String) -> String {
        String(value.prefix(maximumContextCharacters))
    }
}
