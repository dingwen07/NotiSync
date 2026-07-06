import Foundation
import SwiftUI
import UIKit

/// SF Symbols ship as SVG renditions that CoreUI parses on the symbol's FIRST render in the process
/// (`CGSVGDocumentCreateFromData` — on the main thread, and heavily amplified under a debugger). Glyphs
/// that first appear inside a gesture — swipe actions, menus — pay that parse mid-interaction, which
/// profiled as the "first swipe on a Devices row stutters" report. Rasterizing them once at launch on a
/// background thread warms the process-wide rendition cache so the gesture only hits cached artwork.
/// (UIImage and UIGraphicsImageRenderer are thread-safe.)
nonisolated enum GestureSymbolPrewarm {
    /// Symbols whose first on-screen appearance is gesture-gated: swipe-action buttons (Revoke /
    /// Clear Filter), menu items (Delete All, Silence…, Mark as Read), and the Inbox filter chip
    /// (appears on icon tap). Symbols visible at page render don't need this — their parse happens
    /// during navigation, masked by the transition.
    private static let gestureGatedSymbols = ["hand.raised.slash", "bell", "bell.slash", "trash",
                                              "checkmark.circle", "xmark.circle.fill", "tray",
                                              "envelope.badge"]

    static func run() {
        Task.detached(priority: .utility) {
            for name in gestureGatedSymbols {
                guard let image = UIImage(systemName: name) else { continue }
                _ = UIGraphicsImageRenderer(size: image.size).image { _ in
                    image.draw(at: .zero)
                }
            }
        }
    }
}

/// Icon + text for inline flow: caption metadata, status chips, compact bordered buttons.
/// Not for Form/List rows — SF Symbol bounding boxes vary in width (~16–33pt at body size),
/// so stacked rows drift out of column alignment; the system `Label` reserves a uniform icon
/// column in list contexts and is the right component there.
struct InlineIconLabel: View {
    private let title: Text
    private let systemImage: String

    init(_ titleKey: LocalizedStringKey, systemImage: String) {
        self.title = Text(titleKey)
        self.systemImage = systemImage
    }

    init(verbatim title: String, systemImage: String) {
        self.title = Text(verbatim: title)
        self.systemImage = systemImage
    }

    var body: some View {
        HStack(alignment: .center, spacing: 4) {
            Image(systemName: systemImage)
                .imageScale(.medium)
            title
        }
    }
}

struct VerificationValueRow: View {
    let title: LocalizedStringKey
    let value: String

    init(_ title: LocalizedStringKey, value: String) {
        self.title = title
        self.value = value
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            VerificationValueText(value)
        }
        .padding(.vertical, 2)
    }
}

struct VerificationValueText: View {
    let value: String

    init(_ value: String) {
        self.value = value
    }

    var body: some View {
        Text(verbatim: Self.grouped(value))
            .font(.caption.monospaced())
            .foregroundStyle(.secondary)
            .textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
    }

    private static func grouped(_ value: String) -> String {
        if value.contains(":") {
            return value
        }

        guard value.count > 24, value.unicodeScalars.allSatisfy({ hexDigits.contains($0) }) else { return value }
        var groups: [Substring] = []
        var start = value.startIndex
        while start < value.endIndex {
            let end = value.index(start, offsetBy: 4, limitedBy: value.endIndex) ?? value.endIndex
            groups.append(value[start..<end])
            start = end
        }
        return groups.joined(separator: " ")
    }

    private static let hexDigits = CharacterSet(charactersIn: "0123456789abcdefABCDEF")
}
