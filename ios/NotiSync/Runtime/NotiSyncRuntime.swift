import BackgroundTasks
import Combine
import Foundation
import os
import SwiftData
import UIKit
import UserNotifications

private let log = Logger(subsystem: "net.extrawdw.apps.NotiSync", category: "runtime")

private nonisolated struct RoutePublishSnapshot: Sendable {
    var routeRef: String
    var environment: RouteEnvironment
    var routeEpoch: Int
}

/// App-process orchestration: owns the protocol engine + broker client and drives identity/auth, route
/// publishing, inbound delivery (foreground WS / relay drain / silent push), dismissal sync + two-poll
/// reconciliation, and pairing. The NSE handles the alert-push fast path independently (see the extension).
///
/// The implementation is split across focused extensions so this file stays the orchestration spine:
/// - `+Inbound`   — inbound delivery, mirror rendering, asset resolution, local test.
/// - `+Dismissal` — local/remote dismissal and the two-poll reconciliation.
/// - `+Mesh`      — key-epoch convergence, key rotation (#8), pairing and trust.
/// - `+Store`     — SwiftData persistence helpers, Devices roster, and the Inbox icon cache.
/// This file owns the stored state, lifecycle, push registration, route publishing, relay acks, and the
/// background-refresh task. Members reached from those extensions are `internal` (the class is `final` and
/// app-internal); helpers used only here stay `private`.
@MainActor
final class NotiSyncRuntime: NSObject, ObservableObject {
    static let shared = NotiSyncRuntime()
    static let relayRefreshTaskId = "net.extrawdw.apps.NotiSync.relay-refresh"

    @Published var clientId = ""
    @Published var lastError: String?
    @Published var pairingPayload: String?
    @Published var rotationInfo: RotationKeyInfo?
    /// A pairing candidate surfaced from a deep link (notisync://pair or the universal /pair link), awaiting
    /// the user's confirmation. Pairing is never automatic.
    @Published var incomingPairing: PairingCandidate?

    var modelContext: ModelContext?
    var engine: NotiSyncEngine?
    var broker: BrokerClient?
    var liveTask: Task<Void, Never>?
    private var foregroundSyncTask: Task<Void, Never>?
    private var foregroundSyncGeneration = 0
    private var foregroundSyncRerunRequested = false
    private var bgRegistered = false
    var lastReconcileAt = Date.distantPast
    var repairRequested: Set<String> = []   // assetHash → already asked the source to re-upload
    var iconBytesByApp: [String: Data] = [:]   // appKey (ios:bundleId / and:packageName) → icon bytes
    var iconTokensByApp: [String: String] = [:]   // appKey → source token for the cached icon bytes
    var iconPrioritiesByApp: [String: Int] = [:]   // appKey → APP_ICON beats fallback large icons
    /// Bumped whenever a new app icon lands in `iconBytesByApp`; Inbox rows key their icon load on it so a
    /// row still showing the monogram re-resolves once a sibling notification (or a repair) supplies the icon.
    @Published private(set) var iconRevision = 0

    /// Bump `iconRevision`, whose setter stays `private(set)` so only the runtime mutates it. Called from the
    /// `+Inbound` / `+Store` extensions when an icon is cached or re-provisioned, nudging monogram rows.
    func bumpIconRevision() { iconRevision += 1 }

    // MARK: Lifecycle

    func configure(modelContext: ModelContext) {
        self.modelContext = modelContext
        MirrorCategoryRegistry.registerAll()   // base + any persisted per-channel categories (#15)
        ShownStore.clearSuspicions()
        ensureSettings()
        NotiSyncConfig.brokerURL = settings().brokerURL
        setupEngine()
        refreshRotationInfo()
        ensureThisDeviceRow()
        scheduleRelayRefresh()
        // .onChange(of: scenePhase) doesn't fire for the initial .active, so kick off the foreground
        // flows (broker status, route publish, WS, drain) on cold launch too.
        appBecameActive()
    }

    private func setupEngine() {
        do {
            let engine = try NotiSyncEngine()
            self.engine = engine
            self.clientId = engine.selfClientId
            self.broker = BrokerClient(
                baseURL: { NotiSyncConfig.brokerURL },
                identitySigner: engine.identitySigner,
                operationalSigner: { engine.operationalSigner },   // provider — follows rotation (#8)
                attestor: FirebaseBootstrap.attestor(),
                keyEpochProvider: { try engine.buildClientKeyEpochBlob() }
            )
            log.info("engine ready client=\(engine.selfClientId, privacy: .public) epoch=\(engine.epoch)")
        } catch {
            log.error("engine init failed: \(error.localizedDescription, privacy: .public)")
            record(error: error, title: "Identity")
        }
    }

    private func ensureCoreReady() {
        if engine == nil || broker == nil { setupEngine() }
    }

    func registerBackgroundTasks() {
        guard !bgRegistered else { return }
        bgRegistered = true
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.relayRefreshTaskId, using: nil) { task in
            Task { @MainActor in await self.handleRelayRefreshTask(task) }
        }
    }

    func appBecameActive() {
        startForegroundWebSocket()   // the WS flushes any queued relay messages on connect (broker.flushPending)
        startForegroundSync()
    }

    private func startForegroundSync() {
        if foregroundSyncTask != nil {
            foregroundSyncRerunRequested = true
            return
        }
        foregroundSyncGeneration += 1
        let generation = foregroundSyncGeneration
        foregroundSyncTask = Task { @MainActor in
            await runForegroundSyncLoop(generation: generation)
        }
    }

    private func runForegroundSyncLoop(generation: Int) async {
        defer {
            if foregroundSyncGeneration == generation {
                foregroundSyncTask = nil
                foregroundSyncRerunRequested = false
            }
        }

        repeat {
            foregroundSyncRerunRequested = false
            await performForegroundSyncPass(generation: generation)
        } while shouldContinueForegroundSync(generation) && foregroundSyncRerunRequested
    }

    private func performForegroundSyncPass(generation: Int) async {
        guard shouldContinueForegroundSync(generation) else { return }
        await refreshNotificationPermissionStatusAsync()
        guard shouldContinueForegroundSync(generation) else { return }
        await drainPendingInbox() // pull mirrors the NSE displayed-and-acked into the SwiftData Inbox
        guard shouldContinueForegroundSync(generation) else { return }
        await refreshBrokerStatus()
        guard shouldContinueForegroundSync(generation) else { return }
        await publishCurrentRoute()
        guard shouldContinueForegroundSync(generation) else { return }
        await reconcileDismissals()
        guard shouldContinueForegroundSync(generation) else { return }
        await convergeKeyEpochs()
        guard shouldContinueForegroundSync(generation) else { return }
        await rotationMaintenance()   // initiate-if-due / advance an in-flight rotation (#8)
    }

    private func shouldContinueForegroundSync(_ generation: Int) -> Bool {
        !Task.isCancelled && foregroundSyncGeneration == generation
    }

    private func refreshBrokerStatus() async {
        guard let broker else { return }
        let status: String
        if let v = await broker.verificationStatus() {
            status = v.verified ? "Verified • v\(v.version)" : "Reachable • v\(v.version)"
        } else if let h = await broker.health() {
            status = "Reachable • v\(h.version)"
        } else {
            status = "Unreachable"
        }
        settings().brokerStatus = status
        try? modelContext?.save()
    }

    func appLeftForeground() {
        foregroundSyncGeneration += 1
        foregroundSyncRerunRequested = false
        foregroundSyncTask?.cancel()
        foregroundSyncTask = nil
        liveTask?.cancel()
        liveTask = nil
    }

    // MARK: Notifications / push registration

    func requestNotificationPermissionAndRegister() {
        Task {
            do {
                let granted = try await UNUserNotificationCenter.current().requestAuthorization(
                    options: [.alert, .badge, .sound])
                settings().notificationPermission = granted ? "granted" : "denied"
                if granted {
                    UIApplication.shared.registerForRemoteNotifications()
                }
                try? modelContext?.save()
            } catch {
                record(error: error, title: "Notification permission")
            }
        }
    }

    func didRegisterForRemoteNotifications(deviceToken: Data) {
        let token = deviceToken.lowercaseHex
        let s = settings()
        s.apnsToken = token
        s.pushStatus = "APNs registered"
        s.routeEpoch += 1
        try? modelContext?.save()
        addActivity(.route, "APNs registered", String(token.prefix(12)) + "…")
        Task { await publishCurrentRoute() }
    }

    func didFailToRegisterForRemoteNotifications(error: Error) {
        settings().pushStatus = "APNs failed"
        record(error: error, title: "APNs registration")
    }

    /// Silent background push (DISMISSAL / DATA_SYNC) plus inline notification fallback on legacy brokers.
    func handleRemoteNotification(_ userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        ensureCoreReady()
        if let ct = userInfo["ct"] as? String, let bytes = Data(base64Encoded: ct) {
            let handled = await receiveEnvelope(bytes, mode: .apnsInline)
            if handled, let mid = (userInfo["mid"] as? String) ?? (try? engine?.envelopeMessageId(bytes)) {
                await queueAndFlushAck(messageId: mid)
            }
            return handled ? .newData : .failed
        }
        if let mid = userInfo["mid"] as? String, let broker {
            guard let bytes = await broker.fetchRelayMessage(mid) else { return .failed }
            let handled = await receiveEnvelope(bytes, mode: .apnsRelayFetch)
            if handled { await queueAndFlushAck(messageId: mid) }
            return handled ? .newData : .failed
        }
        return .noData
    }

    func handleNotificationResponse(_ response: UNNotificationResponse) async {
        let id = response.actionIdentifier
        guard id == UNNotificationDismissActionIdentifier || id == MirrorPresentation.dismissActionId else { return }
        let info = response.notification.request.content.userInfo
        guard let scid = info["sourceClientId"] as? String, let sk = info["sourceKey"] as? String else { return }
        await locallyDismiss(sourceClientId: scid, sourceKey: sk)
    }

    func dismissNotification(sourceClientId: String, sourceKey: String) async {
        await locallyDismiss(sourceClientId: sourceClientId, sourceKey: sourceKey)
    }

    // MARK: Settings + route publishing

    func saveSettings(brokerURL: String, deviceName: String, environment: RouteEnvironment) {
        let s = settings()
        let brokerChanged = s.brokerURL != brokerURL
        let renamed = s.deviceName != deviceName
        let routeChanged = brokerChanged || s.apnsEnvironment != environment
        guard renamed || routeChanged else { return }
        s.brokerURL = brokerURL
        s.deviceName = deviceName
        s.apnsEnvironment = environment
        if routeChanged { s.routeEpoch += 1 }
        try? modelContext?.save()
        if brokerChanged { NotiSyncConfig.brokerURL = brokerURL }
        if renamed { ensureThisDeviceRow() }
        let profileUpdatedAt = NotiSyncEngine.nowMillis()
        Task {
            if routeChanged { await publishCurrentRoute() }
            if renamed { await broadcastProfile(displayName: deviceName, updatedAt: profileUpdatedAt) }   // #5 — converge the rename across the mesh
        }
    }

    func publishCurrentRoute() async {
        let s = settings()
        guard let engine, let broker else { return }
        guard let token = s.apnsToken, !token.isEmpty else { s.pushStatus = "APNs unregistered"; return }
        let snapshot = RoutePublishSnapshot(routeRef: token, environment: s.apnsEnvironment, routeEpoch: s.routeEpoch)
        let result = await Task.detached(priority: .utility) {
            try await Self.publishRoute(snapshot: snapshot, engine: engine, broker: broker)
        }.result
        switch result {
        case .success:
            s.pushStatus = "Route published"
            s.lastRoutePublishAt = .now
            addActivity(.route, "Route published", s.apnsEnvironment.rawValue)
            try? modelContext?.save()
        case let .failure(error):
            s.pushStatus = "Route pending"
            record(error: error, title: "Route publish")
        }
    }

    private nonisolated static func publishRoute(
        snapshot: RoutePublishSnapshot,
        engine: NotiSyncEngine,
        broker: BrokerClient
    ) async throws {
        // During a rotation the lifecycle publishes the (finite-notAfter) key-epochs; don't clobber them
        // here with a never-expiring blob. Between rotations, refresh the current epoch to notAfter=max.
        if engine.pendingRotation() == nil {
            try await broker.publishKeyEpoch(try engine.buildClientKeyEpochBlob())
        }
        let claim = try engine.buildRouteClaimBlob(
            routeRef: snapshot.routeRef,
            environment: snapshot.environment,
            routeEpoch: snapshot.routeEpoch
        )
        try await broker.publishRoutes([claim])
    }

    // MARK: Acks

    func flushPendingAcks() async {
        guard let broker else { return }
        let pending = fetchPendingAcks(limit: 500)
        guard !pending.isEmpty else { return }
        if await broker.ackRelayMessages(pending.map(\.messageId)) {
            for ack in pending { modelContext?.delete(ack) }
            try? modelContext?.save()
        }
    }

    func queueAndFlushAck(messageId: String) async {
        guard modelContext != nil else {
            _ = await broker?.ackRelayMessages([messageId])
            return
        }
        if fetchPendingAck(messageId: messageId) == nil {
            modelContext?.insert(PendingRelayAck(messageId: messageId))
            try? modelContext?.save()
        }
        await flushPendingAcks()
    }

    // MARK: Background task

    private func handleRelayRefreshTask(_ task: BGTask) async {
        scheduleRelayRefresh()
        var work: Task<Bool, Never>?
        task.expirationHandler = { work?.cancel() }
        work = Task { @MainActor in
            ensureCoreReady()
            await drainPendingInbox()    // NSE-delivered (APNs alert) mirrors land here, not in the relay
            guard !Task.isCancelled else { return false }
            await drainRelay(deliveryMode: .backgroundRefresh)
            guard !Task.isCancelled else { return false }
            await reconcileDismissals()
            await convergeKeyEpochs()
            await rotationMaintenance()  // time-driven rotation upkeep (#8)
            return !Task.isCancelled
        }
        let success = await work?.value ?? false
        task.expirationHandler = {}
        task.setTaskCompleted(success: success)
    }

    private func scheduleRelayRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: Self.relayRefreshTaskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60 * 30)
        try? BGTaskScheduler.shared.submit(request)
    }
}
