import CryptoKit
import Foundation

private nonisolated struct SequenceState: Codable, Sendable {
    var next: Int64
}

/// What a successfully-opened envelope carried.
nonisolated enum DecodedInbound: Sendable {
    case notification(CapturedNotification)
    case dismissal(DismissEvent)
    case dataSync(DataSync)
    /// A type this platform doesn't consume (ACTION — iOS never captures, so it is never an origin
    /// that could perform one). Verified + decrypted fine; the caller acks and drops it.
    case unsupported(MessageType)
}

nonisolated enum EngineError: Error, LocalizedError {
    case unknownSender(String)
    case unresolvedSender(String)
    case untrustedSender(String)
    case verificationFailed
    case persistenceFailed
    case noHpkeKey(Int)
    case notForUs

    /// Drop quietly (logcat-only on Android) rather than surfacing a user-facing activity row: an envelope
    /// from a sender we don't (fully) trust or can't open is expected in a multi-device mesh and would
    /// otherwise flood the activity feed. A bad SIGNATURE is the one exception — it stays user-visible.
    var isSilentDrop: Bool {
        switch self {
        case .unknownSender, .untrustedSender, .unresolvedSender, .noHpkeKey, .notForUs: return true
        case .verificationFailed, .persistenceFailed: return false
        }
    }

    var ackAfterSilentDrop: Bool {
        switch self {
        case .noHpkeKey: return true
        case .unknownSender, .untrustedSender, .unresolvedSender, .notForUs, .verificationFailed,
             .persistenceFailed: return false
        }
    }

    var errorDescription: String? {
        switch self {
        case let .unknownSender(id):
            return String(
                format: String(localized: "error.engine.unknownSender", defaultValue: "No trusted keys for sender %@.", comment: "Error shown when a sender has no trusted keys."),
                id
            )
        case let .unresolvedSender(id):
            return String(
                format: String(localized: "error.engine.unresolvedSender", defaultValue: "No current key-epoch for sender %@.", comment: "Error shown when a sender has no current key epoch."),
                id
            )
        case let .untrustedSender(id):
            return String(
                format: String(localized: "error.engine.untrustedSender", defaultValue: "Sender %@ is not a trusted device.", comment: "Error shown when a sender is not trusted."),
                id
            )
        case .verificationFailed:
            return String(localized: "error.engine.verificationFailed", defaultValue: "Envelope signature did not verify.", comment: "Error shown when an envelope signature is invalid.")
        case .persistenceFailed:
            return String(localized: "error.engine.persistenceFailed", defaultValue: "Could not save trusted-device state.", comment: "Error shown when trusted-device state cannot be saved.")
        case let .noHpkeKey(epoch):
            return String(
                format: String(localized: "error.engine.noHpkeKey", defaultValue: "No HPKE private key for epoch %d.", comment: "Error shown when this device cannot decrypt an envelope for the given key epoch."),
                epoch
            )
        case .notForUs:
            return String(localized: "error.engine.notForUs", defaultValue: "Envelope is not addressed to this device.", comment: "Error shown when an envelope has no recipient entry for this device.")
        }
    }
}

private nonisolated let iosSelfCapabilities: [Capability] = [
    .DISPLAY,
    .DISMISS_SYNC,
    .BACKGROUND_WAKE,
    .FOREGROUND_CONNECTION,
    .CAPABILITY_ROUTING_V1,
]

nonisolated enum KeyEpochStatus: Sendable { case verified, absent, invalid }

nonisolated struct PairingCandidate: Identifiable, Sendable {
    var id: String { clientId }
    var payload: String
    var displayName: String
    var platform: String
    var clientId: String
    var safetyNumber: String
    var identityKeyFingerprint: String
    var epoch: Int
    var operationalKeyFingerprint: String
    var hpkeKeyFingerprint: String
    var keyEpochStatus: KeyEpochStatus
}

nonisolated struct ScreenMirrorSourceRecord: Identifiable, Sendable {
    static let requiredCapabilities: Set<Capability> = [
        .CAPABILITY_ROUTING_V1,
        .SCREEN_MIRROR_SOURCE_V1,
        .SCREEN_MIRROR_CONTROL_V1,
        .SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
        .SCREEN_MIRROR_ENCODER_H264_HW,
    ]

    var id: String { clientId }
    var clientId: String
    var displayName: String
    var capabilities: Set<Capability>

    static func supports(_ peer: TrustedPeerRecord) -> Bool {
        peer.isTrusted
            && peer.ownDevice
            && requiredCapabilities.isSubset(of: Set(peer.announcedCapabilities))
    }
}

nonisolated enum KeyFingerprint {
    /// Short, human-glanceable fingerprint of a key's bytes (groups of 4 hex). Peer-facing fingerprints
    /// match because both sides hash the same transmitted bytes.
    static func short(_ bytes: Data) -> String {
        // Uppercase, colon-separated first-8-bytes of SHA-256 — byte-identical formatting to the Android
        // client's `KeyFingerprint.short`, so a user can eyeball-match the same key across an iPhone and an
        // Android device during pairing.
        NSHash.sha256(bytes).prefix(8).map { String(format: "%02X", $0) }.joined(separator: ":")
    }
}

/// High-level protocol orchestration shared by the app and the NSE: identity/operational/HPKE keys,
/// the trust roster, and seal/open/pairing. Replaces the stubbed `ProtocolBridge`. Holds no mutable
/// state of its own (the trust roster is reloaded from the App Group container per operation), so it is
/// safe to use from any isolation domain.
nonisolated final class NotiSyncEngine: Sendable {
    let selfClientId: String
    let selfIdentitySpki: Data
    let identityAlreadyExisted: Bool
    let recoveredAfterLocalStateLoss: Bool
    let localStateRecoveryKeychainBaseEpoch: Int

    let identityKeys: IdentityKeyStore
    let operationalKeys: OperationalKeyStore
    let hpkeKeys: HpkeKeyStore

    /// The current operational epoch — read from the App Group `RotationStore` per access (no cached mutable
    /// state, so the engine stays `Sendable` and a rotation's `advanceSelfEpoch` is observed immediately by
    /// the signers + the broker's operational-signer provider).
    var epoch: Int { RotationStore.load()?.selfEpoch ?? NotiSyncConfig.initialEpoch }

    /// Create the engine, ensuring identity/operational/HPKE keys exist for the current epoch and the self
    /// record is published to the App Group container (so the NSE knows our clientId + current epoch).
    init() throws {
        let identity = IdentityKeyStore()
        let identityAlreadyExisted = identity.exists()
        let persistedRotation = RotationStore.load()
        let keychainLatestEpoch = KeychainEpochStore.latestEpoch()
        let needsLocalStateRecovery = identityAlreadyExisted && persistedRotation == nil
        let seedEpoch: Int
        if needsLocalStateRecovery {
            seedEpoch = max(keychainLatestEpoch, NotiSyncConfig.initialEpoch - 1) + 1
        } else {
            seedEpoch = NotiSyncConfig.initialEpoch
        }
        let clientId = try identity.clientId()
        let spki = try identity.publicKeySpki()
        let rotation = persistedRotation ?? RotationStore.loadOrSeed(selfEpoch: seedEpoch)
        let epoch = rotation.selfEpoch

        let operational = OperationalKeyStore()
        _ = try operational.publicKeySpki(epoch: epoch)   // create on first use
        let hpke = HpkeKeyStore()
        _ = try hpke.loadOrCreate(epoch: epoch)

        self.identityKeys = identity
        self.operationalKeys = operational
        self.hpkeKeys = hpke
        self.selfClientId = clientId
        self.selfIdentitySpki = spki
        self.identityAlreadyExisted = identityAlreadyExisted
        self.recoveredAfterLocalStateLoss = needsLocalStateRecovery
        self.localStateRecoveryKeychainBaseEpoch = keychainLatestEpoch

        KeychainEpochStore.record(epoch: epoch)
        if let pendingTarget = rotation.pending?.targetEpoch {
            KeychainEpochStore.record(epoch: pendingTarget)
        }
        if needsLocalStateRecovery {
            KeychainEpochStore.setAPNsRouteResetPending(true)
        }
        SelfRecord(clientId: clientId, identitySpki: spki, currentEpoch: epoch).save()
    }

    /// NSE-only initializer: opens envelopes using the self record + shared HPKE keys, never the SE keys.
    init?(forExtension: Bool) {
        guard let record = SelfRecord.load() else { return nil }
        self.identityKeys = IdentityKeyStore()
        self.operationalKeys = OperationalKeyStore()
        self.hpkeKeys = HpkeKeyStore()
        self.selfClientId = record.clientId
        self.selfIdentitySpki = record.identitySpki
        self.identityAlreadyExisted = true
        self.recoveredAfterLocalStateLoss = false
        self.localStateRecoveryKeychainBaseEpoch = 0
    }

    // MARK: Signers

    var identitySigner: IdentitySigner { IdentitySigner(clientId: selfClientId, keyStore: identityKeys) }
    var operationalSigner: OperationalSigner { OperationalSigner(clientId: selfClientId, signerEpoch: epoch, keyStore: operationalKeys) }

    private func trust() -> TrustStore { TrustStore.load(selfClientId: selfClientId, selfIdentitySpki: selfIdentitySpki) }

    private func mutateTrust(_ change: (TrustStore) -> Bool) -> Bool {
        AppGroupStore.withLock(AppGroupStore.Files.trust) {
            let store = trust()
            guard change(store) else { return false }
            return save(store)
        }
    }

    // MARK: Build signed blobs

    /// The route-publish key-epoch for the current epoch: never-expiring (`notAfter = max`) between rotations.
    func buildClientKeyEpochBlob(stripIdentity: Bool = false) throws -> SignedBlob {
        try buildKeyEpochBlob(epoch: epoch, notBefore: 0, notAfter: Int64.max, minEpoch: epoch, stripIdentity: stripIdentity)
    }

    /// A key-epoch blob for an arbitrary epoch + validity window + floor — the rotation lifecycle builds the
    /// retiring (finite `notAfter`) and pre-warmed (future `notBefore`) blobs through this.
    func buildKeyEpochBlob(epoch: Int, notBefore: Int64, notAfter: Int64, minEpoch: Int, stripIdentity: Bool = false) throws -> SignedBlob {
        KeychainEpochStore.record(epoch: epoch)
        let ke = ClientKeyEpoch(
            clientId: selfClientId,
            identityPublicKey: stripIdentity ? Data() : selfIdentitySpki,
            epoch: epoch,
            operationalSigningKey: try operationalKeys.publicKeySpki(epoch: epoch),
            hpkePublicKey: try hpkeKeys.rawPublicKey(epoch: epoch),
            purposes: [.ENVELOPE_SIGN, .REQUEST_AUTH, .HPKE_SEAL],
            notBefore: notBefore, notAfter: notAfter, minEpoch: minEpoch
        )
        let payload = ProtocolCodec.encode(ke)
        return SignedBlob(typ: SignedType.keyEpoch, signerId: selfClientId, payload: payload, sig: try identityKeys.sign(payload))
    }

    func buildClientCardBlob(displayName: String) throws -> SignedBlob {
        let card = ClientCard(
            clientId: selfClientId, identityPublicKey: selfIdentitySpki, displayName: displayName,
            platform: NotiSyncConfig.platform,
            capabilities: iosSelfCapabilities,
            createdAt: Self.nowMillis()
        )
        let payload = ProtocolCodec.encode(card)
        return SignedBlob(typ: SignedType.clientCard, signerId: selfClientId, payload: payload, sig: try identityKeys.sign(payload))
    }

    func buildRouteClaimBlob(routeRef: String, environment: RouteEnvironment, routeEpoch: Int) throws -> SignedBlob {
        let claim = RouteClaim(
            clientId: selfClientId, transport: .APNS, environment: environment, routeRef: routeRef,
            capabilities: RouteCapabilities(inlinePayloadLimitBytes: NotiSyncConfig.inlinePayloadLimitBytes),
            epoch: routeEpoch, issuedAt: Self.nowMillis()
        )
        let payload = ProtocolCodec.encode(claim)
        return SignedBlob(typ: SignedType.routeClaim, signerId: selfClientId, payload: payload, sig: try identityKeys.sign(payload))
    }

    func serverEpoch(from blob: SignedBlob) -> Int? {
        guard blob.typ == SignedType.keyEpoch, blob.signerId == selfClientId,
              let keyEpoch = try? ProtocolCodec.decodeClientKeyEpoch(blob.payload),
              keyEpoch.clientId == selfClientId else {
            return nil
        }
        return max(keyEpoch.epoch, 0)
    }

    // MARK: Open

    /// Verify + open an envelope. Throws `unresolvedSender` if we hold no keys for the sender (caller may
    /// refetch the sender's key-epoch and retry).
    func openEnvelope(_ bytes: Data) throws -> (envelope: Envelope, inbound: DecodedInbound) {
        let env = try ProtocolCodec.decodeEnvelope(bytes)
        let store = trust()
        // Sender not in the roster at all → drop silently (no key-epoch refetch; we'd never resolve it).
        guard let peer = store.peers[env.signerId] else { throw EngineError.unknownSender(env.signerId) }
        // #2 — only a currently-TRUSTED sender is accepted. A pending/revoked peer whose keys still linger
        // in the roster must not be able to deliver notifications, dismissals, or data-sync.
        guard peer.isTrusted else { throw EngineError.untrustedSender(env.signerId) }
        // Trusted, but we hold no usable key-epoch for the claimed signer epoch → refetch + retry (this is
        // the only case worth a broker pull, since the sender IS a peer we already trust).
        guard let spki = store.verifierSpki(signerId: env.signerId, signerEpoch: env.signerEpoch) else {
            throw EngineError.unresolvedSender(env.signerId)
        }
        guard EnvelopeCrypto.verify(env, signerSpki: spki) else { throw EngineError.verificationFailed }
        guard let mine = env.recipients.first(where: { $0.recipientId == selfClientId }) else { throw EngineError.notForUs }
        guard let priv = hpkeKeys.privateKey(epoch: mine.recipientEpoch) else { throw EngineError.noHpkeKey(mine.recipientEpoch) }
        let body = try EnvelopeCrypto.open(env, myClientId: selfClientId, privateKey: priv)
        let inbound: DecodedInbound
        switch env.typ {
        case .NOTIFICATION: inbound = .notification(try ProtocolCodec.decodeCapturedNotification(body))
        case .DISMISSAL: inbound = .dismissal(try ProtocolCodec.decodeDismissEvent(body))
        case .DATA_SYNC: inbound = .dataSync(try ProtocolCodec.decodeDataSync(body))
        // ACTION is Android-origin-only (the broker unicasts it to the capturing client); an iOS client
        // can only see one through a routing bug. Don't throw — the item must still ack, or the relay
        // redelivers it forever.
        case .ACTION: inbound = .unsupported(.ACTION)
        }
        return (env, inbound)
    }

    func envelopeMessageId(_ bytes: Data) throws -> String { try ProtocolCodec.envelopeMessageId(bytes) }

    // MARK: Seal (outbound: dismissals, asset-repair data-sync)

    /// Seal a DISMISSAL to the own-mesh (operational-signed). Returns nil if no sealable peers exist.
    func sealDismissal(sourceClientId: String, sourceKey: String) throws -> Envelope? {
        let recipients = trust().ownMeshRecipients(excluding: selfClientId)
        guard !recipients.isEmpty else { return nil }
        let body = ProtocolCodec.encode(DismissEvent(sourceClientId: sourceClientId, sourceKey: sourceKey, dismissedAt: Self.nowMillis()))
        return try EnvelopeCrypto.seal(signer: operationalSigner, typ: .DISMISSAL, bodyPlaintext: body,
                                       recipients: recipients, messageId: Self.newMessageId(), seq: Self.nextSeq(), createdAt: Self.nowMillis())
    }

    /// Batch form of `sealDismissal` (the Inbox's Read All): one trust-roster load + signature verify for
    /// the whole set instead of one per event. Returns [] if no sealable own-mesh peers exist.
    func sealDismissals(_ pairs: [DismissedSourcePair]) throws -> [Envelope] {
        guard !pairs.isEmpty else { return [] }
        let recipients = trust().ownMeshRecipients(excluding: selfClientId)
        guard !recipients.isEmpty else { return [] }
        return try pairs.map { pair in
            let body = ProtocolCodec.encode(DismissEvent(
                sourceClientId: pair.sourceClientId, sourceKey: pair.sourceKey, dismissedAt: Self.nowMillis()))
            return try EnvelopeCrypto.seal(signer: operationalSigner, typ: .DISMISSAL, bodyPlaintext: body,
                                           recipients: recipients, messageId: Self.newMessageId(),
                                           seq: Self.nextSeq(), createdAt: Self.nowMillis())
        }
    }

    /// Seal an ACTION event — the user acted on a mirrored notification — unicast to the ORIGIN client
    /// only (the one peer that can replay it on the real notification). Operational-signed, like a
    /// dismissal. Nil if the origin isn't a trusted, currently-sealable own-mesh peer.
    func sealAction(_ event: ActionEvent) throws -> Envelope? {
        guard let peer = trust().peers[event.sourceClientId], peer.isTrusted, peer.ownDevice,
              let e = peer.sealable(now: Self.nowMillis()) else { return nil }
        let body = ProtocolCodec.encode(event)
        let recipient = EnvelopeCrypto.RecipientKey(clientId: peer.clientId, hpkePublicKey: e.hpkePublicKey, recipientEpoch: e.epoch)
        return try EnvelopeCrypto.seal(signer: operationalSigner, typ: .ACTION, bodyPlaintext: body,
                                       recipients: [recipient], messageId: Self.newMessageId(), seq: Self.nextSeq(), createdAt: Self.nowMillis())
    }

    /// Seal a DATA_SYNC asset-repair request (consumer → provider) for assets that failed to fetch/decrypt.
    func sealAssetRepairRequest(to provider: String, items: [AssetSyncItem]) throws -> Envelope? {
        guard let peer = trust().peers[provider], peer.isTrusted, let e = peer.sealable(now: Self.nowMillis()) else { return nil }
        let body = ProtocolCodec.encode(DataSync(kind: .ASSET, asset: AssetSync(kind: .ASSET_MISSING, items: items)))
        let recipient = EnvelopeCrypto.RecipientKey(clientId: peer.clientId, hpkePublicKey: e.hpkePublicKey, recipientEpoch: e.epoch)
        return try EnvelopeCrypto.seal(signer: operationalSigner, typ: .DATA_SYNC, bodyPlaintext: body,
                                       recipients: [recipient], messageId: Self.newMessageId(), seq: Self.nextSeq(), createdAt: Self.nowMillis())
    }

    /// Seal a DATA_SYNC notification-filter snapshot (requester → source peer) asking the peer to stop
    /// delivering matching notifications to us. A full snapshot (an empty [rules] clears it); the receiver
    /// keys it by our signer id and resolves races last-writer-wins on [updatedAt]. Nil if the recipient is
    /// not a trusted, currently-sealable peer.
    func sealNotificationFilter(to recipientId: String, rules: [NotificationFilterRule], updatedAt: Int64) throws -> Envelope? {
        guard let peer = trust().peers[recipientId], peer.isTrusted,
              peer.announcedCapabilities.contains(.CAPTURE),
              let e = peer.sealable(now: Self.nowMillis()) else { return nil }
        let body = ProtocolCodec.encode(DataSync(kind: .FILTER, filter: FilterSync(rules: rules, updatedAt: updatedAt)))
        let recipient = EnvelopeCrypto.RecipientKey(clientId: peer.clientId, hpkePublicKey: e.hpkePublicKey, recipientEpoch: e.epoch)
        return try EnvelopeCrypto.seal(signer: operationalSigner, typ: .DATA_SYNC, bodyPlaintext: body,
                                       recipients: [recipient], messageId: Self.newMessageId(), seq: Self.nextSeq(), createdAt: Self.nowMillis())
    }

    /// Seal one screen-session lifecycle message to a trusted own Android source. REQUEST is additionally
    /// capability-gated to the complete v1 source and H.264 hardware encoder declaration.
    func sealScreenMirrorSync(_ sync: ScreenMirrorSync) throws -> Envelope? {
        let store = trust()
        guard sync.requesterPeerId == selfClientId,
              let peer = store.peers[sync.sourcePeerId], peer.isTrusted, peer.ownDevice,
              let epoch = peer.sealable(now: Self.nowMillis()) else { return nil }
        if sync.action == .REQUEST {
            let required: Set<Capability> = [
                .CAPABILITY_ROUTING_V1,
                .SCREEN_MIRROR_SOURCE_V1,
                .SCREEN_MIRROR_CONTROL_V1,
                .SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
                .SCREEN_MIRROR_ENCODER_H264_HW,
            ]
            guard required.isSubset(of: Set(peer.announcedCapabilities)), sync.codec == .H264 else { return nil }
        }
        let body = ProtocolCodec.encode(DataSync(kind: .SCREEN_MIRRORING, screenMirror: sync))
        let recipient = EnvelopeCrypto.RecipientKey(
            clientId: peer.clientId,
            hpkePublicKey: epoch.hpkePublicKey,
            recipientEpoch: epoch.epoch
        )
        return try EnvelopeCrypto.seal(
            signer: operationalSigner,
            typ: .DATA_SYNC,
            bodyPlaintext: body,
            recipients: [recipient],
            messageId: Self.newMessageId(),
            seq: Self.nextSeq(),
            createdAt: Self.nowMillis()
        )
    }

    // MARK: Assets

    /// Fetch + decrypt + integrity-check a private asset blob via the broker. Returns plaintext or nil.
    func openAsset(_ ref: PrivateAssetRef, ciphertext: Data) -> Data? {
        guard let plaintext = try? AssetCrypto.open(ref, sealed: ciphertext) else { return nil }
        guard AssetHash.matches(plaintext, expectedHex: ref.assetHash) else { return nil }
        return plaintext
    }

    // MARK: Key-epoch convergence

    /// Apply a key-epoch fetched from the broker (self-heal for a peer we hold no sealable epoch for).
    @discardableResult
    func applyFetchedKeyEpoch(_ blob: SignedBlob) -> Bool {
        mutateTrust { store in
            guard let existing = store.peers[blob.signerId] else { return false }   // only for peers we already hold
            // A mesh-introduced peer whose card hasn't arrived has an empty identity anchor — verify the
            // (self-authenticating) key-epoch against ITS carried identity, then pin that identity. (#3)
            let pinned = existing.identitySpki.isEmpty ? nil : existing.identitySpki
            guard let ke = KeyEpochs.verify(blob, pinnedIdentitySpki: pinned) else { return false }
            var changed = false
            if existing.identitySpki.isEmpty, !ke.identityPublicKey.isEmpty {
                store.setIdentity(ke.clientId, spki: ke.identityPublicKey)
                changed = true
            }
            if store.applyKeyEpoch(ke, blob: blob) { changed = true }           // may reject below-floor / no change
            return changed
        }
    }

    func peersNeedingKeyEpoch() -> [String] { trust().peersNeedingKeyEpoch() }

    /// The highest key-epoch we currently hold for a peer (0 = none). Used to decide whether a roster
    /// advertises a peer at an epoch ahead of us (a convergence-pull trigger, incl. pending devices).
    func peerEpoch(_ clientId: String) -> Int { trust().peers[clientId]?.currentEpoch ?? 0 }

    /// Batch form of `peerEpoch` for roster convergence; loads the signed trust store once per table.
    func peerEpochs(_ clientIds: [String]) -> [String: Int] {
        let peers = trust().peers
        return Dictionary(uniqueKeysWithValues: clientIds.map { ($0, peers[$0]?.currentEpoch ?? 0) })
    }

    /// Whether a (trusted) sender is one of this user's own-mesh devices — gates own-mesh-only inbound
    /// (e.g. CARD key-epoch relays), mirroring Android's `SendPolicy.mayAccept`.
    func isOwnDevice(_ clientId: String) -> Bool { trust().peers[clientId]?.ownDevice ?? false }

    // MARK: Self key-rotation state + building blocks (#8)

    func pendingRotation() -> SelfPendingRotation? { RotationStore.load()?.pending }
    func selfActivatedAt() -> Int64 { RotationStore.load()?.activatedAt ?? 0 }

    func setPendingRotation(_ p: SelfPendingRotation?) {
        var state = RotationStore.loadOrSeed()
        state.pending = p
        state.save()
    }

    /// Advance the persisted self-epoch (rotation activation). Re-stamps `activatedAt` (the cadence clock) and
    /// the `SelfRecord` the NSE reads. No-op if `target` isn't an advance.
    func advanceSelfEpoch(to target: Int) {
        var state = RotationStore.loadOrSeed()
        guard target > state.selfEpoch else { return }
        state.selfEpoch = target
        state.activatedAt = Self.nowMillis()
        state.save()
        KeychainEpochStore.record(epoch: target)
        SelfRecord(clientId: selfClientId, identitySpki: selfIdentitySpki, currentEpoch: target).save()
    }

    /// Recover when the identity key survived but the local App Group/SwiftData epoch counter did not.
    /// The new live epoch and floor are strictly above both the durable keychain high-water and the broker's
    /// latest self epoch, preventing reuse of an epoch already accepted by peers or the broker.
    @discardableResult
    func recoverSelfEpochAfterLocalStateLoss(serverLatestEpoch: Int, force: Bool = false) throws -> Int {
        let keychainBase = force ? KeychainEpochStore.latestEpoch() : localStateRecoveryKeychainBaseEpoch
        let target = max(keychainBase, serverLatestEpoch, NotiSyncConfig.initialEpoch - 1) + 1
        if force || target > epoch || pendingRotation() != nil {
            try ensureEpochKeys(target)
            let state = RotationStore(
                selfEpoch: target,
                activatedAt: Self.nowMillis(),
                pending: nil
            )
            state.save()
            SelfRecord(clientId: selfClientId, identitySpki: selfIdentitySpki, currentEpoch: target).save()
        } else {
            try ensureEpochKeys(epoch)
        }
        KeychainEpochStore.record(epoch: max(epoch, target))
        KeychainEpochStore.setAPNsRouteResetPending(true)
        return epoch
    }

    /// Mint (generate-on-first-use) the operational + HPKE keys for `epoch`.
    func ensureEpochKeys(_ epoch: Int) throws {
        _ = try operationalKeys.publicKeySpki(epoch: epoch)
        _ = try hpkeKeys.loadOrCreate(epoch: epoch)
    }

    /// Destroy the operational + HPKE private keys for `epoch` (retirement / forward-secrecy GC).
    func destroyEpoch(_ epoch: Int) {
        operationalKeys.destroy(epoch: epoch)
        hpkeKeys.destroy(epoch: epoch)
    }

    /// Seal a pre-warm key-epoch announce (CARD/epochBlob) to own-mesh, identity-signed, so peers cache the
    /// next epoch before its activation without polling the broker. Nil if there are no own-mesh recipients.
    func sealKeyEpochAnnounce(_ blob: SignedBlob) throws -> Envelope? {
        let store = trust()
        let recipients = store.ownMeshRecipients(excluding: selfClientId)
        guard !recipients.isEmpty else { return nil }
        let body = ProtocolCodec.encode(DataSync(kind: .CARD, card: CardDelivery(clientId: selfClientId, card: nil, epochBlob: blob)))
        return try EnvelopeCrypto.seal(signer: identitySigner, typ: .DATA_SYNC, bodyPlaintext: body,
                                       recipients: recipients, messageId: Self.newMessageId(), seq: Self.nextSeq(), createdAt: Self.nowMillis())
    }

    /// Forward-secrecy backstop: when no rotation is in flight, destroy operational + HPKE keys for every
    /// epoch strictly below the live one (covers a retirement whose on-device delete was skipped/failed).
    func gcStaleEpochs() {
        guard pendingRotation() == nil else { return }
        let live = epoch
        guard live > 1 else { return }
        for e in 1..<live { destroyEpoch(e) }
    }

    /// Diagnostics snapshot of the live epoch's public key material + rotation schedule (public keys only).
    func rotationKeyInfo() -> RotationKeyInfo {
        let e = epoch
        let pending = pendingRotation()
        let activated = pending != nil && e >= (pending?.targetEpoch ?? 0)
        let nextAt: Int64
        if let p = pending {
            nextAt = activated ? p.retireRetiredAt : p.notBefore
        } else {
            let act = selfActivatedAt()
            nextAt = act > 0 ? act + NotiSyncConfig.Rotation.intervalMs : 0
        }
        let signFp = (try? operationalKeys.publicKeySpki(epoch: e)).map { KeyFingerprint.short($0) } ?? "—"
        let encFp = (try? hpkeKeys.rawPublicKey(epoch: e)).map { KeyFingerprint.short($0) } ?? "—"
        return RotationKeyInfo(epoch: e, signingKeyFingerprint: signFp, encryptionKeyFingerprint: encFp,
                               pendingTargetEpoch: pending?.targetEpoch, pendingActivated: activated, nextEventAtMillis: nextAt)
    }

    // MARK: Trust / profile convergence (#5/#6)

    /// Apply an inbound `ProfileUpdate` (rename / platform change). Requires the update to be about its own
    /// sender (a peer can't rename another) and to be newer than what we hold (last-writer-wins).
    @discardableResult
    func applyProfile(_ update: ProfileUpdate, from signerId: String) -> Bool {
        guard update.clientId == signerId else { return false }
        return mutateTrust { store in
            guard let record = store.peers[update.clientId], record.isTrusted else { return false }
            let cardFloor = CardStore.blob(update.clientId)
                .flatMap { try? ProtocolCodec.decodeClientCard($0.payload) }?.createdAt ?? 0
            guard update.updatedAt > max(record.profileRevision, cardFloor) else { return false }
            store.setProfile(update.clientId, displayName: update.displayName, platform: update.platform,
                             capabilities: update.capabilities, at: update.updatedAt)
            return true
        }
    }

    /// Fold an inbound `TrustTable` from an own-mesh device into our roster and surface any card/key-epoch
    /// repair material the sender advertised it needs. The table MUST be identity-signed (a roster assertion
    /// binds to the immutable root, §2.3) and only an own-device sender's table is honored.
    func applyTrustTableWithRepairs(_ table: TrustTable, from signerId: String, signerEpoch: Int) -> IncomingTrustResult? {
        // A roster assertion MUST bind to the immutable identity root (§2.3) and come from an own-mesh device.
        guard signerEpoch == 0 else { return nil }
        return AppGroupStore.withLock(AppGroupStore.Files.trust) {
            let store = trust()
            guard let sender = store.peers[signerId], sender.isTrusted, sender.ownDevice,
                  !sender.isExperienceMode else { return nil }
            let result = store.applyIncomingTable(sender: signerId, table: table, now: Self.nowMillis())
            if result.changed { save(store) }
            return result
        }
    }

    @discardableResult
    func applyTrustTable(_ table: TrustTable, from signerId: String, signerEpoch: Int) -> Bool {
        applyTrustTableWithRepairs(table, from: signerId, signerEpoch: signerEpoch)?.changed ?? false
    }

    /// Apply a delivered (self-authenticating) client card — names a peer introduced by a mesh roster. (#3)
    @discardableResult
    func applyDeliveredCard(_ cardBlob: SignedBlob) -> Bool {
        mutateTrust { store in
            store.applyCard(cardBlob, now: Self.nowMillis())   // persists CardStore internally
        }
    }

    /// Locally revoke a peer (Devices UI). Returns false if unknown.
    @discardableResult
    func revoke(_ clientId: String) -> Bool {
        mutateTrust { store in
            guard store.peers[clientId] != nil else { return false }
            store.setStatus(clientId, .REVOKED, at: Self.nowMillis())
            return true
        }
    }

    /// Delete (not revoke) every Experience Mode peer from the roster + drop their held cards — a clean slate
    /// before a new Experience Mode session. Returns the removed client ids so the app can prune mirror rows.
    @discardableResult
    func pruneExperiencePeers() -> [String] {
        var removed: [String] = []
        _ = mutateTrust { store in
            removed = store.removeExperiencePeers()
            return !removed.isEmpty
        }
        for id in removed { CardStore.remove(id) }
        return removed
    }

    /// Run the versioned local cleanup and durably replace even an unreadable/quarantined legacy trust file
    /// with the verified remainder. Nil means persistence failed, so the caller must leave its marker unset.
    func cleanupUnverifiedPeersV1() -> [String]? {
        AppGroupStore.withLock(AppGroupStore.Files.trust) {
            let store = trust()
            let removed = store.removeUnverifiedPeers()
            guard save(store) else { return nil }
            for id in removed { CardStore.remove(id) }
            return removed
        }
    }

    /// Approve a PENDING_TRUST device → TRUSTED (the user accepts a mesh introduction). (#3)
    @discardableResult func approveTrust(_ clientId: String) -> Bool { mutateTrust { $0.approveTrust(clientId, at: Self.nowMillis()) } }
    /// Reject a PENDING_TRUST device → REVOKED (overturn — propagates). (#3)
    @discardableResult func rejectTrust(_ clientId: String) -> Bool { mutateTrust { $0.rejectTrust(clientId, at: Self.nowMillis()) } }
    /// Confirm a PENDING_REVOKE device → REVOKED (agree with the revoker). (#3)
    @discardableResult func confirmRevoke(_ clientId: String) -> Bool { mutateTrust { $0.confirmRevoke(clientId, at: Self.nowMillis()) } }
    /// Keep a PENDING_REVOKE device → TRUSTED (overturn the revoker — propagates). (#3)
    @discardableResult func keepTrusted(_ clientId: String) -> Bool { mutateTrust { $0.keepTrusted(clientId, at: Self.nowMillis()) } }

    /// Seal this device's trust roster to own-mesh, IDENTITY-signed (a roster assertion must bind to the
    /// identity root and verify without epoch convergence). Returns nil if there are no own-mesh recipients.
    func sealTrustTable() throws -> Envelope? {
        let store = trust()
        let recipients = store.ownMeshRecipients(excluding: selfClientId, includingExperience: false)
        guard !recipients.isEmpty else { return nil }
        let body = ProtocolCodec.encode(DataSync(kind: .TRUST, trust: store.buildTrustTable(excluding: selfClientId)))
        return try EnvelopeCrypto.seal(signer: identitySigner, typ: .DATA_SYNC, bodyPlaintext: body,
                                       recipients: recipients, messageId: Self.newMessageId(), seq: Self.nextSeq(), createdAt: Self.nowMillis())
    }

    /// Seal only this device's current key epoch alongside its trust roster. Third-party cards and epochs are
    /// sent on demand by `sealCardMaterialRepair` when a peer advertises a gap; broadcasting all
    /// held cards here makes every anti-entropy heartbeat grow with the mesh and creates large CARD bursts.
    func sealTrustSelfKeyEpoch() throws -> Envelope? {
        // The rotation lifecycle owns the exact finite windows/floor while staged. A generic current-epoch
        // certificate here would overwrite those semantics on receivers.
        guard pendingRotation() == nil else { return nil }
        let store = trust()
        let recipients = store.ownMeshRecipients(excluding: selfClientId, includingExperience: false)
        guard !recipients.isEmpty else { return nil }
        let blob = try buildClientKeyEpochBlob()
        let body = ProtocolCodec.encode(
            DataSync(kind: .CARD, card: CardDelivery(clientId: selfClientId, card: nil, epochBlob: blob))
        )
        return try EnvelopeCrypto.seal(signer: identitySigner, typ: .DATA_SYNC, bodyPlaintext: body,
                                       recipients: recipients, messageId: Self.newMessageId(), seq: Self.nextSeq(),
                                       createdAt: Self.nowMillis())
    }

    /// Unicast all held self-authenticating material for one subject to the own-mesh peer that advertised
    /// the gap. Combining card + epoch avoids two envelopes when the receiver lacks both.
    func sealCardMaterialRepair(to recipientId: String, subjectId: String,
                                card: SignedBlob?, epochBlob: SignedBlob?) throws -> Envelope? {
        guard card != nil || epochBlob != nil else { return nil }
        if let card, card.signerId != subjectId { return nil }
        if let epochBlob, epochBlob.signerId != subjectId { return nil }
        guard let recipient = ownMeshRecipient(recipientId) else { return nil }
        let body = ProtocolCodec.encode(
            DataSync(kind: .CARD,
                     card: CardDelivery(clientId: subjectId, card: card, epochBlob: epochBlob))
        )
        return try EnvelopeCrypto.seal(signer: operationalSigner, typ: .DATA_SYNC, bodyPlaintext: body,
                                       recipients: [recipient], messageId: Self.newMessageId(), seq: Self.nextSeq(), createdAt: Self.nowMillis())
    }

    private func ownMeshRecipient(_ clientId: String) -> EnvelopeCrypto.RecipientKey? {
        let store = trust()
        guard let peer = store.peers[clientId], peer.isTrusted, peer.ownDevice, !peer.isExperienceMode,
              let e = peer.sealable(now: Self.nowMillis()) else { return nil }
        return EnvelopeCrypto.RecipientKey(clientId: peer.clientId, hpkePublicKey: e.hpkePublicKey, recipientEpoch: e.epoch)
    }

    /// Seal a `ProfileUpdate` (this device's rename) to every trusted device — own AND "other" contacts.
    func sealProfileUpdate(displayName: String, updatedAt: Int64 = NotiSyncEngine.nowMillis()) throws -> Envelope? {
        let store = trust()
        let recipients = store.allTrustedRecipients(excluding: selfClientId)
        guard !recipients.isEmpty else { return nil }
        let update = ProfileUpdate(clientId: selfClientId, displayName: displayName, platform: NotiSyncConfig.platform,
                                   capabilities: iosSelfCapabilities,
                                   updatedAt: updatedAt)
        let body = ProtocolCodec.encode(DataSync(kind: .PROFILE, profile: update))
        return try EnvelopeCrypto.seal(signer: operationalSigner, typ: .DATA_SYNC, bodyPlaintext: body,
                                       recipients: recipients, messageId: Self.newMessageId(), seq: Self.nextSeq(), createdAt: Self.nowMillis())
    }

    /// Stable persisted-profile input. Changes whenever the name/platform/capability declaration changes.
    func selfProfileFingerprint(displayName: String) -> String {
        ([displayName, NotiSyncConfig.platform] + iosSelfCapabilities.map(\.rawValue))
            .joined(separator: "\u{1f}")
    }

    // MARK: Pairing (bidirectional QR / CardDelivery)

    func pairingPayload(displayName: String) throws -> String {
        let delivery = CardDelivery(clientId: selfClientId,
                                    card: try buildClientCardBlob(displayName: displayName),
                                    epochBlob: try buildClientKeyEpochBlob(stripIdentity: true))
        return NSBase64URL.encode(ProtocolCodec.encode(delivery))
    }

    /// Verify a scanned peer payload and return displayable details before the user confirms trust.
    func inspectPairing(_ scanned: String) throws -> PairingCandidate {
        let payload = Self.extractPairingPayload(scanned)
        let (cardBlob, card, epochBlob) = try decodeVerifiedDelivery(payload)
        let ke = epochBlob.flatMap { KeyEpochs.verify($0, pinnedIdentitySpki: card.identityPublicKey) }
        let status: KeyEpochStatus = epochBlob == nil ? .absent : (ke == nil ? .invalid : .verified)
        _ = cardBlob
        return PairingCandidate(
            payload: payload, displayName: card.displayName, platform: card.platform, clientId: card.clientId,
            safetyNumber: card.clientId, identityKeyFingerprint: KeyFingerprint.short(card.identityPublicKey),
            epoch: ke?.epoch ?? 0,
            operationalKeyFingerprint: ke.map { KeyFingerprint.short($0.operationalSigningKey) } ?? "",
            hpkeKeyFingerprint: ke.map { KeyFingerprint.short($0.hpkePublicKey) } ?? "",
            keyEpochStatus: status
        )
    }

    /// Accept a scanned peer: pin its identity + key-epoch and trust it. Returns the peer's display name.
    @discardableResult
    func acceptPairing(_ scanned: String, ownDevice: Bool = true) throws -> String {
        let payload = Self.extractPairingPayload(scanned)
        let (cardBlob, card, epochBlob) = try decodeVerifiedDelivery(payload)
        guard card.clientId != selfClientId else { throw EngineError.notForUs }
        let now = Self.nowMillis()
        let pairedName: String? = AppGroupStore.withLock(AppGroupStore.Files.trust) {
            let store = trust()
            guard let put = CardStore.put(card.clientId, blob: cardBlob, now: now) else { return nil }
            // CardStore and the signed roster are separate files. If an earlier write stored a newer card but
            // did not finish the roster update, pair from that effective newest snapshot rather than pinning
            // stale scanned profile data over it.
            store.pin(card: put.card, ownDevice: ownDevice, at: now)
            if let epochBlob,
               let ke = KeyEpochs.verify(epochBlob, pinnedIdentitySpki: put.card.identityPublicKey) {
                store.applyKeyEpoch(ke, blob: epochBlob)
            }
            return save(store) ? put.card.displayName : nil
        }
        guard let pairedName else { throw EngineError.persistenceFailed }
        return pairedName
    }

    private func decodeVerifiedDelivery(_ payload: String) throws -> (SignedBlob, ClientCard, SignedBlob?) {
        guard let raw = NSBase64URL.decode(payload.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw CodecError.typeMismatch("pairing payload")
        }
        let delivery: CardDelivery
        if let parsed = try? ProtocolCodec.decodeCardDelivery(raw), parsed.card != nil {
            delivery = parsed
        } else {
            delivery = CardDelivery(clientId: selfClientId, card: try ProtocolCodec.decodeSignedBlob(raw))
        }
        guard let cardBlob = delivery.card, cardBlob.typ == SignedType.clientCard else {
            throw CodecError.missingField("client card")
        }
        let card = try ProtocolCodec.decodeClientCard(cardBlob.payload)
        guard card.clientId == cardBlob.signerId,
              ClientCardFreshness.accepts(createdAt: card.createdAt, now: Self.nowMillis()),
              IdentityVerifier.verifyBound(expectedSignerId: cardBlob.signerId, spki: card.identityPublicKey,
                                           data: cardBlob.payload, signature: cardBlob.sig) else {
            throw EngineError.verificationFailed
        }
        return (cardBlob, card, delivery.epochBlob)
    }

    func trustedPeers() -> [TrustedPeerRecord] { Array(trust().peers.values) }

    func screenMirrorSources() -> [ScreenMirrorSourceRecord] {
        return trust().peers.values.compactMap { peer in
            let capabilities = Set(peer.announcedCapabilities)
            guard ScreenMirrorSourceRecord.supports(peer) else { return nil }
            return ScreenMirrorSourceRecord(
                clientId: peer.clientId,
                displayName: peer.displayName,
                capabilities: capabilities
            )
        }
    }

    @discardableResult
    private func save(_ store: TrustStore) -> Bool {
        store.save(sign: { try identityKeys.sign($0) })
    }

    // MARK: Helpers

    static func extractPairingPayload(_ scanned: String) -> String {
        let trimmed = scanned.trimmingCharacters(in: .whitespacesAndNewlines)
        if let comps = URLComponents(string: trimmed), let item = comps.queryItems?.first(where: { $0.name == "payload" })?.value {
            return item
        }
        return trimmed
    }

    static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    static func newMessageId() -> String { "ios.\(UUID().uuidString)" }

    static func nextSeq() -> Int64 {
        AppGroupStore.withLock(AppGroupStore.Files.sequence) {
            let stored = AppGroupStore.read(SequenceState.self, AppGroupStore.Files.sequence)?.next
            let next = max(stored ?? nowMillis(), nowMillis())
            AppGroupStore.write(SequenceState(next: next + 1), AppGroupStore.Files.sequence)
            return next
        }
    }
}
