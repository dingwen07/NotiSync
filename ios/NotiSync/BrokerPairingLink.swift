import Foundation
import Security

nonisolated enum BrokerPairingError: Error, LocalizedError {
    case invalidLink, authentication, expired
    var errorDescription: String? {
        switch self {
        case .invalidLink:
            return String(localized: "pairing.broker.invalidLink", defaultValue: "This pairing link is invalid or unsupported. Scan a new QR code from NotiSync.")
        case .authentication, .expired:
            return String(localized: "pairing.broker.failed", defaultValue: "Secure Exchange could not finish. Scan a new QR code and try again.")
        }
    }
}

nonisolated enum PairingRole: String, Sendable {
    case HOST = "host", CLIENT = "client"
    var peer: PairingRole { self == .HOST ? .CLIENT : .HOST }
}

nonisolated struct BrokerPairingLink: Hashable, Sendable, Identifiable, CustomStringConvertible {
    let sessionId: String
    let secret: String
    let hostId: String
    let brokerURL: String
    var id: String { sessionId }
    var description: String { "BrokerPairingLink(sessionId=\(sessionId), secret=[redacted])" }
    static let sessionSeconds: TimeInterval = 180
    static let exchangeSeconds: TimeInterval = 30
    static let maxFrameBytes = 32 * 1024
    static let maxCardBytes = 16 * 1024

    init(sessionId: String, secret: String, hostId: String, brokerURL: String = NotiSyncConfig.defaultBrokerURL) throws {
        guard Self.matches(sessionId, "[A-Za-z0-9_-]{22}"), Self.matches(hostId, "[a-z2-7]{32}"),
              Self.matches(secret, "[A-Za-z0-9_-]{43}"), let decoded = NSBase64URL.decode(secret),
              decoded.count == 32, NSBase64URL.encode(decoded) == secret else { throw BrokerPairingError.invalidLink }
        self.sessionId = sessionId; self.secret = secret; self.hostId = hostId
        self.brokerURL = try Self.normalizeBroker(brokerURL)
    }

    func encode() -> String {
        var parts = URLComponents()
        parts.scheme = "https"; parts.host = PairingLinks.httpsHost; parts.path = "/pair"
        parts.queryItems = [URLQueryItem(name: "pair", value: "1.\(sessionId)")]
        if brokerURL != NotiSyncConfig.defaultBrokerURL { parts.queryItems?.append(URLQueryItem(name: "b", value: brokerURL)) }
        parts.percentEncodedFragment = "i=\(hostId)&k=\(secret)"
        return parts.string!
    }

    static func parse(_ text: String) -> BrokerPairingLink? {
        guard text.utf8.count <= 2048, let uri = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              uri.user == nil, uri.password == nil, uri.port == nil,
              (uri.scheme == "https" && uri.host == PairingLinks.httpsHost && ["/pair", "/pair/"].contains(uri.path)) ||
              (uri.scheme == "notisync" && uri.host == "pair" && uri.path.isEmpty),
              let query = fields(uri.percentEncodedQuery), let marker = query["pair"], marker.hasPrefix("1."),
              Set(query.keys).isSubset(of: ["pair", "b"]),
              let fragment = fields(uri.percentEncodedFragment), Set(fragment.keys) == ["i", "k"] else { return nil }
        return try? BrokerPairingLink(sessionId: String(marker.dropFirst(2)), secret: fragment["k"]!,
                                      hostId: fragment["i"]!, brokerURL: query["b"] ?? NotiSyncConfig.defaultBrokerURL)
    }

    static func normalizeBroker(_ text: String) throws -> String {
        guard text.utf8.count <= 512 else { throw BrokerPairingError.invalidLink }
        var trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        guard var uri = URLComponents(string: trimmed), let host = uri.host, !host.isEmpty,
              uri.user == nil, uri.password == nil, uri.percentEncodedQuery == nil, uri.fragment == nil,
              let scheme = uri.scheme,
              ["https", "wss"].contains(scheme) || (["http", "ws"].contains(scheme) && ["localhost", "127.0.0.1", "[::1]"].contains(host)),
              !uri.percentEncodedPath.split(separator: "/").contains(where: { $0 == "." || $0 == ".." })
        else { throw BrokerPairingError.invalidLink }
        uri.scheme = ["https", "wss"].contains(scheme) ? "https" : "http"
        guard let normalized = uri.string else { throw BrokerPairingError.invalidLink }
        return normalized
    }

    static func generateSecret() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw BrokerPairingError.authentication }
        return NSBase64URL.encode(Data(bytes))
    }

    private static func matches(_ value: String, _ pattern: String) -> Bool {
        value.range(of: "\\A(?:\(pattern))\\z", options: .regularExpression) != nil
    }

    private static func fields(_ raw: String?) -> [String: String]? {
        guard let raw, !raw.isEmpty else { return nil }
        var result: [String: String] = [:]
        for field in raw.split(separator: "&", omittingEmptySubsequences: false) {
            let pair = field.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard pair.count == 2, result[String(pair[0])] == nil,
                  let value = String(pair[1]).replacingOccurrences(of: "+", with: " ").removingPercentEncoding else { return nil }
            result[String(pair[0])] = value
        }
        return result
    }
}
