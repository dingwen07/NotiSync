import Foundation
import NotiSyncProtocol

nonisolated enum CodecError: Error, CustomStringConvertible {
    case missingField(String)
    case typeMismatch(String)
    var description: String {
        switch self {
        case let .missingField(f): return "missing field \(f)"
        case let .typeMismatch(f): return "type mismatch on \(f)"
        }
    }
}

/// Typed protocol codec for the iOS app.
///
/// Encoding/decoding is delegated to the KMP :protocol module, so Swift no longer maintains a parallel
/// CBOR serializer. The app-facing Swift DTOs remain as adapters because the rest of iOS should not have
/// to work with Kotlin/Native byte arrays, boxed ints, and collection types.
nonisolated enum ProtocolCodec {
    private static var kmp: NotiSyncProtocol.SwiftProtocolCodec { NotiSyncProtocol.SwiftProtocolCodec.shared }

    // MARK: Encode

    static func encode(_ a: EnvelopeAuth) -> Data {
        KMPProtocolBridge.data(kmp.encodeEnvelopeAuth(value: KMPProtocolBridge.toKmp(a)))
    }

    static func encode(_ a: AssetAad) -> Data {
        KMPProtocolBridge.data(kmp.encodeAssetAad(value: KMPProtocolBridge.toKmp(a)))
    }

    static func encode(_ b: SignedBlob) -> Data {
        KMPProtocolBridge.data(kmp.encodeSignedBlob(value: KMPProtocolBridge.toKmp(b)))
    }

    static func encode(_ k: ClientKeyEpoch) -> Data {
        KMPProtocolBridge.data(kmp.encodeClientKeyEpoch(value: KMPProtocolBridge.toKmp(k)))
    }

    static func encode(_ c: RouteClaim) -> Data {
        KMPProtocolBridge.data(kmp.encodeRouteClaim(value: KMPProtocolBridge.toKmp(c)))
    }

    static func encode(_ d: DismissEvent) -> Data {
        KMPProtocolBridge.data(kmp.encodeDismissEvent(value: KMPProtocolBridge.toKmp(d)))
    }

    static func encode(_ c: ClientCard) -> Data {
        KMPProtocolBridge.data(kmp.encodeClientCard(value: KMPProtocolBridge.toKmp(c)))
    }

    static func encode(_ d: CardDelivery) -> Data {
        KMPProtocolBridge.data(kmp.encodeCardDelivery(value: KMPProtocolBridge.toKmp(d)))
    }

    static func encode(_ d: DataSync) -> Data {
        KMPProtocolBridge.data(kmp.encodeDataSync(value: KMPProtocolBridge.toKmp(d)))
    }

    static func encode(_ e: Envelope) -> Data {
        KMPProtocolBridge.data(kmp.encodeEnvelope(value: KMPProtocolBridge.toKmp(e)))
    }

    static func encodeSignedBlobList(_ blobs: [SignedBlob]) -> Data {
        KMPProtocolBridge.data(kmp.encodeSignedBlobList(value: blobs.map(KMPProtocolBridge.toKmp)))
    }

    static func encodeIntegrityVerificationRequest(clientId: String,
                                                   attestationType: String,
                                                   attestationToken: String?,
                                                   clientKeyEpoch: SignedBlob) -> Data {
        let request = NotiSyncProtocol.IntegrityVerificationRequest(
            clientId: KMPProtocolBridge.clientId(clientId),
            attestationType: attestationType,
            attestationToken: attestationToken,
            attestationKeyId: nil,
            requestNonce: "",
            requestHash: "",
            integrityToken: "",
            clientKeyEpoch: KMPProtocolBridge.toKmp(clientKeyEpoch),
            clientCard: nil,
            debugProof: nil
        )
        return Data(kmp.encodeIntegrityVerificationRequest(value: request).utf8)
    }

    static func encodeRelayAck(_ messageIds: [String]) -> Data {
        Data(kmp.encodeRelayAck(value: NotiSyncProtocol.RelayAck(messageIds: messageIds)).utf8)
    }

    static func encodeWsAuth(clientId: String, nonce: String, signatureB64: String, epoch: Int = 0) -> String {
        let auth = NotiSyncProtocol.WsAuth(
            clientId: KMPProtocolBridge.clientId(clientId),
            nonce: nonce,
            signatureB64: signatureB64,
            epoch: Int32(epoch)
        )
        return kmp.encodeWsAuth(value: auth)
    }

    static func encodeWsAck(messageId: String) -> String {
        kmp.encodeWsMessage(value: NotiSyncProtocol.WsMessage(kind: WsKind.ack, envelopeB64: nil, messageId: messageId))
    }

    static func encodeWsPong() -> String {
        kmp.encodeWsMessage(value: NotiSyncProtocol.WsMessage(kind: WsKind.pong, envelopeB64: nil, messageId: nil))
    }

    // MARK: Decode

    static func decodeHealthResponse(_ data: Data) throws -> HealthResponse {
        try KMPProtocolBridge.fromKmp(kmp.decodeHealthResponse(text: KMPProtocolBridge.string(data)))
    }

    static func decodeVerificationStatusResponse(_ data: Data) throws -> VerificationStatusResponse {
        try KMPProtocolBridge.fromKmp(kmp.decodeVerificationStatusResponse(text: KMPProtocolBridge.string(data)))
    }

    static func decodeIntegrityVerificationResponse(_ data: Data) throws -> IntegrityVerificationResponse {
        try KMPProtocolBridge.fromKmp(kmp.decodeIntegrityVerificationResponse(text: KMPProtocolBridge.string(data)))
    }

    static func decodeRelayPending(_ data: Data) throws -> RelayPendingResponse {
        try KMPProtocolBridge.fromKmp(kmp.decodeRelayPending(text: KMPProtocolBridge.string(data)))
    }

    static func decodeWsChallenge(_ text: String) throws -> WsChallenge {
        try KMPProtocolBridge.fromKmp(kmp.decodeWsChallenge(text: text))
    }

    static func decodeWsMessage(_ text: String) throws -> WsMessage {
        try KMPProtocolBridge.fromKmp(kmp.decodeWsMessage(text: text))
    }

    static func decodeSignedBlob(_ data: Data) throws -> SignedBlob {
        try KMPProtocolBridge.fromKmp(kmp.decodeSignedBlob(bytes: KMPProtocolBridge.kotlinBytes(data)))
    }

    static func decodeClientKeyEpoch(_ data: Data) throws -> ClientKeyEpoch {
        try KMPProtocolBridge.fromKmp(kmp.decodeClientKeyEpoch(bytes: KMPProtocolBridge.kotlinBytes(data)))
    }

    static func decodeEnvelope(_ data: Data) throws -> Envelope {
        try KMPProtocolBridge.fromKmp(kmp.decodeEnvelope(bytes: KMPProtocolBridge.kotlinBytes(data)))
    }

    static func envelopeMessageId(_ data: Data) throws -> String {
        try decodeEnvelope(data).messageId
    }

    static func decodeDismissEvent(_ data: Data) throws -> DismissEvent {
        try KMPProtocolBridge.fromKmp(kmp.decodeDismissEvent(bytes: KMPProtocolBridge.kotlinBytes(data)))
    }

    static func decodeCapturedNotification(_ data: Data) throws -> CapturedNotification {
        try KMPProtocolBridge.fromKmp(kmp.decodeCapturedNotification(bytes: KMPProtocolBridge.kotlinBytes(data)))
    }

    static func decodeClientCard(_ data: Data) throws -> ClientCard {
        try KMPProtocolBridge.fromKmp(kmp.decodeClientCard(bytes: KMPProtocolBridge.kotlinBytes(data)))
    }

    static func decodeCardDelivery(_ data: Data) throws -> CardDelivery {
        try KMPProtocolBridge.fromKmp(kmp.decodeCardDelivery(bytes: KMPProtocolBridge.kotlinBytes(data)))
    }

    static func decodeDataSync(_ data: Data) throws -> DataSync {
        try KMPProtocolBridge.fromKmp(kmp.decodeDataSync(bytes: KMPProtocolBridge.kotlinBytes(data)))
    }
}

nonisolated enum KMPProtocolBridge {
    static func kotlinBytes(_ data: Data) -> NotiSyncProtocol.KotlinByteArray {
        let out = NotiSyncProtocol.KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            out.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return out
    }

    static func data(_ bytes: NotiSyncProtocol.KotlinByteArray) -> Data {
        var out = Data()
        out.reserveCapacity(Int(bytes.size))
        for index in 0..<Int(bytes.size) {
            out.append(UInt8(bitPattern: bytes.get(index: Int32(index))))
        }
        return out
    }

    static func string(_ data: Data) -> String {
        String(decoding: data, as: UTF8.self)
    }

    static func string(_ value: NotiSyncProtocol.ClientId) -> String {
        value.value
    }

    static func string(_ value: Any) -> String {
        if let s = value as? String { return s }
        if let s = value as? NSString { return s as String }
        return String(describing: value)
    }

    static func clientId(_ value: String) -> NotiSyncProtocol.ClientId {
        NotiSyncProtocol.ClientId(value: value)
    }

    static func clientIds(_ values: [String]) -> [NotiSyncProtocol.ClientId] {
        values.map(clientId)
    }

    // MARK: Swift -> KMP DTOs

    static func toKmp(_ value: EnvelopeAuth) -> NotiSyncProtocol.EnvelopeAuth {
        NotiSyncProtocol.EnvelopeAuth(
            v: Int32(value.v),
            suite: value.suite,
            typ: kmp(value.typ),
            signerId: clientId(value.signerId),
            signerEpoch: Int32(value.signerEpoch),
            messageId: value.messageId,
            seq: value.seq,
            createdAt: value.createdAt,
            bodyCiphertextSha256: kotlinBytes(value.bodyCiphertextSha256),
            recipientIds: clientIds(value.recipientIds),
            recipientEpochs: value.recipientEpochs.map { NotiSyncProtocol.KotlinInt(int: Int32($0)) }
        )
    }

    static func toKmp(_ value: AssetAad) -> NotiSyncProtocol.AssetAad {
        NotiSyncProtocol.AssetAad(
            suite: value.suite,
            sourceClientId: clientId(value.sourceClientId),
            assetId: value.assetId,
            mimeType: value.mimeType,
            sizeBytes: Int32(value.sizeBytes),
            role: kmp(value.role)
        )
    }

    static func toKmp(_ value: SignedBlob) -> NotiSyncProtocol.SignedBlob {
        NotiSyncProtocol.SignedBlob(
            typ: value.typ,
            suite: value.suite,
            signerId: clientId(value.signerId),
            payload: kotlinBytes(value.payload),
            sig: kotlinBytes(value.sig)
        )
    }

    static func toKmp(_ value: ClientKeyEpoch) -> NotiSyncProtocol.ClientKeyEpoch {
        NotiSyncProtocol.ClientKeyEpoch(
            suite: value.suite,
            clientId: clientId(value.clientId),
            identityPublicKey: kotlinBytes(value.identityPublicKey),
            epoch: Int32(value.epoch),
            operationalSigningKey: kotlinBytes(value.operationalSigningKey),
            hpkePublicKey: kotlinBytes(value.hpkePublicKey),
            purposes: value.purposes.map(kmp),
            notBefore: value.notBefore,
            notAfter: value.notAfter,
            minEpoch: Int32(value.minEpoch)
        )
    }

    static func toKmp(_ value: RouteCapabilities) -> NotiSyncProtocol.RouteCapabilities {
        NotiSyncProtocol.RouteCapabilities(
            inlinePayloadLimitBytes: Int32(value.inlinePayloadLimitBytes),
            canWake: value.canWake,
            canDeliverInline: value.canDeliverInline,
            supportsCollapse: value.supportsCollapse
        )
    }

    static func toKmp(_ value: RouteClaim) -> NotiSyncProtocol.RouteClaim {
        NotiSyncProtocol.RouteClaim(
            suite: value.suite,
            clientId: clientId(value.clientId),
            transport: kmp(value.transport),
            environment: kmp(value.environment),
            routeRef: value.routeRef,
            capabilities: toKmp(value.capabilities),
            epoch: Int32(value.epoch),
            issuedAt: value.issuedAt
        )
    }

    static func toKmp(_ value: DismissEvent) -> NotiSyncProtocol.DismissEvent {
        NotiSyncProtocol.DismissEvent(
            sourceClientId: clientId(value.sourceClientId),
            sourceKey: value.sourceKey,
            dismissedAt: value.dismissedAt
        )
    }

    static func toKmp(_ value: PrivateAssetRef) -> NotiSyncProtocol.PrivateAssetRef {
        NotiSyncProtocol.PrivateAssetRef(
            role: kmp(value.role),
            assetHash: value.assetHash,
            mimeType: value.mimeType,
            sizeBytes: Int32(value.sizeBytes),
            sourceClientId: clientId(value.sourceClientId),
            assetId: value.assetId,
            assetKey: kotlinBytes(value.assetKey),
            suite: value.suite
        )
    }

    static func toKmp(_ value: AssetSyncItem) -> NotiSyncProtocol.AssetSyncItem {
        NotiSyncProtocol.AssetSyncItem(
            assetHash: value.assetHash,
            assetId: value.assetId,
            ref: value.ref.map { toKmp($0) }
        )
    }

    static func toKmp(_ value: AssetSync) -> NotiSyncProtocol.AssetSync {
        NotiSyncProtocol.AssetSync(kind: kmp(value.kind), items: value.items.map(toKmp))
    }

    static func toKmp(_ value: TrustTableEntry) -> NotiSyncProtocol.TrustTableEntry {
        NotiSyncProtocol.TrustTableEntry(
            clientId: clientId(value.clientId),
            status: kmp(value.status),
            updatedAt: value.updatedAt,
            keyAvailable: value.keyAvailable,
            ownDevice: value.ownDevice,
            epoch: Int32(value.epoch)
        )
    }

    static func toKmp(_ value: TrustTable) -> NotiSyncProtocol.TrustTable {
        NotiSyncProtocol.TrustTable(entries: value.entries.map(toKmp))
    }

    static func toKmp(_ value: ProfileUpdate) -> NotiSyncProtocol.ProfileUpdate {
        NotiSyncProtocol.ProfileUpdate(
            clientId: clientId(value.clientId),
            displayName: value.displayName,
            platform: value.platform,
            capabilities: value.capabilities.map(kmp),
            updatedAt: value.updatedAt
        )
    }

    static func toKmp(_ value: ClientCard) -> NotiSyncProtocol.ClientCard {
        NotiSyncProtocol.ClientCard(
            suite: value.suite,
            clientId: clientId(value.clientId),
            identityPublicKey: kotlinBytes(value.identityPublicKey),
            displayName: value.displayName,
            platform: value.platform,
            capabilities: value.capabilities.map(kmp),
            createdAt: value.createdAt
        )
    }

    static func toKmp(_ value: CardDelivery) -> NotiSyncProtocol.CardDelivery {
        NotiSyncProtocol.CardDelivery(
            clientId: clientId(value.clientId),
            card: value.card.map { toKmp($0) },
            epochBlob: value.epochBlob.map { toKmp($0) }
        )
    }

    static func toKmp(_ value: DataSync) -> NotiSyncProtocol.DataSync {
        NotiSyncProtocol.DataSync(
            kind: kmp(value.kind),
            asset: value.asset.map { toKmp($0) },
            profile: value.profile.map { toKmp($0) },
            trust: value.trust.map { toKmp($0) },
            card: value.card.map { toKmp($0) }
        )
    }

    static func toKmp(_ value: PerRecipientKey) -> NotiSyncProtocol.PerRecipientKey {
        NotiSyncProtocol.PerRecipientKey(
            recipientId: clientId(value.recipientId),
            sealedDek: kotlinBytes(value.sealedDek),
            recipientEpoch: Int32(value.recipientEpoch)
        )
    }

    static func toKmp(_ value: Envelope) -> NotiSyncProtocol.Envelope {
        NotiSyncProtocol.Envelope(
            v: Int32(value.v),
            suite: value.suite,
            typ: kmp(value.typ),
            signerId: clientId(value.signerId),
            signerEpoch: Int32(value.signerEpoch),
            messageId: value.messageId,
            seq: value.seq,
            createdAt: value.createdAt,
            bodyCiphertext: kotlinBytes(value.bodyCiphertext),
            recipients: value.recipients.map(toKmp),
            sig: kotlinBytes(value.sig)
        )
    }

    // MARK: KMP -> Swift DTOs

    static func fromKmp(_ value: NotiSyncProtocol.HealthResponse) -> HealthResponse {
        HealthResponse(status: value.status, version: value.version)
    }

    static func fromKmp(_ value: NotiSyncProtocol.VerificationStatusResponse) -> VerificationStatusResponse {
        VerificationStatusResponse(
            version: value.version,
            playIntegrityRequired: value.playIntegrityRequired,
            verified: value.verified,
            clientId: value.clientId.map(string),
            expiresAt: value.expiresAt?.int64Value,
            powDifficulty: Int(value.powDifficulty),
            acceptedAttestationMethods: value.acceptedAttestationMethods
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.IntegrityVerificationResponse) -> IntegrityVerificationResponse {
        IntegrityVerificationResponse(
            token: value.token,
            tokenType: value.tokenType,
            clientId: string(value.clientId),
            expiresAt: value.expiresAt
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.RelayPending) -> RelayPendingResponse {
        RelayPendingResponse(messageIds: value.messageIds)
    }

    static func fromKmp(_ value: NotiSyncProtocol.WsChallenge) -> WsChallenge {
        WsChallenge(nonce: value.nonce)
    }

    static func fromKmp(_ value: NotiSyncProtocol.WsMessage) -> WsMessage {
        WsMessage(kind: value.kind, envelopeB64: value.envelopeB64, messageId: value.messageId)
    }

    static func fromKmp(_ value: NotiSyncProtocol.SignedBlob) -> SignedBlob {
        SignedBlob(
            typ: value.typ,
            suite: value.suite,
            signerId: string(value.signerId),
            payload: data(value.payload),
            sig: data(value.sig)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.ClientKeyEpoch) -> ClientKeyEpoch {
        ClientKeyEpoch(
            suite: value.suite,
            clientId: string(value.clientId),
            identityPublicKey: data(value.identityPublicKey),
            epoch: Int(value.epoch),
            operationalSigningKey: data(value.operationalSigningKey),
            hpkePublicKey: data(value.hpkePublicKey),
            purposes: value.purposes.compactMap { Purpose(rawValue: $0.name) },
            notBefore: value.notBefore,
            notAfter: value.notAfter,
            minEpoch: Int(value.minEpoch)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.PerRecipientKey) -> PerRecipientKey {
        PerRecipientKey(
            recipientId: string(value.recipientId),
            sealedDek: data(value.sealedDek),
            recipientEpoch: Int(value.recipientEpoch)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.Envelope) throws -> Envelope {
        Envelope(
            v: Int(value.v),
            suite: value.suite,
            typ: try swift(value.typ),
            signerId: string(value.signerId),
            signerEpoch: Int(value.signerEpoch),
            messageId: value.messageId,
            seq: value.seq,
            createdAt: value.createdAt,
            bodyCiphertext: data(value.bodyCiphertext),
            recipients: value.recipients.map(fromKmp),
            sig: data(value.sig)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.DismissEvent) -> DismissEvent {
        DismissEvent(
            sourceClientId: string(value.sourceClientId),
            sourceKey: value.sourceKey,
            dismissedAt: value.dismissedAt
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.PrivateAssetRef?) -> PrivateAssetRef? {
        guard let value else { return nil }
        return PrivateAssetRef(
            role: AssetRole(rawValue: value.role.name) ?? .APP_ICON,
            assetHash: value.assetHash,
            mimeType: value.mimeType,
            sizeBytes: Int(value.sizeBytes),
            sourceClientId: string(value.sourceClientId),
            assetId: value.assetId,
            assetKey: data(value.assetKey),
            suite: value.suite
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.ConversationMessage) -> ConversationMessage {
        ConversationMessage(
            sender: value.sender,
            text: value.text,
            timestamp: value.timestamp,
            avatar: fromKmp(value.avatar),
            dataMimeType: value.dataMimeType,
            data: fromKmp(value.data)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.CapturedNotification) throws -> CapturedNotification {
        CapturedNotification(
            sourceClientId: string(value.sourceClientId),
            sourceKey: value.sourceKey,
            packageName: value.packageName,
            appLabel: value.appLabel,
            largeIcon: fromKmp(value.largeIcon),
            bigPicture: fromKmp(value.bigPicture),
            title: value.title,
            text: value.text,
            bigText: value.bigText,
            subText: value.subText,
            style: NotifStyle(rawValue: value.style.name) ?? .DEFAULT,
            conversationTitle: value.conversationTitle,
            isGroupConversation: value.isGroupConversation,
            messages: value.messages.map(fromKmp),
            category: MirrorCategory(rawValue: value.category.name) ?? .NONE,
            importance: MirrorImportance(rawValue: value.importance.name) ?? .DEFAULT,
            postTime: value.postTime,
            groupKey: value.groupKey,
            isGroupSummary: value.isGroupSummary,
            isOngoing: value.isOngoing,
            isClearable: value.isClearable,
            sensitiveRedacted: value.sensitiveRedacted,
            channelId: value.channelId,
            channelName: value.channelName,
            channelGroupId: value.channelGroupId,
            channelGroupName: value.channelGroupName,
            channelImportance: value.channelImportance.flatMap { MirrorImportance(rawValue: $0.name) },
            shouldVibrate: value.shouldVibrate,
            isConversation: value.isConversation,
            shortcutId: value.shortcutId,
            conversationId: value.conversationId,
            parentChannelId: value.parentChannelId,
            appIcon: fromKmp(value.appIcon),
            originPlatform: OriginPlatform(rawValue: value.originPlatform.name) ?? .ANDROID_LOCAL,
            originDeviceName: value.originDeviceName,
            iosBundleId: value.iosBundleId,
            originDeviceId: value.originDeviceId,
            onlyAlertOnce: value.onlyAlertOnce
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.ClientCard) -> ClientCard {
        ClientCard(
            suite: value.suite,
            clientId: string(value.clientId),
            identityPublicKey: data(value.identityPublicKey),
            displayName: value.displayName,
            platform: value.platform,
            capabilities: value.capabilities.compactMap { Capability(rawValue: $0.name) },
            createdAt: value.createdAt
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.CardDelivery) -> CardDelivery {
        CardDelivery(
            clientId: string(value.clientId),
            card: value.card.map(fromKmp),
            epochBlob: value.epochBlob.map(fromKmp)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.AssetSyncItem) -> AssetSyncItem {
        AssetSyncItem(
            assetHash: value.assetHash,
            assetId: value.assetId,
            ref: fromKmp(value.ref)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.AssetSync) -> AssetSync {
        AssetSync(
            kind: AssetSyncKind(rawValue: value.kind.name) ?? .ASSET_MISSING,
            items: value.items.map(fromKmp)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.ProfileUpdate?) -> ProfileUpdate? {
        guard let value else { return nil }
        return ProfileUpdate(
            clientId: string(value.clientId),
            displayName: value.displayName,
            platform: value.platform,
            capabilities: value.capabilities.compactMap { Capability(rawValue: $0.name) },
            updatedAt: value.updatedAt
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.TrustTableEntry) -> TrustTableEntry {
        TrustTableEntry(
            clientId: string(value.clientId),
            status: TrustStatus(rawValue: value.status.name) ?? .PENDING_TRUST,
            updatedAt: value.updatedAt,
            keyAvailable: value.keyAvailable,
            ownDevice: value.ownDevice,
            epoch: Int(value.epoch)
        )
    }

    static func fromKmp(_ value: NotiSyncProtocol.TrustTable?) -> TrustTable? {
        guard let value else { return nil }
        return TrustTable(entries: value.entries.map(fromKmp))
    }

    static func fromKmp(_ value: NotiSyncProtocol.DataSync) throws -> DataSync {
        DataSync(
            kind: DataSyncKind(rawValue: value.kind.name) ?? .ASSET,
            asset: value.asset.map(fromKmp),
            profile: fromKmp(value.profile),
            trust: fromKmp(value.trust),
            card: value.card.map(fromKmp)
        )
    }

    // MARK: Enum adapters

    static func swift(_ value: NotiSyncProtocol.MessageType) throws -> MessageType {
        guard let result = MessageType(rawValue: value.name) else { throw CodecError.typeMismatch("typ") }
        return result
    }

    static func kmp(_ value: MessageType) -> NotiSyncProtocol.MessageType {
        NotiSyncProtocol.MessageType.entries.first { $0.name == value.rawValue } ?? NotiSyncProtocol.MessageType.notification
    }

    static func kmp(_ value: Purpose) -> NotiSyncProtocol.Purpose {
        NotiSyncProtocol.Purpose.entries.first { $0.name == value.rawValue } ?? NotiSyncProtocol.Purpose.envelopeSign
    }

    static func kmp(_ value: AssetRole) -> NotiSyncProtocol.AssetRole {
        NotiSyncProtocol.AssetRole.entries.first { $0.name == value.rawValue } ?? NotiSyncProtocol.AssetRole.appIcon
    }

    static func kmp(_ value: AssetSyncKind) -> NotiSyncProtocol.AssetSyncKind {
        NotiSyncProtocol.AssetSyncKind.entries.first { $0.name == value.rawValue } ?? NotiSyncProtocol.AssetSyncKind.assetMissing
    }

    static func kmp(_ value: TrustStatus) -> NotiSyncProtocol.TrustStatus {
        NotiSyncProtocol.TrustStatus.entries.first { $0.name == value.rawValue } ?? NotiSyncProtocol.TrustStatus.pendingTrust
    }

    static func kmp(_ value: Capability) -> NotiSyncProtocol.Capability {
        NotiSyncProtocol.Capability.entries.first { $0.name == value.rawValue } ?? NotiSyncProtocol.Capability.display
    }

    static func kmp(_ value: DataSyncKind) -> NotiSyncProtocol.DataSyncKind {
        NotiSyncProtocol.DataSyncKind.entries.first { $0.name == value.rawValue } ?? NotiSyncProtocol.DataSyncKind.asset
    }

    static func kmp(_ value: TransportType) -> NotiSyncProtocol.TransportType {
        NotiSyncProtocol.TransportType.entries.first { $0.name == value.rawValue } ?? NotiSyncProtocol.TransportType.apns
    }

    static func kmp(_ value: RouteEnvironment) -> NotiSyncProtocol.RouteEnvironment {
        NotiSyncProtocol.RouteEnvironment.entries.first { $0.name == value.rawValue } ?? NotiSyncProtocol.RouteEnvironment.production
    }
}
