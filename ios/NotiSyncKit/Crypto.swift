import CryptoKit
import Foundation
import Security

// Native Swift reimplementation of :protocol-crypto, gated by the Kotlin↔Swift golden vectors
// (see GoldenVectorsTests). HPKE + AEAD via CryptoKit; ECDSA-P256 verify via CryptoKit; no Tink.

// MARK: - Hashes, ids, encodings

nonisolated enum NSHash {
    static func sha256(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }
    static func sha256Hex(_ data: Data) -> String { sha256(data).map { String(format: "%02x", $0) }.joined() }
}

nonisolated enum NSBase32 {
    private static let alphabet = Array("abcdefghijklmnopqrstuvwxyz234567".utf8)

    /// RFC 4648 base32, lowercase, no padding.
    static func encode(_ data: Data) -> String {
        guard !data.isEmpty else { return "" }
        var out = [UInt8]()
        var buffer = 0, bits = 0
        for b in data {
            buffer = (buffer << 8) | Int(b); bits += 8
            while bits >= 5 { out.append(alphabet[(buffer >> (bits - 5)) & 0x1f]); bits -= 5 }
        }
        if bits > 0 { out.append(alphabet[(buffer << (5 - bits)) & 0x1f]) }
        return String(bytes: out, encoding: .ascii) ?? ""
    }
}

nonisolated enum NSBase64URL {
    static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func decode(_ s: String) -> Data? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t)
    }
}

nonisolated extension Data {
    var lowercaseHex: String { map { String(format: "%02x", $0) }.joined() }
}

nonisolated enum ClientIds {
    /// clientId = base32(SHA-256(identity SPKI)[0..<20]).
    static func derive(spki: Data) -> String { NSBase32.encode(NSHash.sha256(spki).prefix(20)) }
}

/// Content address (hex SHA-256) of a plaintext asset, computed before encryption for dedup.
nonisolated enum AssetHash {
    static func of(_ plaintext: Data) -> String { NSHash.sha256Hex(plaintext) }

    /// Constant-time check that [plaintext] hashes to [expectedHex] — the integrity gate before rendering.
    static func matches(_ plaintext: Data, expectedHex: String) -> Bool {
        let a = Array(of(plaintext).utf8), b = Array(expectedHex.utf8)
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count { diff |= a[i] ^ b[i] }
        return diff == 0
    }
}

// MARK: - ECDSA-P256 verification (SPKI DER public key, DER signature, SHA-256 digest)

nonisolated enum IdentityVerifier {
    static func verify(spki: Data, data: Data, signature: Data) -> Bool {
        guard let key = try? P256.Signing.PublicKey(derRepresentation: spki),
              let sig = try? P256.Signing.ECDSASignature(derRepresentation: signature) else { return false }
        return key.isValidSignature(sig, for: data)
    }

    /// Confirms the signer id is the fingerprint of the public key AND the signature is valid.
    static func verifyBound(expectedSignerId: String, spki: Data, data: Data, signature: Data) -> Bool {
        ClientIds.derive(spki: spki) == expectedSignerId && verify(spki: spki, data: data, signature: signature)
    }
}

// MARK: - HPKE (CryptoKit, raw X25519 keys, bare RFC-9180 = Tink NO_PREFIX)

nonisolated enum HpkeError: Error { case badWire }

nonisolated enum Hpke {
    static var ciphersuite: HPKE.Ciphersuite {
        HPKE.Ciphersuite(kem: .Curve25519_HKDF_SHA256, kdf: .HKDF_SHA256, aead: .AES_GCM_256)
    }

    /// Seal `dek` to a recipient's raw 32-byte X25519 public key. Wire = encapsulatedKey(32) ‖ ct‖tag.
    static func seal(dek: Data, recipientRawPublicKey raw32: Data, info: Data) throws -> Data {
        let pub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: raw32)
        var sender = try HPKE.Sender(recipientKey: pub, ciphersuite: ciphersuite, info: info)
        let ct = try sender.seal(dek, authenticating: Data())
        return sender.encapsulatedKey + ct
    }

    /// Open a sealed DEK using this device's X25519 private key. `wire` = encapsulatedKey(32) ‖ ct‖tag.
    static func open(wire: Data, privateKey: Curve25519.KeyAgreement.PrivateKey, info: Data) throws -> Data {
        guard wire.count > 32 else { throw HpkeError.badWire }
        let enc = Data(wire.prefix(32))
        let ct = Data(wire.suffix(from: wire.startIndex + 32))
        var recipient = try HPKE.Recipient(privateKey: privateKey, ciphersuite: ciphersuite,
                                           info: info, encapsulatedKey: enc)
        return try recipient.open(ct, authenticating: Data())
    }
}

// MARK: - Body / asset AEAD (AES-256-GCM, output = nonce(12) ‖ ct ‖ tag(16))

nonisolated enum BodyAead {
    static func generateDek() -> Data { SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) } }

    static func seal(dek: Data, plaintext: Data, aad: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: dek), authenticating: aad)
        guard let combined = box.combined else { throw HpkeError.badWire } // 12-byte nonce → non-nil
        return combined
    }

    static func open(dek: Data, sealed: Data, aad: Data) throws -> Data {
        let box = try AES.GCM.SealedBox(combined: sealed)
        return try AES.GCM.open(box, using: SymmetricKey(data: dek), authenticating: aad)
    }
}

// MARK: - Asset AEAD (reuses BodyAead, keyed by a per-asset key, AAD = CBOR(AssetAad))

nonisolated enum AssetCrypto {
    static func generateAssetKey() -> Data { BodyAead.generateDek() }

    /// A fresh opaque 192-bit server key id (Base32, ~39 chars), unrelated to the asset's content.
    static func generateAssetId() -> String {
        var bytes = Data(count: 24)
        let status = bytes.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, 24, $0.baseAddress!)
        }
        guard status == 0 else {
            let fallback = Data("\(UUID().uuidString).\(UUID().uuidString).\(Date().timeIntervalSince1970)".utf8)
            return NSBase32.encode(NSHash.sha256(fallback).prefix(24))
        }
        return NSBase32.encode(bytes)
    }

    static func aadBytes(_ ref: PrivateAssetRef) -> Data {
        ProtocolCodec.encode(AssetAad(suite: ref.suite, sourceClientId: ref.sourceClientId, assetId: ref.assetId,
                                      mimeType: ref.mimeType, sizeBytes: ref.sizeBytes, role: ref.role))
    }

    static func seal(_ ref: PrivateAssetRef, plaintext: Data) throws -> Data {
        try BodyAead.seal(dek: ref.assetKey, plaintext: plaintext, aad: aadBytes(ref))
    }

    static func open(_ ref: PrivateAssetRef, sealed: Data) throws -> Data {
        try BodyAead.open(dek: ref.assetKey, sealed: sealed, aad: aadBytes(ref))
    }
}

// MARK: - Envelope crypto

nonisolated enum EnvelopeCryptoError: Error { case notARecipient(String) }

nonisolated enum EnvelopeCrypto {
    static func bodyAad(suite: String) -> Data { Data("notisync:body:\(suite)".utf8) }

    static func dekContext(suite: String, recipientId: String, recipientEpoch: Int) -> Data {
        Data("notisync:dek:\(suite)|\(recipientId)|\(recipientEpoch)".utf8)
    }

    /// The exact bytes signed/verified for an envelope (independent of the sig field itself).
    static func authBytes(_ env: Envelope) -> Data {
        ProtocolCodec.encode(EnvelopeAuth(
            v: env.v, suite: env.suite, typ: env.typ, signerId: env.signerId, signerEpoch: env.signerEpoch,
            messageId: env.messageId, seq: env.seq, createdAt: env.createdAt,
            bodyCiphertextSha256: NSHash.sha256(env.bodyCiphertext),
            recipientIds: env.recipientIds(), recipientEpochs: env.recipientEpochs()
        ))
    }

    static func verify(_ env: Envelope, signerSpki: Data) -> Bool {
        env.signerEpoch == 0
            ? IdentityVerifier.verifyBound(expectedSignerId: env.signerId, spki: signerSpki, data: authBytes(env), signature: env.sig)
            : IdentityVerifier.verify(spki: signerSpki, data: authBytes(env), signature: env.sig)
    }

    /// A recipient's id + raw HPKE public key + epoch, used when sealing.
    struct RecipientKey: Sendable {
        var clientId: String
        var hpkePublicKey: Data     // raw 32-byte X25519
        var recipientEpoch: Int
    }

    /// Seal `bodyPlaintext` to `recipients`, signed by `signer`. Mirrors EnvelopeCrypto.sealInternal:
    /// a recipient whose key can't be sealed to is dropped (so one bad peer doesn't abort the fan-out);
    /// throws only if NONE seal. authBytes covers only the sealed recipients.
    static func seal(
        signer: EnvelopeSigner, typ: MessageType, bodyPlaintext: Data, recipients: [RecipientKey],
        messageId: String, seq: Int64, createdAt: Int64, suite: String = CipherSuite.current
    ) throws -> Envelope {
        var dek = BodyAead.generateDek()
        defer { dek.resetBytes(in: 0..<dek.count) }
        let bodyCiphertext = try BodyAead.seal(dek: dek, plaintext: bodyPlaintext, aad: bodyAad(suite: suite))
        let perRecipient: [PerRecipientKey] = recipients.compactMap { r in
            guard let sealed = try? Hpke.seal(dek: dek, recipientRawPublicKey: r.hpkePublicKey,
                                              info: dekContext(suite: suite, recipientId: r.clientId, recipientEpoch: r.recipientEpoch))
            else { return nil }
            return PerRecipientKey(recipientId: r.clientId, sealedDek: sealed, recipientEpoch: r.recipientEpoch)
        }
        guard !perRecipient.isEmpty || recipients.isEmpty else {
            throw EnvelopeCryptoError.notARecipient("all \(recipients.count) recipient(s) failed to seal")
        }
        var env = Envelope(
            suite: suite, typ: typ, signerId: signer.clientId, signerEpoch: signer.signerEpoch,
            messageId: messageId, seq: seq, createdAt: createdAt,
            bodyCiphertext: bodyCiphertext, recipients: perRecipient
        )
        env.sig = try signer.sign(authBytes(env))
        return env
    }

    /// Open an envelope addressed to this device with the X25519 private key for the sealed recipient epoch.
    static func open(_ env: Envelope, myClientId: String, privateKey: Curve25519.KeyAgreement.PrivateKey) throws -> Data {
        guard let mine = env.recipients.first(where: { $0.recipientId == myClientId }) else {
            throw EnvelopeCryptoError.notARecipient(myClientId)
        }
        var dek = try Hpke.open(wire: mine.sealedDek, privateKey: privateKey,
                                info: dekContext(suite: env.suite, recipientId: myClientId, recipientEpoch: mine.recipientEpoch))
        defer { dek.resetBytes(in: 0..<dek.count) }
        return try BodyAead.open(dek: dek, sealed: env.bodyCiphertext, aad: bodyAad(suite: env.suite))
    }
}

/// A signer over envelope/request bytes. `signerEpoch` 0 = identity key, ≥1 = operational key of that epoch.
nonisolated protocol EnvelopeSigner {
    var clientId: String { get }
    var signerEpoch: Int { get }
    func sign(_ data: Data) throws -> Data
}

// MARK: - Key-epoch verification (NS2 two-hop trust)

nonisolated enum KeyEpochs {
    /// Verify a self-contained (or QR-stripped + pinned) ClientKeyEpoch SignedBlob; returns the epoch or nil.
    static func verify(_ blob: SignedBlob, pinnedIdentitySpki: Data? = nil) -> ClientKeyEpoch? {
        guard blob.typ == SignedType.keyEpoch else { return nil }
        guard let ke = try? ProtocolCodec.decodeClientKeyEpoch(blob.payload) else { return nil }
        guard ke.epoch >= 1, ke.clientId == blob.signerId else { return nil }
        let identityKey = !ke.identityPublicKey.isEmpty ? ke.identityPublicKey : pinnedIdentitySpki
        guard let identity = identityKey else { return nil }
        guard ClientIds.derive(spki: identity) == ke.clientId else { return nil }
        if let pinned = pinnedIdentitySpki, pinned != identity { return nil }
        guard IdentityVerifier.verify(spki: identity, data: blob.payload, signature: blob.sig) else { return nil }
        return ke
    }
}

// MARK: - Proof of work (hashcash gate on /integrity/verify)

nonisolated enum ProofOfWork {
    static let headerNonce = "X-PoW-Nonce"
    static let headerTimestamp = "X-PoW-Timestamp"

    /// Lowercase-hex SHA-256 over "signature\nnonce\ntimestamp".
    static func hashHex(signature: String, nonce: String, timestampMillis: Int64) -> String {
        NSHash.sha256Hex(Data("\(signature)\n\(nonce)\n\(timestampMillis)".utf8))
    }

    static func satisfies(_ hex: String, difficulty: Int) -> Bool {
        guard difficulty > 0 else { return true }
        guard hex.count >= difficulty else { return false }
        return hex.prefix(difficulty).allSatisfy { $0 == "0" }
    }

    /// Grind a counter nonce until the hash meets `difficulty`.
    static func solve(signature: String, timestampMillis: Int64, difficulty: Int) -> String {
        var n = 0
        while true {
            let nonce = String(n)
            if satisfies(hashHex(signature: signature, nonce: nonce, timestampMillis: timestampMillis), difficulty: difficulty) {
                return nonce
            }
            n += 1
        }
    }
}
