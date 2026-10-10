import Foundation

// Run: xcrun swiftc ios/NotiSyncKit/HotspotProtocol.swift \
//   ios/NotiSync/Hotspot/HotspotControllerState.swift ios/Tests/HotspotControllerTests.swift \
//   -o /tmp/notisync-hotspot-tests && /tmp/notisync-hotspot-tests
@main
struct HotspotControllerTests {
    static func main() throws {
        let now: Int64 = 1_000_000
        let snapshot = HotspotSnapshot(state: .ENABLED, ssid: "Test;Wi\\Fi", psk: "pa:ss;\\word",
                                       securityType: 2, wifiTethered: true, hiddenSsid: true)
        let status = HotspotSync(action: .STATUS, hotspotDeviceId: "provider", issuedAt: now,
                                 expiresAt: now + 60_000, snapshot: snapshot, result: .OK)
        precondition(status.isValid(now: now, envelopeCreatedAt: now))
        precondition(!status.isValid(now: now + 60_000, envelopeCreatedAt: now))
        precondition(!status.isValid(now: now - 30_001, envelopeCreatedAt: now))
        precondition(!status.isValid(now: now, envelopeCreatedAt: now + 30_001))
        precondition(!status.isValid(now: now, envelopeCreatedAt: Int64.max))
        for mutate in [
            { (s: inout HotspotSync) in s.hotspotDeviceId = nil },
            { s in s.result = .UNAUTHORIZED }, // A denial must not carry a password.
            { s in s.result = nil },
            { s in s.enabled = true },
            { s in s.expiresAt = Int64.max },
            { s in s.issuedAt = Int64.min },
            { s in s.requestId = "\n" },
            { s in s.requestId = "a\0b" },
            { s in s.requestId = String(repeating: "x", count: 129) },
            { s in s.snapshot?.ssid = String(repeating: "x", count: 129) },
        ] {
            var invalid = status
            mutate(&invalid)
            precondition(!invalid.isValid(now: now, envelopeCreatedAt: now))
        }
        for action in [HotspotAction.QUERY, .REFRESH] {
            let fanout = HotspotSync(action: action, issuedAt: now, expiresAt: now + 60_000, requestId: "refresh")
            precondition(fanout.isValid(now: now, envelopeCreatedAt: now))
        }
        var set = HotspotSync(action: .SET_ENABLED, hotspotDeviceId: "provider", issuedAt: now,
                              expiresAt: now + 60_000, requestId: "set", enabled: false)
        precondition(set.isValid(now: now, envelopeCreatedAt: now))
        set.enabled = nil
        precondition(!set.isValid(now: now, envelopeCreatedAt: now))

        // An unrelated broadcast updates the view, but cannot finish an explicit QUERY/SET request.
        var remote = RemoteHotspotState(pendingRequest: "query")
        precondition(remote.receive(status))
        precondition(remote.pendingRequest == "query" && !remote.canToggle)
        var reply = status
        reply.issuedAt += 1
        reply.requestId = "query"
        precondition(remote.receive(reply) && remote.pendingRequest == nil && remote.canToggle)
        precondition(!remote.finish(requestId: "query", failure: .TIMEOUT))
        precondition(!remote.receive(status)) // Older status must not roll state back.
        precondition(!remote.receive(reply)) // Duplicate delivery is idempotent.
        precondition(!remote.needsRefresh(now: reply.issuedAt + 119_999))
        precondition(remote.needsRefresh(now: reply.issuedAt + 120_000))
        precondition(RemoteHotspotState().needsRefresh(now: now))
        precondition(!RemoteHotspotState().canToggle)
        remote.pendingRequest = "next"
        precondition(!remote.finish(requestId: "query", failure: .TIMEOUT))
        precondition(remote.finish(requestId: "next", failure: .UNAVAILABLE))
        precondition(!remote.canToggle)
        var denial = status
        denial.snapshot = nil
        denial.result = .UNAUTHORIZED
        denial.issuedAt += 2
        precondition(remote.receive(denial) && !remote.canToggle)

        let saved = SavedHotspot(snapshot: snapshot, updatedAt: now)!
        precondition(saved.canSaveAndConnect)
        precondition(saved.qrPayload == "WIFI:T:WPA;S:Test\\;Wi\\\\Fi;P:pa\\:ss\\;\\\\word;H:true;;")
        precondition(!String(describing: saved).contains(snapshot.psk!))
        precondition(!String(describing: snapshot).contains(snapshot.psk!))
        let restored = try JSONDecoder().decode(SavedHotspot.self, from: JSONEncoder().encode(saved))
        precondition(restored == saved)
        var config = snapshot
        config.ssid = "<unknown ssid>"
        precondition(SavedHotspot(snapshot: config, updatedAt: now) == nil)
        config.ssid = String(repeating: "网", count: 11) // 33 UTF-8 bytes.
        precondition(SavedHotspot(snapshot: config, updatedAt: now) == nil)
        config.ssid = "network"
        config.psk = "short"
        precondition(SavedHotspot(snapshot: config, updatedAt: now) == nil)
        config.securityType = 3
        let sae = SavedHotspot(snapshot: config, updatedAt: now)!
        precondition(sae.qrPayload!.contains("T:SAE;") && !sae.canSaveAndConnect)
        for security in [0, 4, 5] {
            config.securityType = security
            precondition(SavedHotspot(snapshot: config, updatedAt: now) == nil)
            config.psk = nil
            let open = SavedHotspot(snapshot: config, updatedAt: now)!
            precondition(open.qrPayload!.contains("T:nopass;") && !open.qrPayload!.contains("P:"))
            precondition(open.canSaveAndConnect == (security == 0))
            config.psk = "short"
        }
        config.securityType = 99
        precondition(SavedHotspot(snapshot: config, updatedAt: now) == nil)
        print("Hotspot protocol, request state, refresh interval, credentials, and QR tests passed")
    }
}
