import Foundation

nonisolated enum SshWireError: Error, LocalizedError, Sendable, Equatable {
    case truncated
    case invalidLength
    case invalidUTF8
    case invalidMPInt
    case trailingData
    case outputTooLarge
    case invalidDER

    var errorDescription: String? {
        switch self {
        case .truncated: "SSH data is truncated."
        case .invalidLength: "An SSH field is outside the allowed bounds."
        case .invalidUTF8: "An SSH text field is not valid UTF-8."
        case .invalidMPInt: "An SSH integer is not canonically encoded."
        case .trailingData: "SSH data contains unexpected trailing bytes."
        case .outputTooLarge: "Encoded SSH data is outside the allowed bounds."
        case .invalidDER: "The ECDSA signature is not valid canonical DER."
        }
    }
}

/// Strict, bounded SSH uint32/string/mpint reader. Positive mpints are returned without their optional
/// sign-protecting zero byte; zero is returned as empty data.
nonisolated struct SshWireReader {
    private let bytes: Data
    private(set) var offset = 0
    let maximumStringLength: Int

    init(_ bytes: Data, maximumStringLength: Int? = nil) {
        self.bytes = bytes
        self.maximumStringLength = maximumStringLength ?? bytes.count
    }

    var remaining: Int { bytes.count - offset }

    mutating func readByte() throws -> UInt8 {
        try requireAvailable(1)
        defer { offset += 1 }
        return bytes[bytes.startIndex + offset]
    }

    mutating func readUInt32() throws -> UInt32 {
        try requireAvailable(4)
        let start = bytes.startIndex + offset
        offset += 4
        return (UInt32(bytes[start]) << 24) |
            (UInt32(bytes[start + 1]) << 16) |
            (UInt32(bytes[start + 2]) << 8) |
            UInt32(bytes[start + 3])
    }

    mutating func readString(maximumLength: Int? = nil) throws -> Data {
        let length = Int(try readUInt32())
        let bound = maximumLength ?? maximumStringLength
        guard length <= bound, length <= remaining else { throw SshWireError.invalidLength }
        let start = bytes.startIndex + offset
        offset += length
        return Data(bytes[start..<(start + length)])
    }

    mutating func readUTF8(maximumLength: Int? = nil) throws -> String {
        let encoded = try readString(maximumLength: maximumLength)
        guard let decoded = String(data: encoded, encoding: .utf8), Data(decoded.utf8) == encoded else {
            throw SshWireError.invalidUTF8
        }
        return decoded
    }

    mutating func readPositiveMPInt(maximumLength: Int? = nil) throws -> Data {
        let encoded = try readString(maximumLength: maximumLength)
        guard !encoded.isEmpty else { return Data() }
        let first = encoded[encoded.startIndex]
        guard first & 0x80 == 0 else { throw SshWireError.invalidMPInt }
        if first == 0 {
            guard encoded.count > 1, encoded[encoded.startIndex + 1] & 0x80 != 0 else {
                throw SshWireError.invalidMPInt
            }
            return Data(encoded.dropFirst())
        }
        return encoded
    }

    mutating func readRemaining() -> Data {
        let start = bytes.startIndex + offset
        offset = bytes.count
        return Data(bytes[start..<bytes.endIndex])
    }

    func requireEnd() throws {
        guard remaining == 0 else { throw SshWireError.trailingData }
    }

    private func requireAvailable(_ count: Int) throws {
        guard count >= 0, remaining >= count else { throw SshWireError.truncated }
    }
}

nonisolated struct SshWireWriter {
    private(set) var data = Data()
    let maximumSize: Int

    init(maximumSize: Int = 1024 * 1024) {
        self.maximumSize = maximumSize
    }

    mutating func writeByte(_ value: UInt8) throws {
        try reserve(1)
        data.append(value)
    }

    mutating func writeUInt32(_ value: UInt32) throws {
        try reserve(4)
        data.append(UInt8(truncatingIfNeeded: value >> 24))
        data.append(UInt8(truncatingIfNeeded: value >> 16))
        data.append(UInt8(truncatingIfNeeded: value >> 8))
        data.append(UInt8(truncatingIfNeeded: value))
    }

    mutating func writeString(_ value: Data) throws {
        guard value.count <= Int(UInt32.max) else { throw SshWireError.outputTooLarge }
        try writeUInt32(UInt32(value.count))
        try writeRaw(value)
    }

    mutating func writeUTF8(_ value: String) throws {
        try writeString(Data(value.utf8))
    }

    /// Writes a positive SSH mpint from unsigned big-endian bytes. Leading zeroes are normalized away.
    mutating func writePositiveMPInt(_ unsignedValue: Data) throws {
        let normalized = unsignedValue.drop { $0 == 0 }
        guard let first = normalized.first else {
            try writeString(Data())
            return
        }
        if first & 0x80 != 0 {
            var encoded = Data([0])
            encoded.append(contentsOf: normalized)
            try writeString(encoded)
        } else {
            try writeString(Data(normalized))
        }
    }

    mutating func writeRaw(_ value: Data) throws {
        try reserve(value.count)
        data.append(value)
    }

    private func reserve(_ count: Int) throws {
        guard count >= 0, data.count <= maximumSize - count else { throw SshWireError.outputTooLarge }
    }
}

/// OpenSSH represents an ECDSA signature as an SSH string containing two positive mpints, not as ASN.1 DER.
nonisolated enum SshECDSASignatureCodec {
    static func derToSSH(_ der: Data) throws -> Data {
        var reader = DERReader(der)
        let sequence = try reader.readElement(tag: 0x30)
        try reader.requireEnd()
        var integers = DERReader(sequence)
        let r = try integers.readPositiveInteger()
        let s = try integers.readPositiveInteger()
        try integers.requireEnd()
        guard r.count <= 32, s.count <= 32, !r.allSatisfy({ $0 == 0 }), !s.allSatisfy({ $0 == 0 }) else {
            throw SshWireError.invalidDER
        }
        var writer = SshWireWriter(maximumSize: 128)
        try writer.writePositiveMPInt(r)
        try writer.writePositiveMPInt(s)
        return writer.data
    }

    private struct DERReader {
        private let bytes: Data
        private var offset = 0

        init(_ bytes: Data) { self.bytes = bytes }

        mutating func readElement(tag expectedTag: UInt8) throws -> Data {
            guard offset < bytes.count, bytes[bytes.startIndex + offset] == expectedTag else {
                throw SshWireError.invalidDER
            }
            offset += 1
            let length = try readLength()
            guard length <= bytes.count - offset else { throw SshWireError.invalidDER }
            let start = bytes.startIndex + offset
            offset += length
            return Data(bytes[start..<(start + length)])
        }

        mutating func readPositiveInteger() throws -> Data {
            let encoded = try readElement(tag: 0x02)
            guard !encoded.isEmpty else { throw SshWireError.invalidDER }
            let first = encoded[encoded.startIndex]
            guard first & 0x80 == 0 else { throw SshWireError.invalidDER }
            if first == 0 {
                guard encoded.count > 1, encoded[encoded.startIndex + 1] & 0x80 != 0 else {
                    throw SshWireError.invalidDER
                }
                return Data(encoded.dropFirst())
            }
            return encoded
        }

        mutating func requireEnd() throws {
            guard offset == bytes.count else { throw SshWireError.invalidDER }
        }

        private mutating func readLength() throws -> Int {
            guard offset < bytes.count else { throw SshWireError.invalidDER }
            let first = bytes[bytes.startIndex + offset]
            offset += 1
            if first & 0x80 == 0 { return Int(first) }
            let count = Int(first & 0x7f)
            guard count == 1 || count == 2, count <= bytes.count - offset else {
                throw SshWireError.invalidDER
            }
            var length = 0
            for _ in 0..<count {
                let byte = bytes[bytes.startIndex + offset]
                offset += 1
                if length == 0, byte == 0 { throw SshWireError.invalidDER }
                length = (length << 8) | Int(byte)
            }
            guard length >= 0x80 else { throw SshWireError.invalidDER }
            return length
        }
    }
}
