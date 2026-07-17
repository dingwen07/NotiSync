package net.extrawdw.notisync.daemon

import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.localapi.AcceptedResponse
import net.extrawdw.notisync.localapi.LocalNotificationAction
import net.extrawdw.notisync.localapi.NotificationPhase
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotificationAction
import net.extrawdw.notisync.protocol.NotificationLiveUpdate
import net.extrawdw.notisync.protocol.NotificationProgress
import net.extrawdw.notisync.protocol.NotificationStyle
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SecureChannel

@Serializable
data class PendingNotification(
    val id: String,
    val sourceKey: String,
    val request: NotificationRequest,
    val postTime: Long,
    val acceptedAt: Long,
    /** Daemon-generated generation capabilities, persisted with the outbox item. */
    val actions: List<WireAction> = emptyList(),
)

interface NotificationOutbox {
    /** Must durably commit before returning. */
    fun enqueue(item: PendingNotification)
    fun peek(): PendingNotification?
    fun remove(id: String)
    fun retryLater(id: String)
}

class InMemoryNotificationOutbox : NotificationOutbox {
    private val lock = Any()
    private val pending = linkedMapOf<String, PendingNotification>()

    override fun enqueue(item: PendingNotification) = synchronized(lock) {
        val sameSource = pending.values.filter { it.sourceKey == item.sourceKey }
        if (sameSource.any {
                it.request.phase in setOf(NotificationPhase.COMPLETED, NotificationPhase.FAILED) &&
                    it.request.generation >= item.request.generation
            }
        ) return@synchronized
        when (item.request.phase) {
            NotificationPhase.COMPLETED, NotificationPhase.FAILED -> {
                if (sameSource.any { it.request.generation > item.request.generation }) return@synchronized
                pending.entries.removeIf { it.value.sourceKey == item.sourceKey }
            }
            NotificationPhase.PERIODIC -> {
                if (sameSource.any {
                        it.request.phase == NotificationPhase.PERIODIC &&
                            (it.request.generation > item.request.generation || it.postTime > item.postTime)
                    }
                ) return@synchronized
                pending.entries.removeIf {
                    it.value.sourceKey == item.sourceKey && it.value.request.phase == NotificationPhase.PERIODIC
                }
            }
            else -> Unit
        }
        pending[item.id] = item
    }

    override fun peek(): PendingNotification? = synchronized(lock) { pending.values.firstOrNull() }

    override fun remove(id: String) = synchronized(lock) { pending.remove(id); Unit }

    override fun retryLater(id: String) = synchronized(lock) {
        val item = pending.remove(id) ?: return@synchronized
        pending[id] = item
    }
}

interface NotificationMeshSender {
    val clientId: ClientId
    suspend fun send(item: PendingNotification)
}

/** Protocol mapping and routing policy; Run lifecycle knowledge stays on the local side. */
class SecureChannelNotificationSender(
    private val channel: SecureChannel,
) : NotificationMeshSender {
    override val clientId: ClientId get() = channel.clientId

    override suspend fun send(item: PendingNotification) {
        val notification = item.toCapturedNotification(clientId)
        val recipientCount = if (item.request.phase == NotificationPhase.PERIODIC) {
            channel.send(
                typ = MessageType.DATA_SYNC,
                body = ProtocolCodec.encodeToCbor(
                    DataSync(DataSyncKind.NOTIFICATION, notification = notification.copy(silentUpdate = true)),
                ),
                scope = Recipients.OwnMeshFiltered(
                    requiredCapabilities = setOf(
                        Capability.DISPLAY,
                        Capability.CAPABILITY_ROUTING_V1,
                        Capability.PUSH_FILTERING,
                        Capability.DISPLAY_NOTIFICATION_UPDATES,
                    ),
                    requireCapabilityRoutingV1 = true,
                ),
                urgency = Urgency.NORMAL,
            )
        } else {
            channel.send(
                typ = MessageType.NOTIFICATION,
                body = ProtocolCodec.encodeToCbor(notification),
                scope = Recipients.OwnMeshFiltered(requiredCapabilities = setOf(Capability.DISPLAY)),
                urgency = Urgency.HIGH,
            )
        }
        check(recipientCount > 0) { "no eligible, sealable notification recipient is currently available" }
    }
}

class NotificationDispatcher(
    private val sessions: LocalSessionRegistry,
    private val outbox: NotificationOutbox,
    private val sender: NotificationMeshSender,
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
) : AutoCloseable {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)
    private var worker: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        worker = scope.launch {
            wake.trySend(Unit)
            var consecutiveFailures = 0
            while (isActive) {
                val item = outbox.peek()
                if (item == null) {
                    wake.receive()
                    continue
                }
                try {
                    sender.send(item)
                    outbox.remove(item.id)
                    consecutiveFailures = 0
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    outbox.retryLater(item.id)
                    consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(5)
                    delay((1_000L shl (consecutiveFailures - 1)).coerceAtMost(30_000L))
                }
            }
        }
    }

    fun accept(request: NotificationRequest, bearer: String?, peer: LocalPeer): AcceptedResponse {
        validateNotification(request)
        // Validate the complete wire projection before mutating the session's durable generation/action
        // mapping. A rejected request must not invalidate actions from the last accepted notification.
        val acceptedAt = clock.millis()
        val registration = sessions.registerNotification(request, bearer, peer, acceptedAt)
        val item = PendingNotification(
            id = UUID.randomUUID().toString(),
            sourceKey = registration.sourceKey,
            request = request,
            postTime = registration.postTime,
            acceptedAt = acceptedAt,
            actions = registration.actions,
        )
        outbox.enqueue(item)
        wake.trySend(Unit)
        return AcceptedResponse(item.id, acceptedAt)
    }

    override fun close() {
        worker?.cancel()
        wake.close()
    }

    private fun validateNotification(request: NotificationRequest) {
        require(request.title.length <= 256) { "notification title is too long" }
        require(request.text.length <= 4_096) { "notification text is too long" }
        require((request.expandedText?.length ?: 0) <= 32_768) { "expanded notification text is too long" }
        require((request.shortCriticalText?.length ?: 0) < 7) {
            "shortCriticalText must contain fewer than 7 characters"
        }
        require(request.metadata.size <= 32) { "notification metadata is too large" }
    }
}

private fun PendingNotification.toCapturedNotification(clientId: ClientId): CapturedNotification {
    val isTerminal = request.phase == NotificationPhase.COMPLETED || request.phase == NotificationPhase.FAILED
    val quiet = request.silent || request.phase == NotificationPhase.INITIAL ||
        request.phase == NotificationPhase.PERIODIC || request.phase == NotificationPhase.RESUMED
    return CapturedNotification(
        sourceClientId = clientId,
        sourceKey = sourceKey,
        packageName = "net.extrawdw.notisync.run",
        appLabel = request.metadata["appLabel"] ?: "NotiSync Run",
        title = request.title,
        text = request.text,
        bigText = request.expandedText,
        style = if (request.expandedText != null) NotificationStyle.BIG_TEXT else NotificationStyle.DEFAULT,
        category = MirrorCategory.PROGRESS,
        importance = MirrorImportance.HIGH,
        postTime = postTime,
        isOngoing = request.ongoing && !isTerminal,
        isClearable = request.clearable || isTerminal,
        onlyAlertOnce = quiet,
        silentUpdate = quiet,
        actions = actions.map { action ->
            NotificationAction(
                index = action.index,
                title = action.title,
                remoteInput = action.remoteInput,
                remoteInputLabel = action.remoteInputLabel,
                actionGeneration = action.generation,
                actionToken = action.actionToken,
            )
        },
        liveUpdate = NotificationLiveUpdate(
            requestPromotedOngoing = request.requestPromotedOngoing && !isTerminal,
            progress = request.progress?.takeUnless { isTerminal }?.let {
                NotificationProgress(it.current, it.total, it.indeterminate)
            },
            shortCriticalText = request.shortCriticalText,
        ),
    )
}
