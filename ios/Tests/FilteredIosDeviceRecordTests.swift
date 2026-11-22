import Foundation

@main
struct FilteredIosDeviceRecordTests {
    static func main() throws {
        func observation(_ name: String?, posted: Int64, received: Int64, origin: String? = "phone") -> FilteredIosDeviceRecord {
            FilteredIosDeviceRecord(peerClientId: "bridge", originDeviceId: origin, deviceName: name,
                                    postTime: posted, receivedAt: received)!
        }

        // A legacy filter edit could touch the row after the latest notification was received.
        let legacy = Data(#"[{"peerClientId":"bridge","originDeviceId":"phone","deviceName":"Old name","updatedAt":900}]"#.utf8)
        let old = observation("Old name", posted: 10, received: 11)
        let renamed = observation("  New name  ", posted: 20, received: 21)
        let unnamed = observation(" \n", posted: 30, received: 31)
        for history in [[unnamed, renamed, old], [old, renamed, unnamed]] {
            var rows = try JSONDecoder().decode([FilteredIosDeviceRecord].self, from: legacy)
            let originalKey = rows[0].deviceKey
            precondition(rows.mergeDeviceNames(history))
            precondition(rows.count == 1 && rows[0].deviceKey == originalKey && rows[0].deviceName == "New name")
            rows = try JSONDecoder().decode([FilteredIosDeviceRecord].self, from: JSONEncoder().encode(rows))
            precondition(!rows.mergeDeviceNames(history))
            precondition(!rows.mergeDeviceNames([observation("Old name", posted: 10, received: 999)]))
            precondition(!rows.mergeDeviceNames([observation(nil, posted: 50, received: 51)]))
            precondition(rows.mergeDeviceNames([observation("Renamed again", posted: 20, received: 22)]))
            precondition(!rows.mergeDeviceNames([renamed]))
            precondition(rows[0].deviceName == "Renamed again")
        }

        var rows = try JSONDecoder().decode([FilteredIosDeviceRecord].self, from: legacy)
        precondition(!rows.mergeDeviceNames([unnamed]))
        precondition(rows[0].deviceName == "Old name")
        precondition(rows.mergeDeviceNames([observation(nil, posted: 20, received: 21, origin: nil)]))
        precondition(rows.map(\.deviceKey) == ["ios:bridge:phone", "ios:bridge"])
        precondition(rows.mergeDeviceNames([observation("Other phone", posted: 10, received: 11, origin: nil)]))
        precondition(rows.map(\.deviceName) == ["Old name", "Other phone"])
        print("ANCS device-name regression checks passed")
    }
}
