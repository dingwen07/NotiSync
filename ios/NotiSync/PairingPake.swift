import CryptoKit
import Foundation

/// Same single-use J-PAKE state machine and transcript as peer-core/PairingPake.kt.
/// OpenSSL performs finite-field arithmetic; CryptoKit handles key confirmation, HKDF and AES-GCM.
nonisolated final class PairingPake {
    private var native: OpaquePointer?
    private let role: PairingRole
    private let context: String
    private var phase = 0
    private var previous = Data()
    private var transcript = Data()
    private var material = Data()
    private var localGX: [Data] = []
    private var remoteGX: [Data] = []
    private var transcriptHash = Data()
    private var sendKey: SymmetricKey?
    private var receiveKey: SymmetricKey?
    private var sentCard = false
    private var receivedCard = false

    init(link: BrokerPairingLink, role: PairingRole) throws {
        self.role = role
        context = "notisync-pair-v1|\(link.brokerURL)|\(link.sessionId)|\(link.hostId)"
        let localID = "\(context)|\(role.rawValue)", remoteID = "\(context)|\(role.peer.rawValue)"
        var secret = Data(link.secret.utf8)
        defer { secret.resetBytes(in: 0..<secret.count) }
        native = localID.withCString { local in remoteID.withCString { remote in
            secret.withUnsafeBytes { NSBrokerPairingPakeCreate(local, remote, $0.bindMemory(to: UInt8.self).baseAddress, secret.count) }
        } }
        guard native != nil else { throw BrokerPairingError.authentication }
    }
    deinit { close() }

    func close() {
        phase = -1
        NSBrokerPairingPakeDestroy(native); native = nil
        material.resetBytes(in: 0..<material.count); material = Data()
        sendKey = nil; receiveKey = nil; previous = Data(); transcript = Data()
    }

    func round1() throws -> Data {
        try step(0) {
            let values = try nativeCall { state, _, _, out, length in NSBrokerPairingPakeRound1(state, out, length) }
            localGX = Array(values.prefix(2)).map(Self.unsigned)
            return encode(1, values)
        }
    }
    func round2(_ bytes: Data) throws -> Data {
        try step(1) {
            let values = try decode(bytes, round: 1, count: 6)
            let next = try nativeCall(Self.pack(values), NSBrokerPairingPakeRound2)
            remoteGX = Array(values.prefix(2)).map(Self.unsigned)
            record(bytes)
            return encode(2, next)
        }
    }
    func round3(_ bytes: Data) throws -> Data {
        try step(2) {
            let values = try decode(bytes, round: 2, count: 3)
            let key = try nativeCall(Self.pack(values), NSBrokerPairingPakeKey)
            guard key.count == 1 else { throw BrokerPairingError.authentication }
            material = key[0]
            record(bytes)
            return encode(3, [Self.canonicalSigned(Data(HMAC<SHA256>.authenticationCode(for: macData(role), using: macKey())))])
        }
    }
    func confirm(_ bytes: Data) throws {
        try step(3) {
            let tag = try decode(bytes, round: 3, count: 1)[0]
            guard tag.count <= 32, Self.canonicalSigned(tag) == tag else { throw BrokerPairingError.authentication }
            let padded = Data(repeating: tag.first! & 128 == 0 ? 0 : 255, count: 32 - tag.count) + tag
            guard HMAC<SHA256>.isValidAuthenticationCode(padded, authenticating: macData(role.peer), using: macKey())
            else { throw BrokerPairingError.authentication }
            record(bytes)
            transcriptHash = Data(SHA256.hash(data: transcript))
            sendKey = derive(role); receiveKey = derive(role.peer)
            material.resetBytes(in: 0..<material.count); material = Data()
            NSBrokerPairingPakeDestroy(native); native = nil
            transcript = Data(); previous = Data(); localGX = []; remoteGX = []
        }
    }
    func encryptCard(_ payload: String) throws -> Data {
        guard phase == 4, !sentCard, let sendKey else { throw BrokerPairingError.authentication }
        sentCard = true
        let plain = Data(payload.utf8)
        guard (1...BrokerPairingLink.maxCardBytes).contains(plain.count) else { throw BrokerPairingError.authentication }
        let sealed = try AES.GCM.seal(plain, using: sendKey, authenticating: aad(role))
        return BrokerPairingCodec.encrypt(nonce: Data(sealed.nonce), ciphertext: sealed.ciphertext + sealed.tag)
    }
    func decryptCard(_ bytes: Data) throws -> String {
        guard phase == 4, !receivedCard, let receiveKey else { throw BrokerPairingError.authentication }
        receivedCard = true
        let card = try BrokerPairingCodec.decrypt(bytes)
        guard card.nonce.count == 12, (17...(BrokerPairingLink.maxCardBytes + 16)).contains(card.ciphertext.count)
        else { throw BrokerPairingError.authentication }
        let sealed = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: card.nonce),
                                          ciphertext: card.ciphertext.dropLast(16), tag: card.ciphertext.suffix(16))
        let plain = try AES.GCM.open(sealed, using: receiveKey, authenticating: aad(role.peer))
        guard let text = String(data: plain, encoding: .utf8) else { throw BrokerPairingError.authentication }
        return text
    }

    private func id(_ sender: PairingRole) -> String { "\(context)|\(sender.rawValue)" }
    private func aad(_ sender: PairingRole) -> Data { Data(id(sender).utf8) + transcriptHash }
    private func derive(_ sender: PairingRole) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: material), salt: transcriptHash,
                              info: Data("\(id(sender))|CARD".utf8), outputByteCount: 32)
    }
    private func macKey() -> SymmetricKey {
        SymmetricKey(data: SHA256.hash(data: Self.unsigned(material) + Data("JPAKE_KC".utf8)))
    }
    private func macData(_ sender: PairingRole) -> Data {
        let numbers = sender == role ? localGX + remoteGX : remoteGX + localGX
        return Data("KC_1_U\(id(sender))\(id(sender.peer))".utf8) + numbers.reduce(Data(), +)
    }
    private func encode(_ round: Int, _ values: [Data]) -> Data {
        previous = BrokerPairingCodec.encode(round: round, participantId: id(role), values: values)
        return previous
    }
    private func decode(_ bytes: Data, round: Int, count: Int) throws -> [Data] {
        let frame = try BrokerPairingCodec.decode(bytes)
        guard frame.round == round, frame.participantId == id(role.peer), frame.values.count == count,
              frame.values.allSatisfy({ (1...385).contains($0.count) && Self.canonicalSigned($0) == $0 })
        else { throw BrokerPairingError.authentication }
        return frame.values
    }
    private func record(_ remote: Data) {
        transcript += Self.pack(role == .HOST ? [previous, remote] : [remote, previous])
    }
    private func step<T>(_ expected: Int, _ work: () throws -> T) throws -> T {
        guard phase == expected else { throw BrokerPairingError.authentication }
        phase = -1
        do { let result = try work(); phase = expected + 1; return result }
        catch { close(); throw error }
    }
    private static func unsigned(_ data: Data) -> Data {
        data.count > 1 && data.first == 0 ? Data(data.dropFirst()) : data
    }
    private static func canonicalSigned(_ data: Data) -> Data {
        var bytes = Array(data)
        while bytes.count > 1 && ((bytes[0] == 0 && bytes[1] & 128 == 0) || (bytes[0] == 255 && bytes[1] & 128 != 0)) {
            bytes.removeFirst()
        }
        return Data(bytes)
    }
    private static func pack(_ fields: [Data]) -> Data {
        var result = Data()
        for field in fields {
            var length = UInt32(field.count).bigEndian
            withUnsafeBytes(of: &length) { result.append(contentsOf: $0) }
            result += field
        }
        return result
    }
    private func nativeCall(_ input: Data = Data(),
        _ operation: (OpaquePointer?, UnsafePointer<UInt8>?, Int, UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>?, UnsafeMutablePointer<Int>?) -> Int32
    ) throws -> [Data] {
        guard let native else { throw BrokerPairingError.authentication }
        var output: UnsafeMutablePointer<UInt8>?, length = 0
        let ok = input.withUnsafeBytes { operation(native, $0.bindMemory(to: UInt8.self).baseAddress, input.count, &output, &length) }
        defer { NSBrokerPairingBufferDestroy(output, length) }
        guard ok == 1, let output, length <= 6 * 389 else { throw BrokerPairingError.authentication }
        let bytes = Data(bytes: output, count: length)
        var offset = 0, fields: [Data] = []
        while offset < bytes.count {
            guard bytes.count - offset >= 4 else { throw BrokerPairingError.authentication }
            let n = bytes[offset..<(offset + 4)].reduce(0) { ($0 << 8) | Int($1) }
            offset += 4
            guard n > 0, n <= 385, n <= bytes.count - offset else { throw BrokerPairingError.authentication }
            fields.append(Data(bytes[offset..<(offset + n)])); offset += n
        }
        return fields
    }
}
