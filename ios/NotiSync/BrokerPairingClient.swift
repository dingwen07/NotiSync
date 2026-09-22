import Foundation

private nonisolated final class BrokerPairingRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// A short-lived, cancellable socket separate from normal broker delivery. No trust is granted here.
nonisolated struct BrokerPairingClient: Sendable {
    func host(brokerURL: String, hostId: String, ownPayload: String,
              onReady: @escaping @Sendable (BrokerPairingLink) -> Void) async throws -> String {
        let broker = try BrokerPairingLink.normalizeBroker(brokerURL)
        let secret = try BrokerPairingLink.generateSecret()
        return try await connect(broker: broker, sessionId: nil) { socket, sessionId in
            let link = try BrokerPairingLink(sessionId: sessionId, secret: secret, hostId: hostId, brokerURL: broker)
            onReady(link)
            return try await exchange(socket, link: link, role: .HOST, payload: ownPayload)
        }
    }

    func join(_ link: BrokerPairingLink, ownPayload: String) async throws -> String {
        try await connect(broker: link.brokerURL, sessionId: link.sessionId) { socket, _ in
            try await exchange(socket, link: link, role: .CLIENT, payload: ownPayload)
        }
    }

    private func connect(broker: String, sessionId: String?,
                         operation: @escaping @Sendable (URLSessionWebSocketTask, String) async throws -> String) async throws -> String {
        var components = URLComponents(string: broker)!
        components.scheme = components.scheme == "https" ? "wss" : "ws"
        components.percentEncodedPath += "/v2/pairing"
        guard let url = components.url else { throw BrokerPairingError.invalidLink }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = BrokerPairingLink.sessionSeconds
        config.timeoutIntervalForResource = BrokerPairingLink.sessionSeconds
        let session = URLSession(configuration: config, delegate: BrokerPairingRedirectDelegate(), delegateQueue: nil)
        let socket = session.webSocketTask(with: url)
        socket.maximumMessageSize = BrokerPairingLink.maxFrameBytes
        defer { socket.cancel(with: .goingAway, reason: nil); session.invalidateAndCancel() }
        return try await withTaskCancellationHandler {
            socket.resume()
            return try await deadline(seconds: BrokerPairingLink.sessionSeconds, socket: socket) {
                try await socket.send(.data(BrokerPairingCodec.request(sessionId: sessionId)))
                let readyBytes = try await deadline(seconds: 10, socket: socket) { try await receive(socket) }
                let receivedId = try BrokerPairingCodec.ready(readyBytes)
                guard sessionId == nil || receivedId == sessionId else { throw BrokerPairingError.authentication }
                return try await operation(socket, receivedId)
            }
        } onCancel: {
            socket.cancel(with: .goingAway, reason: nil)
            session.invalidateAndCancel()
        }
    }

    private func exchange(_ socket: URLSessionWebSocketTask, link: BrokerPairingLink, role: PairingRole,
                          payload: String) async throws -> String {
        let pake = try PairingPake(link: link, role: role)
        defer { pake.close() }
        try await socket.send(.data(pake.round1()))
        let first = try await receive(socket)
        return try await deadline(seconds: BrokerPairingLink.exchangeSeconds, socket: socket) {
            try await socket.send(.data(pake.round2(first)))
            let second = try await receive(socket)
            try await socket.send(.data(pake.round3(second)))
            let third = try await receive(socket)
            try pake.confirm(third)
            try await socket.send(.data(pake.encryptCard(payload)))
            return try pake.decryptCard(await receive(socket))
        }
    }

    private func receive(_ socket: URLSessionWebSocketTask) async throws -> Data {
        guard case let .data(bytes) = try await socket.receive(), !bytes.isEmpty,
              bytes.count <= BrokerPairingLink.maxFrameBytes else { throw BrokerPairingError.authentication }
        return bytes
    }

    private func deadline<T: Sendable>(seconds: TimeInterval, socket: URLSessionWebSocketTask,
                                      operation: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(for: .seconds(seconds))
                socket.cancel(with: .goingAway, reason: nil)
                throw BrokerPairingError.expired
            }
            defer { group.cancelAll() }
            guard let result = try await group.next() else { throw CancellationError() }
            return result
        }
    }
}
