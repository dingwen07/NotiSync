import Foundation

nonisolated struct FilteredIosDeviceRecord: Codable, Hashable, Identifiable, Sendable {
    var peerClientId: String
    var originDeviceId: String?
    var deviceName: String
    var updatedAt: Int64
    // Missing in legacy records, whose updatedAt could also be the time a filter was edited.
    var namePostTime: Int64?

    init?(peerClientId: String, originDeviceId: String?, deviceName: String?, postTime: Int64, receivedAt: Int64) {
        let peer = peerClientId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !peer.isEmpty else { return nil }
        self.peerClientId = peer
        self.originDeviceId = originDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = deviceName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.deviceName = name.isEmpty ? "iOS Device" : name
        namePostTime = name.isEmpty ? nil : postTime
        updatedAt = receivedAt
    }

    var id: String { deviceKey }
    var deviceKey: String {
        Self.key(peerClientId: peerClientId, originDeviceId: originDeviceId) ?? "ios:\(peerClientId)"
    }

    static func key(peerClientId: String, originDeviceId: String?) -> String? {
        let peer = peerClientId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !peer.isEmpty else { return nil }
        if let origin = originDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines), !origin.isEmpty {
            return "ios:\(peer):\(origin)"
        }
        return "ios:\(peer)"
    }
}

extension Array where Element == FilteredIosDeviceRecord {
    /// Notification time survives delayed delivery and Inbox replays. Arrival breaks ties for names
    /// attached to notifications posted at the same instant.
    @discardableResult
    nonisolated mutating func mergeDeviceNames(_ observations: [FilteredIosDeviceRecord]) -> Bool {
        var indexByKey = Dictionary(indices.map { (self[$0].deviceKey, $0) }, uniquingKeysWith: { first, _ in first })
        var changed = false
        for observation in observations {
            let key = observation.deviceKey
            if let index = indexByKey[key] {
                guard let time = observation.namePostTime else { continue }
                let saved = self[index]
                if let previous = saved.namePostTime,
                   (time, observation.updatedAt) <= (previous, saved.updatedAt) { continue }
                self[index] = observation
            } else {
                indexByKey[key] = count
                append(observation)
            }
            changed = true
        }
        return changed
    }
}
