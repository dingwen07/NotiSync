import Foundation

/// Minimal relay access for the NSE. Requests are signed with the operational key first, carry a cached
/// broker bearer when the app has saved one, and fall back to the identity key if the broker has not learned
/// the current operational keyepoch yet.
nonisolated enum RelayClient {
    private static func base() -> String {
        var s = NotiSyncConfig.brokerURL.trimmingCharacters(in: .whitespacesAndNewlines)
        s = s.replacingOccurrences(of: "ws://", with: "http://").replacingOccurrences(of: "wss://", with: "https://")
        if s.hasSuffix("/") { s = String(s.dropLast()) }
        return s
    }

    /// Pull one queued envelope by id. New brokers honor `Peek: true` and leave it queued until the caller
    /// explicitly acks after successful handling; older brokers ignore it and keep legacy ack-on-fetch behavior.
    static func fetchMessage(_ messageId: String,
                             identitySigner: IdentitySigner,
                             operationalSigner: OperationalSigner,
                             keyEpochProvider: @Sendable () throws -> SignedBlob) async -> Data? {
        let escaped = messageId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? messageId
        guard let url = URL(string: base() + "/v2/relay/\(escaped)") else { return nil }
        guard let (data, resp) = await send(url: url, method: "GET", body: Data(), contentType: nil,
                                            identitySigner: identitySigner, operationalSigner: operationalSigner,
                                            keyEpochProvider: keyEpochProvider,
                                            extraHeaders: ["Peek": "true"]),
              (resp as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) == true else { return nil }
        return data
    }

    /// Batch-ack handled messages so the broker drops them (prevents the app re-delivering on WS connect).
    static func ack(_ messageIds: [String],
                    identitySigner: IdentitySigner,
                    operationalSigner: OperationalSigner,
                    keyEpochProvider: @Sendable () throws -> SignedBlob) async {
        guard !messageIds.isEmpty, let url = URL(string: base() + "/v2/relay/ack") else { return }
        let body = ProtocolCodec.encodeRelayAck(messageIds)
        _ = await send(url: url, method: "POST", body: body, contentType: "application/json",
                       identitySigner: identitySigner, operationalSigner: operationalSigner,
                       keyEpochProvider: keyEpochProvider)
    }

    /// Fetch a private asset blob for the NSE. Decrypting and persistence stay with the caller/AssetCache.
    static func fetchAsset(_ ref: PrivateAssetRef,
                           identitySigner: IdentitySigner,
                           operationalSigner: OperationalSigner,
                           keyEpochProvider: @Sendable () throws -> SignedBlob) async -> Data? {
        let source = ref.sourceClientId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? ref.sourceClientId
        let asset = ref.assetId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? ref.assetId
        guard let url = URL(string: base() + "/v2/assets/\(source)/\(asset)") else { return nil }
        guard let (data, resp) = await send(url: url, method: "GET", body: Data(), contentType: nil,
                                            identitySigner: identitySigner, operationalSigner: operationalSigner,
                                            keyEpochProvider: keyEpochProvider),
              (resp as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) == true else { return nil }
        return data
    }

    private static func send(url: URL,
                             method: String,
                             body: Data,
                             contentType: String?,
                             identitySigner: IdentitySigner,
                             operationalSigner: OperationalSigner,
                             keyEpochProvider: @Sendable () throws -> SignedBlob,
                             extraHeaders: [String: String] = [:]) async -> (Data, URLResponse)? {
        let bearer = BrokerAuthStore.cachedBearer(clientId: identitySigner.clientId, refreshSkew: 0)
        guard let req = signedRequest(url: url, method: method, body: body, signer: operationalSigner,
                                      contentType: contentType, bearer: bearer, extraHeaders: extraHeaders),
              var result = try? await URLSession.shared.data(for: req) else { return nil }
        if (result.1 as? HTTPURLResponse)?.statusCode == 401 {
            let retryBearer = BrokerAuthStore.cachedBearer(clientId: identitySigner.clientId, refreshSkew: 0) ?? bearer
            await uploadKeyEpoch(identitySigner: identitySigner, bearer: retryBearer, keyEpochProvider: keyEpochProvider)
            guard let fallback = signedRequest(url: url, method: method, body: body, signer: identitySigner,
                                               contentType: contentType, bearer: retryBearer, extraHeaders: extraHeaders),
                  let retry = try? await URLSession.shared.data(for: fallback) else { return result }
            result = retry
        }
        return result
    }

    private static func uploadKeyEpoch(identitySigner: IdentitySigner,
                                       bearer: String?,
                                       keyEpochProvider: @Sendable () throws -> SignedBlob) async {
        guard let url = URL(string: base() + "/v2/keyepoch"),
              let blob = try? keyEpochProvider() else { return }
        let body = ProtocolCodec.encode(blob)
        guard let req = signedRequest(url: url, method: "POST", body: body, signer: identitySigner,
                                      contentType: "application/cbor", bearer: bearer) else { return }
        _ = try? await URLSession.shared.data(for: req)
    }

    private static func signedRequest(url: URL,
                                      method: String,
                                      body: Data,
                                      signer: EnvelopeSigner,
                                      contentType: String?,
                                      bearer: String?,
                                      extraHeaders: [String: String] = [:]) -> URLRequest? {
        var req = URLRequest(url: url)
        req.httpMethod = method
        if method != "GET" { req.httpBody = body }
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        if let bearer { req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        for (key, value) in extraHeaders { req.setValue(value, forHTTPHeaderField: key) }
        guard let headers = try? RequestSigner.signedHeaders(signer: signer, method: method, url: url, body: body) else {
            return nil
        }
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        return req
    }
}
