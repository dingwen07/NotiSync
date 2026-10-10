import Foundation

nonisolated struct RemoteHotspotState: Sendable {
    var status: HotspotSync?
    var pendingRequest: String?
    var failure: HotspotResult?

    var canToggle: Bool {
        pendingRequest == nil && failure == nil && status?.result == .OK
            && (status?.snapshot?.state == .ENABLED || status?.snapshot?.state == .DISABLED)
    }

    func needsRefresh(now: Int64) -> Bool {
        guard let issuedAt = status?.issuedAt else { return true }
        return now < issuedAt || now - issuedAt >= 120_000
    }

    @discardableResult
    mutating func receive(_ sync: HotspotSync) -> Bool {
        guard sync.issuedAt > (status?.issuedAt ?? 0) else { return false }
        status = sync
        failure = nil
        if pendingRequest == sync.requestId { pendingRequest = nil }
        return true
    }

    @discardableResult
    mutating func finish(requestId: String, failure: HotspotResult) -> Bool {
        guard pendingRequest == requestId else { return false }
        pendingRequest = nil
        self.failure = failure
        return true
    }
}

/// Only the last usable configuration is persisted; live on/off state is never restored as current.
nonisolated struct SavedHotspot: Codable, Equatable, Sendable, CustomStringConvertible {
    var ssid: String
    var psk: String?
    var securityType: Int
    var hiddenSsid: Bool
    var updatedAt: Int64

    init?(snapshot: HotspotSnapshot, updatedAt: Int64) {
        guard let ssid = snapshot.ssid, let securityType = snapshot.securityType else { return nil }
        self.ssid = ssid
        self.psk = snapshot.psk
        self.securityType = securityType
        self.hiddenSsid = snapshot.hiddenSsid
        self.updatedAt = updatedAt
        guard usable else { return nil }
    }

    var usable: Bool {
        guard !ssid.isEmpty, ssid != "<unknown ssid>", ssid.utf8.count <= 32,
              !ssid.contains("\0") else { return false }
        switch securityType {
        case 0, 4, 5: return psk == nil
        case 1, 2: return validPassphrase(minimum: 8)
        case 3: return validPassphrase(minimum: 1)
        default: return false
        }
    }

    private func validPassphrase(minimum: Int) -> Bool {
        guard let psk else { return false }
        return (minimum...63).contains(psk.utf8.count)
            && psk.unicodeScalars.allSatisfy { (32...126).contains($0.value) }
    }

    // NEHotspotConfiguration documents open and WPA/WPA2 personal networks. Do not turn OWE
    // into an open configuration, or assume this API can configure WPA3-only networks.
    var canSaveAndConnect: Bool { usable && [0, 1, 2].contains(securityType) }

    var qrPayload: String? {
        guard usable else { return nil }
        let auth = securityType == 3 ? "SAE" : ([1, 2].contains(securityType) ? "WPA" : "nopass")
        let password = psk.map { "P:\(Self.escapeQR($0));" } ?? ""
        return "WIFI:T:\(auth);S:\(Self.escapeQR(ssid));\(password)H:\(hiddenSsid);;"
    }

    private static func escapeQR(_ value: String) -> String {
        value.reduce(into: "") { result, character in
            if "\\;,:\"".contains(character) { result.append("\\") }
            result.append(character)
        }
    }

    var description: String { "SavedHotspot(securityType: \(securityType), credentials: <redacted>)" }
}
