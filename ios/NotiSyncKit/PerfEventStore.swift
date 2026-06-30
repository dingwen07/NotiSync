import Foundation

/// A performance trace measured by a producer that can't report it to Firebase itself, queued in the App
/// Group for the app to replay. Two producers need this:
///   - the Notification Service Extension, which does not link Firebase (configuring it would add tens of ms
///     to the time-boxed alert path it is trying to measure, and extension uploads are unreliable), and
///   - the SwiftData `ModelContainer`, which is built before `FirebaseApp.configure()` runs.
/// The app drains the queue on its next foreground/background pass and replays each via
/// `PerfMonitor.recordValueTrace` (see `NotiSyncRuntime.drainDeferredPerfTraces`).
nonisolated struct DeferredPerfTrace: Codable, Sendable {
    var name: String
    var attributes: [String: String]
    var metrics: [String: Int64]
    var at: Int64

    init(
        name: String,
        attributes: [String: String] = [:],
        metrics: [String: Int64] = [:],
        at: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.name = name
        self.attributes = attributes
        self.metrics = metrics
        self.at = at
    }
}

/// Append-and-drain queue of `DeferredPerfTrace`s in the App Group (JSON), bounded so a long offline stretch
/// can't grow it without limit. Mirrors the `PendingInboxStore` hand-off pattern.
nonisolated enum PerfEventStore {
    private static let name = AppGroupStore.Files.perfEvents
    private static let cap = 256

    static func append(_ trace: DeferredPerfTrace) {
        AppGroupStore.withLock(name) {
            var items = AppGroupStore.read([DeferredPerfTrace].self, name) ?? []
            items.append(trace)
            if items.count > cap { items.removeFirst(items.count - cap) }
            AppGroupStore.write(items, name)
        }
    }

    /// Return all queued traces and clear the queue (the app replays them into Firebase immediately after).
    static func drainAll() -> [DeferredPerfTrace] {
        AppGroupStore.withLock(name) {
            let items = AppGroupStore.read([DeferredPerfTrace].self, name) ?? []
            if !items.isEmpty { AppGroupStore.write([DeferredPerfTrace](), name) }
            return items
        }
    }
}
