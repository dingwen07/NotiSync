import AuthenticationServices
import CoreFoundation
import CryptoKit
import Foundation
import Security
import UIKit

/// Metadata needed to ask the system credential provider to use an existing SSH passkey.
/// The private key never enters NotiSync; it remains owned by the selected passkey provider.
nonisolated struct SshPasskeyCredentialRecord: Codable, Sendable {
    let credentialID: Data
    let userHandle: Data
    let relyingPartyID: String
    let cosePublicKey: Data
    let publicKeyBlob: Data
    let displayName: String
    let createdAt: Int64
    let backupEligible: Bool
    let backupState: Bool
}

nonisolated struct SshPasskeyRegistrationResult: Sendable {
    let credential: SshPasskeyCredentialRecord
    /// Android-compatible recovery-record v2 JSON. This is public recovery metadata, not private-key material.
    let recoveryRecordJSON: String
}

nonisolated struct SshPasskeyAssertionResult: Sendable {
    /// Exact OpenSSH `webauthn-sk-ecdsa-sha2-nistp256@openssh.com` signature blob.
    let signatureBlob: Data
    let backupEligible: Bool
    let backupState: Bool
}

/// A discoverable assertion identifies a roamed credential before its public recovery record is available.
/// The opaque response is retained so a record obtained from local lookup or direct entry can verify the same assertion.
nonisolated struct SshPasskeyRecoverySelection: Sendable {
    let credentialID: Data
    let userHandle: Data

    fileprivate let challenge: Data
    fileprivate let authenticatorData: Data
    fileprivate let clientDataJSON: Data
    fileprivate let signature: Data
}

nonisolated struct SshPasskeyRecoveryResult: Sendable {
    let credential: SshPasskeyCredentialRecord
}

/// Identifies the system API that successfully persisted a companion public recovery record.
/// Both paths may present system UI and require explicit user approval.
nonisolated enum SshPasskeyRecoveryRecordSaveMethod: Sendable {
    case credentialDataManager
    case sharedWebCredential
}

nonisolated enum SshPasskeyProviderError: Error, LocalizedError, Sendable {
    case authorizationAlreadyInProgress
    case unexpectedCredential
    case invalidInput(LocalizedStringResource)
    case invalidCredential(LocalizedStringResource)

    var errorDescription: String? {
        switch self {
        case .authorizationAlreadyInProgress:
            return String(
                localized: "Another passkey request is already in progress.",
                comment: "Error shown when a second SSH passkey operation starts before the first one finishes."
            )
        case .unexpectedCredential:
            return String(
                localized: "The credential provider returned an unexpected credential type.",
                comment: "Error shown when the credential provider returns a credential type that is invalid for the SSH passkey operation."
            )
        case let .invalidInput(message), let .invalidCredential(message):
            return String(localized: message)
        }
    }
}

/// App-owned boundary around Authentication Services for SSH security-key credentials.
///
/// Only the platform-passkey API is used. It presents the user's enabled passkey providers and therefore supports
/// provider-backed roaming without opting into the physical security-key API. Platform passkeys are discoverable by
/// definition; Apple does not expose the security-key API's resident-key/algorithm selectors here, so every returned
/// credential is independently gated to resident ES256/P-256 data before NotiSync accepts it.
@MainActor
final class SshPasskeyProvider {
    private let credentialProvider = ASAuthorizationPlatformPublicKeyCredentialProvider(
        relyingPartyIdentifier: NotiSyncConfig.sshPasskeyRelyingPartyIdentifier
    )
    private var activeAuthorization: SshPasskeyAuthorizationSession?

    func register(
        displayName: String,
        excludedCredentialIDs: [Data] = [],
        presentationAnchor: ASPresentationAnchor
    ) async throws -> SshPasskeyRegistrationResult {
        let boundedName = try SshPasskeyCodec.validatedDisplayName(displayName)
        guard excludedCredentialIDs.count <= 512,
              excludedCredentialIDs.allSatisfy({ !$0.isEmpty && $0.count <= 1_024 }) else {
            throw SshPasskeyProviderError.invalidInput("Excluded passkey identifiers are outside the allowed bounds.")
        }

        let challenge = try SshPasskeyCodec.randomData(count: 32)
        let userHandle = try SshPasskeyCodec.generateUserHandle()
        guard let accountName = String(data: userHandle, encoding: .utf8) else {
            throw SshPasskeyProviderError.invalidInput("Unable to create the passkey account identifier.")
        }

        let request = credentialProvider.createCredentialRegistrationRequest(
            challenge: challenge,
            name: accountName,
            userID: userHandle
        )
        request.displayName = boundedName
        request.userVerificationPreference = .required
        request.attestationPreference = .none
        request.excludedCredentials = excludedCredentialIDs.map {
            ASAuthorizationPlatformPublicKeyCredentialDescriptor(credentialID: $0)
        }

        let authorized = try await authorize(request, presentationAnchor: presentationAnchor)
        guard let registration = authorized as? ASAuthorizationPlatformPublicKeyCredentialRegistration else {
            throw SshPasskeyProviderError.unexpectedCredential
        }
        let createdAt = Int64(Date().timeIntervalSince1970 * 1_000)
        let record = try SshPasskeyCodec.parseRegistration(
            registration,
            challenge: challenge,
            userHandle: userHandle,
            displayName: boundedName,
            createdAt: createdAt
        )
        return SshPasskeyRegistrationResult(
            credential: record,
            recoveryRecordJSON: try SshPasskeyCodec.encodeRecoveryRecord(record)
        )
    }

    func sign(
        credential: SshPasskeyCredentialRecord,
        challenge: Data,
        presentationAnchor: ASPresentationAnchor
    ) async throws -> SshPasskeyAssertionResult {
        try SshPasskeyCodec.validateCredentialRecord(credential)
        try SshPasskeyCodec.validateChallenge(challenge)
        let raw = try await assertion(
            challenge: challenge,
            allowedCredentialID: credential.credentialID,
            presentationAnchor: presentationAnchor
        )
        return try SshPasskeyCodec.parseAndVerifyAssertion(raw, challenge: challenge, credential: credential)
    }

    /// Starts recovery with no allow-list so the system can select any discoverable passkey for this RP.
    /// Callers may first look up the returned user handle/credential ID, then fall back to direct recovery-record entry.
    func beginRecovery(presentationAnchor: ASPresentationAnchor) async throws -> SshPasskeyRecoverySelection {
        let challenge = try SshPasskeyCodec.randomData(count: 32)
        let raw = try await assertion(
            challenge: challenge,
            allowedCredentialID: nil,
            presentationAnchor: presentationAnchor
        )
        return try SshPasskeyCodec.validateRecoverySelection(raw, challenge: challenge)
    }

    /// Completes a discoverable recovery after either automatic record lookup or direct record entry.
    /// The original assertion is cryptographically verified against the supplied public recovery record.
    func completeRecovery(
        _ selection: SshPasskeyRecoverySelection,
        recoveryRecordJSON: String
    ) throws -> SshPasskeyRecoveryResult {
        let record = try SshPasskeyCodec.decodeRecoveryRecord(recoveryRecordJSON)
        let raw = SshPasskeyRawAssertion(
            credentialID: selection.credentialID,
            userHandle: selection.userHandle,
            authenticatorData: selection.authenticatorData,
            clientDataJSON: selection.clientDataJSON,
            signature: selection.signature
        )
        let verified = try SshPasskeyCodec.parseAndVerifyAssertion(
            raw,
            challenge: selection.challenge,
            credential: record
        )
        return SshPasskeyRecoveryResult(
            credential: SshPasskeyCodec.withBackupState(
                record,
                backupEligible: verified.backupEligible,
                backupState: verified.backupState
            )
        )
    }

    /// Best-effort lookup of Android's companion public recovery record through Shared Web Credentials.
    /// The returned password is public SSH metadata, not a secret; the selected passkey assertion is still
    /// verified against it before the key is accepted. Provider/account sharing is not guaranteed, so callers
    /// must retain direct recovery-record entry as the deterministic fallback.
    func lookupRecoveryRecord(
        for selection: SshPasskeyRecoverySelection,
        presentationAnchor: ASPresentationAnchor
    ) async throws -> String {
        let request = ASAuthorizationPasswordProvider().createRequest()
        let authorized = try await authorize(request, presentationAnchor: presentationAnchor)
        guard let password = authorized as? ASPasswordCredential else {
            throw SshPasskeyProviderError.unexpectedCredential
        }
        let record = password.password.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !record.isEmpty, record.lengthOfBytes(using: .utf8) <= 64 * 1_024 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey recovery record is invalid.")
        }
        // Password providers may omit or rewrite `ASPasswordCredential.user` for Android-created entries that
        // have no website label. Treat it as presentation metadata. The record carries the stable passkey identity,
        // and completeRecovery subsequently verifies the selected assertion against its public key.
        let recoveryCredential = try SshPasskeyCodec.decodeRecoveryRecord(record)
        guard recoveryCredential.credentialID == selection.credentialID,
              recoveryCredential.userHandle == selection.userHandle else {
            throw SshPasskeyProviderError.invalidCredential(
                "The selected recovery record belongs to a different passkey."
            )
        }
        return record
    }

    /// Best-effort, user-mediated storage of Android-compatible public recovery metadata in Passwords.
    ///
    /// A failure only means that the companion recovery record was not saved. This method does not alter or
    /// remove the passkey credential. Callers should surface that partial-success state and retain direct recovery-
    /// record export as a deterministic fallback.
    @discardableResult
    func saveRecoveryRecord(
        _ recoveryRecordJSON: String,
        for credential: SshPasskeyCredentialRecord,
        presentationAnchor: ASPresentationAnchor
    ) async throws -> SshPasskeyRecoveryRecordSaveMethod {
        try SshPasskeyCodec.validateCredentialRecord(credential)
        let recoveryCredential = try SshPasskeyCodec.decodeRecoveryRecord(recoveryRecordJSON)
        // A credential provider can update backup flags after registration, and NotiSync can use a local
        // display name for a recovered key. Match only the stable credential and public-key identity here.
        guard recoveryCredential.credentialID == credential.credentialID,
              recoveryCredential.userHandle == credential.userHandle,
              recoveryCredential.relyingPartyID == credential.relyingPartyID,
              recoveryCredential.cosePublicKey == credential.cosePublicKey,
              recoveryCredential.publicKeyBlob == credential.publicKeyBlob else {
            throw SshPasskeyProviderError.invalidCredential(
                "The passkey recovery record does not match the credential."
            )
        }
        guard let account = String(data: credential.userHandle, encoding: .utf8),
              Data(account.utf8) == credential.userHandle else {
            throw SshPasskeyProviderError.invalidCredential("The passkey user handle is invalid.")
        }

        if #available(iOS 26.2, *) {
            let password = ASPasswordCredential(user: account, password: recoveryRecordJSON)
            try await ASCredentialDataManager().save(
                password: password,
                for: ASAutoFillURLScope(host: credential.relyingPartyID),
                title: credential.displayName,
                anchor: presentationAnchor
            )
            return .credentialDataManager
        } else {
            try await saveRecoveryRecordWithSharedWebCredential(
                recoveryRecordJSON,
                account: account,
                relyingPartyID: credential.relyingPartyID
            )
            return .sharedWebCredential
        }
    }

    /// Direct-record recovery when the caller does not have an automatic record for a discoverable selection.
    func recover(
        recoveryRecordJSON: String,
        presentationAnchor: ASPresentationAnchor
    ) async throws -> SshPasskeyRecoveryResult {
        let record = try SshPasskeyCodec.decodeRecoveryRecord(recoveryRecordJSON)
        let challenge = try SshPasskeyCodec.randomData(count: 32)
        let raw = try await assertion(
            challenge: challenge,
            allowedCredentialID: record.credentialID,
            presentationAnchor: presentationAnchor
        )
        let verified = try SshPasskeyCodec.parseAndVerifyAssertion(
            raw,
            challenge: challenge,
            credential: record
        )
        return SshPasskeyRecoveryResult(
            credential: SshPasskeyCodec.withBackupState(
                record,
                backupEligible: verified.backupEligible,
                backupState: verified.backupState
            )
        )
    }

    func encodeRecoveryRecord(_ credential: SshPasskeyCredentialRecord) throws -> String {
        try SshPasskeyCodec.encodeRecoveryRecord(credential)
    }

    func decodeRecoveryRecord(_ encoded: String) throws -> SshPasskeyCredentialRecord {
        try SshPasskeyCodec.decodeRecoveryRecord(encoded)
    }

    private func assertion(
        challenge: Data,
        allowedCredentialID: Data?,
        presentationAnchor: ASPresentationAnchor
    ) async throws -> SshPasskeyRawAssertion {
        try SshPasskeyCodec.validateChallenge(challenge)
        let request = credentialProvider.createCredentialAssertionRequest(challenge: challenge)
        request.userVerificationPreference = .required
        if let allowedCredentialID {
            request.allowedCredentials = [
                ASAuthorizationPlatformPublicKeyCredentialDescriptor(credentialID: allowedCredentialID),
            ]
        } else {
            request.allowedCredentials = []
        }
        let authorized = try await authorize(request, presentationAnchor: presentationAnchor)
        guard let assertion = authorized as? ASAuthorizationPlatformPublicKeyCredentialAssertion else {
            throw SshPasskeyProviderError.unexpectedCredential
        }
        guard assertion.attachment == .platform else {
            throw SshPasskeyProviderError.invalidCredential("Only platform passkeys are supported for SSH keys.")
        }
        return SshPasskeyRawAssertion(
            credentialID: assertion.credentialID,
            userHandle: assertion.userID,
            authenticatorData: assertion.rawAuthenticatorData,
            clientDataJSON: assertion.rawClientDataJSON,
            signature: assertion.signature
        )
    }

    private func authorize(
        _ request: ASAuthorizationRequest,
        presentationAnchor: ASPresentationAnchor
    ) async throws -> any ASAuthorizationCredential {
        guard activeAuthorization == nil else {
            throw SshPasskeyProviderError.authorizationAlreadyInProgress
        }
        return try await withCheckedThrowingContinuation { continuation in
            let session = SshPasskeyAuthorizationSession(
                request: request,
                presentationAnchor: presentationAnchor
            ) { [self] result in
                activeAuthorization = nil
                continuation.resume(with: result)
            }
            activeAuthorization = session
            session.perform()
        }
    }
}

/// Kept in a declaration whose availability ends where the replacement API begins. This prevents new-SDK
/// deprecation warnings while retaining the deployment-target path for iOS 18.6 through iOS 26.1.
@available(iOS, introduced: 8.0, obsoleted: 26.2)
private func saveRecoveryRecordWithSharedWebCredential(
    _ recoveryRecordJSON: String,
    account: String,
    relyingPartyID: String
) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        SecAddSharedWebCredential(
            relyingPartyID as CFString,
            account as CFString,
            recoveryRecordJSON as CFString
        ) { error in
            if let error {
                continuation.resume(throwing: error as Error)
            } else {
                continuation.resume()
            }
        }
    }
}

@MainActor
private final class SshPasskeyAuthorizationSession: NSObject,
    ASAuthorizationControllerDelegate,
    ASAuthorizationControllerPresentationContextProviding {
    private let presentationAnchor: ASPresentationAnchor
    private let completion: (Result<any ASAuthorizationCredential, Error>) -> Void
    private let controller: ASAuthorizationController
    private var completed = false

    init(
        request: ASAuthorizationRequest,
        presentationAnchor: ASPresentationAnchor,
        completion: @escaping (Result<any ASAuthorizationCredential, Error>) -> Void
    ) {
        self.presentationAnchor = presentationAnchor
        self.completion = completion
        controller = ASAuthorizationController(authorizationRequests: [request])
        super.init()
        controller.delegate = self
        controller.presentationContextProvider = self
    }

    func perform() {
        controller.performRequests()
    }

    func authorizationController(
        controller _: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        finish(.success(authorization.credential))
    }

    func authorizationController(
        controller _: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        finish(.failure(error))
    }

    func presentationAnchor(for _: ASAuthorizationController) -> ASPresentationAnchor {
        presentationAnchor
    }

    private func finish(_ result: Result<any ASAuthorizationCredential, Error>) {
        guard !completed else { return }
        completed = true
        completion(result)
    }
}

private nonisolated struct SshPasskeyRawAssertion: Sendable {
    let credentialID: Data
    let userHandle: Data
    let authenticatorData: Data
    let clientDataJSON: Data
    let signature: Data
}

private nonisolated enum SshPasskeyCodec {
    private static let rpID = NotiSyncConfig.sshPasskeyRelyingPartyIdentifier
    private static let expectedOrigin = NotiSyncConfig.sshPasskeyOrigin

    static func validatedDisplayName(_ displayName: String) throws -> String {
        let value = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, value.lengthOfBytes(using: .utf8) <= 256 else {
            throw SshPasskeyProviderError.invalidInput("The passkey name is outside the allowed bounds.")
        }
        return value
    }

    static func validateChallenge(_ challenge: Data) throws {
        guard !challenge.isEmpty, challenge.count <= 256 * 1_024 else {
            throw SshPasskeyProviderError.invalidInput("The SSH signing challenge is outside the allowed bounds.")
        }
    }

    static func randomData(count: Int) throws -> Data {
        var bytes = Data(count: count)
        let status = bytes.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, count, buffer.baseAddress!)
        }
        guard status == errSecSuccess else {
            throw SshPasskeyProviderError.invalidInput("Secure random-number generation failed.")
        }
        return bytes
    }

    static func generateUserHandle() throws -> Data {
        let token = canonicalBase64URL(try randomData(count: 32))
        let userHandle = Data("notisync-ssh:\(token)".utf8)
        try validateUserHandle(userHandle)
        return userHandle
    }

    static func parseRegistration(
        _ registration: ASAuthorizationPlatformPublicKeyCredentialRegistration,
        challenge: Data,
        userHandle: Data,
        displayName: String,
        createdAt: Int64
    ) throws -> SshPasskeyCredentialRecord {
        guard registration.attachment == .platform else {
            throw SshPasskeyProviderError.invalidCredential("Only platform passkeys are supported for SSH keys.")
        }
        try validateClientDataJSON(registration.rawClientDataJSON, type: "webauthn.create", challenge: challenge)
        guard let attestationObject = registration.rawAttestationObject,
              !attestationObject.isEmpty,
              attestationObject.count <= 64 * 1_024 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey registration did not return usable attestation data.")
        }
        let attestation = try SshCborReader.decodeExact(attestationObject).textMap("attestation object")
        guard try attestation.requiredText("fmt") == "none" else {
            throw SshPasskeyProviderError.invalidCredential("Only none passkey attestation is supported.")
        }
        guard try attestation.requiredMap("attStmt").isEmpty else {
            throw SshPasskeyProviderError.invalidCredential("The none attestation statement must be empty.")
        }
        let parsed = try parseRegistrationAuthenticatorData(try attestation.requiredBytes("authData"))
        guard constantTimeEqual(registration.credentialID, parsed.credentialID) else {
            throw SshPasskeyProviderError.invalidCredential("The passkey identifier does not match authenticator data.")
        }
        let record = SshPasskeyCredentialRecord(
            credentialID: registration.credentialID,
            userHandle: userHandle,
            relyingPartyID: rpID,
            cosePublicKey: parsed.cosePublicKey,
            publicKeyBlob: parsed.publicKeyBlob,
            displayName: displayName,
            createdAt: createdAt,
            backupEligible: parsed.backupEligible,
            backupState: parsed.backupState
        )
        try validateCredentialRecord(record)
        return record
    }

    static func validateRecoverySelection(
        _ assertion: SshPasskeyRawAssertion,
        challenge: Data
    ) throws -> SshPasskeyRecoverySelection {
        guard !assertion.credentialID.isEmpty, assertion.credentialID.count <= 1_024 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey identifier is outside the allowed bounds.")
        }
        try validateUserHandle(assertion.userHandle)
        try validateClientDataJSON(assertion.clientDataJSON, type: "webauthn.get", challenge: challenge)
        _ = try parseAssertionAuthenticatorData(assertion.authenticatorData)
        _ = try SshDerSignature.parse(assertion.signature)
        return SshPasskeyRecoverySelection(
            credentialID: assertion.credentialID,
            userHandle: assertion.userHandle,
            challenge: challenge,
            authenticatorData: assertion.authenticatorData,
            clientDataJSON: assertion.clientDataJSON,
            signature: assertion.signature
        )
    }

    static func parseAndVerifyAssertion(
        _ assertion: SshPasskeyRawAssertion,
        challenge: Data,
        credential: SshPasskeyCredentialRecord
    ) throws -> SshPasskeyAssertionResult {
        try validateCredentialRecord(credential)
        try validateChallenge(challenge)
        guard constantTimeEqual(assertion.credentialID, credential.credentialID) else {
            throw SshPasskeyProviderError.invalidCredential("The passkey assertion selected a different credential.")
        }
        guard constantTimeEqual(assertion.userHandle, credential.userHandle) else {
            throw SshPasskeyProviderError.invalidCredential("The passkey user handle does not match.")
        }
        try validateClientDataJSON(assertion.clientDataJSON, type: "webauthn.get", challenge: challenge)
        let authenticator = try parseAssertionAuthenticatorData(assertion.authenticatorData)
        let parsedSignature = try SshDerSignature.parse(assertion.signature)
        let publicPoint = try publicPoint(fromCOSE: credential.cosePublicKey)
        let publicKey: P256.Signing.PublicKey
        do {
            publicKey = try P256.Signing.PublicKey(x963Representation: publicPoint)
        } catch {
            throw SshPasskeyProviderError.invalidCredential("The passkey P-256 public key is invalid.")
        }
        let signedData = assertion.authenticatorData + Data(SHA256.hash(data: assertion.clientDataJSON))
        guard publicKey.isValidSignature(parsedSignature.cryptoKitSignature, for: signedData) else {
            throw SshPasskeyProviderError.invalidCredential("The passkey assertion signature is invalid.")
        }
        let signatureBlob = try webAuthnSignatureBlob(
            ecdsaSignature: parsedSignature.sshEncoding,
            flags: authenticator.flags,
            counter: authenticator.counter,
            clientDataJSON: assertion.clientDataJSON,
            extensions: authenticator.extensions
        )
        return SshPasskeyAssertionResult(
            signatureBlob: signatureBlob,
            backupEligible: authenticator.backupEligible,
            backupState: authenticator.backupState
        )
    }

    static func withBackupState(
        _ credential: SshPasskeyCredentialRecord,
        backupEligible: Bool,
        backupState: Bool
    ) -> SshPasskeyCredentialRecord {
        SshPasskeyCredentialRecord(
            credentialID: credential.credentialID,
            userHandle: credential.userHandle,
            relyingPartyID: credential.relyingPartyID,
            cosePublicKey: credential.cosePublicKey,
            publicKeyBlob: credential.publicKeyBlob,
            displayName: credential.displayName,
            createdAt: credential.createdAt,
            backupEligible: backupEligible,
            backupState: backupState
        )
    }

    static func validateCredentialRecord(_ record: SshPasskeyCredentialRecord) throws {
        guard record.relyingPartyID == rpID else {
            throw SshPasskeyProviderError.invalidCredential("The passkey recovery record uses an unsupported RP ID.")
        }
        guard !record.credentialID.isEmpty, record.credentialID.count <= 1_024 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey identifier is outside the allowed bounds.")
        }
        try validateUserHandle(record.userHandle)
        _ = try validatedDisplayName(record.displayName)
        guard record.createdAt > 0 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey creation time is invalid.")
        }
        guard !record.backupState || record.backupEligible else {
            throw SshPasskeyProviderError.invalidCredential("Passkey backup state requires backup eligibility.")
        }
        guard !record.cosePublicKey.isEmpty, record.cosePublicKey.count <= 2_048,
              !record.publicKeyBlob.isEmpty, record.publicKeyBlob.count <= 16 * 1_024 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey public key is outside the allowed bounds.")
        }
        let expectedBlob = try publicKeyBlob(fromCOSE: record.cosePublicKey)
        guard constantTimeEqual(expectedBlob, record.publicKeyBlob) else {
            throw SshPasskeyProviderError.invalidCredential("The passkey recovery public keys do not match.")
        }
    }

    static func encodeRecoveryRecord(_ record: SshPasskeyCredentialRecord) throws -> String {
        try validateCredentialRecord(record)
        let object: [String: Any] = [
            "version": 2,
            "credentialId": canonicalBase64URL(record.credentialID),
            "userHandle": canonicalBase64URL(record.userHandle),
            "rpId": record.relyingPartyID,
            "cosePublicKey": canonicalBase64URL(record.cosePublicKey),
            "publicKeyBlob": canonicalBase64URL(record.publicKeyBlob),
            "displayName": record.displayName,
            "createdAt": record.createdAt,
            "backupEligible": record.backupEligible,
            "backupState": record.backupState,
        ]
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
        guard data.count <= 64 * 1_024, let encoded = String(data: data, encoding: .utf8) else {
            throw SshPasskeyProviderError.invalidCredential("The passkey recovery record is too large.")
        }
        return encoded
    }

    static func decodeRecoveryRecord(_ encoded: String) throws -> SshPasskeyCredentialRecord {
        let data = Data(encoded.utf8)
        guard !data.isEmpty, data.count <= 64 * 1_024,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw SshPasskeyProviderError.invalidCredential("The passkey recovery record is invalid.")
        }
        let version = try jsonInteger(object, key: "version")
        let v2Fields: Set<String> = [
            "version", "credentialId", "userHandle", "rpId", "cosePublicKey", "publicKeyBlob",
            "displayName", "createdAt", "backupEligible", "backupState",
        ]
        switch version {
        case 2:
            guard Set(object.keys) == v2Fields else {
                throw SshPasskeyProviderError.invalidCredential("The passkey recovery record has unexpected fields.")
            }
        case 1:
            guard Set(object.keys) == v2Fields.union(["createdOrigin"]),
                  let createdOrigin = object["createdOrigin"] as? String,
                  !createdOrigin.isEmpty,
                  createdOrigin.lengthOfBytes(using: .utf8) <= 1_024 else {
                throw SshPasskeyProviderError.invalidCredential("The legacy passkey recovery record is invalid.")
            }
        default:
            throw SshPasskeyProviderError.invalidCredential("The passkey recovery record version is unsupported.")
        }
        let record = SshPasskeyCredentialRecord(
            credentialID: try canonicalBase64URL(try jsonString(object, key: "credentialId"), maximumBytes: 1_024),
            userHandle: try canonicalBase64URL(try jsonString(object, key: "userHandle"), maximumBytes: 64),
            relyingPartyID: try jsonString(object, key: "rpId"),
            cosePublicKey: try canonicalBase64URL(try jsonString(object, key: "cosePublicKey"), maximumBytes: 2_048),
            publicKeyBlob: try canonicalBase64URL(try jsonString(object, key: "publicKeyBlob"), maximumBytes: 16 * 1_024),
            displayName: try jsonString(object, key: "displayName"),
            createdAt: try jsonInteger(object, key: "createdAt"),
            backupEligible: try jsonBoolean(object, key: "backupEligible"),
            backupState: try jsonBoolean(object, key: "backupState")
        )
        try validateCredentialRecord(record)
        return record
    }

    private static func validateUserHandle(_ userHandle: Data) throws {
        guard !userHandle.isEmpty, userHandle.count <= 64,
              let printable = String(data: userHandle, encoding: .utf8),
              Data(printable.utf8) == userHandle,
              printable.hasPrefix("notisync-ssh:") else {
            throw SshPasskeyProviderError.invalidCredential("The passkey user handle is invalid.")
        }
        let token = String(printable.dropFirst("notisync-ssh:".count))
        guard let decoded = try? canonicalBase64URL(token, maximumBytes: 32), decoded.count == 32 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey user handle is invalid.")
        }
    }

    private static func validateClientDataJSON(_ data: Data, type: String, challenge: Data) throws {
        guard !data.isEmpty, data.count <= 12 * 1_024,
              String(data: data, encoding: .utf8) != nil,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              object["type"] as? String == type,
              object["origin"] as? String == expectedOrigin else {
            throw SshPasskeyProviderError.invalidCredential("The passkey client data is invalid.")
        }
        let returnedChallenge = try canonicalBase64URL(try jsonString(object, key: "challenge"), maximumBytes: 256 * 1_024)
        guard constantTimeEqual(returnedChallenge, challenge) else {
            throw SshPasskeyProviderError.invalidCredential("The passkey challenge does not match.")
        }
        if type == "webauthn.get" {
            let prefix = Data(
                "{\"type\":\"webauthn.get\",\"challenge\":\"\(canonicalBase64URL(challenge))\",\"origin\":\"\(expectedOrigin)\""
                    .utf8
            )
            guard data.starts(with: prefix) else {
                throw SshPasskeyProviderError.invalidCredential(
                    "The passkey client data cannot be represented by the OpenSSH WebAuthn signature format."
                )
            }
        }
        if let crossOrigin = object["crossOrigin"] {
            guard let value = crossOrigin as? NSNumber,
                  CFGetTypeID(value) == CFBooleanGetTypeID(),
                  !value.boolValue else {
                throw SshPasskeyProviderError.invalidCredential("Cross-origin passkey responses are not supported.")
            }
        }
    }

    private static func parseRegistrationAuthenticatorData(_ data: Data) throws -> SshRegistrationAuthenticatorData {
        guard data.count >= 56 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey registration authenticator data is truncated.")
        }
        let bytes = [UInt8](data)
        try validateRpIDHash(Data(bytes[0..<32]))
        let flags = bytes[32]
        try validateFlags(flags, expectsAttestedCredentialData: true)
        let credentialLength = (Int(bytes[53]) << 8) | Int(bytes[54])
        let credentialStart = 55
        let coseStart = credentialStart + credentialLength
        guard credentialLength > 0, credentialLength <= 1_024, coseStart < bytes.count else {
            throw SshPasskeyProviderError.invalidCredential("The passkey identifier is outside the allowed bounds.")
        }
        let decoded = try SshCborReader.decodeFirst(data, offset: coseStart)
        let cosePublicKey = Data(bytes[coseStart..<decoded.nextOffset])
        let extensions = Data(bytes[decoded.nextOffset..<bytes.count])
        try validateExtensions(flags: flags, extensions: extensions)
        let publicKeyBlob = try publicKeyBlob(fromCOSEValue: decoded.value)
        return SshRegistrationAuthenticatorData(
            credentialID: Data(bytes[credentialStart..<coseStart]),
            cosePublicKey: cosePublicKey,
            publicKeyBlob: publicKeyBlob,
            backupEligible: flags & 0x08 != 0,
            backupState: flags & 0x10 != 0
        )
    }

    private static func parseAssertionAuthenticatorData(_ data: Data) throws -> SshAssertionAuthenticatorData {
        guard data.count >= 37, data.count <= 16 * 1_024 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey assertion authenticator data is truncated.")
        }
        let bytes = [UInt8](data)
        try validateRpIDHash(Data(bytes[0..<32]))
        let flags = bytes[32]
        try validateFlags(flags, expectsAttestedCredentialData: false)
        let counter = UInt32(bytes[33]) << 24 | UInt32(bytes[34]) << 16 | UInt32(bytes[35]) << 8 | UInt32(bytes[36])
        let extensions = Data(bytes[37..<bytes.count])
        try validateExtensions(flags: flags, extensions: extensions)
        return SshAssertionAuthenticatorData(
            flags: flags,
            counter: counter,
            extensions: extensions,
            backupEligible: flags & 0x08 != 0,
            backupState: flags & 0x10 != 0
        )
    }

    private static func validateFlags(_ flags: UInt8, expectsAttestedCredentialData: Bool) throws {
        guard flags & 0x01 != 0, flags & 0x04 != 0 else {
            throw SshPasskeyProviderError.invalidCredential("Passkey user presence and verification are required.")
        }
        guard (flags & 0x40 != 0) == expectsAttestedCredentialData else {
            throw SshPasskeyProviderError.invalidCredential("The passkey attested-credential flag is invalid.")
        }
        guard flags & 0x10 == 0 || flags & 0x08 != 0 else {
            throw SshPasskeyProviderError.invalidCredential("Passkey backup state requires backup eligibility.")
        }
    }

    private static func validateExtensions(flags: UInt8, extensions: Data) throws {
        guard extensions.count <= 2 * 1_024, (flags & 0x80 != 0) == !extensions.isEmpty else {
            throw SshPasskeyProviderError.invalidCredential("The passkey extension flag does not match extension data.")
        }
        if !extensions.isEmpty {
            _ = try SshCborReader.decodeExact(extensions)
        }
    }

    private static func validateRpIDHash(_ returned: Data) throws {
        let expected = Data(SHA256.hash(data: Data(rpID.utf8)))
        guard constantTimeEqual(returned, expected) else {
            throw SshPasskeyProviderError.invalidCredential("The passkey RP ID hash does not match.")
        }
    }

    private static func publicKeyBlob(fromCOSE cose: Data) throws -> Data {
        try publicKeyBlob(fromCOSEValue: SshCborReader.decodeExact(cose))
    }

    private static func publicPoint(fromCOSE cose: Data) throws -> Data {
        let map = try SshCborReader.decodeExact(cose).integerMap("COSE public key")
        guard try map.requiredInteger(1) == 2,
              try map.requiredInteger(3) == -7,
              try map.requiredInteger(-1) == 1 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey must use ES256 with a P-256 EC2 key.")
        }
        let x = try map.requiredBytes(-2)
        let y = try map.requiredBytes(-3)
        guard x.count == 32, y.count == 32 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey P-256 coordinates must be 32 bytes.")
        }
        let point = Data([0x04]) + x + y
        guard (try? P256.Signing.PublicKey(x963Representation: point)) != nil else {
            throw SshPasskeyProviderError.invalidCredential("The passkey P-256 public key is invalid.")
        }
        return point
    }

    private static func publicKeyBlob(fromCOSEValue value: SshCborValue) throws -> Data {
        let map = try value.integerMap("COSE public key")
        guard try map.requiredInteger(1) == 2,
              try map.requiredInteger(3) == -7,
              try map.requiredInteger(-1) == 1 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey must use ES256 with a P-256 EC2 key.")
        }
        let x = try map.requiredBytes(-2)
        let y = try map.requiredBytes(-3)
        guard x.count == 32, y.count == 32 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey P-256 coordinates must be 32 bytes.")
        }
        let point = Data([0x04]) + x + y
        guard (try? P256.Signing.PublicKey(x963Representation: point)) != nil else {
            throw SshPasskeyProviderError.invalidCredential("The passkey P-256 public key is invalid.")
        }
        var writer = SshPasskeyWireWriter()
        try writer.writeUTF8("sk-ecdsa-sha2-nistp256@openssh.com")
        try writer.writeUTF8("nistp256")
        try writer.writeString(point)
        try writer.writeUTF8(rpID)
        return writer.data
    }

    private static func webAuthnSignatureBlob(
        ecdsaSignature: Data,
        flags: UInt8,
        counter: UInt32,
        clientDataJSON: Data,
        extensions: Data
    ) throws -> Data {
        guard flags & 0x40 == 0,
              !clientDataJSON.isEmpty,
              clientDataJSON.count <= 12 * 1_024,
              extensions.count <= 2 * 1_024 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey signature fields are outside the allowed bounds.")
        }
        var writer = SshPasskeyWireWriter()
        // OpenSSH places the WebAuthn fields directly after the algorithm name. There is no outer signature string.
        try writer.writeUTF8("webauthn-sk-ecdsa-sha2-nistp256@openssh.com")
        try writer.writeString(ecdsaSignature)
        writer.writeByte(flags)
        writer.writeUInt32(counter)
        try writer.writeUTF8(expectedOrigin)
        try writer.writeString(clientDataJSON)
        try writer.writeString(extensions)
        guard writer.data.count <= 16 * 1_024 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey SSH signature is too large.")
        }
        return writer.data
    }

    private static func canonicalBase64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func canonicalBase64URL(_ encoded: String, maximumBytes: Int) throws -> Data {
        guard !encoded.isEmpty,
              !encoded.contains("="),
              encoded.utf8.allSatisfy({ byte in
                  (byte >= 48 && byte <= 57) || (byte >= 65 && byte <= 90) ||
                      (byte >= 97 && byte <= 122) || byte == 45 || byte == 95
              }) else {
            throw SshPasskeyProviderError.invalidCredential("The passkey record contains invalid base64url data.")
        }
        var base64 = encoded.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64 += "=" }
        guard let decoded = Data(base64Encoded: base64),
              !decoded.isEmpty,
              decoded.count <= maximumBytes,
              canonicalBase64URL(decoded) == encoded else {
            throw SshPasskeyProviderError.invalidCredential("The passkey record contains non-canonical base64url data.")
        }
        return decoded
    }

    private static func constantTimeEqual(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var difference: UInt8 = 0
        for (left, right) in zip(lhs, rhs) { difference |= left ^ right }
        return difference == 0
    }

    private static func jsonString(_ object: [String: Any], key: String) throws -> String {
        guard let value = object[key] as? String, !value.isEmpty else {
            throw SshPasskeyProviderError.invalidCredential("The passkey recovery record is missing \(key).")
        }
        return value
    }

    private static func jsonInteger(_ object: [String: Any], key: String) throws -> Int64 {
        guard let number = object[key] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else {
            throw SshPasskeyProviderError.invalidCredential("The passkey recovery record has an invalid \(key).")
        }
        let decimal = number.decimalValue
        var source = decimal
        var rounded = Decimal()
        NSDecimalRound(&rounded, &source, 0, .plain)
        guard decimal == rounded,
              decimal >= Decimal(Int64.min),
              decimal <= Decimal(Int64.max) else {
            throw SshPasskeyProviderError.invalidCredential("The passkey recovery record has an invalid \(key).")
        }
        return NSDecimalNumber(decimal: decimal).int64Value
    }

    private static func jsonBoolean(_ object: [String: Any], key: String) throws -> Bool {
        guard let number = object[key] as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else {
            throw SshPasskeyProviderError.invalidCredential("The passkey recovery record has an invalid \(key).")
        }
        return number.boolValue
    }
}

private nonisolated struct SshRegistrationAuthenticatorData {
    let credentialID: Data
    let cosePublicKey: Data
    let publicKeyBlob: Data
    let backupEligible: Bool
    let backupState: Bool
}

private nonisolated struct SshAssertionAuthenticatorData {
    let flags: UInt8
    let counter: UInt32
    let extensions: Data
    let backupEligible: Bool
    let backupState: Bool
}

private nonisolated struct SshPasskeyWireWriter {
    private(set) var data = Data()

    mutating func writeByte(_ value: UInt8) {
        data.append(value)
    }

    mutating func writeUInt32(_ value: UInt32) {
        data.append(UInt8((value >> 24) & 0xff))
        data.append(UInt8((value >> 16) & 0xff))
        data.append(UInt8((value >> 8) & 0xff))
        data.append(UInt8(value & 0xff))
    }

    mutating func writeUTF8(_ value: String) throws {
        try writeString(Data(value.utf8))
    }

    mutating func writeString(_ value: Data) throws {
        guard value.count <= Int(UInt32.max) else {
            throw SshPasskeyProviderError.invalidCredential("An SSH wire field is too large.")
        }
        writeUInt32(UInt32(value.count))
        data.append(value)
    }
}

private nonisolated struct SshDerSignature {
    let cryptoKitSignature: P256.Signing.ECDSASignature
    let sshEncoding: Data

    static func parse(_ der: Data) throws -> SshDerSignature {
        guard !der.isEmpty, der.count <= 256 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey ECDSA signature is outside the allowed bounds.")
        }
        var outer = SshDerReader(der)
        let sequence = try outer.readElement(tag: 0x30)
        try outer.requireEnd()
        var integers = SshDerReader(sequence)
        let r = try integers.readPositiveP256Integer()
        let s = try integers.readPositiveP256Integer()
        try integers.requireEnd()
        let cryptoSignature: P256.Signing.ECDSASignature
        do {
            cryptoSignature = try P256.Signing.ECDSASignature(derRepresentation: der)
        } catch {
            throw SshPasskeyProviderError.invalidCredential("The passkey ECDSA signature is invalid.")
        }
        var writer = SshPasskeyWireWriter()
        try writer.writeString(r)
        try writer.writeString(s)
        return SshDerSignature(cryptoKitSignature: cryptoSignature, sshEncoding: writer.data)
    }
}

private nonisolated struct SshDerReader {
    private let bytes: [UInt8]
    private var offset = 0

    init(_ data: Data) {
        bytes = [UInt8](data)
    }

    mutating func readElement(tag: UInt8) throws -> Data {
        guard offset < bytes.count, bytes[offset] == tag else {
            throw SshPasskeyProviderError.invalidCredential("The passkey ECDSA signature has an unexpected DER tag.")
        }
        offset += 1
        let length = try readLength()
        guard length <= bytes.count - offset else {
            throw SshPasskeyProviderError.invalidCredential("The passkey ECDSA signature is truncated.")
        }
        let result = Data(bytes[offset..<(offset + length)])
        offset += length
        return result
    }

    mutating func readPositiveP256Integer() throws -> Data {
        let encoded = try readElement(tag: 0x02)
        let bytes = [UInt8](encoded)
        guard !bytes.isEmpty, bytes[0] & 0x80 == 0,
              !(bytes.count > 1 && bytes[0] == 0 && bytes[1] & 0x80 == 0) else {
            throw SshPasskeyProviderError.invalidCredential("The passkey ECDSA integer is not canonical and positive.")
        }
        var magnitude = bytes
        if magnitude.first == 0 { magnitude.removeFirst() }
        guard !magnitude.isEmpty, magnitude.count <= 32, magnitude.contains(where: { $0 != 0 }) else {
            throw SshPasskeyProviderError.invalidCredential("The passkey ECDSA integer is outside P-256 bounds.")
        }
        if magnitude[0] & 0x80 != 0 { magnitude.insert(0, at: 0) }
        return Data(magnitude)
    }

    mutating func requireEnd() throws {
        guard offset == bytes.count else {
            throw SshPasskeyProviderError.invalidCredential("The passkey ECDSA signature has trailing DER data.")
        }
    }

    private mutating func readLength() throws -> Int {
        guard offset < bytes.count else {
            throw SshPasskeyProviderError.invalidCredential("The passkey ECDSA signature is missing a DER length.")
        }
        let first = Int(bytes[offset])
        offset += 1
        if first < 0x80 { return first }
        let count = first & 0x7f
        guard count > 0, count <= 2, offset + count <= bytes.count else {
            throw SshPasskeyProviderError.invalidCredential("The passkey ECDSA signature has an unsupported DER length.")
        }
        var value = 0
        for _ in 0..<count {
            value = (value << 8) | Int(bytes[offset])
            offset += 1
        }
        guard value >= 0x80 else {
            throw SshPasskeyProviderError.invalidCredential("The passkey ECDSA signature has a non-canonical DER length.")
        }
        return value
    }
}

private nonisolated indirect enum SshCborValue {
    case integer(Int64)
    case bytes(Data)
    case text(String)
    case array([SshCborValue])
    case map([(SshCborValue, SshCborValue)])
    case simple(UInt8)

    func textMap(_ description: String) throws -> [String: SshCborValue] {
        guard case let .map(entries) = self else {
            throw SshPasskeyProviderError.invalidCredential("The \(description) must be a CBOR map.")
        }
        var result: [String: SshCborValue] = [:]
        for (key, value) in entries {
            guard case let .text(text) = key, result.updateValue(value, forKey: text) == nil else {
                throw SshPasskeyProviderError.invalidCredential("The \(description) has invalid or duplicate keys.")
            }
        }
        return result
    }

    func integerMap(_ description: String) throws -> [Int64: SshCborValue] {
        guard case let .map(entries) = self else {
            throw SshPasskeyProviderError.invalidCredential("The \(description) must be a CBOR map.")
        }
        var result: [Int64: SshCborValue] = [:]
        for (key, value) in entries {
            guard case let .integer(integer) = key, result.updateValue(value, forKey: integer) == nil else {
                throw SshPasskeyProviderError.invalidCredential("The \(description) has invalid or duplicate keys.")
            }
        }
        return result
    }
}

private nonisolated extension Dictionary where Key == String, Value == SshCborValue {
    func requiredText(_ key: String) throws -> String {
        guard case let .text(value)? = self[key] else {
            throw SshPasskeyProviderError.invalidCredential("The CBOR map is missing text \(key).")
        }
        return value
    }

    func requiredBytes(_ key: String) throws -> Data {
        guard case let .bytes(value)? = self[key] else {
            throw SshPasskeyProviderError.invalidCredential("The CBOR map is missing bytes \(key).")
        }
        return value
    }

    func requiredMap(_ key: String) throws -> [(SshCborValue, SshCborValue)] {
        guard case let .map(value)? = self[key] else {
            throw SshPasskeyProviderError.invalidCredential("The CBOR map is missing map \(key).")
        }
        return value
    }
}

private nonisolated extension Dictionary where Key == Int64, Value == SshCborValue {
    func requiredInteger(_ key: Int64) throws -> Int64 {
        guard case let .integer(value)? = self[key] else {
            throw SshPasskeyProviderError.invalidCredential("The COSE key is missing integer \(key).")
        }
        return value
    }

    func requiredBytes(_ key: Int64) throws -> Data {
        guard case let .bytes(value)? = self[key] else {
            throw SshPasskeyProviderError.invalidCredential("The COSE key is missing bytes \(key).")
        }
        return value
    }
}

private nonisolated struct SshCborReader {
    struct Decoded {
        let value: SshCborValue
        let nextOffset: Int
    }

    private let bytes: [UInt8]
    private var offset: Int
    private var itemCount = 0

    private init(_ data: Data, offset: Int) {
        bytes = [UInt8](data)
        self.offset = offset
    }

    static func decodeExact(_ data: Data) throws -> SshCborValue {
        let decoded = try decodeFirst(data, offset: 0)
        guard decoded.nextOffset == data.count else {
            throw SshPasskeyProviderError.invalidCredential("The CBOR value has trailing data.")
        }
        return decoded.value
    }

    static func decodeFirst(_ data: Data, offset: Int) throws -> Decoded {
        guard !data.isEmpty, data.count <= 64 * 1_024, offset >= 0, offset < data.count else {
            throw SshPasskeyProviderError.invalidCredential("The CBOR input is outside the allowed bounds.")
        }
        var reader = SshCborReader(data, offset: offset)
        let value = try reader.read(depth: 0)
        return Decoded(value: value, nextOffset: reader.offset)
    }

    private mutating func read(depth: Int) throws -> SshCborValue {
        guard depth <= 12, itemCount < 256, offset < bytes.count else {
            throw SshPasskeyProviderError.invalidCredential("The CBOR value is outside the allowed bounds.")
        }
        itemCount += 1
        let initial = bytes[offset]
        offset += 1
        let major = initial >> 5
        let additional = initial & 0x1f
        switch major {
        case 0:
            let value = try readLength(additional)
            guard value <= UInt64(Int64.max) else { throw cborBoundsError() }
            return .integer(Int64(value))
        case 1:
            let value = try readLength(additional)
            guard value <= UInt64(Int64.max) else { throw cborBoundsError() }
            return .integer(-1 - Int64(value))
        case 2:
            return .bytes(try readBytes(length: readLength(additional)))
        case 3:
            let data = try readBytes(length: readLength(additional))
            guard let text = String(data: data, encoding: .utf8), Data(text.utf8) == data else {
                throw SshPasskeyProviderError.invalidCredential("The CBOR text is not valid UTF-8.")
            }
            return .text(text)
        case 4:
            let count = try collectionCount(additional)
            return .array(try (0..<count).map { _ in try read(depth: depth + 1) })
        case 5:
            let count = try collectionCount(additional)
            var entries: [(SshCborValue, SshCborValue)] = []
            entries.reserveCapacity(count)
            for _ in 0..<count {
                entries.append((try read(depth: depth + 1), try read(depth: depth + 1)))
            }
            return .map(entries)
        case 7 where additional == 20 || additional == 21 || additional == 22:
            return .simple(additional)
        default:
            throw SshPasskeyProviderError.invalidCredential("The CBOR value uses an unsupported encoding.")
        }
    }

    private mutating func collectionCount(_ additional: UInt8) throws -> Int {
        let value = try readLength(additional)
        guard value <= 256 else { throw cborBoundsError() }
        return Int(value)
    }

    private mutating func readLength(_ additional: UInt8) throws -> UInt64 {
        switch additional {
        case 0...23:
            return UInt64(additional)
        case 24:
            let value = try readUnsigned(count: 1)
            guard value >= 24 else { throw cborCanonicalError() }
            return value
        case 25:
            let value = try readUnsigned(count: 2)
            guard value > 0xff else { throw cborCanonicalError() }
            return value
        case 26:
            let value = try readUnsigned(count: 4)
            guard value > 0xffff else { throw cborCanonicalError() }
            return value
        case 27:
            let value = try readUnsigned(count: 8)
            guard value > 0xffff_ffff else { throw cborCanonicalError() }
            return value
        default:
            throw SshPasskeyProviderError.invalidCredential("Indefinite CBOR lengths are unsupported.")
        }
    }

    private mutating func readUnsigned(count: Int) throws -> UInt64 {
        guard offset + count <= bytes.count else {
            throw SshPasskeyProviderError.invalidCredential("The CBOR integer is truncated.")
        }
        var value: UInt64 = 0
        for _ in 0..<count {
            value = (value << 8) | UInt64(bytes[offset])
            offset += 1
        }
        return value
    }

    private mutating func readBytes(length: UInt64) throws -> Data {
        guard length <= 64 * 1_024, length <= UInt64(bytes.count - offset) else {
            throw cborBoundsError()
        }
        let count = Int(length)
        let result = Data(bytes[offset..<(offset + count)])
        offset += count
        return result
    }

    private func cborBoundsError() -> SshPasskeyProviderError {
        .invalidCredential("The CBOR value is outside the allowed bounds.")
    }

    private func cborCanonicalError() -> SshPasskeyProviderError {
        .invalidCredential("The CBOR value uses a non-canonical length.")
    }
}
