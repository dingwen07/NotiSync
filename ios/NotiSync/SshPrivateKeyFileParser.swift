import Foundation

nonisolated enum SshPrivateKeyFileFormat: String, Sendable {
    case openSSH = "OpenSSH"
    case pkcs8PEM = "PKCS#8 PEM"
    case traditionalPEM = "PEM"
    case der = "DER"
}

nonisolated struct SshPrivateKeyFileInspection: Sendable, Equatable {
    let format: SshPrivateKeyFileFormat
    let encrypted: Bool
    let passphraseImportSupported: Bool
    let comment: String?
}

nonisolated struct SshAgentIdentityConstraints: Sendable, Equatable {
    let lifetimeSeconds: UInt32?
    let confirmationRequired: Bool
}

nonisolated struct ParsedSshAgentIdentity: Sendable, Equatable {
    let material: SshManagedKeyMaterial
    let comment: String
    let constraints: SshAgentIdentityConstraints
}

/// Non-secret metadata projected from a fully parsed private-key import.
///
/// `fingerprintData` is the canonical SSH public-key blob expected by the app's fingerprint
/// formatter. Private key bytes and import passphrases are intentionally never retained here.
nonisolated struct SshPrivateKeyImportPreview: Sendable, Equatable {
    let algorithm: SshManagedKeyAlgorithm
    let publicKeyBlob: Data
    let importedComment: String?
    let sourceFormat: SshPrivateKeyFileFormat?
    let agentConstraints: SshAgentIdentityConstraints?

    var fingerprintData: Data { publicKeyBlob }
}

nonisolated enum SshPrivateKeyImportError: Error, LocalizedError, Sendable, Equatable {
    case outsideSizeLimit
    case unsupportedFormat
    case puttyUnsupported
    case passphraseRequired
    case encryptedOpenSSHUnsupported
    case malformed(LocalizedStringResource)

    var errorDescription: String? {
        switch self {
        case .outsideSizeLimit:
            String(
                localized: "The SSH private key is outside the 256 KiB size limit.",
                comment: "Error shown when an imported SSH private key exceeds the allowed size."
            )
        case .unsupportedFormat:
            String(
                localized: "This SSH private-key format is not supported.",
                comment: "Error shown when an imported SSH private-key file uses an unsupported format."
            )
        case .puttyUnsupported:
            String(
                localized: "PuTTY PPK private keys are not supported in this version.",
                comment: "Error shown when the user attempts to import a PuTTY PPK private key."
            )
        case .passphraseRequired:
            String(
                localized: "A passphrase is required for this encrypted SSH private key.",
                comment: "Error shown when an encrypted SSH private key is imported without its passphrase."
            )
        case .encryptedOpenSSHUnsupported:
            String(
                localized: "Encrypted OpenSSH private-key files are not supported in this version. Convert the key to encrypted PKCS#8 PEM first.",
                comment: "Error explaining the supported conversion path for an encrypted OpenSSH private-key file."
            )
        case .malformed(let message):
            String(localized: message)
        }
    }
}

/// Bounded file/clipboard and RFC ssh-add identity parsing. Local file and clipboard callers should both map
/// successful imports to the protocol's existing `SAF_IMPORT` origin. Remote `AGENT_IDENTITY` callers retain the
/// parsed constraints and map their origin to `AGENT_ADD`.
nonisolated enum SshPrivateKeyFileParser {
    private static let maximumBytes = 256 * 1024
    private static let maximumPublicBlobBytes = 16 * 1024
    private static let maximumCommentBytes = 4 * 1024
    private static let maximumDisplayCommentBytes = 256
    private static let maximumAgentLifetimeSeconds: UInt32 = 7 * 24 * 60 * 60
    private static let openSSHMagic = Data("openssh-key-v1\0".utf8)

    static func inspect(_ bytes: Data) throws -> SshPrivateKeyFileInspection {
        try requireBounded(bytes)
        if hasPrefix(bytes, "PuTTY-User-Key-File-") { throw SshPrivateKeyImportError.puttyUnsupported }
        if contains(bytes, "-----BEGIN OPENSSH PRIVATE KEY-----") {
            let container = try decodeOpenSSHContainer(bytes)
            let encrypted = container.cipherName != "none"
            let comment = encrypted ? nil : try parseOpenSSHPrivateBlock(
                container.privateBlock,
                expectedPublicBlob: container.publicKeyBlob
            ).importedComment
            return SshPrivateKeyFileInspection(
                format: .openSSH,
                encrypted: encrypted,
                passphraseImportSupported: false,
                comment: comment
            )
        }
        if contains(bytes, "-----BEGIN ENCRYPTED PRIVATE KEY-----") {
            return SshPrivateKeyFileInspection(
                format: .pkcs8PEM, encrypted: true, passphraseImportSupported: true, comment: nil
            )
        }
        if contains(bytes, "-----BEGIN PRIVATE KEY-----") {
            return SshPrivateKeyFileInspection(
                format: .pkcs8PEM, encrypted: false, passphraseImportSupported: true, comment: nil
            )
        }
        if contains(bytes, "-----BEGIN RSA PRIVATE KEY-----") ||
            contains(bytes, "-----BEGIN EC PRIVATE KEY-----") {
            let encrypted = contains(bytes, "Proc-Type: 4,ENCRYPTED") || contains(bytes, "DEK-Info:")
            return SshPrivateKeyFileInspection(
                format: .traditionalPEM,
                encrypted: encrypted,
                passphraseImportSupported: true,
                comment: nil
            )
        }
        if hasPrefix(bytes, "-----BEGIN ") { throw SshPrivateKeyImportError.unsupportedFormat }
        return SshPrivateKeyFileInspection(
            format: .der, encrypted: false, passphraseImportSupported: true, comment: nil
        )
    }

    static func parse(_ bytes: Data, passphrase: String?) throws -> SshManagedKeyMaterial {
        let inspection = try inspect(bytes)
        if inspection.format == .openSSH {
            if inspection.encrypted { throw SshPrivateKeyImportError.encryptedOpenSSHUnsupported }
            let container = try decodeOpenSSHContainer(bytes)
            return try parseOpenSSHPrivateBlock(
                container.privateBlock,
                expectedPublicBlob: container.publicKeyBlob
            )
        }
        if inspection.encrypted, passphrase?.isEmpty != false {
            throw SshPrivateKeyImportError.passphraseRequired
        }
        var passwordBytes = passphrase.map { Data($0.utf8) }
        defer {
            let passwordByteCount = passwordBytes?.count ?? 0
            passwordBytes?.resetBytes(in: 0..<passwordByteCount)
            passwordBytes = nil
        }
        do {
            return try SshManagedKeyProvider.importEncodedPrivateKey(
                bytes,
                passphraseUTF8: passwordBytes,
                comment: nil
            )
        } catch let error as SshManagedKeyProviderError {
            throw SshPrivateKeyImportError.malformed(
                LocalizedStringResource(String.LocalizationValue(error.localizedDescription))
            )
        } catch {
            throw SshPrivateKeyImportError.malformed("The SSH private key or passphrase is invalid.")
        }
    }

    static func parseClipboard(_ text: String, passphrase: String?) throws -> SshManagedKeyMaterial {
        let bytes = Data(text.utf8)
        return try parse(bytes, passphrase: passphrase)
    }

    /// Validates and cryptographically parses a private-key file through the same path used by
    /// import, then returns only non-secret preview metadata.
    static func preview(_ bytes: Data, passphrase: String?) throws -> SshPrivateKeyImportPreview {
        let inspection = try inspect(bytes)
        let material = try parse(bytes, passphrase: passphrase)
        return SshPrivateKeyImportPreview(
            algorithm: material.algorithm,
            publicKeyBlob: material.publicKeyBlob,
            importedComment: material.importedComment,
            sourceFormat: inspection.format,
            agentConstraints: nil
        )
    }

    static func parseAgentIdentity(_ payload: Data, constrained: Bool) throws -> ParsedSshAgentIdentity {
        try requireBounded(payload)
        var reader = SshWireReader(payload, maximumStringLength: maximumBytes)
        let wireName = try reader.readUTF8(maximumLength: 128)
        let components: AgentKeyComponents
        switch wireName {
        case "ssh-ed25519":
            let publicKey = try reader.readString(maximumLength: 32)
            let privateAndPublic = try reader.readString(maximumLength: 64)
            guard publicKey.count == 32, privateAndPublic.count == 64,
                  Data(privateAndPublic.suffix(32)) == publicKey else {
                throw SshPrivateKeyImportError.malformed("Ed25519 agent identity components are invalid.")
            }
            components = .ed25519(seed: Data(privateAndPublic.prefix(32)), publicKey: publicKey)
        case "ssh-rsa":
            components = .rsa(
                modulus: try requiredPositiveMPInt(&reader),
                publicExponent: try requiredPositiveMPInt(&reader),
                privateExponent: try requiredPositiveMPInt(&reader),
                coefficient: try requiredPositiveMPInt(&reader),
                primeP: try requiredPositiveMPInt(&reader),
                primeQ: try requiredPositiveMPInt(&reader)
            )
        case "ecdsa-sha2-nistp256":
            guard try reader.readUTF8(maximumLength: 32) == "nistp256" else {
                throw SshPrivateKeyImportError.malformed("Only ECDSA nistp256 agent identities are supported.")
            }
            let point = try reader.readString(maximumLength: 65)
            guard point.count == 65, point.first == 4 else {
                throw SshPrivateKeyImportError.malformed("The P-256 public point is invalid.")
            }
            components = .ecdsaP256(
                point: point,
                scalar: try requiredPositiveMPInt(&reader, maximumLength: 64)
            )
        default:
            throw SshPrivateKeyImportError.malformed("Unsupported SSH agent identity algorithm \(wireName).")
        }

        let comment = try validatedComment(reader.readUTF8(maximumLength: maximumCommentBytes))
        let constraints = try parseConstraints(&reader, constrained: constrained)
        let material = try material(from: components, comment: displayComment(comment))
        return ParsedSshAgentIdentity(material: material, comment: comment, constraints: constraints)
    }

    /// Validates an RFC ssh-add identity and its constraints through the same path used by import,
    /// then returns only non-secret preview metadata.
    static func previewAgentIdentity(
        _ payload: Data,
        constrained: Bool
    ) throws -> SshPrivateKeyImportPreview {
        let parsed = try parseAgentIdentity(payload, constrained: constrained)
        return SshPrivateKeyImportPreview(
            algorithm: parsed.material.algorithm,
            publicKeyBlob: parsed.material.publicKeyBlob,
            importedComment: parsed.material.importedComment,
            sourceFormat: nil,
            agentConstraints: parsed.constraints
        )
    }

    // MARK: OpenSSH private-key container

    private struct OpenSSHContainer {
        var cipherName: String
        var publicKeyBlob: Data
        var privateBlock: Data
    }

    private static func decodeOpenSSHContainer(_ fileBytes: Data) throws -> OpenSSHContainer {
        let decoded = try decodeSinglePEMObject(
            fileBytes,
            begin: "-----BEGIN OPENSSH PRIVATE KEY-----",
            end: "-----END OPENSSH PRIVATE KEY-----"
        )
        guard decoded.count >= openSSHMagic.count,
              decoded.prefix(openSSHMagic.count) == openSSHMagic else {
            throw SshPrivateKeyImportError.malformed("The OpenSSH private-key header is invalid.")
        }
        var reader = SshWireReader(Data(decoded.dropFirst(openSSHMagic.count)), maximumStringLength: maximumBytes)
        let cipherName = try reader.readUTF8(maximumLength: 64)
        let kdfName = try reader.readUTF8(maximumLength: 64)
        let kdfOptions = try reader.readString(maximumLength: 16 * 1024)
        let keyCount = try reader.readUInt32()
        guard keyCount == 1 else {
            throw SshPrivateKeyImportError.malformed("OpenSSH private-key files must contain exactly one key.")
        }
        let publicKeyBlob = try reader.readString(maximumLength: maximumPublicBlobBytes)
        let privateBlock = try reader.readString(maximumLength: maximumBytes)
        try reader.requireEnd()
        if cipherName == "none" {
            guard kdfName == "none", kdfOptions.isEmpty else {
                throw SshPrivateKeyImportError.malformed("The OpenSSH private-key KDF header is invalid.")
            }
        } else {
            guard !cipherName.isEmpty, kdfName == "bcrypt", !kdfOptions.isEmpty else {
                throw SshPrivateKeyImportError.malformed("The encrypted OpenSSH private-key header is invalid.")
            }
        }
        return OpenSSHContainer(
            cipherName: cipherName,
            publicKeyBlob: publicKeyBlob,
            privateBlock: privateBlock
        )
    }

    private static func parseOpenSSHPrivateBlock(
        _ privateBlock: Data,
        expectedPublicBlob: Data
    ) throws -> SshManagedKeyMaterial {
        var reader = SshWireReader(privateBlock, maximumStringLength: maximumBytes)
        let firstCheck = try reader.readUInt32()
        guard firstCheck == (try reader.readUInt32()) else {
            throw SshPrivateKeyImportError.malformed("The OpenSSH private-key check values do not match.")
        }
        let wireName = try reader.readUTF8(maximumLength: 128)
        let components: AgentKeyComponents
        switch wireName {
        case "ssh-ed25519":
            let publicKey = try reader.readString(maximumLength: 32)
            let privateAndPublic = try reader.readString(maximumLength: 64)
            guard publicKey.count == 32, privateAndPublic.count == 64,
                  Data(privateAndPublic.suffix(32)) == publicKey else {
                throw SshPrivateKeyImportError.malformed("Ed25519 private and public components do not match.")
            }
            components = .ed25519(seed: Data(privateAndPublic.prefix(32)), publicKey: publicKey)
        case "ssh-rsa":
            components = .rsa(
                modulus: try requiredPositiveMPInt(&reader),
                publicExponent: try requiredPositiveMPInt(&reader),
                privateExponent: try requiredPositiveMPInt(&reader),
                coefficient: try requiredPositiveMPInt(&reader),
                primeP: try requiredPositiveMPInt(&reader),
                primeQ: try requiredPositiveMPInt(&reader)
            )
        case "ecdsa-sha2-nistp256":
            guard try reader.readUTF8(maximumLength: 32) == "nistp256" else {
                throw SshPrivateKeyImportError.malformed("Only OpenSSH ECDSA nistp256 keys are supported.")
            }
            let point = try reader.readString(maximumLength: 65)
            guard point.count == 65, point.first == 4 else {
                throw SshPrivateKeyImportError.malformed("The P-256 public point is invalid.")
            }
            components = .ecdsaP256(
                point: point,
                scalar: try requiredPositiveMPInt(&reader, maximumLength: 64)
            )
        default:
            throw SshPrivateKeyImportError.malformed("Unsupported OpenSSH private-key algorithm \(wireName).")
        }
        let comment = try validatedComment(reader.readUTF8(maximumLength: maximumCommentBytes))
        try validateOpenSSHPadding(&reader)
        let material = try material(from: components, comment: displayComment(comment))
        guard material.publicKeyBlob == expectedPublicBlob else {
            throw SshPrivateKeyImportError.malformed("OpenSSH private and public key records do not match.")
        }
        return material
    }

    private static func validateOpenSSHPadding(_ reader: inout SshWireReader) throws {
        let count = reader.remaining
        guard count > 0, count <= 255 else {
            throw SshPrivateKeyImportError.malformed("The OpenSSH private-key padding is invalid.")
        }
        for expected in 1...count {
            guard try reader.readByte() == UInt8(expected) else {
                throw SshPrivateKeyImportError.malformed("The OpenSSH private-key padding is invalid.")
            }
        }
        try reader.requireEnd()
    }

    // MARK: Agent identity construction

    private enum AgentKeyComponents {
        case ed25519(seed: Data, publicKey: Data)
        case rsa(
            modulus: Data,
            publicExponent: Data,
            privateExponent: Data,
            coefficient: Data,
            primeP: Data,
            primeQ: Data
        )
        case ecdsaP256(point: Data, scalar: Data)
    }

    private static func material(
        from components: AgentKeyComponents,
        comment: String?
    ) throws -> SshManagedKeyMaterial {
        do {
            switch components {
            case .ed25519(let seed, let publicKey):
                return try SshManagedKeyProvider.importEd25519(
                    seed: seed,
                    expectedPublicKey: publicKey,
                    comment: comment
                )
            case .rsa(let n, let e, let d, let iqmp, let p, let q):
                return try SshManagedKeyProvider.importRSA(
                    modulus: n,
                    publicExponent: e,
                    privateExponent: d,
                    coefficient: iqmp,
                    primeP: p,
                    primeQ: q,
                    comment: comment
                )
            case .ecdsaP256(let point, let scalar):
                return try SshManagedKeyProvider.importECDSANistP256(
                    privateScalar: scalar,
                    expectedPublicPoint: point,
                    comment: comment
                )
            }
        } catch let error as SshManagedKeyProviderError {
            throw SshPrivateKeyImportError.malformed(
                LocalizedStringResource(String.LocalizationValue(error.localizedDescription))
            )
        }
    }

    private static func parseConstraints(
        _ reader: inout SshWireReader,
        constrained: Bool
    ) throws -> SshAgentIdentityConstraints {
        guard constrained else {
            try reader.requireEnd()
            return SshAgentIdentityConstraints(lifetimeSeconds: nil, confirmationRequired: false)
        }
        var lifetime: UInt32?
        var confirmationRequired = false
        while reader.remaining > 0 {
            switch try reader.readByte() {
            case 1:
                guard lifetime == nil else {
                    throw SshPrivateKeyImportError.malformed("The SSH agent identity has a duplicate lifetime constraint.")
                }
                let value = try reader.readUInt32()
                guard value > 0, value <= maximumAgentLifetimeSeconds else {
                    throw SshPrivateKeyImportError.malformed("The SSH agent lifetime constraint is outside the allowed bounds.")
                }
                lifetime = value
            case 2:
                guard !confirmationRequired else {
                    throw SshPrivateKeyImportError.malformed("The SSH agent identity has a duplicate confirmation constraint.")
                }
                confirmationRequired = true
            case 255:
                throw SshPrivateKeyImportError.malformed("SSH agent constraint extensions are not supported.")
            case let unknown:
                throw SshPrivateKeyImportError.malformed("Unknown SSH agent constraint \(unknown).")
            }
        }
        return SshAgentIdentityConstraints(
            lifetimeSeconds: lifetime,
            confirmationRequired: confirmationRequired
        )
    }

    private static func requiredPositiveMPInt(
        _ reader: inout SshWireReader,
        maximumLength: Int = 2_048
    ) throws -> Data {
        let value = try reader.readPositiveMPInt(maximumLength: maximumLength)
        guard !value.isEmpty, value.contains(where: { $0 != 0 }) else {
            throw SshPrivateKeyImportError.malformed("SSH private-key components must be positive.")
        }
        return value
    }

    // MARK: Input validation

    private static func requireBounded(_ bytes: Data) throws {
        guard !bytes.isEmpty, bytes.count <= maximumBytes else {
            throw SshPrivateKeyImportError.outsideSizeLimit
        }
    }

    private static func validatedComment(_ comment: String) throws -> String {
        guard comment.utf8.count <= maximumCommentBytes,
              !comment.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7f }) else {
            throw SshPrivateKeyImportError.malformed("The SSH key comment contains unsupported control characters.")
        }
        return comment
    }

    private static func displayComment(_ comment: String) -> String? {
        let trimmed = comment.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmed.isEmpty && trimmed.utf8.count <= maximumDisplayCommentBytes ? trimmed : nil
    }

    private static func hasPrefix(_ bytes: Data, _ value: String) -> Bool {
        bytes.prefix(value.utf8.count) == Data(value.utf8)
    }

    private static func contains(_ bytes: Data, _ value: String) -> Bool {
        bytes.range(of: Data(value.utf8)) != nil
    }

    private static func decodeSinglePEMObject(_ bytes: Data, begin: String, end: String) throws -> Data {
        guard let text = String(data: bytes, encoding: .utf8) else {
            throw SshPrivateKeyImportError.malformed("The OpenSSH private-key armor is not valid UTF-8.")
        }
        let parts = text.components(separatedBy: begin)
        guard parts.count == 2, parts[0].trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw SshPrivateKeyImportError.malformed("The OpenSSH private-key armor is invalid.")
        }
        let endParts = parts[1].components(separatedBy: end)
        guard endParts.count == 2, endParts[1].trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw SshPrivateKeyImportError.malformed("The OpenSSH private-key armor is invalid.")
        }
        let bodyScalars = endParts[0].unicodeScalars
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=\r\n\t ")
        guard bodyScalars.allSatisfy({ allowed.contains($0) }) else {
            throw SshPrivateKeyImportError.malformed("The OpenSSH private-key base64 is invalid.")
        }
        let compact = endParts[0].filter { !$0.isWhitespace }
        guard !compact.isEmpty, let decoded = Data(base64Encoded: compact), decoded.count <= maximumBytes else {
            throw SshPrivateKeyImportError.malformed("The OpenSSH private-key base64 is invalid.")
        }
        return decoded
    }
}
