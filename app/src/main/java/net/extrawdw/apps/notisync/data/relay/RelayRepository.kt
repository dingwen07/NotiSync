package net.extrawdw.apps.notisync.data.relay

/**
 * Storage-independent broker-custody receipt boundary.
 *
 * There is intentionally no Android inbox, ACK outbox, retry clock, attempt counter, or claim API. Methods propagate
 * cancellation and storage exceptions so neither can become a false ACK-ready result.
 */
interface RelayRepository {
    /** Atomically rechecks Operational continuity while resolving exact existing handled evidence. */
    suspend fun resolveHandled(
        continuity: RelayOperationalContinuity,
        messageId: String,
        authenticatedToken: AuthenticatedRelayToken,
    ): RelayHandledResolution

    /** Generic terminal/no-feature receipt only; owning feature mutations must finalize in their own transaction. */
    suspend fun finalize(
        continuity: RelayOperationalContinuity,
        request: RelayFinalizeRequest,
    ): RelayFinalizeOutcome

    /** Applies the frozen 72-hour handled-message retention independently of broker ACK state. */
    suspend fun pruneHandled(now: Long): Int
}

/**
 * Disposable metadata scratch for one finite broker drain.
 *
 * It is not custody or resumable work: callers clear it at every start, incomplete END, exception, cancellation,
 * and completed drain. Broker redelivery reconstructs it after process death.
 */
interface RelayBatchSessionRepository {
    suspend fun clearAtDrainBoundary(): Int

    suspend fun record(
        messageId: String,
        authenticatedToken: AuthenticatedRelayToken,
        presentation: RelayBatchPresentation,
    ): RelayBatchRecordOutcome

    suspend fun presentationPage(afterMessageId: String?, limit: Int): List<RelayBatchItem>

    suspend fun nonPresentationPage(afterMessageId: String?, limit: Int): List<RelayBatchItem>

    suspend fun find(messageId: String): RelayBatchItem?

    suspend fun deleteExact(expected: RelayBatchItem): Boolean
}
