import Foundation

/// Rotating app host sessions. The existing session remains usable during the final 30-second overlap.
@MainActor enum BrokerPairingHost {
    private enum Event: Sendable {
        case ready(Int, BrokerPairingLink), rotate(Int), expired(Int), finished(Int, PairingCandidate?), retry
    }

    static func awaitCard(
        exchange: @escaping @Sendable (@escaping @Sendable (BrokerPairingLink) -> Void) async throws -> PairingCandidate,
        onLink: (BrokerPairingLink?) -> Void,
        onUnavailable: () -> Void
    ) async throws -> PairingCandidate {
        let (events, continuation) = AsyncStream<Event>.makeStream()
        var sessions: [Int: Task<Void, Never>] = [:]
        var links: [Int: BrokerPairingLink] = [:]
        var expired: Set<Int> = []
        var newest = 0, displayed: Int?
        var rotationDue = false
        var retry: Task<Void, Never>?
        var retrySeconds: TimeInterval = 2

        func displayLatest() {
            let next = links.keys.max()
            if next != displayed { displayed = next; onLink(next.flatMap { links[$0] }) }
        }
        func start() {
            precondition(sessions.count < 2)
            newest += 1; rotationDue = false
            let id = newest
            sessions[id] = Task {
                let timer = Task {
                    do {
                        try await Task.sleep(for: .seconds(BrokerPairingLink.sessionSeconds - 30))
                        continuation.yield(.rotate(id))
                        try await Task.sleep(for: .seconds(30))
                        continuation.yield(.expired(id))
                    } catch { }
                }
                defer { timer.cancel() }
                let candidate = try? await exchange { continuation.yield(.ready(id, $0)) }
                continuation.yield(.finished(id, Task.isCancelled ? nil : candidate))
            }
        }
        let candidate: PairingCandidate? = await withTaskCancellationHandler {
            onLink(nil)
            start()
            for await event in events {
                if Task.isCancelled { break }
                switch event {
                case let .ready(id, link):
                    if sessions[id] != nil && !expired.contains(id) {
                        links[id] = link; retrySeconds = 2; displayLatest()
                    }
                case let .rotate(id):
                    if id == newest { rotationDue = true; if sessions.count < 2 { start() } }
                case let .expired(id):
                    if sessions[id] != nil {
                        expired.insert(id); links[id] = nil; displayLatest()
                        sessions[id]?.cancel()
                        if links.isEmpty { onUnavailable() }
                    }
                case let .finished(id, received):
                    await sessions.removeValue(forKey: id)?.value
                    links[id] = nil; expired.remove(id)
                    if let received { return received }
                    displayLatest()
                    if links.isEmpty { onUnavailable() }
                    if rotationDue && sessions[newest] != nil && sessions.count < 2 { start() }
                    else if id == newest && retry == nil {
                        let wait = retrySeconds
                        retry = Task {
                            do { try await Task.sleep(for: .seconds(wait)); continuation.yield(.retry) } catch { }
                        }
                        retrySeconds = min(retrySeconds * 2, 30)
                    }
                case .retry:
                    retry = nil
                    if sessions.count < 2 { start() }
                }
            }
            return nil
        } onCancel: { continuation.finish() }
        for task in sessions.values { task.cancel() }
        retry?.cancel()
        for task in sessions.values { await task.value }
        await retry?.value
        continuation.finish(); onLink(nil)
        try Task.checkCancellation()
        guard let candidate else { throw CancellationError() }
        return candidate
    }
}
