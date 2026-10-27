import Foundation

// Keep the native wire models independent of unrelated protocol adapters in this harness.
nonisolated struct SshAgentSync: Sendable {}
nonisolated struct HotspotSync: Sendable {}

@main
struct ScreenRelayValidationTests {
    static func main() throws {
        for (bytes, expected) in [(Data(), ""), (Data("f".utf8), "Zg"),
                                  (Data("fo".utf8), "Zm8"), (Data("foo".utf8), "Zm9v"),
                                  (Data([0xfb, 0xff, 0xff]), "-___")] {
            precondition(bytes.base64URLEncoded() == expected)
        }
        // An explicit wire alphabet is the oracle, including the two URL-safe punctuation bytes.
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".utf8)
        for value in UInt8.min...UInt8.max {
            precondition(ScreenRelayJoin.isRelayIdByte(value) == alphabet.contains(value),
                         "Wrong relay alphabet membership for byte \(value)")
        }
        for invalid in ["+", "/", "=", " ", "\n", "é", "Ａ", "١", "–", "😀"] {
            precondition(!invalid.utf8.allSatisfy(ScreenRelayJoin.isRelayIdByte))
        }
        // Every possible input byte appears in every position of a Base64 block. This exercises
        // the actual encoder used by the screen transport, without randomness.
        for value in UInt8.min...UInt8.max {
            let data = Data(repeating: value, count: 24)
            let id = data.base64URLEncoded()
            let reference = data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
            precondition(id == reference)
            precondition(id.utf8.count == 32)
            precondition(id.utf8.allSatisfy(ScreenRelayJoin.isRelayIdByte))
            let join = ScreenRelayJoin(relayId: id, requesterPeerId: "requester", sourcePeerId: "source",
                                       role: .requester, channel: .video, expiresAt: 123456)
            let decoded = try JSONDecoder().decode(ScreenRelayJoin.self, from: JSONEncoder().encode(join))
            precondition(decoded.relayId == id)
        }
        print("Screen relay alphabet, generation, and JSON round-trip checks passed")
    }
}
