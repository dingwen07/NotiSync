import Foundation
import SwiftData

enum ActivityKind: String, Codable, CaseIterable {
    case received
    case dismissed
    case sent
    case paired
    case route
    case error
}

enum TrustStatusRaw: String, Codable, CaseIterable {
    case thisDevice
    case pendingTrust
    case trusted
    case pendingRevoke
    case revoked
    case quarantined
}

@Model
final class InboxNotification {
    @Attribute(.unique) var messageId: String
    var sourceClientId: String
    var sourceKey: String
    var packageName: String
    var iosBundleId: String?
    var appLabel: String
    var title: String?
    var body: String?
    var subtitle: String?
    var originDeviceName: String?
    var originPlatform: String?
    var category: String
    var importance: String
    var postTime: Date
    var receivedAt: Date
    var localNotificationId: String
    var deliveryMode: String
    var isDismissed: Bool
    var iconURL: URL?
    /// SHA-256 of the decrypted launcher icon (Android-origin apps have no public App Store URL); used to
    /// read the bytes from the shared `AssetCache`. `iconAssetRefData` is the encoded `AssetRefSnapshot`
    /// for a read-through fetch + decrypt when the bytes aren't cached yet.
    var iconAssetHash: String?
    var iconAssetRefData: Data?

    init(
        messageId: String,
        sourceClientId: String,
        sourceKey: String,
        packageName: String,
        iosBundleId: String? = nil,
        appLabel: String,
        title: String? = nil,
        body: String? = nil,
        subtitle: String? = nil,
        originDeviceName: String? = nil,
        originPlatform: String? = nil,
        category: String,
        importance: String,
        postTime: Date,
        receivedAt: Date,
        localNotificationId: String,
        deliveryMode: String,
        isDismissed: Bool = false,
        iconURL: URL? = nil,
        iconAssetHash: String? = nil,
        iconAssetRefData: Data? = nil
    ) {
        self.messageId = messageId
        self.sourceClientId = sourceClientId
        self.sourceKey = sourceKey
        self.packageName = packageName
        self.iosBundleId = iosBundleId
        self.appLabel = appLabel
        self.title = title
        self.body = body
        self.subtitle = subtitle
        self.originDeviceName = originDeviceName
        self.originPlatform = originPlatform
        self.category = category
        self.importance = importance
        self.postTime = postTime
        self.receivedAt = receivedAt
        self.localNotificationId = localNotificationId
        self.deliveryMode = deliveryMode
        self.isDismissed = isDismissed
        self.iconURL = iconURL
        self.iconAssetHash = iconAssetHash
        self.iconAssetRefData = iconAssetRefData
    }

    var isIPhoneOrigin: Bool {
        if originPlatform == OriginPlatform.IOS_ANCS.rawValue { return true }
        return originPlatform == nil && !(iosBundleId?.isEmpty ?? true)
    }
}

@Model
final class PendingRelayAck {
    @Attribute(.unique) var messageId: String
    var createdAt: Date

    init(messageId: String, createdAt: Date = .now) {
        self.messageId = messageId
        self.createdAt = createdAt
    }
}

@Model
final class TrustedDevice {
    @Attribute(.unique) var clientId: String
    var displayName: String
    var platform: String
    var statusRaw: String
    var safetyNumber: String?
    var keyFingerprint: String?
    var updatedAt: Date

    init(
        clientId: String,
        displayName: String,
        platform: String,
        status: TrustStatusRaw,
        safetyNumber: String? = nil,
        keyFingerprint: String? = nil,
        updatedAt: Date = .now
    ) {
        self.clientId = clientId
        self.displayName = displayName
        self.platform = platform
        self.statusRaw = status.rawValue
        self.safetyNumber = safetyNumber
        self.keyFingerprint = keyFingerprint
        self.updatedAt = updatedAt
    }

    var status: TrustStatusRaw {
        get { TrustStatusRaw(rawValue: statusRaw) ?? .pendingTrust }
        set { statusRaw = newValue.rawValue }
    }
}

@Model
final class ActivityRecord {
    var at: Date
    var kindRaw: String
    var title: String
    var detail: String

    init(kind: ActivityKind, title: String, detail: String, at: Date = .now) {
        self.kindRaw = kind.rawValue
        self.title = title
        self.detail = detail
        self.at = at
    }

    var kind: ActivityKind {
        ActivityKind(rawValue: kindRaw) ?? .error
    }
}

@Model
final class AppSettings {
    @Attribute(.unique) var id: String
    var brokerURL: String
    var deviceName: String
    var apnsToken: String?
    var apnsEnvironmentRaw: String
    var routeEpoch: Int
    var notificationPermission: String
    var pushStatus: String
    var brokerStatus: String
    var pairingStatus: String
    var lastDeliveryMode: String?
    var lastRoutePublishAt: Date?
    var lastRelayDrainAt: Date?
    var lastError: String?

    init(
        id: String = AppSettings.singletonId,
        brokerURL: String = NotiSyncConfig.defaultBrokerURL,
        deviceName: String = "iPhone",
        apnsEnvironment: RouteEnvironment = .DEVELOPMENT,
        routeEpoch: Int = 1
    ) {
        self.id = id
        self.brokerURL = brokerURL
        self.deviceName = deviceName
        self.apnsEnvironmentRaw = apnsEnvironment.rawValue
        self.routeEpoch = routeEpoch
        self.notificationPermission = "unknown"
        self.pushStatus = "unregistered"
        self.brokerStatus = "unknown"
        self.pairingStatus = "unpaired"
    }

    var apnsEnvironment: RouteEnvironment {
        get { RouteEnvironment(rawValue: apnsEnvironmentRaw) ?? .DEVELOPMENT }
        set { apnsEnvironmentRaw = newValue.rawValue }
    }

    static let singletonId = "settings"
}
