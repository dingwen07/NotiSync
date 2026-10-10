import Foundation

nonisolated enum HotspotAction: String, Sendable { case QUERY, SET_ENABLED, STATUS, REFRESH }
nonisolated enum HotspotState: String, Sendable {
    case UNKNOWN, DISABLED, ENABLING, ENABLED, DISABLING, FAILED
}
nonisolated enum HotspotResult: String, Sendable {
    case OK, UNAUTHORIZED, UNSUPPORTED, UNAVAILABLE, FAILED, TIMEOUT, BUSY, EXPIRED
}

nonisolated struct HotspotSnapshot: Sendable, Equatable, CustomStringConvertible {
    var state: HotspotState
    var ssid: String?
    var psk: String?
    var securityType: Int?
    var wifiTethered = false
    var hiddenSsid = false

    var description: String { "HotspotSnapshot(state: \(state), credentials: <redacted>)" }
}

nonisolated struct HotspotSync: Sendable {
    var action: HotspotAction
    var hotspotDeviceId: String?
    var issuedAt: Int64
    var expiresAt: Int64
    var requestId: String?
    var enabled: Bool?
    var snapshot: HotspotSnapshot?
    var result: HotspotResult?
    var platformError: Int?

    /// Mirrors protocol/Hotspot.kt. Validate timestamps before subtracting untrusted integers.
    func isValid(now: Int64, envelopeCreatedAt: Int64) -> Bool {
        guard issuedAt > 0, issuedAt <= now + 30_000, expiresAt > now,
              expiresAt > issuedAt, expiresAt - issuedAt <= 60_000,
              envelopeCreatedAt > 0,
              abs(envelopeCreatedAt - issuedAt) <= 30_000 else { return false }
        if let requestId {
            guard !requestId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  requestId.utf16.count <= 128,
                  !requestId.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
            else { return false }
        }
        guard (snapshot?.ssid?.utf16.count ?? 0) <= 128,
              (snapshot?.psk?.utf16.count ?? 0) <= 128 else { return false }
        switch action {
        case .QUERY, .REFRESH:
            return requestId != nil && enabled == nil && snapshot == nil && result == nil && platformError == nil
        case .SET_ENABLED:
            return hotspotDeviceId != nil && requestId != nil && enabled != nil && snapshot == nil
                && result == nil && platformError == nil
        case .STATUS:
            return hotspotDeviceId != nil && enabled == nil && result != nil
                && (snapshot == nil || result == .OK)
        }
    }
}
