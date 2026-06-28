import Foundation

// Swift-facing adapters for the KMP :protocol DTOs. The iOS app keeps native structs at its internal
// boundaries, while ProtocolCodec converts them to/from the shared Kotlin types for all wire encoding.
// KMP `ClientId`/`GroupId` are represented as plain String inside Swift app models.

nonisolated enum CipherSuite {
    static let current = "NS2"
}

// MARK: - Enums (serialize to CBOR as their Kotlin name)

nonisolated enum MessageType: String, Sendable { case NOTIFICATION, DISMISSAL, DATA_SYNC }
nonisolated enum Purpose: String, CaseIterable, Sendable { case ENVELOPE_SIGN, REQUEST_AUTH, HPKE_SEAL
    static let allRawValues: [String] = allCases.map(\.rawValue)
}
nonisolated enum AssetRole: String, Sendable { case LARGE_ICON, BIG_PICTURE, AVATAR, INLINE_IMAGE, APP_ICON }
nonisolated enum NotifStyle: String, Sendable { case DEFAULT, BIG_TEXT, BIG_PICTURE, MESSAGING, INBOX }
nonisolated enum MirrorImportance: String, Sendable { case MIN, LOW, DEFAULT, HIGH, NONE }
nonisolated enum MirrorCategory: String, Sendable {
    case MESSAGE, EMAIL, CALL, ALARM, EVENT, REMINDER, SOCIAL, PROGRESS
    case TRANSPORT, SERVICE, STATUS, ERROR, NAVIGATION, NONE
}
nonisolated enum OriginPlatform: String, Sendable { case ANDROID_LOCAL, IOS_ANCS }
nonisolated enum TrustStatus: String, Sendable { case PENDING_TRUST, TRUSTED, PENDING_REVOKE, REVOKED }
nonisolated enum DataSyncKind: String, Sendable { case ASSET, PROFILE, TRUST, CARD }
nonisolated enum AssetSyncKind: String, Sendable { case ASSET_MISSING, ASSET_READY }
nonisolated enum Capability: String, Sendable {
    case CAPTURE, DISPLAY, DISMISS_SYNC, PROVIDE_ASSETS, BACKGROUND_WAKE, FOREGROUND_CONNECTION
}

nonisolated enum TransportType: String, Sendable { case FCM, WEBSOCKET, APNS, WEBPUSH }

nonisolated enum RouteEnvironment: String, CaseIterable, Identifiable, Sendable {
    case PRODUCTION, DEVELOPMENT
    public var id: String { rawValue }
}

nonisolated enum SignedType {
    static let clientCard = "client-card"
    static let routeClaim = "route-claim"
    static let keyEpoch = "key-epoch"
}

nonisolated enum AttestationType {
    static let playIntegrity = "playIntegrity"
    static let firebaseAppCheck = "firebaseAppCheck"
    static let appleAppAttest = "appleAppAttest"
}

// MARK: - CBOR wire DTOs

nonisolated struct PerRecipientKey: Sendable {
    var recipientId: String
    var sealedDek: Data
    var recipientEpoch: Int = 0
}

nonisolated struct Envelope: Sendable {
    var v: Int = 1
    var suite: String = CipherSuite.current
    var typ: MessageType
    var signerId: String
    var signerEpoch: Int = 0
    var messageId: String
    var seq: Int64
    var createdAt: Int64
    var bodyCiphertext: Data
    var recipients: [PerRecipientKey]
    var sig: Data = Data()

    func recipientIds() -> [String] { recipients.map(\.recipientId) }
    func recipientEpochs() -> [Int] { recipients.map(\.recipientEpoch) }
}

nonisolated struct EnvelopeAuth: Sendable {
    var v: Int
    var suite: String
    var typ: MessageType
    var signerId: String
    var signerEpoch: Int
    var messageId: String
    var seq: Int64
    var createdAt: Int64
    var bodyCiphertextSha256: Data
    var recipientIds: [String]
    var recipientEpochs: [Int]
}

nonisolated struct AssetAad: Sendable {
    var suite: String
    var sourceClientId: String
    var assetId: String
    var mimeType: String
    var sizeBytes: Int
    var role: AssetRole
}

nonisolated struct SignedBlob: Sendable {
    var typ: String
    var suite: String = CipherSuite.current
    var signerId: String
    var payload: Data
    var sig: Data
}

nonisolated struct ClientKeyEpoch: Sendable {
    var suite: String = CipherSuite.current
    var clientId: String
    var identityPublicKey: Data       // X.509 SPKI, EC P-256; empty when stripped for QR
    var epoch: Int
    var operationalSigningKey: Data   // X.509 SPKI, EC P-256
    var hpkePublicKey: Data           // raw 32-byte X25519
    var purposes: [Purpose]
    var notBefore: Int64
    var notAfter: Int64
    var minEpoch: Int
}

nonisolated struct RouteCapabilities: Sendable {
    var inlinePayloadLimitBytes: Int
    var canWake: Bool = true
    var canDeliverInline: Bool = true
    var supportsCollapse: Bool = false
}

nonisolated struct RouteClaim: Sendable {
    var suite: String = CipherSuite.current
    var clientId: String
    var transport: TransportType
    var environment: RouteEnvironment
    var routeRef: String
    var capabilities: RouteCapabilities
    var epoch: Int
    var issuedAt: Int64
}

nonisolated struct DismissEvent: Sendable {
    var sourceClientId: String
    var sourceKey: String
    var dismissedAt: Int64
}

nonisolated struct ConversationMessage: Sendable {
    var sender: String?
    var text: String
    var timestamp: Int64
    var avatar: PrivateAssetRef?
    var dataMimeType: String?
    var data: PrivateAssetRef?
}

nonisolated struct PrivateAssetRef: Sendable {
    var role: AssetRole
    var assetHash: String
    var mimeType: String
    var sizeBytes: Int
    var sourceClientId: String
    var assetId: String
    var assetKey: Data
    var suite: String = CipherSuite.current
}

/// The normalized notification body (decoded from a NOTIFICATION envelope). We decode the fields the
/// iOS mirror renders; unknown fields are ignored (CBOR decode is tolerant), matching kotlinx semantics.
nonisolated struct CapturedNotification: Sendable {
    var sourceClientId: String
    var sourceKey: String
    var packageName: String
    var appLabel: String
    var largeIcon: PrivateAssetRef?
    var bigPicture: PrivateAssetRef?
    var title: String?
    var text: String?
    var bigText: String?
    var subText: String?
    var style: NotifStyle = .DEFAULT
    var conversationTitle: String?
    var isGroupConversation: Bool = false
    var messages: [ConversationMessage] = []
    var category: MirrorCategory = .NONE
    var importance: MirrorImportance = .DEFAULT
    var postTime: Int64
    var groupKey: String?
    var isGroupSummary: Bool = false
    var isOngoing: Bool = false
    var isClearable: Bool = true
    var sensitiveRedacted: Bool = false
    var channelId: String?
    var channelName: String?
    var channelGroupId: String?
    var channelGroupName: String?
    /// Source channel-level importance/mute. When present it overrides the per-notification `importance`
    /// for the alerting decision (mirrors Android `importanceOf` = `channelImportance ?: importance`).
    var channelImportance: MirrorImportance?
    var shouldVibrate: Bool = false
    var isConversation: Bool = false
    var shortcutId: String?
    var conversationId: String?
    var parentChannelId: String?
    var appIcon: PrivateAssetRef?
    var originPlatform: OriginPlatform = .ANDROID_LOCAL
    var originDeviceName: String?
    var iosBundleId: String?
    /// Stable id of the originating device for a bridged capture — lets the consumer group per origin.
    var originDeviceId: String?

    /// The importance that drives alerting: the source channel's importance if present, else the
    /// per-notification importance (mirrors Android `importanceOf`).
    var effectiveImportance: MirrorImportance { channelImportance ?? importance }
}

nonisolated struct AssetSyncItem: Sendable {
    var assetHash: String
    var assetId: String?
    var ref: PrivateAssetRef?
}

nonisolated struct AssetSync: Sendable {
    var kind: AssetSyncKind
    var items: [AssetSyncItem]
}

nonisolated struct TrustTableEntry: Sendable {
    var clientId: String
    var status: TrustStatus
    var updatedAt: Int64
    var keyAvailable: Bool
    var ownDevice: Bool = true
    var epoch: Int = 0
}

nonisolated struct TrustTable: Sendable {
    var entries: [TrustTableEntry]
}

nonisolated struct ProfileUpdate: Sendable {
    var clientId: String
    var displayName: String
    var platform: String
    var capabilities: [Capability]
    var updatedAt: Int64
}

nonisolated struct ClientCard: Sendable {
    var suite: String = CipherSuite.current
    var clientId: String
    var identityPublicKey: Data
    var displayName: String
    var platform: String
    var capabilities: [Capability]
    var createdAt: Int64
}

nonisolated struct CardDelivery: Sendable {
    var clientId: String
    var card: SignedBlob?
    var epochBlob: SignedBlob?
}

nonisolated struct DataSync: Sendable {
    var kind: DataSyncKind
    var asset: AssetSync?
    var profile: ProfileUpdate?
    var trust: TrustTable?
    var card: CardDelivery?
}

// MARK: - JSON control-plane DTOs (Decodable; the broker's REST layer)

nonisolated struct HealthResponse: Decodable, Sendable {
    var status: String
    var version: String
}

nonisolated struct VerificationStatusResponse: Decodable, Sendable {
    var version: String
    var playIntegrityRequired: Bool
    var verified: Bool
    var clientId: String?
    var expiresAt: Int64?
    var powDifficulty: Int
    var acceptedAttestationMethods: [String]

    enum CodingKeys: String, CodingKey {
        case version, playIntegrityRequired, verified, clientId, expiresAt, powDifficulty, acceptedAttestationMethods
    }

    init(version: String, playIntegrityRequired: Bool, verified: Bool, clientId: String?, expiresAt: Int64?,
         powDifficulty: Int, acceptedAttestationMethods: [String]) {
        self.version = version
        self.playIntegrityRequired = playIntegrityRequired
        self.verified = verified
        self.clientId = clientId
        self.expiresAt = expiresAt
        self.powDifficulty = powDifficulty
        self.acceptedAttestationMethods = acceptedAttestationMethods
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = (try? c.decode(String.self, forKey: .version)) ?? "?"
        playIntegrityRequired = (try? c.decode(Bool.self, forKey: .playIntegrityRequired)) ?? false
        verified = (try? c.decode(Bool.self, forKey: .verified)) ?? false
        clientId = try? c.decodeIfPresent(String.self, forKey: .clientId)
        expiresAt = try? c.decodeIfPresent(Int64.self, forKey: .expiresAt)
        powDifficulty = (try? c.decode(Int.self, forKey: .powDifficulty)) ?? 0
        acceptedAttestationMethods = (try? c.decode([String].self, forKey: .acceptedAttestationMethods)) ?? []
    }
}

nonisolated struct IntegrityVerificationResponse: Decodable, Sendable {
    var token: String
    var tokenType: String
    var clientId: String
    var expiresAt: Int64

    enum CodingKeys: String, CodingKey { case token, tokenType, clientId, expiresAt }
    init(token: String, tokenType: String, clientId: String, expiresAt: Int64) {
        self.token = token
        self.tokenType = tokenType
        self.clientId = clientId
        self.expiresAt = expiresAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        token = try c.decode(String.self, forKey: .token)
        tokenType = (try? c.decode(String.self, forKey: .tokenType)) ?? "Bearer"
        clientId = try c.decode(String.self, forKey: .clientId)
        expiresAt = try c.decode(Int64.self, forKey: .expiresAt)
    }
}

nonisolated struct RelayPendingResponse: Decodable, Sendable {
    var messageIds: [String]
    init(messageIds: [String]) {
        self.messageIds = messageIds
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        messageIds = (try? c.decode([String].self, forKey: .messageIds)) ?? []
    }
    enum CodingKeys: String, CodingKey { case messageIds }
}

nonisolated struct WsChallenge: Decodable, Sendable { var nonce: String }

nonisolated struct WsMessage: Codable, Sendable {
    var kind: String
    var envelopeB64: String?
    var messageId: String?
}

nonisolated enum WsKind {
    static let deliver = "deliver", ack = "ack", ping = "ping", pong = "pong"
}
