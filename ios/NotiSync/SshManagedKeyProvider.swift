import Foundation
import Security

nonisolated enum SshManagedKeyAlgorithm: String, Codable, CaseIterable, Sendable {
    case ed25519 = "SSH_ED25519"
    case rsa = "SSH_RSA"
    case ecdsaNistP256 = "ECDSA_NISTP256"

    var publicWireName: String {
        switch self {
        case .ed25519: "ssh-ed25519"
        case .rsa: "ssh-rsa"
        case .ecdsaNistP256: "ecdsa-sha2-nistp256"
        }
    }
}

nonisolated enum SshManagedSignatureAlgorithm: String, Codable, CaseIterable, Sendable {
    case ed25519 = "SSH_ED25519"
    case rsaSHA256 = "RSA_SHA2_256"
    case rsaSHA512 = "RSA_SHA2_512"
    case ecdsaNistP256 = "ECDSA_NISTP256"
    case rsaSHA1Legacy = "RSA_SHA1_LEGACY"

    var wireName: String {
        switch self {
        case .ed25519: "ssh-ed25519"
        case .rsaSHA256: "rsa-sha2-256"
        case .rsaSHA512: "rsa-sha2-512"
        case .ecdsaNistP256: "ecdsa-sha2-nistp256"
        case .rsaSHA1Legacy: "ssh-rsa"
        }
    }
}

nonisolated struct SshManagedKeyMaterial: Sendable, Equatable {
    let algorithm: SshManagedKeyAlgorithm
    let publicKeyBlob: Data
    /// Unencrypted PKCS#8 bytes. The caller must persist this only through `SshManagedKeyProvider.store`.
    let privateKeyPKCS8: Data
    let importedComment: String?
}

nonisolated enum SshManagedKeyProviderError: Error, LocalizedError, Sendable, Equatable {
    case invalidInput(LocalizedStringResource)
    case cryptographicFailure(LocalizedStringResource)
    case storageFailure
    case storageUnavailable
    case keyNotFound

    var errorDescription: String? {
        switch self {
        case .invalidInput(let message), .cryptographicFailure(let message):
            String(localized: message)
        case .storageFailure:
            String(
                localized: "The SSH private key could not be saved in Keychain.",
                comment: "Error shown when a managed SSH private key cannot be stored in Keychain."
            )
        case .storageUnavailable:
            String(
                localized: "The SSH private key is temporarily unavailable in Keychain.",
                comment: "Retryable error shown when Keychain cannot currently return a managed SSH private key."
            )
        case .keyNotFound:
            String(
                localized: "The SSH private key is no longer available in Keychain.",
                comment: "Error shown when the Keychain item for a managed SSH private key no longer exists."
            )
        }
    }
}

/// Managed SSH private keys remain software keys wrapped by an app-and-NSE-only, device-only Keychain item. OpenSSL is
/// used only while generating, importing, validating, or signing; no `EVP_PKEY` handle or raw private material is
/// retained globally. Every ingress path performs a signing round trip before a material object is returned.
nonisolated enum SshManagedKeyProvider {
    static let maximumImportBytes = 256 * 1024
    static let maximumSignBytes = 256 * 1024

    static func generate(
        algorithm: SshManagedKeyAlgorithm = .ecdsaNistP256,
        rsaBits: Int = 3072
    ) throws -> SshManagedKeyMaterial {
        let bridgeAlgorithm: NSSshManagedKeyAlgorithm
        switch algorithm {
        case .ed25519: bridgeAlgorithm = NSSshManagedKeyAlgorithmEd25519
        case .rsa: bridgeAlgorithm = NSSshManagedKeyAlgorithmRSA
        case .ecdsaNistP256: bridgeAlgorithm = NSSshManagedKeyAlgorithmECDSANistP256
        }
        let key = try OpenSSLManagedSSHKey.create { error, count in
            NSSshManagedKeyGenerate(bridgeAlgorithm, Int32(rsaBits), error, count)
        }
        guard key.algorithm == algorithm else {
            throw SshManagedKeyProviderError.cryptographicFailure("Generated SSH key has an unexpected algorithm.")
        }
        return try material(from: key, comment: nil)
    }

    static func store(_ material: SshManagedKeyMaterial, keyId: String) throws {
        // Re-open and compare before Keychain persistence so callers cannot synthesize an unchecked material value.
        _ = try validatedKey(for: material)
        let record = StoredManagedKey(
            version: 1,
            algorithm: material.algorithm,
            publicKeyBlob: material.publicKeyBlob,
            privateKeyPKCS8: material.privateKeyPKCS8
        )
        let encoder = PropertyListEncoder()
        encoder.outputFormat = .binary
        let encoded = try encoder.encode(record)
        guard SshManagedKeyKeychainStore.save(encoded, keyId: keyId) else {
            throw SshManagedKeyProviderError.storageFailure
        }
    }

    static func load(keyId: String) throws -> SshManagedKeyMaterial? {
        let encoded: Data
        switch SshManagedKeyKeychainStore.load(keyId: keyId) {
        case .found(let value): encoded = value
        case .missing: return nil
        case .unavailable: throw SshManagedKeyProviderError.storageUnavailable
        }
        let record: StoredManagedKey
        do {
            record = try PropertyListDecoder().decode(StoredManagedKey.self, from: encoded)
        } catch {
            throw SshManagedKeyProviderError.cryptographicFailure("The Keychain SSH key record is invalid.")
        }
        guard record.version == 1 else {
            throw SshManagedKeyProviderError.cryptographicFailure("The Keychain SSH key record version is unsupported.")
        }
        let material = SshManagedKeyMaterial(
            algorithm: record.algorithm,
            publicKeyBlob: record.publicKeyBlob,
            privateKeyPKCS8: record.privateKeyPKCS8,
            importedComment: nil
        )
        _ = try validatedKey(for: material)
        return material
    }

    static func contains(keyId: String) -> Bool {
        if case .found = SshManagedKeyKeychainStore.load(keyId: keyId) { return true }
        return false
    }

    @discardableResult
    static func delete(keyId: String) -> Bool {
        SshManagedKeyKeychainStore.delete(keyId: keyId)
    }

    static func storedKeyIds() -> SshManagedKeyKeychainStore.KeyIdEnumeration {
        SshManagedKeyKeychainStore.keyIds()
    }

    static func sign(
        keyId: String,
        algorithm: SshManagedSignatureAlgorithm,
        data: Data
    ) throws -> Data {
        guard let material = try load(keyId: keyId) else {
            throw SshManagedKeyProviderError.keyNotFound
        }
        return try sign(material: material, algorithm: algorithm, data: data)
    }

    static func sign(
        material: SshManagedKeyMaterial,
        algorithm: SshManagedSignatureAlgorithm,
        data: Data
    ) throws -> Data {
        guard !data.isEmpty, data.count <= maximumSignBytes else {
            throw SshManagedKeyProviderError.invalidInput("SSH signing data is outside the allowed size limit.")
        }
        guard signatureAlgorithm(algorithm, matches: material.algorithm) else {
            throw SshManagedKeyProviderError.invalidInput("The requested SSH signature algorithm does not match the key.")
        }
        let key = try validatedKey(for: material)
        let native = try key.sign(algorithm: algorithm, message: data)
        let payload = algorithm == .ecdsaNistP256 ? try SshECDSASignatureCodec.derToSSH(native) : native
        var writer = SshWireWriter(maximumSize: 16 * 1024)
        try writer.writeUTF8(algorithm.wireName)
        try writer.writeString(payload)
        return writer.data
    }

    static func randomKeyId() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw SshManagedKeyProviderError.cryptographicFailure("Could not generate an SSH key identifier.")
        }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: Validated import entry points used by SshPrivateKeyFileParser

    static func importEncodedPrivateKey(
        _ encoded: Data,
        passphraseUTF8: Data?,
        comment: String? = nil
    ) throws -> SshManagedKeyMaterial {
        guard !encoded.isEmpty, encoded.count <= maximumImportBytes,
              (passphraseUTF8?.count ?? 0) <= 4_096 else {
            throw SshManagedKeyProviderError.invalidInput("SSH private-key input is outside the allowed size limit.")
        }
        let key = try OpenSSLManagedSSHKey.importEncoded(encoded, passphrase: passphraseUTF8)
        return try material(from: key, comment: comment)
    }

    static func importEd25519(
        seed: Data,
        expectedPublicKey: Data,
        comment: String?
    ) throws -> SshManagedKeyMaterial {
        let key = try OpenSSLManagedSSHKey.createEd25519(seed: seed, expectedPublicKey: expectedPublicKey)
        return try material(from: key, comment: comment)
    }

    static func importRSA(
        modulus: Data,
        publicExponent: Data,
        privateExponent: Data,
        coefficient: Data,
        primeP: Data,
        primeQ: Data,
        comment: String?
    ) throws -> SshManagedKeyMaterial {
        let key = try OpenSSLManagedSSHKey.createRSA(
            modulus: modulus,
            publicExponent: publicExponent,
            privateExponent: privateExponent,
            coefficient: coefficient,
            primeP: primeP,
            primeQ: primeQ
        )
        return try material(from: key, comment: comment)
    }

    static func importECDSANistP256(
        privateScalar: Data,
        expectedPublicPoint: Data,
        comment: String?
    ) throws -> SshManagedKeyMaterial {
        let key = try OpenSSLManagedSSHKey.createECDSA(
            privateScalar: privateScalar,
            expectedPublicPoint: expectedPublicPoint
        )
        return try material(from: key, comment: comment)
    }

    private static func material(from key: OpenSSLManagedSSHKey, comment: String?) throws -> SshManagedKeyMaterial {
        SshManagedKeyMaterial(
            algorithm: key.algorithm,
            publicKeyBlob: try key.publicKeyBlob(),
            privateKeyPKCS8: try key.pkcs8(),
            importedComment: comment
        )
    }

    private static func validatedKey(for material: SshManagedKeyMaterial) throws -> OpenSSLManagedSSHKey {
        guard !material.privateKeyPKCS8.isEmpty, material.privateKeyPKCS8.count <= maximumImportBytes,
              !material.publicKeyBlob.isEmpty, material.publicKeyBlob.count <= 16 * 1024 else {
            throw SshManagedKeyProviderError.cryptographicFailure("Stored SSH key material is outside the allowed bounds.")
        }
        let key = try OpenSSLManagedSSHKey.importEncoded(material.privateKeyPKCS8, passphrase: nil)
        guard key.algorithm == material.algorithm, try key.publicKeyBlob() == material.publicKeyBlob else {
            throw SshManagedKeyProviderError.cryptographicFailure("Stored SSH private and public key material do not match.")
        }
        return key
    }

    private static func signatureAlgorithm(
        _ signature: SshManagedSignatureAlgorithm,
        matches key: SshManagedKeyAlgorithm
    ) -> Bool {
        switch (key, signature) {
        case (.ed25519, .ed25519),
             (.rsa, .rsaSHA256), (.rsa, .rsaSHA512), (.rsa, .rsaSHA1Legacy),
             (.ecdsaNistP256, .ecdsaNistP256): true
        default: false
        }
    }

    private struct StoredManagedKey: Codable {
        var version: Int
        var algorithm: SshManagedKeyAlgorithm
        var publicKeyBlob: Data
        var privateKeyPKCS8: Data
    }
}

private nonisolated final class OpenSSLManagedSSHKey: @unchecked Sendable {
    private let pointer: OpaquePointer

    var algorithm: SshManagedKeyAlgorithm {
        switch NSSshManagedKeyGetAlgorithm(pointer).rawValue {
        case NSSshManagedKeyAlgorithmEd25519.rawValue: .ed25519
        case NSSshManagedKeyAlgorithmRSA.rawValue: .rsa
        case NSSshManagedKeyAlgorithmECDSANistP256.rawValue: .ecdsaNistP256
        default: preconditionFailure("validated OpenSSL key has an unknown algorithm")
        }
    }

    private init(pointer: OpaquePointer) { self.pointer = pointer }

    deinit { NSSshManagedKeyDestroy(pointer) }

    static func create(
        _ body: (UnsafeMutablePointer<CChar>, Int) -> OpaquePointer?
    ) throws -> OpenSSLManagedSSHKey {
        var error = [CChar](repeating: 0, count: 512)
        let pointer = error.withUnsafeMutableBufferPointer { buffer in
            body(buffer.baseAddress!, buffer.count)
        }
        guard let pointer else { throw bridgeError(error) }
        return OpenSSLManagedSSHKey(pointer: pointer)
    }

    static func importEncoded(_ encoded: Data, passphrase: Data?) throws -> OpenSSLManagedSSHKey {
        try create { error, errorCount in
            encoded.withUnsafeBytes { encodedBytes in
                if let passphrase {
                    return passphrase.withUnsafeBytes { passwordBytes in
                        NSSshManagedKeyImport(
                            encodedBytes.bindMemory(to: UInt8.self).baseAddress,
                            encodedBytes.count,
                            passwordBytes.bindMemory(to: UInt8.self).baseAddress,
                            passwordBytes.count,
                            error,
                            errorCount
                        )
                    }
                }
                return NSSshManagedKeyImport(
                    encodedBytes.bindMemory(to: UInt8.self).baseAddress,
                    encodedBytes.count,
                    nil,
                    0,
                    error,
                    errorCount
                )
            }
        }
    }

    static func createEd25519(seed: Data, expectedPublicKey: Data) throws -> OpenSSLManagedSSHKey {
        try create { error, errorCount in
            seed.withUnsafeBytes { seedBytes in
                expectedPublicKey.withUnsafeBytes { publicBytes in
                    NSSshManagedKeyCreateEd25519(
                        seedBytes.bindMemory(to: UInt8.self).baseAddress,
                        seedBytes.count,
                        publicBytes.bindMemory(to: UInt8.self).baseAddress,
                        publicBytes.count,
                        error,
                        errorCount
                    )
                }
            }
        }
    }

    static func createRSA(
        modulus: Data,
        publicExponent: Data,
        privateExponent: Data,
        coefficient: Data,
        primeP: Data,
        primeQ: Data
    ) throws -> OpenSSLManagedSSHKey {
        try create { error, errorCount in
            withSixDataPointers(modulus, publicExponent, privateExponent, coefficient, primeP, primeQ) { values in
                NSSshManagedKeyCreateRSA(
                    values[0].baseAddress, values[0].count,
                    values[1].baseAddress, values[1].count,
                    values[2].baseAddress, values[2].count,
                    values[3].baseAddress, values[3].count,
                    values[4].baseAddress, values[4].count,
                    values[5].baseAddress, values[5].count,
                    error, errorCount
                )
            }
        }
    }

    static func createECDSA(
        privateScalar: Data,
        expectedPublicPoint: Data
    ) throws -> OpenSSLManagedSSHKey {
        try create { error, errorCount in
            privateScalar.withUnsafeBytes { scalarBytes in
                expectedPublicPoint.withUnsafeBytes { publicBytes in
                    NSSshManagedKeyCreateECDSANistP256(
                        scalarBytes.bindMemory(to: UInt8.self).baseAddress,
                        scalarBytes.count,
                        publicBytes.bindMemory(to: UInt8.self).baseAddress,
                        publicBytes.count,
                        error,
                        errorCount
                    )
                }
            }
        }
    }

    func pkcs8() throws -> Data {
        try copyBuffer { output, outputCount, error, errorCount in
            NSSshManagedKeyCopyPKCS8(pointer, output, outputCount, error, errorCount)
        }
    }

    func publicKeyBlob() throws -> Data {
        try copyBuffer { output, outputCount, error, errorCount in
            NSSshManagedKeyCopyPublicKeyBlob(pointer, output, outputCount, error, errorCount)
        }
    }

    func sign(algorithm: SshManagedSignatureAlgorithm, message: Data) throws -> Data {
        let bridgeAlgorithm: NSSshManagedSignatureAlgorithm
        switch algorithm {
        case .ed25519: bridgeAlgorithm = NSSshManagedSignatureAlgorithmEd25519
        case .rsaSHA256: bridgeAlgorithm = NSSshManagedSignatureAlgorithmRSASHA256
        case .rsaSHA512: bridgeAlgorithm = NSSshManagedSignatureAlgorithmRSASHA512
        case .ecdsaNistP256: bridgeAlgorithm = NSSshManagedSignatureAlgorithmECDSANistP256
        case .rsaSHA1Legacy: bridgeAlgorithm = NSSshManagedSignatureAlgorithmRSASHA1Legacy
        }
        return try message.withUnsafeBytes { messageBytes in
            try copyBuffer { output, outputCount, error, errorCount in
                NSSshManagedKeySign(
                    pointer,
                    bridgeAlgorithm,
                    messageBytes.bindMemory(to: UInt8.self).baseAddress,
                    messageBytes.count,
                    output,
                    outputCount,
                    error,
                    errorCount
                )
            }
        }
    }

    private func copyBuffer(
        _ body: (
            UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>,
            UnsafeMutablePointer<Int>,
            UnsafeMutablePointer<CChar>,
            Int
        ) -> Int32
    ) throws -> Data {
        var output: UnsafeMutablePointer<UInt8>?
        var outputCount = 0
        var error = [CChar](repeating: 0, count: 512)
        let result = error.withUnsafeMutableBufferPointer { errorBuffer in
            body(&output, &outputCount, errorBuffer.baseAddress!, errorBuffer.count)
        }
        guard result == 1, let output, outputCount > 0 else {
            if let output { NSSshSensitiveBufferDestroy(output, outputCount) }
            throw Self.bridgeError(error)
        }
        defer { NSSshSensitiveBufferDestroy(output, outputCount) }
        return Data(bytes: output, count: outputCount)
    }

    private static func bridgeError(_ buffer: [CChar]) -> SshManagedKeyProviderError {
        let message = buffer.withUnsafeBufferPointer { pointer -> String in
            guard let baseAddress = pointer.baseAddress, baseAddress.pointee != 0 else {
                return "SSH cryptographic operation failed."
            }
            return String(cString: baseAddress)
        }
        let localizedMessage: LocalizedStringResource
        switch message {
        case let value where value.hasPrefix("invalid private key or passphrase"):
            localizedMessage = "The SSH private key or passphrase is invalid."
        case let value where value.hasPrefix("RSA key size is outside") ||
            value.hasPrefix("RSA generation supports"):
            localizedMessage = "The RSA key size is not supported."
        case let value where value.hasPrefix("unsupported SSH private-key algorithm") ||
            value.hasPrefix("unsupported SSH key-generation algorithm"):
            localizedMessage = "This SSH private-key algorithm is not supported."
        case let value where value.hasPrefix("SSH private-key input is outside"):
            localizedMessage = "SSH private-key input is outside the allowed size limit."
        case let value where value.hasPrefix("SSH signature algorithm does not match"):
            localizedMessage = "The requested SSH signature algorithm does not match the key."
        case let value where value.hasPrefix("missing SSH private key"):
            localizedMessage = "The SSH private key is no longer available in Keychain."
        case let value where value.hasPrefix("Ed25519 identity components") ||
            value.hasPrefix("Ed25519 private and public components") ||
            value.hasPrefix("RSA identity components") ||
            value.hasPrefix("P-256 identity components") ||
            value.hasPrefix("P-256 private and public components") ||
            value.hasPrefix("SSH private-key parameters"):
            localizedMessage = "The SSH private key is invalid."
        default:
            localizedMessage = "SSH cryptographic operation failed."
        }
        return .cryptographicFailure(localizedMessage)
    }

    private static func withSixDataPointers<T>(
        _ first: Data,
        _ second: Data,
        _ third: Data,
        _ fourth: Data,
        _ fifth: Data,
        _ sixth: Data,
        body: ([UnsafeBufferPointer<UInt8>]) -> T
    ) -> T {
        first.withUnsafeBytes { firstBytes in
            second.withUnsafeBytes { secondBytes in
                third.withUnsafeBytes { thirdBytes in
                    fourth.withUnsafeBytes { fourthBytes in
                        fifth.withUnsafeBytes { fifthBytes in
                            sixth.withUnsafeBytes { sixthBytes in
                                body([
                                    firstBytes.bindMemory(to: UInt8.self),
                                    secondBytes.bindMemory(to: UInt8.self),
                                    thirdBytes.bindMemory(to: UInt8.self),
                                    fourthBytes.bindMemory(to: UInt8.self),
                                    fifthBytes.bindMemory(to: UInt8.self),
                                    sixthBytes.bindMemory(to: UInt8.self),
                                ])
                            }
                        }
                    }
                }
            }
        }
    }
}
