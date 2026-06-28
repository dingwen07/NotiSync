import Foundation

/// Content-addressed local store of *decrypted* private-graphic bytes, keyed by the hex SHA-256 of the
/// plaintext (`assetHash`). In the App Group container so the app (which fetches + decrypts, having the
/// JWT) and the NSE (which can only read) share one cache. LRU-bounded. Mirrors `assets/AssetCache.kt`.
nonisolated enum AssetCache {
    private static let maxBytes: UInt64 = 64 * 1024 * 1024

    private static func dir() -> URL? {
        guard let base = AppGroupStore.containerURL else { return nil }
        let d = base.appendingPathComponent("assets", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    static func read(_ assetHash: String) -> Data? {
        guard let f = dir()?.appendingPathComponent(assetHash), let data = try? Data(contentsOf: f) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: f.path) // LRU touch
        return data
    }

    static func write(_ assetHash: String, _ bytes: Data) {
        guard !assetHash.isEmpty, let f = dir()?.appendingPathComponent(assetHash) else { return }
        if FileManager.default.fileExists(atPath: f.path) { return } // content-addressed: already present
        guard (try? bytes.write(to: f, options: .atomic)) != nil else { return }
        evictIfNeeded()
    }

    private static func evictIfNeeded() {
        guard let d = dir() else { return }
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: d, includingPropertiesForKeys: keys) else { return }
        var total = files.reduce(UInt64(0)) { $0 + UInt64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        guard total > maxBytes else { return }
        let oldestFirst = files.sorted {
            let a = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            let b = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return a < b
        }
        for f in oldestFirst where total > maxBytes {
            let size = UInt64((try? f.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            if (try? FileManager.default.removeItem(at: f)) != nil { total -= size }
        }
    }
}
