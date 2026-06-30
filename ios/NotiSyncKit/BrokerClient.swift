import Foundation

nonisolated enum BrokerError: Error, LocalizedError {
    case badURL
    case http(Int, String)
    case attestationFailed(Int, String)
    case unauthorized

    var errorDescription: String? {
        switch self {
        case .badURL:
            return String(localized: "error.broker.badURL", defaultValue: "The broker URL is not valid.", comment: "Error shown when the configured broker URL cannot be parsed.")
        case let .http(code, detail):
            return String(
                format: String(localized: "error.broker.http", defaultValue: "Broker returned %d: %@", comment: "Broker HTTP error with status code and server detail."),
                code,
                detail
            )
        case let .attestationFailed(code, detail):
            return String(
                format: String(localized: "error.broker.attestationFailed", defaultValue: "Attestation failed (%d): %@", comment: "Device attestation error with status code and server detail."),
                code,
                detail
            )
        case .unauthorized:
            return String(localized: "error.broker.unauthorized", defaultValue: "Broker rejected the request (401).", comment: "Broker unauthorized error.")
        }
    }
}

private nonisolated enum WebSocketLoopEvent: Sendable {
    case receivedText(String)
    case deliveryFinished
}

nonisolated struct StoredBrokerAuth: Codable, Sendable {
    var token: String
    var clientId: String
    var expiresAt: Int64
}

private nonisolated struct DemoStartRequest: Codable, Sendable {
    var pairingUrl: String
}

private nonisolated struct DemoStartResponse: Codable, Sendable {
    var pairingUrl: String
}

nonisolated enum BrokerAuthStore {
    private static let jwtAccount = "broker.jwt"

    static func load(clientId: String? = nil) -> StoredBrokerAuth? {
        for group in [NotiSyncConfig.keychainGroup, nil] as [String?] {
            guard let data = Keychain.get(account: jwtAccount, accessGroup: group),
                  let stored = try? JSONDecoder().decode(StoredBrokerAuth.self, from: data),
                  clientId == nil || stored.clientId == clientId else {
                continue
            }
            if group == nil { save(stored) }      // migrate legacy app-only tokens into the shared group.
            return stored
        }
        return nil
    }

    static func cachedBearer(clientId: String? = nil, refreshSkew: TimeInterval = 60) -> String? {
        guard let auth = load(clientId: clientId) else { return nil }
        let nowMillis = Date().timeIntervalSince1970 * 1000
        return nowMillis < Double(auth.expiresAt) - refreshSkew * 1000 ? auth.token : nil
    }

    static func save(_ value: StoredBrokerAuth?) {
        if let value, let data = try? JSONEncoder().encode(value) {
            let wroteShared = Keychain.set(data, account: jwtAccount, accessGroup: NotiSyncConfig.keychainGroup)
            let wroteDefault = Keychain.set(data, account: jwtAccount, accessGroup: nil)
            _ = wroteShared || wroteDefault
        } else {
            Keychain.delete(account: jwtAccount, accessGroup: NotiSyncConfig.keychainGroup)
            Keychain.delete(account: jwtAccount, accessGroup: nil)
        }
    }
}

/// Transport-neutral broker client for the NS2 `/v2` API. Owns the bearer-JWT lifecycle (lazy attest,
/// 401 self-heal, persisted across launches). Control plane = CBOR bodies; `/integrity/verify` and
/// relay-ack = JSON; live delivery = an authenticated WebSocket. Mirrors `app/.../transport/BrokerClient.kt`.
actor BrokerClient {
    private let baseURL: @Sendable () -> String
    private let identitySigner: IdentitySigner
    /// A PROVIDER (not a fixed instance) so a key-rotation that advances the operational epoch is picked up
    /// on the next request without reconstructing the broker client (#8).
    private let operationalSignerProvider: @Sendable () -> OperationalSigner
    private var operationalSigner: OperationalSigner { operationalSignerProvider() }
    private let attestor: Attestor
    private let keyEpochProvider: @Sendable () throws -> SignedBlob
    private let session: URLSession

    private var cachedAuth: StoredBrokerAuth?
    private var lastFailureAt: Date?
    private var failureCooldown: TimeInterval = 0

    private static let refreshSkew: TimeInterval = 60
    private static let wsInitialBackoffNanos: UInt64 = 1_000_000_000
    private static let wsMaxBackoffNanos: UInt64 = 30_000_000_000
    private static let wsReauthAfterFailures = 3
    private static let wsMaxInFlightDeliveries = 8
    init(baseURL: @escaping @Sendable () -> String,
         identitySigner: IdentitySigner,
         operationalSigner: @escaping @Sendable () -> OperationalSigner,
         attestor: Attestor,
         keyEpochProvider: @escaping @Sendable () throws -> SignedBlob,
         session: URLSession = .shared) {
        self.baseURL = baseURL
        self.identitySigner = identitySigner
        self.operationalSignerProvider = operationalSigner
        self.attestor = attestor
        self.keyEpochProvider = keyEpochProvider
        self.session = session
        cachedAuth = BrokerAuthStore.load(clientId: identitySigner.clientId)
    }

    // MARK: URLs

    private func httpBase() -> String {
        baseURL().trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "ws://", with: "http://")
            .replacingOccurrences(of: "wss://", with: "https://")
            .trimmingTrailingSlash()
    }

    private func wsBase() -> String {
        baseURL().trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "http://", with: "ws://")
            .replacingOccurrences(of: "https://", with: "wss://")
            .trimmingTrailingSlash()
    }

    private func url(_ path: String) throws -> URL {
        guard let u = URL(string: httpBase() + path) else { throw BrokerError.badURL }
        return u
    }

    /// Normalize a request path to a stable Performance-Monitoring label, collapsing id-bearing segments
    /// (`/v2/relay/<id>` → `/v2/relay/_id_`) so high-cardinality paths aggregate to one URL pattern instead
    /// of fragmenting — or being dropped once Firebase's per-pattern budget is exceeded.
    private static func metricTemplate(forPath path: String) -> String {
        let withoutQuery = path.split(separator: "?", maxSplits: 1).first.map(String.init) ?? path
        let parts = withoutQuery.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard parts.first == "v2", parts.count >= 2 else { return withoutQuery }
        switch parts[1] {
        case "keyepoch": return parts.count > 2 ? "/v2/keyepoch/_id_" : "/v2/keyepoch"
        case "relay":
            if parts.count == 2 { return "/v2/relay" }
            if parts.count >= 3, parts[2] == "ack" { return "/v2/relay/ack" }
            return "/v2/relay/_id_"
        case "assets": return "/v2/assets/_id_/_id_"
        default: return "/v2/" + parts[1]   // send, status, routes, connect, integrity, …
        }
    }

    // MARK: Public endpoints

    func health() async -> HealthResponse? {
        guard let u = URL(string: httpBase() + "/healthz") else { return nil }
        guard let (data, resp) = try? await PerfMonitor.http(url: u, method: "GET", requestBytes: 0, template: "/healthz", {
            try await self.session.data(from: u)
        }), Self.ok(resp) else { return nil }
        return try? ProtocolCodec.decodeHealthResponse(data)
    }

    func verificationStatus() async -> VerificationStatusResponse? {
        guard let u = try? url("/v2/status") else { return nil }
        var req = URLRequest(url: u)
        if let token = cachedBearerForRefresh() { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let statusReq = req
        guard let (data, resp) = try? await PerfMonitor.http(url: statusReq.url, method: "GET", requestBytes: 0, template: "/v2/status", {
            try await self.session.data(for: statusReq)
        }), Self.ok(resp) else { return nil }
        return try? ProtocolCodec.decodeVerificationStatusResponse(data)
    }

    func publishKeyEpoch(_ blob: SignedBlob) async throws {
        _ = try await authed(path: "/v2/keyepoch", method: "POST",
                             body: ProtocolCodec.encode(blob), contentType: "application/cbor", operational: false)
    }

    func fetchKeyEpoch(clientId: String, epoch: Int? = nil) async -> SignedBlob? {
        let q = epoch.map { "?epoch=\($0)" } ?? ""
        guard let (data, resp) = try? await authed(path: "/v2/keyepoch/\(clientId)\(q)", method: "GET",
                                                   body: Data(), contentType: nil, operational: false),
              Self.ok(resp) else { return nil }
        return try? ProtocolCodec.decodeSignedBlob(data)
    }

    /// Recovery-only key-epoch probe. It signs with the identity key and reuses a still-valid cached bearer
    /// if one exists, but it never re-attests. That keeps a stale local epoch from causing a failed recovery
    /// probe to put the client into the attestation failure cooldown before it can publish the recovered epoch.
    func fetchKeyEpochWithCachedAuth(clientId: String, epoch: Int? = nil) async -> SignedBlob? {
        let q = epoch.map { "?epoch=\($0)" } ?? ""
        guard var req = try? signedRequest(path: "/v2/keyepoch/\(clientId)\(q)", method: "GET",
                                           body: Data(), signer: identitySigner, contentType: nil) else {
            return nil
        }
        if let token = cachedBearerForRefresh() {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let epochReq = req
        guard let (data, resp) = try? await PerfMonitor.http(url: epochReq.url, method: "GET", requestBytes: 0, template: "/v2/keyepoch/_id_", {
            try await self.session.data(for: epochReq)
        }), Self.ok(resp) else { return nil }
        return try? ProtocolCodec.decodeSignedBlob(data)
    }

    func publishRoutes(_ routes: [SignedBlob]) async throws {
        guard !routes.isEmpty else { return }
        _ = try await authed(path: "/v2/routes", method: "POST",
                             body: ProtocolCodec.encodeSignedBlobList(routes), contentType: "application/cbor", operational: false)
    }

    func startDemoExperience(pairingUrl: String) async throws -> String {
        let u = try url("/demo")
        let body = try JSONEncoder().encode(DemoStartRequest(pairingUrl: pairingUrl))
        var req = URLRequest(url: u)
        req.httpMethod = "POST"
        req.httpBody = body
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let demoReq = req
        let (data, resp) = try await PerfMonitor.http(url: demoReq.url, method: "POST", requestBytes: body.count, template: "/demo", {
            try await self.session.data(for: demoReq)
        })
        guard let http = resp as? HTTPURLResponse else { throw BrokerError.http(0, "no response") }
        guard (200..<300).contains(http.statusCode) else {
            throw BrokerError.http(http.statusCode, String(decoding: data, as: UTF8.self))
        }
        return try JSONDecoder().decode(DemoStartResponse.self, from: data).pairingUrl
    }

    @discardableResult
    func send(_ envelope: Envelope) async throws -> Bool {
        let (_, resp) = try await authed(path: "/v2/send", method: "POST",
                                         body: ProtocolCodec.encode(envelope), contentType: "application/cbor", operational: true)
        return Self.ok(resp)
    }

    func uploadAsset(sourceClientId: String, assetId: String, ciphertext: Data) async -> Bool {
        guard let (_, resp) = try? await authed(path: "/v2/assets/\(sourceClientId)/\(assetId)", method: "POST",
                                                body: ciphertext, contentType: "application/octet-stream", operational: true)
        else { return false }
        return Self.ok(resp) || resp.statusCode == 409
    }

    func fetchAsset(sourceClientId: String, assetId: String) async -> Data? {
        guard let (data, resp) = try? await authed(path: "/v2/assets/\(sourceClientId)/\(assetId)", method: "GET",
                                                   body: Data(), contentType: nil, operational: true), Self.ok(resp) else { return nil }
        return data
    }

    // MARK: Relay (operational-signed, identity fallback, never attests)

    func fetchRelayMessage(_ messageId: String, ackOnFetch: Bool = false) async -> Data? {
        let escaped = messageId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? messageId
        let headers = ackOnFetch ? [:] : ["Peek": "true"]
        guard let (data, resp) = try? await sigOnly(path: "/v2/relay/\(escaped)", method: "GET", body: Data(),
                                                    contentType: nil, extraHeaders: headers),
              Self.ok(resp) else { return nil }
        return data
    }

    func pendingRelayIds() async -> [String] {
        guard let (data, resp) = try? await sigOnly(path: "/v2/relay", method: "GET", body: Data(), contentType: nil),
              Self.ok(resp) else { return [] }
        return (try? ProtocolCodec.decodeRelayPending(data))?.messageIds ?? []
    }

    @discardableResult
    func ackRelayMessages(_ messageIds: [String]) async -> Bool {
        guard !messageIds.isEmpty else { return true }
        let body = ProtocolCodec.encodeRelayAck(messageIds)
        guard let (_, resp) = try? await sigOnly(path: "/v2/relay/ack", method: "POST", body: body, contentType: "application/json")
        else { return false }
        return Self.ok(resp)
    }

    // MARK: Live delivery (authenticated WebSocket)

    /// Connect, authenticate (nonce challenge signed by the identity key), and hand each delivered
    /// envelope's bytes to `onEnvelope`; ack after it returns true. Reconnects until cancellation.
    func liveDelivery(onEnvelope: @Sendable @escaping (Data) async -> Bool) async {
        var backoffNanos = Self.wsInitialBackoffNanos
        var consecutiveFailures = 0
        while !Task.isCancelled {
            do {
                try await liveDeliveryOnce(onEnvelope: onEnvelope) {
                    backoffNanos = Self.wsInitialBackoffNanos
                    consecutiveFailures = 0
                }
            } catch {
                if Task.isCancelled { return }
                consecutiveFailures += 1
                // A run of failed handshakes with a cached token usually means the broker rejected or rotated
                // it; dropping the cache lets the next loop re-attest instead of replaying the stale bearer.
                if consecutiveFailures >= Self.wsReauthAfterFailures {
                    storeAuth(nil)
                    consecutiveFailures = 0
                }
            }
            if Task.isCancelled { return }
            let jitter = UInt64.random(in: 0...(backoffNanos / 2))
            try? await Task.sleep(nanoseconds: backoffNanos + jitter)
            backoffNanos = min(backoffNanos * 2, Self.wsMaxBackoffNanos)
        }
    }

    private func liveDeliveryOnce(
        onEnvelope: @Sendable @escaping (Data) async -> Bool,
        onConnected: () -> Void
    ) async throws {
        guard let u = URL(string: wsBase() + "/v2/connect"),
              let httpURL = try? url("/v2/connect") else { throw BrokerError.badURL }
        var req = URLRequest(url: u)
        guard let headers = try? RequestSigner.signedHeaders(signer: identitySigner, method: "GET",
                                                             url: httpURL, body: Data()) else {
            throw BrokerError.unauthorized
        }
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        var attachedBearer = false
        if let token = await bearerTokenOrNil() {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            attachedBearer = true
        }

        // The live-delivery WebSocket is invisible to Firebase's automatic network monitoring (it only
        // captures data/upload/download tasks), so trace connect + nonce-handshake latency and reconnect churn.
        let connectSpan = PerfMonitor.startSpan("ws_connect")
        connectSpan.attribute("had_bearer", attachedBearer ? "true" : "false")
        let task = session.webSocketTask(with: req)
        task.resume()
        defer { task.cancel(with: .goingAway, reason: nil) }

        do {
            let challengeText = try await task.receiveText()
            guard let challenge = try? ProtocolCodec.decodeWsChallenge(challengeText) else {
                throw BrokerError.unauthorized
            }
            let sig = try identitySigner.sign(Data(challenge.nonce.utf8)).base64EncodedString()  // WS sig = base64 STANDARD
            try await task.send(.string(ProtocolCodec.encodeWsAuth(clientId: identitySigner.clientId, nonce: challenge.nonce, signatureB64: sig)))
            connectSpan.attribute("result", "ok")
            connectSpan.stop()
        } catch {
            connectSpan.attribute("result", "fail")
            connectSpan.stop()
            throw error
        }
        onConnected()

        try await withThrowingTaskGroup(of: WebSocketLoopEvent.self) { group in
            var receivePending = false
            var inFlightDeliveries = 0

            func startReceive() {
                receivePending = true
                group.addTask {
                    .receivedText(try await task.receiveText())
                }
            }

            startReceive()
            defer { group.cancelAll() }

            while !Task.isCancelled {
                guard let event = try await group.next() else { break }
                switch event {
                case .receivedText(let text):
                    receivePending = false
                    if let msg = try? ProtocolCodec.decodeWsMessage(text) {
                        if msg.kind == WsKind.deliver,
                           let b64 = msg.envelopeB64,
                           let bytes = Data(base64Encoded: b64) {
                            let messageId = msg.messageId ?? (try? ProtocolCodec.envelopeMessageId(bytes))
                            group.addTask {
                                let handled = await onEnvelope(bytes)
                                guard handled, let messageId, !Task.isCancelled else { return .deliveryFinished }
                                try await task.send(.string(ProtocolCodec.encodeWsAck(messageId: messageId)))
                                return .deliveryFinished
                            }
                            inFlightDeliveries += 1
                        } else if msg.kind == WsKind.ping {
                            try await task.send(.string(ProtocolCodec.encodeWsPong()))
                        }
                    }
                case .deliveryFinished:
                    inFlightDeliveries = max(0, inFlightDeliveries - 1)
                }

                if !receivePending,
                   inFlightDeliveries < Self.wsMaxInFlightDeliveries,
                   !Task.isCancelled {
                    startReceive()
                }
            }
        }
    }

    // MARK: Auth

    func clearCachedAuth() { storeAuth(nil) }

    private func authed(path: String, method: String, body: Data, contentType: String?, operational: Bool) async throws -> (Data, HTTPURLResponse) {
        let signer: EnvelopeSigner = operational ? operationalSigner : identitySigner
        var req = try signedRequest(path: path, method: method, body: body, signer: signer, contentType: contentType)
        if let token = await bearerTokenOrNil() { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let template = Self.metricTemplate(forPath: path)
        let firstReq = req
        var (data, resp) = try await PerfMonitor.http(url: firstReq.url, method: method, requestBytes: body.count, template: template, {
            try await self.session.data(for: firstReq)
        })
        if (resp as? HTTPURLResponse)?.statusCode == 401 {
            storeAuth(nil)
            let token = try await bearerToken()
            if operational {
                await uploadCurrentKeyEpoch(bearerToken: token)
            }
            req = try signedRequest(path: path, method: method, body: body,
                                    signer: operational ? identitySigner : signer, contentType: contentType)
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let retryReq = req
            (data, resp) = try await PerfMonitor.http(url: retryReq.url, method: method, requestBytes: body.count, template: template, {
                try await self.session.data(for: retryReq)
            })
        }
        guard let http = resp as? HTTPURLResponse else { throw BrokerError.http(0, "no response") }
        guard (200..<300).contains(http.statusCode) else {
            throw BrokerError.http(http.statusCode, String(decoding: data, as: UTF8.self))
        }
        return (data, http)
    }

    private func sigOnly(path: String, method: String, body: Data, contentType: String?,
                         extraHeaders: [String: String] = [:]) async throws -> (Data, HTTPURLResponse) {
        let token = cachedBearerForRefresh()
        var req = try signedRequest(path: path, method: method, body: body, signer: operationalSigner, contentType: contentType)
        for (key, value) in extraHeaders { req.setValue(value, forHTTPHeaderField: key) }
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let template = Self.metricTemplate(forPath: path)
        let firstReq = req
        var (data, resp) = try await PerfMonitor.http(url: firstReq.url, method: method, requestBytes: body.count, template: template, {
            try await self.session.data(for: firstReq)
        })
        if (resp as? HTTPURLResponse)?.statusCode == 401 {
            if token != nil { storeAuth(nil) }
            let retryToken = await bearerTokenOrNil()
            await uploadCurrentKeyEpoch(bearerToken: retryToken)
            req = try signedRequest(path: path, method: method, body: body, signer: identitySigner, contentType: contentType)
            for (key, value) in extraHeaders { req.setValue(value, forHTTPHeaderField: key) }
            if let retryToken { req.setValue("Bearer \(retryToken)", forHTTPHeaderField: "Authorization") }
            let retryReq = req
            (data, resp) = try await PerfMonitor.http(url: retryReq.url, method: method, requestBytes: body.count, template: template, {
                try await self.session.data(for: retryReq)
            })
        }
        guard let http = resp as? HTTPURLResponse else { throw BrokerError.http(0, "no response") }
        return (data, http)
    }

    private func signedRequest(path: String, method: String, body: Data, signer: EnvelopeSigner, contentType: String?) throws -> URLRequest {
        let u = try url(path)
        var req = URLRequest(url: u)
        req.httpMethod = method
        if method != "GET" { req.httpBody = body }
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        for (k, v) in try RequestSigner.signedHeaders(signer: signer, method: method, url: u, body: body) {
            req.setValue(v, forHTTPHeaderField: k)
        }
        return req
    }

    private func uploadCurrentKeyEpoch(bearerToken: String?) async {
        guard let blob = try? keyEpochProvider() else { return }
        let body = ProtocolCodec.encode(blob)
        guard var req = try? signedRequest(path: "/v2/keyepoch", method: "POST", body: body,
                                           signer: identitySigner, contentType: "application/cbor") else { return }
        if let bearerToken { req.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization") }
        let epochReq = req
        _ = try? await PerfMonitor.http(url: epochReq.url, method: "POST", requestBytes: body.count, template: "/v2/keyepoch", {
            try await self.session.data(for: epochReq)
        })
    }

    private func cachedBearerOrNull() -> String? {
        guard let auth = cachedAuth else { return nil }
        return Date().timeIntervalSince1970 * 1000 < Double(auth.expiresAt) - Self.refreshSkew * 1000 ? auth.token : nil
    }

    private func cachedBearerForRefresh() -> String? {
        guard let auth = cachedAuth else { return nil }
        return Date().timeIntervalSince1970 * 1000 < Double(auth.expiresAt) ? auth.token : nil
    }

    private func bearerTokenOrNil() async -> String? {
        if let t = cachedBearerOrNull() { return t }
        return try? await bearerToken()
    }

    private func bearerToken() async throws -> String {
        if let t = cachedBearerOrNull() { return t }
        // Only the attest path is traced (the cache hit above returns first). Covers App Check + PoW + the
        // /integrity/verify round-trip — and flags cooldown lockouts, which silently stall all delivery.
        let span = PerfMonitor.startSpan("bearer_acquire")
        defer { span.stop() }
        if let until = lastFailureAt, Date().timeIntervalSince(until) < failureCooldown {
            span.attribute("result", "cooldown")
            throw BrokerError.unauthorized
        }
        do {
            let auth = try await verifyIntegrity()
            lastFailureAt = nil; failureCooldown = 0
            storeAuth(auth)
            span.attribute("result", "ok")
            return auth.token
        } catch {
            lastFailureAt = Date()
            failureCooldown = min((failureCooldown == 0 ? 5 : failureCooldown) * 2, 300)
            span.attribute("result", "fail")
            throw error
        }
    }

    private func verifyIntegrity() async throws -> StoredBrokerAuth {
        let keyEpoch = try keyEpochProvider()
        let token = await attestor.token()
        let body = ProtocolCodec.encodeIntegrityVerificationRequest(
            clientId: identitySigner.clientId,
            attestationType: attestor.attestationType,
            attestationToken: token,
            clientKeyEpoch: keyEpoch
        )
        let u = try url("/v2/integrity/verify")
        var req = URLRequest(url: u)
        req.httpMethod = "POST"
        req.httpBody = body
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let headers = try RequestSigner.signedHeaders(signer: identitySigner, method: "POST", url: u, body: body)
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        let refreshToken = cachedBearerForRefresh()
        if let refreshToken {
            req.setValue("Bearer \(refreshToken)", forHTTPHeaderField: "Authorization")
        } else if let signature = headers[RequestSigner.headerSignature] {
            // First contact: solve PoW bound to this request's signature at the broker's advertised difficulty.
            let difficulty = await verificationStatus()?.powDifficulty ?? 0
            if difficulty > 0 {
                let ts = Int64(Date().timeIntervalSince1970 * 1000)
                let nonce = ProofOfWork.solve(signature: signature, timestampMillis: ts, difficulty: difficulty)
                req.setValue(nonce, forHTTPHeaderField: ProofOfWork.headerNonce)
                req.setValue("\(ts)", forHTTPHeaderField: ProofOfWork.headerTimestamp)
            }
        }
        let verifyReq = req
        let (data, resp) = try await PerfMonitor.http(url: verifyReq.url, method: "POST", requestBytes: body.count, template: "/v2/integrity/verify", {
            try await self.session.data(for: verifyReq)
        })
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            throw BrokerError.attestationFailed(code, String(decoding: data, as: UTF8.self))
        }
        let response = try ProtocolCodec.decodeIntegrityVerificationResponse(data)
        return StoredBrokerAuth(token: response.token, clientId: response.clientId, expiresAt: response.expiresAt)
    }

    private func storeAuth(_ value: StoredBrokerAuth?) {
        cachedAuth = value
        BrokerAuthStore.save(value)
    }

    private static func ok(_ resp: URLResponse) -> Bool {
        guard let http = resp as? HTTPURLResponse else { return false }
        return (200..<300).contains(http.statusCode)
    }

}

private nonisolated extension String {
    func trimmingTrailingSlash() -> String { hasSuffix("/") ? String(dropLast()) : self }
}

private nonisolated extension URLSessionWebSocketTask {
    func receiveText() async throws -> String {
        switch try await receive() {
        case let .string(text): return text
        case let .data(data): return String(decoding: data, as: UTF8.self)
        @unknown default: return ""
        }
    }
}
