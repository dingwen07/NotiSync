import Foundation
import os

/// Fetches a public App Store icon (by iOS bundle id) for use as a Communication Notification avatar.
/// URL + bytes are cached in the App Group container so the app and the NSE share one fetch. Best-effort:
/// returns nil for an unknown / non-iOS source (e.g. an Android-origin mirror with no iOS bundle id).
nonisolated enum AppIconFetcher {
    /// Bundle ids whose lookup recently came back empty (system apps like Messages aren't in the iTunes
    /// catalog — the most common ANCS-bridged sources). Without this, EVERY render of such a notification
    /// re-runs the two-request lookup. In-memory: one process pays at most one lookup per id per TTL.
    private static let recentMisses = OSAllocatedUnfairLock<[String: Date]>(initialState: [:])
    private static let missTTL: TimeInterval = 6 * 60 * 60

    static func cachedIconData(iosBundleId: String?) -> Data? {
        guard let bundleId = iosBundleId, !bundleId.isEmpty else { return nil }
        return AppGroupStore.readData(cacheName(bundleId))
    }

    static func iconData(iosBundleId: String?) async -> Data? {
        guard let bundleId = iosBundleId, !bundleId.isEmpty else { return nil }

        if let cached = await Task.detached(priority: .utility, operation: {
            cachedIconData(iosBundleId: bundleId)
        }).value { return cached }

        if let missedAt = recentMisses.withLock({ $0[bundleId] }), Date().timeIntervalSince(missedAt) < missTTL {
            return nil
        }
        defer {
            recentMisses.withLock { misses in
                if misses.count > 256 { misses.removeAll() }   // safety bound; entries re-add on next miss
            }
        }

        guard var components = URLComponents(string: "https://itunes.apple.com/lookup") else { return nil }
        components.queryItems = [
            URLQueryItem(name: "bundleId", value: bundleId),
            URLQueryItem(name: "entity", value: "software"),
        ]
        guard let lookupURL = components.url,
              let (json, _) = try? await PerfMonitor.http(url: lookupURL, method: "GET", requestBytes: 0, template: "/lookup", {
                  try await URLSession.shared.data(from: lookupURL)
              }),
              let object = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              let results = object["results"] as? [[String: Any]],
              let artwork = results.first?["artworkUrl100"] as? String,
              let artworkURL = URL(string: artwork),
              let (image, _) = try? await PerfMonitor.http(url: artworkURL, method: "GET", requestBytes: 0, template: "/artwork", {
                  try await URLSession.shared.data(from: artworkURL)
              }) else {
            recentMisses.withLock { $0[bundleId] = Date() }
            return nil
        }
        _ = recentMisses.withLock { $0.removeValue(forKey: bundleId) }

        await Task.detached(priority: .utility) {
            _ = AppGroupStore.writeData(image, cacheName(bundleId))
        }.value
        return image
    }

    private static func cacheName(_ bundleId: String) -> String {
        "icon-\(NSHash.sha256Hex(Data(bundleId.utf8)).prefix(16)).dat"
    }
}
