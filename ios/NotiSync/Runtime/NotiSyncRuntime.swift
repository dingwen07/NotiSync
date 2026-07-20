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
    /// The AppSettings singleton row, memoized after the first fetch — `settings()` is called on nearly
    /// every runtime operation and a per-call FetchDescriptor round-trip is main-thread SQLite work.
    var cachedSettings: AppSettings?
    var createdSettingsThisLaunch = false
    var engine: NotiSyncEngine?
    var broker: BrokerClient?
    private var coreBringUpTask: Task<Void, Never>?
    var liveTask: Task<Void, Never>?
    private var foregroundSyncTask: Task<Void, Never>?
    private var foregroundSyncGeneration = 0
    private var foregroundSyncRerunRequested = false
    private var localStateRecoveryNeeded = false
    private var localStateRecoveryCompleted = false
    private var localStateRecoveryUsesCurrentKeychainBase = false
    private var foregroundActive = false
    private var bgRegistered = false
    var lastReconcileAt = Date.distantPast
    /// Coalesces a burst of filter toggles into one outbound FILTER announce (see `notificationFiltersDidChange`).
    var filterAnnounceTask: Task<Void, Never>?
    var repairRequested: Set<String> = []   // assetHash → already asked the source to re-upload
    var iconBytesByApp: [String: Data] = [:]   // appKey (ios:bundleId / and:packageName) → icon bytes
    var iconTokensByApp: [String: String] = [:]   // appKey → source token for the cached icon bytes
    var iconPrioritiesByApp: [String: Int] = [:]   // appKey → APP_ICON beats fallback large icons
    /// Bumped whenever a new app icon lands in `iconBytesByApp`; Inbox rows key their icon load on it so a
    /// row still showing the monogram re-resolves once a sibling notification (or a repair) supplies the icon.
    @Published private(set) var iconRevision = 0
    @Published private(set) var notificationFilterRevision = 0
    /// Bumped after every Inbox mutation (upsert, dismissal, delete-all). The Inbox list is paged
    /// (`InboxListModel`) rather than a live `@Query`, so this is its re-fetch signal.
    @Published private(set) var inboxRevision = 0
    /// FTS5 sidecar over the Inbox's searchable text. Every Inbox mutation goes through this runtime,
    /// which mirrors it into the index; `reconcileSearchIndexIfNeeded` self-heals any drift at launch.
    nonisolated let searchIndex = InboxSearchIndex()

    /// Bump `iconRevision`, whose setter stays `private(set)` so only the runtime mutates it. Called from the
    /// `+Inbound` / `+Store` extensions when an icon is cached or re-provisioned, nudging monogram rows.
    func bumpIconRevision() { iconRevision += 1 }
    func bumpNotificationFilterRevision() { notificationFilterRevision += 1 }
    func bumpInboxRevision() { inboxRevision += 1 }

    /// A local notification-filter setting changed: refresh dependent UI, then announce the new snapshot to the
    /// source peers it targets so they stop/resume delivering to us. A short debounce coalesces a rapid burst
    /// of toggles into one send of the final state and (via cancel-previous) prevents an older snapshot from
    /// landing after a newer one; it is NOT cancelled when the app backgrounds, so the send still fires.
    func notificationFiltersDidChange() {
        bumpNotificationFilterRevision()
        filterAnnounceTask?.cancel()
        filterAnnounceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled, let self else { return }
            await self.broadcastNotificationFilters()
        }
    }

    // MARK: Lifecycle

    func configure(modelContext: ModelContext) {
        self.modelContext = modelContext
        self.cachedSettings = nil
        // Every mutation path already saves explicitly (saveModelContext / withCoalescedSaves), so autosave
        // only adds nondeterministic commits — including WAL checkpoints outside the background-task
        // assertion that guards against the 0xdead10cc suspension kill.
        modelContext.autosaveEnabled = false
        ensureSettings()
        let brokerURL = settings().brokerURL
        // Everything below touches the Keychain / Secure Enclave / App Group files — keep it off the
        // launch frames (it used to run synchronously here and surfaced as a hitch on the first gesture).
        Task { [weak self] in
            guard let self else { return }
            await Task.detached(priority: .userInitiated) {
                NotiSyncConfig.brokerURL = brokerURL
                MirrorCategoryRegistry.registerAll()   // base + any persisted per-channel categories (#15)
                ShownStore.clearSuspicions()
            }.value
            await self.bringUpCore()
            let profileChanged = self.ensureSelfProfileRevision()
            if profileChanged { await self.broadcastProfile() }
            self.refreshRotationInfo()
            self.ensureThisDeviceRow()
            self.scheduleRelayRefresh()
            // Self-heal the Inbox search sidecar (first launch with the feature, schema bump, store
            // reset, drift) — its own low-priority task so the foreground kicks below aren't held up.
            Task(priority: .utility) { await self.reconcileSearchIndexIfNeeded() }
            // .onChange(of: scenePhase) doesn't fire for the initial .active, so kick off the foreground
            // flows (broker status, route publish, WS, drain) on cold launch here. If the scene handler
            // already flipped foregroundActive while the core was still coming up, its WS/sync starts were
            // no-ops (no broker yet) — force them now instead of appBecameActive (which would bail).
            if self.foregroundActive {
                self.startForegroundWebSocket()
                self.startForegroundSync(rerunIfBusy: true)
            } else if UIApplication.shared.applicationState != .background {
                self.appBecameActive()
            }
            // else: backgrounded before the core was ready — the next scenePhase .active runs the kick.
        }
    }

    /// Bring up the engine + broker with the Keychain + Secure Enclave roundtrips off the main thread.
    /// Single-flight and idempotent: concurrent callers (cold-launch configure, a silent push, the BG
    /// refresh task, a pairing deep link) await the same bring-up; a failed attempt is retried by the
    /// next caller (the old `ensureCoreReady` semantics).
    func bringUpCore() async {
        if engine != nil, broker != nil { return }
        if let coreBringUpTask {
            await coreBringUpTask.value
            return
        }
        let task = Task { await self.performCoreBringUp() }
        coreBringUpTask = task
        await task.value
        coreBringUpTask = nil
    }

    private func performCoreBringUp() async {
        let result = await Task.detached(priority: .userInitiated) { () -> Result<NotiSyncEngine, Error> in
            do { return .success(try NotiSyncEngine()) } catch { return .failure(error) }
        }.value
        switch result {
        case .success(let engine):
            self.engine = engine
            self.clientId = engine.selfClientId
            let needsCleanup = await Task.detached {
                NotiSyncConfig.needsUnverifiedDeviceCleanupV1
            }.value
            if needsCleanup,
               let removed = await Task.detached(priority: .utility, operation: {
                   engine.cleanupUnverifiedPeersV1()
               }).value {
                if let modelContext {
                    for id in removed {
                        if let row = fetchDevice(clientId: id) { modelContext.delete(row) }
                    }
                    saveModelContext(modelContext)
                }
                let marked = await Task.detached {
                    NotiSyncConfig.markUnverifiedDeviceCleanupV1Completed()
                }.value
                if marked, !removed.isEmpty {
                    log.info("removed \(removed.count, privacy: .public) unverified device(s) during NS2 upgrade")
                }
            }
            self.broker = BrokerClient(
                baseURL: { NotiSyncConfig.brokerURL },
                identitySigner: engine.identitySigner,
                operationalSigner: { engine.operationalSigner },   // provider — follows rotation (#8)
                attestor: FirebaseBootstrap.attestor(),
                keyEpochProvider: { try engine.buildClientKeyEpochBlob() }
            )
            let swiftDataRecoveredWithExistingIdentity = createdSettingsThisLaunch && engine.identityAlreadyExisted
            createdSettingsThisLaunch = false
            self.localStateRecoveryNeeded = engine.recoveredAfterLocalStateLoss || swiftDataRecoveredWithExistingIdentity
            self.localStateRecoveryCompleted = !localStateRecoveryNeeded
            self.localStateRecoveryUsesCurrentKeychainBase =
                swiftDataRecoveredWithExistingIdentity && !engine.recoveredAfterLocalStateLoss
            if swiftDataRecoveredWithExistingIdentity {
                await Task.detached { KeychainEpochStore.setAPNsRouteResetPending(true) }.value
            }
            log.info("engine ready client=\(engine.selfClientId, privacy: .public) epoch=\(engine.epoch)")
        case .failure(let error):
            log.error("engine init failed: \(error.localizedDescription, privacy: .public)")
            record(error: error, domain: .identity)
        }
    }

    func registerBackgroundTasks() {
        guard !bgRegistered else { return }
        bgRegistered = true
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.relayRefreshTaskId, using: nil) { task in
            Task { @MainActor in await self.handleRelayRefreshTask(task) }
        }
    }

    func appBecameActive() {
        guard !foregroundActive else { return }
        foregroundActive = true
        startForegroundWebSocket()   // the WS flushes any queued relay messages on connect (broker.flushPending)
        startForegroundSync()
    }

    func refreshForegroundNow() {
        startForegroundWebSocket()
        startForegroundSync(rerunIfBusy: true)
    }

    func shouldContinueDelivery(mode: DeliveryMode) -> Bool {
        !Task.isCancelled && (mode != .foregroundWebSocket || foregroundActive)
    }

    private func startForegroundSync(rerunIfBusy: Bool = false) {
        if foregroundSyncTask != nil {
            if rerunIfBusy { foregroundSyncRerunRequested = true }
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
        let span = PerfMonitor.startSpan("foreground_sync_pass")
        defer { span.stop() }
        guard shouldContinueForegroundSync(generation) else { return }
        await refreshNotificationPermissionStatusAsync()
        guard shouldContinueForegroundSync(generation) else { return }
        await drainPendingInbox() // pull mirrors the NSE displayed-and-acked into the SwiftData Inbox
        await drainPendingDismissals() // ...then the dismissals its piggyback drain applied (rows exist first)
        await sweepTombstonedMirrors() // and remove any mirror that raced in after its dismissal
        drainDeferredPerfTraces() // replay NSE-measured perf traces (the NSE has no Firebase) into Performance
        guard shouldContinueForegroundSync(generation) else { return }
        await refreshBrokerStatus()
        guard shouldContinueForegroundSync(generation) else { return }
        if localStateRecoveryNeeded && !localStateRecoveryCompleted {
            guard await recoverLocalStateLossIfNeeded() else { return }
        }
        guard shouldContinueForegroundSync(generation) else { return }
        await publishCurrentRoute()
        guard shouldContinueForegroundSync(generation) else { return }
        await reconcileDismissals()
        guard shouldContinueForegroundSync(generation) else { return }
        await convergeKeyEpochs()
        guard shouldContinueForegroundSync(generation) else { return }
        await rotationMaintenance()   // initiate-if-due / advance an in-flight rotation (#8)
        guard shouldContinueForegroundSync(generation) else { return }
        await announcePeriodicStateIfDue()   // heartbeat: re-announce profile / key-epoch / notification filters
    }

    private func shouldContinueForegroundSync(_ generation: Int) -> Bool {
        !Task.isCancelled && foregroundSyncGeneration == generation
    }

    private func refreshBrokerStatus() async {
        guard let broker else { return }
        let reachability: BrokerReachability
        let version: String?
        if let v = await broker.verificationStatus() {
            reachability = v.verified ? .verified : .reachable
            version = v.version
        } else if let h = await broker.health() {
            reachability = .reachable
            version = h.version
        } else {
            reachability = .unreachable
            version = nil
        }
        // Same-value writes still dirty the row and force a commit; this runs every foreground pass.
        let s = settings()
        if s.brokerReachability != reachability { s.brokerReachability = reachability }
        if s.brokerVersion != version { s.brokerVersion = version }
        saveModelContext()
    }

    func appLeftForeground() {
        foregroundActive = false
        foregroundSyncGeneration += 1
        foregroundSyncRerunRequested = false
        foregroundSyncTask?.cancel()
        foregroundSyncTask = nil
        liveTask?.cancel()
        liveTask = nil
    }

    // MARK: Notifications / push registration

    func requestNotificationPermissionAndRegister() {
        Task { await requestNotificationPermissionAndRegisterAsync() }
    }

    /// Awaitable variant (mirrors the `refreshNotificationPermissionStatus`/`Async` pair) so onboarding
    /// can advance on the user's answer. Returns whether the permission ended up granted.
    @discardableResult
    func requestNotificationPermissionAndRegisterAsync() async -> Bool {
        do {
            let granted = try await UNUserNotificationCenter.current().requestAuthorization(
                options: [.alert, .badge, .sound])
            settings().notificationPermissionValue = granted ? .granted : .denied
            if granted {
                UIApplication.shared.registerForRemoteNotifications()
            }
            saveModelContext()
            return granted
        } catch {
            record(error: error, domain: .notificationPermission)
            return false
        }
    }

    /// First-launch onboarding finished (or was skipped through) — persist so RootView stops showing it.
    func completeOnboarding() {
        let s = settings()
        if !s.hasCompletedOnboarding { s.hasCompletedOnboarding = true }
        saveModelContext()
    }

    func didRegisterForRemoteNotifications(deviceToken: Data) {
        let token = deviceToken.lowercaseHex
        let s = settings()
        _ = s.epochForAPNsRoute(routeRef: token, environment: s.apnsEnvironment)
        s.pushStatusValue = .apnsRegistered
        saveModelContext()
        addActivity(.route, .apnsRegistered, detail: .text, detailArg: String(token.prefix(12)) + "…")
        Task { await publishCurrentRoute() }
    }

    func didFailToRegisterForRemoteNotifications(error: Error) {
        settings().pushStatusValue = .apnsFailed
        record(error: error, domain: .apnsRegistration)
    }

    /// Silent background push (DISMISSAL / DATA_SYNC) plus inline notification fallback on legacy brokers.
    func handleRemoteNotification(_ userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        await bringUpCore()
        if let ct = userInfo["ct"] as? String, let bytes = Data(base64Encoded: ct) {
            let handled = await receiveEnvelope(bytes, mode: .apnsInline)
            if handled, let mid = (userInfo["mid"] as? String) ?? (try? engine?.envelopeMessageId(bytes)) {
                await queueAndFlushAck(messageId: mid)
            }
            return handled ? .newData : .noData
        }
        if let mid = userInfo["mid"] as? String, let broker {
            guard let bytes = await broker.fetchRelayMessage(mid) else { return .failed }
            let handled = await receiveEnvelope(bytes, mode: .apnsRelayFetch)
            if handled { await queueAndFlushAck(messageId: mid) }
            return handled ? .newData : .noData
        }
        return .noData
    }

    func handleNotificationResponse(_ response: UNNotificationResponse) async {
        let id = response.actionIdentifier
        let info = response.notification.request.content.userInfo
        guard let scid = info["sourceClientId"] as? String, let sk = info["sourceKey"] as? String else { return }
        if id == UNNotificationDismissActionIdentifier || id == MirrorPresentation.dismissActionId {
            await bringUpCore()
            await drainPendingInbox()
            await locallyDismiss(sourceClientId: scid, sourceKey: sk)
            return
        }
        // Default tap opens NotiSync only; opening the origin is now an explicit action button so an
        // accidental banner tap does not fire UI on the source device.
        if id == UNNotificationDefaultActionIdentifier { return }
        // "Open on <device>": ask the origin to open the real notification only when the producer
        // declared a content intent (old producers and ANCS bridges can't honor a TAP).
        if id == MirrorPresentation.openActionId {
            guard (info["hasContentIntent"] as? Bool) == true else { return }
            await bringUpCore()
            await sendMirrorAction(
                ActionEvent(sourceClientId: scid, sourceKey: sk, kind: .TAP, actedAt: NotiSyncEngine.nowMillis()))
            return
        }
        // A mirrored action button ("notisync.act.<index>"): unicast a PERFORM to the origin, echoing the
        // title recorded in userInfo so the origin can verify the action row hasn't shifted. Reply text
        // rides along from the text-input action; an empty reply has nothing to perform.
        if id.hasPrefix(MirrorPresentation.performActionPrefix),
           let index = Int(id.dropFirst(MirrorPresentation.performActionPrefix.count)) {
            let actions = info["actions"] as? [[String: Any]]
            let action = actions?.first { ($0["index"] as? Int) == index }
            let title = action?["title"] as? String
            let actionGeneration = (action?["actionGeneration"] as? NSNumber)?.int64Value
            let actionToken = action?["actionToken"] as? String
            let isRemoteInput = (action?["remoteInput"] as? Bool) == true
            let text = (response as? UNTextInputNotificationResponse)?.userText
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if isRemoteInput, text?.isEmpty != false { return }
            await bringUpCore()
            await sendMirrorAction(
                ActionEvent(sourceClientId: scid, sourceKey: sk, kind: .PERFORM, actionIndex: index,
                            actionTitle: title, remoteInputText: isRemoteInput ? text : nil,
                            actedAt: NotiSyncEngine.nowMillis(), actionGeneration: actionGeneration,
                            actionToken: actionToken))
        }
    }

    func dismissNotification(sourceClientId: String, sourceKey: String) async {
        await locallyDismiss(sourceClientId: sourceClientId, sourceKey: sourceKey)
    }

    // MARK: Settings + route publishing

    func saveSettings(brokerURL: String, deviceName: String, environment: RouteEnvironment) {
        let s = settings()
        let brokerURL = NotiSyncConfig.upgradeLegacyDefaultBrokerURL(brokerURL)
        let effectiveEnvironment = NotiSyncConfig.effectiveAPNSEnvironment(environment)
        let brokerChanged = s.brokerURL != brokerURL
        let renamed = s.deviceName != deviceName
        let apnsEnvironmentChanged = s.apnsEnvironment != effectiveEnvironment
        let routeChanged = brokerChanged || apnsEnvironmentChanged
        guard renamed || routeChanged else { return }
        s.brokerURL = brokerURL
        s.deviceName = deviceName
        if apnsEnvironmentChanged, let token = s.apnsToken, !token.isEmpty {
            _ = s.epochForAPNsRoute(routeRef: token, environment: effectiveEnvironment)
        } else {
            s.apnsEnvironment = effectiveEnvironment
        }
        saveModelContext()
        if brokerChanged { NotiSyncConfig.brokerURL = brokerURL }
        if renamed { ensureThisDeviceRow() }
        _ = ensureSelfProfileRevision()
        Task {
            if routeChanged { await publishCurrentRoute() }
            if renamed { await broadcastProfile(displayName: deviceName) }   // #5 — converge the rename across the mesh
        }
    }

    /// Advance the persisted profile LWW clock whenever name/platform/capabilities change (including upgrade).
    @discardableResult
    func ensureSelfProfileRevision() -> Bool {
        guard let engine else { return false }
        let s = settings()
        let fingerprint = engine.selfProfileFingerprint(displayName: s.deviceName)
        guard s.selfProfileFingerprint != fingerprint || s.selfProfileUpdatedAt == 0 else { return false }
        s.selfProfileFingerprint = fingerprint
        let now = NotiSyncEngine.nowMillis()
        s.selfProfileUpdatedAt = max(now, s.selfProfileUpdatedAt + 1)
        saveModelContext()
        return true
    }

    func publishCurrentRoute() async {
        if localStateRecoveryNeeded && !localStateRecoveryCompleted {
            guard await recoverLocalStateLossIfNeeded() else { return }
        }
        let s = settings()
        guard let engine, let broker else { return }
        let environment = NotiSyncConfig.effectiveAPNSEnvironment(s.apnsEnvironment)
        guard let token = s.apnsToken, !token.isEmpty else { s.pushStatusValue = .apnsUnregistered; return }
        let routeEpoch = s.epochForAPNsRoute(routeRef: token, environment: environment)
        saveModelContext()
        let resetRouteFirst = await Task.detached(priority: .utility) {
            KeychainEpochStore.apnsRouteResetPending()   // up to two Keychain roundtrips — off main
        }.value
        let snapshot = RoutePublishSnapshot(routeRef: token, environment: environment, routeEpoch: routeEpoch)
        let result = await Task.detached(priority: .utility) {
            try await Self.publishRoute(snapshot: snapshot, engine: engine, broker: broker, resetRouteFirst: resetRouteFirst)
        }.result
        switch result {
        case .success:
            if resetRouteFirst {
                await Task.detached(priority: .utility) { KeychainEpochStore.setAPNsRouteResetPending(false) }.value
            }
            s.pushStatusValue = .routePublished
            s.lastRoutePublishAt = .now
            addActivity(.route, .routePublished, detail: .routeEnvironment, detailArg: environment.rawValue)
            saveModelContext()
        case let .failure(error):
            s.pushStatusValue = .routePending
            record(error: error, domain: .routePublish)
        }
    }

    private nonisolated static func publishRoute(
        snapshot: RoutePublishSnapshot,
        engine: NotiSyncEngine,
        broker: BrokerClient,
        resetRouteFirst: Bool
    ) async throws {
        // During a rotation the lifecycle publishes the (finite-notAfter) key-epochs; don't clobber them
        // here with a never-expiring blob. Between rotations, refresh the current epoch to notAfter=max.
        if engine.pendingRotation() == nil {
            try await broker.publishKeyEpoch(try engine.buildClientKeyEpochBlob())
        }
        if resetRouteFirst {
            let resetClaim = try engine.buildRouteClaimBlob(
                routeRef: "",
                environment: snapshot.environment,
                routeEpoch: max(snapshot.routeEpoch, 1)
            )
            try await broker.publishRoutes([resetClaim])
        }
        let claim = try engine.buildRouteClaimBlob(
            routeRef: snapshot.routeRef,
            environment: snapshot.environment,
            routeEpoch: snapshot.routeEpoch
        )
        try await broker.publishRoutes([claim])
    }

    @discardableResult
    private func recoverLocalStateLossIfNeeded(force: Bool = false) async -> Bool {
        guard let engine, let broker else { return false }
        guard force || (localStateRecoveryNeeded && !localStateRecoveryCompleted) else { return true }

        let serverLatestEpoch = await broker.fetchKeyEpochWithCachedAuth(clientId: engine.selfClientId)
            .flatMap { engine.serverEpoch(from: $0) } ?? 0
        do {
            let useCurrentKeychainBase = force || localStateRecoveryUsesCurrentKeychainBase
            let recoveredEpoch = try await Task.detached(priority: .utility) {
                try engine.recoverSelfEpochAfterLocalStateLoss(
                    serverLatestEpoch: serverLatestEpoch,
                    force: useCurrentKeychainBase
                )
            }.value
            try await broker.publishKeyEpoch(try engine.buildClientKeyEpochBlob())
            localStateRecoveryNeeded = false
            localStateRecoveryCompleted = true
            localStateRecoveryUsesCurrentKeychainBase = false
            addActivity(.route, .localStateRecovered, detail: .epoch, detailNum: recoveredEpoch)
            await refreshRotationInfoAsync()
            return true
        } catch {
            record(error: error, domain: .rotation)
            return false
        }
    }

    func simulateLocalStateLossRecovery() {
        Task {
            guard await recoverLocalStateLossIfNeeded(force: true) else { return }
            await publishCurrentRoute()
        }
    }

    /// Diagnostics: drop the saved broker bearer (JWT) from memory + Keychain so the next request
    /// re-attests from scratch (App Check → broker). Mirrors Android's Settings→Diagnostics "clear local
    /// token". Then refresh the broker status so the UI reflects the now-unverified state.
    func clearBrokerToken() {
        Task {
            await broker?.clearCachedAuth()
            await refreshBrokerStatus()
        }
    }

    // MARK: Acks

    func flushPendingAcks() async {
        guard let broker else { return }
        let pending = fetchPendingAcks(limit: 500)
        guard !pending.isEmpty else { return }
        if await broker.ackRelayMessages(pending.map(\.messageId)) {
            for ack in pending { modelContext?.delete(ack) }
            saveModelContext()
        }
    }

    func queueAndFlushAck(messageId: String) async {
        guard modelContext != nil else {
            _ = await broker?.ackRelayMessages([messageId])
            return
        }
        if fetchPendingAck(messageId: messageId) == nil {
            modelContext?.insert(PendingRelayAck(messageId: messageId))
            saveModelContext()
        }
        await flushPendingAcks()
    }

    /// Queue acks for a whole batch without flushing (the Inbox's Read All) — one dedupe fetch instead of
    /// one per message. The caller commits and then flushes the batch in a single broker call.
    func queueAcks(messageIds: [String]) {
        guard let modelContext, !messageIds.isEmpty else { return }
        let ids = Array(Set(messageIds))
        let queued = Set(((try? modelContext.fetch(FetchDescriptor<PendingRelayAck>(
            predicate: #Predicate { ids.contains($0.messageId) }))) ?? []).map(\.messageId))
        for id in ids where !queued.contains(id) {
            modelContext.insert(PendingRelayAck(messageId: id))
        }
    }

    // MARK: Background task

    private func handleRelayRefreshTask(_ task: BGTask) async {
        scheduleRelayRefresh()
        var work: Task<Bool, Never>?
        task.expirationHandler = { work?.cancel() }
        work = Task { @MainActor in
            await bringUpCore()
            await drainPendingInbox()    // NSE-delivered (APNs alert) mirrors land here, not in the relay
            await drainPendingDismissals()   // dismissals the NSE's piggyback drain applied
            await sweepTombstonedMirrors()   // mirrors that raced in after their dismissal
            drainDeferredPerfTraces()    // replay NSE-measured perf traces into Performance
            guard !Task.isCancelled else { return false }
            await drainRelay(deliveryMode: .backgroundRefresh)
            guard !Task.isCancelled else { return false }
            await reconcileDismissals()
            await convergeKeyEpochs()
            await rotationMaintenance()  // time-driven rotation upkeep (#8)
            guard !Task.isCancelled else { return false }
            await announcePeriodicStateIfDue()   // heartbeat: profile / key-epoch / notification filters
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

    /// Replay perf traces measured where Firebase can't run live — the NSE (which does not link Firebase) and
    /// the SwiftData container built before `FirebaseApp.configure()` — into Performance Monitoring. Drains
    /// the App Group hand-off queue; each record becomes a one-shot value trace.
    private func drainDeferredPerfTraces() {
        for trace in PerfEventStore.drainAll() {
            PerfMonitor.recordValueTrace(trace.name, attributes: trace.attributes, metrics: trace.metrics)
        }
    }
}
