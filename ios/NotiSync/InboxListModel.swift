import Combine
import Foundation
import SwiftData

/// Windowed pager for the (unbounded) Inbox list: the UI holds only the scrolled-to window instead of a
/// live `@Query` materializing every row. The plain listing and the app filter page straight from
/// SwiftData (`fetchLimit`/`fetchOffset`); a text search pages message ids from the FTS5 sidecar and
/// hydrates the rows by id. Mutations don't invalidate anything automatically — the runtime bumps
/// `inboxRevision` after every Inbox write and the view calls `refreshAfterChange()`; in-place field
/// changes on loaded rows (e.g. `isDismissed`) still propagate through `@Model` observation.
@MainActor
final class InboxListModel: ObservableObject {
    static let pageSize = 100

    /// "Show one app" filter, keyed the same way the FTS sidecar keys `app_key` (iOS bundle id when the
    /// origin is an iPhone app, source package name otherwise). Equality ignores the display label.
    struct AppFilter: Equatable {
        let iosBundleId: String?
        let packageName: String
        let label: String

        init(_ n: InboxNotification) {
            iosBundleId = (n.iosBundleId?.isEmpty == false) ? n.iosBundleId : nil
            packageName = n.packageName
            label = n.appLabel
        }

        var appKey: String { InboxSearchEntry.appKey(iosBundleId: iosBundleId, packageName: packageName) }

        static func == (lhs: Self, rhs: Self) -> Bool { lhs.appKey == rhs.appKey }
    }

    @Published private(set) var rows: [InboxNotification] = []
    /// Rows matching the ACTIVE query — with no search/filter that's the whole Inbox (the Delete All count).
    @Published private(set) var totalCount = 0
    /// Undismissed rows within the active app-filter scope (search text ignored) — drives the
    /// "Mark … as Read" menu item, whose action uses the same scope.
    @Published private(set) var unreadCount = 0
    @Published private(set) var hasMore = false
    /// False until the first fetch lands, so the empty-state overlay doesn't flash during initial load.
    @Published private(set) var hasLoaded = false
    /// The last search text handed to `apply` (untrimmed) — the view debounces only when the text
    /// actually changed, so filter/unread toggles apply instantly.
    private(set) var appliedSearch = ""

    private var modelContext: ModelContext?
    private var searchIndex: InboxSearchIndex?
    private var searchText = ""
    private var appFilter: AppFilter?
    private var unreadOnly = false
    /// Paging cursor in QUERY space: FTS matches consumed for a search (a stale index id that hydrates to
    /// no row still advances it), plain rows fetched otherwise.
    private var nextOffset = 0
    private var isLoadingMore = false
    /// Discards results of a superseded fetch: `reload` bumps it before awaiting, so an in-flight page
    /// for the previous query/window can never land on top of the new one.
    private var generation = 0

    func configure(context: ModelContext, index: InboxSearchIndex) {
        guard modelContext == nil else { return }
        modelContext = context
        searchIndex = index
    }

    /// Apply a new search text + app filter + unread toggle and reload from the top.
    func apply(search: String, filter: AppFilter?, unreadOnly: Bool) async {
        appliedSearch = search
        searchText = search.trimmingCharacters(in: .whitespacesAndNewlines)
        appFilter = filter
        self.unreadOnly = unreadOnly
        await reload(limit: Self.pageSize)
    }

    /// Re-run the active query over the already-scrolled window (an arrival, dismissal, or delete-all
    /// changed the Inbox) — catches inserts, removals, and order changes without resetting scroll.
    func refreshAfterChange() {
        guard modelContext != nil else { return }
        Task { await reload(limit: max(nextOffset, Self.pageSize)) }
    }

    func loadMore() {
        guard hasMore, !isLoadingMore, modelContext != nil else { return }
        isLoadingMore = true
        Task {
            defer { isLoadingMore = false }
            let requestGeneration = generation
            let page = await fetchPage(offset: nextOffset, limit: Self.pageSize)
            guard requestGeneration == generation else { return }
            rows += page.rows
            nextOffset += page.consumed
            hasMore = page.mayHaveMore
        }
    }

    private func reload(limit: Int) async {
        generation += 1
        let requestGeneration = generation
        let page = await fetchPage(offset: 0, limit: limit)
        let total = await fetchTotal()
        let unread = fetchUnreadCount()
        guard requestGeneration == generation else { return }
        rows = page.rows
        nextOffset = page.consumed
        hasMore = page.mayHaveMore
        totalCount = total
        unreadCount = unread
        hasLoaded = true
    }

    private struct Page {
        var rows: [InboxNotification] = []
        var consumed = 0
        var mayHaveMore = false
    }

    private func fetchPage(offset: Int, limit: Int) async -> Page {
        guard let modelContext else { return Page() }
        if let match = InboxSearchIndex.matchExpression(for: searchText) {
            guard let searchIndex else { return Page() }
            let ids = await searchIndex.search(matching: match, appKey: appFilter?.appKey,
                                               limit: limit, offset: offset)
            let byId = Dictionary(fetchByIds(ids).map { ($0.messageId, $0) }, uniquingKeysWith: { first, _ in first })
            return Page(rows: ids.compactMap { byId[$0] }, consumed: ids.count, mayHaveMore: ids.count == limit)
        }
        var descriptor = FetchDescriptor<InboxNotification>(
            predicate: Self.predicate(filter: appFilter, unreadOnly: unreadOnly), sortBy: Self.sortOrder)
        descriptor.fetchLimit = limit
        descriptor.fetchOffset = offset
        let fetched = (try? modelContext.fetch(descriptor)) ?? []
        return Page(rows: fetched, consumed: fetched.count, mayHaveMore: fetched.count == limit)
    }

    /// The message-id tiebreak keeps offset paging stable when rows share a `receivedAt`.
    private static let sortOrder = [SortDescriptor(\InboxNotification.receivedAt, order: .reverse),
                                    SortDescriptor(\InboxNotification.messageId)]

    private func fetchByIds(_ ids: [String]) -> [InboxNotification] {
        guard let modelContext, !ids.isEmpty else { return [] }
        // Unread Only composes with a text search here, at hydration (the FTS sidecar doesn't track the
        // mutable read flag) — a page of ids can hydrate short, but the cursor still advances by ids.
        let predicate: Predicate<InboxNotification> = unreadOnly
            ? #Predicate { ids.contains($0.messageId) && !$0.isDismissed }
            : #Predicate { ids.contains($0.messageId) }
        return (try? modelContext.fetch(FetchDescriptor<InboxNotification>(predicate: predicate))) ?? []
    }

    private func fetchTotal() async -> Int {
        guard let modelContext else { return 0 }
        if let match = InboxSearchIndex.matchExpression(for: searchText) {
            return await searchIndex?.matchCount(matching: match, appKey: appFilter?.appKey) ?? 0
        }
        return (try? modelContext.fetchCount(FetchDescriptor<InboxNotification>(
            predicate: Self.predicate(filter: appFilter, unreadOnly: unreadOnly)))) ?? 0
    }

    private func fetchUnreadCount() -> Int {
        guard let modelContext else { return 0 }
        return (try? modelContext.fetchCount(FetchDescriptor<InboxNotification>(
            predicate: Self.predicate(filter: appFilter, unreadOnly: true)))) ?? 0
    }

    /// The SwiftData predicate for an app-filter scope, optionally narrowed to unread. Also the scope
    /// of the "Mark … as Read" menu action, so what it marks is exactly what the list shows.
    /// One `#Predicate` per return — combining two macro expansions in one expression (a ternary)
    /// times out the type checker.
    static func predicate(filter: AppFilter?, unreadOnly: Bool) -> Predicate<InboxNotification>? {
        guard let filter else {
            if unreadOnly {
                return #Predicate { !$0.isDismissed }
            }
            return nil
        }
        if let bundleId = filter.iosBundleId {
            if unreadOnly {
                return #Predicate { $0.iosBundleId == bundleId && !$0.isDismissed }
            }
            return #Predicate { $0.iosBundleId == bundleId }
        }
        let packageName = filter.packageName
        if unreadOnly {
            return #Predicate {
                $0.packageName == packageName && ($0.iosBundleId == nil || $0.iosBundleId == "") && !$0.isDismissed
            }
        }
        return #Predicate {
            $0.packageName == packageName && ($0.iosBundleId == nil || $0.iosBundleId == "")
        }
    }
}
