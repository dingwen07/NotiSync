import UIKit
import UserNotifications

@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        FirebaseBootstrap.configure()
        UNUserNotificationCenter.current().delegate = self
        // Notification categories must exist before an alert or cold-launch response is delivered. In
        // particular, do not wait for RootView/model configuration before registering the SSH request actions.
        MirrorCategoryRegistry.registerAll()
        NotiSyncRuntime.shared.registerBackgroundTasks()
        GestureSymbolPrewarm.run()
        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        NotiSyncRuntime.shared.didRegisterForRemoteNotifications(deviceToken: deviceToken)
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        NotiSyncRuntime.shared.didFailToRegisterForRemoteNotifications(error: error)
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        Task {
            let result = await NotiSyncRuntime.shared.handleRemoteNotification(userInfo)
            completionHandler(result)
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        let info = notification.request.content.userInfo
        // SSH review prompts and remembered-authorization audit alerts are security events,
        // not mirrored app notifications. Always present them even when Android notification
        // mirroring is disabled or filtered for the requesting device.
        if info[SshKeyProviderNotificationPresentation.requestIdUserInfoKey] != nil {
            return [.banner, .list, .sound]
        }
        if NotificationFilterStore.shouldFilterNotification(
            originPlatform: info["originPlatform"] as? String,
            sourceClientId: info["sourceClientId"] as? String ?? "",
            originDeviceName: info["originDeviceName"] as? String,
            originDeviceId: info["originDeviceId"] as? String,
            packageName: info["packageName"] as? String,
            iosBundleId: info["iosBundleId"] as? String,
            channelId: info["channelId"] as? String
        ) {
            return []
        }
        return [.banner, .list, .sound]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        await NotiSyncRuntime.shared.handleNotificationResponse(response)
    }
}
