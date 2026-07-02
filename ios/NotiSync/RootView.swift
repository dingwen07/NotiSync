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
                        .contextMenu {
                            if let channelFilter = androidChannelFilter(for: notification) {
                                Button {
                                    runtime.setChannelNotificationsEnabled(
                                        false,
                                        deviceKey: channelFilter.deviceKey,
                                        appId: channelFilter.appId,
                                        channelId: channelFilter.channelId)
                                } label: {
                                    Label("Silence this Channel", systemImage: "bell.slash")
                                }
                            }
                            if let deviceKey = runtime.filterDeviceKey(for: notification),
                               let appId = runtime.filterAppIdentifier(for: notification) {
                                Button {
                                    runtime.setAppNotificationsEnabled(false, deviceKey: deviceKey, appId: appId)
                                } label: {
                                    Label("Silence this App", systemImage: "bell.slash")
                                }
                            }
                            if runtime.canFilterNotificationsLike(notification) {
                                Button {
                                    runtime.filterNotificationsLike(notification)
                                } label: {
                                    Label("Silence this Device", systemImage: "bell.slash")
                                }
                            }
                        }
                }
            }
            .navigationTitle("Inbox")
            .toolbar {
                if !notifications.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        // A Menu (not a dialog) is the second confirmation: tapping the trash reveals one
                        // destructive "Delete All N" item — the native pull-down/popover style.
                        Menu {
                            Button(role: .destructive) {
                                runtime.deleteAllInboxNotifications()
                            } label: {
                                Label("Delete All \(notifications.count) Notifications", systemImage: "trash")
                            }
                        } label: {
                            Label("Delete All", systemImage: "trash")
                        }
                    }
                }
            }
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

    private func androidChannelFilter(for notification: InboxNotification) -> (deviceKey: String, appId: String, channelId: String)? {
        guard runtime.originPlatform(for: notification) == .ANDROID_LOCAL,
              let deviceKey = runtime.filterDeviceKey(for: notification),
              let appId = runtime.filterAppIdentifier(for: notification),
              let channelId = nonBlank(notification.channelId) else { return nil }
        return (deviceKey, appId, channelId)
    }

    private func nonBlank(_ value: String?) -> String? {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed?.isEmpty == false ? trimmed : nil
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
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \TrustedDevice.updatedAt, order: .reverse) private var devices: [TrustedDevice]
    @Query private var settingsRows: [AppSettings]
    @State private var showingPairing = false
    @State private var selectedFilterDevice: FilterDeviceSelection?
    /// Deliberately NOT live (no `@Query` on InboxNotification): the bridged-devices list is derived from
    /// Inbox history, and a live query re-fetched + rescanned on every inbound envelope while this page
    /// was up. Loaded on page entry / pull-to-refresh, and re-derived when the user edits a filter (so a
    /// "Clear Filter" swipe removes its row immediately).
    @State private var iosDeviceRows: [FilteredIosDeviceRecord] = []

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
                        TrustedPeerRow(device: device, supportsNotificationFilters: !isIosPeer(device)) {
                            selectedFilterDevice = .android(device)
                        }
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    runtime.revokePeer(clientId: device.clientId)
                                } label: { Label("Revoke", systemImage: "hand.raised.slash") }
                            }
                    }
                }
                IosDevicesSection(
                    devices: iosDeviceRows,
                    peerName: peerName,
                    onOpenFilters: { selectedFilterDevice = .ios($0) })
            }
            .navigationTitle("Devices")
            .task { reloadIosDeviceRows() }
            .refreshable { reloadIosDeviceRows() }
            .onChange(of: runtime.notificationFilterRevision) { _, _ in reloadIosDeviceRows() }
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
            .sheet(item: $selectedFilterDevice) { selection in
                AppFilterSheet(
                    title: selection.title(peerName: peerName),
                    selection: selection)
                    .environmentObject(runtime)
            }
        }
    }

    private func reloadIosDeviceRows() {
        var rowsByKey: [String: FilteredIosDeviceRecord] = [:]
        for device in runtime.iosDevices() {
            rowsByKey[device.deviceKey] = device
        }
        let ancsRaw = OriginPlatform.IOS_ANCS.rawValue
        let descriptor = FetchDescriptor<InboxNotification>(
            predicate: #Predicate { $0.originPlatform == ancsRaw },
            sortBy: [SortDescriptor(\.receivedAt, order: .reverse)])
        for notification in (try? modelContext.fetch(descriptor)) ?? [] {
            guard !notification.sourceClientId.isEmpty,
                  let deviceKey = runtime.filterDeviceKey(for: notification) else { continue }
            let name = notification.originDeviceName?.trimmingCharacters(in: .whitespacesAndNewlines)
            let displayName = name?.isEmpty == false ? name ?? "iOS Device" : "iOS Device"
            rowsByKey[deviceKey] = FilteredIosDeviceRecord(
                peerClientId: notification.sourceClientId,
                originDeviceId: notification.originDeviceId,
                deviceName: displayName,
                updatedAt: Int64(notification.receivedAt.timeIntervalSince1970 * 1000))
        }
        iosDeviceRows = rowsByKey.values.sorted(by: areIosDevicesInDisplayOrder)
    }

    private func areIosDevicesInDisplayOrder(_ lhs: FilteredIosDeviceRecord, _ rhs: FilteredIosDeviceRecord) -> Bool {
        let nameOrder = lhs.deviceName.localizedCaseInsensitiveCompare(rhs.deviceName)
        if nameOrder != .orderedSame { return nameOrder == .orderedAscending }
        let originOrder = (lhs.originDeviceId ?? "").localizedCaseInsensitiveCompare(rhs.originDeviceId ?? "")
        if originOrder != .orderedSame { return originOrder == .orderedAscending }
        return lhs.peerClientId.localizedCaseInsensitiveCompare(rhs.peerClientId) == .orderedAscending
    }

    private func peerName(_ clientId: String) -> String {
        let name = devices.first { $0.clientId == clientId }?.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return name?.isEmpty == false ? name ?? clientId : clientId
    }

    private func isIosPeer(_ device: TrustedDevice) -> Bool {
        device.platform.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "ios"
    }
}

private struct TrustedPeerRow: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    let device: TrustedDevice
    let supportsNotificationFilters: Bool
    let openFilters: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            DeviceLabel(device: device)
                .contentShape(Rectangle())
                .onTapGesture {
                    if supportsNotificationFilters, runtime.androidLocalNotificationsEnabled(for: device.clientId) {
                        openFilters()
                    }
                }
            if supportsNotificationFilters {
                HStack {
                    Toggle(isOn: Binding(
                        get: { runtime.androidLocalNotificationsEnabled(for: device.clientId) },
                        set: { runtime.setAndroidLocalNotificationsEnabled($0, for: device.clientId) }
                    )) {
                        NotificationPostingLabel(isEnabled: runtime.androidLocalNotificationsEnabled(for: device.clientId))
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    Spacer()
                }
            }
        }
    }
}

private enum FilterDeviceSelection: Identifiable {
    case android(TrustedDevice)
    case ios(FilteredIosDeviceRecord)

    var id: String {
        switch self {
        case .android(let device): "android:\(device.clientId)"
        case .ios(let record): record.deviceKey
        }
    }

    var deviceKey: String {
        id
    }

    func title(peerName: (String) -> String) -> String {
        switch self {
        case .android(let device):
            let name = device.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
            return name.isEmpty ? "Peer Apps" : "\(name) Apps"
        case .ios(let record):
            return "\(record.deviceName) (via \(peerName(record.peerClientId))) Apps"
        }
    }

    var supportsChannels: Bool {
        switch self {
        case .android: true
        case .ios: false
        }
    }

    func matches(_ notification: InboxNotification, runtime: NotiSyncRuntime) -> Bool {
        switch self {
        case .android(let device):
            return runtime.originPlatform(for: notification) == .ANDROID_LOCAL
                && notification.sourceClientId == device.clientId
        case .ios(let record):
            return runtime.originPlatform(for: notification) == .IOS_ANCS
                && notification.sourceClientId == record.peerClientId
                && normalizedOriginDeviceId(notification.originDeviceId) == normalizedOriginDeviceId(record.originDeviceId)
        }
    }

    private func normalizedOriginDeviceId(_ value: String?) -> String? {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed?.isEmpty == false ? trimmed : nil
    }
}

private struct AppFilterItem: Identifiable {
    var appId: String
    var appLabel: String
    var detail: String
    var channels: [String: ChannelFilterItem]

    var id: String { appId }
}

private struct ChannelFilterItem: Identifiable {
    var channelId: String
    var channelName: String

    var id: String { channelId }
}

private struct IosDevicesSection: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    let devices: [FilteredIosDeviceRecord]
    let peerName: (String) -> String
    let onOpenFilters: (FilteredIosDeviceRecord) -> Void

    var body: some View {
        if !devices.isEmpty {
            Section("iOS Devices") {
                ForEach(devices) { device in
                    VStack(alignment: .leading, spacing: 8) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(verbatim: device.deviceName)
                                .font(.body.weight(.medium))
                            iosDeviceSubtitle(device)
                        }
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if runtime.iosNotificationsEnabled(deviceKey: device.deviceKey) {
                                onOpenFilters(device)
                            }
                        }
                        HStack {
                            Toggle(isOn: Binding(
                                get: { runtime.iosNotificationsEnabled(deviceKey: device.deviceKey) },
                                set: { runtime.setIosNotificationsEnabled($0, device: device) }
                            )) {
                                NotificationPostingLabel(isEnabled: runtime.iosNotificationsEnabled(deviceKey: device.deviceKey))
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            Spacer()
                        }
                    }
                    .swipeActions(edge: .trailing) {
                        if !runtime.iosNotificationsEnabled(deviceKey: device.deviceKey) {
                            Button {
                                runtime.removeIosDevice(deviceKey: device.deviceKey)
                            } label: {
                                Label("Clear Filter", systemImage: "bell")
                            }
                            .tint(.gray)
                        }
                    }
                }
            }
        }
    }

    private func nonBlank(_ value: String?) -> String? {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed?.isEmpty == false ? trimmed : nil
    }

    @ViewBuilder
    private func iosDeviceSubtitle(_ device: FilteredIosDeviceRecord) -> some View {
        let peer = peerName(device.peerClientId)
        if let originDeviceId = nonBlank(device.originDeviceId) {
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(verbatim: originDeviceId)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                Text(verbatim: "via \(peer)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } else {
            Text(verbatim: "via \(peer)")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

private struct NotificationPostingLabel: View {
    let isEnabled: Bool

    var body: some View {
        if isEnabled {
            InlineIconLabel("Posting Notifications", systemImage: "bell")
        } else {
            InlineIconLabel("Notifications Silenced", systemImage: "bell.slash")
        }
    }
}

private struct AppFilterSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var runtime: NotiSyncRuntime
    /// The full Inbox is scanned to enumerate the selected device's apps/channels — queried HERE (not in
    /// DevicesView) so the 200-row fetch + scan only happens while the sheet is actually open.
    @Query(sort: \InboxNotification.receivedAt, order: .reverse) private var notifications: [InboxNotification]
    let title: String
    let selection: FilterDeviceSelection

    private var deviceKey: String { selection.deviceKey }
    private var supportsChannels: Bool { selection.supportsChannels }

    private var apps: [AppFilterItem] {
        var items: [String: AppFilterItem] = [:]
        for notification in notifications where selection.matches(notification, runtime: runtime) {
            guard let appId = runtime.filterAppIdentifier(for: notification) else { continue }
            let label = notification.appLabel.trimmingCharacters(in: .whitespacesAndNewlines)
            var item = items[appId] ?? AppFilterItem(
                appId: appId,
                appLabel: label.isEmpty ? appId : label,
                detail: appId,
                channels: [:])
            if selection.supportsChannels,
               let channelId = notification.channelId?.trimmingCharacters(in: .whitespacesAndNewlines),
               !channelId.isEmpty {
                let channelName = notification.channelName?.trimmingCharacters(in: .whitespacesAndNewlines)
                item.channels[channelId] = ChannelFilterItem(
                    channelId: channelId,
                    channelName: channelName?.isEmpty == false ? channelName ?? channelId : channelId)
            }
            items[appId] = item
        }
        for appId in runtime.filteredAppIdentifiers(deviceKey: deviceKey) where items[appId] == nil {
            items[appId] = AppFilterItem(appId: appId, appLabel: appId, detail: appId, channels: [:])
        }
        for appId in runtime.appIdentifiersWithFilteredChannels(deviceKey: deviceKey) where items[appId] == nil {
            items[appId] = AppFilterItem(appId: appId, appLabel: appId, detail: appId, channels: [:])
        }
        for appId in Array(items.keys) {
            var item = items[appId] ?? AppFilterItem(appId: appId, appLabel: appId, detail: appId, channels: [:])
            for channelId in runtime.filteredChannelIdentifiers(deviceKey: deviceKey, appId: appId)
                where item.channels[channelId] == nil {
                item.channels[channelId] = ChannelFilterItem(channelId: channelId, channelName: channelId)
            }
            items[appId] = item
        }
        return items.values.sorted {
            $0.appLabel.localizedCaseInsensitiveCompare($1.appLabel) == .orderedAscending
        }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(apps) { app in
                    Section {
                        Toggle(isOn: Binding(
                            get: { runtime.appNotificationsEnabled(deviceKey: deviceKey, appId: app.appId) },
                            set: { runtime.setAppNotificationsEnabled($0, deviceKey: deviceKey, appId: app.appId) }
                        )) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(verbatim: app.appLabel)
                                    .font(.body.weight(.medium))
                                Text(verbatim: app.detail)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .textSelection(.enabled)
                            }
                        }
                        .swipeActions(edge: .trailing) {
                            if !runtime.appNotificationsEnabled(deviceKey: deviceKey, appId: app.appId) {
                                Button {
                                    runtime.setAppNotificationsEnabled(true, deviceKey: deviceKey, appId: app.appId)
                                } label: {
                                    Label("Clear Filter", systemImage: "trash")
                                }
                            }
                        }

                        if supportsChannels {
                            ForEach(app.channels.values.sorted {
                                $0.channelName.localizedCaseInsensitiveCompare($1.channelName) == .orderedAscending
                            }) { channel in
                                Toggle(isOn: Binding(
                                    get: {
                                        runtime.channelNotificationsEnabled(
                                            deviceKey: deviceKey,
                                            appId: app.appId,
                                            channelId: channel.channelId)
                                    },
                                    set: {
                                        runtime.setChannelNotificationsEnabled(
                                            $0,
                                            deviceKey: deviceKey,
                                            appId: app.appId,
                                            channelId: channel.channelId)
                                    }
                                )) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(verbatim: channel.channelName)
                                            .textSelection(.enabled)
                                        Text(verbatim: channel.channelId)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                            .textSelection(.enabled)
                                    }
                                }
                                .disabled(!runtime.appNotificationsEnabled(deviceKey: deviceKey, appId: app.appId))
                                .swipeActions(edge: .trailing) {
                                    if !runtime.channelNotificationsEnabled(
                                        deviceKey: deviceKey,
                                        appId: app.appId,
                                        channelId: channel.channelId) {
                                        Button {
                                            runtime.setChannelNotificationsEnabled(
                                                true,
                                                deviceKey: deviceKey,
                                                appId: app.appId,
                                                channelId: channel.channelId)
                                        } label: {
                                            Label("Clear Filter", systemImage: "trash")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .listSectionSpacing(.compact)
            .navigationTitle(title)
            .overlay {
                if apps.isEmpty {
                    ContentUnavailableView("No apps yet", systemImage: "app.badge")
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
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
    @EnvironmentObject private var runtime: NotiSyncRuntime
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
            .toolbar {
                if !records.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        // Same native Menu confirmation as the Inbox — one destructive "Delete All N" item.
                        Menu {
                            Button(role: .destructive) {
                                runtime.deleteAllActivity()
                            } label: {
                                Label("Delete All \(records.count) Activities", systemImage: "trash")
                            }
                        } label: {
                            Label("Delete All", systemImage: "trash")
                        }
                    }
                }
            }
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
    @State private var releaseHiddenSettingsUnlocked = SettingsHiddenControlsUnlock.isUnlocked

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
                    if showsHiddenSettings {
                        LabeledContent("Address") {
                            TextField("Address", text: $brokerURL)
                                .textInputAutocapitalization(.never)
                                .keyboardType(.URL)
                                .autocorrectionDisabled()
                                .lineLimit(1)
                                .multilineTextAlignment(.trailing)
                        }
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
                        if showsHiddenSettings, let error = settings.lastError {
                            Text(verbatim: error)
                                .font(.footnote)
                                .foregroundStyle(.red)
                        }
                        if showsHiddenSettings {
                            Button {
                                runtime.simulateLocalStateLossRecovery()
                            } label: {
                                InlineIconLabel("Simulate Reinstall Recovery", systemImage: "arrow.counterclockwise.circle")
                            }
                            Button {
                                runtime.clearBrokerToken()
                            } label: {
                                InlineIconLabel("Remove Client Integrity Token", systemImage: "key.slash")
                            }
                        }
                    }
                }
                Section {
                    if let info = runtime.rotationInfo {
                        LabeledContent("Current epoch", value: "\(info.epoch)")
                        VerificationValueRow("Signing key", value: info.signingKeyFingerprint)
                        VerificationValueRow("Encryption key", value: info.encryptionKeyFingerprint)
                        TimelineView(.periodic(from: .now, by: 60)) { timeline in
                            Text(verbatim: LocalizedText.rotationStatus(info, now: timeline.date))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Button {
                        runtime.rotateNow()
                    } label: {
                        InlineIconLabel("Rotate Now", systemImage: "arrow.triangle.2.circlepath")
                    }
                } header: {
                    Text("Key Rotation")
                } footer: {
                    Text("Forces a key rotation immediately. Normally rotates ~monthly. The old epoch's keys are retained through the overlap so in-flight notifications still decrypt.")
                }
            }
            .navigationTitle("Settings")
            .onAppear { loadSettings(); runtime.refreshRotationInfo() }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    SettingsRefreshToolbarButton(
                        onRefresh: { runtime.refreshForegroundNow() },
                        onRevealHiddenSettings: { revealHiddenSettingsForProcess() })
                }
            }
        }
    }

    private var showsHiddenSettings: Bool {
        #if DEBUG
        true
        #else
        releaseHiddenSettingsUnlocked
        #endif
    }

    private func revealHiddenSettingsForProcess() {
        #if !DEBUG
        SettingsHiddenControlsUnlock.isUnlocked = true
        releaseHiddenSettingsUnlocked = true
        #endif
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

private enum SettingsHiddenControlsUnlock {
    static var isUnlocked = false
}

private struct SettingsRefreshToolbarButton: View {
    let onRefresh: () -> Void
    let onRevealHiddenSettings: () -> Void

    var body: some View {
        Label("Drain", systemImage: "arrow.clockwise")
            .labelStyle(.iconOnly)
            .imageScale(.large)
            .foregroundStyle(.tint)
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .gesture(refreshOrRevealGesture)
            .accessibilityLabel("Drain")
            .accessibilityAddTraits(.isButton)
    }

    private var refreshOrRevealGesture: some Gesture {
        ExclusiveGesture(
            LongPressGesture(minimumDuration: 0.7),
            TapGesture()
        )
        .onEnded { value in
            switch value {
            case .first:
                onRevealHiddenSettings()
            case .second:
                onRefresh()
            }
        }
    }
}
