import Foundation

/// One operational epoch of a peer: the rotatable operational signing key + raw HPKE key for that epoch,
/// plus the validity window and the key's declared purposes (NS2 §5/§6).
nonisolated struct EpochRecord: Codable, Sendable {
    var epoch: Int
    var operationalSpki: Data
    var hpkePublicKey: Data
    var notBefore: Int64
    var notAfter: Int64
    /// `Purpose` rawValues the published key-epoch declared (e.g. ENVELOPE_SIGN). Optional for old
    /// records → treated as the full default set so a pre-upgrade roster keeps verifying.
    var purposes: [String]? = nil
    /// Original CBOR(SignedBlob) for this key-epoch, when we learned a self-contained blob. Kept so we can
    /// relay a peer's key-epoch to another own-mesh device that trusts that peer but lacks its epoch.
    var signedBlob: Data? = nil

    func allows(_ purpose: Purpose) -> Bool { (purposes ?? Purpose.allRawValues).contains(purpose.rawValue) }
}

nonisolated struct IncomingTrustResult: Sendable {
    var changed: Bool
    var cardsToOffer: [SignedBlob]
    var keyEpochsToOffer: [SignedBlob]
    var needsBroadcast: Bool
}

/// A trusted peer: its immutable identity anchor + profile + the ring of key epochs it has published, and
/// the monotonic anti-rollback `floor` (NS2 §6) — the lowest epoch we will accept; a key-epoch below it is a
/// retired/superseded key and is rejected.
nonisolated struct TrustedPeerRecord: Codable, Sendable {
    var clientId: String
    var identitySpki: Data
    var displayName: String
    var platform: String
    var status: String                 // TrustStatus rawValue
    var ownDevice: Bool
    var updatedAt: Int64
    var epochs: [EpochRecord]
    var currentEpoch: Int
    /// Optional for migration: old trust files did not persist peer capability declarations.
    var capabilities: [Capability]? = nil
    /// Profile LWW clock, separate from trust-state [updatedAt]. Optional for migration.
    var profileUpdatedAt: Int64? = nil
    /// Monotonic anti-rollback floor. Optional for old records → 0 (no floor yet). (#1)
    var floor: Int? = nil
    /// Local provenance: the peer whose roster broadcast first surfaced this device to us (a mesh
    /// introduction). Null when our own user established it (optical scan / manual). Never sent on the wire. (#3)
    var introducedBy: String? = nil

    func epoch(_ e: Int) -> EpochRecord? { epochs.first { $0.epoch == e } }

    /// The epoch to seal to: the highest one that is at or above the floor AND whose `notBefore` has
    /// arrived (never a pre-warmed, not-yet-active or retired/floored epoch). (#1, #7)
    func sealable(now: Int64) -> EpochRecord? {
        epochs.filter { $0.epoch >= (floor ?? 0) && $0.notBefore <= now }.max { $0.epoch < $1.epoch }
    }

    var isTrusted: Bool { status == TrustStatus.TRUSTED.rawValue }
    var announcedCapabilities: [Capability] { capabilities ?? [] }
    var profileRevision: Int64 { profileUpdatedAt ?? 0 }
    /// True when this peer is the broker's Experience Mode demo device, identified by the server-set,
    /// identity-signed card `platform` (pinned verbatim at pairing). Experience peers are pruned (deleted,
    /// not revoked) before a new Experience session and never appear in — nor receive — the trust roster.
    var isExperienceMode: Bool { platform == NotiSyncConfig.experiencePlatform }
}

private nonisolated struct TrustFile: Codable, Sendable {
    var peers: [TrustedPeerRecord]
    var signature: Data
}

/// The device's local, identity-signed trust roster, persisted in the App Group container so the NSE can
/// read sender keys/epochs to verify + open envelopes. Mirrors `app/.../data/TrustStore.kt` + the
/// four-section `TrustStoreSigning` (self-consistency only on iOS).
nonisolated final class TrustStore {
    let selfClientId: String
    let selfIdentitySpki: Data
    private(set) var peers: [String: TrustedPeerRecord]

    private static let signVersion = "notisync-truststore-sign-v1"

    init(selfClientId: String, selfIdentitySpki: Data, peers: [String: TrustedPeerRecord] = [:]) {
        self.selfClientId = selfClientId
        self.selfIdentitySpki = selfIdentitySpki
        self.peers = peers
    }

    /// Load + verify the persisted roster. A bad/absent signature quarantines (returns empty) — a local
    /// attacker can't promote a device to TRUSTED without the identity key.
    static func load(selfClientId: String, selfIdentitySpki: Data) -> TrustStore {
        let store = TrustStore(selfClientId: selfClientId, selfIdentitySpki: selfIdentitySpki)
        guard let file = AppGroupStore.read(TrustFile.self, AppGroupStore.Files.trust) else { return store }
        let canonical = Self.canonical(selfId: selfClientId, peers: file.peers)
        guard IdentityVerifier.verify(spki: selfIdentitySpki, data: canonical, signature: file.signature) else {
            return store     // tamper / wrong identity → quarantine
        }
        store.peers = Dictionary(uniqueKeysWithValues: file.peers.map { ($0.clientId, $0) })
        return store
    }

    /// Re-sign and persist. `sign` is the identity-key signer (app process only).
    func save(sign: (Data) throws -> Data) {
        let list = peers.values.sorted { $0.clientId < $1.clientId }
        guard let signature = try? sign(Self.canonical(selfId: selfClientId, peers: list)) else { return }
        AppGroupStore.write(TrustFile(peers: list, signature: signature), AppGroupStore.Files.trust)
    }

    // MARK: Mutation

    /// Pin a peer's identity + profile (pairing / card delivery). Preserves any existing epoch ring.
    func pin(card: ClientCard, ownDevice: Bool, at nowMillis: Int64) {
        var record = peers[card.clientId] ?? TrustedPeerRecord(
            clientId: card.clientId, identitySpki: card.identityPublicKey, displayName: card.displayName,
            platform: card.platform, status: TrustStatus.TRUSTED.rawValue, ownDevice: ownDevice,
            updatedAt: nowMillis, epochs: [], currentEpoch: 0,
            capabilities: card.capabilities, profileUpdatedAt: card.createdAt
        )
        record.identitySpki = card.identityPublicKey
        record.displayName = card.displayName
        record.platform = card.platform
        record.capabilities = card.capabilities
        record.profileUpdatedAt = max(record.profileRevision, card.createdAt)
        record.ownDevice = ownDevice
        record.status = TrustStatus.TRUSTED.rawValue
        record.updatedAt = nowMillis
        peers[card.clientId] = record
    }

    /// Remove (delete — not revoke) every Experience Mode peer from the roster. Returns the removed client
    /// ids so the caller can prune their held cards + UI rows. A clean slate before a new Experience session.
    @discardableResult
    func removeExperiencePeers() -> [String] {
        let ids = peers.values.filter(\.isExperienceMode).map(\.clientId)
        for id in ids { peers.removeValue(forKey: id) }
        return ids
    }

    /// Apply a verified key-epoch: enforce the anti-rollback floor + `minEpoch` (#1), record the epoch with
    /// its purposes + validity window, raise the floor and current epoch, and retain the newest `ringSize`
    /// generations (#10). Returns false (no-op) if the epoch — or the bundle's `minEpoch` — is below the floor.
    @discardableResult
    func applyKeyEpoch(_ ke: ClientKeyEpoch, blob: SignedBlob? = nil) -> Bool {
        guard var record = peers[ke.clientId] else { return false }
        let floor = record.floor ?? 0
        guard ke.epoch >= floor else { return false }      // rollback: a retired/superseded epoch
        guard ke.minEpoch >= floor else { return false }   // a replayed bundle must not drag the floor down
        let newFloor = max(floor, ke.minEpoch)
        record.floor = newFloor
        record.epochs.removeAll { $0.epoch == ke.epoch }
        record.epochs.append(EpochRecord(epoch: ke.epoch, operationalSpki: ke.operationalSigningKey,
                                         hpkePublicKey: ke.hpkePublicKey, notBefore: ke.notBefore,
                                         notAfter: ke.notAfter, purposes: ke.purposes.map(\.rawValue),
                                         signedBlob: blob.map(ProtocolCodec.encode)))
        if ke.epoch >= record.currentEpoch { record.currentEpoch = ke.epoch }
        // Drop below-floor generations, then retain the newest `ringSize` (current + grace predecessors).
        record.epochs.removeAll { $0.epoch < newFloor }
        record.epochs.sort { $0.epoch > $1.epoch }
        if record.epochs.count > Self.ringSize { record.epochs = Array(record.epochs.prefix(Self.ringSize)) }
        peers[ke.clientId] = record
        return true
    }

    func setStatus(_ clientId: String, _ status: TrustStatus, at nowMillis: Int64) {
        guard var record = peers[clientId] else { return }
        record.status = status.rawValue
        record.updatedAt = nowMillis
        peers[clientId] = record
    }

    /// Pin a peer's identity anchor (used to fill a mesh-introduced peer whose card hadn't arrived). (#3)
    func setIdentity(_ clientId: String, spki: Data) {
        guard var record = peers[clientId] else { return }
        record.identitySpki = spki
        peers[clientId] = record
    }

    /// Apply a peer's mutable profile (a `ProfileUpdate` rename / platform change). (#5)
    func setProfile(_ clientId: String, displayName: String, platform: String,
                    capabilities: [Capability], at nowMillis: Int64) {
        guard var record = peers[clientId] else { return }
        record.displayName = displayName
        record.platform = platform
        record.capabilities = capabilities
        record.profileUpdatedAt = nowMillis
        peers[clientId] = record
    }

    // MARK: Mesh introductions (#3 — pending-trust / pending-revoke), mirroring Android TrustMachine

    /// Fold an own-mesh peer's broadcast roster into ours: an unknown TRUSTED device becomes PENDING_TRUST
    /// (awaiting the user's approval), a peer revoking a device we trust becomes PENDING_REVOKE, etc. Only
    /// TRUSTED/REVOKED assertions change state; PENDING rows are informational repair beacons.
    /// Returns changed state plus repair material to send back to the sender. Self/sender rows are ignored.
    @discardableResult
    func applyIncomingTable(sender: String, table: TrustTable, now: Int64) -> IncomingTrustResult {
        var changed = false
        var cardsToOffer: [SignedBlob] = []
        var keyEpochsToOffer: [SignedBlob] = []
        var needsBroadcast = false
        for wire in table.entries where wire.clientId != selfClientId && wire.clientId != sender {
            let current = peers[wire.clientId]
            if current == nil || wire.updatedAt > (current?.updatedAt ?? 0) {
                let isOwn = current?.ownDevice ?? wire.ownDevice
                let currentStatus = current.flatMap { TrustStatus(rawValue: $0.status) }
                let next: (status: TrustStatus, introducedBy: String?)?
                if !isOwn {
                    // "Other" (contact) devices skip the pending machinery — applied immediately last-writer-wins.
                    switch wire.status {
                    case .TRUSTED: next = (.TRUSTED, current?.introducedBy ?? sender)
                    case .REVOKED: next = (.REVOKED, current?.introducedBy ?? sender)
                    default: next = nil
                    }
                } else {
                    switch wire.status {
                    case .TRUSTED:
                        switch currentStatus {
                        case nil: next = (.PENDING_TRUST, sender)                       // introduction → awaits approval
                        case .PENDING_TRUST: next = (.PENDING_TRUST, current?.introducedBy)
                        case .TRUSTED: next = (.TRUSTED, current?.introducedBy)
                        case .PENDING_REVOKE: next = (.PENDING_REVOKE, current?.introducedBy) // conflict — keep paused
                        case .REVOKED: next = (.PENDING_TRUST, sender)                  // re-trust → awaits approval
                        }
                    case .REVOKED:
                        switch currentStatus {
                        case nil: next = (.REVOKED, sender)                             // silent tombstone
                        case .PENDING_TRUST: next = (.REVOKED, sender)                  // never approved → drop
                        case .TRUSTED: next = (.PENDING_REVOKE, sender)                 // peer revoked a trusted device
                        case .PENDING_REVOKE: next = (.PENDING_REVOKE, current?.introducedBy)
                        case .REVOKED: next = (.REVOKED, current?.introducedBy)
                        }
                    default: next = nil
                    }
                }
                if let n = next {
                    upsertEntry(wire.clientId, status: n.status, introducedBy: n.introducedBy, ownDevice: isOwn, at: wire.updatedAt)
                    if CardStore.blob(wire.clientId) == nil,
                       n.status == .PENDING_TRUST || n.status == .TRUSTED {
                        needsBroadcast = true
                    }
                    changed = true
                }
            }
            // Repair runs even for stale/informational rows: the sender may be telling us it lacks a card or
            // an epoch we already hold. The material is self-authenticating, so we only need to be a relay.
            if let mine = peers[wire.clientId], mine.isTrusted {
                if !wire.keyAvailable, let card = CardStore.blob(wire.clientId) {
                    cardsToOffer.append(card)
                }
                if wire.epoch < mine.currentEpoch, let blob = currentKeyEpochBlob(wire.clientId) {
                    keyEpochsToOffer.append(blob)
                }
            }
        }
        return IncomingTrustResult(changed: changed, cardsToOffer: cardsToOffer,
                                   keyEpochsToOffer: keyEpochsToOffer, needsBroadcast: needsBroadcast)
    }

    /// Pin a delivered (self-authenticating) card: store it for relay, and fill the identity/name of a
    /// pending introduction that arrived without one. First-verified-wins. Returns true if the roster changed.
    @discardableResult
    func applyCard(_ cardBlob: SignedBlob, now: Int64) -> Bool {
        guard cardBlob.typ == SignedType.clientCard,
              let card = try? ProtocolCodec.decodeClientCard(cardBlob.payload),
              card.clientId == cardBlob.signerId,
              IdentityVerifier.verifyBound(expectedSignerId: cardBlob.signerId, spki: card.identityPublicKey,
                                           data: cardBlob.payload, signature: cardBlob.sig)
        else { return false }
        CardStore.put(card.clientId, blob: cardBlob)
        guard var record = peers[card.clientId] else { return false }   // no entry yet — held in CardStore for later
        // Fill each field only if empty: identity is first-verified-wins (never overwrite a pinned key); name
        // and platform may have been set by the key-epoch (identity only) or a later PROFILE update, so only
        // backfill a blank. Decoupled so a key-epoch arriving before the card still gets named. (#3)
        var changed = false
        if record.identitySpki.isEmpty { record.identitySpki = card.identityPublicKey; changed = true }
        if record.displayName.isEmpty { record.displayName = card.displayName; changed = true }
        if record.platform.isEmpty { record.platform = card.platform; changed = true }
        if record.capabilities == nil {
            record.capabilities = card.capabilities
            record.profileUpdatedAt = max(record.profileRevision, card.createdAt)
            changed = true
        }
        guard changed else { return false }
        peers[card.clientId] = record
        return true
    }

    /// Create or update a roster entry from a mesh assertion. A new entry pulls its identity/name from a held
    /// card if available (else a placeholder with empty identity until its card arrives).
    private func upsertEntry(_ clientId: String, status: TrustStatus, introducedBy: String?, ownDevice: Bool, at: Int64) {
        if var record = peers[clientId] {
            record.status = status.rawValue
            record.introducedBy = introducedBy
            record.updatedAt = at
            peers[clientId] = record
        } else {
            let card = CardStore.blob(clientId).flatMap { try? ProtocolCodec.decodeClientCard($0.payload) }
            peers[clientId] = TrustedPeerRecord(
                clientId: clientId, identitySpki: card?.identityPublicKey ?? Data(),
                displayName: card?.displayName ?? "", platform: card?.platform ?? "",
                status: status.rawValue, ownDevice: ownDevice, updatedAt: at,
                epochs: [], currentEpoch: 0,
                capabilities: card?.capabilities, profileUpdatedAt: card?.createdAt,
                floor: nil, introducedBy: introducedBy)
        }
    }

    // ---- local approve/reject of a pending state ----

    @discardableResult func approveTrust(_ clientId: String, at: Int64) -> Bool { transition(clientId, to: .TRUSTED, from: .PENDING_TRUST, clearIntroducer: false, at: at) }
    @discardableResult func rejectTrust(_ clientId: String, at: Int64) -> Bool { transition(clientId, to: .REVOKED, from: .PENDING_TRUST, clearIntroducer: true, at: at) }
    @discardableResult func confirmRevoke(_ clientId: String, at: Int64) -> Bool { transition(clientId, to: .REVOKED, from: .PENDING_REVOKE, clearIntroducer: false, at: at) }
    @discardableResult func keepTrusted(_ clientId: String, at: Int64) -> Bool { transition(clientId, to: .TRUSTED, from: .PENDING_REVOKE, clearIntroducer: true, at: at) }

    private func transition(_ clientId: String, to: TrustStatus, from: TrustStatus, clearIntroducer: Bool, at: Int64) -> Bool {
        guard var record = peers[clientId], record.status == from.rawValue else { return false }
        record.status = to.rawValue
        record.updatedAt = at
        if clearIntroducer { record.introducedBy = nil }
        peers[clientId] = record
        return true
    }

    /// Signed cards for every TRUSTED device — pushed alongside the roster so a peer can name a newly
    /// introduced device. (#3) Experience Mode peers are never broadcast, so their cards are withheld.
    func trustedCardBlobs() -> [SignedBlob] {
        peers.values.filter { $0.isTrusted && !$0.isExperienceMode }.compactMap { CardStore.blob($0.clientId) }
    }

    /// The highest relayable, self-contained key-epoch blob we hold for a peer. Stripped QR copies are
    /// deliberately skipped because a card-less receiver could not verify them without the identity anchor.
    func currentKeyEpochBlob(_ clientId: String) -> SignedBlob? {
        peers[clientId]?.epochs.compactMap { epoch -> (Int, SignedBlob)? in
            guard let data = epoch.signedBlob,
                  let blob = try? ProtocolCodec.decodeSignedBlob(data),
                  let ke = try? ProtocolCodec.decodeClientKeyEpoch(blob.payload),
                  !ke.identityPublicKey.isEmpty else { return nil }
            return (ke.epoch, blob)
        }.max { $0.0 < $1.0 }?.1
    }

    // MARK: Resolution

    /// The public key to verify an envelope/request from `signerId` at `signerEpoch` (identity for epoch 0,
    /// else the operational key of that epoch). For an operational epoch this is the anti-rollback gate (#1):
    /// the epoch must be at or above the peer's floor AND the key must carry `ENVELOPE_SIGN`. Nil → caller
    /// drops (a replayed retired/floored or wrong-purpose epoch never verifies).
    func verifierSpki(signerId: String, signerEpoch: Int) -> Data? {
        guard let peer = peers[signerId] else { return nil }
        if signerEpoch == 0 { return peer.identitySpki.isEmpty ? nil : peer.identitySpki }
        guard signerEpoch >= (peer.floor ?? 0),
              let e = peer.epoch(signerEpoch), e.allows(.ENVELOPE_SIGN) else { return nil }
        return e.operationalSpki
    }

    var trustedOwnDevices: [TrustedPeerRecord] {
        peers.values.filter { $0.isTrusted && $0.ownDevice }
    }

    /// Recipients to seal an own-mesh broadcast to (dismissals, data-sync). Skips peers with no usable
    /// (at-or-above-floor, already-active) sealable epoch. `includingExperience: false` also skips Experience
    /// Mode peers — used by the trust-roster seal, which must never reach a demo device.
    func ownMeshRecipients(excluding excluded: String? = nil, includingExperience: Bool = true) -> [EnvelopeCrypto.RecipientKey] {
        let now = Self.nowMillis()
        return trustedOwnDevices.compactMap { peer in
            guard peer.clientId != excluded else { return nil }
            if !includingExperience, peer.isExperienceMode { return nil }
            guard let e = peer.sealable(now: now) else { return nil }
            return EnvelopeCrypto.RecipientKey(clientId: peer.clientId, hpkePublicKey: e.hpkePublicKey, recipientEpoch: e.epoch)
        }
    }

    /// Trusted peers we hold no *usable* key-epoch for — candidates for a GET /keyepoch refetch (self-heal).
    /// "Available ≠ usable": a peer whose only held epoch is expired (`notAfter` passed) or pre-warmed is
    /// also surfaced, so convergence proactively heals it after a peer rotates (#7).
    func peersNeedingKeyEpoch() -> [String] {
        let now = Self.nowMillis()
        return peers.values.filter { peer in
            guard peer.isTrusted else { return false }
            guard let e = peer.sealable(now: now) else { return true }
            return e.notAfter <= now
        }.map(\.clientId)
    }

    /// Recipients to seal a message to every trusted device (own AND "other" contacts) — used for profile
    /// updates, which converge across the whole mesh (not just own-mesh). Skips peers with no usable epoch.
    func allTrustedRecipients(excluding excluded: String? = nil) -> [EnvelopeCrypto.RecipientKey] {
        let now = Self.nowMillis()
        return peers.values.filter(\.isTrusted).compactMap { peer in
            guard peer.clientId != excluded, let e = peer.sealable(now: now) else { return nil }
            return EnvelopeCrypto.RecipientKey(clientId: peer.clientId, hpkePublicKey: e.hpkePublicKey, recipientEpoch: e.epoch)
        }
    }

    /// This device's broadcast trust roster (every peer but self), each tagged with its status, ownDevice
    /// class, key-availability, and current epoch — folded into peers' rosters on receipt. (#5)
    func buildTrustTable(excluding selfId: String) -> TrustTable {
        // PENDING_* rows travel as informational repair beacons: receivers do not apply a peer's pending
        // state as a trust decision, but they can use `keyAvailable=false` / `epoch` to offer missing material.
        // Experience Mode peers are never advertised — a demo device must stay invisible to the real mesh.
        let entries = peers.values
            .filter { $0.clientId != selfId && !$0.isExperienceMode }
            .map { peer in
                TrustTableEntry(clientId: peer.clientId, status: TrustStatus(rawValue: peer.status) ?? .TRUSTED,
                                updatedAt: peer.updatedAt, keyAvailable: CardStore.blob(peer.clientId) != nil,
                                ownDevice: peer.ownDevice, epoch: peer.currentEpoch)
            }
        return TrustTable(entries: entries)
    }

    static let ringSize = 3   // NS2 §6 generation ring (current + grace predecessors)
    static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // MARK: Signing canonical (four sections; cards/overlays empty on iOS)

    private static func canonical(selfId: String, peers: [TrustedPeerRecord]) -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let sorted = peers.sorted { $0.clientId < $1.clientId }
        let entries = (try? encoder.encode(sorted.map(EntrySection.init))).map { String(decoding: $0, as: UTF8.self) } ?? "[]"
        let epochs = (try? encoder.encode(sorted.map(EpochSection.init))).map { String(decoding: $0, as: UTF8.self) } ?? "[]"
        func digest(_ s: String) -> String { NSBase64URL.encode(NSHash.sha256(Data(s.utf8))) }
        let canonical = [signVersion, selfId, digest(entries), digest(""), digest(""), digest(epochs)].joined(separator: "\n")
        return Data(canonical.utf8)
    }

    private struct EntrySection: Codable {
        var clientId: String, identitySpki: Data, status: String, ownDevice: Bool, updatedAt: Int64
        var introducedBy: String?   // omitted by JSONEncoder when nil → pre-introduction rosters verify unchanged
        init(_ p: TrustedPeerRecord) {
            clientId = p.clientId; identitySpki = p.identitySpki; status = p.status
            ownDevice = p.ownDevice; updatedAt = p.updatedAt; introducedBy = p.introducedBy
        }
    }

    private struct EpochSection: Codable {
        var clientId: String, currentEpoch: Int, floor: Int?, epochs: [EpochRecord]
        init(_ p: TrustedPeerRecord) {
            clientId = p.clientId
            currentEpoch = p.currentEpoch
            // Sign the floor only once it's non-zero (omitted by JSONEncoder when nil), so a pre-upgrade
            // roster — which had no floor/purposes — still verifies its existing signature and isn't forced
            // to re-pair. A real (raised) floor IS signed, so it can't be tampered down.
            floor = (p.floor ?? 0) > 0 ? p.floor : nil
            epochs = p.epochs
        }
    }
}
