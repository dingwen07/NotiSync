import Foundation
import SwiftData
import UIKit
import UserNotifications

/// SwiftData-backed persistence + the in-memory icon cache: settings/device/inbox/activity rows (the Inbox
/// unbounded — paged by `InboxListModel`, text-searched via the `InboxSearchIndex` FTS5 sidecar; activity
/// bounded by pruning), the Devices roster mirrored from the protocol trust store, unified Inbox-row icon
/// resolution (App Store artwork for iOS-origin, decrypted launcher icons for Android-origin), and
/// activity/error logging.
extension NotiSyncRuntime {

    // MARK: SwiftData helpers

    /// Task-local flag marking a `withCoalescedSaves` scope, where intermediate `saveModelContext` calls
    /// become no-ops and one commit happens at the scope's end. Task-local (not a stored property) so two
    /// envelope deliveries interleaving on the main actor can't leak each other's deferral.
    private enum SaveCoalescing {
        @TaskLocal static var isActive = false
    }

    @discardableResult
    func saveModelContext(_ context: ModelContext? = nil) -> Bool {
        if SaveCoalescing.isActive { return true }   // the enclosing withCoalescedSaves commits
        return persistModelContext(context)
    }

    /// Batch a multi-write flow (an inbound envelope upserts an Inbox row, logs activity, and stamps the
    /// settings row — three commits without this) into a single SQLite commit at the end of `body`. Nested
    /// scopes each commit at their own boundary, so an inner `receiveEnvelope`'s save-before-ack contract
    /// holds even when an outer drain loop is also batching (the outer commit then finds no changes).
    func withCoalescedSaves<T>(_ body: () async -> T) async -> T {
        let result = await SaveCoalescing.$isActive.withValue(true, operation: body)
        persistModelContext(nil)
        return result
    }

    @discardableResult
    private func persistModelContext(_ context: ModelContext?) -> Bool {
        guard let context = context ?? modelContext else { return false }
        guard context.hasChanges else { return true }
        // Hold a background assertion across the commit: a WAL checkpoint inside save() can block on fsync,
        // and being suspended at that point is the 0xdead10cc kill. The empty expiration handler is fine —
        // save() blocks this (main) thread, so the handler couldn't run until save() returns anyway, at
        // which point the defer ends the task.
        let task = UIApplication.shared.beginBackgroundTask(withName: "NotiSync SwiftData Save") {}
        defer {
            if task != .invalid {
                UIApplication.shared.endBackgroundTask(task)
            }
        }
        do {
            try context.save()
            return true
        } catch {
            return false
        }
    }

    func ensureSettings() { _ = settings(); saveModelContext() }

    func androidLocalNotificationsEnabled(for clientId: String) -> Bool {
        _ = notificationFilterRevision
        return NotificationFilterStore.androidLocalNotificationsEnabled(for: clientId)
    }

    func setAndroidLocalNotificationsEnabled(_ enabled: Bool, for clientId: String) {
        NotificationFilterStore.setAndroidLocalNotificationsEnabled(enabled, for: clientId)
        notificationFiltersDidChange()
    }

    func iosDevices() -> [FilteredIosDeviceRecord] {
        _ = notificationFilterRevision
        return NotificationFilterStore.iosDevices()
    }

    func recordIosDevice(peerClientId: String, originDeviceId: String?, deviceName: String?) {
        NotificationFilterStore.recordIosDevice(
            peerClientId: peerClientId,
            originDeviceId: originDeviceId,
            deviceName: deviceName)
        bumpNotificationFilterRevision()
    }

    func removeIosDevice(deviceKey: String) {
        NotificationFilterStore.removeIosDevice(deviceKey: deviceKey)
        // Removing a device clears its filters too — announce so the source peer drops them (a clearing FILTER).
        notificationFiltersDidChange()
    }

    func iosNotificationsEnabled(deviceKey: String) -> Bool {
        _ = notificationFilterRevision
        return NotificationFilterStore.iosNotificationsEnabled(deviceKey: deviceKey)
    }

    func setIosNotificationsEnabled(_ enabled: Bool, device: FilteredIosDeviceRecord) {
        NotificationFilterStore.setIosNotificationsEnabled(
            enabled,
            deviceKey: device.deviceKey,
            peerClientId: device.peerClientId,
            originDeviceId: device.originDeviceId,
            deviceName: device.deviceName)
        notificationFiltersDidChange()
    }

    func appNotificationsEnabled(deviceKey: String, appId: String) -> Bool {
        _ = notificationFilterRevision
        return NotificationFilterStore.appNotificationsEnabled(deviceKey: deviceKey, appId: appId)
    }

    func setAppNotificationsEnabled(_ enabled: Bool, deviceKey: String, appId: String) {
        NotificationFilterStore.setAppNotificationsEnabled(enabled, deviceKey: deviceKey, appId: appId)
        notificationFiltersDidChange()
    }

    func filteredAppIdentifiers(deviceKey: String) -> Set<String> {
        _ = notificationFilterRevision
        return NotificationFilterStore.filteredAppIds(deviceKey: deviceKey)
    }

    func channelNotificationsEnabled(deviceKey: String, appId: String, channelId: String) -> Bool {
        _ = notificationFilterRevision
        return NotificationFilterStore.channelNotificationsEnabled(deviceKey: deviceKey, appId: appId, channelId: channelId)
    }

    func setChannelNotificationsEnabled(_ enabled: Bool, deviceKey: String, appId: String, channelId: String) {
        NotificationFilterStore.setChannelNotificationsEnabled(enabled, deviceKey: deviceKey, appId: appId, channelId: channelId)
        notificationFiltersDidChange()
    }

    func filteredChannelIdentifiers(deviceKey: String, appId: String) -> Set<String> {
        _ = notificationFilterRevision
        return NotificationFilterStore.filteredChannelIds(deviceKey: deviceKey, appId: appId)
    }

    func appIdentifiersWithFilteredChannels(deviceKey: String) -> Set<String> {
        _ = notificationFilterRevision
        return NotificationFilterStore.appIdsWithFilteredChannels(deviceKey: deviceKey)
    }

    func filterNotificationsLike(_ notification: InboxNotification) {
        let platform = originPlatform(for: notification)
        switch platform {
        case .ANDROID_LOCAL:
            setAndroidLocalNotificationsEnabled(false, for: notification.sourceClientId)
        case .IOS_ANCS:
            let name = NotificationFilterStore.filteredIosDeviceName(from: notification.originDeviceName) ?? "iOS Device"
            recordIosDevice(peerClientId: notification.sourceClientId, originDeviceId: notification.originDeviceId, deviceName: name)
            if let deviceKey = filterDeviceKey(for: notification) {
                let device = FilteredIosDeviceRecord(
                    peerClientId: notification.sourceClientId,
                    originDeviceId: notification.originDeviceId,
                    deviceName: name,
                    updatedAt: Int64(notification.receivedAt.timeIntervalSince1970 * 1000))
                NotificationFilterStore.setIosNotificationsEnabled(
                    false,
                    deviceKey: deviceKey,
                    peerClientId: device.peerClientId,
                    originDeviceId: device.originDeviceId,
                    deviceName: device.deviceName)
                notificationFiltersDidChange()
            }
        }
    }

    func canFilterNotificationsLike(_ notification: InboxNotification) -> Bool {
        let platform = originPlatform(for: notification)
        switch platform {
        case .ANDROID_LOCAL:
            return !notification.sourceClientId.isEmpty
        case .IOS_ANCS:
            return !notification.sourceClientId.isEmpty
        }
    }

    func originPlatform(for notification: InboxNotification) -> OriginPlatform {
        if let platform = notification.originPlatform.flatMap(OriginPlatform.init(rawValue:)) {
            return platform
        }
        return notification.isIPhoneOrigin ? .IOS_ANCS : .ANDROID_LOCAL
    }

    func filterDeviceKey(for notification: InboxNotification) -> String? {
        switch originPlatform(for: notification) {
        case .ANDROID_LOCAL:
            return NotificationFilterStore.androidDeviceKey(notification.sourceClientId)
        case .IOS_ANCS:
            return NotificationFilterStore.iosDeviceKey(
                peerClientId: notification.sourceClientId,
                originDeviceId: notification.originDeviceId)
        }
    }

    func filterAppIdentifier(for notification: InboxNotification) -> String? {
        NotificationFilterStore.appIdentifier(packageName: notification.packageName, iosBundleId: notification.iosBundleId)
    }

    func settings() -> AppSettings {
        if let cachedSettings { return cachedSettings }
        // No context yet (pre-configure): hand out a throwaway row and do NOT cache it — its writes are
        // intentionally dropped, and caching would shadow the persistent row once the context arrives.
        guard let modelContext else { return AppSettings() }
        if let existing = try? modelContext.fetch(FetchDescriptor<AppSettings>()).first {
            cachedSettings = existing
            return existing
        }
        let created = AppSettings()
        createdSettingsThisLaunch = true
        modelContext.insert(created)
        cachedSettings = created
        return created
    }

    func refreshNotificationPermissionStatus() {
        Task {
            await refreshNotificationPermissionStatusAsync()
        }
    }

    func refreshNotificationPermissionStatusAsync() async {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        let s = settings()
        let newValue: NotificationPermissionStatus = switch status {
        case .authorized, .provisional, .ephemeral: .granted
        case .denied: .denied
        case .notDetermined: .notRequested
        @unknown default: .unknown
        }
        // Same-value writes still dirty the row and force a commit; this runs every foreground pass.
        if s.notificationPermissionValue != newValue { s.notificationPermissionValue = newValue }
        saveModelContext()
    }

    func ensureThisDeviceRow() {
        let s = settings()
        guard !clientId.isEmpty else { return }
        if let device = fetchDevice(clientId: clientId) {
            // Touch the row only on an actual change — this runs on every launch, and an unconditional
            // updatedAt bump forced a main-thread commit each time.
            if device.displayName != s.deviceName || device.status != .thisDevice {
                device.displayName = s.deviceName
                device.updatedAt = .now
                device.status = .thisDevice
            }
        } else {
            modelContext?.insert(TrustedDevice(clientId: clientId, displayName: s.deviceName, platform: "ios",
                                               status: .thisDevice, safetyNumber: clientId))
        }
        saveModelContext()
    }

    func refreshPeerRows() {
        guard let engine else { return }
        applyPeerRows(engine.trustedPeers())
    }

    /// Off-main variant for async flows (inbound data-sync, key-epoch convergence): loading the trust
    /// roster re-reads and signature-verifies the whole file — not main-thread work.
    func refreshPeerRowsAsync() async {
        guard let engine else { return }
        let peers = await Task.detached(priority: .utility) { engine.trustedPeers() }.value
        applyPeerRows(peers)
    }

    private func applyPeerRows(_ peers: [TrustedPeerRecord]) {
        for peer in peers where peer.clientId != clientId {
            let status = Self.rowStatus(for: peer)
            if let row = fetchDevice(clientId: peer.clientId) {
                row.displayName = peer.displayName
                row.platform = peer.platform
                row.status = status
                row.updatedAt = .now
            } else {
                modelContext?.insert(TrustedDevice(clientId: peer.clientId, displayName: peer.displayName,
                                                   platform: peer.platform, status: status,
                                                   safetyNumber: peer.clientId))
            }
        }
        saveModelContext()
    }

    /// Map a peer's protocol trust status to the Devices-UI row status (incl. revoked, #6).
    private static func rowStatus(for peer: TrustedPeerRecord) -> TrustStatusRaw {
        switch TrustStatus(rawValue: peer.status) {
        case .TRUSTED: return .trusted
        case .PENDING_TRUST: return .pendingTrust
        case .PENDING_REVOKE: return .pendingRevoke
        case .REVOKED: return .revoked
        case .none: return .pendingTrust
        }
    }

    func upsertInbox(_ n: CapturedNotification, messageId: String, identifier: String, deliveryMode: String) {
        let iconRef = n.appIcon ?? n.largeIcon
        let iconRefData = iconRef.flatMap { try? JSONEncoder().encode(AssetRefSnapshot($0)) }
        var shouldRefreshIcons = false
        if let existing = fetchNotification(messageId: messageId) {
            existing.receivedAt = .now
            searchIndex.touch(messageId: messageId, receivedAt: existing.receivedAt)
            existing.deliveryMode = deliveryMode
            if existing.originPlatform == nil {
                existing.originPlatform = n.originPlatform.rawValue
            }
            if existing.originDeviceName == nil {
                existing.originDeviceName = n.originDeviceName
            }
            if existing.originDeviceId == nil {
                existing.originDeviceId = n.originDeviceId
            }
            if existing.channelId == nil {
                existing.channelId = n.channelId
            }
            if existing.channelName == nil {
                existing.channelName = n.channelName
            }
            if existing.iconAssetHash == nil, let hash = iconRef?.assetHash {
                existing.iconAssetHash = hash
                shouldRefreshIcons = true
            }
            if existing.iconAssetRefData == nil, let data = iconRefData {
                existing.iconAssetRefData = data
                shouldRefreshIcons = true
            }
        } else {
            // The icon (App Store artwork for iOS-origin, decrypted APP_ICON for Android-origin) is resolved
            // lazily + uniformly by `appIconBytes` at display time; the row only needs the asset reference.
            let inserted = InboxNotification(
                messageId: messageId, sourceClientId: n.sourceClientId, sourceKey: n.sourceKey,
                packageName: n.packageName, iosBundleId: n.iosBundleId, appLabel: n.appLabel,
                title: n.title, body: n.bigText ?? n.text, subtitle: n.subText,
                originDeviceName: n.originDeviceName, originDeviceId: n.originDeviceId,
                originPlatform: n.originPlatform.rawValue, channelId: n.channelId, channelName: n.channelName,
                category: n.category.rawValue,
                importance: n.importance.rawValue,
                postTime: Date(timeIntervalSince1970: TimeInterval(n.postTime) / 1000), receivedAt: .now,
                localNotificationId: identifier, deliveryMode: deliveryMode,
                iconAssetHash: iconRef?.assetHash, iconAssetRefData: iconRefData)
            // Index only what persists: with no context (pre-configure) the row is dropped, and a
            // phantom index entry would surface unopenable search hits until the next reconcile.
            if let modelContext {
                modelContext.insert(inserted)
                searchIndex.insert(InboxSearchEntry(inserted))
            }
        }
        saveModelContext()
        bumpInboxRevision()
        if shouldRefreshIcons { bumpIconRevision() }
    }

    /// Activity rows stay persisted but bounded — drop the oldest past the cap so the diagnostic log
    /// doesn't grow without limit. The Inbox is intentionally NOT capped: history is the product surface,
    /// the UI pages it (`InboxListModel`) instead of materializing it, and search goes through the FTS5
    /// sidecar — so retained rows cost disk, not working set.
    static let activityRowLimit = 600

    /// Delete the oldest rows of `T` (by a `Date` key) beyond `keeping`. Cheap: a COUNT, then a bounded
    /// fetch of just the overflow. The caller's `save()` persists the deletions.
    private func pruneOldest<T: PersistentModel>(_ type: T.Type, by keyPath: KeyPath<T, Date>, keeping limit: Int) {
        guard let modelContext else { return }
        let count = (try? modelContext.fetchCount(FetchDescriptor<T>())) ?? 0
        guard count > limit else { return }
        var descriptor = FetchDescriptor<T>(sortBy: [SortDescriptor(keyPath, order: .forward)])
        descriptor.fetchLimit = count - limit
        (try? modelContext.fetch(descriptor))?.forEach { modelContext.delete($0) }
    }

    /// Drain mirrors the NSE displayed over the APNs alert path (and then acked) into the SwiftData Inbox.
    /// Idempotent: `upsertInbox` dedupes on `messageId`, so a replay just refreshes the existing row.
    func drainPendingInbox() async {
        let items = await Task.detached(priority: .utility) {
            PendingInboxStore.drainAll()
        }.value
        guard !items.isEmpty else { return }
        await withCoalescedSaves {
            for item in items {
                upsertInbox(item.capturedNotification, messageId: item.messageId,
                            identifier: item.identifier, deliveryMode: item.deliveryMode)
            }
        }
    }

    /// Unified Inbox-row icon bytes, memoized per (app, peer) so each Android peer's icon is independently
    /// fetched and cached in AssetCache. iOS-origin apps resolve via the public App Store icon (per bundleId,
    /// peer-agnostic). A new entry bumps `iconRevision` so monogram rows re-resolve.
    func appIconBytes(for n: InboxNotification) async -> Data? {
        guard let key = iconAppKey(bundleId: n.iosBundleId, packageName: n.packageName,
                                   sourceClientId: n.sourceClientId) else { return nil }
        let source = iconCacheSource(bundleId: n.iosBundleId, iconAssetHash: n.iconAssetHash, refData: n.iconAssetRefData)
        if let cached = iconBytesByApp[key] {
            let cachedPriority = iconPrioritiesByApp[key] ?? 0
            if cachedPriority > source.priority
                || (cachedPriority == source.priority && iconTokensByApp[key] == source.token) {
                return cached
            }
        }
        var data: Data?
        if let bundleId = n.iosBundleId, !bundleId.isEmpty {
            data = await AppIconFetcher.iconData(iosBundleId: bundleId)
        } else if n.iconAssetHash != nil {
            data = await inboxIconData(hash: n.iconAssetHash, refData: n.iconAssetRefData)
        }
        if let data {
            cacheAppIcon(data, key: key, token: source.token, priority: source.priority)
            return data
        }
        return iconBytesByApp[key]
    }

    private func iconAppKey(bundleId: String?, packageName: String, sourceClientId: String? = nil) -> String? {
        if let bundleId, !bundleId.isEmpty { return "ios:\(bundleId)" }
        guard !packageName.isEmpty else { return nil }
        let peer = sourceClientId.flatMap { $0.isEmpty ? nil : $0 } ?? "_"
        return "and:\(packageName):\(peer)"
    }

    private func iconCacheSource(bundleId: String?, iconAssetHash: String?, refData: Data?) -> (token: String, priority: Int) {
        if let bundleId, !bundleId.isEmpty {
            return ("ios:\(bundleId)", 3)
        }

        if let refData, let snapshot = try? JSONDecoder().decode(AssetRefSnapshot.self, from: refData) {
            let priority = snapshot.role == AssetRole.APP_ICON.rawValue ? 2 : 1
            return ("and:\(snapshot.role):\(snapshot.assetHash)", priority)
        }

        if let iconAssetHash {
            return ("and:asset:\(iconAssetHash)", 1)
        }

        return ("none", 0)
    }

    private func cacheAppIcon(_ data: Data, key: String, token: String, priority: Int) {
        if let cached = iconBytesByApp[key] {
            let cachedPriority = iconPrioritiesByApp[key] ?? 0
            if cachedPriority > priority { return }
            if cached == data && cachedPriority == priority && iconTokensByApp[key] == token { return }
        }
        iconBytesByApp[key] = data
        iconTokensByApp[key] = token
        iconPrioritiesByApp[key] = priority
        bumpIconRevision()   // nudge rows still on the monogram to re-resolve (now an in-memory hit)
    }

    /// Icon bytes for an Inbox row's launcher icon (Android-origin apps): cache-first, then a read-through
    /// fetch + decrypt of the private `APP_ICON` asset. On a broker miss (the sender thinks the asset is
    /// still uploaded — e.g. its local ticket is fresh but the broker GC'd/lost the bytes), ask the source
    /// to re-provide it; a subsequent resolve (after the repair lands) then succeeds.
    func inboxIconData(hash: String?, refData: Data?) async -> Data? {
        if let hash, let cached = await Task.detached(priority: .utility, operation: {
            AssetCache.read(hash)
        }).value { return cached }
        guard let refData, let snapshot = await Task.detached(priority: .utility, operation: {
            try? JSONDecoder().decode(AssetRefSnapshot.self, from: refData)
        }).value else { return nil }
        let ref = snapshot.ref
        if let data = await loadAsset(ref) { return data }
        scheduleAssetRepair(ref)
        return nil
    }

    func markDismissed(sourceClientId: String, sourceKey: String) {
        guard let modelContext else { return }
        let descriptor = FetchDescriptor<InboxNotification>(
            predicate: #Predicate { $0.sourceClientId == sourceClientId && $0.sourceKey == sourceKey })
        try? modelContext.fetch(descriptor).forEach { $0.isDismissed = true }
        saveModelContext(modelContext)
        bumpInboxRevision()
    }

    /// Mark every Inbox row matching `predicate` as read (the Inbox menu's "Mark … as Read") — the FULL
    /// dismissal flow, batched: each distinct (source, key) clears its local mirrors, acks, and seals a
    /// DismissEvent to the mesh (so the source device dismisses too), exactly as if the user tapped
    /// every row. Rows dim progressively as the pass advances; non-clearable (ongoing) sources keep
    /// their existing skip-the-outbound-send handling inside `locallyDismiss`.
    func markAllAsRead(matching predicate: Predicate<InboxNotification>?) async {
        guard let modelContext else { return }
        let rows = (try? modelContext.fetch(FetchDescriptor<InboxNotification>(predicate: predicate))) ?? []
        var seen = Set<String>()
        var sources: [(clientId: String, key: String)] = []
        for row in rows where seen.insert("\(row.sourceClientId)\u{1f}\(row.sourceKey)").inserted {
            sources.append((row.sourceClientId, row.sourceKey))
        }
        guard !sources.isEmpty else { return }
        await withCoalescedSaves {
            for source in sources {
                await locallyDismiss(sourceClientId: source.clientId, sourceKey: source.key)
            }
        }
    }

    /// Delete every Inbox notification from local storage (the Inbox "Delete All" action). Storage-only — it
    /// does not sync a dismissal to peers; it just prunes the mirrored rows on this device.
    func deleteAllInboxNotifications() {
        guard let modelContext else { return }
        // Batch form: fetching every row just to delete it is O(history) in memory, and the Inbox is unbounded.
        try? modelContext.delete(model: InboxNotification.self)
        saveModelContext(modelContext)
        searchIndex.deleteAll()
        bumpInboxRevision()
    }

    /// Delete one app's Inbox rows (the Inbox menu's Delete All while an app filter is active). Same
    /// storage-only semantics as `deleteAllInboxNotifications`.
    func deleteInboxNotifications(matching predicate: Predicate<InboxNotification>, appKey: String) {
        guard let modelContext else { return }
        try? modelContext.delete(model: InboxNotification.self, where: predicate)
        saveModelContext(modelContext)
        searchIndex.deleteApp(appKey: appKey)
        bumpInboxRevision()
    }

    /// Delete every Inbox row matching an active search — ALL matches from the sidecar, not just the
    /// loaded page. The id list comes from the index, so a row the index missed (drift) survives; it was
    /// invisible to the search either way, and the launch reconcile re-converges the two stores.
    func deleteInboxSearchResults(matching match: String, appKey: String?) async {
        guard let modelContext else { return }
        let ids = await searchIndex.search(matching: match, appKey: appKey, limit: Int(Int32.max), offset: 0)
        guard !ids.isEmpty else { return }
        var start = 0
        while start < ids.count {
            let chunk = Array(ids[start..<min(start + 500, ids.count)])
            try? modelContext.delete(model: InboxNotification.self,
                                     where: #Predicate { chunk.contains($0.messageId) })
            start += 500
        }
        saveModelContext(modelContext)
        searchIndex.deleteMessages(ids)
        bumpInboxRevision()
    }

    /// Launch-time self-heal for the FTS sidecar: SwiftData is the source of truth and the index is
    /// disposable, so any drift (a crash between store commit and index write, the pre-release destructive
    /// store reset, a schema/tokenizer bump, first launch over existing history) is repaired by rebuilding
    /// from the store. Cheap when healthy: one COUNT on each side.
    func reconcileSearchIndexIfNeeded() async {
        guard let modelContext else { return }
        let storeCount = (try? modelContext.fetchCount(FetchDescriptor<InboxNotification>())) ?? 0
        guard await searchIndex.needsRebuild(expectedCount: storeCount) else { return }
        let started = Date()
        var entries: [InboxSearchEntry] = []
        entries.reserveCapacity(storeCount)
        // Page the enumeration and yield between pages — the context is main-actor, and a large history
        // fetched in one shot would materialize every row inside a single main-thread frame.
        let batchSize = 500
        var offset = 0
        while true {
            var descriptor = FetchDescriptor<InboxNotification>(
                sortBy: [SortDescriptor(\.receivedAt), SortDescriptor(\.messageId)])
            descriptor.fetchLimit = batchSize
            descriptor.fetchOffset = offset
            let page = (try? modelContext.fetch(descriptor)) ?? []
            entries.append(contentsOf: page.map(InboxSearchEntry.init))
            if page.count < batchSize { break }
            offset += batchSize
            await Task.yield()
        }
        await searchIndex.rebuild(entries)
        PerfMonitor.recordValueTrace(
            "inbox_index_rebuild",
            metrics: ["rows": Int64(entries.count),
                      "duration_ms": Int64(Date().timeIntervalSince(started) * 1000)])
    }

    /// Delete every Activity log entry from local storage (the Activity "Delete All" action).
    func deleteAllActivity() {
        guard let modelContext else { return }
        (try? modelContext.fetch(FetchDescriptor<ActivityRecord>()))?.forEach { modelContext.delete($0) }
        saveModelContext(modelContext)
    }

    func addActivity(
        _ kind: ActivityKind,
        _ title: ActivityTitleToken,
        titleArg: String = "",
        detail: ActivityDetailStyle = .none,
        detailArg: String = "",
        detailNum: Int = 0
    ) {
        modelContext?.insert(ActivityRecord(
            kind: kind, title: title, titleArg: titleArg,
            detail: detail, detailArg: detailArg, detailNum: detailNum))
        pruneOldest(ActivityRecord.self, by: \.at, keeping: Self.activityRowLimit)
        saveModelContext()
    }

    func record(error: Error, domain: ErrorDomain) {
        let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        lastError = message
        settings().lastError = message
        addActivity(.error, .error, titleArg: domain.rawValue, detail: .text, detailArg: message)
        saveModelContext()
    }

    func fetchNotification(messageId: String) -> InboxNotification? {
        guard let modelContext else { return nil }
        return try? modelContext.fetch(FetchDescriptor<InboxNotification>(
            predicate: #Predicate { $0.messageId == messageId })).first
    }

    func fetchPendingAck(messageId: String) -> PendingRelayAck? {
        guard let modelContext else { return nil }
        return try? modelContext.fetch(FetchDescriptor<PendingRelayAck>(
            predicate: #Predicate { $0.messageId == messageId })).first
    }

    func fetchPendingAcks(limit: Int) -> [PendingRelayAck] {
        guard let modelContext else { return [] }
        var descriptor = FetchDescriptor<PendingRelayAck>(sortBy: [SortDescriptor(\.createdAt)])
        descriptor.fetchLimit = limit
        return (try? modelContext.fetch(descriptor)) ?? []
    }

    func fetchDevice(clientId: String) -> TrustedDevice? {
        guard let modelContext else { return nil }
        return try? modelContext.fetch(FetchDescriptor<TrustedDevice>(
            predicate: #Predicate { $0.clientId == clientId })).first
    }
}
