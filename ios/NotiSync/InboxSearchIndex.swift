import Foundation
import os
import SQLite3

/// One Inbox row's searchable projection, captured at write time so indexing never re-touches SwiftData.
nonisolated struct InboxSearchEntry: Sendable {
    var messageId: String
    var appLabel: String
    var appId: String
    var title: String
    var subtitle: String
    var body: String
    var receivedAt: Date
    var appKey: String

    init(_ n: InboxNotification) {
        messageId = n.messageId
        appLabel = n.appLabel
        appId = [n.packageName, n.iosBundleId ?? ""].filter { !$0.isEmpty }.joined(separator: " ")
        title = n.title ?? ""
        subtitle = n.subtitle ?? ""
        body = n.body ?? ""
        receivedAt = n.receivedAt
        appKey = Self.appKey(iosBundleId: n.iosBundleId, packageName: n.packageName)
    }

    /// The app-identity key both the sidecar's `app_key` column and the Inbox app filter group on: the iOS
    /// bundle id when the origin is an iPhone app, the source package name otherwise (peer-agnostic — the
    /// same app on two Android phones is one filter target).
    static func appKey(iosBundleId: String?, packageName: String) -> String {
        if let iosBundleId, !iosBundleId.isEmpty { return "ios:\(iosBundleId)" }
        return "and:\(packageName)"
    }
}

/// SQLite FTS5 sidecar index over the SwiftData Inbox — the Inbox's source of truth stays SwiftData
/// (the same store holds Devices/Activity/Settings, and the runtime's save discipline is built around
/// it); this file holds ONLY derived, disposable search rows in the system SQLite (FTS5 ships in iOS —
/// no third-party dependency). Consistency is best-effort by design: every Inbox mutation flows through
/// the runtime (the NSE never writes the store directly) and mirrors into here, and any drift — a crash
/// between store commit and index write, a store reset, a schema/tokenizer bump — is repaired by the
/// launch-time rebuild from the store (`reconcileSearchIndexIfNeeded`).
///
/// Two tables: `fts` (FTS5; the text columns) and `docs` (plain; message id ↔ fts rowid, plus the
/// receive time and app key so results can be ordered by recency and filtered by app without touching
/// SwiftData). CJK text is folded to one token per character at index AND query time (unicode61 would
/// otherwise treat an unspaced CJK run as a single token, making substring search impossible).
///
/// All state is confined to a private serial queue; writers are fire-and-forget (callers are on the main
/// actor, so enqueue order matches mutation order) and readers await their turn behind pending writes.
nonisolated final class InboxSearchIndex: @unchecked Sendable {
    private static let schemaVersion: Int32 = 1
    private static let log = Logger(subsystem: "net.extrawdw.apps.NotiSync", category: "search-index")
    private static let transientText = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private let queue = DispatchQueue(label: "net.extrawdw.apps.NotiSync.inbox-search", qos: .utility)
    private var db: OpaquePointer?
    private var openAttempted = false

    // MARK: - Writes

    /// Index a newly inserted Inbox row. Idempotent: a stale copy under the same message id (index drift)
    /// is replaced.
    func insert(_ entry: InboxSearchEntry) {
        queue.async { self.insertLocked(entry) }
    }

    /// A re-delivered message refreshed its row's `receivedAt` — keep result ordering in sync. The text
    /// columns never change on that path, so the FTS row is left alone.
    func touch(messageId: String, receivedAt: Date) {
        queue.async {
            guard self.openIfNeeded() else { return }
            _ = self.step("UPDATE docs SET received_at = ? WHERE message_id = ?",
                          [.real(receivedAt.timeIntervalSince1970), .text(messageId)])
        }
    }

    func deleteAll() {
        queue.async {
            guard self.openIfNeeded() else { return }
            self.exec("BEGIN IMMEDIATE")
            self.exec("DELETE FROM fts")
            self.exec("DELETE FROM docs")
            self.exec("COMMIT")
        }
    }

    /// Remove one app's rows (the app-filter-scoped Delete All).
    func deleteApp(appKey: String) {
        queue.async {
            guard self.openIfNeeded() else { return }
            self.exec("BEGIN IMMEDIATE")
            _ = self.step("DELETE FROM fts WHERE rowid IN (SELECT docid FROM docs WHERE app_key = ?)",
                          [.text(appKey)])
            _ = self.step("DELETE FROM docs WHERE app_key = ?", [.text(appKey)])
            self.exec("COMMIT")
        }
    }

    /// Remove specific rows (the search-scoped Delete All — the caller already holds the matched ids).
    func deleteMessages(_ messageIds: [String]) {
        guard !messageIds.isEmpty else { return }
        queue.async {
            guard self.openIfNeeded() else { return }
            self.exec("BEGIN IMMEDIATE")
            for messageId in messageIds { self.deleteRowLocked(messageId: messageId) }
            self.exec("COMMIT")
        }
    }

    /// Replace the whole index with `entries` (the launch-time self-heal). One transaction, so a crash
    /// mid-rebuild leaves the previous consistent state — and the next launch's count check retries.
    func rebuild(_ entries: [InboxSearchEntry]) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async {
                defer { continuation.resume() }
                guard self.openIfNeeded() else { return }
                self.exec("BEGIN IMMEDIATE")
                self.exec("DELETE FROM fts")
                self.exec("DELETE FROM docs")
                for entry in entries { self.insertRowLocked(entry) }
                self.exec("COMMIT")
            }
        }
    }

    // MARK: - Reads

    /// True when the index cannot answer for the store's current row count — new install over existing
    /// history, store reset, schema bump (which wiped the tables at open), or plain drift.
    func needsRebuild(expectedCount: Int) async -> Bool {
        await onQueue {
            guard self.openIfNeeded() else { return false }   // unusable index — a rebuild can't help
            return self.documentCountLocked() != expectedCount
        }
    }

    /// Message ids matching `matching` (an FTS5 MATCH expression from `matchExpression(for:)`), newest
    /// first, optionally restricted to one app. Paged — `offset` counts matches, not Inbox rows.
    func search(matching: String, appKey: String?, limit: Int, offset: Int) async -> [String] {
        await onQueue {
            guard self.openIfNeeded() else { return [] }
            let sql = """
                SELECT docs.message_id FROM fts JOIN docs ON docs.docid = fts.rowid
                WHERE fts MATCH ?1 AND (?2 IS NULL OR docs.app_key = ?2)
                ORDER BY docs.received_at DESC, docs.message_id LIMIT ?3 OFFSET ?4
                """
            let binds: [SQLValue] = [.text(matching), appKey.map(SQLValue.text) ?? .null,
                                     .int(Int64(limit)), .int(Int64(offset))]
            return self.withStatement(sql, binds) { statement in
                var ids: [String] = []
                while sqlite3_step(statement) == SQLITE_ROW {
                    if let text = sqlite3_column_text(statement, 0) { ids.append(String(cString: text)) }
                }
                return ids
            } ?? []
        }
    }

    func matchCount(matching: String, appKey: String?) async -> Int {
        await onQueue {
            guard self.openIfNeeded() else { return 0 }
            let sql = """
                SELECT count(*) FROM fts JOIN docs ON docs.docid = fts.rowid
                WHERE fts MATCH ?1 AND (?2 IS NULL OR docs.app_key = ?2)
                """
            let binds: [SQLValue] = [.text(matching), appKey.map(SQLValue.text) ?? .null]
            return self.withStatement(sql, binds) { statement in
                sqlite3_step(statement) == SQLITE_ROW ? Int(sqlite3_column_int64(statement, 0)) : 0
            } ?? 0
        }
    }

    // MARK: - Query building

    /// Build an FTS5 MATCH expression from the user's free text: alphanumeric runs become quoted prefix
    /// terms (`"noti"*`), CJK runs become per-character phrases (`"微 信"*`, matching the folding applied
    /// at index time), all AND-ed. Every term is quoted, so FTS5 operators/keywords in user text are
    /// inert. Returns nil when the text has no searchable content — the caller then shows the plain,
    /// non-search listing.
    static func matchExpression(for rawQuery: String) -> String? {
        let maxTerms = 8
        var terms: [String] = []
        var run = ""
        var runIsCJK = false
        func flushRun() {
            guard !run.isEmpty else { return }
            terms.append("\"" + (runIsCJK ? run.map(String.init).joined(separator: " ") : run) + "\"*")
            run = ""
        }
        for scalar in rawQuery.unicodeScalars {
            if terms.count >= maxTerms { break }
            let cjk = isCJK(scalar)
            guard cjk || CharacterSet.alphanumerics.contains(scalar) else { flushRun(); continue }
            if !run.isEmpty && cjk != runIsCJK { flushRun() }
            runIsCJK = cjk
            run.unicodeScalars.append(scalar)
        }
        flushRun()
        guard !terms.isEmpty else { return nil }
        return terms.prefix(maxTerms).joined(separator: " ")
    }

    /// Space-delimit each CJK character so unicode61 emits per-character tokens; a query's CJK phrase
    /// (`"微 信"`) then matches those tokens consecutively — substring search over unspaced CJK text.
    private static func foldedForIndex(_ text: String) -> String {
        guard text.unicodeScalars.contains(where: isCJK) else { return text }
        var out = ""
        out.reserveCapacity(text.count * 2)
        for scalar in text.unicodeScalars {
            if isCJK(scalar) {
                out.append(" ")
                out.unicodeScalars.append(scalar)
                out.append(" ")
            } else {
                out.unicodeScalars.append(scalar)
            }
        }
        return out
    }

    private static func isCJK(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x3040...0x30FF,      // Hiragana + Katakana
             0x3130...0x318F,      // Hangul compatibility jamo
             0x3400...0x4DBF,      // CJK extension A
             0x4E00...0x9FFF,      // CJK unified ideographs
             0xAC00...0xD7AF,      // Hangul syllables
             0xF900...0xFAFF,      // CJK compatibility ideographs
             0x20000...0x2FA1F:    // CJK extensions B+ and compatibility supplement
            return true
        default:
            return false
        }
    }

    // MARK: - Queue-confined SQLite plumbing

    private func onQueue<T: Sendable>(_ body: @escaping () -> T) async -> T {
        await withCheckedContinuation { continuation in
            queue.async { continuation.resume(returning: body()) }
        }
    }

    private func insertLocked(_ entry: InboxSearchEntry) {
        guard openIfNeeded() else { return }
        exec("BEGIN IMMEDIATE")
        deleteRowLocked(messageId: entry.messageId)
        insertRowLocked(entry)
        exec("COMMIT")
    }

    private func insertRowLocked(_ entry: InboxSearchEntry) {
        guard step("INSERT INTO fts(app_label, app_id, title, subtitle, body) VALUES(?,?,?,?,?)", [
            .text(Self.foldedForIndex(entry.appLabel)),
            .text(entry.appId),
            .text(Self.foldedForIndex(entry.title)),
            .text(Self.foldedForIndex(entry.subtitle)),
            .text(Self.foldedForIndex(entry.body)),
        ]) else { return }
        _ = step("INSERT OR REPLACE INTO docs(message_id, docid, received_at, app_key) VALUES(?,?,?,?)", [
            .text(entry.messageId), .int(sqlite3_last_insert_rowid(db)),
            .real(entry.receivedAt.timeIntervalSince1970), .text(entry.appKey),
        ])
    }

    private func deleteRowLocked(messageId: String) {
        let existing = withStatement("SELECT docid FROM docs WHERE message_id = ?", [.text(messageId)]) { statement in
            sqlite3_step(statement) == SQLITE_ROW ? sqlite3_column_int64(statement, 0) : nil
        }
        guard let docid = existing ?? nil else { return }
        _ = step("DELETE FROM fts WHERE rowid = ?", [.int(docid)])
        _ = step("DELETE FROM docs WHERE docid = ?", [.int(docid)])
    }

    private func documentCountLocked() -> Int {
        withStatement("SELECT count(*) FROM docs", []) { statement in
            sqlite3_step(statement) == SQLITE_ROW ? Int(sqlite3_column_int64(statement, 0)) : 0
        } ?? 0
    }

    /// Open (creating if needed) on first use. A failed open or table creation (e.g. FTS5 unavailable)
    /// disables the index for the process: writes no-op and searches return empty — search degrades,
    /// the Inbox itself is unaffected.
    private func openIfNeeded() -> Bool {
        if db != nil { return true }
        if openAttempted { return false }
        openAttempted = true
        guard let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return false
        }
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        let path = support.appendingPathComponent("InboxSearchIndex.sqlite").path
        var handle: OpaquePointer?
        guard sqlite3_open_v2(path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nil) == SQLITE_OK,
              let handle else {
            Self.log.error("open failed: \(handle.map { String(cString: sqlite3_errmsg($0)) } ?? "no handle", privacy: .public)")
            sqlite3_close(handle)
            return false
        }
        db = handle
        exec("PRAGMA journal_mode=WAL")
        exec("PRAGMA synchronous=NORMAL")   // the index is disposable — don't pay full-durability fsyncs
        // A schema/tokenizer change invalidates existing rows: drop and recreate, and the launch
        // reconcile repopulates (the count check sees an empty index).
        if userVersionLocked() != Self.schemaVersion {
            exec("DROP TABLE IF EXISTS fts")
            exec("DROP TABLE IF EXISTS docs")
            exec("PRAGMA user_version = \(Self.schemaVersion)")
        }
        let ready = exec("""
            CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(
                app_label, app_id, title, subtitle, body,
                tokenize='unicode61 remove_diacritics 2', prefix='2 3')
            """)
            && exec("""
            CREATE TABLE IF NOT EXISTS docs(
                message_id TEXT PRIMARY KEY,
                docid INTEGER UNIQUE NOT NULL,
                received_at REAL NOT NULL,
                app_key TEXT NOT NULL)
            """)
        if !ready {
            sqlite3_close(handle)
            db = nil
        }
        return db != nil
    }

    private func userVersionLocked() -> Int32 {
        withStatement("PRAGMA user_version", []) { statement in
            sqlite3_step(statement) == SQLITE_ROW ? sqlite3_column_int(statement, 0) : 0
        } ?? 0
    }

    private enum SQLValue {
        case text(String)
        case int(Int64)
        case real(Double)
        case null
    }

    @discardableResult
    private func exec(_ sql: String) -> Bool {
        var message: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(db, sql, nil, nil, &message) == SQLITE_OK else {
            Self.log.error("exec failed: \(message.map { String(cString: $0) } ?? "unknown", privacy: .public)")
            sqlite3_free(message)
            return false
        }
        return true
    }

    private func step(_ sql: String, _ binds: [SQLValue]) -> Bool {
        withStatement(sql, binds) { sqlite3_step($0) == SQLITE_DONE } ?? false
    }

    private func withStatement<T>(_ sql: String, _ binds: [SQLValue], _ body: (OpaquePointer) -> T) -> T? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
            Self.log.error("prepare failed: \(String(cString: sqlite3_errmsg(self.db)), privacy: .public)")
            return nil
        }
        defer { sqlite3_finalize(statement) }
        for (offset, value) in binds.enumerated() {
            let index = Int32(offset + 1)
            switch value {
            case .text(let string): sqlite3_bind_text(statement, index, string, -1, Self.transientText)
            case .int(let number): sqlite3_bind_int64(statement, index, number)
            case .real(let number): sqlite3_bind_double(statement, index, number)
            case .null: sqlite3_bind_null(statement, index)
            }
        }
        return body(statement)
    }
}
