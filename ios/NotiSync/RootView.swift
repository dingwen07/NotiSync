import SwiftData
import SwiftUI
import UIKit

/// Top-level tabs, tagged so flows (onboarding's "Go to Devices") can select one programmatically.
enum RootTab: Hashable {
    case inbox, devices, activity, settings
}

struct RootView: View {
    @Environment(\.modelContext) private var modelContext
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Query private var settingsRows: [AppSettings]
    @State private var selection: RootTab = .inbox

    /// False until `configure` creates the settings row, so a fresh install starts in onboarding.
    private var hasCompletedOnboarding: Bool {
        settingsRows.first?.hasCompletedOnboarding ?? false
    }

    var body: some View {
        Group {
            if hasCompletedOnboarding {
                TabView(selection: $selection) {
                    InboxView()
                        .tabItem { Label("Inbox", systemImage: "tray.full") }
                        .tag(RootTab.inbox)
                    DevicesView()
                        .tabItem { Label("Devices", systemImage: "iphone.gen3.radiowaves.left.and.right") }
                        .tag(RootTab.devices)
                    ActivityLogView()
                        .tabItem { Label("Activity", systemImage: "waveform.path.ecg") }
                        .tag(RootTab.activity)
                    SettingsView()
                        .tabItem { Label("Settings", systemImage: "gearshape") }
                        .tag(RootTab.settings)
                }
            } else {
                OnboardingView {
                    // Land on Devices — the pairing entry point — when the flow completes.
                    selection = .devices
                    runtime.completeOnboarding()
                }
            }
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
        .fullScreenCover(item: $runtime.screenMirrorPresentation) { source in
            ScreenMirrorPlayerView(sourceId: source.sourceId, sourceName: source.sourceName)
                .environmentObject(runtime)
        }
    }
}

struct InboxView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.modelContext) private var modelContext
    /// The notification opened in the "Show Full Text" sheet — the escape hatch for content taller
    /// than the context-menu preview, plus in-place link taps and text selection (a menu preview is
    /// a non-interactive snapshot, so neither works there).
    @State private var fullTextNotification: InboxNotification?
    /// Paged window over the (unbounded) Inbox — a live `@Query` would materialize every row. Re-fetched
    /// on `runtime.inboxRevision` bumps; extended by the sentinel row as the user scrolls.
    @StateObject private var list = InboxListModel()
    @State private var searchText = ""
    @State private var appFilter: InboxListModel.AppFilter?
    /// The filtered app's icon, resolved once when the filter is applied (the tapped row was already
    /// displaying it, so this is a memoized-cache hit). Nil falls back to a generic filter glyph.
    @State private var appFilterIcon: UIImage?
    /// How far the list is rubber-banded past its top edge. The floating accessory line rides the bounce
    /// with the content — pinned while the list scrolls beneath it, but not sitting detached while
    /// overscrolled rows drag away under it.
    @State private var topOverscroll: CGFloat = 0
    @State private var unreadOnly = false
    @Query private var devices: [TrustedDevice]

    var body: some View {
        NavigationStack {
            List {
                ForEach(list.rows) { notification in
                    InboxRow(notification: notification, sourceDevice: device(for: notification),
                             onTapAppIcon: { toggleAppFilter(for: notification) })
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
                            contextMenuItems(for: notification)
                        } preview: {
                            // The row clamps title/body to 1/2 lines; the preview re-renders them in
                            // full. Explicit environment hand-off: the preview is hosted out-of-
                            // hierarchy, and InboxIconView dereferences the runtime.
                            NotificationPreviewCard(notification: notification,
                                                    sourceDevice: device(for: notification))
                                .environmentObject(runtime)
                        }
                }
                if list.hasMore {
                    // Sentinel: rendering it means the user reached the loaded window's end.
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                    .onAppear { list.loadMore() }
                }
            }
            // Pinned (`.always`) so the field stays visible while scrolling instead of collapsing away
            // with the drawer.
            .searchable(text: $searchText, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: Text("Search notifications"))
            // The accessory line (active-filter pill; red delete-results button while searching) FLOATS
            // over the list (no safe-area inset, no opaque bar): an inset with a solid background under
            // the navigation bar defeated its scroll-edge glass. The content margin keeps the top row
            // readable at rest while still scrolling under the floating elements.
            .overlay(alignment: .top) {
                if appFilter != nil || showsSearchDelete {
                    topAccessoryBar
                }
            }
            .contentMargins(.top, appFilter != nil || showsSearchDelete ? 54 : 0, for: .scrollContent)
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                // At rest contentOffset.y == -contentInsets.top; anything below that is rubber-band.
                max(0, -(geometry.contentOffset.y + geometry.contentInsets.top))
            } action: { _, overscroll in
                topOverscroll = overscroll
            }
            .navigationTitle("Inbox")
            .toolbar {
                // Keep the menu reachable while Unread Only is on (it's the only way to toggle it back off)
                // even if that filter currently matches nothing.
                if list.hasLoaded, list.totalCount > 0 || unreadOnly {
                    ToolbarItem(placement: .topBarTrailing) {
                        inboxMenu
                    }
                }
            }
            .overlay {
                if list.hasLoaded && list.rows.isEmpty {
                    if !searchText.isEmpty {
                        ContentUnavailableView.search(text: searchText)
                    } else if unreadOnly {
                        ContentUnavailableView("No Unread Notifications", systemImage: "checkmark.circle")
                    } else if appFilter != nil {
                        ContentUnavailableView("No Notifications for This App",
                                               systemImage: "line.3.horizontal.decrease.circle")
                    } else {
                        ContentUnavailableView("No mirrored notifications", systemImage: "tray")
                    }
                }
            }
            .sheet(item: $fullTextNotification) { notification in
                NotificationFullTextView(notification: notification, sourceDevice: device(for: notification))
                    .environmentObject(runtime)
            }
            .onAppear { list.configure(context: modelContext, index: runtime.searchIndex) }
            // One task per (searchText, appFilter, unreadOnly) state — a keystroke cancels the previous
            // one, giving the debounce; filter/unread toggles and cleared text apply immediately.
            .task(id: queryKey) {
                if !searchText.isEmpty, searchText != list.appliedSearch {
                    try? await Task.sleep(for: .milliseconds(250))
                    guard !Task.isCancelled else { return }
                }
                await list.apply(search: searchText, filter: appFilter, unreadOnly: unreadOnly)
            }
            .onChange(of: runtime.inboxRevision) { _, _ in
                list.refreshAfterChange()
                // A bulk delete (Delete All resolves against live models) can free the row the
                // full-text sheet is holding; touching a deleted model's properties crashes.
                if fullTextNotification?.isDeleted == true {
                    fullTextNotification = nil
                }
            }
        }
    }

    /// The Inbox actions menu. All/Unread is a picker (checkmark on the active choice, All by default).
    /// Action scope tracks what the list shows: Mark as Read and Delete All follow the active app
    /// filter, and their counts are the counts on screen. Delete All hides while a search or Unread
    /// narrows the list further (the visible subset would belie the count — searches get their own
    /// delete button on the accessory line). The destructive item living one level into the menu is
    /// the second confirmation (the pre-menu design's trash button worked the same way).
    private var inboxMenu: some View {
        Menu {
            // The Picker is implicitly its own menu section — the system draws the split after it, so
            // no explicit Divider (stacking one doubled the section gap).
            Picker("Show", selection: $unreadOnly) {
                Label("All", systemImage: "tray").tag(false)
                Label("Unread", systemImage: "envelope.badge").tag(true)
            }
            if searchText.isEmpty {
                Button {
                    let predicate = InboxListModel.predicate(filter: appFilter, unreadOnly: true)
                    Task { await runtime.markAllAsRead(matching: predicate) }
                } label: {
                    if let appFilter {
                        Label("Mark Notifications from \(appFilter.label) as Read", systemImage: "checkmark.circle")
                    } else {
                        Label("Read All", systemImage: "checkmark.circle")
                    }
                }
                .disabled(list.unreadCount == 0)
            }
            if searchText.isEmpty, !unreadOnly, list.totalCount > 0 {
                Button(role: .destructive) {
                    if let appFilter, let predicate = InboxListModel.predicate(filter: appFilter, unreadOnly: false) {
                        runtime.deleteInboxNotifications(matching: predicate, appKey: appFilter.appKey)
                    } else {
                        runtime.deleteAllInboxNotifications()
                    }
                } label: {
                    if let appFilter {
                        Label("Delete All \(list.totalCount) from \(appFilter.label)", systemImage: "trash")
                    } else {
                        Label("Delete All \(list.totalCount) Notifications", systemImage: "trash")
                    }
                }
            }
        } label: {
            Label("Inbox Options", systemImage: "line.3.horizontal.decrease")
        }
    }

    private var queryKey: String { "\(unreadOnly)\u{1f}\(appFilter?.appKey ?? "")\u{1f}\(searchText)" }

    /// The delete-results button shows only when the search's total is trustworthy: Unread Only narrows
    /// the visible rows below what the sidecar counted, so the pair would advertise one number and
    /// delete another.
    private var showsSearchDelete: Bool {
        InboxSearchIndex.matchExpression(for: searchText) != nil && !unreadOnly && list.totalCount > 0
    }

    /// Floating accessory line under the search field: the active-filter pill (centered) and, while a
    /// search is up, its red delete-results button (trailing). Offset by the rubber-band distance so it
    /// bounces with the content.
    private var topAccessoryBar: some View {
        ZStack {
            if showsSearchDelete {
                HStack {
                    Spacer()
                    searchDeleteMenu
                }
            }
            if let appFilter {
                appFilterPill(appFilter)
                    // Keep the centered pill's truncation clear of the trailing delete button.
                    .padding(.horizontal, showsSearchDelete ? 56 : 0)
            }
        }
        .padding(.top, 6)
        .padding(.horizontal, 16)
        .offset(y: topOverscroll)
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    /// Same double-confirmation shape as the Inbox menu's Delete All: the trash reveals one destructive
    /// item, labeled with the full match count (every result, not just the loaded page).
    private var searchDeleteMenu: some View {
        Menu {
            Button(role: .destructive) {
                let match = InboxSearchIndex.matchExpression(for: searchText)
                let appKey = appFilter?.appKey
                Task { [match, appKey] in
                    guard let match else { return }
                    await runtime.deleteInboxSearchResults(matching: match, appKey: appKey)
                }
            } label: {
                Label("Delete \(list.totalCount) Results", systemImage: "trash")
            }
        } label: {
            Image(systemName: "trash")
                .foregroundStyle(.red)
                .frame(width: 40, height: 40)
                .contentShape(Rectangle())
        }
        .modifier(FloatingGlassCapsule())
        .accessibilityLabel("Delete Search Results")
    }

    private func toggleAppFilter(for notification: InboxNotification) {
        let tapped = InboxListModel.AppFilter(notification)
        if appFilter == tapped {
            clearAppFilter()
            return
        }
        withAnimation { appFilter = tapped }
        appFilterIcon = nil
        Task {
            if let data = await runtime.appIconBytes(for: notification), appFilter == tapped {
                appFilterIcon = UIImage(data: data)
            }
        }
    }

    private func clearAppFilter() {
        withAnimation {
            appFilter = nil
            appFilterIcon = nil
        }
    }

    private func appFilterPill(_ filter: InboxListModel.AppFilter) -> some View {
        HStack(spacing: 6) {
            if let appFilterIcon {
                Image(uiImage: appFilterIcon)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 22, height: 22)
                    .clipShape(RoundedRectangle(cornerRadius: 5))
            } else {
                Image(systemName: "line.3.horizontal.decrease")
                    .imageScale(.small)
            }
            Text(verbatim: filter.label)
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
            Button {
                clearAppFilter()
            } label: {
                // The visible glyph is small; the 36pt frame is the actual tap target.
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Clear App Filter")
        }
        .padding(.leading, 12)
        .padding(.trailing, 2)
        .padding(.vertical, 2)
        .modifier(FloatingGlassCapsule())
    }

    private func device(for notification: InboxNotification) -> TrustedDevice? {
        devices.first { $0.clientId == notification.sourceClientId }
    }

    /// Content actions first (the full-text sheet — where links are live — and copy), then the
    /// silencing scopes.
    @ViewBuilder
    private func contextMenuItems(for notification: InboxNotification) -> some View {
        Section {
            if notification.hasTextContent {
                Button {
                    fullTextNotification = notification
                } label: {
                    Label("Show Full Text", systemImage: "doc.text.magnifyingglass")
                }
                Button {
                    UIPasteboard.general.string = notification.copyableText
                } label: {
                    Label("Copy Text", systemImage: "doc.on.doc")
                }
            }
        }
        Section {
            // Each scope flips to its undo (the Devices tab's "Clear Filter") when it is
            // already silenced, so the same long-press reverses a mis-tap.
            if let channelFilter = androidChannelFilter(for: notification) {
                SilenceMenuButton(
                    isSilenced: !runtime.channelNotificationsEnabled(
                        deviceKey: channelFilter.deviceKey,
                        appId: channelFilter.appId,
                        channelId: channelFilter.channelId),
                    silence: "Silence this Channel",
                    clear: "Clear Channel Filter"
                ) { enabled in
                    runtime.setChannelNotificationsEnabled(
                        enabled,
                        deviceKey: channelFilter.deviceKey,
                        appId: channelFilter.appId,
                        channelId: channelFilter.channelId)
                }
            }
            if let deviceKey = runtime.filterDeviceKey(for: notification),
               let appId = runtime.filterAppIdentifier(for: notification) {
                SilenceMenuButton(
                    isSilenced: !runtime.appNotificationsEnabled(deviceKey: deviceKey, appId: appId),
                    silence: "Silence this App",
                    clear: "Clear App Filter"
                ) { enabled in
                    runtime.setAppNotificationsEnabled(enabled, deviceKey: deviceKey, appId: appId)
                }
            }
            if runtime.canFilterNotificationsLike(notification) {
                SilenceMenuButton(
                    isSilenced: runtime.notificationsLikeAreFiltered(notification),
                    silence: "Silence this Device",
                    clear: "Clear Device Filter"
                ) { enabled in
                    if enabled {
                        runtime.unfilterNotificationsLike(notification)
                    } else {
                        runtime.filterNotificationsLike(notification)
                    }
                }
            }
        }
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

/// One Inbox context-menu silencing item. While the scope (channel/app/device) is posting it offers
/// "Silence …"; once silenced it becomes the undo, using the Devices tab's "Clear Filter" verb and its
/// bell glyph (the un-silenced state there). `setEnabled` receives the new enabled state, matching the
/// runtime's `set…NotificationsEnabled` setters.
private struct SilenceMenuButton: View {
    let isSilenced: Bool
    let silence: LocalizedStringKey
    let clear: LocalizedStringKey
    let setEnabled: (Bool) -> Void

    var body: some View {
        Button {
            setEnabled(isSilenced)
        } label: {
            if isSilenced {
                Label(clear, systemImage: "bell")
            } else {
                Label(silence, systemImage: "bell.slash")
            }
        }
    }
}

/// Liquid-glass capsule for elements floating over scrolling content (the Inbox's active-filter chip):
/// the real `glassEffect` on iOS 26, a material capsule on earlier systems (the app deploys to 18.6).
private struct FloatingGlassCapsule: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular.interactive(), in: .capsule)
        } else {
            content.background(.regularMaterial, in: Capsule())
        }
    }
}

struct InboxRow: View {
    let notification: InboxNotification
    let sourceDevice: TrustedDevice?
    /// Tap on the app icon — the Inbox filters to that app. The inner gesture wins over the row's
    /// tap-to-dismiss, so the icon is the one non-dismiss tap target in the row.
    var onTapAppIcon: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 12) {
            InboxIconView(notification: notification)
                .onTapGesture { onTapAppIcon?() }
                .accessibilityAddTraits(.isButton)
                .accessibilityLabel("Filter by App")
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
                    if let originLabel = notification.originDisplayLabel(sourceDevice: sourceDevice) {
                        InlineIconLabel(verbatim: originLabel, systemImage: notification.originSystemImage)
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
    @Query private var devices: [TrustedDevice]
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
                let pending = devicesInNameOrder.filter { $0.status == .pendingTrust || $0.status == .pendingRevoke }
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
                    ForEach(devicesInNameOrder.filter { $0.status == .trusted }) { device in
                        TrustedPeerRow(
                            device: device,
                            supportsNotificationFilters: !isIosPeer(device),
                            canMirrorScreen: runtime.screenMirrorSourceIds.contains(device.clientId),
                            openFilters: { selectedFilterDevice = .android(device) },
                            mirrorScreen: {
                                _ = runtime.presentScreenMirror(
                                    sourceId: device.clientId,
                                    fallbackName: device.displayName
                                )
                            }
                        )
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
                PairingView()
                    .environmentObject(runtime)
                    .presentationSizing(.page)
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
        var descriptor = FetchDescriptor<InboxNotification>(
            predicate: #Predicate { $0.originPlatform == ancsRaw },
            sortBy: [SortDescriptor(\.receivedAt, order: .reverse)])
        // Recent history suffices to (re)derive bridged devices — persisted filter records already seed
        // rowsByKey, and the Inbox is unbounded, so cap the scan instead of materializing everything.
        descriptor.fetchLimit = 2000
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

    private var devicesInNameOrder: [TrustedDevice] {
        devices.sorted(by: areTrustedDevicesInDisplayOrder)
    }

    private func areTrustedDevicesInDisplayOrder(_ lhs: TrustedDevice, _ rhs: TrustedDevice) -> Bool {
        let nameOrder = lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName)
        if nameOrder != .orderedSame { return nameOrder == .orderedAscending }
        return lhs.clientId.localizedCaseInsensitiveCompare(rhs.clientId) == .orderedAscending
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
    let canMirrorScreen: Bool
    let openFilters: () -> Void
    let mirrorScreen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 12) {
                DeviceLabel(device: device)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        if supportsNotificationFilters, runtime.androidLocalNotificationsEnabled(for: device.clientId) {
                            openFilters()
                        }
                    }
                if canMirrorScreen {
                    Button(action: mirrorScreen) {
                        Image(systemName: "rectangle.inset.filled.and.person.filled")
                            .font(.body.weight(.semibold))
                            .frame(width: 28, height: 28)
                    }
                    .screenMirrorNativeButton()
                    .buttonBorderShape(.circle)
                    .controlSize(.small)
                    .accessibilityLabel("View Screen")
                    .accessibilityHint(device.displayName.isEmpty
                                       ? "Opens this device in the screen viewer"
                                       : "Opens " + device.displayName + " in the screen viewer")
                }
            }
            if supportsNotificationFilters {
                Toggle(isOn: Binding(
                    get: { runtime.androidLocalNotificationsEnabled(for: device.clientId) },
                    set: { runtime.setAndroidLocalNotificationsEnabled($0, for: device.clientId) }
                )) {
                    NotificationPostingLabel(isEnabled: runtime.androidLocalNotificationsEnabled(for: device.clientId))
                }
                .font(.caption)
                .foregroundStyle(.secondary)
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
                        .frame(maxWidth: .infinity, alignment: .leading)
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
    @Environment(\.modelContext) private var modelContext
    /// The selected device's apps/channels are enumerated from RECENT Inbox history — the Inbox is
    /// unbounded, so the scan is capped rather than materializing every row, and fetched once per
    /// presentation (not live): filter toggles re-render via runtime state, and arrivals while the
    /// sheet is up aren't worth a live query.
    @State private var notifications: [InboxNotification] = []
    private static let recentScanLimit = 2000
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
            .task {
                var descriptor = FetchDescriptor<InboxNotification>(
                    sortBy: [SortDescriptor(\.receivedAt, order: .reverse)])
                descriptor.fetchLimit = Self.recentScanLimit
                notifications = (try? modelContext.fetch(descriptor)) ?? []
            }
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
    @State private var communicationAppIcons = false
    @State private var releaseHiddenSettingsUnlocked = SettingsHiddenControlsUnlock.isUnlocked

    var body: some View {
        NavigationStack {
            Form {
                Section("Onboarding") {
                    Button {
                        runtime.requestNotificationPermissionAndRegister()
                    } label: {
                        Label("Notifications", systemImage: "bell.badge")
                    }
                    Button {
                        runtime.postLocalTestNotification()
                    } label: {
                        Label("Test Local Notification", systemImage: "bell.and.waves.left.and.right")
                    }
                    LabeledContent("Permission", value: LocalizedText.notificationPermission(settingsRows.first?.notificationPermissionValue ?? .unknown))
                    LabeledContent("Pairing", value: LocalizedText.pairingStatus(settingsRows.first?.pairingStatusValue ?? .unpaired))
                }
                Section {
                    Toggle(isOn: Binding(
                        get: { communicationAppIcons },
                        set: { enabled in
                            communicationAppIcons = enabled
                            runtime.setCommunicationAppIconsEnabled(enabled)
                        }
                    )) {
                        Label("Improve Watch Compatibility", systemImage: "applewatch")
                    }
                } header: {
                    Text("Notifications")
                } footer: {
                    Text("Show app icons in a different way that displays better on Apple Watch. When off, app icons appear as image thumbnails.")
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
                        Label("Save", systemImage: "checkmark")
                            .dimmedWhenDisabled()
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
                                Label("Simulate Reinstall Recovery", systemImage: "arrow.counterclockwise.circle")
                            }
                            Button {
                                runtime.clearBrokerToken()
                            } label: {
                                Label("Remove Client Integrity Token", systemImage: "key.slash")
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
                        Label("Rotate Now", systemImage: "arrow.triangle.2.circlepath")
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
        communicationAppIcons = runtime.communicationAppIconsEnabled()
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
