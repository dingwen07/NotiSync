import Foundation
import SwiftUI

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
