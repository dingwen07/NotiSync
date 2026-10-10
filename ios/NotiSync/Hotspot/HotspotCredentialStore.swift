import Foundation
import Security

/// App-process-only Keychain storage. After-first-unlock permits background status delivery;
/// ThisDeviceOnly prevents credentials from migrating to another device. No App Group database
/// or NSE reads are needed. The actor serializes timestamp comparisons and atomic item updates.
actor HotspotCredentialStore {
    static let shared = HotspotCredentialStore()

    private struct StorageError: Error { let status: OSStatus }

    private func query(_ deviceId: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: NotiSyncConfig.bundleId + ".hotspot",
         kSecAttrAccount as String: deviceId,
         kSecAttrSynchronizable as String: false]
    }

    func load(_ deviceId: String) throws -> SavedHotspot? {
        var query = query(deviceId)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw StorageError(status: status) }
        let saved = try JSONDecoder().decode(SavedHotspot.self, from: data)
        return saved.usable ? saved : nil
    }

    func save(_ saved: SavedHotspot, for deviceId: String) throws -> SavedHotspot {
        if let old = try load(deviceId), old.updatedAt >= saved.updatedAt { return old }
        let attributes: [String: Any] = [
            kSecValueData as String: try JSONEncoder().encode(saved),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let query = query(deviceId)
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw StorageError(status: status) }
        return saved
    }
}
