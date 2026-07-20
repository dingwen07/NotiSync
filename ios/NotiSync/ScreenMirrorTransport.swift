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
        case .noLAN: "Connect this iPhone and the Android source to the same Wi-Fi network."
        case .listenerFailed(let detail): "Could not open the local screen listener: \(detail)"
        case .connectionFailed(let detail): "The secure screen connection failed: \(detail)"
        case .timedOut: "The Android source did not connect before the request expired."
        case .channelClosed: "The Android source closed the screen connection."
        case .malformedBinding: "The Android source sent an invalid screen-channel binding."
        case .duplicateChannel: "The Android source connected the same screen channel twice."
        case .unsupportedStream(let detail): detail
        }
    }
}

/// One OpenSSL-backed TLS 1.3 PSK channel. Blocking TLS I/O stays on a private serial queue.
nonisolated final class IOSScreenWireConnection: @unchecked Sendable {
    private let connection: OpaquePointer
    private let queue: DispatchQueue

    init(_ connection: OpaquePointer, label: String) {
        self.connection = connection
        self.queue = DispatchQueue(label: label, qos: .userInitiated)
    }

    deinit {
        NSScreenTLSConnectionDestroy(connection)
    }

    func readExactly(_ count: Int, deadline: DispatchTime? = nil) async throws -> Data {
        precondition(count >= 0)
        guard count > 0 else { return Data() }
        let timeout = try timeoutMilliseconds(until: deadline)
        do {
            let data = try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { continuation in
                    queue.async { [self] in
                        var data = Data(count: count)
                        var errorBuffer = [CChar](repeating: 0, count: 512)
                        let succeeded = data.withUnsafeMutableBytes { bytes in
                            NSScreenTLSConnectionReadExactly(
                                connection,
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
                NSScreenTLSConnectionClose(connection)
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
        do {
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    queue.async { [self] in
                        var errorBuffer = [CChar](repeating: 0, count: 512)
                        let succeeded = data.withUnsafeBytes { bytes in
                            NSScreenTLSConnectionWriteAll(
                                connection,
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
                NSScreenTLSConnectionClose(connection)
            }
            try Task.checkCancellation()
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw error
        }
    }

    func cancel() { NSScreenTLSConnectionClose(connection) }

    private func timeoutMilliseconds(until deadline: DispatchTime?) throws -> Int32 {
        guard let deadline else { return -1 }
        let now = DispatchTime.now().uptimeNanoseconds
        guard deadline.uptimeNanoseconds > now else { throw IOSScreenTransportError.timedOut }
        let remaining = (deadline.uptimeNanoseconds - now + 999_999) / 1_000_000
        return Int32(min(UInt64(Int32.max), max(1, remaining)))
    }
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
                    let channel = try await authenticateBinding(on: wire)
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
