import Foundation
import Security

/// Canonical HTTP request signing (`notisync-http-sign-v2`) shared with the broker. The signature
/// covers version, method, path+query, clientId, signer-epoch, timestamp, nonce, and base64url(SHA-256(body)).
/// Epoch 0 = identity key (attestation / key-epoch publish / fallback / WS); ≥1 = operational (hot path).
nonisolated enum RequestSigner {
    enum NonceError: Error { case invalidByteCount, randomGenerationFailed(OSStatus) }

    static let headerClientId = "X-NotiSync-Client-Id"
    static let headerTimestamp = "X-NotiSync-Timestamp"
    static let headerNonce = "X-NotiSync-Nonce"
    static let headerContentSha256 = "X-NotiSync-Content-SHA256"
    static let headerSignature = "X-NotiSync-Signature"
    static let headerSignerEpoch = "X-NotiSync-Signer-Epoch"

    static func bodyHash(_ body: Data) -> String { NSBase64URL.encode(NSHash.sha256(body)) }

    static func newNonce(byteCount: Int = 18) throws -> String {
        guard byteCount > 0 else { throw NonceError.invalidByteCount }
        var bytes = Data(count: byteCount)
        let status = bytes.withUnsafeMutableBytes { buffer -> OSStatus in
            guard let baseAddress = buffer.baseAddress else { return errSecParam }
            return SecRandomCopyBytes(kSecRandomDefault, byteCount, baseAddress)
        }
        guard status == errSecSuccess else { throw NonceError.randomGenerationFailed(status) }
        return NSBase64URL.encode(bytes)
    }

    static func canonical(method: String, pathAndQuery: String, clientId: String, signerEpoch: Int,
                          timestampMillis: Int64, nonce: String, contentSha256: String) -> Data {
        Data([
            "notisync-http-sign-v2",
            method.uppercased(),
            pathAndQuery.isEmpty ? "/" : pathAndQuery,
            clientId,
            "\(signerEpoch)",
            "\(timestampMillis)",
            nonce,
            contentSha256,
        ].joined(separator: "\n").utf8)
    }

    /// Returns the signed header dictionary for `signer` over `(method, url, body)`.
    static func signedHeaders(signer: EnvelopeSigner, method: String, url: URL, body: Data,
                              nowMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
                              nonce: String? = nil) throws -> [String: String] {
        let nonce = try nonce ?? newNonce()
        let hash = bodyHash(body)
        let canonical = canonical(method: method, pathAndQuery: pathAndQuery(url), clientId: signer.clientId,
                                  signerEpoch: signer.signerEpoch, timestampMillis: nowMillis, nonce: nonce,
                                  contentSha256: hash)
        let signature = NSBase64URL.encode(try signer.sign(canonical))
        return [
            headerClientId: signer.clientId,
            headerTimestamp: "\(nowMillis)",
            headerNonce: nonce,
            headerContentSha256: hash,
            headerSignature: signature,
            headerSignerEpoch: "\(signer.signerEpoch)",
        ]
    }

    static func pathAndQuery(_ url: URL) -> String {
        var value = url.path(percentEncoded: true)
        if value.isEmpty { value = "/" }
        if let query = url.query(percentEncoded: true), !query.isEmpty { value += "?\(query)" }
        return value
    }
}
