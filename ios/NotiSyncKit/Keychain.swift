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

/// Durable high-water state for recovery from local-container loss. iOS may keep Keychain items while
/// removing SwiftData/App Group files, so this must live beside the identity key rather than in SwiftData.
nonisolated enum KeychainEpochStore {
    private static let latestEpochAccount = "self.latest.keyepoch"
    private static let apnsRouteResetPendingAccount = "apns.route.reset.pending"

    static func latestEpoch() -> Int {
        var latest = 0
        var sawDefault = false
        for group in [NotiSyncConfig.keychainGroup, nil] as [String?] {
            guard let data = Keychain.get(account: latestEpochAccount, accessGroup: group),
                  let text = String(data: data, encoding: .utf8),
                  let value = Int(text) else {
                continue
            }
            if group == nil { sawDefault = true }
            latest = max(latest, value)
        }
        if sawDefault, latest > 0 { writeLatestEpoch(latest) }   // migrate legacy/default-only value into shared group.
        return max(latest, 0)
    }

    static func record(epoch: Int) {
        guard epoch > latestEpoch() else { return }
        writeLatestEpoch(epoch)
    }

    static func apnsRouteResetPending() -> Bool {
        Keychain.get(account: apnsRouteResetPendingAccount, accessGroup: NotiSyncConfig.keychainGroup) != nil
            || Keychain.get(account: apnsRouteResetPendingAccount, accessGroup: nil) != nil
    }

    static func setAPNsRouteResetPending(_ pending: Bool) {
        if pending {
            let value = Data("1".utf8)
            let wroteShared = Keychain.set(value, account: apnsRouteResetPendingAccount, accessGroup: NotiSyncConfig.keychainGroup)
            let wroteDefault = Keychain.set(value, account: apnsRouteResetPendingAccount, accessGroup: nil)
            _ = wroteShared || wroteDefault
        } else {
            Keychain.delete(account: apnsRouteResetPendingAccount, accessGroup: NotiSyncConfig.keychainGroup)
            Keychain.delete(account: apnsRouteResetPendingAccount, accessGroup: nil)
        }
    }

    private static func writeLatestEpoch(_ epoch: Int) {
        let data = Data(String(max(epoch, 0)).utf8)
        let wroteShared = Keychain.set(data, account: latestEpochAccount, accessGroup: NotiSyncConfig.keychainGroup)
        let wroteDefault = Keychain.set(data, account: latestEpochAccount, accessGroup: nil)
        _ = wroteShared || wroteDefault
    }
}
