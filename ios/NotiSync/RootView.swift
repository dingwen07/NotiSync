import SwiftData
import SwiftUI
import UIKit

struct RootView: View {
    @Environment(\.modelContext) private var modelContext
    @EnvironmentObject private var runtime: NotiSyncRuntime

    var body: some View {
        TabView {
            InboxView()
                .tabItem { Label("Inbox", systemImage: "tray.full") }
            DevicesView()
                .tabItem { Label("Devices", systemImage: "iphone.gen3.radiowaves.left.and.right") }
            ActivityLogView()
                .tabItem { Label("Activity", systemImage: "waveform.path.ecg") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .onAppear {
            runtime.configure(modelContext: modelContext)
        }
        // Pairing deep links: the custom scheme (notisync://pair, works standalone) and — once the Associated
        // Domains capability + server apple-app-site-association are provisioned — the universal /pair link.
        .onOpenURL { runtime.handlePairingURL($0) }
        .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
            if let url = activity.webpageURL { runtime.handlePairingURL(url) }
        }
        .sheet(item: $runtime.incomingPairing) { candidate in
            PairingConfirmView(candidate: candidate) { confirmed, ownDevice in
                if confirmed { runtime.acceptPairing(candidate.payload, ownDevice: ownDevice) }
                runtime.incomingPairing = nil
            }
        }
    }
}

struct InboxView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Query(sort: \InboxNotification.receivedAt, order: .reverse) private var notifications: [InboxNotification]
    @Query private var devices: [TrustedDevice]

    var body: some View {
        NavigationStack {
            List {
                ForEach(notifications) { notification in
                    InboxRow(notification: notification, sourceDevice: device(for: notification))
                        .contentShape(Rectangle())
                        .onTapGesture {
                            guard !notification.isDismissed else { return }
                            Task {
                                await runtime.dismissNotification(
                                    sourceClientId: notification.sourceClientId,
                                    sourceKey: notification.sourceKey
                                )
                            }
                        }
                }
            }
            .navigationTitle("Inbox")
            .overlay {
                if notifications.isEmpty {
                    ContentUnavailableView("No mirrored notifications", systemImage: "tray")
                }
            }
        }
    }

    private func device(for notification: InboxNotification) -> TrustedDevice? {
        devices.first { $0.clientId == notification.sourceClientId }
    }
}

struct InboxRow: View {
    let notification: InboxNotification
    let sourceDevice: TrustedDevice?

    var body: some View {
        HStack(spacing: 12) {
            InboxIconView(notification: notification)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(verbatim: notification.appLabel)
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Text(notification.receivedAt, style: .time)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let title = notification.title, !title.isEmpty {
                    Text(verbatim: title)
                        .font(.body)
                        .lineLimit(1)
                }
                if let body = notification.body, !body.isEmpty {
                    Text(verbatim: body)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                HStack(spacing: 8) {
                    if let originLabel {
                        InlineIconLabel(verbatim: originLabel, systemImage: originSystemImage)
                    }
                    InlineIconLabel(verbatim: LocalizedText.deliveryMode(notification.deliveryMode), systemImage: "arrow.down.circle")
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
        }
        .opacity(notification.isDismissed ? 0.55 : 1)
        .padding(.vertical, 4)
    }

    private var originLabel: String? {
        if notification.isIPhoneOrigin {
            return nonBlank(notification.originDeviceName) ?? "iPhone"
        }
        return nonBlank(sourceDevice?.displayName)
            ?? nonBlank(notification.originDeviceName)
            ?? nonBlank(notification.sourceClientId)
    }

    private var originSystemImage: String {
        // using "iphone" icon specifically for ANDROID_LOCAL origin notification
        notification.isIPhoneOrigin ? "apple.logo" : "iphone"
    }

    private func nonBlank(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }
}

/// Inbox row icon, rendered uniformly from cached bytes for every app: iOS-origin apps resolve the public
/// App Store artwork, Android-origin apps the decrypted `APP_ICON` asset — both via `runtime.appIconBytes`,
/// memoized per app. Keying the load on `runtime.iconRevision` makes a monogram row re-resolve the moment a
/// sibling notification (or an asset repair) supplies the app's icon, so rows from the same app stay in sync.
struct InboxIconView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    let notification: InboxNotification
    @State private var icon: UIImage?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color(.secondarySystemBackground))
            if let icon {
                Image(uiImage: icon).resizable().scaledToFill()
            } else {
                fallbackText
            }
        }
        .frame(width: 42, height: 42)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .task(id: runtime.iconRevision) {
            if let data = await runtime.appIconBytes(for: notification), let image = UIImage(data: data) {
                icon = image
            }
        }
    }

    private var fallbackText: some View {
        Text(verbatim: String(notification.appLabel.prefix(1)).uppercased())
            .font(.headline)
            .foregroundStyle(.secondary)
    }
}

struct DevicesView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Query(sort: \TrustedDevice.updatedAt, order: .reverse) private var devices: [TrustedDevice]
    @Query private var settingsRows: [AppSettings]
    @State private var showingPairing = false

    private var settings: AppSettings? { settingsRows.first }

    var body: some View {
        NavigationStack {
            List {
                Section("This Device") {
                    VerificationValueRow("Client ID", value: runtime.clientId.isEmpty ? LocalizedText.pendingClientId : runtime.clientId)
                    if let settings {
                        LabeledContent("Route", value: LocalizedText.pushStatus(settings.pushStatusValue))
                        LabeledContent("APNs", value: settings.apnsToken == nil
                                       ? LocalizedText.unregistered
                                       : LocalizedText.routeEnvironment(
                                           NotiSyncConfig.effectiveAPNSEnvironment(settings.apnsEnvironment)))
                        LabeledContent("Broker", value: LocalizedText.brokerStatus(
                            reachability: settings.brokerReachability, version: settings.brokerVersion))
                    }
                }
                let pending = devices.filter { $0.status == .pendingTrust || $0.status == .pendingRevoke }
                if !pending.isEmpty {
                    Section("Pending Approval") {
                        ForEach(pending) { device in
                            VStack(alignment: .leading, spacing: 8) {
                                DeviceLabel(device: device)
                                PendingDeviceActions(device: device)
                            }
                        }
                    }
                }
                Section("Trusted Peers") {
                    ForEach(devices.filter { $0.status == .trusted }) { device in
                        DeviceLabel(device: device)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    runtime.revokePeer(clientId: device.clientId)
                                } label: { Label("Revoke", systemImage: "hand.raised.slash") }
                            }
                    }
                }
            }
            .navigationTitle("Devices")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showingPairing = true
                    } label: {
                        Label("Pair", systemImage: "qrcode")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await runtime.publishCurrentRoute() }
                    } label: {
                        Label("Publish Route", systemImage: "antenna.radiowaves.left.and.right")
                    }
                }
            }
            .sheet(isPresented: $showingPairing) {
                PairingView().environmentObject(runtime)
            }
        }
    }
}

/// A trusted/pending device row: best-known name (falls back to the client id), platform • status, and the
/// safety number for out-of-band verification.
struct DeviceLabel: View {
    let device: TrustedDevice

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if device.displayName.isEmpty {
                Text("Unnamed device")
                    .font(.body.weight(.medium))
            } else {
                Text(verbatim: device.displayName)
                    .font(.body.weight(.medium))
            }
            if device.displayName.isEmpty {
                VerificationValueText(device.clientId)
            }
            Text(verbatim: "\(device.platform.isEmpty ? "—" : LocalizedText.platform(device.platform)) • \(LocalizedText.trustStatus(device.status))")
                .font(.caption)
                .foregroundStyle(.secondary)
            if let safetyNumber = device.safetyNumber,
               !safetyNumber.isEmpty,
               !device.displayName.isEmpty || safetyNumber != device.clientId {
                VerificationValueText(safetyNumber)
            }
        }
    }
}

private struct PendingDeviceActions: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    let device: TrustedDevice

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { buttons }
            VStack(alignment: .leading, spacing: 8) { buttons }
        }
    }

    @ViewBuilder
    private var buttons: some View {
        if device.status == .pendingTrust {
            Button {
                runtime.approveTrust(clientId: device.clientId)
            } label: {
                InlineIconLabel("Approve", systemImage: "checkmark.circle")
                    .foregroundStyle(.white)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)

            Button(role: .destructive) {
                runtime.rejectTrust(clientId: device.clientId)
            } label: {
                InlineIconLabel("Reject", systemImage: "xmark.circle")
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
        } else {
            Button {
                runtime.keepTrusted(clientId: device.clientId)
            } label: {
                InlineIconLabel("Keep", systemImage: "checkmark.circle")
            }
            .buttonStyle(.bordered)
            .controlSize(.small)

            Button(role: .destructive) {
                runtime.confirmRevoke(clientId: device.clientId)
            } label: {
                InlineIconLabel("Remove", systemImage: "trash")
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
        }
    }
}

struct ActivityLogView: View {
    @Query(sort: \ActivityRecord.at, order: .reverse) private var records: [ActivityRecord]

    var body: some View {
        NavigationStack {
            List(records) { record in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        InlineIconLabel(verbatim: LocalizedText.activityTitle(record), systemImage: icon(for: record.kind))
                            .font(.body.weight(.medium))
                        Spacer()
                        Text(record.at, style: .time)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(verbatim: LocalizedText.activityDetail(record))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 2)
            }
            .navigationTitle("Activity")
            .overlay {
                if records.isEmpty {
                    ContentUnavailableView("No activity", systemImage: "waveform.path.ecg")
                }
            }
        }
    }

    private func icon(for kind: ActivityKind) -> String {
        switch kind {
        case .received: "arrow.down.circle"
        case .dismissed: "xmark.circle"
        case .sent: "paperplane"
        case .paired: "checkmark.seal"
        case .route: "antenna.radiowaves.left.and.right"
        case .error: "exclamationmark.triangle"
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Query private var settingsRows: [AppSettings]
    @State private var brokerURL = ""
    @State private var deviceName = ""
    @State private var environment: RouteEnvironment = NotiSyncConfig.defaultAPNSEnvironment

    var body: some View {
        NavigationStack {
            Form {
                Section("Onboarding") {
                    Button {
                        runtime.requestNotificationPermissionAndRegister()
                    } label: {
                        InlineIconLabel("Notifications", systemImage: "bell.badge")
                    }
                    Button {
                        runtime.postLocalTestNotification()
                    } label: {
                        InlineIconLabel("Test Local Notification", systemImage: "bell.and.waves.left.and.right")
                    }
                    LabeledContent("Permission", value: LocalizedText.notificationPermission(settingsRows.first?.notificationPermissionValue ?? .unknown))
                    LabeledContent("Pairing", value: LocalizedText.pairingStatus(settingsRows.first?.pairingStatusValue ?? .unpaired))
                }
                Section("Broker") {
                    LabeledContent("Address") {
                        TextField("Address", text: $brokerURL)
                            .textInputAutocapitalization(.never)
                            .keyboardType(.URL)
                            .autocorrectionDisabled()
                            .lineLimit(1)
                            .multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Device Name") {
                        TextField("Device Name", text: $deviceName)
                            .multilineTextAlignment(.trailing)
                    }
                    #if DEBUG
                    if NotiSyncConfig.allowsAPNSEnvironmentSelection {
                        Picker("APNs", selection: $environment) {
                            ForEach(RouteEnvironment.allCases) { env in
                                Text(verbatim: LocalizedText.routeEnvironment(env)).tag(env)
                            }
                        }
                    }
                    #endif
                    Button {
                        runtime.saveSettings(brokerURL: brokerURL, deviceName: deviceName, environment: environment)
                    } label: {
                        InlineIconLabel("Save", systemImage: "checkmark")
                    }
                    .disabled(!hasSettingsChanges)
                }
                Section("Diagnostics") {
                    if let settings = settingsRows.first {
                        LabeledContent("Push Route", value: LocalizedText.pushStatus(settings.pushStatusValue))
                        LabeledContent("Last Delivery", value: settings.lastDeliveryMode.map { LocalizedText.deliveryMode($0) } ?? LocalizedText.none)
                        LabeledContent("Last Route", value: settings.lastRoutePublishAt?.formatted(date: .abbreviated, time: .shortened) ?? LocalizedText.none)
                        LabeledContent("Last Drain", value: settings.lastRelayDrainAt?.formatted(date: .abbreviated, time: .shortened) ?? LocalizedText.none)
                        if let error = settings.lastError {
                            Text(verbatim: error)
                                .font(.footnote)
                                .foregroundStyle(.red)
                        }
                        Button {
                            runtime.simulateLocalStateLossRecovery()
                        } label: {
                            InlineIconLabel("Simulate Reinstall Recovery", systemImage: "arrow.counterclockwise.circle")
                        }
                    }
                }
                Section {
                    if let info = runtime.rotationInfo {
                        LabeledContent("Current epoch", value: "\(info.epoch)")
                        VerificationValueRow("Signing key", value: info.signingKeyFingerprint)
                        VerificationValueRow("Encryption key", value: info.encryptionKeyFingerprint)
                        Text(verbatim: LocalizedText.rotationStatus(info))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Button {
                        runtime.rotateNow()
                    } label: {
                        InlineIconLabel("Rotate Now (debug)", systemImage: "arrow.triangle.2.circlepath")
                    }
                } header: {
                    Text("Key Rotation")
                } footer: {
                    Text("Forces a key rotation immediately (debug). Normally rotates ~monthly. The old epoch's keys are retained through the overlap so in-flight notifications still decrypt.")
                }
            }
            .navigationTitle("Settings")
            .onAppear { loadSettings(); runtime.refreshRotationInfo() }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        runtime.refreshForegroundNow()
                    } label: {
                        Label("Drain", systemImage: "arrow.clockwise")
                    }
                }
            }
        }
    }

    private var hasSettingsChanges: Bool {
        guard let settings = settingsRows.first else { return false }
        return brokerURL != settings.brokerURL
            || deviceName != settings.deviceName
            || NotiSyncConfig.effectiveAPNSEnvironment(environment)
                != NotiSyncConfig.effectiveAPNSEnvironment(settings.apnsEnvironment)
    }

    private func loadSettings() {
        guard let settings = settingsRows.first else { return }
        brokerURL = settings.brokerURL
        deviceName = settings.deviceName
        environment = NotiSyncConfig.effectiveAPNSEnvironment(settings.apnsEnvironment)
    }

}
