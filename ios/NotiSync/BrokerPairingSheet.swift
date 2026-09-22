import SwiftUI
import UIKit

struct BrokerPairingSheet: View {
    let link: BrokerPairingLink
    let beforeStart: () async -> Void
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var candidate: PairingCandidate?
    @State private var failed = false

    var body: some View {
        Group {
            if let candidate {
                PairingConfirmView(candidate: candidate) { confirmed, ownDevice in
                    if confirmed { runtime.acceptPairing(candidate.payload, ownDevice: ownDevice) }
                    dismiss()
                }
            } else {
                NavigationStack {
                    VStack(spacing: 20) {
                        Text("Secure Exchange").font(.headline)
                        Text("Keep device pairing page open on the other device.")
                        if failed {
                            Text(BrokerPairingError.authentication.localizedDescription).foregroundStyle(.red)
                        } else { ProgressView() }
                    }
                    .padding()
                    .navigationTitle("Device Pairing")
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
                }
            }
        }
        .task(id: scenePhase == .background) {
            guard scenePhase != .background, candidate == nil else { return }
            do {
                await beforeStart()
                try Task.checkCancellation()
                let own = try await runtime.brokerPairingPayload()
                let payload = try await BrokerPairingClient().join(link, ownPayload: own.payload)
                let received = try await runtime.inspectBrokerPairing(payload, hostId: link.hostId)
                try Task.checkCancellation()
                candidate = received
            } catch is CancellationError { }
            catch { if !Task.isCancelled { failed = true } }
        }
    }
}

struct LegacyPairingQRView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.dismiss) private var dismiss
    @State private var image: UIImage?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Text("This QR code contains your device card and works without a broker connection. Each device scans the other device’s code.")
                    if let image {
                        Image(uiImage: image).interpolation(.none).resizable().aspectRatio(1, contentMode: .fit)
                            .frame(maxWidth: 400).accessibilityLabel("Pairing QR code")
                    } else { ProgressView() }
                    if let link = runtime.pairingPayload, let url = URL(string: link) {
                        ShareLink(item: url) { Label("Share QR Code Link", systemImage: "square.and.arrow.up") }
                    }
                }.padding()
            }
            .navigationTitle("QR Code")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .task(id: runtime.pairingPayload) {
                guard let link = runtime.pairingPayload else { return }
                if let cgImage = await PairingView.qrCGImage(link) { image = UIImage(cgImage: cgImage) }
            }
        }
    }
}
