import AudioToolbox
import AVFoundation
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// Bidirectional pairing over QR/clipboard or reader-only NFC. Every transport surfaces the verified
/// candidate (safety number + key fingerprints) for the user to confirm before trusting.
struct PairingView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.dismiss) private var dismiss
    @State private var activeSheet: ActiveSheet?
    @State private var queuedCandidate: CandidateItem?
    @State private var nfcReaderSession: PairingNfcReaderSession?
    @State private var qrImage: UIImage?
    @State private var scanError: String?
    @State private var experienceInProgress = false
    @State private var experienceMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("My pairing code") {
                    if let image = qrImage {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .aspectRatio(1, contentMode: .fit)
                            .frame(maxWidth: 400)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .padding(.vertical, 8)
                        Text("Scan this on your other device to pair.")
                            .font(.caption).foregroundStyle(.secondary)
                    } else {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                }
                Section("Add a device") {
                    Button {
                        scanError = nil
                        activeSheet = .scanner
                    } label: {
                        Label("Scan pairing code", systemImage: "qrcode.viewfinder")
                    }
                    Button {
                        scanError = nil
                        if let text = UIPasteboard.general.string {
                            inspectPairingCode(text, source: .clipboard)
                        } else {
                            scanError = String(localized: "pairing.error.noValidClipboard", defaultValue: "No valid pairing code on the clipboard.", comment: "Shown when the clipboard does not contain a valid pairing code.")
                        }
                    } label: {
                        Label("Paste pairing code", systemImage: "doc.on.clipboard")
                    }
                    if PairingNfcReaderSession.isAvailable {
                        Button {
                            startNfcPairing()
                        } label: {
                            if nfcReaderSession != nil {
                                HStack(spacing: 8) {
                                    ProgressView()
                                    Text(
                                        String(
                                            localized: "pairing.nfc.lookingForSupportedDevice",
                                            defaultValue: "Looking for supported device",
                                            comment: "Pairing button state while the Core NFC reader sheet is active."
                                        )
                                    )
                                }
                            } else {
                                Label(
                                    String(
                                        localized: "pairing.nfc.action",
                                        defaultValue: "Pair via NFC",
                                        comment: "Button that starts one-tap pairing by reading an Android HCE card."
                                    ),
                                    systemImage: "wave.3.right"
                                )
                            }
                        }
                        .disabled(nfcReaderSession != nil || runtime.pairingPayload == nil)
                    }
                    Button {
                        startExperienceMode()
                    } label: {
                        if experienceInProgress {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("Starting Experience Mode")
                            }
                        } else {
                            Label("Experience Mode", systemImage: "sparkles")
                                .dimmedWhenDisabled()
                        }
                    }
                    .disabled(experienceInProgress || runtime.pairingPayload == nil)
                    if let experienceMessage {
                        Text(verbatim: experienceMessage).font(.footnote).foregroundStyle(.secondary)
                    }
                    if let scanError {
                        Text(verbatim: scanError).font(.footnote).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Pair Device")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .task {
                if runtime.pairingPayload == nil { await runtime.makePairingPayloadAsync() }
            }
            .task(id: runtime.pairingPayload) {
                guard let payload = runtime.pairingPayload else {
                    qrImage = nil
                    return
                }
                qrImage = nil
                if let cgImage = await Self.qrCGImage(payload) {
                    qrImage = UIImage(cgImage: cgImage)
                }
            }
            .sheet(item: $activeSheet, onDismiss: presentQueuedCandidate) { sheet in
                switch sheet {
                case .scanner:
                    NavigationStack {
                        ZStack {
                            QRScannerView { result in
                                switch result {
                                case let .success(code):
                                    inspectPairingCode(code, source: .scanner)
                                case let .failure(message):
                                    scanError = message
                                    activeSheet = nil
                                }
                            }
                            .ignoresSafeArea()
                            ScannerGuideView()
                        }
                        .navigationTitle("Scan Code")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("Cancel") { activeSheet = nil }
                            }
                        }
                    }
                case .candidate(let item):
                    PairingConfirmView(candidate: item.candidate) { confirmed, ownDevice in
                        if confirmed { runtime.acceptPairing(item.candidate.payload, ownDevice: ownDevice) }
                        activeSheet = nil
                    }
                }
            }
            .onDisappear {
                nfcReaderSession?.cancel()
                nfcReaderSession = nil
            }
        }
    }

    private func startExperienceMode() {
        Task { @MainActor in
            experienceInProgress = true
            experienceMessage = nil
            scanError = nil
            let started = await runtime.startExperienceMode()
            experienceInProgress = false
            if started {
                experienceMessage = String(localized: "pairing.experience.started", defaultValue: "Experience Mode paired. Demo notifications will arrive shortly.", comment: "Shown after Experience Mode starts from the pairing sheet.")
            } else {
                scanError = runtime.lastError ?? String(localized: "pairing.experience.failed", defaultValue: "Experience Mode could not start.", comment: "Shown when Experience Mode fails to start.")
            }
        }
    }

    struct CandidateItem: Identifiable {
        let candidate: PairingCandidate
        var id: String { candidate.clientId }
    }

    private enum ActiveSheet: Identifiable {
        case scanner
        case candidate(CandidateItem)

        var id: String {
            switch self {
            case .scanner: return "scanner"
            case .candidate(let item): return "candidate-\(item.id)"
            }
        }
    }

    private enum PairingInputSource: Equatable {
        case scanner
        case clipboard
        case nfc
    }

    private func startNfcPairing() {
        guard let ownPairingText = runtime.pairingPayload else { return }
        scanError = nil
        do {
            let session = try PairingNfcReaderSession(ownPairingText: ownPairingText) { outcome in
                nfcReaderSession = nil
                switch outcome {
                case .success(let payload):
                    inspectPairingCode(payload, source: .nfc)
                case .canceled:
                    break
                case .failure(let message):
                    scanError = message
                }
            }
            nfcReaderSession = session
            session.begin()
        } catch {
            scanError = PairingNfcText.preparationFailure
        }
    }

    private func inspectPairingCode(_ code: String, source: PairingInputSource) {
        Task { @MainActor in
            if let candidate = await runtime.inspectPairingAsync(code) {
                let item = CandidateItem(candidate: candidate)
                if source == .scanner {
                    queuedCandidate = item
                    activeSheet = nil
                } else {
                    activeSheet = .candidate(item)
                }
            } else {
                switch source {
                case .scanner:
                    scanError = String(localized: "pairing.error.codeNotVerified", defaultValue: "That code could not be verified.", comment: "Shown when a scanned QR pairing code cannot be verified.")
                    activeSheet = nil
                case .clipboard:
                    scanError = String(localized: "pairing.error.noValidClipboard", defaultValue: "No valid pairing code on the clipboard.", comment: "Shown when the clipboard does not contain a valid pairing code.")
                case .nfc:
                    scanError = PairingNfcText.verificationFailure
                }
            }
        }
    }

    private func presentQueuedCandidate() {
        guard activeSheet == nil, let item = queuedCandidate else { return }
        queuedCandidate = nil
        activeSheet = .candidate(item)
    }

    static func qrCGImage(_ string: String) async -> CGImage? {
        await Task.detached(priority: .userInitiated) {
            let context = CIContext()
            let filter = CIFilter.qrCodeGenerator()
            filter.message = Data(string.utf8)
            filter.correctionLevel = "M"
            guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
                  let cg = context.createCGImage(output, from: output.extent) else { return nil }
            return cg
        }.value
    }
}

struct PairingConfirmView: View {
    let candidate: PairingCandidate
    /// `(confirmed, ownDevice)` — ownDevice distinguishes one of *your* devices (full mesh: notifications,
    /// dismissals, trust) from someone else's contact device (profile sync only). (#9)
    let onDecision: (Bool, Bool) -> Void
    @Environment(\.dismiss) private var dismiss

    private var existingDeviceName: String {
        guard let name = candidate.existingTrust?.displayName,
              !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return candidate.displayName
        }
        return name
    }

    private var myDeviceActionTitle: LocalizedStringKey {
        guard let existingTrust = candidate.existingTrust else { return "Trust as my device" }
        return existingTrust.ownDevice ? "Update \(existingDeviceName)" : "Update as my device"
    }

    private var otherDeviceActionTitle: LocalizedStringKey {
        guard let existingTrust = candidate.existingTrust else { return "Trust as someone else's" }
        return existingTrust.ownDevice ? "Update as other's device" : "Update \(existingDeviceName)"
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Device") {
                    LabeledContent("Name") {
                        Text(verbatim: candidate.displayName)
                    }
                    LabeledContent("Platform", value: LocalizedText.platform(candidate.platform))
                    VerificationValueRow("Safety Number", value: candidate.safetyNumber)
                }
                Section("Keys") {
                    VerificationValueRow("Identity", value: candidate.identityKeyFingerprint)
                    switch candidate.keyEpochStatus {
                    case .verified:
                        VerificationValueRow("Operational", value: candidate.operationalKeyFingerprint)
                        VerificationValueRow("Encryption", value: candidate.hpkeKeyFingerprint)
                        LabeledContent("Epoch", value: "\(candidate.epoch)")
                    case .absent:
                        Text("Keys will sync after pairing.").font(.caption).foregroundStyle(.secondary)
                    case .invalid:
                        Label("Key signature did not verify — do not trust.", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }
                Section {
                    Button {
                        onDecision(true, true); dismiss()
                    } label: { Label(myDeviceActionTitle, systemImage: "checkmark.seal").dimmedWhenDisabled() }
                        .disabled(candidate.keyEpochStatus == .invalid)
                    Button {
                        onDecision(true, false); dismiss()
                    } label: { Label(otherDeviceActionTitle, systemImage: "person.crop.circle.badge.checkmark").dimmedWhenDisabled() }
                        .disabled(candidate.keyEpochStatus == .invalid)
                    Button(role: .cancel) { onDecision(false, false); dismiss() } label: { Text("Cancel") }
                } footer: {
                    Text("“My device” shares notifications, dismissals, and trust. “Someone else’s” only syncs their name.")
                }
            }
            .navigationTitle("Device Pairing")
        }
    }
}

private struct ScannerGuideView: View {
    var body: some View {
        GeometryReader { proxy in
            let scanSize = min(proxy.size.width * 0.72, proxy.size.height * 0.45, 280)
            ZStack {
                RoundedRectangle(cornerRadius: 24)
                    .stroke(.black.opacity(0.45), lineWidth: 6)
                    .frame(width: scanSize, height: scanSize)
                RoundedRectangle(cornerRadius: 24)
                    .stroke(.white, lineWidth: 3)
                    .frame(width: scanSize, height: scanSize)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .allowsHitTesting(false)
    }
}

// MARK: - Camera scanner

enum QRScanResult {
    case success(String)
    case failure(String)
}

struct QRScannerView: UIViewControllerRepresentable {
    let onResult: (QRScanResult) -> Void

    func makeUIViewController(context: Context) -> QRScannerController {
        let controller = QRScannerController()
        controller.onResult = onResult
        return controller
    }

    func updateUIViewController(_ uiViewController: QRScannerController, context: Context) {}
}

final class QRScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onResult: ((QRScanResult) -> Void)?
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "net.extrawdw.apps.NotiSync.qrscanner.session")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var configured = false
    private var reported = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.frame = view.layer.bounds
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)
        previewLayer = preview
        sessionQueue.async { [weak self] in self?.configureAndStart() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.layer.bounds
    }

    private func configureAndStart() {
        guard !configured else {
            if !session.isRunning { session.startRunning() }
            return
        }
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            report(.failure(String(localized: "scanner.error.cameraUnavailable", defaultValue: "Camera unavailable.", comment: "Shown when the QR scanner cannot access a camera.")))
            return
        }
        session.beginConfiguration()
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            report(.failure(String(localized: "scanner.error.cameraUnavailable", defaultValue: "Camera unavailable.", comment: "Shown when the QR scanner cannot access a camera.")))
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: sessionQueue)
        output.metadataObjectTypes = [.qr]
        session.commitConfiguration()
        configured = true
        if !session.isRunning { session.startRunning() }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !reported,
              let obj = objects.first as? AVMetadataMachineReadableCodeObject,
              let value = obj.stringValue else { return }
        reported = true
        session.stopRunning()
        report(.success(value))
    }

    private func report(_ result: QRScanResult) {
        DispatchQueue.main.async { [weak self] in
            if case .success = result { AudioServicesPlaySystemSound(1057) }
            self?.onResult?(result)
        }
    }
}
