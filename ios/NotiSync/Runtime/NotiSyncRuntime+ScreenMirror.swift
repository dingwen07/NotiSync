import Foundation
import Security

nonisolated enum IOSScreenMirrorRuntimeError: Error, LocalizedError {
    case unavailable
    case anotherSession
    case requestFailed
    case remote(String)
    case cancelled

    var errorDescription: String? {
        switch self {
        case .unavailable: "This device is not an available trusted Android screen source."
        case .anotherSession: "Another screen session is already active."
        case .requestFailed: "The screen request could not be delivered to the Android source."
        case .remote(let detail): detail
        case .cancelled: "Screen sharing was cancelled."
        }
    }
}

nonisolated final class IOSScreenMirrorSession: @unchecked Sendable {
    let sessionId: String
    let sourceId: String
    let sourceName: String
    let descriptor: IOSScreenSessionDescriptor
    let channels: IOSScreenChannelPair

    init(sourceName: String, descriptor: IOSScreenSessionDescriptor, channels: IOSScreenChannelPair) {
        sessionId = descriptor.sessionId
        sourceId = descriptor.sourcePeerId
        self.sourceName = sourceName
        self.descriptor = descriptor
        self.channels = channels
    }

    func cancelTransport() { channels.cancel() }
}

@MainActor
final class IOSScreenMirrorRequestContext {
    let source: ScreenMirrorSourceRecord
    let descriptor: IOSScreenSessionDescriptor
    let listener: IOSScreenLANListener
    var requestSent = false
    var connected = false
    var terminalSent = false
    var locallyCancelled = false
    var remoteDetail: String?
    var channels: IOSScreenChannelPair?

    init(source: ScreenMirrorSourceRecord, descriptor: IOSScreenSessionDescriptor, listener: IOSScreenLANListener) {
        self.source = source
        self.descriptor = descriptor
        self.listener = listener
    }
}

extension NotiSyncRuntime {
    func openScreenMirror(sourceId: String, sourceName fallbackName: String) async throws -> IOSScreenMirrorSession {
        guard activeScreenMirror == nil else { throw IOSScreenMirrorRuntimeError.anotherSession }
        guard let engine, let broker,
              let source = engine.screenMirrorSources().first(where: { $0.clientId == sourceId }) else {
            throw IOSScreenMirrorRuntimeError.unavailable
        }

        let issuedAt = NotiSyncEngine.nowMillis()
        let descriptor = IOSScreenSessionDescriptor(
            sessionId: "screen:\(UUID().uuidString.lowercased())",
            sourcePeerId: source.clientId,
            requesterPeerId: engine.selfClientId,
            issuedAt: issuedAt,
            expiresAt: issuedAt + 5 * 60 * 1_000,
            codec: "h264",
            controlEnabled: true,
            clipboardEnabled: false,
            maxDimension: 1_920,
            maxFps: 60,
            videoBitrateBps: 8_000_000
        )
        let routingToken = try secureRandomData(count: 16)
        let masterPsk = try secureRandomData(count: 32)
        updateScreenMirrorPhase("Opening local listener…")
        let listener: IOSScreenLANListener
        do {
            listener = try await IOSScreenLANListener.open(
                descriptor: descriptor,
                routingToken: routingToken,
                masterPsk: masterPsk
            )
        } catch {
            updateScreenMirrorPhase(nil)
            throw error
        }
        guard !Task.isCancelled else {
            listener.cancel()
            updateScreenMirrorPhase(nil)
            throw CancellationError()
        }
        let context = IOSScreenMirrorRequestContext(source: source, descriptor: descriptor, listener: listener)
        activeScreenMirror = context

        do {
            let request = ScreenMirrorSync(
                action: .REQUEST,
                sessionId: descriptor.sessionId,
                requesterPeerId: descriptor.requesterPeerId,
                sourcePeerId: descriptor.sourcePeerId,
                issuedAt: descriptor.issuedAt,
                expiresAt: descriptor.expiresAt,
                routingToken: routingToken,
                masterPsk: masterPsk,
                codec: .H264,
                requestControl: true,
                requestClipboard: false,
                maxDimension: descriptor.maxDimension,
                maxFps: descriptor.maxFps,
                videoBitrateBps: descriptor.videoBitrateBps,
                candidates: listener.candidates
            )
            guard let envelope = try engine.sealScreenMirrorSync(request),
                  try await broker.send(envelope, urgency: .HIGH) else {
                throw IOSScreenMirrorRuntimeError.requestFailed
            }
            context.requestSent = true
            updateScreenMirrorPhase("Waiting for \(source.displayName.isEmpty ? fallbackName : source.displayName)…")
            let channels = try await listener.acceptPair()
            guard activeScreenMirror === context, !context.locallyCancelled, context.remoteDetail == nil else {
                channels.cancel()
                if let detail = context.remoteDetail { throw IOSScreenMirrorRuntimeError.remote(detail) }
                throw IOSScreenMirrorRuntimeError.cancelled
            }
            context.channels = channels
            context.connected = true
            updateScreenMirrorPhase("Connected")
            return IOSScreenMirrorSession(
                sourceName: source.displayName.isEmpty ? fallbackName : source.displayName,
                descriptor: descriptor,
                channels: channels
            )
        } catch {
            listener.cancel()
            context.channels?.cancel()
            if activeScreenMirror === context {
                await sendScreenTerminal(context, detail: error.localizedDescription)
                activeScreenMirror = nil
                updateScreenMirrorPhase(nil)
            }
            if let detail = context.remoteDetail { throw IOSScreenMirrorRuntimeError.remote(detail) }
            throw error
        }
    }

    func endScreenMirror(sessionId: String? = nil, detail: String = "iOS viewer closed") async {
        guard let context = activeScreenMirror,
              sessionId == nil || context.descriptor.sessionId == sessionId else { return }
        context.locallyCancelled = true
        context.listener.cancel()
        context.channels?.cancel()
        await sendScreenTerminal(context, detail: detail)
        if activeScreenMirror === context { activeScreenMirror = nil }
        updateScreenMirrorPhase(nil)
    }

    /// Called only after envelope signature/decryption and the ordinary trusted-sender gate succeeded.
    func handleScreenMirrorStatus(
        _ status: ScreenMirrorSync,
        from signerId: String,
        envelopeCreatedAt: Int64
    ) async {
        guard let context = activeScreenMirror,
              signerId == context.source.clientId,
              status.action != .REQUEST,
              status.protocolVersion == 1,
              status.sessionId == context.descriptor.sessionId,
              status.sourcePeerId == context.source.clientId,
              status.requesterPeerId == context.descriptor.requesterPeerId else { return }
        let now = NotiSyncEngine.nowMillis()
        let issuedDelta = status.issuedAt.subtractingReportingOverflow(envelopeCreatedAt)
        guard !issuedDelta.overflow,
              issuedDelta.partialValue.magnitude <= UInt64(2 * 60 * 1_000),
              envelopeCreatedAt <= now + 2 * 60 * 1_000,
              envelopeCreatedAt >= now - 5 * 60 * 1_000 else { return }

        if status.action == .STATUS, status.status == .CONNECTING {
            updateScreenMirrorPhase("Android is connecting…")
            return
        }
        if status.action == .STATUS, status.status == .READY {
            updateScreenMirrorPhase("Android started sharing…")
            return
        }
        let terminal = status.action == .CANCEL || status.action == .END ||
            (status.action == .STATUS && status.status != .CONNECTING && status.status != .READY)
        guard terminal else { return }
        let detail = status.detail?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let detail, !detail.isEmpty {
            context.remoteDetail = String(detail.prefix(160))
        } else {
            context.remoteDetail = screenStatusDescription(status.status)
        }
        context.listener.cancel()
        context.channels?.cancel()
        updateScreenMirrorPhase(context.remoteDetail)
    }

    private func sendScreenTerminal(_ context: IOSScreenMirrorRequestContext, detail: String?) async {
        guard context.requestSent, !context.terminalSent, let engine, let broker else { return }
        context.terminalSent = true
        let action: ScreenMirrorAction = context.connected ? .END : .CANCEL
        let terminal = ScreenMirrorSync(
            action: action,
            sessionId: context.descriptor.sessionId,
            requesterPeerId: context.descriptor.requesterPeerId,
            sourcePeerId: context.descriptor.sourcePeerId,
            issuedAt: NotiSyncEngine.nowMillis(),
            status: action == .END ? .ENDED : nil,
            detail: detail.map { String($0.filter { !$0.isNewline }.prefix(160)) }
        )
        if let envelope = try? engine.sealScreenMirrorSync(terminal) {
            _ = try? await broker.send(envelope, urgency: .NORMAL)
        }
    }

    private func secureRandomData(count: Int) throws -> Data {
        var data = Data(repeating: 0, count: count)
        let result = data.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, count, bytes.baseAddress!)
        }
        guard result == errSecSuccess else { throw IOSScreenTransportError.listenerFailed("secure random failed") }
        return data
    }

    private func screenStatusDescription(_ status: ScreenMirrorStatus?) -> String {
        switch status {
        case .UNAUTHORIZED: "Authorize this iPhone for screen sharing on the Android device."
        case .EXPIRED: "The screen-sharing request expired."
        case .BUSY: "The Android device is already sharing its screen."
        case .SHIZUKU_UNAVAILABLE: "Screen sharing is not ready in Shizuku on the Android device."
        case .CODEC_UNAVAILABLE: "The Android device has no compatible H.264 encoder."
        case .CODEC_START_FAILED: "The Android screen encoder could not start."
        case .TRANSPORT_FAILED: "The Android device could not connect over the local network."
        case .ENDED, nil: "Screen sharing ended."
        case .CONNECTING, .READY: "Screen sharing ended."
        }
    }
}
