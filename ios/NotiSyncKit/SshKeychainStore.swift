import Foundation
import Security

/// App-and-NSE, device-only storage for complete managed-key records. Callers encode the private key and its
/// matching public metadata into one bounded record. Successful enumeration lets reconciliation distinguish
/// missing private material from a temporarily unavailable Keychain without exposing any private bytes.
nonisolated enum SshManagedKeyKeychainStore {
    enum LoadResult: Sendable {
        case found(Data)
        case missing
        case unavailable(OSStatus)
    }

    enum KeyIdEnumeration: Sendable {
        case available([String])
        case unavailable(OSStatus)
    }

    private static let service = "net.extrawdw.apps.NotiSync.ssh.keys.v1"
    private static let accountPrefix = "ssh.key."
    private static let maximumRecordBytes = 64 * 1024

    @discardableResult
    static func save(_ data: Data, keyId: String) -> Bool {
        guard validOperationId(keyId), !data.isEmpty, data.count <= maximumRecordBytes else { return false }
        return SshKeychainItemStore.save(
            data,
            service: service,
            account: accountPrefix + keyId,
            accessGroup: NotiSyncConfig.sshKeychainGroup,
            accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        )
    }

    static func load(keyId: String) -> LoadResult {
        guard validOperationId(keyId) else { return .missing }
        switch SshKeychainItemStore.load(
            service: service,
            account: accountPrefix + keyId,
            accessGroup: NotiSyncConfig.sshKeychainGroup
        ) {
        case .found(let data) where data.count <= maximumRecordBytes:
            return .found(data)
        case .found:
            return .unavailable(errSecDecode)
        case .missing:
            return .missing
        case .unavailable(let status):
            return .unavailable(status)
        }
    }

    @discardableResult
    static func delete(keyId: String) -> Bool {
        guard validOperationId(keyId) else { return false }
        return SshKeychainItemStore.delete(
            service: service,
            account: accountPrefix + keyId,
            accessGroup: NotiSyncConfig.sshKeychainGroup
        )
    }

    /// Keychain is authoritative for managed private material. A failed query must not be confused with an
    /// empty Keychain, because doing so could remove every public catalog row during a transient lock/error.
    static func keyIds() -> KeyIdEnumeration {
        switch SshKeychainItemStore.accounts(service: service, accessGroup: NotiSyncConfig.sshKeychainGroup) {
        case .available(let accounts):
            return .available(accounts.compactMap { account -> String? in
                guard account.hasPrefix(accountPrefix) else { return nil }
                let id = String(account.dropFirst(accountPrefix.count))
                return validOperationId(id) ? id : nil
            }.sorted())
        case .unavailable(let status):
            return .unavailable(status)
        }
    }

    private static func validOperationId(_ value: String) -> Bool {
        value.utf8.count == 32 && value.utf8.allSatisfy {
            ($0 >= 48 && $0 <= 57) || ($0 >= 97 && $0 <= 102)
        }
    }
}

/// Short-lived cross-process staging for authenticated inbound sign/import request bytes and durable responses.
/// It uses the dedicated app-and-NSE SSH group so Notification Content cannot read SSH payloads. The app deletes
/// each item on its terminal transition or after response delivery.
nonisolated enum SshPendingSecretStore {
    private static let service = "net.extrawdw.apps.NotiSync.ssh.staging.v1"
    private static let accountPrefix = "ssh.pending."
    private static let maximumSecretBytes = 256 * 1024

    static func account(requestId: String) -> String? {
        guard validOperationId(requestId) else { return nil }
        return accountPrefix + requestId
    }

    @discardableResult
    static func save(_ data: Data, account: String) -> Bool {
        guard validAccount(account), !data.isEmpty, data.count <= maximumSecretBytes else { return false }
        return SshKeychainItemStore.save(
            data,
            service: service,
            account: account,
            accessGroup: NotiSyncConfig.sshKeychainGroup,
            accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        )
    }

    static func load(account: String) -> Data? {
        guard validAccount(account) else { return nil }
        guard case .found(let data) = SshKeychainItemStore.load(
            service: service,
            account: account,
            accessGroup: NotiSyncConfig.sshKeychainGroup
        ), data.count <= maximumSecretBytes else { return nil }
        return data
    }

    @discardableResult
    static func delete(account: String) -> Bool {
        guard validAccount(account) else { return false }
        return SshKeychainItemStore.delete(
            service: service,
            account: account,
            accessGroup: NotiSyncConfig.sshKeychainGroup
        )
    }

    /// Removes crash-orphaned staging items that no longer have a durable request row.
    static func deleteAll(except retainedAccounts: Set<String>) {
        deleteLegacySharedGroupItems()
        let retained = retainedAccounts.filter(validAccount)
        guard case .available(let accounts) = SshKeychainItemStore.accounts(
            service: service,
            accessGroup: NotiSyncConfig.sshKeychainGroup
        ) else { return }
        for account in accounts where validAccount(account) && !retained.contains(account) {
            _ = delete(account: account)
        }
    }

    /// Pre-release development builds briefly used the general app/extension group for this service. Never
    /// read those legacy SSH secrets into the new flow; remove them best-effort so Notification Content does
    /// not retain access after an upgrade-in-place on a test device.
    private static func deleteLegacySharedGroupItems() {
        guard NotiSyncConfig.keychainGroup != NotiSyncConfig.sshKeychainGroup,
              case .available(let accounts) = SshKeychainItemStore.accounts(
                  service: service,
                  accessGroup: NotiSyncConfig.keychainGroup
              ) else { return }
        for account in accounts where validAccount(account) {
            _ = SshKeychainItemStore.delete(
                service: service,
                account: account,
                accessGroup: NotiSyncConfig.keychainGroup
            )
        }
    }

    private static func validAccount(_ value: String) -> Bool {
        guard value.hasPrefix(accountPrefix) else { return false }
        return validOperationId(String(value.dropFirst(accountPrefix.count)))
    }

    private static func validOperationId(_ value: String) -> Bool {
        value.utf8.count == 32 && value.utf8.allSatisfy {
            ($0 >= 48 && $0 <= 57) || ($0 >= 97 && $0 <= 102)
        }
    }
}

private nonisolated enum SshKeychainItemStore {
    enum LoadResult: Sendable {
        case found(Data)
        case missing
        case unavailable(OSStatus)
    }

    enum AccountEnumeration: Sendable {
        case available([String])
        case unavailable(OSStatus)
    }

    static func save(
        _ data: Data,
        service: String,
        account: String,
        accessGroup: String,
        accessible: CFString
    ) -> Bool {
        let query = baseQuery(service: service, account: account, accessGroup: accessGroup)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: accessible,
        ]
        let update = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if update == errSecSuccess { return true }
        guard update == errSecItemNotFound else { return false }

        var add = query
        for (key, value) in attributes { add[key] = value }
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        if addStatus == errSecSuccess { return true }
        // Another process may have inserted the same deterministic staging account after our first update.
        return addStatus == errSecDuplicateItem &&
            SecItemUpdate(query as CFDictionary, attributes as CFDictionary) == errSecSuccess
    }

    static func load(service: String, account: String, accessGroup: String) -> LoadResult {
        var query = baseQuery(service: service, account: account, accessGroup: accessGroup)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return .missing }
        guard status == errSecSuccess else { return .unavailable(status) }
        guard let data = item as? Data else { return .unavailable(errSecDecode) }
        return .found(data)
    }

    static func delete(service: String, account: String, accessGroup: String) -> Bool {
        let status = SecItemDelete(
            baseQuery(service: service, account: account, accessGroup: accessGroup) as CFDictionary
        )
        return status == errSecSuccess || status == errSecItemNotFound
    }

    static func accounts(service: String, accessGroup: String) -> AccountEnumeration {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccessGroup as String: accessGroup,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        // Keep this mutable to match Security.framework's CFDictionary API without sharing mutable state.
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        query.removeAll(keepingCapacity: false)
        if status == errSecItemNotFound { return .available([]) }
        guard status == errSecSuccess else { return .unavailable(status) }
        if let rows = item as? [[String: Any]] {
            return .available(rows.compactMap { $0[kSecAttrAccount as String] as? String })
        }
        if let row = item as? [String: Any], let account = row[kSecAttrAccount as String] as? String {
            return .available([account])
        }
        return .unavailable(errSecDecode)
    }

    private static func baseQuery(service: String, account: String, accessGroup: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: accessGroup,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
        ]
    }
}
