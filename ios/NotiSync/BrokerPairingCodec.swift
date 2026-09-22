import Foundation
import NotiSyncProtocol

nonisolated enum BrokerPairingCodec {
    static var codec: SwiftProtocolCodec { SwiftProtocolCodec.shared }
    struct Frame { let round: Int; let participantId: String; let values: [Data] }

    static func request(sessionId: String?) -> Data {
        KMPProtocolBridge.data(codec.encodePairingRelayRequest(value: PairingRelayRequest(version: 1, sessionId: sessionId)))
    }
    static func ready(_ bytes: Data) throws -> String {
        let ready = try codec.decodePairingRelayReady(bytes: KMPProtocolBridge.kotlinBytes(bytes))
        guard ready.version == 1 else { throw BrokerPairingError.authentication }
        return ready.sessionId
    }
    static func encode(round: Int, participantId: String, values: [Data]) -> Data {
        KMPProtocolBridge.data(codec.encodePairingPakeFrame(value: PairingPakeFrame(
            round: Int32(round), participantId: participantId, values: values.map(KMPProtocolBridge.kotlinBytes))))
    }
    static func decode(_ bytes: Data) throws -> Frame {
        guard bytes.count <= BrokerPairingLink.maxFrameBytes else { throw BrokerPairingError.authentication }
        let frame = try codec.decodePairingPakeFrame(bytes: KMPProtocolBridge.kotlinBytes(bytes))
        return Frame(round: Int(frame.round), participantId: frame.participantId, values: frame.values.map(KMPProtocolBridge.data))
    }
    static func encrypt(nonce: Data, ciphertext: Data) -> Data {
        KMPProtocolBridge.data(codec.encodePairingEncryptedCard(value: PairingEncryptedCard(
            nonce: KMPProtocolBridge.kotlinBytes(nonce), ciphertext: KMPProtocolBridge.kotlinBytes(ciphertext))))
    }
    static func decrypt(_ bytes: Data) throws -> (nonce: Data, ciphertext: Data) {
        guard bytes.count <= BrokerPairingLink.maxFrameBytes else { throw BrokerPairingError.authentication }
        let card = try codec.decodePairingEncryptedCard(bytes: KMPProtocolBridge.kotlinBytes(bytes))
        return (KMPProtocolBridge.data(card.nonce), KMPProtocolBridge.data(card.ciphertext))
    }
}
