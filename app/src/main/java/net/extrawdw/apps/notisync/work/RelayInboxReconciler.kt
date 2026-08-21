package net.extrawdw.apps.notisync.work

import net.extrawdw.apps.notisync.data.MessageStore
import net.extrawdw.apps.notisync.data.RelayInboxItem
import net.extrawdw.notisync.peer.channel.DeliveryOutcome
import net.extrawdw.notisync.peer.channel.PreparationOutcome
import net.extrawdw.notisync.peer.channel.PreparedInbound
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.peer.channel.safeToAck
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.DismissEvent
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reduces a completely staged relay snapshot before applying it. Dismissals run first and only the
 * newest notification/update per source key is dispatched, so an old backlog never produces a
 * post-then-cancel burst. The secure channel still owns verification, handler dispatch, and dedup.
 */
internal class RelayInboxReconciler(
    private val channel: SecureChannel,
    private val store: MessageStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class LifecycleKey(val sourceClientId: String, val sourceKey: String)

    private sealed interface Classified {
        data class Notification(val key: LifecycleKey, val postTime: Long, val acceptedAt: Long) : Classified
        data class Dismissal(val key: LifecycleKey, val dismissedAt: Long, val acceptedAt: Long) : Classified
        data object Other : Classified
    }

    /** Return true when one or more rows remain retryable. */
    suspend fun reconcile(): Boolean {
        val highWater = store.relayInboxHighWater()
        if (highWater == 0L) return false

        // Keep only small classification metadata in memory. Encrypted envelopes remain in SQLite and
        // are read in bounded pages, so a 48-hour backlog never becomes one large heap allocation.
        val classified = HashMap<String, Classified>()
        val notificationWinners = HashMap<LifecycleKey, String>()
        val dismissalWinners = HashMap<LifecycleKey, String>()
        var retryNeeded = false

        forEachPage(highWater) { item ->
            val envelope = runCatching { ProtocolCodec.decodeFromCbor<Envelope>(item.envelope) }.getOrNull()
            if (envelope == null || envelope.messageId != item.messageId) {
                retryNeeded = true
            } else when (val outcome = channel.prepare(envelope, item.deliveryMode, item.acceptedAt)) {
                PreparationOutcome.Duplicate -> if (!finish(item, DeliveryOutcome.DUPLICATE)) retryNeeded = true
                PreparationOutcome.Dropped -> retryNeeded = true
                is PreparationOutcome.Ready -> {
                    val classification = classify(outcome.prepared, item)
                    classified[item.messageId] = classification
                    when (classification) {
                        is Classified.Notification -> selectNotificationWinner(
                            notificationWinners,
                            classified,
                            item.messageId,
                            classification,
                        )
                        is Classified.Dismissal -> selectDismissalWinner(
                            dismissalWinners,
                            classified,
                            item.messageId,
                            classification,
                        )
                        Classified.Other -> Unit
                    }
                }
            }
        }

        // Lifecycle ordering is deliberate: every dismissal commits its tombstone before any surviving
        // notification is offered to MirrorEngine's persistent postTime check.
        processClass(highWater, classified, { it is Classified.Dismissal }) { item, prepared, classification ->
            val dismissal = classification as Classified.Dismissal
            if (dismissalWinners[dismissal.key] == item.messageId) channel.deliverPrepared(prepared)
            else channel.completePreparedWithoutDispatch(prepared)
        }.also { retryNeeded = retryNeeded || it }

        processClass(highWater, classified, { it is Classified.Notification }) { item, prepared, classification ->
            val notification = classification as Classified.Notification
            if (notificationWinners[notification.key] == item.messageId) {
                channel.deliverPrepared(
                    prepared,
                    forceSilent = item.acceptedAt <= now() - STALE_RELAY_AGE_MS,
                )
            } else channel.completePreparedWithoutDispatch(prepared)
        }.also { retryNeeded = retryNeeded || it }

        processClass(highWater, classified, { it == Classified.Other }) { _, prepared, _ ->
            channel.deliverPrepared(prepared)
        }.also { retryNeeded = retryNeeded || it }
        return retryNeeded
    }

    private fun classify(prepared: PreparedInbound, item: RelayInboxItem): Classified {
        val msg = prepared.message
        if (!msg.senderOwnDevice) return Classified.Other
        return when (msg.typ) {
            MessageType.NOTIFICATION -> runCatching {
                ProtocolCodec.decodeFromCbor<CapturedNotification>(msg.body)
            }.getOrNull()?.let {
                Classified.Notification(
                    LifecycleKey(it.sourceClientId.value, it.sourceKey),
                    it.postTime,
                    item.acceptedAt,
                )
            } ?: Classified.Other
            MessageType.DISMISSAL -> runCatching {
                ProtocolCodec.decodeFromCbor<DismissEvent>(msg.body)
            }.getOrNull()?.let {
                Classified.Dismissal(
                    LifecycleKey(it.sourceClientId.value, it.sourceKey),
                    it.dismissedAt,
                    item.acceptedAt,
                )
            } ?: Classified.Other
            MessageType.DATA_SYNC -> runCatching {
                ProtocolCodec.decodeFromCbor<DataSync>(msg.body)
            }.getOrNull()?.takeIf { it.kind == DataSyncKind.NOTIFICATION }?.notification?.let {
                Classified.Notification(
                    LifecycleKey(it.sourceClientId.value, it.sourceKey),
                    it.postTime,
                    item.acceptedAt,
                )
            } ?: Classified.Other
            MessageType.ACTION -> Classified.Other
        }
    }

    private fun selectNotificationWinner(
        winners: MutableMap<LifecycleKey, String>,
        classified: Map<String, Classified>,
        messageId: String,
        candidate: Classified.Notification,
    ) {
        val priorId = winners[candidate.key]
        val prior = priorId?.let { classified[it] as? Classified.Notification }
        if (prior == null || compareNotification(candidate, messageId, prior, requireNotNull(priorId)) > 0) {
            winners[candidate.key] = messageId
        }
    }

    private fun selectDismissalWinner(
        winners: MutableMap<LifecycleKey, String>,
        classified: Map<String, Classified>,
        messageId: String,
        candidate: Classified.Dismissal,
    ) {
        val priorId = winners[candidate.key]
        val prior = priorId?.let { classified[it] as? Classified.Dismissal }
        if (prior == null || compareDismissal(candidate, messageId, prior, requireNotNull(priorId)) > 0) {
            winners[candidate.key] = messageId
        }
    }

    private fun compareNotification(
        left: Classified.Notification,
        leftId: String,
        right: Classified.Notification,
        rightId: String,
    ): Int = compareValuesBy(left, right, { it.postTime }, { it.acceptedAt })
        .takeIf { it != 0 } ?: leftId.compareTo(rightId)

    private fun compareDismissal(
        left: Classified.Dismissal,
        leftId: String,
        right: Classified.Dismissal,
        rightId: String,
    ): Int = compareValuesBy(left, right, { it.dismissedAt }, { it.acceptedAt })
        .takeIf { it != 0 } ?: leftId.compareTo(rightId)

    private suspend fun processClass(
        highWater: Long,
        classified: Map<String, Classified>,
        matches: (Classified) -> Boolean,
        dispatch: (RelayInboxItem, PreparedInbound, Classified) -> DeliveryOutcome,
    ): Boolean {
        var retryNeeded = false
        forEachPage(highWater) { item ->
            val classification = classified[item.messageId] ?: return@forEachPage
            if (!matches(classification)) return@forEachPage
            val envelope = runCatching { ProtocolCodec.decodeFromCbor<Envelope>(item.envelope) }.getOrNull()
            if (envelope == null || envelope.messageId != item.messageId) {
                retryNeeded = true
                return@forEachPage
            }
            when (val preparation = channel.prepare(envelope, item.deliveryMode, item.acceptedAt)) {
                PreparationOutcome.Duplicate -> if (!finish(item, DeliveryOutcome.DUPLICATE)) retryNeeded = true
                PreparationOutcome.Dropped -> retryNeeded = true
                is PreparationOutcome.Ready -> if (!finish(
                        item,
                        dispatch(item, preparation.prepared, classification),
                    )
                ) retryNeeded = true
            }
        }
        return retryNeeded
    }

    private suspend fun forEachPage(highWater: Long, block: (RelayInboxItem) -> Unit) {
        var afterRowId = 0L
        while (afterRowId < highWater) {
            currentCoroutineContext().ensureActive()
            val page = store.relayInboxPage(afterRowId, highWater)
            if (page.isEmpty()) return
            for (item in page) {
                currentCoroutineContext().ensureActive()
                block(item)
            }
            afterRowId = page.last().rowId
        }
    }

    private fun finish(item: RelayInboxItem, outcome: DeliveryOutcome): Boolean {
        if (!outcome.safeToAck) return false
        // SecureChannel records dedup before returning, but MessageDedup's historical Unit API cannot
        // report a failed SQLite write. Verify it here before making inbox deletion irreversible.
        if (!store.seen(item.messageId)) return false
        if (!item.earlyAck && !store.ensureAckQueued(item.messageId)) return false
        return store.deleteInbox(listOf(item.messageId))
    }

    private companion object {
        const val STALE_RELAY_AGE_MS = 2L * 60 * 60 * 1000
    }
}
