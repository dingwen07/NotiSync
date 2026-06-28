import Foundation
import Security

/// Low-level Keychain helpers for generic-password items (HPKE private keys, the broker JWT).
/// Items destined for the NSE use `accessGroup: NotiSyncConfig.keychainGroup` + an after-first-unlock
/// accessibility so a locked-device push can still decrypt.
nonisolated enum Keychain {
    @discardableResult
    static func set(_ data: Data, account: String,
                    accessGroup: String? = nil,
                    accessible: CFString = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) -> Bool {
        var delete: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: NotiSyncConfig.bundleId,
            kSecAttrAccount as String: account,
        ]
        if let accessGroup { delete[kSecAttrAccessGroup as String] = accessGroup }
        SecItemDelete(delete as CFDictionary)

        var add = delete
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = accessible
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static func get(account: String, accessGroup: String? = nil) -> Data? {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: NotiSyncConfig.bundleId,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        if let accessGroup { query[kSecAttrAccessGroup as String] = accessGroup }
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else { return nil }
        return item as? Data
    }

    @discardableResult
    static func delete(account: String, accessGroup: String? = nil) -> Bool {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: NotiSyncConfig.bundleId,
            kSecAttrAccount as String: account,
        ]
        if let accessGroup { query[kSecAttrAccessGroup as String] = accessGroup }
        return SecItemDelete(query as CFDictionary) == errSecSuccess
    }
}
