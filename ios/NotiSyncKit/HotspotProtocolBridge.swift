import Foundation
import NotiSyncProtocol

extension KMPProtocolBridge {
    nonisolated static func toKmp(_ value: HotspotSync) -> NotiSyncProtocol.HotspotSync {
        NotiSyncProtocol.HotspotSync(
            action: NotiSyncProtocol.HotspotAction.entries.first { $0.name == value.action.rawValue }!,
            hotspotDeviceId: value.hotspotDeviceId.map(clientId),
            issuedAt: value.issuedAt, expiresAt: value.expiresAt, requestId: value.requestId,
            enabled: value.enabled.map { KotlinBoolean(bool: $0) },
            snapshot: value.snapshot.map { snapshot in
                NotiSyncProtocol.HotspotSnapshot(
                    state: NotiSyncProtocol.HotspotState.entries.first { $0.name == snapshot.state.rawValue }!,
                    ssid: snapshot.ssid, psk: snapshot.psk,
                    securityType: snapshot.securityType.map { KotlinInt(int: Int32($0)) },
                    wifiTethered: snapshot.wifiTethered, hiddenSsid: snapshot.hiddenSsid
                )
            },
            result: value.result.map { result in
                NotiSyncProtocol.HotspotResult.entries.first { $0.name == result.rawValue }!
            },
            platformError: value.platformError.map { KotlinInt(int: Int32($0)) }
        )
    }

    nonisolated static func fromKmp(_ value: NotiSyncProtocol.HotspotSync) throws -> HotspotSync {
        guard let action = HotspotAction(rawValue: value.action.name) else {
            throw CodecError.typeMismatch("hotspot.action")
        }
        let snapshot: HotspotSnapshot? = try value.snapshot.map {
            guard let state = HotspotState(rawValue: $0.state.name) else {
                throw CodecError.typeMismatch("hotspot.snapshot.state")
            }
            return HotspotSnapshot(state: state, ssid: $0.ssid, psk: $0.psk,
                                   securityType: $0.securityType?.intValue,
                                   wifiTethered: $0.wifiTethered, hiddenSsid: $0.hiddenSsid)
        }
        let result: HotspotResult? = try value.result.map {
            guard let result = HotspotResult(rawValue: $0.name) else {
                throw CodecError.typeMismatch("hotspot.result")
            }
            return result
        }
        return HotspotSync(action: action, hotspotDeviceId: value.hotspotDeviceId.map(string),
                           issuedAt: value.issuedAt, expiresAt: value.expiresAt, requestId: value.requestId,
                           enabled: value.enabled?.boolValue, snapshot: snapshot, result: result,
                           platformError: value.platformError?.intValue)
    }
}
