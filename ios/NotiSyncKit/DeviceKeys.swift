import CryptoKit
import Foundation
import Security

nonisolated enum DeviceKeyError: Error, LocalizedError {
    case generationFailed
    case publicKeyUnavailable
    case signatureFailed
    case hpkeKeyMissing(Int)

    var errorDescription: String? {
        switch self {
        case .generationFailed:
            return String(localized: "error.deviceKey.generationFailed", defaultValue: "Could not create a NotiSync key.", comment: "Error shown when key generation fails.")
        case .publicKeyUnavailable:
            return String(localized: "error.deviceKey.publicKeyUnavailable", defaultValue: "Could not export a NotiSync public key.", comment: "Error shown when a public key cannot be exported.")
        case .signatureFailed:
            return String(localized: "error.deviceKey.signatureFailed", defaultValue: "Could not sign with a NotiSync key.", comment: "Error shown when signing fails.")
        case let .hpkeKeyMissing(epoch):
            return String(
                format: String(localized: "error.deviceKey.hpkeKeyMissing", defaultValue: "No HPKE private key for epoch %d.", comment: "Error shown when an HPKE private key is missing for a key epoch."),
                epoch
            )
        }
    }
}

/// A Secure-Enclave-backed ECDSA-P256 key identified by an application tag. SPKI is the X.509
/// SubjectPublicKeyInfo (DER); signatures are DER (`SHA256withECDSA`-compatible). Falls back to a
/// non-SE keychain key only if the Secure Enclave is unavailable (older simulators).
nonisolated final class SecureEnclaveSigningKey: Sendable {
    private let tag: Data
    private let accessGroup: String?

    init(tag: String, accessGroup: String? = nil) {
        self.tag = Data(tag.utf8)
        self.accessGroup = accessGroup
    }

    func publicKeySpki() throws -> Data {
        let key = try privateKey()
        guard let pub = SecKeyCopyPublicKey(key),
              let x963 = SecKeyCopyExternalRepresentation(pub, nil) as Data? else {
            throw DeviceKeyError.publicKeyUnavailable
        }
        return Self.spki(forX963: x963)
    }

    func sign(_ data: Data) throws -> Data {
        let key = try privateKey()
        var error: Unmanaged<CFError>?
        guard let sig = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, data as CFData, &error) as Data? else {
            throw error?.takeRetainedValue() ?? DeviceKeyError.signatureFailed
        }
        return sig
    }

    /// Permanently delete this key (rotation retirement / forward-secrecy GC). Idempotent.
    func delete() {
        for group in lookupAccessGroups {
            SecItemDelete(keyQuery(accessGroup: group) as CFDictionary)
        }
    }

    private func privateKey() throws -> SecKey {
        if let existing = load() { return existing }
        if let accessGroup, let key = try? generate(accessGroup: accessGroup) { return key }
        return try generate(accessGroup: nil)
    }

    private func load() -> SecKey? {
        for group in lookupAccessGroups {
            var query = keyQuery(accessGroup: group)
            query[kSecReturnRef as String] = true
            var item: CFTypeRef?
            if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let item {
                return (item as! SecKey)
            }
        }
        return nil
    }

    private var lookupAccessGroups: [String?] {
        guard let accessGroup else { return [nil] }
        return [accessGroup, nil]
    }

    private func keyQuery(accessGroup: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
        ]
        if let accessGroup { query[kSecAttrAccessGroup as String] = accessGroup }
        return query
    }

    private func privateKeyAttrs(accessGroup: String?) -> [String: Any] {
        var attrs: [String: Any] = [
            kSecAttrIsPermanent as String: true,
            kSecAttrApplicationTag as String: tag,
        ]
        if let accessGroup { attrs[kSecAttrAccessGroup as String] = accessGroup }
        return attrs
    }

    private func generate(accessGroup: String?) throws -> SecKey {
        // Secure Enclave attempt (real devices). The access-control object MUST carry .privateKeyUsage —
        // that flag is Secure-Enclave-only, so it can NOT be reused for the software fallback below.
        if let access = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, [.privateKeyUsage], nil
        ) {
            var privateAttrs = privateKeyAttrs(accessGroup: accessGroup)
            privateAttrs[kSecAttrAccessControl as String] = access
            let seAttrs: [String: Any] = [
                kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeySizeInBits as String: 256,
                kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
                kSecPrivateKeyAttrs as String: privateAttrs,
            ]
            if let key = SecKeyCreateRandomKey(seAttrs as CFDictionary, nil) { return key }
        }

        // Software fallback (Simulator / no Secure Enclave): a permanent keychain key keyed by the same
        // tag, using kSecAttrAccessible (NOT the SE-only .privateKeyUsage access control). Surfaces the
        // real CFError so a genuine on-device failure is diagnosable.
        var privateAttrs = privateKeyAttrs(accessGroup: accessGroup)
        privateAttrs[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let swAttrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: privateAttrs,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(swAttrs as CFDictionary, &error) else {
            throw error?.takeRetainedValue() ?? DeviceKeyError.generationFailed
        }
        return key
    }

    /// Wrap a 65-byte X9.63 EC point (0x04‖x‖y) in the fixed P-256 SPKI prefix.
    private static func spki(forX963 key: Data) -> Data {
        var spki = Data([
            0x30, 0x59, 0x30, 0x13,
            0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
            0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07,
            0x03, 0x42, 0x00,
        ])
        spki.append(key)
        return spki
    }
}

/// The device identity root (Secure Enclave, non-exportable). Stable across operational-key rotation.
nonisolated final class IdentityKeyStore: Sendable {
    private let key = SecureEnclaveSigningKey(tag: "\(NotiSyncConfig.bundleId).identity.p256",
                                              accessGroup: NotiSyncConfig.signingKeychainGroup)

    func publicKeySpki() throws -> Data { try key.publicKeySpki() }
    func clientId() throws -> String { ClientIds.derive(spki: try key.publicKeySpki()) }
    func sign(_ data: Data) throws -> Data { try key.sign(data) }
}

/// Per-epoch operational signing keys (Secure Enclave). They do the hot-path work (envelope + request
/// signing) so the identity root stays cold. The clientId stays the identity fingerprint.
nonisolated final class OperationalKeyStore: Sendable {
    private func key(epoch: Int) -> SecureEnclaveSigningKey {
        SecureEnclaveSigningKey(tag: "\(NotiSyncConfig.bundleId).operational.p256.epoch\(epoch)",
                                accessGroup: NotiSyncConfig.signingKeychainGroup)
    }

    func publicKeySpki(epoch: Int) throws -> Data { try key(epoch: epoch).publicKeySpki() }
    func sign(epoch: Int, _ data: Data) throws -> Data { try key(epoch: epoch).sign(data) }
    func destroy(epoch: Int) { key(epoch: epoch).delete() }
}

/// Per-epoch HPKE X25519 private keys, stored raw in the **shared** keychain access group so the NSE can
/// open envelopes while the device is locked. A ring keyed by epoch lets a recipient select the matching
/// key when the sender sealed to a (possibly pre-rotation, retained) epoch.
nonisolated final class HpkeKeyStore: Sendable {
    private func account(_ epoch: Int) -> String { "hpke.priv.epoch\(epoch)" }

    /// Load (or, if absent, generate + persist) the X25519 private key for `epoch`. Prefers the shared
    /// keychain access group (so the NSE can read it on a real device); falls back to the app's default
    /// group when the shared group isn't authorized (Simulator / unprovisioned entitlements) so the app
    /// still works in development.
    func loadOrCreate(epoch: Int) throws -> Curve25519.KeyAgreement.PrivateKey {
        if let priv = privateKey(epoch: epoch) { return priv }
        let key = Curve25519.KeyAgreement.PrivateKey()
        let raw = key.rawRepresentation
        if Keychain.set(raw, account: account(epoch), accessGroup: NotiSyncConfig.keychainGroup) { return key }
        guard Keychain.set(raw, account: account(epoch), accessGroup: nil) else {
            throw DeviceKeyError.generationFailed
        }
        return key
    }

    func privateKey(epoch: Int) -> Curve25519.KeyAgreement.PrivateKey? {
        let raw = Keychain.get(account: account(epoch), accessGroup: NotiSyncConfig.keychainGroup)
            ?? Keychain.get(account: account(epoch), accessGroup: nil)
        return raw.flatMap { try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: $0) }
    }

    /// Raw 32-byte X25519 public key published in this device's `ClientKeyEpoch.hpkePublicKey`.
    func rawPublicKey(epoch: Int) throws -> Data {
        try loadOrCreate(epoch: epoch).publicKey.rawRepresentation
    }

    /// Permanently delete the HPKE private key for `epoch` (rotation retirement). Both groups, idempotent.
    func destroy(epoch: Int) {
        Keychain.delete(account: account(epoch), accessGroup: NotiSyncConfig.keychainGroup)
        Keychain.delete(account: account(epoch), accessGroup: nil)
    }
}

// MARK: - Signers

nonisolated struct IdentitySigner: EnvelopeSigner {
    let clientId: String
    let signerEpoch = 0
    let keyStore: IdentityKeyStore
    func sign(_ data: Data) throws -> Data { try keyStore.sign(data) }
}

nonisolated struct OperationalSigner: EnvelopeSigner {
    let clientId: String          // identity fingerprint (stable across rotation)
    let signerEpoch: Int
    let keyStore: OperationalKeyStore
    func sign(_ data: Data) throws -> Data { try keyStore.sign(epoch: signerEpoch, data) }
}
