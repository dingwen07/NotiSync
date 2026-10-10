import NetworkExtension
import SwiftUI

enum HotspotText {
    static var title: String { String(localized: "hotspot.title", defaultValue: "Wi-Fi Hotspot") }
    static var refresh: String { String(localized: "hotspot.refresh", defaultValue: "Refresh") }
    static var turnOn: String { String(localized: "hotspot.turnOn", defaultValue: "Turn on hotspot") }
    static var turnOff: String { String(localized: "hotspot.turnOff", defaultValue: "Turn off hotspot") }
    static var savedNetwork: String { String(localized: "hotspot.savedNetwork", defaultValue: "Saved network") }
    static var qr: String { String(localized: "hotspot.qr", defaultValue: "Wi-Fi QR Code") }

    static func state(_ state: HotspotState?) -> String {
        switch state {
        case .ENABLED: String(localized: "hotspot.state.on", defaultValue: "On")
        case .DISABLED: String(localized: "hotspot.state.off", defaultValue: "Off")
        case .ENABLING: String(localized: "hotspot.state.starting", defaultValue: "Turning on…")
        case .DISABLING: String(localized: "hotspot.state.stopping", defaultValue: "Turning off…")
        case .FAILED: String(localized: "hotspot.state.failed", defaultValue: "Hotspot failed")
        case .UNKNOWN, nil: String(localized: "hotspot.state.unknown", defaultValue: "Status unknown")
        }
    }

    static func failure(_ result: HotspotResult?) -> String? {
        switch result {
        case .OK, nil: nil
        case .UNAUTHORIZED:
            String(localized: "hotspot.error.permission", defaultValue: "Allow screen sharing for this device on the Android device to use its hotspot.")
        case .UNSUPPORTED:
            String(localized: "hotspot.error.unsupported", defaultValue: "This device does not support remote hotspot control.")
        case .UNAVAILABLE:
            String(localized: "hotspot.error.unavailable", defaultValue: "Hotspot control is unavailable. Check the connection and Shizuku on the Android device.")
        case .TIMEOUT, .EXPIRED:
            String(localized: "hotspot.error.timeout", defaultValue: "The hotspot request timed out. Refresh to try again.")
        case .BUSY:
            String(localized: "hotspot.error.busy", defaultValue: "The hotspot is busy. Try again shortly.")
        case .FAILED:
            String(localized: "hotspot.error.failed", defaultValue: "The hotspot request failed. Refresh to try again.")
        }
    }
}

struct HotspotView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.dismiss) private var dismiss
    let deviceId: String
    let deviceName: String
    @State private var revealPassword = false
    @State private var showingQR = false
    @State private var joining = false
    @State private var joinMessage: String?

    private var remote: RemoteHotspotState { runtime.remoteHotspots[deviceId] ?? RemoteHotspotState() }
    private var saved: SavedHotspot? {
        if let status = remote.status, let snapshot = status.snapshot,
           let live = SavedHotspot(snapshot: snapshot, updatedAt: status.issuedAt) { return live }
        return runtime.savedHotspots[deviceId]
    }
    private var supported: Bool { runtime.hotspotProviderIds.contains(deviceId) }
    private var statusAvailable: Bool {
        remote.failure == nil && remote.status?.result == .OK
            && (remote.status?.snapshot?.state == .ENABLED || remote.status?.snapshot?.state == .DISABLED)
    }
    private var enabled: Bool {
        statusAvailable && remote.status?.snapshot?.state == .ENABLED
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 12) {
                        Image(systemName: "personalhotspot")
                            .font(.system(size: 36, weight: .medium))
                            .foregroundStyle(enabled ? .green : .secondary)
                            .frame(width: 76, height: 76)
                            .background(enabled ? Color.green.opacity(0.12) : Color.secondary.opacity(0.1), in: .circle)
                            .accessibilityLabel(HotspotText.title)
                            .accessibilityValue(HotspotText.state(remote.failure == nil ? remote.status?.snapshot?.state : nil))
                        Text(deviceName).font(.headline).multilineTextAlignment(.center)
                        if remote.pendingRequest != nil || !statusAvailable {
                            HStack(spacing: 8) {
                                if remote.pendingRequest != nil { ProgressView().controlSize(.small) }
                                if !statusAvailable {
                                    Text(HotspotText.state(remote.failure == nil ? remote.status?.snapshot?.state : nil))
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .listRowSeparator(.hidden)
                    Toggle(HotspotText.title, isOn: Binding(
                        get: { enabled },
                        set: { runtime.setHotspotEnabled($0, deviceId: deviceId) }
                    ))
                    .disabled(!supported || !remote.canToggle)
                    Button { runtime.refreshHotspot(deviceId) } label: {
                        Label(HotspotText.refresh, systemImage: "arrow.clockwise")
                    }
                    .disabled(!supported || remote.pendingRequest != nil)
                    // Match Android: missing settled status or usable credentials exposes recovery actions.
                    // A pending request alone does not reveal them when status and credentials are known.
                    if !statusAvailable || saved?.usable != true {
                        Button { runtime.setHotspotEnabled(true, deviceId: deviceId) } label: {
                            Label(HotspotText.turnOn, systemImage: "personalhotspot")
                        }
                        .disabled(!supported || remote.pendingRequest != nil)
                        Button { runtime.setHotspotEnabled(false, deviceId: deviceId) } label: {
                            Label(HotspotText.turnOff, systemImage: "power")
                        }
                        .disabled(!supported || remote.pendingRequest != nil)
                    }
                }
                if let error = HotspotText.failure(remote.failure ?? remote.status?.result) {
                    Section { Label(error, systemImage: "exclamationmark.circle").foregroundStyle(.orange) }
                }
                if runtime.hotspotStorageFailures.contains(deviceId) {
                    Section {
                        Text(String(localized: "hotspot.storageFailed", defaultValue: "Could not access saved hotspot details. Unlock this device and refresh to try again."))
                            .foregroundStyle(.orange)
                    }
                }
                Section {
                    if let saved {
                        LabeledContent(String(localized: "hotspot.networkName", defaultValue: "Name")) {
                            Text(saved.ssid).textSelection(.enabled)
                        }
                        if let password = saved.psk {
                            HStack {
                                Text(String(localized: "hotspot.password", defaultValue: "Password"))
                                Spacer()
                                Text(revealPassword ? password : "••••••••")
                                    .font(.body.monospaced()).foregroundStyle(.secondary)
                                Button { revealPassword.toggle() } label: {
                                    Image(systemName: revealPassword ? "eye.slash" : "eye")
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(revealPassword
                                    ? String(localized: "hotspot.hidePassword", defaultValue: "Hide password")
                                    : String(localized: "hotspot.showPassword", defaultValue: "Show password"))
                            }
                        }
                        Button { Task { await saveAndConnect(saved) } } label: {
                            HStack {
                                Label(String(localized: "hotspot.saveAndConnect", defaultValue: "Connect"), systemImage: "wifi")
                                if joining { Spacer(); ProgressView() }
                            }
                        }
                        .disabled(joining || !saved.canSaveAndConnect)
                        Button { showingQR = true } label: { Label(HotspotText.qr, systemImage: "qrcode") }
                        if !saved.canSaveAndConnect {
                            Text(String(localized: "hotspot.joinUnsupported", defaultValue: "For this security type, use the QR code or enter the saved details in Wi-Fi Settings."))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    } else {
                        ContentUnavailableView {
                            Label(HotspotText.savedNetwork, systemImage: "wifi")
                        } description: {
                            Text(String(localized: "hotspot.noCredentials", defaultValue: "Refresh to receive the network name and password from this device."))
                        }
                    }
                } header: {
                    Text(HotspotText.savedNetwork)
                } footer: {
                    Text(String(localized: "hotspot.savedHint", defaultValue: "Details are saved securely on this device and remain available while the hotspot is offline."))
                }
            }
            .navigationTitle(HotspotText.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task(id: deviceId) { await runtime.openHotspotControls(deviceId) }
            .sheet(isPresented: $showingQR) {
                if let saved { HotspotQRView(saved: saved) }
            }
            .alert(HotspotText.title, isPresented: Binding(
                get: { joinMessage != nil }, set: { if !$0 { joinMessage = nil } }
            )) { Button("OK", role: .cancel) { joinMessage = nil } } message: { Text(joinMessage ?? "") }
        }
    }

    private func saveAndConnect(_ saved: SavedHotspot) async {
        guard saved.canSaveAndConnect else { return }
        joining = true
        defer { joining = false }
        let config: NEHotspotConfiguration
        if let psk = saved.psk { config = NEHotspotConfiguration(ssid: saved.ssid, passphrase: psk, isWEP: false) }
        else { config = NEHotspotConfiguration(ssid: saved.ssid) }
        config.hidden = saved.hiddenSsid
        config.joinOnce = false
        do {
            try await NEHotspotConfigurationManager.shared.apply(config)
        } catch {
            let error = error as NSError
            if error.domain == NEHotspotConfigurationErrorDomain {
                if error.code == NEHotspotConfigurationError.userDenied.rawValue { return }
                if error.code == NEHotspotConfigurationError.alreadyAssociated.rawValue {
                    joinMessage = String(localized: "hotspot.alreadyConnected", defaultValue: "Already connected to this network.")
                    return
                }
            }
            joinMessage = String(localized: "hotspot.joinFailed", defaultValue: "Could not save or join this network. Check Wi-Fi Settings and try again.")
            return
        }
        // Applying a configuration does not prove association or Internet connectivity.
        joinMessage = String(localized: "hotspot.joinSaved", defaultValue: "Wi-Fi configuration saved. Check Wi-Fi Settings to confirm the connection.")
    }
}

private struct HotspotQRView: View {
    @Environment(\.dismiss) private var dismiss
    let saved: SavedHotspot
    @State private var qrImage: CGImage?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    Text(saved.ssid).font(.title2.bold()).multilineTextAlignment(.center)
                    if let qrImage {
                        Image(decorative: qrImage, scale: 1).interpolation(.none).resizable().scaledToFit()
                            .padding(24).background(.white, in: .rect(cornerRadius: 24))
                            .frame(maxWidth: 340)
                            .accessibilityLabel(HotspotText.qr)
                    } else { ProgressView().frame(height: 280) }
                    Text(String(localized: "hotspot.qrHint", defaultValue: "Scan this code on another device to join the hotspot. The code includes the saved Wi-Fi password."))
                        .multilineTextAlignment(.center).foregroundStyle(.secondary)
                }.padding(24).frame(maxWidth: .infinity)
            }
            .navigationTitle(HotspotText.qr).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task(id: saved.updatedAt) {
                if let payload = saved.qrPayload { qrImage = await PairingView.qrCGImage(payload) }
            }
        }
    }
}
