import SwiftUI

/// Keep the shared item's localized title identical at every entry point for the same link type.
struct PairingShareLink: View {
    let url: URL?
    var brokerAssisted = false

    private var actionTitle: LocalizedStringKey {
        brokerAssisted ? "Share Secure Exchange Link" : "Share Pairing Link"
    }

    private var itemTitle: Text {
        brokerAssisted ? Text("NotiSync Secure Exchange Link") : Text("NotiSync Pairing Link")
    }

    var body: some View {
        if let url {
            ShareLink(item: url, subject: itemTitle, preview: SharePreview(itemTitle)) {
                Label(actionTitle, systemImage: "square.and.arrow.up")
            }
        } else {
            Button {} label: { Label(actionTitle, systemImage: "square.and.arrow.up") }
                .disabled(true)
        }
    }
}
