import Foundation

private nonisolated struct SharedBrokerConfig: Codable, Sendable {
    var brokerURL: String
}

/// Build-wide constants shared by the app and the Notification Service Extension.
///
/// `appGroup` and keychain groups MUST match the entitlements of BOTH targets. The keychain groups are
/// team-prefixed (`$(AppIdentifierPrefix)` resolves to the team id for this app) so the app and the
/// NSE can share the private material and cached auth state needed while the device is locked.
nonisolated enum NotiSyncConfig {
    static let bundleId = "net.extrawdw.apps.NotiSync"
    static let appGroup = "group.net.extrawdw.apps.NotiSync"
    static let teamPrefix = "475KZ2S33S"
    static let signingKeychainGroup = "\(teamPrefix).\(bundleId)"
    static let keychainGroup = "\(teamPrefix).\(bundleId).shared"

    static let platform = "ios"

    /// `ClientCard.platform` the broker's Experience Mode demo peer advertises (server
    /// `DemoScenarioConfig.platform` / `demo-experience.json`). Identity-signed in the card, so a peer pinned
    /// with this platform is authentically the demo device — used to wall demo peers off from trust broadcast
    /// and to prune them before a new session.
    static let experiencePlatform = "demo-server"

    #if DEBUG
    static let allowsAPNSEnvironmentSelection = true
    static let defaultAPNSEnvironment: RouteEnvironment = .DEVELOPMENT
    static func effectiveAPNSEnvironment(_ value: RouteEnvironment) -> RouteEnvironment { value }
    #else
    static let allowsAPNSEnvironmentSelection = false
    static let defaultAPNSEnvironment: RouteEnvironment = .PRODUCTION
    static func effectiveAPNSEnvironment(_ value: RouteEnvironment) -> RouteEnvironment { .PRODUCTION }
    #endif

    /// Dedicated compact-CBOR NS2 broker. Pre-release field-name-CBOR brokers are intentionally isolated.
    static let defaultBrokerURL = "https://notisync-api-v2.extrawdw.net"

    /// Largest inline APNs payload we advertise in our route claim (base64 envelope chars).
    static let inlinePayloadLimitBytes = 3500

    /// The first operational/HPKE epoch. Persisted in `RotationStore`; rotation advances it from here.
    static let initialEpoch = 1

    /// Heartbeat cadence for re-announcing mutable own-mesh state — our profile, our current key-epoch (in
    /// case the broker lost it), and our notification filters — so peers (and the broker) converge even with
    /// no explicit change. The "due" clock is persisted (`PeriodicAnnounceStore`) so this fires at most once
    /// per interval across cold launches / background wakes, not once per launch; a filter *change* (debounced)
    /// and a device *rename* still announce immediately, independent of this gate.
    static let periodicAnnounceIntervalMs: Int64 = 6 * 60 * 60 * 1000   // 6h

    /// NS2 §7 self key-rotation timing (mint → pre-warm → activate → retire). Mirrors the Android
    /// `RotationManager` constants. Overlap ≥ relay TTL so a retired HPKE keyset is retained long enough to
    /// open in-flight (relayed) notifications.
    enum Rotation {
        static let relayTtlMs: Int64 = 48 * 60 * 60 * 1000          // broker relay store-and-forward TTL (48h)
        static let minOverlapMs: Int64 = relayTtlMs                 // hard floor on the overlap window
        static let leadMs: Int64 = 6 * 60 * 60 * 1000              // pre-warm lead before signing with N+1 (6h)
        static let overlapMs: Int64 = 7 * 24 * 60 * 60 * 1000      // notAfter(N) − notBefore(N+1) (~7d)
        static let graceMs: Int64 = relayTtlMs                      // extra HPKE retention past notAfter before destroy
        static let lifetimeMs: Int64 = 60 * 24 * 60 * 60 * 1000    // fallback expiry for an abandoned device (~60d)
        static let intervalMs: Int64 = 30 * 24 * 60 * 60 * 1000    // cadence to initiate a fresh rotation (~30d)
    }

    /// Broker base URL, mirrored into the App Group container so the broker client and NSE read it without
    /// touching the app's SwiftData store.
    static var brokerURL: String {
        get {
            AppGroupStore.read(SharedBrokerConfig.self, AppGroupStore.Files.brokerConfig)?.brokerURL
                ?? defaultBrokerURL
        }
        set {
            AppGroupStore.write(SharedBrokerConfig(brokerURL: newValue), AppGroupStore.Files.brokerConfig)
        }
    }
}

/// Pairing deep links — the same scheme the Android client emits/accepts, so a QR scans cross-platform.
/// The payload is base64url (URL-safe, no padding) so it needs no percent-encoding.
nonisolated enum PairingLinks {
    static let httpsHost = "notisync.apps.extrawdw.net"
    /// Custom URL scheme the Android client also emits (`notisync://pair?payload=…`). Registered in Info.plist
    /// so a shared pairing link opens the app even without the universal-link associated domain provisioned.
    static let scheme = "notisync"
    static func link(payload: String) -> String { "https://\(httpsHost)/pair?payload=\(payload)" }

    /// True if `url` is a NotiSync pairing link — the custom scheme, or the universal-link host's `/pair` path.
    static func isPairing(_ url: URL) -> Bool {
        if url.scheme?.lowercased() == scheme { return true }
        return url.host?.lowercased() == httpsHost && url.path.hasPrefix("/pair")
    }
}
