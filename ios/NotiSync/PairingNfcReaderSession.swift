import CoreNFC
import Foundation

/// One foreground, reader-only Core NFC session. Android is the HCE card; the iPhone reads it and sends its
/// own signed pairing payload back through the same reciprocal APDU exchange.
@MainActor
final class PairingNfcReaderSession: NSObject, NFCTagReaderSessionDelegate {
    enum Outcome {
        case success(String)
        case canceled
        case failure(String)
    }

    static var isAvailable: Bool { NFCTagReaderSession.readingAvailable }

    private let ownPayload: Data
    private let onCompletion: @MainActor (Outcome) -> Void
    private var readerSession: NFCTagReaderSession?
    private var exchangeInProgress = false
    private var cancellationRequested = false
    private var delivered = false
    private var successfulPayload: String?
    private var terminalFailure: String?

    init(ownPairingText: String, onCompletion: @escaping @MainActor (Outcome) -> Void) throws {
        ownPayload = try PairingNfcWireProtocol.decodePairingPayload(
            PairingLinks.payload(from: ownPairingText)
        )
        self.onCompletion = onCompletion
        super.init()
    }

    func begin() {
        guard readerSession == nil else { return }
        guard Self.isAvailable, let session = makeReaderSession() else {
            deliver(.failure(PairingNfcText.unavailable))
            return
        }
        readerSession = session
        session.alertMessage = PairingNfcText.scanPrompt
        session.begin()
    }

    func cancel() {
        guard !delivered else { return }
        cancellationRequested = true
        readerSession?.invalidate()
        deliver(.canceled)
    }

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard !exchangeInProgress else { return }
        guard tags.count == 1 else {
            session.alertMessage = PairingNfcText.multipleDevices
            session.restartPolling()
            return
        }
        guard case .iso7816(let card) = tags[0] else {
            session.alertMessage = PairingNfcText.unsupportedCard
            session.restartPolling()
            return
        }

        exchangeInProgress = true
        let detectedTag = tags[0]
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await session.connect(to: detectedTag)
                let payload = try await exchange(with: card)
                successfulPayload = payload
                session.alertMessage = PairingNfcText.success
                session.invalidate()
                // Core NFC normally calls didInvalidate immediately. Keep a fallback so a future framework
                // behavior change cannot leave the pairing view stuck retaining a completed session.
                Task { @MainActor [weak self] in
                    try? await Task.sleep(for: .milliseconds(500))
                    self?.deliverSuccessfulPayloadIfPresent()
                }
            } catch {
                handleExchangeFailure(error, session: session)
            }
        }
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        readerSession = nil
        exchangeInProgress = false
        if successfulPayload != nil {
            deliverSuccessfulPayloadIfPresent()
            return
        }
        if cancellationRequested || (error as NSError).code == 200 {
            deliver(.canceled)
            return
        }
        deliver(.failure(terminalFailure ?? PairingNfcText.message(forInvalidationError: error)))
    }

    private func makeReaderSession() -> NFCTagReaderSession? {
        if #available(iOS 26.4, *) {
            return NFCTagReaderSession(
                configuration: .init(
                    pollingOption: .iso14443,
                    iso7816SelectIdentifiers: [PairingNfcWireProtocol.applicationAidHex]
                ),
                delegate: self,
                queue: .main
            )
        }
        return NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: .main)
    }

    private func exchange(with card: any NFCISO7816Tag) async throws -> String {
        let discovery = try await send(PairingNfcWireProtocol.selectApplicationCommand, to: card)
        let version = try PairingNfcWireProtocol.pairingVersion(from: discovery)
        let negotiation = try PairingNfcWireProtocol.negotiatePairingCommand(version: version)
        let selection = try PairingNfcWireProtocol.parseOperationSelection(
            try await send(negotiation, to: card),
            version: version
        )

        var remotePayload = Data(count: selection.payloadSize)
        remotePayload.replaceSubrange(
            0..<selection.initialPayload.count,
            with: selection.initialPayload
        )
        let chunkSize = min(
            selection.maximumChunkSize,
            PairingNfcWireProtocol.maximumExchangeChunkBytes
        )
        var readerOffset = 0
        var peerOffset = selection.initialPayload.count

        while readerOffset < ownPayload.count || peerOffset < remotePayload.count {
            let readerLength = min(chunkSize, ownPayload.count - readerOffset)
            let peerLength = min(chunkSize, remotePayload.count - peerOffset)
            let command = try PairingNfcWireProtocol.exchangePayloadCommand(
                readerPayload: ownPayload,
                readerOffset: readerOffset,
                readerLength: readerLength,
                requestedPeerOffset: peerOffset,
                requestedPeerLength: peerLength
            )
            let peerChunk = try PairingNfcWireProtocol.requireSuccessfulResponse(
                try await send(command, to: card)
            )
            guard peerChunk.count == peerLength else {
                throw PairingNfcWireProtocol.ProtocolError.incompletePayload
            }
            remotePayload.replaceSubrange(peerOffset..<(peerOffset + peerLength), with: peerChunk)
            readerOffset += readerLength
            peerOffset += peerLength
        }

        let commitData = try PairingNfcWireProtocol.requireSuccessfulResponse(
            try await send(PairingNfcWireProtocol.commitExchangeCommand, to: card)
        )
        guard commitData.isEmpty else {
            throw PairingNfcWireProtocol.ProtocolError.invalidResponse
        }
        return PairingNfcWireProtocol.encodePairingPayload(remotePayload)
    }

    private func send(
        _ command: PairingNfcWireProtocol.Command,
        to card: any NFCISO7816Tag
    ) async throws -> PairingNfcWireProtocol.Response {
        guard let apdu = NFCISO7816APDU(data: command.encoded) else {
            throw PairingNfcWireProtocol.ProtocolError.invalidResponse
        }
        let (data, statusWord1, statusWord2) = try await card.sendCommand(apdu: apdu)
        return PairingNfcWireProtocol.Response(
            data: data,
            statusWord1: statusWord1,
            statusWord2: statusWord2
        )
    }

    private func handleExchangeFailure(_ error: Error, session: NFCTagReaderSession) {
        exchangeInProgress = false
        if PairingNfcText.isRetryableConnectionError(error) {
            session.alertMessage = PairingNfcText.connectionLost
            session.restartPolling()
            return
        }
        let message = PairingNfcText.message(forExchangeError: error)
        terminalFailure = message
        session.invalidate(errorMessage: message)
    }

    private func deliverSuccessfulPayloadIfPresent() {
        guard let successfulPayload else { return }
        deliver(.success(successfulPayload))
    }

    private func deliver(_ outcome: Outcome) {
        guard !delivered else { return }
        delivered = true
        onCompletion(outcome)
    }
}

@MainActor
private enum PairingNfcText {
    static let scanPrompt = String(
        localized: "pairing.nfc.scan.prompt",
        defaultValue: "Hold the top of this iPhone near the supported device. Make sure the device is unlocked.",
        comment: "Core NFC scan-sheet instruction for pairing with a supported device that emulates a card."
    )
    static let multipleDevices = String(
        localized: "pairing.nfc.scan.multipleDevices",
        defaultValue: "More than one device detected. Move other NFC devices away and try again.",
        comment: "Core NFC scan-sheet instruction when multiple NFC devices are detected."
    )
    static let unsupportedCard = String(
        localized: "pairing.nfc.scan.unsupportedCard",
        defaultValue: "That is not a compatible NotiSync device. Try another supported device.",
        comment: "Core NFC scan-sheet instruction after detecting a non-NotiSync card."
    )
    static let connectionLost = String(
        localized: "pairing.nfc.scan.connectionLost",
        defaultValue: "Connection lost. Hold the devices together and try again.",
        comment: "Core NFC scan-sheet instruction after losing the Android HCE connection."
    )
    static let success = String(
        localized: "pairing.nfc.scan.success",
        defaultValue: "Pairing information exchanged.",
        comment: "Core NFC scan-sheet success message after reciprocal pairing data exchange."
    )
    static let unavailable = String(
        localized: "pairing.nfc.error.unavailable",
        defaultValue: "NFC pairing is unavailable on this device.",
        comment: "Error when Core NFC tag reading is unavailable."
    )

    static func isRetryableConnectionError(_ error: Error) -> Bool {
        guard !(error is PairingNfcWireProtocol.ProtocolError) else { return false }
        // Core NFC transceive failures occupy 100...105. A fresh SELECT on the next polling pass resets the
        // Android exchange, so a lost/retried/not-connected tag can safely be tapped again in this session.
        return (100...105).contains((error as NSError).code)
    }

    static func message(forExchangeError error: Error) -> String {
        guard let protocolError = error as? PairingNfcWireProtocol.ProtocolError else {
            return communicationFailure
        }
        switch protocolError {
        case .peerNotReady:
            return String(
                localized: "pairing.nfc.error.deviceNotReady",
                defaultValue: "The device is not ready for NFC pairing. Open NotiSync on it, make sure it is unlocked, and try again.",
                comment: "Error when the device has no cached local pairing payload."
            )
        case .pairingUnsupported, .incompatiblePairingVersion:
            return String(
                localized: "pairing.nfc.error.incompatible",
                defaultValue: "This device does not support NotiSync one-tap pairing.",
                comment: "Error when the Android HCE protocol does not support a compatible pairing operation."
            )
        case .invalidLocalPayload:
            return String(
                localized: "pairing.nfc.error.preparation",
                defaultValue: "Could not prepare NFC pairing.",
                comment: "Error when this iPhone's local pairing payload cannot be prepared for NFC."
            )
        case .invalidResponse, .peerRejected, .payloadTooLarge, .incompletePayload:
            return String(
                localized: "pairing.nfc.error.invalidExchange",
                defaultValue: "The NFC pairing information was invalid.",
                comment: "Error when the Android HCE protocol response is malformed or rejects the exchange."
            )
        }
    }

    static func message(forInvalidationError error: Error) -> String {
        switch (error as NSError).code {
        case 1, 2, 6, 7, 8:
            return unavailable
        case 201:
            return String(
                localized: "pairing.nfc.error.timeout",
                defaultValue: "NFC pairing timed out. Try again.",
                comment: "Error shown after the Core NFC reader session times out."
            )
        default:
            return communicationFailure
        }
    }

    private static let communicationFailure = String(
        localized: "pairing.nfc.error.communication",
        defaultValue: "Could not exchange pairing information over NFC. Try again.",
        comment: "Generic NFC pairing communication error."
    )
}
