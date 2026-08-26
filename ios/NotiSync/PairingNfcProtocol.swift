import Foundation

/// Reader-side implementation of the versioned short-APDU protocol served by Android's NotiSync HCE
/// service. iOS never emulates a card: it selects the Android application, exchanges both devices' signed
/// pairing payloads, then hands the peer payload to the existing verifier and confirmation UI.
nonisolated enum PairingNfcWireProtocol {
    static let applicationAidHex = "F04E6F746953796E63"

    static let maximumPayloadBytes = 16 * 1024
    static let maximumExchangeChunkBytes = 0xff - exchangeHeaderBytes

    private static let exchangeHeaderBytes = 7
    private static let discoveryHeaderBytes = 8
    private static let operationDescriptorBytes = 3
    private static let operationResponseMetadataBytes = 7

    private static let discoveryFormatVersion: UInt8 = 1
    private static let pairingOperation: UInt8 = 1
    private static let minimumPairingVersion: UInt8 = 1
    private static let maximumPairingVersion: UInt8 = 1

    private static let instructionSelect: UInt8 = 0xa4
    private static let notiSyncClass: UInt8 = 0x80
    private static let instructionExchangePayload: UInt8 = 0xe0
    private static let instructionNegotiateOperation: UInt8 = 0xe1
    private static let exchangeTransfer: UInt8 = 0
    private static let exchangeCommit: UInt8 = 1
    private static let responseMarker = Data([0x4e, 0x53]) // ASCII "NS"

    struct Command: Equatable, Sendable {
        let instructionClass: UInt8
        let instructionCode: UInt8
        let p1: UInt8
        let p2: UInt8
        let data: Data
        /// `-1` omits Le. Values 1...256 use short Le; 256 is encoded as `00`.
        let expectedResponseLength: Int

        /// The exact short APDU sent to Core NFC. Keeping this encoding here makes protocol parity with the
        /// Android implementation explicit, including its Case 1 commit and Case 4 transfer framing.
        var encoded: Data {
            precondition(data.count <= 0xff)
            precondition(expectedResponseLength == -1 || (1...256).contains(expectedResponseLength))
            var bytes = Data([instructionClass, instructionCode, p1, p2])
            if !data.isEmpty {
                bytes.append(UInt8(data.count))
                bytes.append(data)
            }
            if expectedResponseLength != -1 {
                bytes.append(expectedResponseLength == 256 ? 0 : UInt8(expectedResponseLength))
            }
            return bytes
        }
    }

    struct Response: Equatable, Sendable {
        let data: Data
        let statusWord1: UInt8
        let statusWord2: UInt8
    }

    struct OperationSelection: Equatable, Sendable {
        let payloadSize: Int
        let maximumChunkSize: Int
        let initialPayload: Data
    }

    enum ProtocolError: Error, Equatable {
        case invalidLocalPayload
        case invalidResponse
        case peerNotReady
        case peerRejected(UInt16)
        case pairingUnsupported
        case incompatiblePairingVersion
        case payloadTooLarge
        case incompletePayload
    }

    static var selectApplicationCommand: Command {
        Command(
            instructionClass: 0,
            instructionCode: instructionSelect,
            p1: 0x04,
            p2: 0,
            data: applicationAid,
            expectedResponseLength: 256
        )
    }

    static var commitExchangeCommand: Command {
        Command(
            instructionClass: notiSyncClass,
            instructionCode: instructionExchangePayload,
            p1: exchangeCommit,
            p2: 0,
            data: Data(),
            expectedResponseLength: -1
        )
    }

    static func decodePairingPayload(_ encodedPayload: String) throws -> Data {
        let payload = encodedPayload.trimmingCharacters(in: .whitespacesAndNewlines)
        let maximumEncodedCharacters = (maximumPayloadBytes * 4 + 2) / 3
        guard (2...maximumEncodedCharacters).contains(payload.count),
              payload.utf8.allSatisfy({ byte in
                  (0x30...0x39).contains(byte) || (0x41...0x5a).contains(byte) ||
                      (0x61...0x7a).contains(byte) || byte == 0x2d || byte == 0x5f
              }) else {
            throw ProtocolError.invalidLocalPayload
        }
        var base64 = payload.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64.append("=") }
        guard let decoded = Data(base64Encoded: base64),
              (1...maximumPayloadBytes).contains(decoded.count),
              encodePairingPayload(decoded) == payload else {
            throw ProtocolError.invalidLocalPayload
        }
        return decoded
    }

    static func encodePairingPayload(_ payload: Data) -> String {
        precondition((1...maximumPayloadBytes).contains(payload.count))
        return payload.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func requireSuccessfulResponse(_ response: Response) throws -> Data {
        let status = UInt16(response.statusWord1) << 8 | UInt16(response.statusWord2)
        switch status {
        case 0x9000:
            return response.data
        case 0x6a82:
            throw ProtocolError.peerNotReady
        default:
            throw ProtocolError.peerRejected(status)
        }
    }

    /// Parses discovery and returns the highest mutually supported pairing version.
    static func pairingVersion(from response: Response) throws -> UInt8 {
        let data = try requireSuccessfulResponse(response)
        guard data.count >= discoveryHeaderBytes,
              data.prefix(responseMarker.count) == responseMarker,
              data[2] == discoveryFormatVersion else {
            throw ProtocolError.invalidResponse
        }
        let operationCount = Int(data[7])
        guard data.count == discoveryHeaderBytes + operationCount * operationDescriptorBytes else {
            throw ProtocolError.invalidResponse
        }

        var seenOperations = Set<UInt8>()
        var supportedRange: ClosedRange<UInt8>?
        for index in 0..<operationCount {
            let offset = discoveryHeaderBytes + index * operationDescriptorBytes
            let operation = data[offset]
            let minimumVersion = data[offset + 1]
            let maximumVersion = data[offset + 2]
            guard operation != 0, minimumVersion != 0, minimumVersion <= maximumVersion,
                  seenOperations.insert(operation).inserted else {
                throw ProtocolError.invalidResponse
            }
            if operation == pairingOperation {
                supportedRange = minimumVersion...maximumVersion
            }
        }
        guard let supportedRange else { throw ProtocolError.pairingUnsupported }
        let minimum = max(minimumPairingVersion, supportedRange.lowerBound)
        let maximum = min(maximumPairingVersion, supportedRange.upperBound)
        guard minimum <= maximum else { throw ProtocolError.incompatiblePairingVersion }
        return maximum
    }

    static func negotiatePairingCommand(version: UInt8) throws -> Command {
        guard (minimumPairingVersion...maximumPairingVersion).contains(version) else {
            throw ProtocolError.incompatiblePairingVersion
        }
        return Command(
            instructionClass: notiSyncClass,
            instructionCode: instructionNegotiateOperation,
            p1: pairingOperation,
            p2: version,
            data: Data(),
            expectedResponseLength: 256
        )
    }

    static func parseOperationSelection(_ response: Response, version: UInt8) throws -> OperationSelection {
        let data = try requireSuccessfulResponse(response)
        guard data.count >= operationResponseMetadataBytes,
              data.prefix(responseMarker.count) == responseMarker,
              data[2] == pairingOperation,
              data[3] == version else {
            throw ProtocolError.invalidResponse
        }
        let payloadSize = Int(data[4]) << 8 | Int(data[5])
        guard (1...maximumPayloadBytes).contains(payloadSize) else {
            throw ProtocolError.payloadTooLarge
        }
        let maximumChunkSize = Int(data[6])
        guard (1...maximumExchangeChunkBytes).contains(maximumChunkSize) else {
            throw ProtocolError.invalidResponse
        }
        let initialPayload = data.dropFirst(operationResponseMetadataBytes)
        guard initialPayload.count <= payloadSize, initialPayload.count <= maximumChunkSize else {
            throw ProtocolError.invalidResponse
        }
        return OperationSelection(
            payloadSize: payloadSize,
            maximumChunkSize: maximumChunkSize,
            initialPayload: Data(initialPayload)
        )
    }

    static func exchangePayloadCommand(
        readerPayload: Data,
        readerOffset: Int,
        readerLength: Int,
        requestedPeerOffset: Int,
        requestedPeerLength: Int
    ) throws -> Command {
        guard (1...maximumPayloadBytes).contains(readerPayload.count),
              (0...readerPayload.count).contains(readerOffset),
              (0...maximumExchangeChunkBytes).contains(readerLength),
              readerOffset + readerLength <= readerPayload.count,
              (0...maximumPayloadBytes).contains(requestedPeerOffset),
              (0...maximumExchangeChunkBytes).contains(requestedPeerLength),
              requestedPeerOffset + requestedPeerLength <= maximumPayloadBytes else {
            throw ProtocolError.payloadTooLarge
        }

        var transfer = Data()
        transfer.appendUInt16(readerPayload.count)
        transfer.appendUInt16(readerOffset)
        transfer.appendUInt16(requestedPeerOffset)
        transfer.append(UInt8(requestedPeerLength))
        transfer.append(readerPayload[readerOffset..<(readerOffset + readerLength)])
        return Command(
            instructionClass: notiSyncClass,
            instructionCode: instructionExchangePayload,
            p1: exchangeTransfer,
            p2: 0,
            data: transfer,
            expectedResponseLength: requestedPeerLength > 0 ? requestedPeerLength : -1
        )
    }

    private static var applicationAid: Data {
        Data(stride(from: 0, to: applicationAidHex.count, by: 2).map { offset in
            UInt8(applicationAidHex.dropFirst(offset).prefix(2), radix: 16)!
        })
    }
}

private nonisolated extension Data {
    mutating func appendUInt16(_ value: Int) {
        precondition((0...Int(UInt16.max)).contains(value))
        append(UInt8(value >> 8))
        append(UInt8(value & 0xff))
    }
}
