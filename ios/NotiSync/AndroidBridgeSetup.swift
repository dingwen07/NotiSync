import Combine
import CoreBluetooth
import SwiftUI
import UIKit

/// Maintains only the Android accessories explicitly selected for notification sharing.
@MainActor
final class AndroidBridgeSetup: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    static let shared = AndroidBridgeSetup()
    private static let service = CBUUID(string: "d87b3910-72fa-48df-8ab7-6ae96fc3fb51")
    private static let version = CBUUID(string: "d87b3911-72fa-48df-8ab7-6ae96fc3fb51")
    private static let reconnect = CBUUID(string: "d87b3912-72fa-48df-8ab7-6ae96fc3fb51")
    private let selectionKey = "androidBluetoothBridges"
    private let restoreID = "net.extrawdw.notisync.android-bridge"

    enum Phase { case connecting, needsSharing, authorized, failed }
    @Published private(set) var state: CBManagerState = .unknown
    @Published private(set) var selected: [UUID: String] = [:]
    @Published private(set) var phases: [UUID: Phase] = [:]
    @Published private(set) var nearby: [UUID: String] = [:]
    private var central: CBCentralManager?
    private var devices: [UUID: CBPeripheral] = [:]
    private var versionRead: Set<UUID> = []
    private var retryTasks: [UUID: Task<Void, Never>] = [:]
    private var retryDelays: [UUID: Int] = [:]
    private var visible = false
    private var scanningWhileVisible: Bool?
    private var lastSetupAdvertisement: [UUID: ContinuousClock.Instant] = [:]

    private override init() {
        super.init()
        let defaults = UserDefaults.standard
        if let saved = defaults.dictionary(forKey: selectionKey) as? [String: String] {
            for (key, name) in saved {
                if let id = UUID(uuidString: key) { selected[id] = name }
            }
        } else if let old = defaults.string(forKey: "androidBluetoothBridgePeripheral").flatMap(UUID.init(uuidString:)) {
            selected[old] = ""
            persist()
        }
    }

    private func persist() {
        UserDefaults.standard.set(Dictionary(uniqueKeysWithValues: selected.map { ($0.key.uuidString, $0.value) }), forKey: selectionKey)
        UserDefaults.standard.removeObject(forKey: "androidBluetoothBridgePeripheral")
    }

    func resumeSelection() { if !selected.isEmpty { startCentral() } }

    func present(_ presented: Bool) {
        visible = presented
        if presented { startCentral() }
        else {
            nearby.removeAll()
            lastSetupAdvertisement.removeAll()
            devices = devices.filter { selected[$0.key] != nil }
        }
        updateScan()
    }

    private func startCentral() {
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main, options: [
                CBCentralManagerOptionRestoreIdentifierKey: restoreID,
                CBCentralManagerOptionShowPowerAlertKey: false,
            ])
        }
    }

    private func updateScan() {
        guard let central, central.state == .poweredOn else { return }
        let needed = visible || selected.keys.contains { devices[$0] == nil || retryTasks[$0] != nil }
        if !needed {
            if central.isScanning { central.stopScan() }
            return
        }
        guard !central.isScanning || scanningWhileVisible != visible else { return }
        if central.isScanning { central.stopScan() }
        central.scanForPeripherals(withServices: [Self.service, Self.reconnect],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: visible])
        scanningWhileVisible = visible
    }

    // Core Bluetooth reports discoveries but has no callback when a peripheral stops advertising.
    func expireNearby() {
        let cutoff = ContinuousClock.now - .seconds(10)
        for (id, lastSeen) in lastSetupAdvertisement where lastSeen < cutoff {
            lastSetupAdvertisement.removeValue(forKey: id)
            nearby.removeValue(forKey: id)
            if selected[id] == nil { devices.removeValue(forKey: id) }
        }
    }

    func choose(_ id: UUID) {
        guard let peer = devices[id], let name = nearby.removeValue(forKey: id) else { return }
        lastSetupAdvertisement.removeValue(forKey: id)
        selected[id] = name
        persist()
        connect(peer)
    }

    func retry(_ id: UUID) {
        if let peer = devices[id] ?? central?.retrievePeripherals(withIdentifiers: [id]).first { connect(peer) }
        else { updateScan() }
    }

    func forget(_ id: UUID) {
        retryTasks.removeValue(forKey: id)?.cancel()
        retryDelays.removeValue(forKey: id)
        selected.removeValue(forKey: id)
        phases.removeValue(forKey: id)
        versionRead.remove(id)
        persist()
        if let peer = devices.removeValue(forKey: id) { central?.cancelPeripheralConnection(peer) }
        // Reset duplicate filtering so an accessory that never left radio range can be chosen again.
        nearby.removeAll()
        lastSetupAdvertisement.removeAll()
        if central?.isScanning == true { central?.stopScan() }
        updateScan()
    }

    private func connect(_ peer: CBPeripheral) {
        let id = peer.identifier
        guard selected[id] != nil, let central, central.state == .poweredOn else { return }
        retryTasks.removeValue(forKey: id)?.cancel()
        devices[id] = peer
        peer.delegate = self
        phases[id] = .connecting
        if selected[id]?.isEmpty == true, let name = peer.name { selected[id] = name; persist() }
        switch peer.state {
        case .connected: discover(peer)
        case .disconnected:
            central.connect(peer, options: [CBConnectPeripheralOptionRequiresANCS: true,
                                            CBConnectPeripheralOptionEnableAutoReconnect: true])
        default: break
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        state = central.state
        guard state == .poweredOn else {
            for task in retryTasks.values { task.cancel() }
            retryTasks.removeAll(); retryDelays.removeAll()
            nearby.removeAll(); versionRead.removeAll(); phases.removeAll(); devices.removeAll()
            lastSetupAdvertisement.removeAll()
            scanningWhileVisible = nil
            return
        }
        for peer in central.retrievePeripherals(withIdentifiers: Array(selected.keys)) { devices[peer.identifier] = peer }
        for id in selected.keys { if let peer = devices[id] { connect(peer) } }
        updateScan()
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        for peer in dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? [] {
            if selected[peer.identifier] != nil {
                devices[peer.identifier] = peer
                peer.delegate = self
            } else { central.cancelPeripheralConnection(peer) }
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peer: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let id = peer.identifier
        if selected[id] != nil {
            if devices[id] == nil || retryTasks[id] != nil { connect(peer); updateScan() }
        } else if visible {
            let advertised = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
            guard advertised.contains(Self.service) && !advertised.contains(Self.reconnect) else {
                if lastSetupAdvertisement.removeValue(forKey: id) != nil {
                    devices.removeValue(forKey: id)
                    nearby.removeValue(forKey: id)
                }
                return
            }
            lastSetupAdvertisement[id] = .now
            devices[id] = peer
            let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peer.name ?? ""
            if nearby[id] != name { nearby[id] = name }
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peer: CBPeripheral) {
        guard selected[peer.identifier] != nil else { central.cancelPeripheralConnection(peer); return }
        retryTasks.removeValue(forKey: peer.identifier)?.cancel()
        retryDelays.removeValue(forKey: peer.identifier)
        updateScan()
        discover(peer)
    }

    func peripheral(_ peer: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        if selected[peer.identifier] != nil && invalidatedServices.contains(where: { $0.uuid == Self.service }) {
            discover(peer)
        }
    }

    private func discover(_ peer: CBPeripheral) {
        versionRead.remove(peer.identifier)
        peer.delegate = self
        peer.discoverServices([Self.service])
    }

    func peripheral(_ peer: CBPeripheral, didDiscoverServices error: Error?) {
        guard selected[peer.identifier] != nil else { return }
        guard error == nil, let service = peer.services?.first(where: { $0.uuid == Self.service }) else {
            phases[peer.identifier] = .failed; return
        }
        peer.discoverCharacteristics([Self.version], for: service)
    }

    func peripheral(_ peer: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard selected[peer.identifier] != nil else { return }
        guard error == nil, let version = service.characteristics?.first(where: { $0.uuid == Self.version }) else {
            phases[peer.identifier] = .failed; return
        }
        peer.readValue(for: version) // This read requires link encryption and initiates LE pairing.
    }

    func peripheral(_ peer: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard selected[peer.identifier] != nil, characteristic.uuid == Self.version else { return }
        guard error == nil, characteristic.value == Data([1]) else { phases[peer.identifier] = .failed; return }
        versionRead.insert(peer.identifier)
        updateAuthorization(peer)
    }

    private func updateAuthorization(_ peer: CBPeripheral) {
        if versionRead.contains(peer.identifier) && selected[peer.identifier] != nil {
            phases[peer.identifier] = peer.ancsAuthorized ? .authorized : .needsSharing
        }
    }

    func centralManager(_ central: CBCentralManager, didUpdateANCSAuthorizationFor peer: CBPeripheral) { updateAuthorization(peer) }

    func centralManager(_ central: CBCentralManager, didFailToConnect peer: CBPeripheral, error: Error?) {
        let id = peer.identifier
        guard selected[id] != nil, central.state == .poweredOn, retryTasks[id] == nil else { return }
        phases[id] = .failed
        let delay = retryDelays[id] ?? 2
        retryDelays[id] = min(60, delay * 2)
        retryTasks[id] = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(delay)) } catch { return }
            self?.connect(peer)
            self?.updateScan()
        }
        // A filtered scan can wake the app if iOS suspends it during the retry delay.
        updateScan()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peer: CBPeripheral,
                        timestamp: CFAbsoluteTime, isReconnecting: Bool, error: Error?) {
        guard selected[peer.identifier] != nil else { return }
        versionRead.remove(peer.identifier)
        phases[peer.identifier] = .connecting
        // Keep a connection request registered with Core Bluetooth when the system hands retries back.
        if !isReconnecting { connect(peer) }
    }
}

struct AndroidBridgeSetupView: View {
    @ObservedObject private var setup = AndroidBridgeSetup.shared

    var body: some View {
        List {
            if !setup.selected.isEmpty {
                Section("Selected devices") {
                    ForEach(ordered(setup.selected), id: \.self) { id in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(alignment: .center, spacing: 10) {
                                Image(systemName: "iphone")
                                    .foregroundStyle(.tint)
                                    .frame(width: 22)
                                Text(verbatim: label(setup.selected[id], id))
                                    .font(.body.weight(.medium))
                                    .lineLimit(1)
                                Spacer(minLength: 8)
                                if setup.state == .poweredOn && setup.phases[id] == .failed {
                                    Button("Retry") { setup.retry(id) }
                                        .buttonStyle(.bordered)
                                        .controlSize(.small)
                                }
                                Button("Forget", role: .destructive) { setup.forget(id) }
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                            }
                            selectedStatusRow(for: id)
                                .padding(.leading, 32)
                        }
                        .padding(.vertical, 4)
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                setup.forget(id)
                            } label: {
                                Label("Forget", systemImage: "trash")
                            }
                        }
                    }
                }
            }
            Section {
                switch setup.state {
                case .unauthorized:
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Allow Bluetooth access for NotiSync in Settings on this \(HostFormFactor.current.displayName).")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Button("Open Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                        }
                    }
                    .padding(.vertical, 2)
                case .poweredOff:
                    Label("Turn on Bluetooth to connect to Android.", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                case .unsupported:
                    Text("Bluetooth is unavailable on this device.")
                        .foregroundStyle(.secondary)
                case .poweredOn:
                    if setup.nearby.isEmpty {
                        Text("Looking for Android devices…")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(ordered(setup.nearby), id: \.self) { id in
                        Button {
                            setup.choose(id)
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "iphone")
                                    .foregroundStyle(.secondary)
                                    .frame(width: 22)
                                Text(verbatim: label(setup.nearby[id], id))
                                    .foregroundStyle(.primary)
                                Spacer()
                                Text("Connect")
                                    .font(.subheadline)
                                    .foregroundStyle(.tint)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                default:
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Checking Bluetooth…")
                            .foregroundStyle(.secondary)
                    }
                }
            } header: {
                HStack(spacing: 8) {
                    Text("Nearby devices")
                    if setup.state == .poweredOn {
                        ProgressView().controlSize(.small)
                    }
                }
            } footer: {
                Text("On Android, open NotiSync’s iPhone tab, turn on the bridge, and choose NotiSync iOS App under Pair your iPhone if shown.")
            }
        }
        .navigationTitle("Android Bluetooth Bridge")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { setup.present(true) }
        .onDisappear { setup.present(false) }
        .task {
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(2)) } catch { return }
                setup.expireNearby()
            }
        }
    }

    @ViewBuilder
    private func selectedStatusRow(for id: UUID) -> some View {
        if setup.state != .poweredOn {
            Text("Waiting for Bluetooth…")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        } else {
            switch setup.phases[id] ?? .connecting {
            case .connecting:
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text("Connecting…")
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            case .needsSharing:
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .foregroundStyle(.orange)
                    Text("Open Bluetooth in Settings on this \(HostFormFactor.current.displayName), select the info button next to this device, and turn on Share System Notifications.")
                        .foregroundStyle(.secondary)
                }
                .font(.subheadline)
            case .authorized:
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                    Text("Notification sharing is on.")
                        .foregroundStyle(.secondary)
                }
                .font(.subheadline)
            case .failed:
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text("Connection failed. Retrying automatically…")
                        .foregroundStyle(.secondary)
                }
                .font(.subheadline)
            }
        }
    }

    private func label(_ name: String?, _ id: UUID) -> String {
        name.flatMap { $0.isEmpty ? nil : $0 } ?? String(id.uuidString.suffix(8))
    }

    private func ordered(_ names: [UUID: String]) -> [UUID] {
        names.keys.sorted { (label(names[$0], $0), $0.uuidString) < (label(names[$1], $1), $1.uuidString) }
    }
}
