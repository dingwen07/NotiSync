import Foundation
import Darwin

// Host-only fixture. The runner compiles the production store and wire models beside this file.
// Only the App Group URL resolver is substituted; no persistence or locking implementation is copied.
// macOS exercises file/lock semantics, not iOS suspension, entitlements, or RunningBoard termination.
nonisolated enum ProbeResolver {
    static let root = URL(fileURLWithPath: ProcessInfo.processInfo.environment["STORE_TEST_ROOT"]!)
    static var remainingFailures = Int(ProcessInfo.processInfo.environment["STORE_TEST_NIL_RESOLUTIONS"] ?? "0")!
    static var calls = 0

    static func resolve() -> URL? {
        calls += 1
        if remainingFailures > 0 {
            remainingFailures -= 1
            return nil
        }
        return root
    }
}

@main
struct NotificationFilterStoreHostTests {
    static let name = AppGroupStore.Files.notificationFilters

    static func require(_ condition: @autoclosure () -> Bool, _ message: String) {
        guard condition() else {
            FileHandle.standardError.write(Data("FAIL: \(message)\n".utf8))
            exit(1)
        }
    }

    static func signal(_ value: String) {
        print(value)
        fflush(stdout)
    }

    static func waitForParent() {
        require(readLine() != nil, "parent closed the synchronization pipe")
    }

    static func snapshot(_ marker: String) -> NotificationFilterPreferences {
        NotificationFilterPreferences(
            filteredAndroidLocalPeerIds: Set((0..<32).map { "\(marker)-android-\($0)" }),
            iosDevices: [FilteredIosDeviceRecord(
                peerClientId: "\(marker)-peer", originDeviceId: marker,
                deviceName: marker, updatedAt: 123
            )],
            filteredIosDeviceKeys: ["\(marker)-ios"],
            filteredAppIdsByDeviceKey: ["device": Set((0..<32).map { "\(marker)-app-\($0)" })],
            filteredChannelIdsByDeviceAppKey: ["device\u{1f}app": ["\(marker)-channel"]]
        )
    }

    static func equals(_ value: NotificationFilterPreferences, _ expected: NotificationFilterPreferences) -> Bool {
        value.filteredAndroidLocalPeerIds == expected.filteredAndroidLocalPeerIds
            && value.iosDevices == expected.iosDevices
            && value.filteredIosDeviceKeys == expected.filteredIosDeviceKeys
            && value.filteredAppIdsByDeviceKey == expected.filteredAppIdsByDeviceKey
            && value.filteredChannelIdsByDeviceAppKey == expected.filteredChannelIdsByDeviceAppKey
    }

    static func main() throws {
        let args = Array(CommandLine.arguments.dropFirst())
        switch args[0] {
        case "seed":
            require(AppGroupStore.write(snapshot(args[1]), name), "seed write failed")
        case "read":
            signal("READY")
            require(equals(NotificationFilterStore.preferences(), snapshot("A")), "reader lost the seeded snapshot")
        case "hold":
            AppGroupStore.withLock(name) {
                signal("LOCKED")
                waitForParent()
            }
        case "atomic-writer":
            signal("READY")
            waitForParent()
            for index in 0..<Int(args[1])! {
                require(AppGroupStore.write(snapshot(index.isMultiple(of: 2) ? "B" : "A"), name), "atomic write failed")
                usleep(100)
            }
            try Data().write(to: ProbeResolver.root.appendingPathComponent("writer-done"))
        case "atomic-reader":
            let a = snapshot("A")
            let b = snapshot("B")
            var reads = 0
            var seenA = false
            var seenB = false
            signal("READY")
            waitForParent()
            repeat {
                let value = NotificationFilterStore.preferences()
                let isA = equals(value, a)
                let isB = equals(value, b)
                require(isA || isB, "atomic replacement produced a partial/default snapshot")
                seenA = seenA || isA
                seenB = seenB || isB
                reads += 1
            } while reads < 10_000 || !FileManager.default.fileExists(atPath: ProbeResolver.root.appendingPathComponent("writer-done").path)
            require(seenA && seenB, "reader did not observe both replacement snapshots")
            signal("READS \(reads)")
        case "setter":
            signal("READY")
            waitForParent()
            for index in 0..<Int(args[2])! {
                let id = "\(args[1])-\(index)"
                NotificationFilterStore.setAndroidLocalNotificationsEnabled(false, for: id)
                NotificationFilterStore.setAppNotificationsEnabled(false, deviceKey: "shared-device", appId: id)
                NotificationFilterStore.setChannelNotificationsEnabled(false, deviceKey: "shared-device", appId: "app", channelId: id)
                NotificationFilterStore.setIosNotificationsEnabled(
                    false, deviceKey: "ios:\(id)", peerClientId: id, originDeviceId: nil, deviceName: id
                )
            }
        case "verify-setters":
            let ids = Set((0..<Int(args[1])!).flatMap { worker in
                (0..<Int(args[2])!).map { "worker-\(worker)-\($0)" }
            })
            let value = NotificationFilterStore.preferences()
            require(value.filteredAndroidLocalPeerIds == ids, "concurrent Android setter lost an update")
            require(value.filteredAppIdsByDeviceKey["shared-device"] == ids, "concurrent app setter lost an update")
            require(value.filteredChannelIdsByDeviceAppKey["shared-device\u{1f}app"] == ids, "concurrent channel setter lost an update")
            require(value.filteredIosDeviceKeys == Set(ids.map { "ios:\($0)" }), "concurrent iOS setter lost an update")
            require(Set(value.iosDevices.map(\.peerClientId)) == ids, "concurrent device record setter lost an update")
            require(value.iosDevices.count == ids.count, "device records contain duplicates")
        case "cache-reader":
            require(equals(NotificationFilterStore.preferences(), snapshot("A")), "cache warm-up failed")
            signal("WARM")
            waitForParent()
            require(equals(NotificationFilterStore.preferences(), snapshot("B")), "cache missed external inode replacement")
        case "resolver-success":
            for _ in 0..<5 { _ = NotificationFilterStore.preferences() }
            NotificationFilterStore.setAppNotificationsEnabled(false, deviceKey: "device", appId: "app")
            require(!NotificationFilterStore.appNotificationsEnabled(deviceKey: "device", appId: "app"), "setter failed")
            require(ProbeResolver.calls == 1, "successful URL resolution was repeated: \(ProbeResolver.calls)")
        case "resolver-retry":
            require(AppGroupStore.containerURL == nil, "first resolver call should fail")
            require(AppGroupStore.containerURL == ProbeResolver.root, "nil result was cached instead of retried")
            _ = AppGroupStore.containerURL
            AppGroupStore.withLock("retry") { _ = AppGroupStore.readData("missing") }
            require(ProbeResolver.calls == 2, "expected exactly one failed and one successful resolution")
        case "fallback":
            // The test root is a regular file, so creation of its locks/ child must fail. The body
            // resolves the cached URL while the fallback NSLock is held: reusing that lock for
            // URL caching would deadlock. The Python runner bounds every child with a timeout.
            let ran = AppGroupStore.withLock("fallback") {
                require(AppGroupStore.readData("missing") == nil, "unexpected fallback data")
                return true
            }
            require(ran, "fallback body did not execute")
            var requiredBodyRan = false
            let result: Bool? = AppGroupStore.withRequiredLock("required") {
                requiredBodyRan = true
                return true
            }
            require(result == nil && !requiredBodyRan, "required lock silently degraded to fallback")
        default:
            fatalError("Unknown test mode: \(args[0])")
        }
        signal("PASS \(args[0])")
    }
}
