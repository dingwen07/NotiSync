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
            fatalError("Could not create ModelContainer: \(error)")
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
