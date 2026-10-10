import CryptoKit
import Foundation
import NotiSyncProtocol

// The SSH adapter shares the codec; this is its only dependency on the full crypto stack.
nonisolated enum NSHash {
    static func sha256(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }
}

@main
struct HotspotProtocolBridgeTests {
    static func main() throws {
        let issuedAt: Int64 = 1_000_000
        for action in [HotspotAction.QUERY, .REFRESH, .SET_ENABLED, .STATUS] {
            let snapshot = HotspotSnapshot(state: .ENABLED, ssid: "Bridge test", psk: "test-password",
                                           securityType: 2, wifiTethered: true, hiddenSsid: true)
            let sync = HotspotSync(action: action, hotspotDeviceId: action == .REFRESH ? nil : "provider",
                                   issuedAt: issuedAt, expiresAt: issuedAt + 60_000,
                                   requestId: action == .STATUS ? nil : "request",
                                   enabled: action == .SET_ENABLED ? false : nil,
                                   snapshot: action == .STATUS ? snapshot : nil,
                                   result: action == .STATUS ? .OK : nil)
            let wire = ProtocolCodec.encode(DataSync(kind: .HOTSPOT, hotspot: sync))
            let decoded = try ProtocolCodec.decodeDataSync(wire)
            precondition(decoded.kind == .HOTSPOT)
            let result = decoded.hotspot!
            precondition(result.action == sync.action && result.hotspotDeviceId == sync.hotspotDeviceId)
            precondition(result.issuedAt == sync.issuedAt && result.expiresAt == sync.expiresAt)
            precondition(result.requestId == sync.requestId && result.enabled == sync.enabled)
            precondition(result.snapshot == sync.snapshot && result.result == sync.result)
            precondition(result.isValid(now: issuedAt, envelopeCreatedAt: issuedAt))
        }
        for failure in [HotspotResult.UNAUTHORIZED, .UNSUPPORTED, .UNAVAILABLE, .FAILED, .TIMEOUT, .BUSY, .EXPIRED] {
            let sync = HotspotSync(action: .STATUS, hotspotDeviceId: "provider", issuedAt: issuedAt,
                                   expiresAt: issuedAt + 60_000, requestId: "request", result: failure, platformError: 12)
            let result = try ProtocolCodec.decodeDataSync(ProtocolCodec.encode(DataSync(kind: .HOTSPOT, hotspot: sync)))
            precondition(result.hotspot?.result == failure && result.hotspot?.platformError == 12)
        }
        // Reject a HOTSPOT body smuggled under another kind, even if KMP can decode its fields.
        let malformed = NotiSyncProtocol.DataSync(kind: .profile, asset: nil, profile: nil, trust: nil,
            card: nil, filter: nil, notification: nil, run: nil, screenMirror: nil, openPgpSign: nil, sshAgent: nil,
            hotspot: KMPProtocolBridge.toKmp(HotspotSync(action: .REFRESH, issuedAt: issuedAt,
                expiresAt: issuedAt + 60_000, requestId: "request")))
        let wire = KMPProtocolBridge.data(SwiftProtocolCodec.shared.encodeDataSync(value: malformed))
        do {
            _ = try ProtocolCodec.decodeDataSync(wire)
            preconditionFailure("Mismatched hotspot kind must be rejected")
        } catch is CodecError { }
        for capability in [Capability.HOTSPOT_PROVIDER_V1, .HOTSPOT_CONTROL_V1] {
            precondition(KMPProtocolBridge.kmp(capability).name == capability.rawValue)
        }
        print("Hotspot KMP CBOR bridge round trips and kind validation passed")
    }
}
