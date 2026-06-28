//
//  NotiSyncApp.swift
//  NotiSync
//
//  Created by Dingwen Wang on 6/25/26.
//

import SwiftUI
import SwiftData

@main
struct NotiSyncApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var runtime = NotiSyncRuntime.shared
    @Environment(\.scenePhase) private var scenePhase

    var sharedModelContainer: ModelContainer = {
        let schema = Schema([
            InboxNotification.self,
            PendingRelayAck.self,
            TrustedDevice.self,
            ActivityRecord.self,
            AppSettings.self,
        ])
        let modelConfiguration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)

        do {
            return try ModelContainer(for: schema, configurations: [modelConfiguration])
        } catch {
            // The app is pre-release: a model-schema change can make the existing on-disk store
            // unopenable. Rather than crash (forcing a manual delete/reinstall), wipe the store and
            // rebuild it. Destructive — drops local Inbox, Activity, Devices, and Settings rows; the
            // mesh re-converges peers/notifications and Settings fall back to defaults.
            let fm = FileManager.default
            for suffix in ["", "-wal", "-shm"] {
                try? fm.removeItem(at: URL(fileURLWithPath: modelConfiguration.url.path + suffix))
            }
            do {
                return try ModelContainer(for: schema, configurations: [modelConfiguration])
            } catch {
                fatalError("Could not create ModelContainer after reset: \(error)")
            }
        }
    }()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(runtime)
        }
        .modelContainer(sharedModelContainer)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                runtime.appBecameActive()
            } else {
                runtime.appLeftForeground()
            }
        }
    }
}
