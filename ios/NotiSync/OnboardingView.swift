import SwiftUI
import UserNotifications

/// First-launch onboarding: welcome → notification permission → pair-your-devices guidance.
/// iOS only mirrors and syncs dismissals (no capture, no ANCS), so the notification permission is
/// the only setup this platform needs before pairing. `onFinish` runs when the user completes the
/// flow; RootView persists the flag and lands on the Devices tab.
struct OnboardingView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.scenePhase) private var scenePhase
    let onFinish: () -> Void

    private enum Step: Int, CaseIterable {
        case welcome, notifications, pair
    }

    @State private var step: Step = .welcome
    @State private var permissionStatus: UNAuthorizationStatus = .notDetermined
    @State private var requestingPermission = false

    private var permissionGranted: Bool {
        switch permissionStatus {
        case .authorized, .provisional, .ephemeral: true
        default: false
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            ZStack {
                switch step {
                case .welcome: welcomePage.transition(.blurReplace)
                case .notifications: notificationsPage.transition(.blurReplace)
                case .pair: pairPage.transition(.blurReplace)
                }
            }
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            dots
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 8)
        .task { await refreshPermissionStatus() }
        // Re-check when coming back from the Settings app after "Open Settings".
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await refreshPermissionStatus() } }
        }
    }

    // MARK: Chrome

    private var header: some View {
        HStack {
            if step != .welcome {
                Button {
                    advance(to: Step(rawValue: step.rawValue - 1) ?? .welcome)
                } label: {
                    Image(systemName: "chevron.backward")
                        .font(.body.weight(.semibold))
                }
                .accessibilityLabel("Back")
            }
            Spacer()
        }
        .frame(height: 44)
    }

    private var dots: some View {
        HStack(spacing: 8) {
            ForEach(Step.allCases, id: \.rawValue) { s in
                Circle()
                    .fill(s == step ? AnyShapeStyle(.tint) : AnyShapeStyle(.quaternary))
                    .frame(width: 8, height: 8)
            }
        }
        .padding(.vertical, 8)
    }

    // MARK: Pages

    private var welcomePage: some View {
        OnboardingPage(
            icon: "iphone.gen3.radiowaves.left.and.right",
            title: "Welcome to NotiSync",
            message: "See notifications from your other devices on this iPhone — private and end-to-end encrypted."
        ) {
            EmptyView()
        } actions: {
            primaryButton("Get Started") { advance(to: .notifications) }
            skipSlot(nil)
        }
    }

    private var notificationsPage: some View {
        OnboardingPage(
            icon: "bell.badge",
            title: "Show Notifications Here",
            message: "NotiSync needs permission to show notifications from your other devices on this iPhone."
        ) {
            if permissionGranted {
                statusLabel("Notifications are on.", systemImage: "checkmark.circle.fill")
            } else if permissionStatus == .denied {
                statusLabel("Notifications are off. You can turn them on in Settings.",
                            systemImage: "bell.slash")
            }
        } actions: {
            if permissionGranted {
                primaryButton("Continue") { advance(to: .pair) }
                skipSlot(nil)
            } else if permissionStatus == .denied {
                primaryButton("Open Settings") { openSystemSettings() }
                skipSlot { advance(to: .pair) }
            } else {
                primaryButton("Allow", busy: requestingPermission) { requestPermission() }
                skipSlot { advance(to: .pair) }
            }
        }
    }

    private var pairPage: some View {
        OnboardingPage(
            icon: "qrcode",
            title: "Connect Your Other Devices",
            message: "Install NotiSync on your other devices, then pair them from the Devices tab."
        ) {
            EmptyView()
        } actions: {
            primaryButton("Done") { onFinish() }
            skipSlot(nil)
        }
    }

    // MARK: Pieces

    private func primaryButton(_ title: LocalizedStringKey, busy: Bool = false,
                               action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if busy { ProgressView() }
                Text(title)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(busy)
    }

    /// Fixed-height slot below the primary button so it doesn't jump between steps with and
    /// without a skip option.
    private func skipSlot(_ action: (() -> Void)?) -> some View {
        ZStack {
            if let action {
                Button("Skip", action: action)
                    .font(.callout)
            }
        }
        .frame(height: 44)
    }

    private func statusLabel(_ text: LocalizedStringKey, systemImage: String) -> some View {
        Label(text, systemImage: systemImage)
            .font(.callout.weight(.medium))
            .foregroundStyle(.tint)
            .multilineTextAlignment(.center)
            .padding(.top, 16)
    }

    // MARK: Actions

    private func advance(to next: Step) {
        withAnimation(.snappy) { step = next }
    }

    private func requestPermission() {
        guard !requestingPermission else { return }
        requestingPermission = true
        Task {
            let granted = await runtime.requestNotificationPermissionAndRegisterAsync()
            await refreshPermissionStatus()
            requestingPermission = false
            if granted { advance(to: .pair) }
        }
    }

    private func refreshPermissionStatus() async {
        permissionStatus =
            await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    private func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

/// One onboarding step: centered icon badge + title + message (+ optional status), actions pinned
/// at the bottom.
private struct OnboardingPage<Status: View, Actions: View>: View {
    let icon: String
    let title: LocalizedStringKey
    let message: LocalizedStringKey
    @ViewBuilder var status: () -> Status
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Image(systemName: icon)
                .font(.system(size: 52, weight: .medium))
                .foregroundStyle(.tint)
                .frame(width: 104, height: 104)
                .background(.tint.opacity(0.12), in: Circle())
            Text(title)
                .font(.largeTitle.bold())
                .multilineTextAlignment(.center)
                .padding(.top, 28)
            Text(message)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.top, 12)
            status()
            Spacer()
            actions()
        }
    }
}
