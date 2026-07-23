import CryptoKit
import Dispatch
import Foundation

nonisolated enum IOSScreenChannel: UInt8, Sendable {
    case video = 0
    case control = 1

    var wireName: String { self == .video ? "video" : "control" }
}

nonisolated struct IOSScreenSessionDescriptor: Equatable, Sendable {
    var sessionId: String
    var sourcePeerId: String
    var requesterPeerId: String
    var issuedAt: Int64
    var expiresAt: Int64
    var codec: String
    var controlEnabled: Bool
    var clipboardEnabled: Bool
    var maxDimension: Int
    var maxFps: Int
    var videoBitrateBps: Int
}

nonisolated enum IOSScreenTransportError: Error, LocalizedError {
    case noLAN
    case listenerFailed(String)
    case connectionFailed(String)
    case timedOut
    case channelClosed
    case malformedBinding
    case duplicateChannel
    case unsupportedStream(String)

    var errorDescription: String? {
        switch self {
        case .noLAN: "Connect this iPhone and the source device to the same Wi-Fi network."
        case .listenerFailed(let detail): "Could not open the local screen listener: \(detail)"
        case .connectionFailed(let detail): "The secure screen connection failed: \(detail)"
        case .timedOut: "The source device did not connect before the request expired."
        case .channelClosed: "The source device closed the screen connection."
        case .malformedBinding: "The source device sent an invalid screen-channel binding."
        case .duplicateChannel: "The source device connected the same screen channel twice."
        case .unsupportedStream(let detail): detail
        }
    }
}

/// One screen byte stream. LAN and Relay-control channels use OpenSSL TLS; Relay video is decrypted
/// record-by-record and exposed here as the ordinary scrcpy byte stream expected by the decoder.
nonisolated final class IOSScreenWireConnection: @unchecked Sendable {
    private final class TLSStorage: @unchecked Sendable {
        let connection: OpaquePointer
        let queue: DispatchQueue

        init(_ connection: OpaquePointer, label: String) {
            self.connection = connection
            queue = DispatchQueue(label: label, qos: .userInitiated)
        }

        func close() { NSScreenTLSConnectionClose(connection) }
    }

    private enum Backend {
        case tls(TLSStorage)
        case relayVideo(IOSRelayVideoStream)
    }

    private let backend: Backend

    init(_ connection: OpaquePointer, label: String) {
        backend = .tls(TLSStorage(connection, label: label))
    }

    init(relayVideo: IOSRelayVideoStream) {
        backend = .relayVideo(relayVideo)
    }

    deinit {
        switch backend {
        case .tls(let storage): NSScreenTLSConnectionDestroy(storage.connection)
        case .relayVideo(let stream): stream.cancel()
        }
    }

    func readExactly(_ count: Int, deadline: DispatchTime? = nil) async throws -> Data {
        precondition(count >= 0)
        guard count > 0 else { return Data() }
        if case .relayVideo(let stream) = backend {
            return try await stream.readExactly(count, deadline: deadline)
        }
        guard case .tls(let storage) = backend else {
            throw IOSScreenTransportError.channelClosed
        }
        let timeout = try timeoutMilliseconds(until: deadline)
        do {
            let data = try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { continuation in
                    storage.queue.async {
                        var data = Data(count: count)
                        var errorBuffer = [CChar](repeating: 0, count: 512)
                        let succeeded = data.withUnsafeMutableBytes { bytes in
                            NSScreenTLSConnectionReadExactly(
                                storage.connection,
                                bytes.bindMemory(to: UInt8.self).baseAddress!,
                                bytes.count,
                                timeout,
                                &errorBuffer,
                                errorBuffer.count
                            )
                        }
                        if succeeded == 1 {
                            continuation.resume(returning: data)
                        } else {
                            let detail = String(cString: errorBuffer)
                            if detail.localizedCaseInsensitiveContains("closed") {
                                continuation.resume(throwing: IOSScreenTransportError.channelClosed)
                            } else if deadline != nil {
                                continuation.resume(throwing: IOSScreenTransportError.timedOut)
                            } else {
                                continuation.resume(throwing: IOSScreenTransportError.connectionFailed(detail))
                            }
                        }
                    }
                }
            } onCancel: {
                storage.close()
            }
            try Task.checkCancellation()
            return data
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw error
        }
    }

    func send(_ data: Data) async throws {
        guard !data.isEmpty else { return }
        guard case .tls(let storage) = backend else {
            throw IOSScreenTransportError.unsupportedStream("Relay video is receive-only on the viewer.")
        }
        do {
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    storage.queue.async {
                        var errorBuffer = [CChar](repeating: 0, count: 512)
                        let succeeded = data.withUnsafeBytes { bytes in
                            NSScreenTLSConnectionWriteAll(
                                storage.connection,
                                bytes.bindMemory(to: UInt8.self).baseAddress!,
                                bytes.count,
                                10_000,
                                &errorBuffer,
                                errorBuffer.count
                            )
                        }
                        if succeeded == 1 {
                            continuation.resume()
                        } else {
                            let detail = String(cString: errorBuffer)
                            if detail.localizedCaseInsensitiveContains("closed") {
                                continuation.resume(throwing: IOSScreenTransportError.channelClosed)
                            } else {
                                continuation.resume(throwing: IOSScreenTransportError.connectionFailed(detail))
                            }
                        }
                    }
                }
            } onCancel: {
                storage.close()
            }
            try Task.checkCancellation()
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw error
        }
    }

    func cancel() {
        switch backend {
        case .tls(let storage): storage.close()
        case .relayVideo(let stream): stream.cancel()
        }
    }

    /// Ask a Relay source for a fresh key frame after the local renderer loses decoder state.
    /// Direct transports have no framed feedback channel and recover on their next key frame.
    func requestVideoRecovery() async throws {
        guard case .relayVideo(let stream) = backend else { return }
        try await stream.requestRecovery()
    }

    private func timeoutMilliseconds(until deadline: DispatchTime?) throws -> Int32 {
        guard let deadline else { return -1 }
        let now = DispatchTime.now().uptimeNanoseconds
        guard deadline.uptimeNanoseconds > now else { throw IOSScreenTransportError.timedOut }
        let remaining = (deadline.uptimeNanoseconds - now + 999_999) / 1_000_000
        return Int32(min(UInt64(Int32.max), max(1, remaining)))
    }
}

nonisolated protocol IOSScreenSessionListener: AnyObject, Sendable {
    var candidates: [ScreenMirrorConnectionCandidate] { get }
    func acceptPair() async throws -> IOSScreenChannelPair
    func cancel()
}

nonisolated struct IOSScreenChannelPair: Sendable {
    var video: IOSScreenWireConnection
    var control: IOSScreenWireConnection

    func cancel() {
        video.cancel()
        control.cancel()
    }
}

/// Viewer-side TLS 1.3 external-PSK listener for screen protocol v1.
nonisolated final class IOSScreenLANListener: IOSScreenSessionListener, @unchecked Sendable {
    let candidates: [ScreenMirrorConnectionCandidate]

    private let listener: OpaquePointer
    private let descriptor: IOSScreenSessionDescriptor
    private let queue = DispatchQueue(label: "net.extrawdw.apps.NotiSync.screen-listener", qos: .userInitiated)

    private init(
        listener: OpaquePointer,
        descriptor: IOSScreenSessionDescriptor,
        candidates: [ScreenMirrorConnectionCandidate]
    ) {
        self.listener = listener
        self.descriptor = descriptor
        self.candidates = candidates
    }

    deinit {
        NSScreenTLSListenerDestroy(listener)
    }

    static func open(
        descriptor: IOSScreenSessionDescriptor,
        routingToken: Data,
        masterPsk: Data
    ) async throws -> IOSScreenLANListener {
        guard routingToken.count == 16, masterPsk.count == 32 else {
            throw IOSScreenTransportError.listenerFailed("invalid session secrets")
        }
        let videoIdentity = Data("nss1.\(routingToken.base64URLEncoded()).video".utf8)
        let controlIdentity = Data("nss1.\(routingToken.base64URLEncoded()).control".utf8)
        var videoKey = IOSScreenSessionKeyDeriver.derive(
            masterPsk: masterPsk,
            routingToken: routingToken,
            descriptor: descriptor,
            channel: .video
        )
        var controlKey = IOSScreenSessionKeyDeriver.derive(
            masterPsk: masterPsk,
            routingToken: routingToken,
            descriptor: descriptor,
            channel: .control
        )
        defer {
            videoKey.resetBytes(in: videoKey.startIndex..<videoKey.endIndex)
            controlKey.resetBytes(in: controlKey.startIndex..<controlKey.endIndex)
        }
        var port: UInt16 = 0
        var errorBuffer = [CChar](repeating: 0, count: 512)
        let listener = videoIdentity.withUnsafeBytes { videoIdentityBytes in
            videoKey.withUnsafeBytes { videoKeyBytes in
                controlIdentity.withUnsafeBytes { controlIdentityBytes in
                    controlKey.withUnsafeBytes { controlKeyBytes in
                        NSScreenTLSListenerCreate(
                            videoIdentityBytes.bindMemory(to: UInt8.self).baseAddress!,
                            videoIdentityBytes.count,
                            videoKeyBytes.bindMemory(to: UInt8.self).baseAddress!,
                            videoKeyBytes.count,
                            controlIdentityBytes.bindMemory(to: UInt8.self).baseAddress!,
                            controlIdentityBytes.count,
                            controlKeyBytes.bindMemory(to: UInt8.self).baseAddress!,
                            controlKeyBytes.count,
                            &port,
                            &errorBuffer,
                            errorBuffer.count
                        )
                    }
                }
            }
        }
        guard let listener else {
            throw IOSScreenTransportError.listenerFailed(String(cString: errorBuffer))
        }
        let candidates = localLANCandidates(port: port)
        guard !candidates.isEmpty else {
            NSScreenTLSListenerDestroy(listener)
            throw IOSScreenTransportError.noLAN
        }
        return IOSScreenLANListener(
            listener: listener,
            descriptor: descriptor,
            candidates: Array(candidates.prefix(8))
        )
    }

    func acceptPair() async throws -> IOSScreenChannelPair {
        var accepted: [IOSScreenChannel: IOSScreenWireConnection] = [:]
        var attempts = 0
        var lastFailure: String?
        do {
            while accepted.count < 2 {
                let remaining = descriptor.expiresAt - NotiSyncEngine.nowMillis()
                guard remaining > 0 else { throw IOSScreenTransportError.timedOut }
                attempts += 1
                guard attempts <= 8 else {
                    throw IOSScreenTransportError.listenerFailed(
                        lastFailure ?? "too many unauthenticated connections"
                    )
                }
                do {
                    guard let wire = try await acceptConnection(timeoutMilliseconds: remaining) else {
                        throw IOSScreenTransportError.timedOut
                    }
                    let channel = try await authenticateScreenBinding(on: wire, descriptor: descriptor)
                    guard accepted[channel] == nil else { throw IOSScreenTransportError.duplicateChannel }
                    accepted[channel] = wire
                } catch {
                    if case IOSScreenTransportError.connectionFailed(let detail) = error {
                        lastFailure = detail
                        continue
                    }
                    throw error
                }
            }
            guard let video = accepted[.video], let control = accepted[.control] else {
                throw IOSScreenTransportError.timedOut
            }
            NSScreenTLSListenerClose(listener)
            return IOSScreenChannelPair(video: video, control: control)
        } catch {
            accepted.values.forEach { $0.cancel() }
            throw error
        }
    }

    func cancel() {
        NSScreenTLSListenerClose(listener)
    }

    private func acceptConnection(timeoutMilliseconds: Int64) async throws -> IOSScreenWireConnection? {
        let timeout = Int32(min(Int64(Int32.max), max(1, timeoutMilliseconds)))
        do {
            let accepted = try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation {
                    (continuation: CheckedContinuation<IOSScreenWireConnection?, Error>) in
                    queue.async { [self] in
                        var connection: OpaquePointer?
                        var errorBuffer = [CChar](repeating: 0, count: 512)
                        let result = NSScreenTLSListenerAccept(
                            listener,
                            timeout,
                            &connection,
                            &errorBuffer,
                            errorBuffer.count
                        )
                        switch result {
                        case 1:
                            guard let connection else {
                                continuation.resume(throwing: IOSScreenTransportError.connectionFailed("missing TLS connection"))
                                return
                            }
                            continuation.resume(returning: IOSScreenWireConnection(
                                connection,
                                label: "net.extrawdw.apps.NotiSync.screen-channel"
                            ))
                        case 0:
                            continuation.resume(returning: nil)
                        default:
                            continuation.resume(throwing: IOSScreenTransportError.connectionFailed(
                                String(cString: errorBuffer)
                            ))
                        }
                    }
                }
            } onCancel: {
                NSScreenTLSListenerClose(listener)
            }
            guard !Task.isCancelled else {
                accepted?.cancel()
                throw CancellationError()
            }
            return accepted
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw error
        }
    }

}

/// Requester-side listener whose two channels rendezvous through the authenticated broker.
nonisolated final class IOSScreenBrokerRelayListener: IOSScreenSessionListener, @unchecked Sendable {
    let candidates: [ScreenMirrorConnectionCandidate]

    private let broker: BrokerClient
    private let relayId: String
    private let descriptor: IOSScreenSessionDescriptor
    private var routingToken: Data
    private var masterPsk: Data
    private let queue = DispatchQueue(label: "net.extrawdw.apps.NotiSync.screen-relay-tls", qos: .userInitiated)
    private let lock = NSLock()
    private var connections: [BrokerScreenRelayConnection] = []
    private var controlStream: IOSRelayControlByteStream?
    private var closed = false

    init(
        broker: BrokerClient,
        relayId: String,
        descriptor: IOSScreenSessionDescriptor,
        routingToken: Data,
        masterPsk: Data
    ) throws {
        guard routingToken.count == 16, masterPsk.count == 32 else {
            throw IOSScreenTransportError.listenerFailed("invalid session secrets")
        }
        self.broker = broker
        self.relayId = relayId
        self.descriptor = descriptor
        self.routingToken = routingToken
        self.masterPsk = masterPsk
        candidates = [ScreenMirrorConnectionCandidate(
            kind: ScreenMirrorConnectionCandidate.brokerRelay,
            host: nil,
            port: nil,
            serviceName: relayId,
            interfaceName: nil
        )]
    }

    deinit { cancel() }

    func acceptPair() async throws -> IOSScreenChannelPair {
        var secrets = try lock.withLock { () throws -> (routingToken: Data, masterPsk: Data) in
            guard !closed else { throw IOSScreenTransportError.channelClosed }
            return (routingToken, masterPsk)
        }
        defer {
            secrets.routingToken.resetBytes(in: secrets.routingToken.startIndex..<secrets.routingToken.endIndex)
            secrets.masterPsk.resetBytes(in: secrets.masterPsk.startIndex..<secrets.masterPsk.endIndex)
        }
        do {
            let videoRelay = try await open(.video)
            try track(videoRelay)
            let controlRelay = try await open(.control)
            try track(controlRelay)

            let stream = IOSRelayControlByteStream(connection: controlRelay)
            try track(stream)
            stream.start()
            var controlKey = IOSScreenSessionKeyDeriver.derive(
                masterPsk: secrets.masterPsk,
                routingToken: secrets.routingToken,
                descriptor: descriptor,
                channel: .control
            )
            defer { controlKey.resetBytes(in: controlKey.startIndex..<controlKey.endIndex) }
            let identity = Data("nss1.\(secrets.routingToken.base64URLEncoded()).control".utf8)
            let control = try await acceptControlTLS(identity: identity, key: controlKey, stream: stream)
            guard try await authenticateScreenBinding(on: control, descriptor: descriptor) == .control else {
                throw IOSScreenTransportError.malformedBinding
            }

            let videoStream = IOSRelayVideoStream(
                connection: videoRelay,
                descriptor: descriptor,
                routingToken: secrets.routingToken,
                masterPsk: secrets.masterPsk
            )
            clearSecrets()
            return IOSScreenChannelPair(
                video: IOSScreenWireConnection(relayVideo: videoStream),
                control: control
            )
        } catch {
            cancel()
            if Task.isCancelled { throw CancellationError() }
            throw error
        }
    }

    func cancel() {
        let resources: ([BrokerScreenRelayConnection], IOSRelayControlByteStream?) = lock.withLock {
            guard !closed else { return ([], nil) }
            closed = true
            let resources = (connections, controlStream)
            connections.removeAll()
            controlStream = nil
            clearSecretsLocked()
            return resources
        }
        resources.1?.close()
        resources.0.forEach { $0.cancel() }
    }

    private func open(_ channel: ScreenRelayChannel) async throws -> BrokerScreenRelayConnection {
        guard descriptor.expiresAt > NotiSyncEngine.nowMillis() else {
            throw IOSScreenTransportError.timedOut
        }
        return try await broker.openScreenRelay(ScreenRelayJoin(
            relayId: relayId,
            requesterPeerId: descriptor.requesterPeerId,
            sourcePeerId: descriptor.sourcePeerId,
            role: .requester,
            channel: channel,
            expiresAt: descriptor.expiresAt
        ))
    }

    private func track(_ connection: BrokerScreenRelayConnection) throws {
        try lock.withLock {
            guard !closed else {
                connection.cancel()
                throw IOSScreenTransportError.channelClosed
            }
            connections.append(connection)
        }
    }

    private func track(_ stream: IOSRelayControlByteStream) throws {
        try lock.withLock {
            guard !closed else {
                stream.close()
                throw IOSScreenTransportError.channelClosed
            }
            controlStream = stream
        }
    }

    private func acceptControlTLS(
        identity: Data,
        key: Data,
        stream: IOSRelayControlByteStream
    ) async throws -> IOSScreenWireConnection {
        let remaining = descriptor.expiresAt - NotiSyncEngine.nowMillis()
        guard remaining > 0 else { throw IOSScreenTransportError.timedOut }
        let timeout = Int32(min(Int64(10_000), remaining))
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation {
                (continuation: CheckedContinuation<IOSScreenWireConnection, Error>) in
                queue.async {
                    let retainedContext = Unmanaged.passRetained(stream).toOpaque()
                    var errorBuffer = [CChar](repeating: 0, count: 512)
                    let connection = identity.withUnsafeBytes { identityBytes in
                        key.withUnsafeBytes { keyBytes in
                            NSScreenTLSStreamServerAccept(
                                identityBytes.bindMemory(to: UInt8.self).baseAddress!,
                                identityBytes.count,
                                keyBytes.bindMemory(to: UInt8.self).baseAddress!,
                                keyBytes.count,
                                iosScreenRelayStreamRead,
                                iosScreenRelayStreamWrite,
                                iosScreenRelayStreamClose,
                                retainedContext,
                                timeout,
                                &errorBuffer,
                                errorBuffer.count
                            )
                        }
                    }
                    guard let connection else {
                        continuation.resume(throwing: IOSScreenTransportError.connectionFailed(
                            String(cString: errorBuffer)
                        ))
                        return
                    }
                    continuation.resume(returning: IOSScreenWireConnection(
                        connection,
                        label: "net.extrawdw.apps.NotiSync.screen-relay-control"
                    ))
                }
            }
        } onCancel: {
            stream.close()
        }
    }

    private func clearSecrets() {
        lock.withLock { clearSecretsLocked() }
    }

    private func clearSecretsLocked() {
        routingToken.resetBytes(in: routingToken.startIndex..<routingToken.endIndex)
        masterPsk.resetBytes(in: masterPsk.startIndex..<masterPsk.endIndex)
    }
}

/// A small blocking ordered stream used only by OpenSSL's custom BIO callbacks.
private nonisolated final class IOSRelayControlByteStream: @unchecked Sendable {
    private let connection: BrokerScreenRelayConnection
    private let condition = NSCondition()
    private var buffer = Data()
    private var offset = 0
    private var terminal = false
    private var failed = false
    private var receiveTask: Task<Void, Never>?

    init(connection: BrokerScreenRelayConnection) {
        self.connection = connection
    }

    func start() {
        condition.lock()
        guard receiveTask == nil, !terminal else {
            condition.unlock()
            return
        }
        receiveTask = Task { [weak self] in await self?.receiveLoop() }
        condition.unlock()
    }

    func read(into target: UnsafeMutablePointer<UInt8>, maximumLength: Int, timeoutMilliseconds: Int32) -> Int {
        condition.lock()
        defer { condition.unlock() }
        let deadline = timeoutMilliseconds < 0 ? nil : Date(timeIntervalSinceNow: Double(timeoutMilliseconds) / 1_000)
        while offset == buffer.count, !terminal {
            if let deadline {
                guard condition.wait(until: deadline) else { return -2 }
            } else {
                condition.wait()
            }
        }
        guard offset < buffer.count else { return failed ? -1 : 0 }
        let count = min(maximumLength, buffer.count - offset)
        buffer.copyBytes(to: target, from: offset..<(offset + count))
        offset += count
        if offset == buffer.count {
            buffer.removeAll(keepingCapacity: true)
            offset = 0
        }
        return count
    }

    func write(_ data: Data, timeoutMilliseconds: Int32) -> Int {
        guard !data.isEmpty else { return 0 }
        var cursor = 0
        while cursor < data.count {
            let count = min(2 * 1_024, data.count - cursor)
            let frame = data.subdata(in: cursor..<(cursor + count))
            let result = IOSRelaySynchronousSendResult()
            let task = Task {
                do {
                    try await connection.sendBinary(frame)
                    result.finish(nil)
                } catch {
                    result.finish(error)
                }
            }
            let waitResult: DispatchTimeoutResult
            if timeoutMilliseconds < 0 {
                result.semaphore.wait()
                waitResult = .success
            } else {
                waitResult = result.semaphore.wait(timeout: .now() + .milliseconds(Int(timeoutMilliseconds)))
            }
            guard waitResult == .success else {
                task.cancel()
                close()
                return -2
            }
            if result.error != nil {
                close(failed: true)
                return -1
            }
            cursor += count
        }
        return data.count
    }

    func close() { close(failed: false) }

    private func close(failed: Bool) {
        condition.lock()
        guard !terminal else {
            condition.unlock()
            return
        }
        terminal = true
        self.failed = self.failed || failed
        let task = receiveTask
        receiveTask = nil
        condition.broadcast()
        condition.unlock()
        task?.cancel()
        connection.cancel()
    }

    private func receiveLoop() async {
        do {
            while !Task.isCancelled {
                let message = try await connection.receive()
                guard case .data(let data) = message, !data.isEmpty else {
                    throw BrokerScreenRelayError.unexpectedMessage
                }
                guard appendIncoming(data) else { return }
            }
        } catch {
            close(failed: !Task.isCancelled)
        }
    }

    private func appendIncoming(_ data: Data) -> Bool {
        condition.lock()
        defer { condition.unlock() }
        guard !terminal else { return false }
        buffer.append(data)
        condition.broadcast()
        return true
    }
}

private nonisolated final class IOSRelaySynchronousSendResult: @unchecked Sendable {
    let semaphore = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var storedError: Error?

    var error: Error? { lock.withLock { storedError } }

    func finish(_ error: Error?) {
        lock.withLock { storedError = error }
        semaphore.signal()
    }
}

private nonisolated let iosScreenRelayStreamRead: NSScreenTLSStreamReadCallback = {
    context, buffer, maximumLength, timeoutMilliseconds in
    guard let context, let buffer else { return -1 }
    return Unmanaged<IOSRelayControlByteStream>.fromOpaque(context).takeUnretainedValue().read(
        into: buffer,
        maximumLength: maximumLength,
        timeoutMilliseconds: timeoutMilliseconds
    )
}

private nonisolated let iosScreenRelayStreamWrite: NSScreenTLSStreamWriteCallback = {
    context, buffer, length, timeoutMilliseconds in
    guard let context, let buffer else { return -1 }
    let data = Data(bytes: buffer, count: length)
    return Unmanaged<IOSRelayControlByteStream>.fromOpaque(context).takeUnretainedValue().write(
        data,
        timeoutMilliseconds: timeoutMilliseconds
    )
}

private nonisolated let iosScreenRelayStreamClose: NSScreenTLSStreamCloseCallback = { context in
    guard let context else { return }
    let stream = Unmanaged<IOSRelayControlByteStream>.fromOpaque(context).takeRetainedValue()
    stream.close()
}

private nonisolated struct IOSRelayVideoFragmentHeader: Sendable {
    var flags: UInt8
    var sequence: Int64
    var recordBytes: Int
    var fragmentOffset: Int
    var fragmentBytes: Int
    var firstFragment: Bool { fragmentOffset == 0 }
}

/// Decrypts Relay video records, restores the ordinary scrcpy stream, and ACKs parser consumption.
actor IOSRelayVideoStream {
    nonisolated private let connection: BrokerScreenRelayConnection
    private var key: Data
    private var pendingFrame: Data?
    private var currentRecord: (header: IOSRelayVideoFragmentHeader, bytes: Data)?
    private var currentOffset = 0
    private var lastSequence: Int64 = -1
    private var deliveredBytes: Int64 = 0
    private var preambleReceived = false
    private var needsKeyFrame = false
    private var closed = false

    init(
        connection: BrokerScreenRelayConnection,
        descriptor: IOSScreenSessionDescriptor,
        routingToken: Data,
        masterPsk: Data
    ) {
        self.connection = connection
        var channelKey = IOSScreenSessionKeyDeriver.derive(
            masterPsk: masterPsk,
            routingToken: routingToken,
            descriptor: descriptor,
            channel: .video
        )
        key = Data(HMAC<SHA256>.authenticationCode(
            for: Data("notisync-screen-relay-video-v1".utf8),
            using: SymmetricKey(data: channelKey)
        ))
        channelKey.resetBytes(in: channelKey.startIndex..<channelKey.endIndex)
    }

    func readExactly(_ count: Int, deadline: DispatchTime?) async throws -> Data {
        guard !closed else { throw IOSScreenTransportError.channelClosed }
        var result = Data()
        result.reserveCapacity(count)
        while result.count < count {
            if currentRecord == nil {
                currentRecord = try await nextDeliverableRecord(deadline: deadline)
                currentOffset = 0
            }
            guard let currentRecord else { throw IOSScreenTransportError.channelClosed }
            let copied = min(count - result.count, currentRecord.bytes.count - currentOffset)
            result.append(currentRecord.bytes.subdata(in: currentOffset..<(currentOffset + copied)))
            currentOffset += copied
            if currentOffset == currentRecord.bytes.count {
                try await acknowledge(currentRecord.header.sequence, bytes: currentRecord.header.recordBytes)
                self.currentRecord = nil
                currentOffset = 0
            }
        }
        return result
    }

    nonisolated func cancel() {
        connection.cancel()
        Task { await close() }
    }

    private func close() {
        guard !closed else { return }
        closed = true
        key.resetBytes(in: key.startIndex..<key.endIndex)
        connection.cancel()
    }

    private func nextDeliverableRecord(deadline: DispatchTime?) async throws
        -> (header: IOSRelayVideoFragmentHeader, bytes: Data) {
        while !closed {
            let record = try await receiveRecord(deadline: deadline)
            let sequence = record.header.sequence
            if lastSequence >= 0, sequence != lastSequence + 1 { needsKeyFrame = true }
            lastSequence = sequence
            switch record.header.flags {
            case IOSRelayVideoWire.preamble:
                guard !preambleReceived, sequence == 0, record.bytes.count == 16 else {
                    throw IOSScreenTransportError.connectionFailed("invalid Relay video preamble")
                }
                preambleReceived = true
                needsKeyFrame = false
                return record
            case IOSRelayVideoWire.session:
                try requirePreamble()
                needsKeyFrame = true
                return record
            case IOSRelayVideoWire.codecConfig:
                try requirePreamble()
                return record
            case IOSRelayVideoWire.keyFrame:
                try requirePreamble()
                needsKeyFrame = false
                return record
            case IOSRelayVideoWire.delta:
                try requirePreamble()
                if !needsKeyFrame { return record }
                try await acknowledge(sequence, bytes: 0)
            default:
                throw IOSScreenTransportError.connectionFailed("unknown Relay video record type")
            }
        }
        throw IOSScreenTransportError.channelClosed
    }

    private func receiveRecord(deadline: DispatchTime?) async throws
        -> (header: IOSRelayVideoFragmentHeader, bytes: Data) {
        while true {
            let first = try await takeFrame(deadline: deadline)
            let firstHeader = try IOSRelayVideoWire.decode(first)
            if !firstHeader.firstFragment {
                _ = try decrypt(first, header: firstHeader)
                needsKeyFrame = true
                try await discardRecord(firstHeader, deadline: deadline)
                continue
            }
            var record = Data(count: firstHeader.recordBytes)
            var expectedOffset = 0
            var frame = first
            while true {
                let header = try IOSRelayVideoWire.decode(frame)
                if header.sequence != firstHeader.sequence || header.flags != firstHeader.flags ||
                    header.recordBytes != firstHeader.recordBytes || header.fragmentOffset != expectedOffset {
                    if header.sequence > firstHeader.sequence, header.firstFragment {
                        pendingFrame = frame
                        needsKeyFrame = true
                        try await acknowledge(firstHeader.sequence, bytes: 0)
                        break
                    }
                    throw IOSScreenTransportError.connectionFailed("invalid Relay video fragment sequence")
                }
                let plaintext = try decrypt(frame, header: header)
                record.replaceSubrange(expectedOffset..<(expectedOffset + plaintext.count), with: plaintext)
                expectedOffset += plaintext.count
                if expectedOffset == record.count { return (firstHeader, record) }
                frame = try await takeFrame(deadline: deadline)
            }
        }
    }

    private func discardRecord(_ initial: IOSRelayVideoFragmentHeader, deadline: DispatchTime?) async throws {
        var expectedOffset = initial.fragmentOffset + initial.fragmentBytes
        while expectedOffset < initial.recordBytes {
            let frame = try await takeFrame(deadline: deadline)
            let header = try IOSRelayVideoWire.decode(frame)
            if header.sequence != initial.sequence {
                pendingFrame = frame
                break
            }
            guard header.flags == initial.flags, header.recordBytes == initial.recordBytes,
                  header.fragmentOffset == expectedOffset else {
                throw IOSScreenTransportError.connectionFailed("invalid discarded Relay video fragment sequence")
            }
            expectedOffset += try decrypt(frame, header: header).count
        }
        try await acknowledge(initial.sequence, bytes: 0)
    }

    private func takeFrame(deadline: DispatchTime?) async throws -> Data {
        if let pendingFrame {
            self.pendingFrame = nil
            return pendingFrame
        }
        let message: URLSessionWebSocketTask.Message
        if let deadline {
            let now = DispatchTime.now().uptimeNanoseconds
            guard deadline.uptimeNanoseconds > now else { throw IOSScreenTransportError.timedOut }
            let remaining = deadline.uptimeNanoseconds - now
            message = try await withThrowingTaskGroup(of: URLSessionWebSocketTask.Message.self) { group in
                group.addTask { try await self.connection.receive() }
                group.addTask {
                    try await Task.sleep(nanoseconds: remaining)
                    self.connection.cancel()
                    throw IOSScreenTransportError.timedOut
                }
                defer { group.cancelAll() }
                guard let value = try await group.next() else { throw IOSScreenTransportError.timedOut }
                return value
            }
        } else {
            message = try await connection.receive()
        }
        guard case .data(let data) = message else {
            throw IOSScreenTransportError.connectionFailed("invalid Relay video message")
        }
        return data
    }

    private func acknowledge(_ sequence: Int64, bytes: Int) async throws {
        deliveredBytes += Int64(bytes)
        let signal = ScreenRelaySignal(
            kind: ScreenRelaySignalKind.videoAck,
            sequence: sequence,
            deliveredBytes: deliveredBytes
        )
        let data = try JSONEncoder().encode(signal)
        try await connection.sendText(String(decoding: data, as: UTF8.self))
    }

    func requestRecovery() async throws {
        guard !closed else { throw IOSScreenTransportError.channelClosed }
        guard lastSequence >= 0 else { return }
        let signal = ScreenRelaySignal(
            kind: ScreenRelaySignalKind.videoCongested,
            detail: "iOS video renderer requested a fresh key frame",
            sequence: lastSequence,
            deliveredBytes: deliveredBytes
        )
        let data = try JSONEncoder().encode(signal)
        try await connection.sendText(String(decoding: data, as: UTF8.self))
    }

    private func decrypt(_ message: Data, header: IOSRelayVideoFragmentHeader) throws -> Data {
        let headerData = message.prefix(IOSRelayVideoWire.headerBytes)
        let body = message.dropFirst(IOSRelayVideoWire.headerBytes)
        let ciphertext = body.dropLast(IOSRelayVideoWire.tagBytes)
        let tag = body.suffix(IOSRelayVideoWire.tagBytes)
        var nonceData = Data()
        nonceData.appendInt64(header.sequence)
        nonceData.appendUInt32(UInt32(header.fragmentOffset))
        do {
            let sealed = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonceData),
                ciphertext: ciphertext,
                tag: tag
            )
            return try AES.GCM.open(
                sealed,
                using: SymmetricKey(data: key),
                authenticating: headerData
            )
        } catch {
            throw IOSScreenTransportError.connectionFailed("Relay video authentication failed")
        }
    }

    private func requirePreamble() throws {
        guard preambleReceived else {
            throw IOSScreenTransportError.connectionFailed("Relay video record arrived before preamble")
        }
    }
}

private nonisolated enum IOSRelayVideoWire {
    static let headerBytes = 28
    static let tagBytes = 16
    static let maximumMessageBytes = 16 * 1_024
    static let maximumFragmentBytes = maximumMessageBytes - headerBytes - tagBytes
    static let maximumRecordBytes = 16 * 1_024 * 1_024 + 12
    static let preamble: UInt8 = 1
    static let session: UInt8 = 2
    static let codecConfig: UInt8 = 4
    static let keyFrame: UInt8 = 8
    static let delta: UInt8 = 16
    private static let knownFlags = preamble | session | codecConfig | keyFrame | delta

    static func decode(_ message: Data) throws -> IOSRelayVideoFragmentHeader {
        guard message.count >= headerBytes + tagBytes + 1, message.count <= maximumMessageBytes,
              uint32(message, 0) == 0x4e535231,
              message[4] == 1, message[5] == 1, message[7] == 0 else {
            throw IOSScreenTransportError.connectionFailed("invalid Relay video message")
        }
        let flags = message[6]
        let sequenceBits = uint64(message, 8)
        let recordBytes = Int(uint32(message, 16))
        let fragmentOffset = Int(uint32(message, 20))
        let fragmentBytes = Int(uint32(message, 24))
        guard flags != 0, flags & knownFlags == flags, flags.nonzeroBitCount == 1,
              sequenceBits <= UInt64(Int64.max),
              (1...maximumRecordBytes).contains(recordBytes),
              fragmentOffset >= 0, (1...maximumFragmentBytes).contains(fragmentBytes),
              fragmentOffset <= recordBytes - fragmentBytes,
              message.count == headerBytes + fragmentBytes + tagBytes else {
            throw IOSScreenTransportError.connectionFailed("invalid Relay video fragment metadata")
        }
        return IOSRelayVideoFragmentHeader(
            flags: flags,
            sequence: Int64(sequenceBits),
            recordBytes: recordBytes,
            fragmentOffset: fragmentOffset,
            fragmentBytes: fragmentBytes
        )
    }

    private static func uint32(_ data: Data, _ offset: Int) -> UInt32 {
        data[offset..<(offset + 4)].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }

    private static func uint64(_ data: Data, _ offset: Int) -> UInt64 {
        data[offset..<(offset + 8)].reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    }
}

private nonisolated func authenticateScreenBinding(
    on connection: IOSScreenWireConnection,
    descriptor: IOSScreenSessionDescriptor
) async throws -> IOSScreenChannel {
    let deadline = DispatchTime.now() + .seconds(10)
    let sizeData = try await connection.readExactly(4, deadline: deadline)
    var sizeReader = IOSScreenDataReader(sizeData)
    let size = Int(try sizeReader.uint32())
    guard size > 0, size <= 4 * 1_024 else { throw IOSScreenTransportError.malformedBinding }
    let body = try await connection.readExactly(size, deadline: deadline)
    var reader = IOSScreenDataReader(body)
    guard try reader.uint32() == 0x4e535331,
          try reader.uint16() == 1,
          let channel = IOSScreenChannel(rawValue: try reader.uint8()),
          try reader.uint8() == 0 else {
        throw IOSScreenTransportError.malformedBinding
    }
    let remote = try reader.descriptor()
    guard remote == descriptor, reader.isAtEnd else { throw IOSScreenTransportError.malformedBinding }

    var responseBody = Data()
    responseBody.appendUInt32(0x4e535331)
    responseBody.appendUInt16(1)
    responseBody.append(channel.rawValue)
    responseBody.append(1)
    responseBody.append(descriptor: descriptor)
    var response = Data()
    response.appendUInt32(UInt32(responseBody.count))
    response.append(responseBody)
    try await connection.send(response)
    return channel
}

nonisolated enum IOSScreenSessionKeyDeriver {
    static func derive(
        masterPsk: Data,
        routingToken: Data,
        descriptor: IOSScreenSessionDescriptor,
        channel: IOSScreenChannel
    ) -> Data {
        let base = context(descriptor: descriptor, routingToken: routingToken, channel: nil)
        let full = context(descriptor: descriptor, routingToken: routingToken, channel: channel)
        let salt = Data(SHA256.hash(data: base))
        let prk = Data(HMAC<SHA256>.authenticationCode(for: masterPsk, using: SymmetricKey(data: salt)))
        var info = full
        info.append(1)
        return Data(HMAC<SHA256>.authenticationCode(for: info, using: SymmetricKey(data: prk)))
    }

    private static func context(
        descriptor: IOSScreenSessionDescriptor,
        routingToken: Data,
        channel: IOSScreenChannel?
    ) -> Data {
        var data = Data()
        data.appendUTF8("notisync-screen")
        data.appendUInt32(1)
        data.appendUTF8(descriptor.sessionId)
        data.appendUTF8(descriptor.sourcePeerId)
        data.appendUTF8(descriptor.requesterPeerId)
        data.appendInt64(descriptor.issuedAt)
        data.appendInt64(descriptor.expiresAt)
        data.appendUTF8(descriptor.codec)
        data.append(descriptor.controlEnabled ? 1 : 0)
        data.append(descriptor.clipboardEnabled ? 1 : 0)
        data.appendUInt32(UInt32(descriptor.maxDimension))
        data.appendUInt32(UInt32(descriptor.maxFps))
        data.appendUInt32(UInt32(descriptor.videoBitrateBps))
        data.appendUInt32(UInt32(routingToken.count))
        data.append(routingToken)
        data.appendUTF8(channel?.wireName ?? "session")
        return data
    }
}

private nonisolated struct IOSScreenDataReader {
    private let data: Data
    private var offset = 0

    init(_ data: Data) { self.data = data }
    var isAtEnd: Bool { offset == data.count }

    mutating func uint8() throws -> UInt8 {
        guard offset < data.count else { throw IOSScreenTransportError.malformedBinding }
        defer { offset += 1 }
        return data[offset]
    }

    mutating func uint16() throws -> UInt16 {
        let bytes = try take(2)
        return bytes.reduce(UInt16(0)) { ($0 << 8) | UInt16($1) }
    }

    mutating func uint32() throws -> UInt32 {
        let bytes = try take(4)
        return bytes.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }

    mutating func int64() throws -> Int64 {
        let bytes = try take(8)
        let value = bytes.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        return Int64(bitPattern: value)
    }

    mutating func string(maxBytes: Int) throws -> String {
        let count = Int(try uint16())
        guard count <= maxBytes else { throw IOSScreenTransportError.malformedBinding }
        let bytes = try take(count)
        guard let result = String(data: bytes, encoding: .utf8), Data(result.utf8) == bytes else {
            throw IOSScreenTransportError.malformedBinding
        }
        return result
    }

    mutating func descriptor() throws -> IOSScreenSessionDescriptor {
        IOSScreenSessionDescriptor(
            sessionId: try string(maxBytes: 128),
            sourcePeerId: try string(maxBytes: 256),
            requesterPeerId: try string(maxBytes: 256),
            issuedAt: try int64(),
            expiresAt: try int64(),
            codec: try string(maxBytes: 32),
            controlEnabled: try uint8() != 0,
            clipboardEnabled: try uint8() != 0,
            maxDimension: Int(try uint32()),
            maxFps: Int(try uint32()),
            videoBitrateBps: Int(try uint32())
        )
    }

    private mutating func take(_ count: Int) throws -> Data {
        guard count >= 0, offset + count <= data.count else { throw IOSScreenTransportError.malformedBinding }
        defer { offset += count }
        return data.subdata(in: offset..<(offset + count))
    }
}

nonisolated extension Data {
    func base64URLEncoded() -> String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    mutating func appendUInt16(_ value: UInt16) {
        append(UInt8((value >> 8) & 0xff)); append(UInt8(value & 0xff))
    }

    mutating func appendUInt32(_ value: UInt32) {
        append(UInt8((value >> 24) & 0xff)); append(UInt8((value >> 16) & 0xff))
        append(UInt8((value >> 8) & 0xff)); append(UInt8(value & 0xff))
    }

    mutating func appendInt64(_ value: Int64) {
        let bits = UInt64(bitPattern: value)
        for shift in stride(from: 56, through: 0, by: -8) { append(UInt8((bits >> UInt64(shift)) & 0xff)) }
    }

    mutating func appendUTF8(_ value: String) {
        let bytes = Data(value.utf8)
        precondition(bytes.count <= Int(UInt16.max))
        appendUInt16(UInt16(bytes.count)); append(bytes)
    }

    mutating func append(descriptor: IOSScreenSessionDescriptor) {
        appendUTF8(descriptor.sessionId)
        appendUTF8(descriptor.sourcePeerId)
        appendUTF8(descriptor.requesterPeerId)
        appendInt64(descriptor.issuedAt)
        appendInt64(descriptor.expiresAt)
        appendUTF8(descriptor.codec)
        append(descriptor.controlEnabled ? 1 : 0)
        append(descriptor.clipboardEnabled ? 1 : 0)
        appendUInt32(UInt32(descriptor.maxDimension))
        appendUInt32(UInt32(descriptor.maxFps))
        appendUInt32(UInt32(descriptor.videoBitrateBps))
    }
}

private nonisolated func localLANCandidates(port: UInt16) -> [ScreenMirrorConnectionCandidate] {
    var first: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&first) == 0, let first else { return [] }
    defer { freeifaddrs(first) }
    var result: [ScreenMirrorConnectionCandidate] = []
    var pointer: UnsafeMutablePointer<ifaddrs>? = first
    while let current = pointer {
        defer { pointer = current.pointee.ifa_next }
        let record = current.pointee
        guard let address = record.ifa_addr else { continue }
        let flags = Int32(record.ifa_flags)
        guard flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0 else { continue }
        let interface = String(cString: record.ifa_name)
        guard interface.hasPrefix("en") else { continue }
        guard address.pointee.sa_family == UInt8(AF_INET) || address.pointee.sa_family == UInt8(AF_INET6) else {
            continue
        }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        guard getnameinfo(
            address,
            socklen_t(address.pointee.sa_len),
            &host,
            socklen_t(host.count),
            nil,
            0,
            NI_NUMERICHOST
        ) == 0 else { continue }
        let raw = String(cString: host)
        let value = raw.split(separator: "%", maxSplits: 1).first.map(String.init) ?? raw
        result.append(ScreenMirrorConnectionCandidate(
            kind: ScreenMirrorConnectionCandidate.lanTCP,
            host: value,
            port: Int(port),
            serviceName: nil,
            interfaceName: interface
        ))
    }
    return result.reduce(into: []) { unique, candidate in
        if !unique.contains(where: { $0.host == candidate.host }) { unique.append(candidate) }
    }
}
