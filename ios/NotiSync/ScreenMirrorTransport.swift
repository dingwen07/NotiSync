import CryptoKit
import Foundation
import Network
import Security

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
    case timedOut
    case channelClosed
    case malformedBinding
    case duplicateChannel
    case unsupportedStream(String)

    var errorDescription: String? {
        switch self {
        case .noLAN: "Connect this iPhone and the Android source to the same Wi-Fi network."
        case .listenerFailed(let detail): "Could not open the local screen listener: \(detail)"
        case .timedOut: "The Android source did not connect before the request expired."
        case .channelClosed: "The Android source closed the screen connection."
        case .malformedBinding: "The Android source sent an invalid screen-channel binding."
        case .duplicateChannel: "The Android source connected the same screen channel twice."
        case .unsupportedStream(let detail): detail
        }
    }
}

private nonisolated final class IOSScreenCompletionGate: @unchecked Sendable {
    private let lock = NSLock()
    private var completed = false

    func finish(_ body: () -> Void) {
        lock.lock()
        guard !completed else {
            lock.unlock()
            return
        }
        completed = true
        lock.unlock()
        body()
    }
}

/// One TLS-protected screen channel. Network.framework owns the socket and applies back-pressure to every
/// async send/receive; callers never touch the underlying file descriptor.
nonisolated final class IOSScreenWireConnection: @unchecked Sendable {
    private let connection: NWConnection
    private let queue: DispatchQueue

    init(_ connection: NWConnection, label: String) {
        self.connection = connection
        self.queue = DispatchQueue(label: label, qos: .userInitiated)
    }

    func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let gate = IOSScreenCompletionGate()
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    gate.finish { continuation.resume() }
                case .failed(let error):
                    gate.finish { continuation.resume(throwing: error) }
                case .cancelled:
                    gate.finish { continuation.resume(throwing: IOSScreenTransportError.channelClosed) }
                default:
                    break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + .seconds(10)) { [connection] in
                gate.finish {
                    connection.cancel()
                    continuation.resume(throwing: IOSScreenTransportError.timedOut)
                }
            }
        }
    }

    func readExactly(_ count: Int, deadline: DispatchTime? = nil) async throws -> Data {
        precondition(count >= 0)
        var result = Data()
        result.reserveCapacity(count)
        while result.count < count {
            let remaining = count - result.count
            let chunk = try await receive(maximumLength: remaining, deadline: deadline)
            guard !chunk.isEmpty else { throw IOSScreenTransportError.channelClosed }
            result.append(chunk)
        }
        return result
    }

    private func receive(maximumLength: Int, deadline: DispatchTime?) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            let gate = IOSScreenCompletionGate()
            connection.receive(minimumIncompleteLength: 1, maximumLength: maximumLength) { data, _, complete, error in
                gate.finish {
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let data, !data.isEmpty {
                        continuation.resume(returning: data)
                    } else if complete {
                        continuation.resume(throwing: IOSScreenTransportError.channelClosed)
                    } else {
                        continuation.resume(returning: Data())
                    }
                }
            }
            if let deadline {
                queue.asyncAfter(deadline: deadline) { [connection] in
                    gate.finish {
                        connection.cancel()
                        continuation.resume(throwing: IOSScreenTransportError.timedOut)
                    }
                }
            }
        }
    }

    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            })
        }
    }

    func cancel() { connection.cancel() }
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
nonisolated final class IOSScreenLANListener: @unchecked Sendable {
    let candidates: [ScreenMirrorConnectionCandidate]

    private let listener: NWListener
    private let descriptor: IOSScreenSessionDescriptor
    private let incoming: AsyncThrowingStream<NWConnection, Error>
    private let incomingContinuation: AsyncThrowingStream<NWConnection, Error>.Continuation
    private let queue = DispatchQueue(label: "net.extrawdw.apps.NotiSync.screen-listener", qos: .userInitiated)

    private init(
        listener: NWListener,
        descriptor: IOSScreenSessionDescriptor,
        candidates: [ScreenMirrorConnectionCandidate],
        incoming: AsyncThrowingStream<NWConnection, Error>,
        continuation: AsyncThrowingStream<NWConnection, Error>.Continuation
    ) {
        self.listener = listener
        self.descriptor = descriptor
        self.candidates = candidates
        self.incoming = incoming
        self.incomingContinuation = continuation
    }

    static func open(
        descriptor: IOSScreenSessionDescriptor,
        routingToken: Data,
        masterPsk: Data
    ) async throws -> IOSScreenLANListener {
        guard routingToken.count == 16, masterPsk.count == 32 else {
            throw IOSScreenTransportError.listenerFailed("invalid session secrets")
        }
        let tls = NWProtocolTLS.Options()
        let security = tls.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(security, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(security, .TLSv13)
        sec_protocol_options_append_tls_ciphersuite(security, .AES_128_GCM_SHA256)
        sec_protocol_options_append_tls_ciphersuite(security, .CHACHA20_POLY1305_SHA256)
        sec_protocol_options_add_tls_application_protocol(security, "notisync-screen/1")
        sec_protocol_options_set_tls_tickets_enabled(security, false)
        sec_protocol_options_set_peer_authentication_required(security, false)

        for channel in [IOSScreenChannel.video, .control] {
            let identity = Data("nss1.\(routingToken.base64URLEncoded()).\(channel.wireName)".utf8)
            let key = IOSScreenSessionKeyDeriver.derive(
                masterPsk: masterPsk,
                routingToken: routingToken,
                descriptor: descriptor,
                channel: channel
            )
            sec_protocol_options_add_pre_shared_key(
                security,
                key.securityDispatchData,
                identity.securityDispatchData
            )
        }

        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        let parameters = NWParameters(tls: tls, tcp: tcp)
        parameters.includePeerToPeer = true
        parameters.allowLocalEndpointReuse = true
        let listener: NWListener
        do {
            listener = try NWListener(using: parameters, on: .any)
        } catch {
            throw IOSScreenTransportError.listenerFailed(error.localizedDescription)
        }

        let serviceName = "notisync-\(UUID().uuidString.lowercased())"
        listener.service = NWListener.Service(name: serviceName, type: "_notisync-screen._tcp")
        let streamPair = AsyncThrowingStream<NWConnection, Error>.makeStream(bufferingPolicy: .bufferingNewest(8))

        let port = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UInt16, Error>) in
            let gate = IOSScreenCompletionGate()
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if let port = listener.port?.rawValue {
                        gate.finish { continuation.resume(returning: port) }
                    } else {
                        gate.finish {
                            continuation.resume(throwing: IOSScreenTransportError.listenerFailed("no listener port"))
                        }
                    }
                case .failed(let error):
                    gate.finish {
                        continuation.resume(throwing: IOSScreenTransportError.listenerFailed(error.localizedDescription))
                    }
                case .cancelled:
                    gate.finish { continuation.resume(throwing: IOSScreenTransportError.channelClosed) }
                default:
                    break
                }
            }
            listener.newConnectionHandler = { streamPair.continuation.yield($0) }
            listener.start(queue: DispatchQueue(label: "net.extrawdw.apps.NotiSync.screen-listener.start"))
        }

        var candidates = localLANCandidates(port: port)
        guard !candidates.isEmpty else {
            listener.cancel()
            throw IOSScreenTransportError.noLAN
        }
        candidates.append(ScreenMirrorConnectionCandidate(
            kind: ScreenMirrorConnectionCandidate.dnsSD,
            host: nil,
            port: Int(port),
            serviceName: serviceName,
            interfaceName: nil
        ))
        return IOSScreenLANListener(
            listener: listener,
            descriptor: descriptor,
            candidates: Array(candidates.prefix(8)),
            incoming: streamPair.stream,
            continuation: streamPair.continuation
        )
    }

    func acceptPair() async throws -> IOSScreenChannelPair {
        let delay = max(1, descriptor.expiresAt - NotiSyncEngine.nowMillis())
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delay))) { [incomingContinuation] in
            incomingContinuation.finish(throwing: IOSScreenTransportError.timedOut)
        }

        var accepted: [IOSScreenChannel: IOSScreenWireConnection] = [:]
        var attempts = 0
        do {
            for try await rawConnection in incoming {
                if accepted.count == 2 { break }
                attempts += 1
                guard attempts <= 8 else {
                    rawConnection.cancel()
                    throw IOSScreenTransportError.listenerFailed("too many unauthenticated connections")
                }
                let wire = IOSScreenWireConnection(
                    rawConnection,
                    label: "net.extrawdw.apps.NotiSync.screen-channel-\(accepted.count)"
                )
                do {
                    try await wire.start()
                    let channel = try await authenticateBinding(on: wire)
                    guard accepted[channel] == nil else { throw IOSScreenTransportError.duplicateChannel }
                    accepted[channel] = wire
                    if accepted.count == 2 { break }
                } catch {
                    wire.cancel()
                    if case IOSScreenTransportError.timedOut = error { continue }
                    if error is IOSScreenTransportError { throw error }
                }
            }
            guard let video = accepted[.video], let control = accepted[.control] else {
                throw IOSScreenTransportError.timedOut
            }
            listener.cancel()
            incomingContinuation.finish()
            return IOSScreenChannelPair(video: video, control: control)
        } catch {
            accepted.values.forEach { $0.cancel() }
            throw error
        }
    }

    func cancel() {
        listener.cancel()
        incomingContinuation.finish()
    }

    private func authenticateBinding(on connection: IOSScreenWireConnection) async throws -> IOSScreenChannel {
        let deadline = DispatchTime.now() + .seconds(10)
        let sizeData = try await connection.readExactly(4, deadline: deadline)
        var sizeReader = IOSScreenDataReader(sizeData)
        let size = Int(try sizeReader.uint32())
        guard size > 0, size <= 4 * 1024 else { throw IOSScreenTransportError.malformedBinding }
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
        responseBody.append(1) // VIEWER
        responseBody.append(descriptor: descriptor)
        var response = Data()
        response.appendUInt32(UInt32(responseBody.count))
        response.append(responseBody)
        try await connection.send(response)
        return channel
    }
}

private nonisolated enum IOSScreenSessionKeyDeriver {
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

private nonisolated extension Data {
    var securityDispatchData: dispatch_data_t {
        withUnsafeBytes { DispatchData(bytes: $0)._bridgeToObjectiveC() }
    }

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
