import Darwin
import Foundation

/// JSON files in the shared App Group container — the only state the app and the NSE both touch:
/// shared runtime config, the trust roster (sender keys/epochs the NSE needs to verify+open), the
/// self record (clientId + current epoch), mirror display preferences, the mirror identifier map, and the
/// dismissal reconciliation sets.
nonisolated enum AppGroupStore {
    private static let fallbackLock = NSLock()
    private static var cachedLockDirectory: URL?

    static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: NotiSyncConfig.appGroup)
    }

    private static func url(_ name: String) -> URL? { containerURL?.appendingPathComponent(name) }
    private static func lockURL(_ name: String) -> URL? {
        guard let containerURL else { return nil }
        let dir: URL
        fallbackLock.lock()
        if let cachedLockDirectory {
            dir = cachedLockDirectory
        } else {
            let created = containerURL.appendingPathComponent("locks", isDirectory: true)
            do {
                try FileManager.default.createDirectory(at: created, withIntermediateDirectories: true)
                cachedLockDirectory = created
                dir = created
            } catch {
                fallbackLock.unlock()
                return nil
            }
        }
        fallbackLock.unlock()
        let safeName = name.map { $0.isLetter || $0.isNumber ? String($0) : "_" }.joined()
        return dir.appendingPathComponent("\(safeName).lock")
    }

    private static func withFallbackLock<T>(_ body: () -> T) -> T {
        fallbackLock.lock()
        defer { fallbackLock.unlock() }
        return body()
    }

    static func withLock<T>(_ name: String, _ body: () -> T) -> T {
        guard let lockURL = lockURL(name) else { return withFallbackLock(body) }
        let fd = open(lockURL.path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { return withFallbackLock(body) }
        guard flock(fd, LOCK_EX) == 0 else {
            close(fd)
            return withFallbackLock(body)
        }
        defer {
            flock(fd, LOCK_UN)
            close(fd)
        }
        return body()
    }

    static func readData(_ name: String) -> Data? {
        guard let url = url(name) else { return nil }
        return try? Data(contentsOf: url)
    }

    @discardableResult
    static func writeData(_ data: Data, _ name: String) -> Bool {
        guard let url = url(name) else { return false }
        return (try? data.write(to: url, options: .atomic)) != nil
    }

    static func read<T: Decodable>(_ type: T.Type, _ name: String) -> T? {
        guard let data = readData(name) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    @discardableResult
    static func write<T: Encodable>(_ value: T, _ name: String) -> Bool {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(value) else { return false }
        return writeData(data, name)
    }

    enum Files {
        static let brokerConfig = "broker-config.json"
        static let sequence = "sequence.json"
        static let selfRecord = "self.json"
        static let trust = "trust.json"
        static let mirrorMap = "mirror-map.json"
        static let shownSet = "shown.json"
        static let pendingInbox = "pending-inbox.json"
        static let dedup = "dedup.json"
        static let rotation = "rotation.json"
        static let cards = "cards.json"
        static let notificationFilters = "notification-filters.json"
        /// Peers this device last announced a non-empty notification filter to (so a now-empty snapshot
        /// can send one clearing FILTER instead of silently leaving a stale filter on the peer).
        static let filterAnnounce = "notification-filter-announce.json"
    }
}

/// User-maintained mirror display rules shared by the app and Notification Service Extension. These rules
/// affect presentation only: matching notifications are still decoded and handed to the app Inbox.
nonisolated struct FilteredIosDeviceRecord: Codable, Hashable, Identifiable, Sendable {
    var peerClientId: String
    var originDeviceId: String?
    var deviceName: String
    var updatedAt: Int64

    var id: String { deviceKey }
    var deviceKey: String {
        NotificationFilterStore.iosDeviceKey(peerClientId: peerClientId, originDeviceId: originDeviceId) ?? "ios:\(peerClientId)"
    }
}

nonisolated struct NotificationFilterPreferences: Codable, Sendable {
    var filteredAndroidLocalPeerIds: Set<String> = []
    var iosDevices: [FilteredIosDeviceRecord] = []
    var filteredIosDeviceKeys: Set<String> = []
    var filteredAppIdsByDeviceKey: [String: Set<String>] = [:]
    var filteredChannelIdsByDeviceAppKey: [String: Set<String>] = [:]

    init(
        filteredAndroidLocalPeerIds: Set<String> = [],
        iosDevices: [FilteredIosDeviceRecord] = [],
        filteredIosDeviceKeys: Set<String> = [],
        filteredAppIdsByDeviceKey: [String: Set<String>] = [:],
        filteredChannelIdsByDeviceAppKey: [String: Set<String>] = [:]
    ) {
        self.filteredAndroidLocalPeerIds = filteredAndroidLocalPeerIds
        self.iosDevices = iosDevices
        self.filteredIosDeviceKeys = filteredIosDeviceKeys
        self.filteredAppIdsByDeviceKey = filteredAppIdsByDeviceKey
        self.filteredChannelIdsByDeviceAppKey = filteredChannelIdsByDeviceAppKey
    }

}

nonisolated enum NotificationFilterStore {
    private static let fileName = AppGroupStore.Files.notificationFilters

    static func preferences() -> NotificationFilterPreferences {
        AppGroupStore.withLock(fileName) {
            if let prefs = AppGroupStore.read(NotificationFilterPreferences.self, fileName) { return prefs }
            return NotificationFilterPreferences()
        }
    }

    static func androidLocalNotificationsEnabled(for clientId: String) -> Bool {
        guard !clientId.isEmpty else { return true }
        return !preferences().filteredAndroidLocalPeerIds.contains(clientId)
    }

    static func setAndroidLocalNotificationsEnabled(_ enabled: Bool, for clientId: String) {
        let normalized = clientId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return }
        AppGroupStore.withLock(fileName) {
            var prefs = preferencesUnlocked()
            if enabled {
                prefs.filteredAndroidLocalPeerIds.remove(normalized)
            } else {
                prefs.filteredAndroidLocalPeerIds.insert(normalized)
            }
            AppGroupStore.write(prefs, fileName)
        }
    }

    static func iosDevices() -> [FilteredIosDeviceRecord] {
        preferences().iosDevices.sorted {
            $0.deviceName.localizedCaseInsensitiveCompare($1.deviceName) == .orderedAscending
        }
    }

    static func recordIosDevice(peerClientId: String, originDeviceId: String?, deviceName: String?) {
        let peerId = peerClientId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !peerId.isEmpty, let deviceKey = iosDeviceKey(peerClientId: peerId, originDeviceId: originDeviceId) else { return }
        let name = cleanText(deviceName) ?? "iOS Device"
        let originId = cleanText(originDeviceId)
        AppGroupStore.withLock(fileName) {
            var prefs = preferencesUnlocked()
            if let index = prefs.iosDevices.firstIndex(where: { $0.deviceKey == deviceKey }) {
                prefs.iosDevices[index].deviceName = name
                prefs.iosDevices[index].updatedAt = nowMillis()
            } else {
                prefs.iosDevices.append(FilteredIosDeviceRecord(
                    peerClientId: peerId,
                    originDeviceId: originId,
                    deviceName: name,
                    updatedAt: nowMillis()))
            }
            AppGroupStore.write(prefs, fileName)
        }
    }

    static func removeIosDevice(deviceKey: String) {
        guard !deviceKey.isEmpty else { return }
        AppGroupStore.withLock(fileName) {
            var prefs = preferencesUnlocked()
            prefs.iosDevices.removeAll { $0.deviceKey == deviceKey }
            prefs.filteredIosDeviceKeys.remove(deviceKey)
            prefs.filteredAppIdsByDeviceKey.removeValue(forKey: deviceKey)
            prefs.filteredChannelIdsByDeviceAppKey = prefs.filteredChannelIdsByDeviceAppKey.filter {
                !$0.key.hasPrefix("\(deviceKey)\u{1f}")
            }
            AppGroupStore.write(prefs, fileName)
        }
    }

    static func iosNotificationsEnabled(deviceKey: String) -> Bool {
        guard !deviceKey.isEmpty else { return true }
        return !preferences().filteredIosDeviceKeys.contains(deviceKey)
    }

    static func setIosNotificationsEnabled(
        _ enabled: Bool,
        deviceKey: String,
        peerClientId: String,
        originDeviceId: String?,
        deviceName: String?
    ) {
        let peerId = peerClientId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !peerId.isEmpty, !deviceKey.isEmpty else { return }
        let originId = cleanText(originDeviceId)
        AppGroupStore.withLock(fileName) {
            var prefs = preferencesUnlocked()
            if let name = cleanText(deviceName) {
                if let index = prefs.iosDevices.firstIndex(where: { $0.deviceKey == deviceKey }) {
                    prefs.iosDevices[index].deviceName = name
                    prefs.iosDevices[index].updatedAt = nowMillis()
                } else {
                    prefs.iosDevices.append(FilteredIosDeviceRecord(
                        peerClientId: peerId,
                        originDeviceId: originId,
                        deviceName: name,
                        updatedAt: nowMillis()))
                }
            }
            if enabled {
                prefs.filteredIosDeviceKeys.remove(deviceKey)
            } else {
                prefs.filteredIosDeviceKeys.insert(deviceKey)
            }
            AppGroupStore.write(prefs, fileName)
        }
    }

    static func filteredAppIds(deviceKey: String) -> Set<String> {
        preferences().filteredAppIdsByDeviceKey[deviceKey] ?? []
    }

    static func appNotificationsEnabled(deviceKey: String, appId: String) -> Bool {
        let normalized = appId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !deviceKey.isEmpty, !normalized.isEmpty else { return true }
        return !(preferences().filteredAppIdsByDeviceKey[deviceKey]?.contains(normalized) ?? false)
    }

    static func setAppNotificationsEnabled(_ enabled: Bool, deviceKey: String, appId: String) {
        let normalized = appId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !deviceKey.isEmpty, !normalized.isEmpty else { return }
        AppGroupStore.withLock(fileName) {
            var prefs = preferencesUnlocked()
            var filtered = prefs.filteredAppIdsByDeviceKey[deviceKey] ?? []
            if enabled {
                filtered.remove(normalized)
            } else {
                filtered.insert(normalized)
            }
            prefs.filteredAppIdsByDeviceKey[deviceKey] = filtered.isEmpty ? nil : filtered
            AppGroupStore.write(prefs, fileName)
        }
    }

    static func filteredChannelIds(deviceKey: String, appId: String) -> Set<String> {
        preferences().filteredChannelIdsByDeviceAppKey[deviceAppKey(deviceKey: deviceKey, appId: appId)] ?? []
    }

    static func appIdsWithFilteredChannels(deviceKey: String) -> Set<String> {
        let prefix = "\(deviceKey)\u{1f}"
        return Set(preferences().filteredChannelIdsByDeviceAppKey.keys.compactMap { key in
            guard key.hasPrefix(prefix) else { return nil }
            return String(key.dropFirst(prefix.count))
        })
    }

    static func channelNotificationsEnabled(deviceKey: String, appId: String, channelId: String) -> Bool {
        let normalized = channelId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !deviceKey.isEmpty, !appId.isEmpty, !normalized.isEmpty else { return true }
        return !filteredChannelIds(deviceKey: deviceKey, appId: appId).contains(normalized)
    }

    static func setChannelNotificationsEnabled(_ enabled: Bool, deviceKey: String, appId: String, channelId: String) {
        let normalized = channelId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !deviceKey.isEmpty, !appId.isEmpty, !normalized.isEmpty else { return }
        AppGroupStore.withLock(fileName) {
            var prefs = preferencesUnlocked()
            let key = deviceAppKey(deviceKey: deviceKey, appId: appId)
            var filtered = prefs.filteredChannelIdsByDeviceAppKey[key] ?? []
            if enabled {
                filtered.remove(normalized)
            } else {
                filtered.insert(normalized)
            }
            prefs.filteredChannelIdsByDeviceAppKey[key] = filtered.isEmpty ? nil : filtered
            AppGroupStore.write(prefs, fileName)
        }
    }

    static func shouldFilterNotification(_ n: CapturedNotification) -> Bool {
        switch n.originPlatform {
        case .ANDROID_LOCAL:
            guard androidLocalNotificationsEnabled(for: n.sourceClientId) else { return true }
            guard let appId = appIdentifier(packageName: n.packageName, iosBundleId: n.iosBundleId),
                  let deviceKey = androidDeviceKey(n.sourceClientId) else { return false }
            if !appNotificationsEnabled(deviceKey: deviceKey, appId: appId) { return true }
            guard let channelId = cleanText(n.channelId), !channelId.isEmpty else { return false }
            return !channelNotificationsEnabled(deviceKey: deviceKey, appId: appId, channelId: channelId)
        case .IOS_ANCS:
            recordIosDevice(peerClientId: n.sourceClientId, originDeviceId: n.originDeviceId, deviceName: n.originDeviceName)
            guard let deviceKey = iosDeviceKey(peerClientId: n.sourceClientId, originDeviceId: n.originDeviceId) else { return false }
            guard iosNotificationsEnabled(deviceKey: deviceKey) else { return true }
            guard let appId = appIdentifier(packageName: n.packageName, iosBundleId: n.iosBundleId) else { return false }
            return !appNotificationsEnabled(deviceKey: deviceKey, appId: appId)
        }
    }

    static func shouldFilterNotification(
        originPlatform: String?,
        sourceClientId: String,
        originDeviceName: String?,
        originDeviceId: String?,
        packageName: String?,
        iosBundleId: String?,
        channelId: String?
    ) -> Bool {
        let platform = originPlatform.flatMap(OriginPlatform.init(rawValue:)) ?? .ANDROID_LOCAL
        switch platform {
        case .ANDROID_LOCAL:
            guard androidLocalNotificationsEnabled(for: sourceClientId) else { return true }
            guard let appId = appIdentifier(packageName: packageName, iosBundleId: iosBundleId),
                  let deviceKey = androidDeviceKey(sourceClientId) else { return false }
            if !appNotificationsEnabled(deviceKey: deviceKey, appId: appId) { return true }
            guard let channelId = cleanText(channelId), !channelId.isEmpty else { return false }
            return !channelNotificationsEnabled(deviceKey: deviceKey, appId: appId, channelId: channelId)
        case .IOS_ANCS:
            recordIosDevice(peerClientId: sourceClientId, originDeviceId: originDeviceId, deviceName: originDeviceName)
            guard let deviceKey = iosDeviceKey(peerClientId: sourceClientId, originDeviceId: originDeviceId) else { return false }
            guard iosNotificationsEnabled(deviceKey: deviceKey) else { return true }
            guard let appId = appIdentifier(packageName: packageName, iosBundleId: iosBundleId) else { return false }
            return !appNotificationsEnabled(deviceKey: deviceKey, appId: appId)
        }
    }

    static func filteredIosDeviceName(from originDeviceName: String?) -> String? {
        guard let name = cleanText(originDeviceName), !name.isEmpty else { return nil }
        return name
    }

    static func androidDeviceKey(_ clientId: String) -> String? {
        let normalized = clientId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return nil }
        return "android:\(normalized)"
    }

    static func iosDeviceKey(peerClientId: String, originDeviceId: String?) -> String? {
        let peerId = peerClientId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !peerId.isEmpty else { return nil }
        if let originId = cleanText(originDeviceId), !originId.isEmpty {
            return "ios:\(peerId):\(originId)"
        }
        return "ios:\(peerId)"
    }

    static func appIdentifier(packageName: String?, iosBundleId: String?) -> String? {
        if let bundleId = cleanText(iosBundleId), !bundleId.isEmpty { return bundleId }
        if let packageName = cleanText(packageName), !packageName.isEmpty { return packageName }
        return nil
    }

    // MARK: Filter export (DATA_SYNC FILTER) — describe what each *source* peer should suppress for us.

    /// Build the per-source-peer filter snapshots to announce over `DataSyncKind.FILTER`, keyed by the peer
    /// client id that is the notification *source* — its own Android-local captures and/or its ANCS bridge.
    /// The local store keys an iOS-origin filter by `(peerClientId, originDeviceId)`, but the wire snapshot
    /// DROPS `originDeviceId`: each ANCS-bridged app is a master switch per trusted (bridging) device, so two
    /// bridged iPhones under the same peer collapse to one rule (deduped here).
    static func filterRulesByPeer() -> [String: [NotificationFilterRule]] {
        let prefs = preferences()
        var byPeer: [String: [NotificationFilterRule]] = [:]
        var seen: [String: Set<String>] = [:]   // peerId → rule signatures (dedup collapsed iOS rules)

        func add(_ rawPeerId: String, _ rule: NotificationFilterRule) {
            let peerId = rawPeerId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !peerId.isEmpty else { return }
            let sig = "\(rule.originPlatform.rawValue)\u{1f}\(rule.appId ?? "")\u{1f}\(rule.channelId ?? "")"
            if seen[peerId]?.contains(sig) == true { return }
            seen[peerId, default: []].insert(sig)
            byPeer[peerId, default: []].append(rule)
        }

        // Android-local device-level master switch — keyed by the source client id.
        for peerId in prefs.filteredAndroidLocalPeerIds {
            add(peerId, NotificationFilterRule(originPlatform: .ANDROID_LOCAL, appId: nil, channelId: nil))
        }
        // iOS (ANCS) device-level master switch — collapse originDeviceId to the bridging peer.
        for deviceKey in prefs.filteredIosDeviceKeys {
            guard let parsed = parseFilterDeviceKey(deviceKey), parsed.platform == .IOS_ANCS else { continue }
            add(parsed.peerId, NotificationFilterRule(originPlatform: .IOS_ANCS, appId: nil, channelId: nil))
        }
        // App-level (both origins): key is "android:<peer>" or "ios:<peer>[:<originId>]".
        for (deviceKey, appIds) in prefs.filteredAppIdsByDeviceKey {
            guard let parsed = parseFilterDeviceKey(deviceKey) else { continue }
            for appId in appIds where !appId.isEmpty {
                add(parsed.peerId, NotificationFilterRule(originPlatform: parsed.platform, appId: appId, channelId: nil))
            }
        }
        // Channel-level (Android only): key is "<deviceKey>\u{1f}<appId>".
        for (deviceAppKey, channelIds) in prefs.filteredChannelIdsByDeviceAppKey {
            let parts = deviceAppKey.components(separatedBy: "\u{1f}")
            guard parts.count == 2, let parsed = parseFilterDeviceKey(parts[0]),
                  parsed.platform == .ANDROID_LOCAL, !parts[1].isEmpty else { continue }
            for channelId in channelIds where !channelId.isEmpty {
                add(parsed.peerId, NotificationFilterRule(originPlatform: .ANDROID_LOCAL, appId: parts[1], channelId: channelId))
            }
        }
        return byPeer
    }

    /// Parse a stored deviceKey into (origin, source-peer client id), DROPPING the iOS originDeviceId.
    /// "android:<clientId>" → (.ANDROID_LOCAL, clientId); "ios:<peerId>[:<originId>]" → (.IOS_ANCS, peerId).
    /// Client ids are colon-free base32, so the first ":" after the prefix bounds the peer id.
    private static func parseFilterDeviceKey(_ deviceKey: String) -> (platform: OriginPlatform, peerId: String)? {
        if deviceKey.hasPrefix("android:") {
            let peer = String(deviceKey.dropFirst("android:".count))
            return peer.isEmpty ? nil : (.ANDROID_LOCAL, peer)
        }
        if deviceKey.hasPrefix("ios:") {
            let rest = deviceKey.dropFirst("ios:".count)
            let peer = rest.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
                .first.map(String.init) ?? ""
            return peer.isEmpty ? nil : (.IOS_ANCS, peer)
        }
        return nil
    }

    /// Peers we last announced a non-empty FILTER to — so a now-empty snapshot sends one clearing FILTER.
    static func lastAnnouncedFilterPeers() -> Set<String> {
        AppGroupStore.withLock(AppGroupStore.Files.filterAnnounce) {
            Set(AppGroupStore.read([String].self, AppGroupStore.Files.filterAnnounce) ?? [])
        }
    }

    static func setLastAnnouncedFilterPeers(_ peers: Set<String>) {
        _ = AppGroupStore.withLock(AppGroupStore.Files.filterAnnounce) {
            AppGroupStore.write(Array(peers).sorted(), AppGroupStore.Files.filterAnnounce)
        }
    }

    private static func preferencesUnlocked() -> NotificationFilterPreferences {
        if let prefs = AppGroupStore.read(NotificationFilterPreferences.self, fileName) { return prefs }
        return NotificationFilterPreferences()
    }

    private static func deviceAppKey(deviceKey: String, appId: String) -> String {
        "\(deviceKey)\u{1f}\(appId)"
    }

    private static func cleanText(_ value: String?) -> String? {
        value?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

}

/// Held signed client cards (`SignedBlob` CBOR, keyed by clientId), in the App Group. They are
/// self-authenticating (clientId == identity fingerprint + identity signature, verified on use), so they
/// are stored unsigned and relayed to introduce a peer by name (the trust-table introduction flow, #3).
nonisolated enum CardStore {
    private static let name = AppGroupStore.Files.cards

    static func all() -> [String: Data] { AppGroupStore.read([String: Data].self, name) ?? [:] }

    static func put(_ clientId: String, blob: SignedBlob) {
        AppGroupStore.withLock(name) {
            var m = all()
            guard m[clientId] == nil else { return }   // first-verified-wins: never overwrite a pinned card
            m[clientId] = ProtocolCodec.encode(blob)
            AppGroupStore.write(m, name)
        }
    }

    static func blob(_ clientId: String) -> SignedBlob? {
        all()[clientId].flatMap { try? ProtocolCodec.decodeSignedBlob($0) }
    }

    static func remove(_ clientId: String) {
        AppGroupStore.withLock(name) {
            var m = all()
            m.removeValue(forKey: clientId)
            AppGroupStore.write(m, name)
        }
    }
}

nonisolated enum MessageDedupClaim: Sendable {
    case claimed
    case alreadyHandled
    case inFlight
}

/// Cross-process, cross-restart idempotency for inbound envelopes (#3), mirroring Android's `MessageDedup`.
/// A bounded, newest-last LRU of durably-handled message ids in the App Group, shared by the app and the
/// NSE so a redelivered relay item (broker at-least-once, app update, NSE↔WS race) is never reprocessed.
/// Contract: `record` is called ONLY after a message was handled (its handler ran / the NSE displayed it),
/// so a crash before recording costs at most a duplicate, never a suppressed-but-never-shown notification.
/// A short-lived in-flight claim closes the concurrent duplicate window without making that claim durable.
nonisolated enum MessageDedupStore {
    private static let name = AppGroupStore.Files.dedup
    private static let cap = 512
    private static let inFlightLeaseMillis: Int64 = 5 * 60 * 1000

    private struct State: Codable, Sendable {
        var handled: [String]
        var inFlight: [String: Int64]
    }

    static func seen(_ messageId: String) -> Bool {
        AppGroupStore.withLock(name) {
            load().handled.contains(messageId)
        }
    }

    static func claim(_ messageId: String) -> MessageDedupClaim {
        AppGroupStore.withLock(name) {
            var state = load()
            let now = nowMillis()
            var changed = prune(&state, now: now)
            if state.handled.contains(messageId) {
                if changed { AppGroupStore.write(state, name) }
                return .alreadyHandled
            }
            if state.inFlight[messageId] != nil {
                if changed { AppGroupStore.write(state, name) }
                return .inFlight
            }
            state.inFlight[messageId] = now
            changed = true
            if changed { AppGroupStore.write(state, name) }
            return .claimed
        }
    }

    static func record(_ messageId: String) {
        AppGroupStore.withLock(name) {
            var state = load()
            _ = prune(&state, now: nowMillis())
            state.inFlight.removeValue(forKey: messageId)
            state.handled.removeAll { $0 == messageId }
            state.handled.append(messageId)
            if state.handled.count > cap { state.handled.removeFirst(state.handled.count - cap) }
            AppGroupStore.write(state, name)
        }
    }

    static func release(_ messageId: String) {
        AppGroupStore.withLock(name) {
            var state = load()
            var changed = prune(&state, now: nowMillis())
            if state.inFlight.removeValue(forKey: messageId) != nil { changed = true }
            if changed { AppGroupStore.write(state, name) }
        }
    }

    private static func load() -> State {
        if let state = AppGroupStore.read(State.self, name) { return state }
        return State(handled: AppGroupStore.read([String].self, name) ?? [], inFlight: [:])
    }

    private static func prune(_ state: inout State, now: Int64) -> Bool {
        var changed = false
        let before = state.inFlight.count
        state.inFlight = state.inFlight.filter { now - $0.value < inFlightLeaseMillis }
        if state.inFlight.count != before { changed = true }
        if state.inFlight.count > cap {
            let keep = Set(state.inFlight.sorted { $0.value > $1.value }.prefix(cap).map(\.key))
            state.inFlight = state.inFlight.filter { keep.contains($0.key) }
            changed = true
        }
        if state.handled.count > cap {
            state.handled.removeFirst(state.handled.count - cap)
            changed = true
        }
        return changed
    }

    private static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

/// Codable mirror of a `PrivateAssetRef` (the CBOR DTO isn't `Codable`). Lets the NSE hand the app the
/// full reference for a delivered icon so the app can fetch + decrypt it later for the Inbox, and lets the
/// Inbox row persist that reference to render an Android-origin launcher icon (no public App Store URL).
nonisolated struct AssetRefSnapshot: Codable, Sendable {
    var role: String
    var assetHash: String
    var mimeType: String
    var sizeBytes: Int
    var sourceClientId: String
    var assetId: String
    var assetKey: Data
    var suite: String

    init(_ r: PrivateAssetRef) {
        role = r.role.rawValue; assetHash = r.assetHash; mimeType = r.mimeType; sizeBytes = r.sizeBytes
        sourceClientId = r.sourceClientId; assetId = r.assetId; assetKey = r.assetKey; suite = r.suite
    }

    var ref: PrivateAssetRef {
        PrivateAssetRef(role: AssetRole(rawValue: role) ?? .APP_ICON, assetHash: assetHash, mimeType: mimeType,
                        sizeBytes: sizeBytes, sourceClientId: sourceClientId, assetId: assetId,
                        assetKey: assetKey, suite: suite)
    }
}

/// A notification the NSE displayed via the APNs alert path but couldn't add to the SwiftData Inbox (the
/// store lives in the app sandbox, not the App Group). The NSE acks the relay copy so the app never sees
/// the message again over WS/drain — without this hand-off the mirror would never reach the Inbox list.
/// The app drains the queue on foreground and upserts each item (idempotent on `messageId`).
nonisolated struct PendingInboxItem: Codable, Sendable {
    var messageId: String
    var identifier: String
    var deliveryMode: String
    var sourceClientId: String
    var sourceKey: String
    var packageName: String
    var iosBundleId: String?
    var appLabel: String
    var title: String?
    var body: String?
    var subtitle: String?
    var originDeviceName: String?
    var originDeviceId: String?
    var originPlatform: String?
    var channelId: String?
    var channelName: String?
    var category: String
    var importance: String
    var postTime: Int64
    var icon: AssetRefSnapshot?

    init(from n: CapturedNotification, messageId: String, identifier: String, deliveryMode: String) {
        self.messageId = messageId
        self.identifier = identifier
        self.deliveryMode = deliveryMode
        sourceClientId = n.sourceClientId
        sourceKey = n.sourceKey
        packageName = n.packageName
        iosBundleId = n.iosBundleId
        appLabel = n.appLabel
        title = n.title
        body = n.bigText ?? n.text
        subtitle = n.subText
        originDeviceName = n.originDeviceName
        originDeviceId = n.originDeviceId
        originPlatform = n.originPlatform.rawValue
        channelId = n.channelId
        channelName = n.channelName
        category = n.category.rawValue
        importance = n.importance.rawValue
        postTime = n.postTime
        icon = (n.appIcon ?? n.largeIcon).map(AssetRefSnapshot.init)
    }

    /// Rebuild the slice of the captured notification the Inbox upsert needs (icon ref preserved).
    var capturedNotification: CapturedNotification {
        CapturedNotification(
            sourceClientId: sourceClientId, sourceKey: sourceKey, packageName: packageName, appLabel: appLabel,
            title: title, text: body, subText: subtitle,
            category: MirrorCategory(rawValue: category) ?? .NONE,
            importance: MirrorImportance(rawValue: importance) ?? .DEFAULT,
            postTime: postTime, channelId: channelId, channelName: channelName, appIcon: icon?.ref,
            originPlatform: originPlatform.flatMap(OriginPlatform.init(rawValue:)) ?? .ANDROID_LOCAL,
            originDeviceName: originDeviceName, iosBundleId: iosBundleId, originDeviceId: originDeviceId)
    }
}

/// Hand-off queue the NSE writes and the app drains (App Group JSON). De-duped on `messageId`.
nonisolated enum PendingInboxStore {
    private static let name = AppGroupStore.Files.pendingInbox

    static func append(_ item: PendingInboxItem) {
        AppGroupStore.withLock(name) {
            var items = AppGroupStore.read([PendingInboxItem].self, name) ?? []
            items.removeAll { $0.messageId == item.messageId }
            items.append(item)
            AppGroupStore.write(items, name)
        }
    }

    /// Return all queued items and clear the queue (the app persists them into SwiftData immediately after).
    static func drainAll() -> [PendingInboxItem] {
        AppGroupStore.withLock(name) {
            let items = AppGroupStore.read([PendingInboxItem].self, name) ?? []
            if !items.isEmpty { AppGroupStore.write([PendingInboxItem](), name) }
            return items
        }
    }
}

/// This device's identity summary, written by the app so the NSE knows whose recipient entry to open
/// and which HPKE epoch key to select.
nonisolated struct SelfRecord: Codable, Sendable {
    var clientId: String
    var identitySpki: Data
    var currentEpoch: Int

    static func load() -> SelfRecord? { AppGroupStore.read(SelfRecord.self, AppGroupStore.Files.selfRecord) }
    func save() { AppGroupStore.write(self, AppGroupStore.Files.selfRecord) }
}

/// An in-flight self key-rotation (NS2 §7), persisted so staged activation/retirement survive a restart.
/// `targetEpoch` is the minted next epoch; we start signing with it at `notBefore`, and the retired epoch's
/// private keys are destroyed at `retireRetiredAt` (old notAfter + a relay-TTL grace).
nonisolated struct SelfPendingRotation: Codable, Sendable {
    var targetEpoch: Int
    var notBefore: Int64
    var notAfter: Int64
    var retiredEpoch: Int
    var retireRetiredAt: Int64
}

/// This device's own operational epoch counter + any in-flight rotation, in the App Group so the engine
/// (app and NSE) reads the current `selfEpoch` without holding mutable in-memory state. `activatedAt` is
/// when the live epoch became active — the rotation-cadence clock.
nonisolated struct RotationStore: Codable, Sendable {
    var selfEpoch: Int
    var activatedAt: Int64
    var pending: SelfPendingRotation?

    static func load() -> RotationStore? { AppGroupStore.read(RotationStore.self, AppGroupStore.Files.rotation) }
    func save() { AppGroupStore.write(self, AppGroupStore.Files.rotation) }

    /// Load the persisted state, seeding it at the supplied epoch on first run.
    static func loadOrSeed(selfEpoch: Int = NotiSyncConfig.initialEpoch) -> RotationStore {
        if let existing = load() { return existing }
        let seeded = RotationStore(selfEpoch: max(selfEpoch, NotiSyncConfig.initialEpoch),
                                   activatedAt: Int64(Date().timeIntervalSince1970 * 1000), pending: nil)
        seeded.save()
        return seeded
    }
}

/// A diagnostics snapshot of the live epoch's public key material + rotation schedule (public keys only).
nonisolated struct RotationKeyInfo: Sendable {
    var epoch: Int
    var signingKeyFingerprint: String
    var encryptionKeyFingerprint: String
    var pendingTargetEpoch: Int?
    var pendingActivated: Bool
    var nextEventAtMillis: Int64
}

/// Persisted identifier → mirror mapping (for dismissal reconciliation + relay-ack), shared with the NSE.
nonisolated struct MirrorMapEntry: Codable, Sendable {
    var identifier: String        // UNNotificationRequest.identifier
    var sourceClientId: String
    var sourceKey: String
    var messageId: String
    /// How this mirror was actually delivered (e.g. the NSE's "APNs alert"), so the app's Inbox reflects
    /// the real path even though the app process only sees it later via drain/WS. Optional for old entries.
    var deliveryMode: String?
    /// Whether the source notification was clearable. A swipe of a non-clearable (ongoing) mirror removes
    /// the local copy but must NOT broadcast a DismissEvent — that would clear the live notification on the
    /// source. Optional for old entries → treated as clearable. (#14)
    var isClearable: Bool?
}

nonisolated enum MirrorMapStore {
    private static let name = AppGroupStore.Files.mirrorMap

    private static func load() -> [String: MirrorMapEntry] {
        AppGroupStore.read([String: MirrorMapEntry].self, name) ?? [:]
    }

    static func all() -> [String: MirrorMapEntry] {
        AppGroupStore.withLock(name) { load() }
    }

    static func put(_ entry: MirrorMapEntry) {
        AppGroupStore.withLock(name) {
            var map = load()
            map[entry.identifier] = entry
            AppGroupStore.write(map, name)
        }
    }

    static func remove(identifier: String) {
        AppGroupStore.withLock(name) {
            var map = load()
            map.removeValue(forKey: identifier)
            AppGroupStore.write(map, name)
        }
    }

    /// All mirror entries for a given source — used to remove every delivered copy (NSE-posted entries
    /// carry the APNs-assigned identifier; app-posted ones carry the derived identifier).
    static func entries(sourceClientId: String, sourceKey: String) -> [MirrorMapEntry] {
        AppGroupStore.withLock(name) {
            load().values.filter { $0.sourceClientId == sourceClientId && $0.sourceKey == sourceKey }
        }
    }

    /// True if any currently-shown mirror already carries this messageId (dedupe NSE vs app re-post).
    static func contains(messageId: String) -> Bool {
        AppGroupStore.withLock(name) {
            load().values.contains { $0.messageId == messageId }
        }
    }

    /// Build an opaque, reversible identifier from (sourceClientId, sourceKey) per plan §D.3.
    static func identifier(sourceClientId: String, sourceKey: String) -> String {
        "\(NSBase64URL.encode(Data(sourceClientId.utf8))).\(NSBase64URL.encode(Data(sourceKey.utf8)))"
    }
}

/// The "should-be-showing" set + the self-removal echo set, shared with the NSE (the NSE adds posted
/// identifiers; the app reconciles against getDeliveredNotifications).
nonisolated struct ShownState: Codable, Sendable {
    var showing: Set<String> = []
    var echo: Set<String> = []          // identifiers we removed ourselves — don't treat as user-dismissed
    var suspected: Set<String> = []     // absent once; confirm across a second poll before emitting a dismissal
}

nonisolated enum ShownStore {
    private static let name = AppGroupStore.Files.shownSet

    static func load() -> ShownState { AppGroupStore.read(ShownState.self, name) ?? ShownState() }
    static func save(_ s: ShownState) { _ = AppGroupStore.withLock(name) { AppGroupStore.write(s, name) } }

    @discardableResult
    static func update<T>(_ body: (inout ShownState) -> T) -> T {
        AppGroupStore.withLock(name) {
            var state = load()
            let result = body(&state)
            AppGroupStore.write(state, name)
            return result
        }
    }

    static func markShowing(_ id: String) {
        update {
            $0.showing.insert(id)
            $0.echo.remove(id)
            $0.suspected.remove(id)
        }
    }

    static func markEchoRemoved(_ id: String) {
        update {
            $0.echo.insert(id)
            $0.showing.remove(id)
            $0.suspected.remove(id)
        }
    }

    static func clear(_ id: String) {
        update {
            $0.showing.remove(id)
            $0.echo.remove(id)
            $0.suspected.remove(id)
        }
    }

    static func clearSuspicions() {
        update { $0.suspected.removeAll() }
    }
}
