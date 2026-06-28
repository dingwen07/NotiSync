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

// MARK: - Status tokens
//
// These enums are the persisted, stable representation of UI status — never English display text. Producers
// in the runtime set them; `LocalizedText` turns them into localized strings at render time. This keeps the
// model layer free of presentation, mirroring how Android resolves an `R.string` per value at the call site.

/// APNs push-route state shown in Devices / Diagnostics.
enum PushStatus: String, Codable {
    case unregistered
    case apnsRegistered
    case apnsFailed
    case apnsUnregistered
    case routePublished
    case routePending
}

/// How reachable/trustworthy the broker is. The broker version (when known) is carried separately.
enum BrokerReachability: String, Codable {
    case unknown
    case unreachable
    case reachable
    case verified
}

/// System notification-authorization state, normalized for display.
enum NotificationPermissionStatus: String, Codable {
    case unknown
    case granted
    case denied
    case notRequested
}

/// Whether this device has at least one paired peer.
enum PairingStatus: String, Codable {
    case unpaired
    case paired
}

/// The semantic title of an activity-log row.
///
/// Most cases are fully static phrases. `appLabel` carries a dynamic, non-localized app name in
/// `ActivityRecord.titleArg`; `error` carries an `ErrorDomain` raw value there.
enum ActivityTitleToken: String, Codable {
    case dismissedLocally
    case dismissed
    case remoteDismissal
    case relayDrained
    case assetSync
    case renamed
    case trustUpdated
    case rotationStarted
    case rotationActivated
    case rotationRetired
    case rotatedDebug
    case paired
    case revoked
    case approved
    case rejected
    case revokeConfirmed
    case keptTrusted
    case apnsRegistered
    case routePublished
    case appLabel
    case error
}

/// How an activity-log row's detail is rendered. Dynamic values live in `ActivityRecord.detailArg`
/// (free text / enum raw) and `ActivityRecord.detailNum` (epoch / count).
enum ActivityDetailStyle: String, Codable {
    case none
    case text
    case ongoingNotSynced
    case syncedToMesh
    case noPeers
    case toEpoch
    case nowEpoch
    case epoch
    case messageCount
    case routeEnvironment
    case deliveryMode
}

/// The operation that failed, used as an error activity's localized title.
enum ErrorDomain: String, Codable {
    case identity
    case notificationPermission
    case apnsRegistration
    case routePublish
    case rotation
    case trustBroadcast
    case profileBroadcast
    case envelopeDelivery
    case dismissSync
    case pairing
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
    /// `ActivityTitleToken` raw value — localized at render time.
    var titleTokenRaw: String
    /// Dynamic title text: an app label (for `.appLabel`) or an `ErrorDomain` raw (for `.error`).
    var titleArg: String
    /// `ActivityDetailStyle` raw value — selects how `detailArg` / `detailNum` are rendered.
    var detailStyleRaw: String
    /// Dynamic detail text: free text, or an enum raw value (`RouteEnvironment` / `DeliveryMode`).
    var detailArg: String
    /// Dynamic numeric detail: a key epoch or a message count.
    var detailNum: Int

    init(
        kind: ActivityKind,
        title: ActivityTitleToken,
        titleArg: String = "",
        detail: ActivityDetailStyle = .none,
        detailArg: String = "",
        detailNum: Int = 0,
        at: Date = .now
    ) {
        self.kindRaw = kind.rawValue
        self.titleTokenRaw = title.rawValue
        self.titleArg = titleArg
        self.detailStyleRaw = detail.rawValue
        self.detailArg = detailArg
        self.detailNum = detailNum
        self.at = at
    }

    var kind: ActivityKind {
        ActivityKind(rawValue: kindRaw) ?? .error
    }

    var titleToken: ActivityTitleToken {
        ActivityTitleToken(rawValue: titleTokenRaw) ?? .error
    }

    var detailStyle: ActivityDetailStyle {
        ActivityDetailStyle(rawValue: detailStyleRaw) ?? .none
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
    /// `NotificationPermissionStatus` raw value.
    var notificationPermission: String
    /// `PushStatus` raw value.
    var pushStatus: String
    /// `BrokerReachability` raw value.
    var brokerStatus: String
    /// Broker version string reported by the last health/verification probe, when known.
    var brokerVersion: String?
    /// `PairingStatus` raw value.
    var pairingStatus: String
    var lastDeliveryMode: String?
    var lastRoutePublishAt: Date?
    var lastRelayDrainAt: Date?
    var lastError: String?

    init(
        id: String = AppSettings.singletonId,
        brokerURL: String = NotiSyncConfig.defaultBrokerURL,
        deviceName: String = "iPhone",
        apnsEnvironment: RouteEnvironment = NotiSyncConfig.defaultAPNSEnvironment,
        routeEpoch: Int = 1
    ) {
        self.id = id
        self.brokerURL = brokerURL
        self.deviceName = deviceName
        self.apnsEnvironmentRaw = NotiSyncConfig.effectiveAPNSEnvironment(apnsEnvironment).rawValue
        self.routeEpoch = routeEpoch
        self.notificationPermission = NotificationPermissionStatus.unknown.rawValue
        self.pushStatus = PushStatus.unregistered.rawValue
        self.brokerStatus = BrokerReachability.unknown.rawValue
        self.pairingStatus = PairingStatus.unpaired.rawValue
    }

    var apnsEnvironment: RouteEnvironment {
        get {
            NotiSyncConfig.effectiveAPNSEnvironment(
                RouteEnvironment(rawValue: apnsEnvironmentRaw) ?? NotiSyncConfig.defaultAPNSEnvironment)
        }
        set { apnsEnvironmentRaw = NotiSyncConfig.effectiveAPNSEnvironment(newValue).rawValue }
    }

    /// Token-typed accessors over the persisted raw strings — producers read/write these, never the raw text.
    var notificationPermissionValue: NotificationPermissionStatus {
        get { NotificationPermissionStatus(rawValue: notificationPermission) ?? .unknown }
        set { notificationPermission = newValue.rawValue }
    }

    var pushStatusValue: PushStatus {
        get { PushStatus(rawValue: pushStatus) ?? .unregistered }
        set { pushStatus = newValue.rawValue }
    }

    var brokerReachability: BrokerReachability {
        get { BrokerReachability(rawValue: brokerStatus) ?? .unknown }
        set { brokerStatus = newValue.rawValue }
    }

    var pairingStatusValue: PairingStatus {
        get { PairingStatus(rawValue: pairingStatus) ?? .unpaired }
        set { pairingStatus = newValue.rawValue }
    }

    static let singletonId = "settings"
}
