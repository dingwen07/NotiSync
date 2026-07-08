import SwiftData
import SwiftUI
import UIKit

/// Origin metadata shared by the Inbox row and the long-press preview card.
extension InboxNotification {
    /// Where the notification came from, best-name first: the paired device's display name, then the
    /// origin name carried in the envelope, then the raw client id. iPhone-origin (ANCS) rows fall
    /// back to the "iPhone" brand name rather than a client id.
    func originDisplayLabel(sourceDevice: TrustedDevice?) -> String? {
        if isIPhoneOrigin {
            return nonBlank(originDeviceName) ?? "iPhone"
        }
        return nonBlank(sourceDevice?.displayName)
            ?? nonBlank(originDeviceName)
            ?? nonBlank(sourceClientId)
    }

    /// using "iphone" icon specifically for ANDROID_LOCAL origin notification
    var originSystemImage: String {
        isIPhoneOrigin ? "apple.logo" : "iphone"
    }
}

private func nonBlank(_ value: String?) -> String? {
    guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
        return nil
    }
    return trimmed
}

/// Mirrored payloads are plain strings, so link detection happens client-side: URLs, bare domains,
/// and mail addresses via the data detector's link type, plus phone numbers mapped to `tel:`.
enum NotificationTextLinks {
    /// Hyperlink rendering for the preview card and the full-text sheet: link runs get the tint
    /// color and an underline, and `Text` makes them tappable (routed through `openURL`). That is
    /// real only in the sheet — a context-menu preview is a non-interactive snapshot, so there the
    /// same runs are display-only and opening links means going through "Show Full Text".
    static func attributed(_ text: String) -> AttributedString {
        var attributed = AttributedString(text)
        guard let detector else { return attributed }
        let matches = detector.matches(in: text, options: [], range: NSRange(text.startIndex..., in: text))
        for match in matches {
            guard let range = Range(match.range, in: attributed), let url = url(for: match) else { continue }
            attributed[range].link = url
            attributed[range].underlineStyle = .single
        }
        return attributed
    }

    private static let detector = try? NSDataDetector(
        types: NSTextCheckingResult.CheckingType([.link, .phoneNumber]).rawValue)

    private static func url(for match: NSTextCheckingResult) -> URL? {
        if let url = match.url { return url }
        if let phone = match.phoneNumber {
            return URL(string: "tel:\(phone.filter { $0.isNumber || $0 == "+" })")
        }
        return nil
    }
}

/// The context-menu preview for an Inbox row: the row's data re-rendered for reading instead of
/// scanning — title/subtitle/body at reading line counts (the row clamps them to 1/2 lines),
/// detected links styled, full date, and origin metadata. The system owns the presentation: lift
/// animation, rounded platter, shadow, and dismissal all come from `.contextMenu`, so this view is
/// content only.
struct NotificationPreviewCard: View {
    /// Fixed — not a max. The system measures the preview with an unspecified proposal, under
    /// which `maxWidth` never constrains `Text`: each line lays out at its single-line ideal width
    /// and clips. A fixed width both forces wrapping and keeps every card the same size.
    static let width: CGFloat = 360

    let notification: InboxNotification
    let sourceDevice: TrustedDevice?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            NotificationHeaderRow(notification: notification)
                .padding(16)
            Divider()
            NotificationContentColumn(notification: notification, clamped: true)
                .padding(16)
            Divider()
            NotificationMetadataColumn(notification: notification, sourceDevice: sourceDevice)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
        }
        .frame(width: Self.width, alignment: .leading)
        .background(Color(.systemBackground))
    }
}

/// Everything the context-menu preview can't do because it is a snapshot: text selection, links
/// tappable in place, and unbounded content behind a real ScrollView. Reached from the menu's
/// "Show Full Text"; presented as a plain system sheet.
struct NotificationFullTextView: View {
    @Environment(\.dismiss) private var dismiss
    let notification: InboxNotification
    let sourceDevice: TrustedDevice?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    NotificationHeaderRow(notification: notification)
                        .padding(.vertical, 16)
                    Divider()
                    NotificationContentColumn(notification: notification, clamped: false)
                        .padding(.vertical, 16)
                        .textSelection(.enabled)
                    Divider()
                    NotificationMetadataColumn(notification: notification, sourceDevice: sourceDevice)
                        .padding(.vertical, 12)
                }
                .padding(.horizontal, 20)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(Text(verbatim: notification.appLabel))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// App icon, label, and the full post date (Inbox rows only show a clock time, which is ambiguous
/// for anything older than today).
struct NotificationHeaderRow: View {
    let notification: InboxNotification

    var body: some View {
        HStack(spacing: 12) {
            InboxIconView(notification: notification)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: notification.appLabel)
                    .font(.subheadline.weight(.semibold))
                Text(notification.postTime, format: Date.FormatStyle(date: .abbreviated, time: .shortened))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
    }
}

struct NotificationContentColumn: View {
    /// Preview caps. Enough body to actually read, while keeping the whole card short enough that
    /// the menu lands adjacent to the pressing finger — the native press→slide→release selection
    /// only engages once the touch reaches the menu, so an over-tall card turns that gesture into
    /// platter-dragging instead. Content past the caps lives in the "Show Full Text" sheet.
    private static let titleLineLimit = 4
    private static let subtitleLineLimit = 3
    private static let bodyLineLimit = 10

    let notification: InboxNotification
    /// True in the context-menu preview; false in the full-text sheet (unbounded, scrollable).
    let clamped: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title = nonBlank(notification.title) {
                Text(NotificationTextLinks.attributed(title))
                    .font(.headline)
                    .lineLimit(clamped ? Self.titleLineLimit : nil)
            }
            if let subtitle = nonBlank(notification.subtitle) {
                Text(NotificationTextLinks.attributed(subtitle))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(clamped ? Self.subtitleLineLimit : nil)
            }
            if let body = nonBlank(notification.body) {
                Text(NotificationTextLinks.attributed(body))
                    .font(.body)
                    .lineLimit(clamped ? Self.bodyLineLimit : nil)
            }
            if !notification.hasTextContent {
                Text("This notification has no text.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct NotificationMetadataColumn: View {
    let notification: InboxNotification
    let sourceDevice: TrustedDevice?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let origin = notification.originDisplayLabel(sourceDevice: sourceDevice) {
                InlineIconLabel(verbatim: origin, systemImage: notification.originSystemImage)
            }
            if let channelName = nonBlank(notification.channelName) {
                InlineIconLabel(verbatim: channelName, systemImage: "bell.badge")
            }
            InlineIconLabel(verbatim: LocalizedText.deliveryMode(notification.deliveryMode),
                            systemImage: "arrow.down.circle")
            if notification.isDismissed {
                InlineIconLabel("Dismissed", systemImage: "checkmark.circle")
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}

extension InboxNotification {
    var hasTextContent: Bool {
        copyableText != nil
    }

    /// Title/subtitle/body as one pasteboard string, nil when the notification carries no text.
    var copyableText: String? {
        let parts = [title, subtitle, body].compactMap(nonBlank)
        return parts.isEmpty ? nil : parts.joined(separator: "\n")
    }
}
